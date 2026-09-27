package app.ghostly.ui

import kotlin.test.Test
import kotlin.test.assertEquals

class FormatTest {
    @Test
    fun calendarDate() {
        assertEquals("1 января 1970", Format.date(0, 0))
        assertEquals("29 февраля 2000", Format.date(951_782_400, 0))
        assertEquals("21 сентября 2026", Format.date(1_790_000_000, 180))
        // 23:00 UTC is already the next day at UTC+3.
        assertEquals("2 января 1970", Format.date(82_800, 180))
        assertEquals("31 декабря 1969", Format.date(-1, 0))
    }
}
