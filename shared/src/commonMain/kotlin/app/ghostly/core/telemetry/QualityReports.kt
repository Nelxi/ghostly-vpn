package app.ghostly.core.telemetry

import app.ghostly.core.JsonX
import app.ghostly.core.sub.GhostlyDomains
import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable

/**
 * One measurement against a Ghostly server. Short names: a batch goes out over mobile data too.
 * [t]: "ping" (the ping button / list), "connect" (a connect attempt's outcome), "health" (the guard's
 * check through the running tunnel). [ms]: round trip or time to connect, 0 when it failed.
 */
@Serializable
data class QualityEvent(
    val t: String,
    val s: String,
    val p: String,
    val ok: Boolean,
    val ms: Long,
    val net: String,
    val at: Long,
    val m: String? = null,
    val b: String? = null,
)

@Serializable
data class QualityBatch(
    val v: Int = 1,
    val app: String,
    val os: String,
    val core: String,
    val events: List<QualityEvent>,
)

/**
 * Anonymous connection-quality reports (setting «Анонимная статистика качества», on by default).
 *
 * Only Ghostly's own servers are reported, and nothing that identifies the person: no subscription,
 * device id, cookies or device model — the server reads the rough place and the operator from the
 * request's address and keeps only those (see ghostlink_telemetry.py on the server). Sent straight past
 * the tunnel (where the platform allows it: the app's own traffic bypasses the tunnel on Android), so the
 * operator is the real one; when it fails the batch waits for the next try. Kept in memory only.
 */
class QualityReports(
    private val scope: CoroutineScope,
    private val appVersion: String,
    private val os: String,
    private val enabled: () -> Boolean,
    private val now: () -> Long,
) {
    private val gate = Mutex()
    private val buffer = ArrayList<QualityEvent>()
    // Counted from the start: the first measurements wait for company instead of going out one by one.
    private var lastSentAt = now()
    private var sending = false

    private val client by lazy {
        HttpClient {
            install(HttpTimeout) {
                requestTimeoutMillis = 15_000
                connectTimeoutMillis = 8_000
                socketTimeoutMillis = 15_000
            }
            expectSuccess = false
            followRedirects = false
        }
    }

    /** Remembers one measurement; a batch goes out when enough piled up or enough time passed. */
    fun record(kind: String, server: String, protocol: String, ms: Long, net: String, core: String, method: String? = null, block: String? = null) {
        if (!enabled()) return
        val at = now()
        scope.launch {
            val batch = gate.withLock {
                buffer += QualityEvent(kind, server.take(80), protocol.take(24), ms > 0, ms.coerceIn(0, 60_000), net, at / 1000, method, block)
                trim(buffer, MAX_KEPT)
                if (sending || !due(buffer.size, lastSentAt, at)) return@launch
                sending = true
                QualityBatch(app = appVersion, os = os, core = core, events = buffer.toList())
            }
            val sent = runCatching { send(batch) }.getOrElse { if (it is CancellationException) throw it; false }
            gate.withLock {
                sending = false
                lastSentAt = at
                if (sent) buffer.removeAll(batch.events.toSet())
            }
        }
    }

    private suspend fun send(batch: QualityBatch): Boolean = kotlinx.coroutines.withContext(Dispatchers.IO) {
        val body = JsonX.encodeToString(QualityBatch.serializer(), batch)
        for (host in listOf(GhostlyDomains.COM, GhostlyDomains.MAIN)) {
            val ok = runCatching {
                client.post("https://$host$PATH") {
                    // No device model, no cookies: the app's version and OS are all the server gets.
                    header("User-Agent", "GhostlyVPN/$appVersion ($os)")
                    header("X-GV", "1")
                    contentType(ContentType.Application.Json)
                    setBody(body)
                }.status.isSuccess()
            }.getOrElse { if (it is CancellationException) throw it; false }
            if (ok) return@withContext true
        }
        false
    }

    companion object {
        const val PATH = "/api/web/app/q"
        /** A batch goes out at this size... */
        const val BATCH = 40
        /** ...or when the oldest unsent one is this old and there is something to send. */
        const val EVERY_MS = 10 * 60_000L
        /** Never more than this many kept while offline; the oldest go first. */
        const val MAX_KEPT = 300

        fun due(size: Int, lastSentAt: Long, now: Long): Boolean =
            size >= BATCH || (size > 0 && now - lastSentAt >= EVERY_MS)

        fun <T> trim(list: MutableList<T>, max: Int) {
            if (list.size > max) list.subList(0, list.size - max).clear()
        }
    }
}
