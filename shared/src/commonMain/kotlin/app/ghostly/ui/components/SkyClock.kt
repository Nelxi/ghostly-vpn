package app.ghostly.ui.components

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

/** Milliseconds of the device's local wall clock: epoch time shifted by the time zone's current offset. */
internal expect fun localWallClockMs(): Long

private const val DAY_MS = 86_400_000L
private const val MINUTE_MS = 60_000L

/** Sunrise and sunset in local minutes. Fixed (no season or latitude): a calm, readable day. */
internal const val SUNRISE_MIN = 6 * 60 + 30
internal const val SUNSET_MIN = 19 * 60 + 30

/**
 * The light of the lake at a moment of the local day. Every value is smooth in time, so the scene can be
 * drawn from it frame by frame without jumps (at midnight too).
 */
internal class DayLight(
    /** Sun height: 1 at noon, 0 on the horizon, below 0 at night (−1 at the deepest point of the night). */
    val sunAlt: Float,
    /** Sun's way over the sky, 0 at sunrise … 1 at sunset (outside the day it is clamped). */
    val sunPath: Float,
    /** Moon height over the night: 0 at sunset and sunrise, 1 in the middle of the night; 0 during the day. */
    val moonAlt: Float,
    /** Moon's way over the night sky, 0 … 1. */
    val moonPath: Float,
    /** 0 at night … 1 in full daylight (twilight in between). */
    val day: Float,
    /** Warm light of a low sun: dawn and sunset (0 … 1). */
    val golden: Float,
    /** 1 before noon (dawn, morning), 0 after: dawn is pinker, sunset more orange. */
    val morning: Float,
)

private fun smoothstep(x: Float): Float {
    val t = x.coerceIn(0f, 1f)
    return t * t * (3f - 2f * t)
}

/** Light at [minute] of the local day (0 … 1440, fractions allowed). */
internal fun dayLight(minute: Float): DayLight {
    val dayLen = (SUNSET_MIN - SUNRISE_MIN).toFloat()
    val nightLen = 1440f - dayLen
    // Minutes since sunrise, wrapped: the day runs 0 … dayLen, the night dayLen … 1440.
    val x = ((minute - SUNRISE_MIN) % 1440f + 1440f) % 1440f
    val isDay = x < dayLen
    val sunPath = if (isDay) x / dayLen else if (x - dayLen < nightLen / 2f) 1f else 0f
    val nightQ = if (isDay) 0f else (x - dayLen) / nightLen
    val sunAlt = if (isDay) sin(PI.toFloat() * x / dayLen) else -sin(PI.toFloat() * nightQ)
    val moonAlt = if (isDay) 0f else sin(PI.toFloat() * nightQ)
    // Civil twilight: the sky starts to brighten while the sun is still a little below the horizon.
    val day = smoothstep((sunAlt + 0.10f) / 0.38f)
    // Strongest with the sun on the horizon, gone a little above it and in the dark below it.
    val golden = smoothstep(1f - abs(sunAlt - 0.02f) / 0.30f)
    val morning = if (minute % 1440f < 13 * 60f) 1f else 0f
    return DayLight(sunAlt, sunPath, moonAlt, nightQ, day, golden, morning)
}

/** What falls from the sky. */
internal enum class Weather { CLEAR, RAIN, SNOW }

/** Weather of one stretch of time and how strong it is right now (0 … 1, it comes and goes smoothly). */
internal class WeatherNow(val kind: Weather, val amount: Float)

/** Each stretch of weather lasts this long; rain and snow come and go at random between them. */
internal const val WEATHER_SLOT_MS = 25 * MINUTE_MS
private const val WEATHER_FADE_MS = 3 * MINUTE_MS

/** Same stretch, same weather on every device: it is picked from the slot's number, not from a random seed. */
internal fun weatherOf(slot: Long): Pair<Weather, Float> {
    var z = slot * -0x61c8864680b583ebL + 0x3C6EF372FE94F82AL
    z = (z xor (z ushr 30)) * -0x40a7b892e31b1a47L
    z = (z xor (z ushr 27)) * -0x6b2fb644ecceee15L
    z = z xor (z ushr 31)
    val r = ((z ushr 11).toDouble() / (1L shl 53).toDouble()).toFloat()
    val strength = 0.55f + 0.45f * (((z ushr 3) and 0xFF).toFloat() / 255f)
    return when {
        r < 0.52f -> Weather.CLEAR to 0f
        r < 0.78f -> Weather.RAIN to strength
        else -> Weather.SNOW to strength
    }
}

/** Fixed weather for screenshots (see the JVM's `ghostly.sky` property); null = the real schedule. */
internal var weatherOverride: WeatherNow? = null

/** The weather at local wall-clock time [wallMs]: it fades in and out over a few minutes at each slot's ends. */
internal fun weatherAt(wallMs: Long): WeatherNow {
    weatherOverride?.let { return it }
    val slot = floorDiv(wallMs, WEATHER_SLOT_MS)
    val inSlot = wallMs - slot * WEATHER_SLOT_MS
    val (kind, strength) = weatherOf(slot)
    if (kind == Weather.CLEAR) return WeatherNow(Weather.CLEAR, 0f)
    // Run on into the next slot when it has the same weather, so a long rain doesn't stop for a moment.
    val fadeIn = if (weatherOf(slot - 1).first == kind) 1f else smoothstep(inSlot.toFloat() / WEATHER_FADE_MS)
    val fadeOut = if (weatherOf(slot + 1).first == kind) 1f else smoothstep((WEATHER_SLOT_MS - inSlot).toFloat() / WEATHER_FADE_MS)
    return WeatherNow(kind, strength * fadeIn * fadeOut)
}

private fun floorDiv(a: Long, b: Long): Long {
    val q = a / b
    return if ((a % b != 0L) && ((a xor b) < 0L)) q - 1 else q
}

/** Minutes since local midnight at local wall-clock time [wallMs]. */
internal fun minuteOfDay(wallMs: Long): Float = (((wallMs % DAY_MS) + DAY_MS) % DAY_MS).toFloat() / MINUTE_MS
