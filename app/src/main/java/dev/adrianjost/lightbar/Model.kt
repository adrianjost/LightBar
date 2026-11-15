package dev.adrianjost.lightbar

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.roundToInt

/**
 * Where the panel sits on screen: the four corners and the four edge centres.
 * [col] and [row] are 0..2 in a 3x3 grid whose middle cell is unused.
 */
enum class Anchor(val label: String, val col: Int, val row: Int) {
    TOP_LEFT("Top left", 0, 0), TOP("Top", 1, 0), TOP_RIGHT("Top right", 2, 0),
    LEFT("Left", 0, 1), RIGHT("Right", 2, 1),
    BOTTOM_LEFT("Bottom left", 0, 2), BOTTOM("Bottom", 1, 2), BOTTOM_RIGHT("Bottom right", 2, 2),
}

/** Shape of one light tile: a wide row or a compact, roughly square card. */
enum class CardStyle(val label: String) { WIDE("Wide"), SQUARE("Square") }

/** How many tiles sit next to each other before a new row starts; 0 puts all of them in one row. */
val PER_ROW = listOf(1 to "1", 2 to "2", 3 to "3", 0 to "All")

/** Distance between the panel and the screen edge, in dp. */
val EDGE_GAPS = listOf(0 to "None", 12 to "Small", 24 to "Medium", 48 to "Large")

/** Seconds without input after which the overlay closes itself; 0 keeps it open. */
val AUTO_CLOSE = listOf(5 to "5 s", 10 to "10 s", 30 to "30 s", 0 to "Never")

enum class Gesture(val label: String) { PRESS("Single press"), DOUBLE("Double press"), HOLD("Long press") }

enum class Param { BRIGHTNESS, WARMTH }

/**
 * When a light receives commands while its slider is being moved. The first entry is live
 * (rate-limited to one command per interval); the others wait until the value has not changed
 * for that long, so slow devices get a single command.
 */
val UPDATE_RATES = listOf(200 to "Live", 500 to "After 0.5 s", 1000 to "After 1 s", 3000 to "After 3 s")

/**
 * A light the user picked in settings.
 * @property name short display name, editable from the phone setup page
 * @property intervalMs one of [UPDATE_RATES]: live rate limit, or how long the value must rest before it is sent
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

    // Earlier versions stored an edge ("layout": 0 right, 1 bottom, 2 left, 3 top) and a position
    // along it ("align": 0 start, 1 centre, 2 end). They only serve as defaults for the settings below.
    private val legacyEdge get() = sp.getInt("layout", 0)
    private val legacyStacked get() = legacyEdge == 0 || legacyEdge == 2

    var anchor: Anchor
        get() = sp.getString("anchor", null)?.let { name -> Anchor.entries.firstOrNull { it.name == name } }
            ?: when (legacyEdge) {
                1 -> listOf(Anchor.BOTTOM_LEFT, Anchor.BOTTOM, Anchor.BOTTOM_RIGHT)
                2 -> listOf(Anchor.TOP_LEFT, Anchor.LEFT, Anchor.BOTTOM_LEFT)
                3 -> listOf(Anchor.TOP_LEFT, Anchor.TOP, Anchor.TOP_RIGHT)
                else -> listOf(Anchor.TOP_RIGHT, Anchor.RIGHT, Anchor.BOTTOM_RIGHT)
            }.getOrElse(sp.getInt("align", 1)) { Anchor.RIGHT }
        set(v) = sp.edit().putString("anchor", v.name).apply()

    var cardStyle: CardStyle
        get() = CardStyle.entries.getOrNull(sp.getInt("cardStyle", -1))
            ?: if (legacyStacked) CardStyle.WIDE else CardStyle.SQUARE
        set(v) = sp.edit().putInt("cardStyle", v.ordinal).apply()

    /** One of [PER_ROW]. */
    var perRow: Int
        get() = sp.getInt("perRow", if (legacyStacked) 1 else 0)
        set(v) = sp.edit().putInt("perRow", v).apply()

    var edgeGap: Int
        get() = sp.getInt("edgeGap", 24)
        set(v) = sp.edit().putInt("edgeGap", v).apply()

    var autoCloseSeconds: Int
        get() = sp.getInt("autoClose", 10)
        set(v) = sp.edit().putInt("autoClose", v).apply()

    /** Android key codes of the remote buttons that open the overlay, in the order they were added. */
    var triggerKeys: List<Int>
        get() = sp.getString("triggerKeys", null)?.split(',')?.mapNotNull { it.toIntOrNull() }
            // Earlier versions stored a single key.
            ?: listOf(sp.getInt("triggerKey", 0)).filter { it != 0 }
        set(v) = sp.edit().putString("triggerKeys", v.distinct().joinToString(",")).apply()

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
