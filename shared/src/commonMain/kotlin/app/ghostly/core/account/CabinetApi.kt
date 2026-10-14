package app.ghostly.core.account

import app.ghostly.core.JsonX
import app.ghostly.core.store.FileStore
import app.ghostly.core.sub.GhostlyDomains
import app.ghostly.core.vpn.PlatformInfo
import app.ghostly.i18n.tr
import io.ktor.client.HttpClient
import io.ktor.client.engine.ProxyBuilder
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** The server refused or could not be reached; [code] is the server's error code when it answered. */
class CabinetException(message: String, val code: String? = null, val status: Int = 0, val body: JsonObject? = null) : Exception(message) {
    val network: Boolean get() = code == "network"
}

/**
 * The HTTP side of the in-app cabinet: the site's own API, with the session kept by the app.
 *
 * The site signs a browser in with two cookies (gv_s, the session; gv_d, the device). The app keeps their
 * values in its data folder and sends them with every request to whichever of Ghostly's addresses answers:
 * the session lives on the server, not on a domain. A request goes out directly first and, while the
 * tunnel is up, through it; the way that worked is tried first next time. One request at a time, so a
 * burst of taps never trips the site's rate limits.
 */
class CabinetApi(private val platform: PlatformInfo, private val store: FileStore) {

    @Serializable
    private data class Saved(
        val token: String? = null,
        val device: String? = null,
        val base: String? = null,
        /** False after the person signed out: the app then waits to be asked before signing in by itself. */
        val auto: Boolean = true,
    )

    private var saved = store.load(FILE, Saved.serializer()) ?: Saved()
    private val gate = Mutex()

    /** Local SOCKS port into the running tunnel, null while it is down. */
    var tunnelPort: () -> Int? = { null }

    /** The address of the Ghostly subscription in the app: its host is tried before the defaults. */
    var subscriptionUrl: () -> String? = { null }

    val hasSession: Boolean get() = !saved.token.isNullOrEmpty()

    var autoSignIn: Boolean
        get() = saved.auto
        set(v) {
            if (v != saved.auto) persist(saved.copy(auto = v))
        }

    val userAgent: String get() = "GhostlyVPN/${platform.appVersion} (${platform.os} ${platform.osVersion}; ${platform.deviceModel})"

    private fun newClient(socks: Int?) = HttpClient {
        install(HttpTimeout) {
            requestTimeoutMillis = 30_000
            connectTimeoutMillis = 8_000
            socketTimeoutMillis = 30_000
        }
        expectSuccess = false
        followRedirects = false
        if (socks != null) engine { proxy = ProxyBuilder.socks("127.0.0.1", socks) }
    }

    private val direct = newClient(null)
    private var tunneled: Pair<Int, HttpClient>? = null
    private var lastRoute: Pair<String, Boolean>? = null

    private fun viaTunnel(port: Int): HttpClient {
        tunneled?.let { (p, c) -> if (p == port) return c else c.close() }
        return newClient(port).also { tunneled = port to it }
    }

    /** Where the API is asked, in order: the address that worked last, the subscription's own, then Ghostly's. */
    fun bases(): List<String> = baseOrder(saved.base, subscriptionUrl())

    fun forgetSession() = persist(saved.copy(token = null))

    // ------------------------------------------------------------------ calls

    /** POST [path] with [body]. [safe]: may be repeated on another route when one fails (reads, quotes). */
    suspend fun post(path: String, body: JsonObject = EMPTY, safe: Boolean = false, device: Boolean = false): JsonObject =
        call(path, body, safe, device)

    suspend fun get(path: String): JsonObject = call(path, null, safe = true, device = false)

    suspend fun <T> decode(serializer: KSerializer<T>, json: JsonObject): T = JsonX.decodeFromJsonElement(serializer, json)

    private suspend fun call(path: String, body: JsonObject?, safe: Boolean, device: Boolean): JsonObject = gate.withLock {
        val port = tunnelPort()
        val routes = buildList {
            lastRoute?.let { (b, t) -> if (!t || port != null) add(b to t) }
            for (b in bases()) {
                add(b to false)
                if (port != null) add(b to true)
            }
        }.distinct()
        var failure: Throwable? = null
        for ((base, tunnel) in routes) {
            val client = if (tunnel) viaTunnel(port ?: continue) else direct
            val response = try {
                send(client, base + path, body, device)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                failure = e
                // A request that may have reached the server (a purchase) is never sent a second time.
                if (!safe && !neverSent(e)) {
                    throw CabinetException(tr("Сервер не ответил вовремя. Обновите кабинет, прежде чем повторять"), "network")
                }
                continue
            }
            lastRoute = base to tunnel
            takeCookies(response.headers.getAll(HttpHeaders.SetCookie).orEmpty(), base)
            val text = runCatching { response.bodyAsText() }.getOrDefault("")
            val json = runCatching { JsonX.parseToJsonElement(text).jsonObject }.getOrNull()
            if (json == null) {
                // Not our API (a captive portal, a proxy's error page): another route may do better.
                failure = CabinetException(tr("Сервер ответил непонятно (HTTP {0})", response.status.value), "bad_answer", response.status.value)
                if (!safe) throw failure
                continue
            }
            if (!response.status.isSuccess() || json.bool("ok") == false) {
                throw CabinetException(
                    json.string("error") ?: tr("Не получилось (HTTP {0})", response.status.value),
                    json.string("code"), response.status.value, json,
                )
            }
            return json
        }
        throw CabinetException(
            tr("Нет связи с сервером Ghostly. Проверьте интернет и попробуйте ещё раз") +
                (failure?.message?.takeIf { it.isNotBlank() }?.let { " ($it)" } ?: ""),
            "network",
        )
    }

    private suspend fun send(client: HttpClient, url: String, body: JsonObject?, device: Boolean): HttpResponse {
        val headers: HttpRequestBuilder.() -> Unit = {
            header("User-Agent", userAgent)
            header("X-GV", "1")
            header("Accept", "application/json")
            header("Accept-Language", app.ghostly.i18n.L10n.language)
            cookieHeader(saved.token, saved.device)?.let { header("Cookie", it) }
            if (device) {
                header("x-hwid", platform.hwid)
                header("x-device-os", platform.os)
                header("x-device-model", platform.deviceModel)
            }
        }
        return if (body == null) client.get(url) { headers() }
        else client.post(url) {
            headers()
            contentType(ContentType.Application.Json)
            setBody(body.toString())
        }
    }

    private fun takeCookies(setCookies: List<String>, base: String) {
        val next = applyCookies(setCookies, saved.token, saved.device)
        val updated = saved.copy(token = next.first, device = next.second, base = base)
        if (updated != saved) persist(updated)
    }

    private fun persist(s: Saved) {
        saved = s
        store.save(FILE, Saved.serializer(), s)
    }

    companion object {
        private const val FILE = "cabinet.json"
        const val SESSION = "gv_s"
        const val DEVICE = "gv_d"
        val EMPTY = JsonObject(emptyMap())

        fun baseOrder(last: String?, subscription: String?): List<String> = buildList {
            last?.let(::add)
            subscription?.takeIf { GhostlyDomains.isOurs(it) }?.let { add("https://" + GhostlyDomains.hostOf(GhostlyDomains.current(it))) }
            add("https://" + GhostlyDomains.MAIN)
            add("https://" + GhostlyDomains.LEGACY_ALIVE)
        }.map { it.trimEnd('/') }.distinct()

        fun cookieHeader(token: String?, device: String?): String? =
            listOfNotNull(token?.takeIf { it.isNotEmpty() }?.let { "$SESSION=$it" }, device?.takeIf { it.isNotEmpty() }?.let { "$DEVICE=$it" })
                .joinToString("; ").ifEmpty { null }

        /**
         * The session and device after a response's Set-Cookie headers. On a subdomain the site sets the
         * domain cookie and deletes a host-only twin in the same answer: a live value wins over a deletion;
         * a deletion alone (sign-out) clears it.
         */
        fun applyCookies(setCookies: List<String>, token: String?, device: String?): Pair<String?, String?> {
            var t = token
            var d = device
            for (name in listOf(SESSION, DEVICE)) {
                val mine = setCookies.mapNotNull { parseSetCookie(it) }.filter { it.first == name }
                if (mine.isEmpty()) continue
                val live = mine.lastOrNull { it.second.isNotEmpty() && it.third }
                if (name == SESSION) t = live?.second
                else if (live != null) d = live.second
            }
            return t to d
        }

        /** (name, value, alive) of one Set-Cookie header; alive = false for Max-Age=0 or an empty value. */
        fun parseSetCookie(header: String): Triple<String, String, Boolean>? {
            val parts = header.split(';').map { it.trim() }
            val first = parts.firstOrNull()?.takeIf { '=' in it } ?: return null
            val name = first.substringBefore('=').trim()
            val value = first.substringAfter('=').trim().removeSurrounding("\"")
            val maxAge = parts.drop(1).firstOrNull { it.startsWith("max-age", ignoreCase = true) }?.substringAfter('=')?.trim()?.toLongOrNull()
            val expired = parts.drop(1).any { it.startsWith("expires", ignoreCase = true) && "1970" in it }
            return Triple(name, value, value.isNotEmpty() && (maxAge == null || maxAge > 0) && !expired)
        }

        private val CONNECT_FAILURES = listOf("UnknownHost", "UnresolvedAddress", "ConnectException", "NoRouteToHost", "SSLHandshake", "PortUnreachable", "Socks")

        /** The request certainly never reached the server (no connection was made), so it may go another way. */
        fun neverSent(e: Throwable): Boolean {
            if (e is io.ktor.client.network.sockets.ConnectTimeoutException) return true
            return generateSequence(e) { it.cause }.take(6).any { x ->
                val n = x::class.simpleName.orEmpty()
                CONNECT_FAILURES.any { it in n }
            }
        }

        /** The value of query parameter [name] in [url] (`?app=<code>`, `?pair=<code>`). */
        fun queryParam(url: String, name: String): String? =
            url.substringBefore('#').substringAfter('?', "").split('&')
                .firstOrNull { it.substringBefore('=') == name }?.substringAfter('=', "")?.takeIf { it.isNotEmpty() }
    }
}

// ---------------------------------------------------------------------- tiny JSON helpers

internal fun JsonObject.string(key: String): String? =
    (this[key] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.contentOrNull?.takeIf { it.isNotEmpty() }

internal fun JsonObject.bool(key: String): Boolean? = (this[key] as? JsonPrimitive)?.booleanOrNull

internal fun JsonObject.obj(key: String): JsonObject? = this[key] as? JsonObject

internal fun jsonOf(vararg pairs: Pair<String, Any?>): JsonObject = JsonObject(
    pairs.mapNotNull { (k, v) ->
        val e: JsonElement = when (v) {
            null -> return@mapNotNull null
            is JsonElement -> v
            is String -> JsonPrimitive(v)
            is Number -> JsonPrimitive(v)
            is Boolean -> JsonPrimitive(v)
            else -> JsonPrimitive(v.toString())
        }
        k to e
    }.toMap(),
)
