package app.ghostly.core.account

import app.ghostly.core.JsonX
import app.ghostly.core.sub.Attest
import app.ghostly.core.sub.GhostlyDomains
import app.ghostly.core.vpn.PlatformInfo
import io.ktor.client.HttpClient
import io.ktor.client.engine.ProxyBuilder
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.serialization.Serializable

/**
 * The way from the app into the user's Ghostly account: everything the bot and the site can do
 * (renew, change the plan, devices, extra traffic, promo codes, notices) lives in the site cabinet,
 * and the app opens it already signed in.
 *
 * The app shows the server which subscription it runs on and which device it is; the server answers
 * with a link. The device that added the subscription first gets a one-time sign-in in that link,
 * any other device gets the plain page (a shared subscription link must not open its owner's account).
 * Only for Ghostly's own subscriptions: nothing is ever sent to another provider.
 */
class GhostlyAccount(private val platform: PlatformInfo, private val userAgent: () -> String) {

    @Serializable
    data class Link(
        val ok: Boolean = false,
        /** True when [url] signs the browser in by itself. */
        val login: Boolean = false,
        val url: String = "",
        /** Why there is no sign-in: "not_first_device", "totp". */
        val reason: String? = null,
        /** The subscription was bought in the Telegram bot. */
        val bot: Boolean = false,
    )

    private fun client(socksPort: Int?) = HttpClient {
        install(HttpTimeout) {
            requestTimeoutMillis = 8_000
            connectTimeoutMillis = 5_000
        }
        expectSuccess = false
        if (socksPort != null) engine { proxy = ProxyBuilder.socks("127.0.0.1", socksPort) }
    }

    /**
     * A link into the cabinet for the subscription at [subscriptionUrl], opened on [go] ("cabinet",
     * "renew", "notices"). Tries the subscription's own address, then Ghostly's mirrors, each directly
     * and — when the tunnel is up — through it. Null when nothing answered.
     */
    suspend fun cabinet(subscriptionUrl: String, go: String, tunnelPort: Int?): Link? {
        if (!GhostlyDomains.isOurs(subscriptionUrl)) return null
        val endpoints = (listOf(subscriptionUrl) + GhostlyDomains.mirrorsOf(subscriptionUrl)).mapNotNull { endpoint(it) }.distinct()
        val ports = listOfNotNull(null, tunnelPort).distinct()
        for (port in ports) {
            val http = client(port)
            try {
                for (e in endpoints) {
                    val link = runCatching { ask(http, e, go) }.getOrNull()
                    if (link != null) return link
                }
            } finally {
                http.close()
            }
        }
        return null
    }

    private suspend fun ask(http: HttpClient, endpoint: String, go: String): Link? {
        val r = http.post(endpoint) {
            header("User-Agent", userAgent())
            header("x-hwid", platform.hwid)
            header("x-device-os", platform.os)
            header("x-device-model", platform.deviceModel)
            contentType(ContentType.Application.Json)
            setBody("""{"go":"$go"}""")
        }
        if (!r.status.isSuccess()) return null
        val link = JsonX.decodeFromString(Link.serializer(), r.bodyAsText())
        // The answer is opened in the browser: take nothing but an https page of Ghostly's own site.
        return link.takeIf { it.ok && it.url.startsWith("https://") && GhostlyDomains.isOurs(it.url) }
    }

    companion object {
        /** The sections the site can open straight away. */
        val SECTIONS = setOf("cabinet", "renew", "notices")

        /** `…/sub/<id>/cabinet` next to the subscription; null for a link of another shape. */
        fun endpoint(subscriptionUrl: String): String? = Attest.endpoint(subscriptionUrl)?.removeSuffix("/attest")?.plus("/cabinet")

        /** Where to send the person when the server could not be asked: the site, on the same section. */
        fun fallback(subscriptionUrl: String, go: String): String {
            val id = subscriptionUrl.substringBefore('#').substringBefore('?').trimEnd('/').substringAfterLast('/')
            val anchor = when (go) {
                "renew" -> "#renew=$id"
                "notices" -> "#notices"
                else -> "#cabinet"
            }
            return "https://${GhostlyDomains.MAIN}/$anchor"
        }
    }
}
