package app.ghostly.ui

import app.ghostly.ui.components.flagCode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class FlagTest {
    @Test
    fun emojiFlagsBecomeCountryCodes() {
        assertEquals("FI", flagCode("\uD83C\uDDEB\uD83C\uDDEE"))
        assertEquals("RU", flagCode("\uD83C\uDDF7\uD83C\uDDFA"))
        assertEquals("EU", flagCode("\uD83C\uDDEA\uD83C\uDDFA"))
        assertEquals("NL", flagCode("nl"))
        assertNull(flagCode("\uD83D\uDC7B"))
        assertNull(flagCode(null))
    }
}

class ServerTitleTest {
    private fun titled(name: String) = app.ghostly.core.model.Server(id = "s", name = name, protocol = "vless").title()

    @Test
    fun iconTakesFirstFlagAnywhere() {
        // Leading flag, as before.
        assertEquals("🇳🇱", titled("🇳🇱 Амстердам").flag)
        // Flag after the words (Happ-style names): still the icon, removed from the title text.
        val t = titled("Автовыбор 🇪🇺 🤝🇷🇺")
        assertEquals("🇪🇺", t.flag)
        assertEquals("Автовыбор 🤝🇷🇺", t.title)
        // No flag at all.
        assertNull(titled("Просто сервер").flag)
    }
}

class ServerSectionTest {
    private fun server(name: String, pool: String?) = app.ghostly.core.model.Server(id = name, name = name, protocol = "vless", pool = pool)

    @Test
    fun countryIsSplitIntoRegularAndLte() {
        assertEquals(ServerSection("🇫🇮", lte = false), server("🇫🇮 ⚡ Vision", "reg").section())
        assertEquals(ServerSection("🇫🇮", lte = true), server("🇫🇮 LTE · Основной", "wl").section())
        // The Hysteria2 white list is sent as pool "wl" too.
        assertEquals(ServerSection("🇩🇪", lte = true), server("🇩🇪 LTE · Hysteria2", "wl").section())
        assertNull(server("Просто сервер", null).section())
    }

    @Test
    fun lteRowsDropThePrefixUnderTheirHeader() {
        val t = server("🇫🇮 LTE · Резерв", "wl").title().withoutLte()
        assertEquals("Резерв", t.title)
        assertNull(t.subtitle)
        // Names that don't start with it stay as they are.
        assertEquals("Белые списки", server("🇫🇮 Белые списки", "wl").title().withoutLte().title)
        assertEquals("LTE", server("🇫🇮 LTE", "wl").title().withoutLte().title)
    }
}

class FlagPairTest {
    @Test
    fun findsFlagsAnywhere() {
        val re = Regex("[\\x{1F1E6}-\\x{1F1FF}]{2}")
        val name = "Amsterdam \uD83C\uDDF3\uD83C\uDDF1\uD83C\uDDE9\uD83C\uDDEA"
        assertEquals(listOf("NL", "DE"), re.findAll(name).map { flagCode(it.value) }.toList())
    }
}
