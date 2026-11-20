package app.ghostly.core

import app.ghostly.core.mihomo.MihomoConfigBuilder
import app.ghostly.core.mihomo.MihomoIngress
import app.ghostly.core.mihomo.MihomoProfiles
import app.ghostly.core.model.AppSettings
import app.ghostly.core.model.CoreType
import app.ghostly.core.model.Profile
import app.ghostly.core.model.SplitMode
import app.ghostly.core.sub.SubscriptionParser
import app.ghostly.core.xray.Ingress
import app.ghostly.core.xray.XrayConfigBuilder
import kotlinx.serialization.json.Json
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
          - name: PROXY
            type: select
            hidden: true
            proxies: ["🇩🇪 Germany", "🇳🇱 NL"]
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
    fun hiddenGroupsAreMarked() {
        val cfg = SubscriptionParser.parse(yaml, emptyMap(), "p1").mihomo!!
        val groups = MihomoProfiles.groups(cfg)
        assertTrue(groups.getValue("PROXY").hidden)
        assertTrue(groups.filterValues { !it.hidden }.keys == setOf("Proxy", "Europe", "Auto"))
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

    @Test
    fun mixedLinksUseOrderedFallbackAndKeepManualPicksAligned() {
        val regular = app.ghostly.core.link.LinkParser.parse("trojan://pass@example.com:443#Regular", "m:0")!!
        val lte = regular.copy(id = "m:1", name = "LTE", link = "trojan://pass@lte.example.com:443#LTE")
        val reserve = regular.copy(id = "m:2", name = "Резерв 08 · Может не работать", link = "trojan://pass@reserve.example.com:443#Reserve")
        val profile = Profile("m", "Test", servers = listOf(reserve, lte, regular))
        val plan = MihomoConfigBuilder.build(reserve, profile, AppSettings(), MihomoIngress(9999, "x"))
        assertEquals(listOf(regular.link, lte.link, reserve.link).joinToString("\n"), plan.links)
        assertEquals("fallback", MihomoProfiles.groups(plan.config).getValue(MihomoConfigBuilder.AUTO_GROUP).type)
        assertEquals(2, plan.picks.single().providerIndex)
        assertEquals(plan.picks, MihomoConfigBuilder.picksFor(reserve, profile))
    }

    @Test
    fun customExecutablesUseExactPathsInBothCores() {
        val first = """C:\Program Files\First\client.exe"""
        val second = """C:\Tools (portable, x64)\client.exe"""
        val excluded = """C:\Unchecked\client.exe"""
        val server = app.ghostly.core.link.LinkParser.parse("trojan://pass@example.com:443?sni=example.com#Test", "m:0")!!
        val settings = AppSettings(
            splitMode = SplitMode.BYPASS_SELECTED,
            splitApps = setOf(first, second, "legacy.exe", "com.example.android"),
            customAppPaths = setOf(first, second, excluded),
        )
        val restored = Json.decodeFromString(AppSettings.serializer(), Json.encodeToString(AppSettings.serializer(), settings))
        assertEquals(settings.customAppPaths, restored.customAppPaths)
        assertEquals(settings.splitApps, restored.splitApps)

        for (mode in SplitMode.entries) {
            val config = restored.withSplitMode(mode)
            val mihomo = MihomoConfigBuilder.build(server, null, config, MihomoIngress(9999, "x")).config
            val rules = (mihomo["rules"] as JsonArray).map { it.jsonPrimitive.content }
            val processRules = rules.filter { it.startsWith("PROCESS-") }

            val xray = XrayConfigBuilder.build(server, config, Ingress.TunSystem(1500, "Ghostly"))
            val xrayRules = (xray["routing"] as JsonObject)["rules"] as JsonArray
            val processPaths = xrayRules.flatMap { (it as JsonObject)["process"] as? JsonArray ?: emptyList() }
                .map { it.jsonPrimitive.content }

            if (mode == SplitMode.OFF) {
                assertTrue(processRules.isEmpty())
                assertTrue(processPaths.isEmpty())
            } else {
                val target = if (mode == SplitMode.BYPASS_SELECTED) "DIRECT" else MihomoConfigBuilder.MAIN_GROUP
                assertEquals(listOf(
                    "PROCESS-PATH,$first,$target",
                    """PROCESS-PATH-REGEX,^C:\\Tools \(portable\x2c x64\)\\client\.exe$,$target""",
                    "PROCESS-NAME,legacy.exe,$target",
                ), processRules)
                assertEquals(listOf(first.replace('\\', '/'), second.replace('\\', '/'), "legacy.exe"), processPaths)
            }
        }
    }

    @Test
    fun storedSelectorChoicesWinOverSelectedServer() {
        val parsed = SubscriptionParser.parse(yaml, emptyMap(), "p1")
        val profile = Profile(id = "p1", name = "t", servers = parsed.servers, mihomo = parsed.mihomo)
        // The user set Proxy → Auto by hand; connecting to the NL server must not undo it.
        val plan = MihomoConfigBuilder.build(
            parsed.servers[1], profile, AppSettings(), MihomoIngress(9999, "x"),
            stored = mapOf("Proxy" to "Auto", "Bogus" to "Nope", "Europe" to "Not a member"),
        )
        assertEquals(listOf("Europe" to "🇳🇱 NL", "Proxy" to "Auto"), plan.picks.map { it.group to it.choice })
    }

    @Test
    fun storedLinkChoicePicksThatServer() {
        val a = app.ghostly.core.link.LinkParser.parse("trojan://pass@a.example.com:443?sni=a.example.com#Alpha", "m:0")!!
        val b = app.ghostly.core.link.LinkParser.parse("trojan://pass@b.example.com:443?sni=b.example.com#Beta", "m:1")!!
        val profile = Profile(id = "p1", name = "t", servers = listOf(a, b))
        val settings = AppSettings(core = CoreType.MIHOMO)
        // Selected server is Alpha, but the saved «Ghostly» choice is Beta → Beta wins.
        val byName = MihomoConfigBuilder.build(a, profile, settings, MihomoIngress(9999, "x"), stored = mapOf(MihomoConfigBuilder.MAIN_GROUP to "Beta"))
        assertEquals(1, byName.picks.single().providerIndex)
        // A saved automatic group is applied by name.
        val auto = MihomoConfigBuilder.build(a, profile, settings, MihomoIngress(9999, "x"), stored = mapOf(MihomoConfigBuilder.MAIN_GROUP to MihomoConfigBuilder.AUTO_GROUP))
        assertEquals(MihomoConfigBuilder.AUTO_GROUP, auto.picks.single().choice)
        // A stale saved name falls back to the selected server.
        val stale = MihomoConfigBuilder.build(a, profile, settings, MihomoIngress(9999, "x"), stored = mapOf(MihomoConfigBuilder.MAIN_GROUP to "Gone"))
        assertEquals(0, stale.picks.single().providerIndex)
    }
}
