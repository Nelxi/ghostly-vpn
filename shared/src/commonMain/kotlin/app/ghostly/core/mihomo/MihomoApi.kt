package app.ghostly.core.mihomo

import app.ghostly.core.JsonX
import io.ktor.client.HttpClient
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.prepareGet
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.URLProtocol
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.http.appendPathSegments
import io.ktor.utils.io.readUTF8Line
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/** A proxy group as the UI shows it: a selector (pick by hand) or an automatic one (url-test, fallback…). */
data class ProxyGroupInfo(
    val name: String,
    /** Selector, URLTest, Fallback, LoadBalance, Relay… (mihomo names). */
    val type: String,
    val now: String?,
    val members: List<String>,
    /** Last measured delay per member, ms; 0 = timeout/failed, absent = never tested. */
    val delays: Map<String, Int>,
    /** Member is a group itself (nested selectors). */
    val nestedGroups: Set<String>,
) {
    val selectable: Boolean get() = type.equals("Selector", ignoreCase = true)
}

/** mihomo's REST controller (the same API Clash dashboards use), on loopback with a secret. */
class MihomoApi(val port: Int, private val secret: String) {

    private val client = HttpClient { expectSuccess = false }

    private fun HttpRequestBuilder.endpoint(vararg segments: String, query: Map<String, String> = emptyMap()) {
        url {
            protocol = URLProtocol.HTTP
            host = "127.0.0.1"
            this.port = this@MihomoApi.port
            appendPathSegments(*segments)
            query.forEach { (k, v) -> parameters.append(k, v) }
        }
        header("Authorization", "Bearer $secret")
    }

    suspend fun version(): String? = runCatching {
        withTimeoutOrNull(1500) {
            val r = client.get { endpoint("version") }
            if (!r.status.isSuccess()) return@withTimeoutOrNull null
            JsonX.parseToJsonElement(r.bodyAsText()).jsonObject["version"]?.jsonPrimitive?.contentOrNull ?: "mihomo"
        }
    }.getOrNull()

    suspend fun ready(): Boolean = version() != null

    /** Groups in config order (GLOBAL lists them that way), hidden ones and GLOBAL itself left out. */
    suspend fun groups(includeGlobal: Boolean = false): List<ProxyGroupInfo> {
        val body = withTimeoutOrNull(4000) { client.get { endpoint("proxies") }.bodyAsText() } ?: return emptyList()
        val all = JsonX.parseToJsonElement(body).jsonObject["proxies"] as? JsonObject ?: return emptyList()
        fun delayOf(name: String): Int? {
            val history = (all[name] as? JsonObject)?.get("history") as? JsonArray ?: return null
            return (history.lastOrNull() as? JsonObject)?.get("delay")?.jsonPrimitive?.intOrNull
        }
        val groupTypes = setOf("selector", "urltest", "fallback", "loadbalance", "relay", "smart")
        fun isGroup(name: String) = ((all[name] as? JsonObject)?.get("type")?.jsonPrimitive?.contentOrNull?.lowercase()) in groupTypes
        val order = ((all["GLOBAL"] as? JsonObject)?.get("all") as? JsonArray).orEmpty().mapNotNull { it.jsonPrimitive.contentOrNull }
        val names = (order + all.keys).distinct().filter { isGroup(it) && (includeGlobal || it != "GLOBAL") }
        return names.mapNotNull { name ->
            val g = all[name] as? JsonObject ?: return@mapNotNull null
            if (g["hidden"]?.jsonPrimitive?.contentOrNull == "true") return@mapNotNull null
            val members = (g["all"] as? JsonArray).orEmpty().mapNotNull { it.jsonPrimitive.contentOrNull }
            ProxyGroupInfo(
                name = name,
                type = g["type"]?.jsonPrimitive?.contentOrNull ?: "Selector",
                now = g["now"]?.jsonPrimitive?.contentOrNull,
                members = members,
                delays = members.mapNotNull { m -> delayOf(m)?.let { m to it } }.toMap(),
                nestedGroups = members.filter(::isGroup).toSet(),
            )
        }
    }

    suspend fun select(group: String, name: String): Boolean = runCatching {
        client.put {
            endpoint("proxies", group)
            contentType(ContentType.Application.Json)
            setBody(JsonObject(mapOf("name" to JsonPrimitive(name))).toString())
        }.status.isSuccess()
    }.getOrDefault(false)

    /** Tests every member of a group; mihomo stores the results, [groups] shows them afterwards. */
    suspend fun testGroup(group: String, url: String, timeoutMs: Int = 5000): Map<String, Int> = runCatching {
        val r = client.get { endpoint("group", group, "delay", query = mapOf("url" to url, "timeout" to "$timeoutMs")) }
        JsonX.parseToJsonElement(r.bodyAsText()).jsonObject.mapNotNull { (k, v) -> v.jsonPrimitive.intOrNull?.let { k to it } }.toMap()
    }.getOrDefault(emptyMap())

    /** One proxy's delay through the core, ms; negative on failure. */
    suspend fun delay(name: String, url: String, timeoutMs: Int = 5000): Long = runCatching {
        val r = client.get { endpoint("proxies", name, "delay", query = mapOf("url" to url, "timeout" to "$timeoutMs")) }
        if (!r.status.isSuccess()) return@runCatching -1L
        JsonX.parseToJsonElement(r.bodyAsText()).jsonObject["delay"]?.jsonPrimitive?.longOrNull?.takeIf { it > 0 } ?: -1L
    }.getOrDefault(-1L)

    /** [delay] with a second try when the first gets no answer: one lost handshake isn't a dead server. */
    suspend fun delayRetry(name: String, url: String, timeoutMs: Int = 5000): Long {
        val first = delay(name, url, timeoutMs)
        return if (first > 0) first else delay(name, url, timeoutMs)
    }

    /** Proxy names of a proxy-provider, in file order. */
    suspend fun providerProxies(provider: String): List<String> = runCatching {
        val r = client.get { endpoint("providers", "proxies", provider) }
        (JsonX.parseToJsonElement(r.bodyAsText()).jsonObject["proxies"] as? JsonArray).orEmpty()
            .mapNotNull { (it as? JsonObject)?.get("name")?.jsonPrimitive?.contentOrNull }
    }.getOrDefault(emptyList())

    /** Applies picks from [MihomoConfigBuilder]; provider picks are resolved to names first. */
    suspend fun applyPicks(picks: List<MihomoPick>): Boolean {
        var ok = true
        for (p in picks) {
            val choice = p.choice ?: p.provider?.let { providerProxies(it).getOrNull(p.providerIndex) } ?: continue
            ok = select(p.group, choice) && ok
        }
        if (picks.isNotEmpty()) closeConnections()
        return ok
    }

    /** Drops live connections so they reconnect through the newly selected proxy. */
    suspend fun closeConnections() {
        runCatching { client.delete { endpoint("connections") } }
    }

    /** Up/down bytes per second, one pair a second, while the core runs. */
    fun traffic(): Flow<Pair<Long, Long>> = flow {
        client.prepareGet { endpoint("traffic") }.execute { r ->
            val ch = r.bodyAsChannel()
            while (true) {
                val line = ch.readUTF8Line() ?: break
                if (line.isBlank()) continue
                val o = runCatching { JsonX.parseToJsonElement(line).jsonObject }.getOrNull() ?: continue
                emit((o["up"]?.jsonPrimitive?.longOrNull ?: 0L) to (o["down"]?.jsonPrimitive?.longOrNull ?: 0L))
            }
        }
    }

    /** Log lines as the core emits them ("level: message"), filtered by [level] (debug/info/warning/error). */
    fun logs(level: String): Flow<String> = flow {
        client.prepareGet { endpoint("logs", query = mapOf("level" to level)) }.execute { r ->
            val ch = r.bodyAsChannel()
            while (true) {
                val line = ch.readUTF8Line() ?: break
                if (line.isBlank()) continue
                val o = runCatching { JsonX.parseToJsonElement(line).jsonObject }.getOrNull() ?: continue
                val type = o["type"]?.jsonPrimitive?.contentOrNull ?: "info"
                emit("$type: ${o["payload"]?.jsonPrimitive?.contentOrNull.orEmpty()}")
            }
        }
    }

    fun close() = client.close()

    private fun JsonArray?.orEmpty(): List<kotlinx.serialization.json.JsonElement> = this ?: emptyList()
}
