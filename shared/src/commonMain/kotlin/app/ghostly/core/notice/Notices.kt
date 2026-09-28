package app.ghostly.core.notice

import app.ghostly.core.JsonX
import app.ghostly.core.model.Profile
import app.ghostly.core.store.FileStore
import app.ghostly.core.sub.GhostlyDomains
import app.ghostly.core.vpn.PlatformInfo
import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable

/** A message from Ghostly: to everyone, or to this person only. Shown in the app window like an incoming Telegram message. */
@Serializable
data class Notice(
    val id: Long,
    /** info · gift · warn · danger */
    val kind: String = "info",
    val title: String = "",
    val text: String = "",
    val sender: String = "Ghostly",
    val button: NoticeButton? = null,
    /** Unix seconds. */
    val ts: Long = 0,
    /** Addressed to this person, not a broadcast. */
    val personal: Boolean = false,
)

@Serializable
data class NoticeButton(val text: String = "Открыть", val url: String? = null)

@Serializable
private data class NoticesReply(val ok: Boolean = false, val notices: List<Notice> = emptyList())

/**
 * Polls Ghostly's `/sub/<id>/notices` for every Ghostly subscription in the app. Which notices this
 * device already showed is kept locally, so each of the person's devices shows the message once.
 */
class Notices(private val store: FileStore, private val platform: PlatformInfo, private val userAgent: String) {
    private val http = HttpClient {
        install(HttpTimeout) { requestTimeoutMillis = 10_000; connectTimeoutMillis = 6_000 }
        expectSuccess = false
    }

    private val seen = (store.readText(SEEN)?.lines()?.mapNotNull { it.trim().toLongOrNull() } ?: emptyList()).toMutableSet()

    private val _queue = MutableStateFlow<List<Notice>>(emptyList())
    /** Not yet dismissed, oldest first; the UI shows the head. */
    val queue: StateFlow<List<Notice>> = _queue.asStateFlow()

    private val _history = MutableStateFlow<List<Notice>>(emptyList())
    /** The last 14 days, newest first, including dismissed and expired ones (the bell). */
    val history: StateFlow<List<Notice>> = _history.asStateFlow()

    suspend fun loadHistory(profiles: List<Profile>) {
        val all = subsOf(profiles).flatMap { fetch(it, history = true).orEmpty() }
        _history.value = all.distinctBy { it.id }.sortedByDescending { it.id }
    }

    /** Where each notice came from, to report it as seen on the same subscription. */
    private val origin = mutableMapOf<Long, String>()

    suspend fun refresh(profiles: List<Profile>) {
        for (sub in subsOf(profiles)) {
            val list = fetch(sub) ?: continue
            val fresh = list.filter { it.id !in seen && _queue.value.none { q -> q.id == it.id } }
            if (fresh.isEmpty()) continue
            fresh.forEach { origin[it.id] = sub }
            _queue.value = _queue.value + fresh
        }
    }

    suspend fun dismiss(id: Long) {
        _queue.value = _queue.value.filterNot { it.id == id }
        seen += id
        store.writeText(SEEN, seen.sortedDescending().take(300).joinToString("\n"))
        val sub = origin.remove(id) ?: return
        for (host in HOSTS) {
            val ok = runCatching {
                http.post("https://$host/sub/$sub/notices") {
                    header("User-Agent", userAgent)
                    header("x-hwid", platform.hwid)
                    contentType(ContentType.Application.Json)
                    setBody("""{"seen":[$id]}""")
                }.status.isSuccess()
            }.getOrDefault(false)
            if (ok) return
        }
    }

    private fun subsOf(profiles: List<Profile>) =
        profiles.mapNotNull { p -> p.url?.takeIf { GhostlyDomains.isOurs(it) }?.let { subIdOf(it) } }.distinct()

    private suspend fun fetch(sub: String, history: Boolean = false): List<Notice>? {
        for (host in HOSTS) {
            val reply = runCatching {
                val r = http.get("https://$host/sub/$sub/notices" + if (history) "?all=1" else "") {
                    header("User-Agent", userAgent)
                    header("x-hwid", platform.hwid)
                }
                if (r.status.isSuccess()) JsonX.decodeFromString(NoticesReply.serializer(), r.bodyAsText()) else null
            }.getOrNull() ?: continue
            return reply.notices
        }
        return null
    }

    internal companion object {
        private const val SEEN = "notices.seen"
        private val HOSTS = listOf(GhostlyDomains.DIRECT, GhostlyDomains.MAIN)
        private val SUB_ID = Regex("/sub/(sub_[A-Za-z0-9]+)")

        /** `https://…/sub/sub_abc#Name` → `sub_abc`. */
        fun subIdOf(url: String): String? = SUB_ID.find(url)?.groupValues?.get(1)
    }
}
