package app.ghostly.core.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Traffic pools: the CDN white lists and «Белые списки 2» over Hysteria2.
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
    fun poolTitlesCoverTheSecondWhiteList() {
        assertEquals("Белые списки", TrafficPool(POOL_WL, 0, 1).title)
        assertEquals("Белые списки 2", TrafficPool(POOL_WL2, 0, 1).title)
        assertEquals("Обычные серверы", TrafficPool(POOL_REG, 0, 1).title)
        assertTrue(TrafficPool(POOL_WL2, 0, 1).isWhitelist)
    }
}
