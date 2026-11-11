package app.ghostly.desktop

import app.ghostly.core.xray.ServerAddressPin
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap

/**
 * Resolves the servers' names over DNS-over-HTTPS, straight to the resolvers' IP addresses (no lookup needed
 * to reach them), before the core starts: see [ServerAddressPin] for why the core's own lookup can't be trusted.
 * A name that can't be resolved is left as it is, and the core looks it up the old way.
 */
internal object DohResolver {

    private val ENDPOINTS = listOf(
        "https://1.1.1.1/dns-query?type=A&name=",
        "https://8.8.8.8/resolve?type=A&name=",
        "https://1.0.0.1/dns-query?type=A&name=",
    )
    private const val MIN_TTL_MS = 300_000L
    private const val STALE_MS = 86_400_000L

    private class Entry(val ip: String, val freshUntil: Long, val staleUntil: Long)

    private val cache = ConcurrentHashMap<String, Entry>()
    private val http: HttpClient by lazy {
        HttpClient.newBuilder().connectTimeout(Duration.ofMillis(2_500)).followRedirects(HttpClient.Redirect.NEVER).build()
    }

    /** [config] with the server names it can resolve swapped for addresses. Never throws. */
    suspend fun pin(config: JsonObject): JsonObject = runCatching {
        val names = ServerAddressPin.names(config)
        if (names.isEmpty()) return config
        val ips = withTimeoutOrNull(6_000) { resolveAll(names) } ?: names.mapNotNull { n -> cache[n]?.let { n to it.ip } }.toMap()
        ServerAddressPin.apply(config, ips)
    }.getOrDefault(config)

    private suspend fun resolveAll(names: Set<String>): Map<String, String> = coroutineScope {
        names.map { n -> async(Dispatchers.IO) { resolve(n)?.let { n to it } } }.awaitAll().filterNotNull().toMap()
    }

    private suspend fun resolve(name: String): String? = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        cache[name]?.takeIf { it.freshUntil > now }?.let { return@withContext it.ip }
        for (base in ENDPOINTS) {
            val got = runCatching { query(base, name) }.getOrNull() ?: continue
            val (ip, ttlS) = got
            val fresh = now + maxOf(MIN_TTL_MS, ttlS * 1000L)
            cache[name] = Entry(ip, fresh, now + STALE_MS)
            return@withContext ip
        }
        // Every resolver failed: an address seen within the last day beats a hijacked lookup.
        cache[name]?.takeIf { it.staleUntil > now }?.ip
    }

    private fun query(base: String, name: String): Pair<String, Long>? {
        val req = HttpRequest.newBuilder(URI.create(base + URLEncoder.encode(name, Charsets.UTF_8)))
            .header("accept", "application/dns-json")
            .timeout(Duration.ofMillis(2_500))
            .GET().build()
        val res = http.send(req, HttpResponse.BodyHandlers.ofString())
        if (res.statusCode() != 200) return null
        val answers = Json.parseToJsonElement(res.body()).jsonObject["Answer"] as? JsonArray ?: return null
        return answers.map { it.jsonObject }
            .filter { it["type"]?.jsonPrimitive?.int == 1 }
            .mapNotNull { a ->
                val ip = a["data"]?.jsonPrimitive?.content ?: return@mapNotNull null
                val ttl = a["TTL"]?.jsonPrimitive?.content?.toLongOrNull() ?: 300L
                (ip to ttl).takeIf { isPublic(ip) }
            }
            .firstOrNull()
    }

    /** Fake-IP pools, loopback and private ranges are what hijackers answer with: never pin those. */
    internal fun isPublic(ip: String): Boolean {
        val p = ip.split('.').mapNotNull { it.toIntOrNull() }
        if (p.size != 4 || p.any { it !in 0..255 }) return false
        val (a, b) = p
        return when {
            a == 0 || a == 10 || a == 127 || a >= 224 -> false
            a == 100 && b in 64..127 -> false
            a == 169 && b == 254 -> false
            a == 172 && b in 16..31 -> false
            a == 192 && b == 168 -> false
            a == 198 && b in 18..19 -> false
            else -> true
        }
    }
}
