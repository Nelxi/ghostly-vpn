package app.ghostly.core.design

import app.ghostly.core.JsonX
import app.ghostly.core.store.FileStore
import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable

/**
 * Look & feel knobs the server can turn without an app update (`/dl/design.json`):
 * glow strength, parallax, seasonal accent, an announcement card. Layouts and new
 * animations still ship with the app — these only tune what is already there.
 */
@Serializable
data class DesignTokens(
    /** Background aurora brightness, 1 = default. */
    val aurora: Float = 1f,
    /** How far the background follows the cursor, 0 = off. */
    val parallax: Float = 1f,
    /** Glow around the connect orb, 1 = default. */
    val halo: Float = 1f,
    /** How much dark songs on the stage dim the window and the aurora: 0 = never, 1 = full (pre-0.3.19 look). */
    val stageDim: Float = 0f,
    /** Darkness of the shadows under cards and buttons: 1 = the old heavy shadows. */
    val shadow: Float = 0.45f,
    /** How far the shadows spread (bigger = softer edge): 1 = the old tight shadows. */
    val shadowSoft: Float = 1.6f,
    /**
     * A new track: the ghost's shades and microphone go away for this long (ms) and come back the way
     * they do when music starts. 0 = they stay on through a track change.
     */
    val outfitReplayMs: Int = 320,
    /** "#RRGGBB": replaces the default violet accent (users who picked their own colour keep it). */
    val accent: String? = null,
    val banner: Banner? = null,
) {
    /** Shadows/dim from a design.json without these keys (or a saved pre-0.3.19 one) use the defaults above. */
    fun accentArgb(): Long? = accent?.removePrefix("#")?.takeIf { it.length == 6 }?.toLongOrNull(16)?.let { 0xFF000000L or it }
}

@Serializable
data class Banner(
    val id: String,
    val title: String,
    val text: String = "",
    val url: String? = null,
    /** Unix seconds; 0 = no end. */
    val until: Long = 0,
)

class RemoteDesign(private val store: FileStore, private val userAgent: String) {
    private val http = HttpClient {
        install(HttpTimeout) { requestTimeoutMillis = 10_000; connectTimeoutMillis = 6_000 }
        expectSuccess = false
    }

    private val _tokens = MutableStateFlow(store.load(FILE, DesignTokens.serializer()) ?: DesignTokens())
    val tokens: StateFlow<DesignTokens> = _tokens.asStateFlow()

    private val _dismissed = MutableStateFlow(store.readText(DISMISSED)?.lines()?.filter { it.isNotBlank() }?.toSet() ?: emptySet())
    val dismissed: StateFlow<Set<String>> = _dismissed.asStateFlow()

    suspend fun refresh() {
        for (url in URLS) {
            val t = runCatching {
                val r = http.get(url) { header("User-Agent", userAgent) }
                if (r.status.isSuccess()) JsonX.decodeFromString(DesignTokens.serializer(), r.bodyAsText()) else null
            }.getOrNull() ?: continue
            _tokens.value = t.copy(
                aurora = t.aurora.coerceIn(0f, 2.5f),
                parallax = t.parallax.coerceIn(0f, 3f),
                halo = t.halo.coerceIn(0f, 2.5f),
                stageDim = t.stageDim.coerceIn(0f, 1f),
                shadow = t.shadow.coerceIn(0f, 1.5f),
                shadowSoft = t.shadowSoft.coerceIn(0.5f, 3f),
                outfitReplayMs = t.outfitReplayMs.coerceIn(0, 2000),
            )
            store.save(FILE, DesignTokens.serializer(), _tokens.value)
            return
        }
    }

    fun dismiss(id: String) {
        _dismissed.value = _dismissed.value + id
        store.writeText(DISMISSED, _dismissed.value.joinToString("\n"))
    }

    private companion object {
        const val FILE = "design.json"
        const val DISMISSED = "banners.dismissed"
        val URLS = listOf(
            "https://ghostlynex.fun/dl/design.json",
            "https://ghostlinknex.online/dl/design.json",
        )
    }
}
