package app.ghostly.vpn.stage

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.SystemClock
import android.provider.Settings
import android.service.notification.NotificationListenerService
import app.ghostly.core.stage.LyricLine
import app.ghostly.core.stage.Lyrics
import app.ghostly.core.stage.NowPlaying
import app.ghostly.core.stage.StageAudio
import app.ghostly.core.stage.StageSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.exp
import kotlin.math.max

/**
 * Needed only so Android lets us read the media sessions of other apps (which track plays where);
 * we never look at notifications themselves.
 */
class GhostlyMediaListener : NotificationListenerService()

/**
 * Android stage source: the ghost sings along with whatever plays on the phone.
 * - now playing from the system media sessions (needs "notification access" for Ghostly); Android
 *   reports the position with its update time and speed, so the clock is exact without polling tricks;
 * - the same lrclib.net lines as on Windows ([Lyrics]);
 * - no audio analysis (that would need the microphone permission): the mouth follows the synced lines.
 */
class AndroidStage(private val context: Context) : StageSource {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var jobs: List<Job> = emptyList()

    private val _audio = MutableStateFlow(StageAudio())
    override val audio: StateFlow<StageAudio> = _audio.asStateFlow()
    private val _track = MutableStateFlow<NowPlaying?>(null)
    override val track: StateFlow<NowPlaying?> = _track.asStateFlow()
    private val _setup = MutableStateFlow<String?>(null)
    override val setupNeeded: StateFlow<String?> = _setup.asStateFlow()
    override val whereLabel = "на телефоне"

    @Volatile private var controller: MediaController? = null

    override fun positionMs(): Long {
        val st = controller?.playbackState ?: return 0L
        var p = st.position
        if (st.state == PlaybackState.STATE_PLAYING) {
            p += ((SystemClock.elapsedRealtime() - st.lastPositionUpdateTime) * st.playbackSpeed).toLong() + LYRIC_LEAD_MS
        }
        val dur = _track.value?.durationMs ?: 0L
        return if (dur > 0) p.coerceIn(0, dur) else max(0, p)
    }

    override fun start() {
        if (jobs.isNotEmpty()) return
        jobs = listOf(scope.launch { pollSessions() }, scope.launch { analyse() })
    }

    override fun stop() {
        jobs.forEach { it.cancel() }
        jobs = emptyList()
        controller = null
        _audio.value = StageAudio()
    }

    override fun requestSetup() {
        when {
            // Android 13+ greys the switch out for apps installed outside a store ("restricted setting");
            // the second tap opens App info, where the ⋮ menu has "Allow restricted settings".
            !hasListenerAccess() -> context.startActivity(
                (if (setupTaps++ % 2 == 0) Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
                else Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, android.net.Uri.fromParts("package", context.packageName, null)))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
    }

    private var setupTaps = 0

    private fun hasListenerAccess(): Boolean =
        (Settings.Secure.getString(context.contentResolver, "enabled_notification_listeners") ?: "")
            .contains(ComponentName(context, GhostlyMediaListener::class.java).flattenToString())

    private fun updateSetup() {
        _setup.value = when {
            !hasListenerAccess() -> "Разрешите Ghostly доступ к уведомлениям — так он видит, какой трек играет (сами уведомления не читаются). Если переключатель серый: нажмите ещё раз → ⋮ → «Разрешить ограниченные настройки»"
            else -> null
        }
    }

    // ------------------------------------------------------------------ now playing

    private suspend fun pollSessions() {
        val msm = context.getSystemService(MediaSessionManager::class.java)
        val listener = ComponentName(context, GhostlyMediaListener::class.java)
        var key = ""
        while (kotlinx.coroutines.currentCoroutineContext().isActive) {
            updateSetup()
            val sessions = runCatching { msm?.getActiveSessions(listener).orEmpty() }.getOrDefault(emptyList())
            val c = sessions.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING && it.metadata != null }
                ?: sessions.firstOrNull { it.metadata != null }
            controller = c
            val md = c?.metadata
            if (c == null || md == null) {
                if (_track.value != null) { _track.value = null; key = "" }
            } else {
                val title = md.getString(MediaMetadata.METADATA_KEY_TITLE).orEmpty()
                val artist = (md.getString(MediaMetadata.METADATA_KEY_ARTIST) ?: md.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST)).orEmpty()
                val dur = md.getLong(MediaMetadata.METADATA_KEY_DURATION)
                val playing = c.playbackState?.state == PlaybackState.STATE_PLAYING
                val newKey = (artist + "|" + title).lowercase()
                if (newKey != key && title.isNotBlank()) {
                    key = newKey
                    _track.value = NowPlaying(title, artist, dur, playing, lyricsLoading = true)
                    scope.launch(Dispatchers.IO) { loadLines(newKey, title, artist, dur) }
                } else {
                    _track.value = _track.value?.copy(playing = playing, durationMs = dur)
                }
            }
            delay(if (c != null) 700 else 2500)
        }
    }

    // ------------------------------------------------------------------ lines

    private val cacheDir = File(context.cacheDir, "lines").apply { mkdirs() }
    private val memory = ConcurrentHashMap<String, Pair<List<LyricLine>, Boolean>>()

    private fun loadLines(key: String, title: String, artist: String, durationMs: Long) {
        val file = File(cacheDir, Integer.toHexString(key.hashCode()) + ".lrc")
        val found = memory[key]
            ?: runCatching { Lyrics.decode(key, file.readText()) }.getOrNull()
            ?: runCatching { Lyrics.lookup(title, artist, durationMs, ::get) }.getOrNull()
                ?.also { runCatching { file.writeText(Lyrics.encode(key, it)) } }
        if (found != null) memory[key] = found
        val (lines, synced) = found ?: (emptyList<LyricLine>() to false)
        _track.value?.let { t ->
            if ((t.artist + "|" + t.title).lowercase() == key) _track.value = t.copy(lines = lines, synced = synced, lyricsLoading = false)
        }
    }

    private fun get(url: String): String? = runCatching {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 6000; c.readTimeout = 20000
        c.setRequestProperty("User-Agent", "GhostlyVPN (https://github.com/Nelxi/ghostly-vpn)")
        try { if (c.responseCode in 200..299) c.inputStream.bufferedReader().readText() else null } finally { c.disconnect() }
    }.getOrNull()

    // ------------------------------------------------------------------ motion

    /**
     * No audio analysis on Android: it needs the microphone permission, and a VPN app asking for the
     * microphone is exactly what Play Protect flags. The ghost moves from what the session and the
     * lines tell us: the mouth follows the synced lines, the stage breathes gently while music plays.
     */
    private suspend fun analyse() {
        var energy = 0f
        var last = System.nanoTime()
        while (kotlinx.coroutines.currentCoroutineContext().isActive) {
            val now = System.nanoTime()
            val dt = ((now - last) / 1e9f).coerceIn(0.001f, 0.2f)
            last = now
            val ms = SystemClock.elapsedRealtime()
            val playing = _track.value?.playing == true
            energy += ((if (playing) 0.35f else 0f) - energy) * (1f - exp(-dt / 1.5f))
            val breath = energy * (0.75f + 0.25f * kotlin.math.sin(ms / 900.0).toFloat())
            _audio.value = StageAudio(
                active = false, bass = breath * 0.5f, melody = breath, vocal = lineVoice(ms), energy = breath,
                calm = 1f - breath, darkness = 0f, tempo = 0.4f,
            )
            delay(if (playing) 16 else 250)
        }
    }

    private fun lineVoice(ms: Long): Float {
        val t = _track.value ?: return 0f
        if (!t.playing || !t.synced || t.lines.isEmpty()) return 0f
        val pos = positionMs()
        val i = t.lineAt(pos)
        if (i < 0) return 0f
        val end = t.lines.getOrNull(i + 1)?.timeMs ?: (t.lines[i].timeMs + 4000)
        if (pos > end - 250) return 0f
        return 0.45f + 0.35f * kotlin.math.abs(kotlin.math.sin(ms / 110.0)).toFloat()
    }

    private companion object {
        /** A line starts typing at its first letter; a hair early keeps it with the voice. */
        const val LYRIC_LEAD_MS = 200L
    }
}
