package dev.adrianjost.lightbar

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key as pressedKey
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.json.JSONObject
import java.io.IOException

/** The only screen of the app: connection, light selection, layout and trigger button. */
class SettingsActivity : ComponentActivity(), HaClient.Listener {

    private lateinit var prefs: Prefs
    private val handler = Handler(Looper.getMainLooper())
    private var client: HaClient? = null
    private var server: PhoneSetupServer? = null

    private var status by mutableStateOf("Not connected yet")
    private val allLights = mutableStateListOf<LightRef>()
    private val selected = mutableStateListOf<LightRef>()
    private var layout by mutableStateOf(BarLayout.SIDEBAR)
    private var align by mutableStateOf(BarAlign.CENTER)
    private var edgeGap by mutableIntStateOf(24)
    private var gesture by mutableStateOf(Gesture.HOLD)
    private var triggerKey by mutableIntStateOf(0)
    private var capturing by mutableStateOf(false)
    private var captureHint by mutableStateOf("")
    private var serviceEnabled by mutableStateOf(false)
    private var pairingUrl by mutableStateOf<String?>(null)
    private var pairingQr by mutableStateOf<ImageBitmap?>(null)

    /** Id of the light that is currently picked up for sorting, if any. */
    private var grabbed by mutableStateOf<String?>(null)

    /** Key whose release still has to be swallowed after it was captured. */
    private var swallowUp = -1

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        selected.addAll(prefs.lights)
        layout = prefs.layout
        align = prefs.align
        edgeGap = prefs.edgeGap
        gesture = prefs.gesture
        triggerKey = prefs.triggerKey
        setContent { Screen() }
    }

    override fun onResume() {
        super.onResume()
        val enabled = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
        serviceEnabled = enabled.orEmpty().contains("$packageName/")
        connect()
    }

    override fun onPause() {
        super.onPause()
        stopCapture()
        stopPairing()
        grabbed = null
        client?.close()
        client = null
    }

    // ------------------------------------------------------- Home Assistant

    private fun connect() {
        client?.close()
        client = null
        if (prefs.url.isBlank() || prefs.token.isBlank()) {
            status = "Not connected yet"
            return
        }
        client = HaClient(prefs.url, prefs.token, this).also { it.connect() }
    }

    override fun onStatus(status: HaClient.Status) {
        this.status = when (status) {
            HaClient.Status.CONNECTING -> "Connecting to ${prefs.url}…"
            HaClient.Status.CONNECTED -> "Connected to ${prefs.url}"
            HaClient.Status.AUTH_FAILED -> "The token was rejected by ${prefs.url}"
            HaClient.Status.DISCONNECTED -> "Cannot reach ${prefs.url}, retrying…"
        }
    }

    override fun onStates(states: List<JSONObject>) {
        val found = states.map {
            val id = it.optString("entity_id")
            LightRef(id, it.optJSONObject("attributes")?.optString("friendly_name").orEmpty().ifBlank { id })
        }.sortedBy { it.name.lowercase() }
        allLights.clear()
        allLights.addAll(found)
    }

    override fun onStateChanged(state: JSONObject) {}

    // -------------------------------------------------------- phone pairing

    private fun startPairing() {
        stopPairing()
        val ip = PhoneSetupServer.localIp()
        if (ip == null) {
            status = "This TV has no network address"
            return
        }
        for (port in 8765..8769) {
            val candidate = PhoneSetupServer(port, prefs.url, prefs.token.isNotBlank(), selected.toList()) { url, token, names ->
                handler.post {
                    val reconnect = url != prefs.url || token != null
                    prefs.url = url
                    if (token != null) prefs.token = token
                    saveLights(selected.map { it.copy(name = names[it.id] ?: it.name) })
                    if (reconnect) connect()
                    // Give the confirmation page time to reach the phone before stopping.
                    handler.postDelayed({ stopPairing() }, 500)
                }
            }
            try {
                candidate.start()
            } catch (e: IOException) {
                continue
            }
            server = candidate
            val url = "http://$ip:$port"
            pairingUrl = url
            pairingQr = PhoneSetupServer.qrCode(url).asImageBitmap()
            return
        }
        status = "Could not start the setup page"
    }

    private fun stopPairing() {
        server?.stop()
        server = null
        pairingUrl = null
        pairingQr = null
    }

    // ------------------------------------------------------- trigger button

    private fun startCapture() {
        captureHint = "Press the remote button you want to use (Back cancels)…"
        capturing = true
        // The service records the key when it is running: it also sees buttons that apps never get.
        LightBarService.onKeyCaptured = { code -> saveTrigger(code) }
        LightBarService.captureMode = true
    }

    private fun stopCapture() {
        capturing = false
        LightBarService.captureMode = false
        LightBarService.onKeyCaptured = null
    }

    private fun saveTrigger(code: Int) {
        triggerKey = code
        prefs.triggerKey = code
        stopCapture()
    }

    /** Fallback while the accessibility service is off, and handling of Back / navigation keys. */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (capturing) {
            if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
                when (event.keyCode) {
                    KeyEvent.KEYCODE_BACK -> stopCapture()
                    in LightBarService.RESERVED_KEYS -> captureHint = "That button is needed for navigation, pick another"
                    else -> saveTrigger(event.keyCode)
                }
                swallowUp = event.keyCode
            }
            return true
        }
        if (event.keyCode == swallowUp) {
            if (event.action == KeyEvent.ACTION_UP) swallowUp = -1
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    private fun openAccessibilitySettings() {
        val intents = listOf(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS), Intent(Settings.ACTION_SETTINGS))
        for (intent in intents) {
            if (runCatching { startActivity(intent) }.isSuccess) return
        }
    }

    // --------------------------------------------------------------- lights

    private fun saveLights(lights: List<LightRef>) {
        selected.clear()
        selected.addAll(lights)
        prefs.lights = lights
    }

    private fun moveLight(id: String, by: Int) {
        val index = selected.indexOfFirst { it.id == id }
        val target = index + by
        if (index < 0 || target !in selected.indices) return
        saveLights(selected.toMutableList().apply { add(target, removeAt(index)) })
    }

    private fun cycleRate(ref: LightRef) {
        val next = (UPDATE_RATES.indexOfFirst { it.first == ref.intervalMs } + 1) % UPDATE_RATES.size
        saveLights(selected.map { if (it.id == ref.id) it.copy(intervalMs = UPDATE_RATES[next].first) else it })
    }

    // ------------------------------------------------------------------- UI

    @Composable
    private fun Screen() {
        val first = remember { FocusRequester() }
        LaunchedEffect(Unit) { first.requestFocus() }

        Row(
            Modifier.fillMaxSize().background(Color(0xFF0B0E11)).padding(horizontal = 48.dp, vertical = 28.dp)
        ) {
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 4.dp)) {
                Label("LightBar", 26.sp, Color.White, weight = FontWeight.SemiBold)

                Section("Home Assistant")
                Item("Set up with your phone", status, modifier = Modifier.focusRequester(first)) { startPairing() }

                Section("Overlay")
                Item(
                    "Accessibility service",
                    if (serviceEnabled) "Enabled" else "Off. LightBar needs it to see your remote button. Press OK to open settings.",
                ) { openAccessibilitySettings() }
                Item(
                    "Trigger button",
                    when {
                        capturing -> captureHint
                        triggerKey == 0 -> "Not set. Press OK, then the remote button you want to use."
                        else -> KeyEvent.keyCodeToString(triggerKey).removePrefix("KEYCODE_")
                    },
                ) { startCapture() }
                Choice("Trigger gesture", Gesture.entries.map { it.label }, gesture.ordinal) {
                    gesture = Gesture.entries[it]
                    prefs.gesture = gesture
                }
                Item("Preview", if (serviceEnabled) "Show or hide the overlay now" else "Enable the accessibility service first") {
                    LightBarService.instance?.toggle()
                }

                Section("Appearance")
                // Shown in reading direction, which differs from the stored enum order.
                val layouts = listOf(BarLayout.LEFT_SIDEBAR, BarLayout.BOTTOM, BarLayout.SIDEBAR)
                Choice("Layout", layouts.map { it.label }, layouts.indexOf(layout)) {
                    layout = layouts[it]
                    prefs.layout = layout
                }
                Choice(
                    "Position",
                    if (layout != BarLayout.BOTTOM) listOf("Top", "Center", "Bottom") else listOf("Left", "Center", "Right"),
                    align.ordinal,
                ) {
                    align = BarAlign.entries[it]
                    prefs.align = align
                }
                Choice("Gap from screen edge", EDGE_GAPS.map { it.second }, EDGE_GAPS.indexOfFirst { it.first == edgeGap }) {
                    edgeGap = EDGE_GAPS[it].first
                    prefs.edgeGap = edgeGap
                }

                Section("Shown in the overlay")
                if (selected.isEmpty()) Note("No lights yet. Add some from the list below.")
                selected.forEach { ref -> key(ref.id) { ShownLight(ref) } }

                Section("Available lights")
                val others = allLights.filter { light -> selected.none { it.id == light.id } }
                if (allLights.isEmpty()) Note("Your lights appear here once Home Assistant is connected.")
                others.forEach { ref ->
                    key(ref.id) {
                        Item(ref.name, ref.id, trailingIcon = R.drawable.ic_add, trailing = "Add") { saveLights(selected + ref) }
                    }
                }
            }

            Spacer(Modifier.width(36.dp))

            Column(Modifier.width(280.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                val qr = pairingQr
                if (qr != null) {
                    Image(qr, contentDescription = "Setup QR code", modifier = Modifier.size(220.dp).clip(RoundedCornerShape(16.dp)))
                    Spacer(Modifier.height(16.dp))
                    Help("Scan with your phone, or open ${pairingUrl.orEmpty()} on any device in the same network. There you enter your Home Assistant address and access token, and give your lights short names.")
                } else {
                    Spacer(Modifier.height(48.dp))
                    Help(
                        "In the overlay\n\n" +
                            "D-Pad: choose a light\n" +
                            "OK: adjust the light\n" +
                            "Hold OK: on / off\n\n" +
                            "While adjusting\n\n" +
                            "Left / Right: tap for 1 %, hold to sweep\n" +
                            "Up / Down: brightness or warmth\n" +
                            "OK or Back: done\n\n" +
                            "Updates\n\n" +
                            "Live suits normal lights. For devices that cannot take frequent changes, " +
                            "pick After …: the value is sent once it has rested that long, or at once " +
                            "when you confirm with OK or close the overlay."
                    )
                }
            }
        }
    }

    /** One selected light with its own buttons: sort, update rate, remove. */
    @Composable
    private fun ShownLight(ref: LightRef) {
        val isGrabbed = grabbed == ref.id
        val shape = RoundedCornerShape(16.dp)
        Row(
            modifier = Modifier
                .padding(vertical = 4.dp)
                .fillMaxWidth()
                .clip(shape)
                .background(Color.White.copy(alpha = if (isGrabbed) 0.16f else 0.06f))
                .border(2.dp, if (isGrabbed) Color.White else Color.Transparent, shape)
                .padding(start = 20.dp, end = 10.dp, top = 9.dp, bottom = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Column(Modifier.weight(1f)) {
                Label(ref.name, 16.sp, Color.White, weight = FontWeight.Medium)
                Label(ref.id, 12.sp, Color.White.copy(alpha = 0.6f))
            }
            Pressable(
                onClick = { grabbed = if (isGrabbed) null else ref.id },
                radius = 12.dp,
                highlighted = isGrabbed,
                onFocusLost = { if (grabbed == ref.id) grabbed = null },
                onPreviewKey = { key, down ->
                    // While picked up, Up/Down carry the light through the list instead of moving focus.
                    when {
                        !isGrabbed -> false
                        key == Key.DirectionUp || key == Key.DirectionDown -> {
                            if (down) moveLight(ref.id, if (key == Key.DirectionUp) -1 else 1)
                            true
                        }
                        key == Key.DirectionLeft || key == Key.DirectionRight -> true
                        key == Key.Back -> {
                            if (!down) grabbed = null
                            true
                        }
                        else -> false
                    }
                },
            ) {
                Icon(R.drawable.ic_move)
                Label(if (isGrabbed) "▲ ▼ to move, OK to drop" else "Move", 13.sp, Color.White, Modifier.padding(start = 6.dp))
            }
            Pressable(onClick = { cycleRate(ref) }, radius = 12.dp) {
                val rate = UPDATE_RATES.firstOrNull { it.first == ref.intervalMs } ?: UPDATE_RATES[0]
                Label("Updates: ${rate.second}", 13.sp, Color.White)
            }
            Pressable(onClick = { saveLights(selected.filter { it.id != ref.id }) }, radius = 12.dp) {
                Icon(R.drawable.ic_delete)
                Label("Remove", 13.sp, Color.White, Modifier.padding(start = 6.dp))
            }
        }
    }

    /**
     * A setting with a few fixed options, shown side by side like radio buttons.
     * Left/Right moves between them, OK picks one; the picked option is filled white.
     */
    @Composable
    private fun Choice(title: String, options: List<String>, selectedIndex: Int, onSelect: (Int) -> Unit) {
        val shape = RoundedCornerShape(16.dp)
        Row(
            modifier = Modifier
                .padding(vertical = 4.dp)
                .fillMaxWidth()
                .clip(shape)
                .background(Color.White.copy(alpha = 0.06f))
                .padding(start = 20.dp, end = 10.dp, top = 9.dp, bottom = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Label(title, 16.sp, Color.White, Modifier.weight(1f), FontWeight.Medium)
            options.forEachIndexed { index, option ->
                val picked = index == selectedIndex
                var focused by remember { mutableStateOf(false) }
                val background by animateColorAsState(
                    when {
                        picked -> Color.White
                        focused -> Color.White.copy(alpha = 0.18f)
                        else -> Color.Transparent
                    },
                    tween(100), label = "segment",
                )
                val outline by animateColorAsState(if (focused) Color.White else Color.Transparent, tween(60), label = "outline")
                val segment = RoundedCornerShape(12.dp)
                Row(
                    Modifier
                        // The outline sits outside the fill so it stays visible on the white, picked option.
                        .border(2.dp, outline, RoundedCornerShape(15.dp))
                        .padding(3.dp)
                        .clip(segment)
                        .background(background)
                        .onFocusChanged { focused = it.isFocused }
                        .clickable(remember { MutableInteractionSource() }, indication = null) { onSelect(index) }
                        .padding(horizontal = 14.dp, vertical = 7.dp)
                ) {
                    Label(option, 13.sp, if (picked) Ink else Color.White, weight = FontWeight.Medium)
                }
            }
        }
    }

    @Composable
    private fun Section(title: String) {
        Label(
            title.uppercase(), 12.sp, Color.White.copy(alpha = 0.55f),
            Modifier.padding(top = 22.dp, bottom = 6.dp, start = 4.dp), FontWeight.Medium,
        )
    }

    @Composable
    private fun Note(text: String) {
        Label(text, 13.sp, Color.White.copy(alpha = 0.6f), Modifier.padding(start = 4.dp, top = 4.dp, bottom = 4.dp))
    }

    @Composable
    private fun Help(text: String) {
        BasicText(text, style = TextStyle(color = Color.White.copy(alpha = 0.65f), fontSize = 13.sp, lineHeight = 19.sp))
    }

    @Composable
    private fun Icon(id: Int) {
        Image(painterResource(id), contentDescription = null, modifier = Modifier.size(18.dp), colorFilter = ColorFilter.tint(Color.White))
    }

    /** A full-width row that does one thing when OK is pressed. */
    @Composable
    private fun Item(
        title: String,
        subtitle: String,
        modifier: Modifier = Modifier,
        trailing: String = "",
        trailingIcon: Int? = null,
        onClick: () -> Unit,
    ) {
        Pressable(onClick, modifier.padding(vertical = 4.dp).fillMaxWidth(), horizontal = 20.dp, vertical = 11.dp) {
            Column(Modifier.weight(1f)) {
                Label(title, 16.sp, Color.White, weight = FontWeight.Medium)
                if (subtitle.isNotEmpty()) Label(subtitle, 12.sp, Color.White.copy(alpha = 0.6f))
            }
            if (trailingIcon != null) Icon(trailingIcon)
            if (trailing.isNotEmpty()) Label(trailing, 14.sp, Color.White, Modifier.padding(start = 6.dp), FontWeight.Medium)
        }
    }

    /**
     * Anything focusable. Focus is shown by a white outline and a lighter background;
     * the text keeps its colour so moving around does not flicker.
     */
    @Composable
    private fun Pressable(
        onClick: () -> Unit,
        modifier: Modifier = Modifier,
        radius: Dp = 16.dp,
        horizontal: Dp = 12.dp,
        vertical: Dp = 9.dp,
        highlighted: Boolean = false,
        onFocusLost: () -> Unit = {},
        onPreviewKey: ((key: Key, down: Boolean) -> Boolean)? = null,
        content: @Composable RowScope.() -> Unit,
    ) {
        var focused by remember { mutableStateOf(false) }
        val background by animateColorAsState(
            Color.White.copy(alpha = if (highlighted) 0.34f else if (focused) 0.18f else 0.07f), tween(60), label = "bg",
        )
        val outline by animateColorAsState(if (focused) Color.White else Color.Transparent, tween(60), label = "outline")
        val shape = RoundedCornerShape(radius)
        Row(
            modifier = modifier
                .clip(shape)
                .background(background)
                .border(2.dp, outline, shape)
                .onFocusChanged {
                    if (focused && !it.isFocused) onFocusLost()
                    focused = it.isFocused
                }
                .onPreviewKeyEvent { event ->
                    onPreviewKey?.invoke(event.pressedKey, event.type == KeyEventType.KeyDown) ?: false
                }
                .clickable(remember { MutableInteractionSource() }, indication = null, onClick = onClick)
                .padding(horizontal = horizontal, vertical = vertical),
            verticalAlignment = Alignment.CenterVertically,
            content = content,
        )
    }
}
