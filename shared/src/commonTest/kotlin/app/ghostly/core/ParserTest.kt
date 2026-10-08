package app.ghostly.core

import app.ghostly.core.link.LinkParser
import app.ghostly.core.model.AppSettings
import app.ghostly.core.model.RoutingMode
import app.ghostly.core.sub.SubscriptionParser
import app.ghostly.core.xray.Ingress
import app.ghostly.core.xray.XrayConfigBuilder
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.io.encoding.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ParserTest {

    private val reality =
        "vless://11111111-2222-3333-4444-555555555555@srv.example.org:10443?type=grpc&security=reality" +
            "&sni=deepseek.com&fp=chrome&pbk=yhL0OdmOdq9XKBWogJsj-AQMNipm81RBH3qB0FEm_D4&sid=01" +
            "&serviceName=google.android.location.SyncService&authority=deepseek.com#%F0%9F%87%AB%F0%9F%87%AE%20%D0%9E%D0%B1%D1%8B%D1%87%D0%BD%D1%8B%D0%B9"

    private val xhttp =
        "vless://11111111-2222-3333-4444-555555555555@151.236.109.225:443?type=xhttp&security=tls&sni=example.org" +
            "&host=example.org&path=%2Fstatic%2Fvideo.ts&mode=auto&extra=%7B%22xmux%22%3A%7B%22maxConcurrency%22%3A4%7D%7D#WL"

    private val hy2 = "hysteria2://91427610-f7f7-4b85-ab3b-256307243818@srv.example.org:443?sni=srv.example.org" +
        "&pinSHA256=B0:F3:F5:09#Hysteria2"

    @Test
    fun vlessRealityGrpc() {
        val s = assertNotNull(LinkParser.parse(reality, "t:0"))
        assertEquals("🇫🇮 Обычный", s.name)
        assertEquals("grpc", s.transport)
        assertEquals("reality", s.security)
        val stream = s.outbound!!["streamSettings"]!!.jsonObject
        assertEquals("google.android.location.SyncService", stream["grpcSettings"]!!.jsonObject["serviceName"]!!.jsonPrimitive.content)
        assertEquals("01", stream["realitySettings"]!!.jsonObject["shortId"]!!.jsonPrimitive.content)
    }

    @Test
    fun vlessXhttpExtra() {
        val s = assertNotNull(LinkParser.parse(xhttp, "t:1"))
        val x = s.outbound!!["streamSettings"]!!.jsonObject["xhttpSettings"]!!.jsonObject
        assertEquals("/static/video.ts", x["path"]!!.jsonPrimitive.content)
        assertEquals(4, x["extra"]!!.jsonObject["xmux"]!!.jsonObject["maxConcurrency"]!!.jsonPrimitive.content.toInt())
    }

    @Test
    fun hysteria2() {
        val s = assertNotNull(LinkParser.parse(hy2, "t:2"))
        val tls = s.outbound!!["streamSettings"]!!.jsonObject["tlsSettings"]!!.jsonObject
        assertEquals("b0f3f509", tls["pinnedPeerCertSha256"]!!.jsonPrimitive.content)
        assertEquals("hysteria", s.outbound["protocol"]!!.jsonPrimitive.content)
    }

    @Test
    fun hysteria2Salamander() {
        val s = assertNotNull(LinkParser.parse(hy2.replace("#", "&obfs=salamander&obfs-password=p%40ss#"), "t:3"))
        val mask = s.outbound!!["streamSettings"]!!.jsonObject["finalmask"]!!.jsonObject["udp"]!!.jsonArray.single().jsonObject
        assertEquals("salamander", mask["type"]!!.jsonPrimitive.content)
        assertEquals("p@ss", mask["settings"]!!.jsonObject["password"]!!.jsonPrimitive.content)
        // an obfuscated link without its password, or with an unknown obfuscation, is not a server
        assertNull(LinkParser.parse(hy2.replace("#", "&obfs=salamander#"), "t:4"))
        assertNull(LinkParser.parse(hy2.replace("#", "&obfs=other&obfs-password=x#"), "t:5"))
        assertNull(LinkParser.parse(hy2, "t:6")!!.outbound!!["streamSettings"]!!.jsonObject["finalmask"])
    }

    @Test
    fun shadowsocksBothForms() {
        val sip002 = "ss://" + Base64.encode("chacha20-ietf-poly1305:pa+ss".encodeToByteArray()) + "@1.2.3.4:8388#SS"
        val legacy = "ss://" + Base64.encode("aes-256-gcm:pw@5.6.7.8:443".encodeToByteArray()) + "#Old"
        assertEquals(8388, LinkParser.parse(sip002, "a")!!.port)
        assertEquals("5.6.7.8", LinkParser.parse(legacy, "b")!!.host)
    }

    @Test
    fun base64SubscriptionWithInlineHeaders() {
        val body = "#profile-title: base64:" + Base64.encode("Ghostly VPN👻".encodeToByteArray()) + "\n#profile-update-interval: 1\n$reality\n$xhttp\n$hy2\n"
        val encoded = Base64.encode(body.encodeToByteArray())
        val parsed = SubscriptionParser.parse(encoded, mapOf("subscription-userinfo" to "upload=10; download=20; total=1073741824; expire=1790000000"), "p")
        assertEquals("Ghostly VPN👻", parsed.title)
        assertEquals(3, parsed.servers.size)
        assertEquals(30, parsed.info!!.used)
        assertEquals(1, parsed.updateIntervalHours)
    }

    @Test
    fun remnawaveAnnounceHeader() {
        val note = "👤 kimberly\n⌛ Осталось: 3"
        val parsed = SubscriptionParser.parse(reality, mapOf("announce" to "base64:" + Base64.encode(note.encodeToByteArray())), "p")
        assertEquals(note, parsed.announce)
        assertEquals("plain note", SubscriptionParser.parse(reality, mapOf("announce" to "plain note"), "p").announce)
        assertEquals(null, SubscriptionParser.parse(reality, mapOf("announce" to "  "), "p").announce)
    }

    @Test
    fun happRenewAndInfoHeaders() {
        val parsed = SubscriptionParser.parse(reality, mapOf(
            "sub-expire-button-link" to "https://t.me/wisp_bot",
            "sub-info-text" to "Скидка 20%",
            "sub-info-color" to "Green",
            "sub-info-button-text" to "Купить",
            "sub-info-button-link" to "https://example.com/pay",
        ), "p")
        assertEquals("https://t.me/wisp_bot", parsed.renewUrl)
        assertEquals(app.ghostly.core.model.ProviderNotice("Скидка 20%", "green", "Купить", "https://example.com/pay"), parsed.notice)
        val none = SubscriptionParser.parse(reality, mapOf("sub-info-color" to "red"), "p")
        assertEquals(null, none.notice)
        assertEquals(null, none.renewUrl)
    }

    @Test
    fun xrayJsonSubscriptionKeepsProviderRouting() {
        val cfg = """
            [{"remarks":"Auto","outbounds":[{"tag":"a","protocol":"vless","settings":{"vnext":[{"address":"h","port":1,"users":[{"id":"x"}]}]}},
              {"tag":"b","protocol":"hysteria","settings":{"version":2,"address":"h","port":443}},{"tag":"direct","protocol":"freedom"}],
              "routing":{"rules":[{"type":"field","ip":["geoip:ru"],"outboundTag":"direct"},{"type":"field","port":"53","outboundTag":"dns-out"},
              {"type":"field","network":"tcp,udp","balancerTag":"bal"}],"balancers":[{"tag":"bal","selector":["a","b"]}]}},
             {"meta":{"serverDescription":"Single"},"outbounds":[{"tag":"proxy","protocol":"vless","streamSettings":{"network":"grpc","security":"reality"},
              "settings":{"vnext":[{"address":"srv","port":10443,"users":[{"id":"x"}]}]}}]}]
        """.trimIndent()
        val parsed = SubscriptionParser.parse(cfg, emptyMap(), "p")
        assertEquals(2, parsed.servers.size)
        assertTrue(parsed.servers[0].isAuto)
        assertEquals("Single", parsed.servers[1].name)
        assertEquals("srv", parsed.servers[1].host)

        val built = XrayConfigBuilder.build(parsed.servers[0], AppSettings(blockAds = true), Ingress.TunFd(1500))
        val rules = built["routing"]!!.jsonObject["rules"]!!.jsonArray.map { it.jsonObject }
        assertEquals("dns-out", rules[0]["outboundTag"]!!.jsonPrimitive.content, "DNS hijack stays first")
        assertEquals("block", rules[1]["outboundTag"]!!.jsonPrimitive.content, "ads rule right after DNS")
        assertEquals("bal", rules.last()["balancerTag"]!!.jsonPrimitive.content)
        assertEquals("tun", built["inbounds"]!!.jsonArray[0].jsonObject["protocol"]!!.jsonPrimitive.content)
        val tags = built["outbounds"]!!.jsonArray.map { it.jsonObject["tag"]!!.jsonPrimitive.content }
        assertTrue("block" in tags && "dns-out" in tags)

        val global = XrayConfigBuilder.build(parsed.servers[0], AppSettings(routingMode = RoutingMode.GLOBAL), Ingress.TunFd(1500))
        assertTrue(global["routing"]!!.jsonObject["rules"]!!.jsonArray.none { it.toString().contains("geoip:ru") })
        // A balancer is pinged through its first real proxy, even when "direct" is listed first.
        assertTrue(parsed.servers[0].canPing)
        val directFirst = SubscriptionParser.serverFromConfig(
            JsonX.parseToJsonElement(cfg).jsonArray[0].jsonObject.let { c ->
                val obs = c["outbounds"]!!.jsonArray
                JsonObject(c + ("outbounds" to JsonArray(listOf(obs.last()) + obs.dropLast(1))))
            },
            "p:x",
        )!!
        val ping = XrayConfigBuilder.buildPing(directFirst) as JsonObject
        assertEquals("a", ping["outbounds"]!!.jsonArray[0].jsonObject["tag"]!!.jsonPrimitive.content)
    }

    @Test
    fun linkServerTemplateAndPing() {
        val s = LinkParser.parse(reality, "t:0")!!
        val built = XrayConfigBuilder.build(s, AppSettings(mux = true, fragment = true), Ingress.Proxy(app.ghostly.core.xray.LocalProxy(10808, 10809, user = "ghostly_a1", pass = "p"), 0))
        val outbounds = built["outbounds"] as JsonArray
        assertEquals("proxy", outbounds[0].jsonObject["tag"]!!.jsonPrimitive.content)
        assertTrue(built.toString().contains("geosite:category-ru"))
        // reality is not TLS: no fragment chain
        assertTrue(outbounds.none { it.jsonObject["tag"]!!.jsonPrimitive.content == "fragment" })
        val socks = (built["inbounds"] as JsonArray).map { it.jsonObject }.first { it["tag"]!!.jsonPrimitive.content == "socks-in" }
        assertEquals("password", socks["settings"]!!.jsonObject["auth"]!!.jsonPrimitive.content)
        val ping = XrayConfigBuilder.buildPing(s) as JsonObject
        assertEquals(1, (ping["outbounds"] as JsonArray).size)
        assertTrue("inbounds" !in ping)
    }

    @Test
    fun versionCompare() {
        val cmp = app.ghostly.core.update.Updater.Companion::compareVersions
        assertTrue(cmp("0.1.10", "0.1.9") > 0)
        assertTrue(cmp("0.2.0", "0.1.99") > 0)
        assertEquals(0, cmp("0.1.2", "0.1.2-dev"))
        assertTrue(cmp("0.1.1", "0.1.2") < 0)
    }
}

class GhostlyDomainsTest {
    @kotlin.test.Test
    fun mirrorsCoverEveryOwnDomain() {
        val d = app.ghostly.core.sub.GhostlyDomains
        // A link on the previous domain is tried on the current one too…
        kotlin.test.assertEquals(listOf("https://ghostlynex.fun/sub/abc"), d.mirrorsOf("https://ghostlinknex.online/sub/abc"))
        kotlin.test.assertEquals(
            listOf("https://ghostlynex.fun/sub/abc", "https://ghostlinknex.online/sub/abc"),
            d.mirrorsOf("https://srv.ghostlinknex.online/sub/abc"),
        )
        // …and the current one falls back to the previous while that is alive.
        kotlin.test.assertEquals(listOf("https://ghostlinknex.online/sub/x?y=1"), d.mirrorsOf("https://ghostlynex.fun/sub/x?y=1"))
        kotlin.test.assertTrue(d.mirrorsOf("https://example.com/sub/abc").isEmpty())
    }

    @kotlin.test.Test
    fun addedSubscriptionKeepsTheMirrorOnlyWhenItsOwnAddressFailed() {
        val d = app.ghostly.core.sub.GhostlyDomains
        val old = "https://srv.ghostlinknex.online/sub/sub_6d0a6b02f44f29b1#Ghostly%20VPN%F0%9F%91%BB"
        val now = "https://ghostlynex.fun/sub/sub_6d0a6b02f44f29b1#Ghostly%20VPN%F0%9F%91%BB"
        kotlin.test.assertEquals(now, d.mirrorsOf(old).first())
        // A link on the previous domain is always saved on the current one.
        kotlin.test.assertEquals(now, d.linkToSave(old, now, ownAddressFailed = true))
        kotlin.test.assertEquals(now, d.linkToSave(old, old, ownAddressFailed = false))
        kotlin.test.assertEquals(now, d.current(old))
        kotlin.test.assertEquals(now, d.current(now))
        kotlin.test.assertEquals("https://ghostlynex.fun/sub/x?fmt=xray", d.current("https://www.ghostlinknex.online/sub/x?fmt=xray"))
        kotlin.test.assertEquals(now, d.linkToSave(now, now, ownAddressFailed = false))
        // Someone else's subscription is never rewritten.
        kotlin.test.assertEquals("https://example.com/s", d.linkToSave("https://example.com/s", "https://other.net/s", ownAddressFailed = true))
    }
}
