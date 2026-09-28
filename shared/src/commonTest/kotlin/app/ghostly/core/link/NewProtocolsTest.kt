package app.ghostly.core.link

import app.ghostly.core.mihomo.visibleFor
import app.ghostly.core.model.CoreType
import app.ghostly.core.model.Profile
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class NewProtocolsTest {
    @Test
    fun wireguardBecomesXrayOutbound() {
        val s = assertNotNull(LinkParser.parse(
            "wireguard://cGsrUHJpdmF0ZUtleT0%3D@1.2.3.4:51820?publickey=UHViS2V5PQ%3D%3D&address=10.7.0.2%2F32,fd00::2&mtu=1280&reserved=1,2,3#WG", "w"))
        assertEquals("wireguard", s.protocol)
        val settings = s.outbound!!["settings"] as JsonObject
        assertEquals("cGsrUHJpdmF0ZUtleT0=", settings["secretKey"]!!.jsonPrimitive.content)
        assertEquals(listOf("10.7.0.2/32", "fd00::2/128"), (settings["address"] as JsonArray).map { it.jsonPrimitive.content })
        val peer = (settings["peers"] as JsonArray)[0] as JsonObject
        assertEquals("1.2.3.4:51820", peer["endpoint"]!!.jsonPrimitive.content)
        assertEquals(3, (settings["reserved"] as JsonArray).size)
        assertEquals("WG", s.name)
    }

    @Test
    fun mihomoOnlyLinksKeepTheLink() {
        val tuic = assertNotNull(LinkParser.parse("tuic://uuid:pass@h.example:443?congestion_control=bbr&alpn=h3&sni=h.example#T", "t"))
        assertEquals("tuic", tuic.protocol); assertNull(tuic.outbound)
        val any = assertNotNull(LinkParser.parse("anytls://pass@h.example:443?sni=h.example#A", "a"))
        assertEquals("anytls", any.protocol)
        val mieru = assertNotNull(LinkParser.parse("mieru://user:pw@h.example:2999?profile=p&port=2999&protocol=TCP#M", "m"))
        assertEquals("mieru", mieru.protocol)
        assertEquals(true, mieru.link!!.startsWith("mierus://"))
    }

    @Test
    fun eachCoreSeesOnlyWhatItRuns() {
        val wg = LinkParser.parse("wg://a2V5@1.2.3.4:51820?publickey=cHVi#WG", "1")!!
        val tuic = LinkParser.parse("tuic://u:p@h:443#T", "2")!!
        val vless = LinkParser.parse("vless://11111111-1111-1111-1111-111111111111@h:443?security=tls&type=tcp#V", "3")!!
        val p = Profile(id = "p", name = "p", servers = listOf(wg, tuic, vless))
        assertEquals(listOf("WG", "V"), p.visibleFor(CoreType.XRAY)!!.servers.map { it.name })
        assertEquals(listOf("T", "V"), p.visibleFor(CoreType.MIHOMO)!!.servers.map { it.name })
    }
}
