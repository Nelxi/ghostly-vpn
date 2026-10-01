package app.ghostly.core

import app.ghostly.core.link.LinkParser
import app.ghostly.core.mihomo.MihomoConfigBuilder
import app.ghostly.core.mihomo.MihomoIngress
import app.ghostly.core.model.AppSettings
import app.ghostly.core.model.CoreType
import app.ghostly.core.model.RuApps
import app.ghostly.core.model.SplitMode
import app.ghostly.core.vpn.LoopbackAuth
import app.ghostly.core.xray.Ingress
import app.ghostly.core.xray.XrayConfigBuilder
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/** The hidden app-in port must never be usable by other apps on the device. */
class LoopbackAuthTest {
    private val server = LinkParser.parse("trojan://pass@example.com:443?sni=example.com#Test", "t:0")!!

    @Test
    fun credentialsAreRandomAndNonTrivial() {
        assertTrue(LoopbackAuth.user.length >= 8)
        assertTrue(LoopbackAuth.pass.length >= 20)
        assertNotEquals(LoopbackAuth.user, LoopbackAuth.pass)
    }

    @Test
    fun xrayAppInRequiresPassword() {
        val cfg = XrayConfigBuilder.build(server, AppSettings(), Ingress.TunFd(1500, appPort = 20001))
        val appIn = cfg["inbounds"]!!.jsonArray.map { it.jsonObject }.single { it["tag"]!!.jsonPrimitive.content == "app-in" }
        assertEquals("127.0.0.1", appIn["listen"]!!.jsonPrimitive.content)
        val settings = appIn["settings"]!!.jsonObject
        assertEquals("password", settings["auth"]!!.jsonPrimitive.content)
        val account = settings["accounts"]!!.jsonArray.single().jsonObject
        assertEquals(LoopbackAuth.user, account["user"]!!.jsonPrimitive.content)
        assertEquals(LoopbackAuth.pass, account["pass"]!!.jsonPrimitive.content)
    }

    @Test
    fun pingInboundRequiresPassword() {
        val settings = XrayConfigBuilder.pingInbound(20002, "in0")["settings"]!!.jsonObject
        assertEquals("password", settings["auth"]!!.jsonPrimitive.content)
    }

    @Test
    fun mihomoAppInRequiresPasswordEvenFromLoopback() {
        val settings = AppSettings(core = CoreType.MIHOMO)
        val cfg = MihomoConfigBuilder.build(server, null, settings, MihomoIngress(9999, "x", appPort = 20001)).config
        assertEquals(JsonArray(emptyList()), cfg["skip-auth-prefixes"])
        val appIn = (cfg["listeners"] as JsonArray).map { it as JsonObject }.single { it["name"]!!.jsonPrimitive.content == "app-in" }
        val user = appIn["users"]!!.jsonArray.single().jsonObject
        assertEquals(LoopbackAuth.user, user["username"]!!.jsonPrimitive.content)
        assertEquals(LoopbackAuth.pass, user["password"]!!.jsonPrimitive.content)
    }

    @Test
    fun ruAppsBypassByDefault() {
        val fresh = AppSettings()
        assertEquals(SplitMode.BYPASS_SELECTED, fresh.splitMode)
        assertTrue(fresh.splitApps.containsAll(setOf("ru.rostel", "ru.oneme.app", "ru.sberbankmobile", "ru.ozon.app.android")))
        assertTrue("org.telegram.messenger" !in RuApps.PACKAGES && "com.yandex.browser" !in RuApps.PACKAGES)
    }

    @Test
    fun migrationMovesEveryoneOnce() {
        val off = AppSettings(splitMode = SplitMode.OFF, splitApps = setOf("org.telegram.messenger")).withRuAppsBypass()
        assertEquals(SplitMode.BYPASS_SELECTED, off.splitMode)
        assertEquals(RuApps.PACKAGES, off.splitApps)
        assertTrue(off.ruAppsBypassApplied)

        val own = AppSettings(splitMode = SplitMode.BYPASS_SELECTED, splitApps = setOf("com.example.game")).withRuAppsBypass()
        assertEquals(RuApps.PACKAGES + "com.example.game", own.splitApps)

        val only = AppSettings(splitMode = SplitMode.ONLY_SELECTED, splitApps = setOf("org.telegram.messenger")).withRuAppsBypass()
        assertEquals(SplitMode.ONLY_SELECTED, only.splitMode)
        assertEquals(setOf("org.telegram.messenger"), only.splitApps)
    }

    @Test
    fun ruAppsNeverBecomeOnlyThroughVpn() {
        val only = AppSettings().withSplitMode(SplitMode.ONLY_SELECTED)
        assertTrue(only.splitApps.none { it in RuApps.PACKAGES })
        assertTrue(only.withSplitMode(SplitMode.BYPASS_SELECTED).splitApps.containsAll(RuApps.PACKAGES))
    }
}
