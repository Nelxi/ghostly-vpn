package app.ghostly.core.vpn

import app.ghostly.core.model.POOL_WL
import app.ghostly.core.model.Server
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class BlockCheckTest {

    @Test
    fun whiteListIsNotCheckedAgainstTheCdnRoot() {
        val server = Server(
            id = "wl",
            name = "🇫🇮 LTE",
            protocol = "vless",
            transport = "xhttp",
            security = "tls",
            host = "v.frameloft.online",
            port = 443,
            pool = POOL_WL,
        )
        // The CDN edge is not the server itself; a failed GET / there was falsely reported as DPI.
        assertNull(BlockCheck.target(server))
    }

    @Test
    fun regularTlsServerUsesItsHostAsSni() {
        val server = Server(
            id = "regular",
            name = "🇫🇮 ⚡ Vision",
            protocol = "vless",
            transport = "tcp",
            security = "tls",
            host = "example.com",
            port = 443,
        )
        val target = BlockCheck.target(server)
        assertEquals("example.com", target?.host)
        assertEquals(443, target?.port)
        assertEquals("example.com", target?.sni)
    }
}
