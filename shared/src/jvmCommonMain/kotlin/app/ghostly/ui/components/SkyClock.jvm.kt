package app.ghostly.ui.components

/**
 * `-Dghostly.sky=HH:MM[,rain|snow]` pins the lake's time of day (and weather) for screenshots
 * (`./gradlew :desktopApp:shots -Psky=19:20,rain`). The clock still runs on from that moment.
 */
private val pinned: Long? by lazy {
    val spec = System.getProperty("ghostly.sky")?.takeIf { it.isNotBlank() } ?: return@lazy null
    val parts = spec.split(',')
    val hm = parts[0].split(':').mapNotNull { it.trim().toIntOrNull() }
    if (hm.size != 2) return@lazy null
    when (parts.getOrNull(1)?.trim()) {
        "rain" -> weatherOverride = WeatherNow(Weather.RAIN, 1f)
        "snow" -> weatherOverride = WeatherNow(Weather.SNOW, 1f)
        else -> weatherOverride = WeatherNow(Weather.CLEAR, 0f)
    }
    val now = realWall()
    val dayStart = now - Math.floorMod(now, 86_400_000L)
    dayStart + (hm[0] * 60L + hm[1]) * 60_000L - now
}

private fun realWall(): Long {
    val now = System.currentTimeMillis()
    return now + java.util.TimeZone.getDefault().getOffset(now)
}

internal actual fun localWallClockMs(): Long = realWall() + (pinned ?: 0L)
