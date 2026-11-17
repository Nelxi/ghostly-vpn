package app.ghostly.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class HeaderTextTest {
    @Test fun cyrillicPcNameIsTransliterated() {
        assertEquals("komp", headerSafe("комп"))
        assertEquals("DESKTOP-Vasya", headerSafe("DESKTOP-Вася"))
        assertEquals("Schuka", headerSafe("Щука"))
    }

    @Test fun asciiIsKept() {
        assertEquals("Nothing A059", headerSafe("Nothing A059"))
    }

    @Test fun anythingElseBecomesPrintableAscii() {
        val v = headerSafe("PCé中\t")
        assertEquals("PC___", v)
        assertEquals("__", headerSafe("中中"))
        assertEquals("PC", headerSafe("   "))
    }

    @Test fun userAgentFromCyrillicHostIsSendable() {
        val ua = "Happ/2.0.0 GhostlyVPN/0.3.57 (Windows 10.0; ${headerSafe("комп")})"
        assertTrue(ua.all { it in ' '..'~' }, ua)
    }
}
