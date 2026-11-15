package dev.adrianjost.lightbar

import android.accessibilityservice.AccessibilityService
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import androidx.compose.ui.platform.ComposeView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import org.json.JSONObject
import kotlin.math.roundToInt

/**
 * Accessibility service that listens for the trigger button, shows the overlay
 * and translates D-Pad presses into Home Assistant light commands.
 *
 * Keys while the overlay is open:
 *  - browse:  D-Pad moves between the light tiles, OK enters control mode, Back closes.
 *  - control: Left/Right adjusts (hold to keep going), Up/Down switches brightness/warmth, OK or Back confirms.
 *  - holding OK toggles the selected light in both modes.
 */
class LightBarService : AccessibilityService(), HaClient.Listener {

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var prefs: Prefs
    private val ui = OverlayUiState()

    private var client: HaClient? = null
    private var clientConfig = ""
    private var refs: List<LightRef> = emptyList()

    /** Latest Home Assistant state per light entity. */
    private val states = HashMap<String, JSONObject>()
    /** While we are adjusting a light, incoming states are held back until this uptime. */
    private val lockedUntil = HashMap<String, Long>()
    /** Lights with a changed value that is waiting for its next allowed send slot. */
    private val pendingSends = HashMap<String, Param>()
    private val sendTimers = HashMap<String, Runnable>()
    /** When any command (slider value or on/off) last went to a light; used to space commands. */
    private val lastCommand = HashMap<String, Long>()

    private var overlay: View? = null
    private var overlayOwner: OverlayLifecycleOwner? = null
    private var hiding = false

    /** When we last sent a slider value for a light / last heard about it (uptime millis). */
    private val lastSend = HashMap<String, Long>()
    private val stateTime = HashMap<String, Long>()
    /** Copy of the trigger buttons from settings, refreshed whenever the settings screen changes them. */
    private var triggerKeys: Set<Int> = emptySet()

    /** Lets other apps (e.g. Button Mapper) toggle the overlay with a broadcast. */
    private val toggleReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) = toggle()
    }

    /** Navigation keys whose key-down the overlay consumed and that have not been released yet. */
    private val ownedKeys = HashSet<Int>()
    /** Key that was just recorded as trigger; its remaining events are swallowed until release. */
    private var swallowKey = 0
    /** Direction of the Left/Right key currently held down, 0 if none. */
    private var holdDir = 0
    private var okLongFired = false
    private var lastTriggerDown = 0L

    private val autoHide = Runnable { hide() }
    private val removeOverlay = Runnable { removeOverlayNow() }
    private val disconnect = Runnable { client?.close() }
    private val triggerHold = Runnable { toggle() }
    // The system does not reliably deliver key repeats to accessibility services, so we repeat ourselves.
    private val holdRepeat = object : Runnable {
        override fun run() {
            if (holdDir == 0 || !ui.controlling) return
            if (adjust(holdDir, held = true)) handler.postDelayed(this, 40)
        }
    }
    private val okHold = Runnable {
        okLongFired = true
        togglePower()
    }

    override fun onServiceConnected() {
        prefs = Prefs(this)
        triggerKeys = prefs.triggerKeys.toSet()
        instance = this
        val filter = IntentFilter(ACTION_TOGGLE)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(toggleReceiver, filter, Context.RECEIVER_EXPORTED)
        } else {
            registerReceiver(toggleReceiver, filter)
        }
    }

    /** Called by the settings screen after the trigger buttons were edited. */
    fun reloadTriggerKeys() {
        triggerKeys = prefs.triggerKeys.toSet()
    }

    override fun onDestroy() {
        instance = null
        runCatching { unregisterReceiver(toggleReceiver) }
        handler.removeCallbacksAndMessages(null)
        removeOverlayNow()
        client?.close()
        super.onDestroy()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    // ---------------------------------------------------------------- keys

    override fun onKeyEvent(event: KeyEvent): Boolean {
        val code = event.keyCode
        if (captureMode) {
            // Recorded here, not in the activity: some remote buttons (e.g. the Star key) never
            // reach apps, but accessibility services see them.
            if (code in RESERVED_KEYS) return false
            if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
                swallowKey = code
                captureMode = false
                onKeyCaptured?.invoke(code)
            }
            return true
        }
        if (code == swallowKey) {
            if (event.action == KeyEvent.ACTION_UP) swallowKey = 0
            return true
        }
        val visible = overlay != null && !hiding

        if (visible && code in NAV_KEYS) {
            if (event.action == KeyEvent.ACTION_DOWN) ownedKeys.add(code) else ownedKeys.remove(code)
            handleOverlayKey(event)
            return true
        }
        // The overlay closed while one of its keys was still down (Back closes it on key-down).
        // Swallow the rest of that press, or the app underneath would see a lone key-up and act
        // on it, e.g. YouTube leaving the video on Back.
        if (code in ownedKeys) {
            if (event.action == KeyEvent.ACTION_UP) ownedKeys.remove(code)
            return true
        }
        if (code in triggerKeys) return handleTrigger(event)
        if (visible && event.action == KeyEvent.ACTION_DOWN && code == KeyEvent.KEYCODE_HOME) hide()
        return false
    }

    /** @return true if the key event should be hidden from the foreground app. */
    private fun handleTrigger(event: KeyEvent): Boolean {
        val down = event.action == KeyEvent.ACTION_DOWN
        when (prefs.gesture) {
            Gesture.PRESS -> {
                if (!down && !event.isCanceled) toggle()
                return true
            }
            Gesture.DOUBLE -> if (down && event.repeatCount == 0) {
                if (event.eventTime - lastTriggerDown < 400) {
                    lastTriggerDown = 0
                    toggle()
                } else {
                    lastTriggerDown = event.eventTime
                }
            }
            Gesture.HOLD -> {
                if (down && event.repeatCount == 0) handler.postDelayed(triggerHold, 500)
                if (!down) handler.removeCallbacks(triggerHold)
            }
        }
        // Double/long press: the app underneath still gets the key, we cannot replay it later.
        return false
    }

    private fun handleOverlayKey(event: KeyEvent) {
        scheduleAutoHide()
        val down = event.action == KeyEvent.ACTION_DOWN
        val first = down && event.repeatCount == 0

        when (event.keyCode) {
            KeyEvent.KEYCODE_BACK -> if (first) {
                if (ui.controlling) leaveControl() else hide()
            }

            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> {
                if (first) {
                    okLongFired = false
                    handler.postDelayed(okHold, 450)
                } else if (!down) {
                    handler.removeCallbacks(okHold)
                    if (okLongFired) return
                    // Short press: enter control mode, or confirm and go back to browsing.
                    if (ui.controlling) {
                        leaveControl()
                    } else if (ui.message == null && ui.lights.isNotEmpty()) {
                        ui.param = Param.BRIGHTNESS
                        ui.controlling = true
                    }
                }
            }

            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT -> {
                val dir = if (event.keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) 1 else -1
                when {
                    !down -> if (dir == holdDir) stopHold()
                    !first -> Unit
                    ui.controlling -> startHold(dir)
                    ui.columns > 1 -> moveSelection(dir, 0)
                    // Single column: Left/Right is unused while browsing, so adjust straight away.
                    ui.message == null && ui.lights.isNotEmpty() -> {
                        ui.param = Param.BRIGHTNESS
                        ui.controlling = true
                        startHold(dir)
                    }
                }
            }

            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN -> if (down) {
                val dir = if (event.keyCode == KeyEvent.KEYCODE_DPAD_DOWN) 1 else -1
                when {
                    ui.controlling -> if (first && selectedLight()?.supportsTemp == true) {
                        ui.param = if (ui.param == Param.BRIGHTNESS) Param.WARMTH else Param.BRIGHTNESS
                    }
                    else -> moveSelection(0, dir)
                }
            }
        }
    }

    /** Moves the selection one tile sideways ([dx]) or one row up/down ([dy]) in the tile grid. */
    private fun moveSelection(dx: Int, dy: Int) {
        val count = ui.lights.size
        if (count == 0) return
        val columns = ui.columns
        val current = ui.selected.coerceIn(0, count - 1)
        var target = current
        if (dx != 0) {
            val next = current + dx
            if (next in 0 until count && next / columns == current / columns) target = next
        }
        if (dy != 0) {
            val next = current + dy * columns
            target = when {
                next in 0 until count -> next
                // The last row may be shorter: going down from above its end lands on its last tile.
                dy > 0 && (count - 1) / columns > current / columns -> count - 1
                else -> current
            }
        }
        ui.selected = target
    }

    private fun startHold(dir: Int) {
        stopHold()
        holdDir = dir
        adjust(dir, held = false)
        handler.postDelayed(holdRepeat, 300)
    }

    private fun stopHold() {
        holdDir = 0
        handler.removeCallbacks(holdRepeat)
    }

    private fun leaveControl() {
        stopHold()
        flushSends()
        ui.controlling = false
        ui.param = Param.BRIGHTNESS
    }

    // ------------------------------------------------------------ commands

    private fun selectedLight(): Light? = ui.lights.getOrNull(ui.selected)

    /**
     * One step of the selected value. A tap ([held] = false) moves exactly 1 %, so every level is
     * reachable. While the key is held the speed follows the current level: slow near the bottom,
     * where small changes are most visible, and fast higher up (about 2.5 s for the full range).
     * Holding stops at 1 %; a separate tap from there switches the light off.
     *
     * @return false once the value cannot move any further in that direction.
     */
    private fun adjust(dir: Int, held: Boolean): Boolean {
        val light = selectedLight() ?: return false
        if (!light.available) return false
        val updated = if (ui.param == Param.WARMTH && light.supportsTemp) {
            val step = (light.maxKelvin - light.minKelvin) * 0.02f
            val kelvin = (light.kelvin + dir * step).roundToInt().coerceIn(light.minKelvin, light.maxKelvin)
            light.copy(kelvin = kelvin, on = true, brightness = if (light.on) light.brightness else 1f)
        } else if (light.supportsBrightness) {
            val current = light.fraction
            val level = if (held) {
                val speed = (0.004f + current * 0.06f).coerceAtMost(0.03f)
                (current + dir * speed).coerceIn(if (light.on) 0.01f else 0f, 1f)
            } else {
                ((current * 100).roundToInt() + dir).coerceIn(0, 100) / 100f
            }
            light.copy(on = level > 0f, brightness = level)
        } else {
            light.copy(on = dir > 0)
        }
        if (updated == light) return false
        ui.lights[ui.selected] = updated
        lock(light.id, 2500)
        queueSend(light.id, if (updated.kelvin != light.kelvin) Param.WARMTH else Param.BRIGHTNESS)
        return true
    }

    /**
     * Schedules sending the light's current value, depending on its update setting:
     *  - live: right away if the last command is old enough, otherwise once the interval has passed;
     *  - settled: only after the value has not changed for the chosen time, so the device gets
     *    one command. Confirming with OK/Back or closing the overlay skips the rest of that wait
     *    ([flushSends]).
     * In both cases two commands to one light are never closer together than its interval.
     */
    private fun queueSend(id: String, param: Param) {
        val live = UPDATE_RATES[0].first
        val interval = refs.firstOrNull { it.id == id }?.intervalMs ?: live
        pendingSends[id] = param
        if (interval <= live) {
            if (id !in sendTimers) schedule(id, untilAllowed(id, interval))
        } else {
            // Restart the rest period; it is at least as long as the spacing to the last command.
            schedule(id, maxOf(interval.toLong(), untilAllowed(id, interval)))
        }
    }

    /** Milliseconds until the light may receive its next command. */
    private fun untilAllowed(id: String, interval: Int): Long =
        ((lastCommand[id] ?: 0) + interval - SystemClock.uptimeMillis()).coerceAtLeast(0)

    private fun schedule(id: String, delay: Long) {
        sendTimers.remove(id)?.let { handler.removeCallbacks(it) }
        val timer = Runnable { send(id) }
        sendTimers[id] = timer
        handler.postDelayed(timer, delay)
    }

    /**
     * Sends every waiting value as early as its light allows: now, or as soon as the interval
     * since that light's last command has passed. This also works after the overlay is closed.
     */
    private fun flushSends() {
        pendingSends.keys.toList().forEach { id ->
            val interval = refs.firstOrNull { it.id == id }?.intervalMs ?: UPDATE_RATES[0].first
            schedule(id, untilAllowed(id, interval))
        }
    }

    private fun send(id: String) {
        sendTimers.remove(id)?.let { handler.removeCallbacks(it) }
        val param = pendingSends.remove(id) ?: return
        val ha = client ?: return
        val light = ui.lights.firstOrNull { it.id == id } ?: return
        lastSend[id] = SystemClock.uptimeMillis()
        lastCommand[id] = SystemClock.uptimeMillis()
        lock(id, 2500)
        val data = JSONObject().put("transition", 0.2)
        when {
            param == Param.WARMTH -> ha.callService("turn_on", id, data.put("color_temp_kelvin", light.kelvin))
            !light.on -> ha.callService("turn_off", id)
            light.supportsBrightness -> ha.callService(
                "turn_on", id,
                data.put("brightness_pct", (light.brightness * 100).roundToInt().coerceIn(1, 100)),
            )
            else -> ha.callService("turn_on", id)
        }
    }

    private fun togglePower() {
        val light = selectedLight() ?: return
        if (!light.available) return
        pendingSends.remove(light.id)
        sendTimers.remove(light.id)?.let { handler.removeCallbacks(it) }
        val on = !light.on
        // Until Home Assistant reports the real level, show a freshly switched-on light as full.
        ui.lights[ui.selected] = light.copy(on = on, brightness = if (on && light.brightness == 0f) 1f else light.brightness)
        lock(light.id, 800)
        lastSend.remove(light.id)
        lastCommand[light.id] = SystemClock.uptimeMillis()
        client?.callService(if (on) "turn_on" else "turn_off", light.id)
    }

    /** Keeps our optimistic value on screen while Home Assistant catches up, then syncs. */
    private fun lock(id: String, millis: Long) {
        lockedUntil[id] = SystemClock.uptimeMillis() + millis
        handler.postDelayed({ applyState(id) }, millis + 30)
    }

    private fun applyState(id: String) {
        val now = SystemClock.uptimeMillis()
        if (now < (lockedUntil[id] ?: 0) || id in pendingSends) return
        // A state reported right after our last command is usually a mid-transition value. Showing it
        // would make the slider jump back, so keep our own value until something newer arrives.
        val sent = lastSend[id] ?: 0
        if (sent != 0L && (stateTime[id] ?: 0) < sent + 600) return
        val index = refs.indexOfFirst { it.id == id }
        if (index < 0 || index >= ui.lights.size) return
        val state = states[id] ?: return
        ui.lights[index] = parseLight(refs[index], state)
    }

    // ------------------------------------------------------- Home Assistant

    override fun onStatus(status: HaClient.Status) {
        if (status == HaClient.Status.AUTH_FAILED) ui.message = "Home Assistant rejected the token"
    }

    override fun onStates(states: List<JSONObject>) {
        val now = SystemClock.uptimeMillis()
        states.forEach {
            this.states[it.optString("entity_id")] = it
            stateTime[it.optString("entity_id")] = now
        }
        refs.forEach { applyState(it.id) }
    }

    override fun onStateChanged(state: JSONObject) {
        val id = state.optString("entity_id")
        states[id] = state
        stateTime[id] = SystemClock.uptimeMillis()
        applyState(id)
    }

    // -------------------------------------------------------------- overlay

    fun toggle() {
        if (overlay != null && !hiding) hide() else show()
    }

    private fun show() {
        handler.removeCallbacks(disconnect)
        handler.removeCallbacks(removeOverlay)
        loadConfig()
        ui.controlling = false
        ui.param = Param.BRIGHTNESS
        hiding = false

        if (overlay == null) {
            val view = ComposeView(this).apply { setContent { LightBarOverlay(ui) } }
            val owner = OverlayLifecycleOwner().also { it.attach(view) }
            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                // Keys reach us through onKeyEvent, so the window never needs focus or touches.
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT,
            )
            try {
                getSystemService(WindowManager::class.java).addView(view, params)
            } catch (e: RuntimeException) {
                owner.destroy()
                return
            }
            overlay = view
            overlayOwner = owner
        }
        ui.shown.targetState = true
        scheduleAutoHide()
    }

    private fun hide() {
        if (overlay == null || hiding) return
        hiding = true
        stopHold()
        flushSends()
        handler.removeCallbacks(autoHide)
        handler.removeCallbacks(okHold)
        ui.shown.targetState = false
        handler.postDelayed(removeOverlay, 260)
        handler.postDelayed(disconnect, 60_000)
    }

    private fun removeOverlayNow() {
        val view = overlay ?: return
        runCatching { getSystemService(WindowManager::class.java).removeView(view) }
        overlayOwner?.destroy()
        overlay = null
        overlayOwner = null
        hiding = false
    }

    private fun scheduleAutoHide() {
        handler.removeCallbacks(autoHide)
        val seconds = prefs.autoCloseSeconds
        if (seconds > 0) handler.postDelayed(autoHide, seconds * 1000L)
    }

    /** Re-reads settings so changes apply the next time the overlay opens. */
    private fun loadConfig() {
        refs = prefs.lights
        ui.anchor = prefs.anchor
        ui.square = prefs.cardStyle == CardStyle.SQUARE
        ui.perRow = prefs.perRow
        ui.edgeGap = prefs.edgeGap
        ui.message = when {
            prefs.url.isBlank() || prefs.token.isBlank() -> "Open the LightBar app to connect Home Assistant"
            refs.isEmpty() -> "Open the LightBar app to choose your lights"
            else -> null
        }
        val now = SystemClock.uptimeMillis()
        val lights = refs.map { ref ->
            // A value that is still waiting to be sent (slow update rate) or was just sent must
            // survive reopening the overlay, otherwise the older Home Assistant state would go out.
            val recent = ref.id in pendingSends || now < (lockedUntil[ref.id] ?: 0)
            ui.lights.firstOrNull { recent && it.id == ref.id }?.copy(name = ref.name)
                ?: parseLight(ref, states[ref.id])
        }
        ui.lights.clear()
        ui.lights.addAll(lights)
        ui.selected = ui.selected.coerceIn(0, (lights.size - 1).coerceAtLeast(0))

        val config = prefs.url + "\n" + prefs.token
        if (config != clientConfig) {
            client?.close()
            states.clear()
            clientConfig = config
            client = if (prefs.url.isBlank() || prefs.token.isBlank()) null else HaClient(prefs.url, prefs.token, this)
        }
        client?.connect()
    }

    companion object {
        /** Broadcast action that shows or hides the overlay. */
        const val ACTION_TOGGLE = "dev.adrianjost.lightbar.TOGGLE"

        /** Set while the service is enabled, so the settings screen can show a preview. */
        var instance: LightBarService? = null
            private set

        /** True while the settings screen is recording a new trigger button. */
        @Volatile
        var captureMode = false

        /** Called on the main thread with the key code recorded while [captureMode] was on. */
        var onKeyCaptured: ((Int) -> Unit)? = null

        private val NAV_KEYS = setOf(
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT,
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN,
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER,
            KeyEvent.KEYCODE_BACK,
        )
        val RESERVED_KEYS: Set<Int> = NAV_KEYS + KeyEvent.KEYCODE_HOME
    }
}

/** Gives a ComposeView that lives in a bare window the lifecycle Compose needs. */
private class OverlayLifecycleOwner : LifecycleOwner, SavedStateRegistryOwner {
    private val registry = LifecycleRegistry(this)
    private val savedState = SavedStateRegistryController.create(this)

    override val lifecycle: Lifecycle get() = registry
    override val savedStateRegistry: SavedStateRegistry get() = savedState.savedStateRegistry

    fun attach(view: View) {
        savedState.performRestore(null)
        registry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
        view.setViewTreeLifecycleOwner(this)
        view.setViewTreeSavedStateRegistryOwner(this)
    }

    fun destroy() {
        registry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
    }
}
