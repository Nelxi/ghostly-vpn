package app.ghostly.ui

import app.ghostly.core.model.Server
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.assertFalse

class ServerSearchTest {
    private val server = Server("s1", "🇩🇪 ⚡ Быстрый", "vless", "grpc", "reality", "edge.example", 443)

    @Test fun findsCountryHeadersAndProtocolTogether() {
        assertTrue(server.matchesQuery("Германия"))
        assertTrue(server.matchesQuery(" de   GRPC "))
        assertTrue(server.matchesQuery("edge.example reality"))
        assertTrue(server.matchesQuery(""))
        assertFalse(server.matchesQuery("Германия hysteria"))
    }
}
