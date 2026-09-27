package app.ghostly.core.sub

import app.ghostly.core.vpn.PlatformInfo
import io.ktor.client.HttpClient
import io.ktor.client.engine.ProxyBuilder
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class SubscriptionException(message: String, val definitive: Boolean = false) : Exception(message)

/**
 * Fetches subscriptions faster and more reliably than a plain GET:
 * the direct request, the provider's mirror domain and — when our tunnel is up — the same request
 * through the tunnel race each other (staggered, happy-eyeballs style). The first good answer wins.
 */
class SubscriptionClient(private val platform: PlatformInfo) {

    private fun newClient(socksPort: Int? = null) = HttpClient {
        install(HttpTimeout) {
            requestTimeoutMillis = 15_000
            connectTimeoutMillis = 7_000
        }
        followRedirects = true
        expectSuccess = false
        if (socksPort != null) engine { proxy = ProxyBuilder.socks("127.0.0.1", socksPort) }
    }

    private val direct = newClient()
    private var tunneled: Pair<Int, HttpClient>? = null

    private fun viaTunnel(port: Int): HttpClient {
        tunneled?.let { (p, c) -> if (p == port) return c else c.close() }
        return newClient(port).also { tunneled = port to it }
    }

    /**
     * User-Agent providers match on. "Happ/…" first: Remnawave-style panels serve the full Xray-JSON
     * subscription (balancers, routing) only to clients they know can play it, and they know Happ;
     * we accept the same format. "ghostly" stays for Ghostly's own servers.
     */
    val userAgent: String
        get() = "Happ/2.0.0 GhostlyVPN/${platform.appVersion} (${platform.os} ${platform.osVersion}; ${platform.deviceModel})"

    /** For mihomo: providers match "clash"/"mihomo" in the User-Agent and send a Clash YAML with proxy groups. */
    val mihomoUserAgent: String
        // No "GhostlyVPN" here: Ghostly's own server would answer that with its Xray JSON.
        get() = "clash.meta/v1.19.31 mihomo/v1.19.31"

    suspend fun fetch(url: String, idPrefix: String, tunnelPort: Int? = null, mihomo: Boolean = false): ParsedSubscription = coroutineScope {
        data class Attempt(val url: String, val client: HttpClient, val delayMs: Long)
        val mirrors = mirrorsOf(url)
        val attempts = buildList {
            add(Attempt(url, direct, 0))
            tunnelPort?.let { add(Attempt(url, viaTunnel(it), 250)) }
            mirrors.forEachIndexed { i, m ->
                add(Attempt(m, direct, 900L + i * 600))
                if (tunnelPort != null) add(Attempt(m, viaTunnel(tunnelPort), 1200L + i * 600))
            }
        }
        val results = Channel<Result<ParsedSubscription>>(attempts.size)
        val jobs = attempts.map { a ->
            launch {
                delay(a.delayMs)
                results.send(runCatching { fetchOnce(a.client, a.url, idPrefix, if (mihomo) mihomoUserAgent else userAgent) })
            }
        }
        var error: Throwable? = null
        repeat(attempts.size) {
            val r = results.receive()
            r.onSuccess { parsed ->
                jobs.forEach { it.cancel() }
                return@coroutineScope parsed
            }
            val e = r.exceptionOrNull()
            // A real answer from the provider (e.g. "trial used", 404) beats network noise.
            if (error == null || (e as? SubscriptionException)?.definitive == true) error = e
            if ((e as? SubscriptionException)?.definitive == true) {
                jobs.forEach { it.cancel() }
                throw e
            }
        }
        throw error ?: SubscriptionException("Не удалось загрузить подписку")
    }

    private suspend fun fetchOnce(client: HttpClient, url: String, idPrefix: String, ua: String): ParsedSubscription {
        val response = client.get(url.trim()) {
            header("User-Agent", ua)
            header("Accept", "*/*")
            // Same device headers Happ sends: providers use them for device limits.
            header("x-hwid", platform.hwid)
            header("x-device-os", platform.os)
            header("x-ver-os", platform.osVersion)
            header("x-device-model", platform.deviceModel)
        }
        val body = response.bodyAsText()
        if (!response.status.isSuccess()) {
            val hint = body.lineSequence().map { it.trim().removePrefix("#").trim() }.firstOrNull { it.isNotEmpty() && !it.startsWith("<") }
            throw SubscriptionException(
                "HTTP ${response.status.value}" + (hint?.let { ": ${it.take(160)}" } ?: ""),
                definitive = response.status.value in 400..499,
            )
        }
        val headers = response.headers.entries().associate { (k, v) -> k.lowercase() to v.joinToString(", ") }
        val parsed = SubscriptionParser.parse(body, headers, idPrefix)
        if (parsed.servers.isEmpty()) throw SubscriptionException("В подписке нет поддерживаемых серверов", definitive = true)
        return parsed.copy(fetchedFrom = url)
    }

    /** Ghostly's other addresses of the same subscription, in the order they are tried. */
    fun mirrorsOf(url: String): List<String> = GhostlyDomains.mirrorsOf(url)

    fun close() {
        direct.close()
        tunneled?.second?.close()
    }
}
