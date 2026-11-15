package dev.adrianjost.lightbar

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.roundToInt

// New entries go at the end: the ordinal is what gets stored.
enum class BarLayout(val label: String) { SIDEBAR("Right sidebar"), BOTTOM("Bottom bar"), LEFT_SIDEBAR("Left sidebar") }

/** Where the panel sits along its screen edge: top/centre/bottom for the sidebar, left/centre/right for the bottom bar. */
enum class BarAlign { START, CENTER, END }

/** Distance between the panel and the screen edge, in dp. */
val EDGE_GAPS = listOf(0 to "None", 12 to "Small", 24 to "Medium", 48 to "Large")

enum class Gesture(val label: String) { PRESS("Single press"), DOUBLE("Double press"), HOLD("Long press") }

enum class Param { BRIGHTNESS, WARMTH }

/** How often a light may receive commands while its slider is being moved. */
val UPDATE_RATES = listOf(200 to "Live", 1000 to "Every 1 s", 3000 to "Every 3 s")

/**
 * A light the user picked in settings.
 * @property name short display name, editable from the phone setup page
 * @property intervalMs minimum time between two commands to this light, for slow devices
 */
data class LightRef(val id: String, val name: String, val intervalMs: Int = UPDATE_RATES[0].first)

/** Display state of one light in the overlay. */
data class Light(
    val id: String,
    val name: String,
    val known: Boolean = false,
    val available: Boolean = false,
    val on: Boolean = false,
    val brightness: Float = 0f,
    val supportsBrightness: Boolean = false,
    val supportsTemp: Boolean = false,
    val kelvin: Int = 3000,
    val minKelvin: Int = 2000,
    val maxKelvin: Int = 6500,
) {
    /** Fill level of the brightness slider, 0..1. */
    val fraction: Float
        get() = when {
            !on -> 0f
            supportsBrightness -> brightness
            else -> 1f
        }

    val tempFraction: Float
        get() = ((kelvin - minKelvin).toFloat() / (maxKelvin - minKelvin).coerceAtLeast(1)).coerceIn(0f, 1f)

    val valueText: String
        get() = when {
            !known -> "…"
            !available -> "—"
            !on -> "Off"
            supportsBrightness -> "${(brightness * 100).roundToInt().coerceAtLeast(1)}%"
            else -> "On"
        }
}

/** Builds a [Light] from a Home Assistant state object. */
fun parseLight(ref: LightRef, state: JSONObject?): Light {
    if (state == null) return Light(ref.id, ref.name)
    val attrs = state.optJSONObject("attributes") ?: JSONObject()
    val value = state.optString("state")
    val modes = attrs.optJSONArray("supported_color_modes")
        ?.let { arr -> (0 until arr.length()).map { arr.optString(it) } }
        .orEmpty()
    val minK = attrs.optInt("min_color_temp_kelvin", 2000)
    val maxK = attrs.optInt("max_color_temp_kelvin", 6500)
    return Light(
        id = ref.id,
        name = ref.name,
        known = true,
        available = value == "on" || value == "off",
        on = value == "on",
        brightness = (attrs.optInt("brightness", 0) / 255f).coerceIn(0f, 1f),
        supportsBrightness = modes.any { it != "onoff" },
        supportsTemp = "color_temp" in modes && maxK > minK,
        kelvin = attrs.optInt("color_temp_kelvin", (minK + maxK) / 2).coerceIn(minK, maxK),
        minKelvin = minK,
        maxKelvin = maxK,
    )
}

/** All persisted settings. Stored app-private; the app opts out of backups. */
class Prefs(context: Context) {
    private val sp = context.applicationContext.getSharedPreferences("lightbar", Context.MODE_PRIVATE)

    var url: String
        get() = sp.getString("url", "").orEmpty()
        set(v) = sp.edit().putString("url", v.trim()).apply()

    var token: String
        get() = sp.getString("token", "").orEmpty()
        set(v) = sp.edit().putString("token", v.trim()).apply()

    var layout: BarLayout
        get() = BarLayout.entries.getOrElse(sp.getInt("layout", 0)) { BarLayout.SIDEBAR }
        set(v) = sp.edit().putInt("layout", v.ordinal).apply()

    var align: BarAlign
        get() = BarAlign.entries.getOrElse(sp.getInt("align", BarAlign.CENTER.ordinal)) { BarAlign.CENTER }
        set(v) = sp.edit().putInt("align", v.ordinal).apply()

    var edgeGap: Int
        get() = sp.getInt("edgeGap", 24)
        set(v) = sp.edit().putInt("edgeGap", v).apply()

    /** Android key code of the remote button that opens the overlay, 0 if unset. */
    var triggerKey: Int
        get() = sp.getInt("triggerKey", 0)
        set(v) = sp.edit().putInt("triggerKey", v).apply()

    var gesture: Gesture
        get() = Gesture.entries.getOrElse(sp.getInt("gesture", Gesture.HOLD.ordinal)) { Gesture.HOLD }
        set(v) = sp.edit().putInt("gesture", v.ordinal).apply()

    var lights: List<LightRef>
        get() = runCatching {
            val arr = JSONArray(sp.getString("lights", "[]"))
            (0 until arr.length()).map {
                val o = arr.getJSONObject(it)
                LightRef(o.getString("id"), o.getString("name"), o.optInt("interval", UPDATE_RATES[0].first))
            }
        }.getOrDefault(emptyList())
        set(v) {
            val arr = JSONArray()
            v.forEach { arr.put(JSONObject().put("id", it.id).put("name", it.name).put("interval", it.intervalMs)) }
            sp.edit().putString("lights", arr.toString()).apply()
        }
}
