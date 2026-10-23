package app.ghostly.core

import app.ghostly.core.link.LinkParser
import app.ghostly.core.model.AppSettings
import app.ghostly.core.model.DnsPreset
import app.ghostly.core.xray.Ingress
import app.ghostly.core.xray.XrayConfigBuilder
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertTrue

/** The core's own lookups must never be plain :53 — those hit the port-53 hijack and leave past the tunnel. */
class DnsLeakTest {
    private val server = LinkParser.parse("trojan://pass@example.com:443?sni=example.com#Test", "t:0")!!

    private fun tunnelServers(settings: AppSettings): List<String> {
        val cfg = XrayConfigBuilder.build(server, settings, Ingress.TunFd(1500, appPort = 20001))
        return cfg["dns"]!!.jsonObject["servers"]!!.jsonArray.mapNotNull {
            when (it) {
                is JsonPrimitive -> it.content
                // Split entries for Russian domains go straight out by design.
                is JsonObject -> if ("domains" in it) null else (it["address"] as? JsonPrimitive)?.content
                else -> null
            }
        }
    }

    @Test
    fun everyTunnelResolverIsEncrypted() {
        for (preset in listOf(DnsPreset.CLOUDFLARE, DnsPreset.GOOGLE, DnsPreset.QUAD9, DnsPreset.ADGUARD)) {
            val servers = tunnelServers(AppSettings(dns = preset))
            assertTrue(servers.isNotEmpty())
            servers.forEach { assertTrue(it.startsWith("https://"), "$preset: plain resolver $it") }
        }
    }
}
