package app.ghostly.core.sub

import app.ghostly.core.vpn.PlatformInfo
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
import app.ghostly.i18n.tr

class SubscriptionException(message: String, val definitive: Boolean = false) : Exception(message)

/**
 * Fetches subscriptions faster and more reliably than a plain GET:
 * the direct request, the provider's mirror domain and — when our tunnel is up — the same request
 * through the tunnel race each other (staggered, happy-eyeballs style). The first good answer wins.
 */
class SubscriptionClient(private val platform: PlatformInfo) {

    private fun newClient(socksPort: Int? = null) = HttpClient {
        configure()
        if (socksPort != null) engine { proxy = ProxyBuilder.socks("127.0.0.1", socksPort) }
    }

    private fun io.ktor.client.HttpClientConfig<*>.configure() {
        install(HttpTimeout) {
            requestTimeoutMillis = 15_000
            connectTimeoutMillis = 7_000
        }
        followRedirects = true
        expectSuccess = false
    }

    /** Clients pinned to one node each (see [GhostlyDomains.doorsOf]), kept while the address stays the same. */
    private val doorClients = HashMap<Pair<String, String>, HttpClient>()
    private val doorLock = Mutex()

    private suspend fun door(host: String, address: String): HttpClient? = doorLock.withLock {
        doorClients[host to address]?.let { return@withLock it }
        // Nodes that left the subscription (an IP change) take their clients with them.
        if (doorClients.size >= GhostlyDomains.MAX_DOORS * 2) {
            doorClients.values.forEach { it.close() }
            doorClients.clear()
        }
        platform.pinnedClient(host, address) { configure() }?.also { doorClients[host to address] = it }
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

    /**
     * [nodeHosts]: addresses of the subscription's own servers. For Ghostly's subscriptions each is also asked
     * directly (after the domains have had their head start), so a node under attack or a dead CDN/DNS name
     * doesn't leave the app without a subscription while the other node is up.
     */
    suspend fun fetch(
        link: String,
        idPrefix: String,
        tunnelPort: Int? = null,
        mihomo: Boolean = false,
        nodeHosts: List<String> = emptyList(),
    ): ParsedSubscription = coroutineScope {
        data class Attempt(val url: String, val client: HttpClient, val delayMs: Long)
        // A pasted link on the previous domain is asked on the current one: the old name is never contacted.
        val url = if (GhostlyDomains.isLegacy(link)) GhostlyDomains.current(link) else link
        val mirrors = mirrorsOf(url)
        val attempts = buildList {
            add(Attempt(url, direct, 0))
            tunnelPort?.let { add(Attempt(url, viaTunnel(it), 250)) }
            mirrors.forEachIndexed { i, m ->
                add(Attempt(m, direct, 900L + i * 600))
                if (tunnelPort != null) add(Attempt(m, viaTunnel(tunnelPort), 1200L + i * 600))
            }
            GhostlyDomains.doorsOf(url, nodeHosts).forEachIndexed { i, (u, address) ->
                door(GhostlyDomains.hostOf(u), address)?.let { add(Attempt(u, it, DOOR_DELAY_MS + i * 300)) }
            }
        }
        val results = Channel<Pair<Attempt, Result<ParsedSubscription>>>(attempts.size)
        val jobs = attempts.map { a ->
            launch {
                delay(a.delayMs)
                results.send(a to runCatching { fetchOnce(a.client, a.url, idPrefix, if (mihomo) mihomoUserAgent else userAgent) })
            }
        }
        val ownTotal = attempts.count { it.url == url }
        var ownFailed = 0
        var error: Throwable? = null
        repeat(attempts.size) {
            val (a, r) = results.receive()
            r.onSuccess { parsed ->
                jobs.forEach { it.cancel() }
                return@coroutineScope parsed.copy(ownAddressFailed = a.url != url && ownFailed == ownTotal)
            }
            if (a.url == url) ownFailed++
            val e = r.exceptionOrNull()
            // A real answer from the provider (e.g. "trial used", 404) beats network noise.
            if (error == null || (e as? SubscriptionException)?.definitive == true) error = e
            if ((e as? SubscriptionException)?.definitive == true) {
                jobs.forEach { it.cancel() }
                throw e
            }
        }
        throw error ?: SubscriptionException(tr("Не удалось загрузить подписку"))
    }

    private suspend fun fetchOnce(client: HttpClient, url: String, idPrefix: String, ua: String, attested: Boolean = false): ParsedSubscription {
        val response = client.get(url.trim()) {
            header("User-Agent", ua)
            header("Accept", "*/*")
            // Same device headers Happ sends: providers use them for device limits.
            header("x-hwid", platform.hwid)
            header("Accept-Language", app.ghostly.i18n.L10n.language)
            header("x-device-os", platform.os)
            header("x-ver-os", platform.osVersion)
            header("x-device-model", platform.deviceModel)
            // Wi-Fi / mobile / Ethernet: support sees it next to the region and operator of the request.
            header("x-net", platform.networkType().name)
        }
        // Ghostly's server wants proof that this is the real app (trials on Android): answer once and ask again.
        if (!attested) Attest.parse(response.headers[Attest.HEADER])?.let { ask ->
            // The second request carries a one-off query: a caching mirror in front of the server must
            // not hand back the "prove it" answer it stored a moment ago.
            if (answerAttest(client, url, ua, ask)) {
                return fetchOnce(client, Attest.again(url, ask), idPrefix, ua, attested = true).copy(fetchedFrom = url)
            }
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
        if (parsed.servers.isEmpty()) throw SubscriptionException(tr("В подписке нет поддерживаемых серверов"), definitive = true)
        return parsed.copy(fetchedFrom = url)
    }

    private val attestLock = Mutex()

    /**
     * Makes the attested key for [ask] and posts its chain next to the subscription. True when the
     * server took an answer (accepted or refused — either way the next fetch shows the result).
     * One at a time: the parallel attempts of [fetch] must not each burn a key and a challenge.
     */
    private suspend fun answerAttest(client: HttpClient, url: String, ua: String, ask: Attest.Ask): Boolean = attestLock.withLock {
        val endpoint = Attest.endpoint(url) ?: return@withLock false
        val challenge = decodeBase64(ask.challenge) ?: return@withLock false
        val chain = runCatching { platform.attestKey(challenge) }
        val signs = runCatching { platform.rootSigns() }.getOrDefault(emptyList())
        val answer = Attest.Answer(
            challenge = ask.id,
            chain = chain.getOrNull().orEmpty(),
            root = signs.isNotEmpty(),
            rootSigns = signs.joinToString(","),
            app = platform.appVersion,
            error = if (chain.getOrNull() == null) (chain.exceptionOrNull()?.let { it::class.simpleName + ": " + it.message } ?: "unsupported").take(200) else null,
        )
        runCatching {
            client.post(endpoint) {
                header("User-Agent", ua)
                header("x-hwid", platform.hwid)
                header("Accept-Language", app.ghostly.i18n.L10n.language)
                header("x-device-os", platform.os)
                header("x-ver-os", platform.osVersion)
                header("x-device-model", platform.deviceModel)
                contentType(ContentType.Application.Json)
                setBody(app.ghostly.core.JsonX.encodeToString(Attest.Answer.serializer(), answer))
            }.status.isSuccess()
        }.getOrDefault(false)
    }

    @OptIn(kotlin.io.encoding.ExperimentalEncodingApi::class)
    private fun decodeBase64(text: String): ByteArray? = runCatching { kotlin.io.encoding.Base64.decode(text) }.getOrNull()

    /** Ghostly's other addresses of the same subscription, in the order they are tried. */
    fun mirrorsOf(url: String): List<String> = GhostlyDomains.mirrorsOf(url)

    fun close() {
        direct.close()
        tunneled?.second?.close()
        doorClients.values.forEach { it.close() }
        doorClients.clear()
    }

    private companion object {
        /** Nodes are asked after the domains and mirrors had their chance: normally the CDN answers first. */
        const val DOOR_DELAY_MS = 1_500L
    }
}
