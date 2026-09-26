package app.ghostly.core

import app.ghostly.core.mihomo.MihomoConfigBuilder
import app.ghostly.core.mihomo.MihomoIngress
import app.ghostly.core.mihomo.MihomoProfiles
import app.ghostly.core.model.AppSettings
import app.ghostly.core.model.CoreType
import app.ghostly.core.model.Profile
import app.ghostly.core.sub.SubscriptionParser
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MihomoTest {

    private val yaml = """
        #profile-title: Test Clash
        mixed-port: 7890
        allow-lan: true
        external-controller: 0.0.0.0:9090
        tun:
          enable: true
        base: &base
          udp: true
          skip-cert-verify: false
        proxies:
          - name: "🇩🇪 Germany"
            type: vless
            server: de.example.com
            port: 443
            uuid: 0a1b2c3d-0000-4000-8000-000000000001
            network: ws
            tls: true
            <<: *base
          - {name: "🇳🇱 NL", type: ss, server: 1.2.3.4, port: 8388, cipher: aes-128-gcm, password: "0123"}
        proxy-groups:
          - name: Proxy
            type: select
            proxies: [Auto, Europe, DIRECT]
          - name: Europe
            type: select
            proxies: ["🇩🇪 Germany", "🇳🇱 NL"]
          - name: Auto
            type: url-test
            proxies: ["🇩🇪 Germany", "🇳🇱 NL"]
            url: https://www.gstatic.com/generate_204
            interval: 300
        rules:
          - GEOSITE,category-ru,DIRECT
          - MATCH,Proxy
    """.trimIndent()

    @Test
    fun parsesClashYaml() {
        val parsed = SubscriptionParser.parse(yaml, emptyMap(), "p1")
        assertEquals("Test Clash", parsed.title)
        assertNotNull(parsed.mihomo)
        assertEquals(2, parsed.servers.size)
        val de = parsed.servers[0]
        assertEquals("vless", de.protocol)
        assertEquals("ws", de.transport)
        assertEquals("tls", de.security)
        assertEquals(443, de.port)
        // Merge key applied, leading-zero password kept as a string.
        assertEquals("true", de.mihomo!!["udp"]!!.jsonPrimitive.content)
        assertEquals("0123", parsed.servers[1].mihomo!!["password"]!!.jsonPrimitive.content)
        assertEquals("shadowsocks", parsed.servers[1].protocol)
    }

    @Test
    fun selectPathGoesThroughNestedSelectors() {
        val cfg = SubscriptionParser.parse(yaml, emptyMap(), "p1").mihomo!!
        assertEquals(listOf("Proxy" to "Europe", "Europe" to "🇳🇱 NL"), MihomoProfiles.selectPath(cfg, "🇳🇱 NL"))
    }

    @Test
    fun providerConfigGetsOurListenersAndController() {
        val parsed = SubscriptionParser.parse(yaml, emptyMap(), "p1")
        val profile = Profile(id = "p1", name = "t", servers = parsed.servers, mihomo = parsed.mihomo)
        val plan = MihomoConfigBuilder.build(parsed.servers[1], profile, AppSettings(blockAds = true), MihomoIngress(9999, "s3cret", appPort = 20001))
        val cfg = plan.config
        assertNull(cfg["mixed-port"])
        assertEquals("127.0.0.1:9999", cfg["external-controller"]!!.jsonPrimitive.content)
        assertEquals("false", (cfg["tun"] as JsonObject)["enable"]!!.jsonPrimitive.content)
        val rules = (cfg["rules"] as JsonArray).map { it.jsonPrimitive.content }
        assertEquals("GEOSITE,category-ads-all,REJECT", rules.first())
        assertEquals("MATCH,Proxy", rules.last())
        assertTrue((cfg["listeners"] as JsonArray).isNotEmpty())
        assertEquals(2, plan.picks.size)
    }

    @Test
    fun pingConfigHasOnlyProxiesAndController() {
        val parsed = SubscriptionParser.parse(yaml, emptyMap(), "p1")
        val cfg = MihomoProfiles.pingConfig(parsed.servers + parsed.servers, 5555, "k")
        assertEquals(2, (cfg["proxies"] as JsonArray).size)
        assertEquals("127.0.0.1:5555", cfg["external-controller"]!!.jsonPrimitive.content)
        assertEquals("normal", (cfg["dns"] as JsonObject)["enhanced-mode"]!!.jsonPrimitive.content)
        assertNull(cfg["tun"])
    }

    @Test
    fun offlineGroupsFromProfile() {
        val parsed = SubscriptionParser.parse(yaml, emptyMap(), "p1")
        val profile = Profile(id = "p1", name = "t", servers = parsed.servers, mihomo = parsed.mihomo)
        val groups = MihomoProfiles.offlineGroups(profile)
        assertEquals(listOf("Proxy", "Europe", "Auto"), groups.map { it.name })
        assertEquals(listOf("Selector", "Selector", "URLTest"), groups.map { it.type })
        assertEquals(setOf("Auto", "Europe"), groups[0].nestedGroups)

        val link = app.ghostly.core.link.LinkParser.parse("trojan://pass@example.com:443?sni=example.com#Test", "m:0")!!
        val linkGroups = MihomoProfiles.offlineGroups(Profile(id = "m", name = "m", servers = listOf(link)))
        assertEquals(MihomoConfigBuilder.MAIN_GROUP, linkGroups[0].name)
        assertTrue("Test" in linkGroups[0].members)
    }

    @Test
    fun linksGetOwnGroups() {
        val s = app.ghostly.core.link.LinkParser.parse("trojan://pass@example.com:443?sni=example.com#Test", "m:0")!!
        val settings = AppSettings(core = CoreType.MIHOMO)
        assertTrue(MihomoConfigBuilder.wants(s, null, settings))
        val plan = MihomoConfigBuilder.build(s, null, settings, MihomoIngress(9999, "x"))
        assertEquals(s.link, plan.links)
        val groups = (plan.config["proxy-groups"] as JsonArray).map { (it as JsonObject)["name"]!!.jsonPrimitive.content }
        assertEquals(listOf(MihomoConfigBuilder.MAIN_GROUP, MihomoConfigBuilder.AUTO_GROUP, MihomoConfigBuilder.FALLBACK_GROUP), groups)
        assertEquals(0, plan.picks.single().providerIndex)
    }
}
