package app.ghostly.core.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Traffic pools: the CDN white lists, «Белые списки 2» over Hysteria2, and the preference applied
 * when the app leaves the white lists for a regular server.
 */
class PoolTest {

    private fun server(name: String, protocol: String = "vless", pool: String? = null) =
        Server(id = name, name = name, protocol = protocol, pool = pool)

    @Test
    fun guessesWhitelistPoolsFromNames() {
        assertEquals(POOL_WL, guessPool("🇷🇺Белые списки 1"))
        assertEquals(POOL_WL, guessPool("Белые списки · Стандарт"))
        assertEquals(POOL_WL2, guessPool("Белые списки 2 · Hysteria2"))
        assertEquals(POOL_WL2, guessPool("WHITELIST HY2"))
        assertEquals(null, guessPool("🇫🇮 Обычный"))
        // The CDN «Белые списки 2» stays a plain white list: only a protocol hint makes it the second pool.
        assertEquals(POOL_WL, guessPool("🇷🇺Белые списки 2"))
    }

    @Test
    fun bothWhitelistPoolsCountAsWhitelists() {
        assertTrue(server("wl", pool = POOL_WL).isWhitelist)
        assertTrue(server("wl2", pool = POOL_WL2).isWhitelist)
        assertFalse(server("reg", pool = POOL_REG).isWhitelist)
        assertFalse(server("none").isWhitelist)
        assertTrue(server("wl2", pool = POOL_WL2).isWhitelistHysteria)
        assertFalse(server("wl", pool = POOL_WL).isWhitelistHysteria)
        // What the Ghostly server actually sends: pool "wl" plus the Hysteria2 protocol.
        assertTrue(server("🇫🇮 Белые списки 2 · Hysteria2", protocol = "hysteria", pool = POOL_WL).isWhitelistHysteria)
        assertFalse(server("🇫🇮 Hysteria2", protocol = "hysteria", pool = POOL_REG).isWhitelistHysteria)
    }

    @Test
    fun hysteriaOnTheFinnishNodeWinsAsARegularServer() {
        val fiHy2 = server("🇫🇮 Hysteria2", protocol = "hysteria")
        val fiVision = server("🇫🇮 ⚡ Vision")
        val deHy2 = server("🇩🇪 Hysteria2", protocol = "hysteria")
        val dePlain = server("🇩🇪 Обычный")

        assertTrue(regularPreference(fiHy2) > regularPreference(fiVision))
        assertTrue(regularPreference(fiHy2) > regularPreference(deHy2))
        assertTrue(regularPreference(deHy2) > regularPreference(dePlain))
        assertEquals(0, regularPreference(dePlain))
    }

    @Test
    fun aliasesForFinlandAlsoCount() {
        assertEquals(regularPreference(server("Hysteria2", protocol = "hysteria")), regularPreference(server("Finland Hysteria2", protocol = "hysteria")) - 1)
        assertEquals(1, regularPreference(server("Финляндия · Обычный")))
    }

    @Test
    fun finnishWhiteList2WinsInsideTheWhiteLists() {
        val fiWl2 = server("🇫🇮 Белые списки 2 · Hysteria2", protocol = "hysteria", pool = POOL_WL2)
        val deWl2 = server("🇩🇪 Белые списки 2 · Hysteria2", protocol = "hysteria", pool = POOL_WL2)
        val fiWl = server("🇫🇮 Белые списки", protocol = "vless", pool = POOL_WL)
        val deWl = server("🇩🇪 Белые списки", protocol = "vless", pool = POOL_WL)

        assertTrue(whitelistPreference(fiWl2) > whitelistPreference(deWl2))
        assertTrue(whitelistPreference(deWl2) > whitelistPreference(fiWl))
        assertTrue(whitelistPreference(fiWl) > whitelistPreference(deWl))
        assertEquals(0, whitelistPreference(deWl))
        // Same ranking with the pool the server really sends for «Белые списки 2».
        val fiWl2AsWl = server("🇫🇮 Белые списки 2 · Hysteria2", protocol = "hysteria", pool = POOL_WL)
        assertEquals(whitelistPreference(fiWl2), whitelistPreference(fiWl2AsWl))
    }

    @Test
    fun poolTitlesCoverTheSecondWhiteList() {
        assertEquals("Белые списки", TrafficPool(POOL_WL, 0, 1).title)
        assertEquals("Белые списки 2", TrafficPool(POOL_WL2, 0, 1).title)
        assertEquals("Обычные серверы", TrafficPool(POOL_REG, 0, 1).title)
        assertTrue(TrafficPool(POOL_WL2, 0, 1).isWhitelist)
    }
}
