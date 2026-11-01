package app.ghostly.core.vpn

import app.ghostly.core.model.Ping
import app.ghostly.core.model.Server
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FailoverTest {

    private val now = 1_000_000_000L

    private fun server(id: String, name: String, host: String, port: Int = 443) =
        Server(id = id, name = name, protocol = "vless", host = host, port = port)

    private val fiTorrent = server("fi-torrent", "🇫🇮 Торрент", "78.17.1.122", 10447)
    private val fiXhttp = server("fi-xhttp", "🇫🇮 XHTTP", "78.17.1.122", 2083)
    private val fiVision = server("fi-vision", "🇫🇮 ⚡ Vision", "78.17.1.122", 10443)
    private val nlVision = server("nl-vision", "🇳🇱 ⚡ Vision", "185.231.204.224")
    private val deVision = server("de-vision", "🇩🇪 ⚡ Vision", "185.231.204.224", 11443)

    @Test
    fun aBlockedAddressIsNotWalkedEntryByEntry() {
        // Finland's address is cut: its XHTTP still has a green ping from before the block, the others too.
        val pings = mapOf(
            "fi-xhttp" to Ping(30, now - 60_000),
            "fi-vision" to Ping(35, now - 60_000),
            "nl-vision" to Ping(80, now - 60_000),
            "de-vision" to Ping(95, now - 60_000),
        )
        val order = Failover.rank(listOf(fiXhttp, fiVision, nlVision, deVision), fiTorrent, pings, emptyMap(), now)
        assertEquals(listOf("nl-vision", "de-vision", "fi-xhttp", "fi-vision"), order.map { it.id })
    }

    @Test
    fun anotherCountryBehindTheSameDoorStillComesFirst() {
        // FI, NL and DE all enter through the NL address: the country tells them apart.
        val fiDoor = server("fi-door", "🇫🇮 ⚡ Vision", "185.231.204.224", 10443)
        val deTorrent = server("de-torrent", "🇩🇪 Торрент", "185.231.204.224", 18443)
        val order = Failover.rank(listOf(deTorrent, fiDoor, nlVision), deVision, emptyMap(), emptyMap(), now)
        assertEquals(listOf("fi-door", "nl-vision", "de-torrent"), order.map { it.id })
    }

    @Test
    fun aServerThatJustFailedSitsOutForAWhile() {
        val penalties = mapOf("nl-vision" to now - 60_000)
        val order = Failover.rank(listOf(nlVision, deVision, fiVision), fiTorrent, emptyMap(), penalties, now)
        assertEquals(listOf("de-vision", "fi-vision"), order.map { it.id })
        // Its address and country count as suspicious too, while the penalty lasts.
        val nlXhttp = server("nl-xhttp", "🇳🇱 XHTTP", "185.231.204.224", 2083)
        assertEquals("de-vision", Failover.rank(listOf(nlXhttp, deVision, nlVision), fiTorrent, emptyMap(), penalties, now).first().id)
        // Ten minutes later it is a candidate again.
        val later = now + Failover.PENALTY_MS
        assertTrue(Failover.rank(listOf(nlVision), fiTorrent, emptyMap(), penalties, later).isNotEmpty())
        assertTrue(Failover.active(penalties, later).isEmpty())
    }

    @Test
    fun insideAGroupWhatWorksNowWins() {
        val a = server("a", "🇳🇱 A", "10.0.0.1")
        val b = server("b", "🇳🇱 B", "10.0.0.2")
        val c = server("c", "🇳🇱 C", "10.0.0.3")
        val d = server("d", "🇳🇱 D", "10.0.0.4")
        val e = server("e", "🇳🇱 E", "10.0.0.5")
        val pings = mapOf(
            "a" to Ping(-1, now - 10_000), // failed a moment ago
            "b" to Ping(300, now - 30 * 60_000), // worked, long ago
            "c" to Ping(120, now - 20_000), // works now
            "e" to Ping(60, now - 20_000), // works now, faster
        )
        val order = Failover.rank(listOf(a, b, c, d, e), fiTorrent, pings, emptyMap(), now)
        assertEquals(listOf("e", "c", "b", "d", "a"), order.map { it.id })
    }

    @Test
    fun theFailedServerAndBalancersAreNeverCandidates() {
        val auto = Server(id = "auto", name = "Авто", protocol = "balancer")
        val order = Failover.rank(listOf(fiTorrent, auto, nlVision), fiTorrent, emptyMap(), emptyMap(), now)
        assertEquals(listOf("nl-vision"), order.map { it.id })
    }

    @Test
    fun countryIsTheFirstFlagInTheName() {
        assertEquals("🇳🇱", Failover.country(nlVision))
        assertEquals("🇩🇪", Failover.country(server("x", "LTE · 🇩🇪 Германия", "v.frameloft.online")))
        assertNull(Failover.country(server("y", "Server 1", "example.com")))
        assertEquals("example.com", Failover.entry(server("z", "Z", " Example.COM ")))
    }
}
