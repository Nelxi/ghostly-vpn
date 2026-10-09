package app.ghostly.core

import app.ghostly.core.link.LinkParser
import app.ghostly.core.model.AppSettings
import app.ghostly.core.model.ExtraProxy
import app.ghostly.core.model.ExtraProxyType
import app.ghostly.core.xray.Ingress
import app.ghostly.core.xray.LocalProxy
import app.ghostly.core.xray.XrayConfigBuilder
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ExtraProxyTest {
    private fun link(host: String) =
        "vless://11111111-2222-3333-4444-555555555555@$host:443?type=tcp&security=reality&pbk=abc&fp=chrome&sni=example.com&sid=01&flow=xtls-rprx-vision#$host"

    private val main = LinkParser.parse(link("main.example"), "t:0")!!
    private val other = LinkParser.parse(link("other.example"), "t:1")!!
    private val ingress = Ingress.Proxy(LocalProxy(10808, 10809), 0)

    private fun JsonObject.tags(key: String) = (this[key] as JsonArray).map { it.jsonObject["tag"]?.jsonPrimitive?.content }

    @Test
    fun extraProxyGetsItsOwnInboundOutboundAndFirstRule() {
        val x = ExtraProxy(id = "a", type = ExtraProxyType.HTTP, port = 11080, auth = true, user = "u", pass = "p", outbound = XrayConfigBuilder.proxyOutbound(other))
        val cfg = XrayConfigBuilder.build(main, AppSettings(extraProxies = listOf(x)), ingress)

        val inbound = (cfg["inbounds"] as JsonArray).map { it.jsonObject }.first { it["tag"]!!.jsonPrimitive.content == "xp-a" }
        assertEquals("http", inbound["protocol"]!!.jsonPrimitive.content)
        assertEquals(11080, inbound["port"]!!.jsonPrimitive.content.toInt())
        assertEquals("u", inbound["settings"]!!.jsonObject["accounts"]!!.jsonArray[0].jsonObject["user"]!!.jsonPrimitive.content)

        // Its server is a second outbound; the main one stays the first.
        val outbounds = (cfg["outbounds"] as JsonArray).map { it.jsonObject }
        assertEquals("proxy", outbounds[0]["tag"]!!.jsonPrimitive.content)
        assertTrue(outbounds.first { it["tag"]!!.jsonPrimitive.content == "xp-a" }.toString().contains("other.example"))

        // Everything from that inbound goes to that server, before any split rule.
        val first = cfg["routing"]!!.jsonObject["rules"]!!.jsonArray[0].jsonObject
        assertEquals("xp-a", first["inboundTag"]!!.jsonArray[0].jsonPrimitive.content)
        assertEquals("xp-a", first["outboundTag"]!!.jsonPrimitive.content)
    }

    @Test
    fun socksWithoutPasswordIsNoauth() {
        val x = ExtraProxy(id = "b", port = 11081, outbound = XrayConfigBuilder.proxyOutbound(other))
        val cfg = XrayConfigBuilder.build(main, AppSettings(extraProxies = listOf(x)), ingress)
        val inbound = (cfg["inbounds"] as JsonArray).map { it.jsonObject }.first { it["tag"]!!.jsonPrimitive.content == "xp-b" }
        assertEquals("noauth", inbound["settings"]!!.jsonObject["auth"]!!.jsonPrimitive.content)
    }

    @Test
    fun offUnboundOrClashingProxiesStayOut() {
        val out = XrayConfigBuilder.proxyOutbound(other)
        val proxies = listOf(
            ExtraProxy(id = "off", port = 11080, enabled = false, outbound = out),
            ExtraProxy(id = "lost", port = 11081, serverName = "gone"),
            // the main SOCKS port: a second listener there would stop the core
            ExtraProxy(id = "main", port = 10808, outbound = out),
            ExtraProxy(id = "ok", port = 11082, outbound = out),
            ExtraProxy(id = "twin", port = 11082, outbound = out),
        )
        val cfg = XrayConfigBuilder.build(main, AppSettings(extraProxies = proxies), ingress)
        assertEquals(listOf("xp-ok"), cfg.tags("inbounds").filter { it!!.startsWith("xp-") })
        assertEquals(listOf("xp-ok"), cfg.tags("outbounds").filter { it!!.startsWith("xp-") })
    }

    @Test
    fun balancerHasNoSingleOutbound() {
        assertNotNull(XrayConfigBuilder.proxyOutbound(other))
        assertNull(XrayConfigBuilder.proxyOutbound(other.copy(protocol = "balancer")))
    }
}
