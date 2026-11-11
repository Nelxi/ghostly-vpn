package app.ghostly.core

import app.ghostly.core.xray.ServerAddressPin
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ServerAddressPinTest {

    private val config = Json.parseToJsonElement(
        """
        {"outbounds":[
          {"tag":"v","protocol":"vless","settings":{"vnext":[{"address":"nl.example.net","port":21443,"users":[{"id":"x"}]}]},
           "streamSettings":{"network":"tcp","security":"reality","realitySettings":{"serverName":"www.philips.com"}}},
          {"tag":"h","protocol":"hysteria","settings":{"version":2,"address":"srv.example.net","port":443},
           "streamSettings":{"network":"hysteria","security":"tls","tlsSettings":{"serverName":"srv.example.net"}}},
          {"tag":"w","protocol":"vless","settings":{"vnext":[{"address":"nl.example.net","port":21443}]},
           "streamSettings":{"network":"ws","security":"tls","wsSettings":{"path":"/live"}}},
          {"tag":"ip","protocol":"vless","settings":{"vnext":[{"address":"1.2.3.4","port":1}]}},
          {"tag":"direct","protocol":"freedom","settings":{"address":"not.a.server"}}
        ]}
        """,
    ).jsonObject

    private fun ob(c: JsonObject, i: Int) = (c["outbounds"] as JsonArray)[i].jsonObject

    @Test
    fun findsServerNamesOnly() {
        assertEquals(setOf("nl.example.net", "srv.example.net"), ServerAddressPin.names(config))
        assertFalse(ServerAddressPin.isDomain("1.2.3.4"))
        assertFalse(ServerAddressPin.isDomain("::1"))
        assertTrue(ServerAddressPin.isDomain("de.ghostlynex.fun"))
    }

    @Test
    fun swapsAddressesAndKeepsTheName() {
        val out = ServerAddressPin.apply(config, mapOf("nl.example.net" to "9.9.9.1", "srv.example.net" to "9.9.9.2"))
        val v = ob(out, 0)
        assertEquals("9.9.9.1", (v["settings"]!!.jsonObject["vnext"] as JsonArray)[0].jsonObject["address"]!!.jsonPrimitive.content)
        // Reality keeps its own server name, nothing else is added.
        assertNull(v["streamSettings"]!!.jsonObject["tlsSettings"])
        val h = ob(out, 1)
        assertEquals("9.9.9.2", h["settings"]!!.jsonObject["address"]!!.jsonPrimitive.content)
        assertEquals("srv.example.net", h["streamSettings"]!!.jsonObject["tlsSettings"]!!.jsonObject["serverName"]!!.jsonPrimitive.content)
        // TLS + WebSocket without a server name or host: both get the original name.
        val w = ob(out, 2)["streamSettings"]!!.jsonObject
        assertEquals("nl.example.net", w["tlsSettings"]!!.jsonObject["serverName"]!!.jsonPrimitive.content)
        assertEquals("nl.example.net", w["wsSettings"]!!.jsonObject["host"]!!.jsonPrimitive.content)
        assertEquals("/live", w["wsSettings"]!!.jsonObject["path"]!!.jsonPrimitive.content)
        // Untouched: an IP server and a non-proxy outbound.
        assertEquals(ob(config, 3), ob(out, 3))
        assertEquals(ob(config, 4), ob(out, 4))
    }

    @Test
    fun unknownNamesStayAsTheyAre() {
        assertEquals(config, ServerAddressPin.apply(config, mapOf("other.net" to "9.9.9.9")))
    }
}
