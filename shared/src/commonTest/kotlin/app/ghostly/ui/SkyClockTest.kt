package app.ghostly.ui

import app.ghostly.ui.components.SUNRISE_MIN
import app.ghostly.ui.components.SUNSET_MIN
import app.ghostly.ui.components.WEATHER_SLOT_MS
import app.ghostly.ui.components.Weather
import app.ghostly.ui.components.dayLight
import app.ghostly.ui.components.minuteOfDay
import app.ghostly.ui.components.weatherAt
import app.ghostly.ui.components.weatherOf
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SkyClockTest {

    @Test
    fun noonIsBrightAndMidnightIsDark() {
        val noon = dayLight(13 * 60f)
        assertTrue(noon.day > 0.99f, "noon day=${noon.day}")
        assertTrue(noon.sunAlt > 0.95f)
        assertEquals(0f, noon.moonAlt)
        val midnight = dayLight(60f)
        assertTrue(midnight.day < 0.01f, "night day=${midnight.day}")
        assertTrue(midnight.moonAlt > 0.95f)
        assertTrue(midnight.golden < 0.01f)
    }

    @Test
    fun sunsetAndDawnAreGolden() {
        assertTrue(dayLight(SUNSET_MIN.toFloat()).golden > 0.9f)
        assertTrue(dayLight(SUNRISE_MIN.toFloat()).golden > 0.9f)
        assertEquals(1f, dayLight(SUNRISE_MIN.toFloat()).morning)
        assertEquals(0f, dayLight(SUNSET_MIN.toFloat()).morning)
    }

    @Test
    fun lightHasNoJumps() {
        var prev = dayLight(0f)
        var m = 0.5f
        while (m <= 1440f) {
            val cur = dayLight(m % 1440f)
            assertTrue(abs(cur.day - prev.day) < 0.02f, "day jumps at $m")
            assertTrue(abs(cur.sunAlt - prev.sunAlt) < 0.02f, "sun jumps at $m")
            assertTrue(abs(cur.moonAlt - prev.moonAlt) < 0.02f, "moon jumps at $m")
            prev = cur
            m += 0.5f
        }
    }

    @Test
    fun minuteOfDayWrapsNegativeTimes() {
        assertEquals(1439f, minuteOfDay(-60_000L))
        assertEquals(90f, minuteOfDay(86_400_000L * 3 + 90 * 60_000L))
    }

    @Test
    fun weatherIsStableAndMixed() {
        val kinds = (0L until 400L).map { weatherOf(it).first }
        assertTrue(kinds.count { it == Weather.CLEAR } in 140..280)
        assertTrue(kinds.count { it == Weather.RAIN } > 40)
        assertTrue(kinds.count { it == Weather.SNOW } > 30)
        assertEquals(weatherOf(12345L), weatherOf(12345L))
    }

    @Test
    fun weatherFadesAtTheEdgesOfItsSlot() {
        // A slot with rain or snow whose neighbours are different: nothing at its very start, full in the middle.
        val slot = (1L..10_000L).first { s ->
            val k = weatherOf(s).first
            k != Weather.CLEAR && weatherOf(s - 1).first != k && weatherOf(s + 1).first != k
        }
        val start = slot * WEATHER_SLOT_MS
        assertTrue(weatherAt(start).amount < 0.01f)
        assertTrue(weatherAt(start + WEATHER_SLOT_MS / 2).amount > 0.5f)
        assertTrue(weatherAt(start + WEATHER_SLOT_MS - 1).amount < 0.01f)
    }
}
