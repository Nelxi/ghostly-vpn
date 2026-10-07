package app.ghostly.desktop

import app.ghostly.core.stage.LyricLine
import app.ghostly.core.stage.NowPlaying
import app.ghostly.core.stage.StageAudio
import app.ghostly.core.stage.StageSource
import app.ghostly.desktop.audio.AudioReactiveEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min

/**
 * Windows stage source, ported from the Kasane media bar:
 * - now playing from the system media session (SMTC), read by a separate PowerShell helper (a crash there
 *   can never take the app down);
 * - time-synced lines looked up at runtime on lrclib.net for the track that is playing, matched by
 *   artist and duration (never another song with the same name) and cached on disk;
 * - WASAPI loopback analysis (AudioReactiveEngine) plus a mood layer on top: darkness, tempo,
 *   build-ups and drops.
 */
class DesktopStage(dataDir: String) : StageSource {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    // The now-playing helper is read with blocking calls: give it a dedicated thread.
    private val comThread = Executors.newSingleThreadExecutor { Thread(it, "ghostly-now-playing").apply { isDaemon = true } }
        .asCoroutineDispatcher()
    private var jobs: List<Job> = emptyList()

    private val _audio = MutableStateFlow(StageAudio())
    override val audio: StateFlow<StageAudio> = _audio.asStateFlow()
    private val _track = MutableStateFlow<NowPlaying?>(null)
    override val track: StateFlow<NowPlaying?> = _track.asStateFlow()

    // What the player reports (whole seconds, once a poll) goes into the shared lyric time; the UI
    // reads only that (see app.ghostly.core.stage.LyricTime — the Kasane media bar's clock).
    @Volatile private var msFactor = 1000.0
    private var lastRawPos = -1L
    private var quietPolls = 0

    private val lyricTime = app.ghostly.core.stage.LyricTime()
    @Volatile private var trackKey = ""

    /** The time the lines are typed by: smooth, at the track's pace, steered by the player's position. */
    override fun positionMs(): Long {
        val t = _track.value ?: return 0L
        return synchronized(lyricTime) { lyricTime.nowMs(System.currentTimeMillis(), t.durationMs) }
    }

    override fun start() {
        if (jobs.isNotEmpty() || hostOs != HostOs.WINDOWS) return
        AudioReactiveEngine.startEngine()
        jobs = listOf(scope.launch(comThread) { pollNowPlaying() }, scope.launch { analyse() })
    }

    override fun stop() {
        jobs.forEach { it.cancel() }
        jobs = emptyList()
        AudioReactiveEngine.shutdownEngine()
        _audio.value = StageAudio()
    }

    // ------------------------------------------------------------------ now playing

    /**
     * One reading of the system media session as the helper reports it; [position] and [duration] in
     * seconds. [positionMs] is the same position in ms and [ageMs] how long ago the player published
     * it (-1: unknown) — between two publications a player keeps reporting the same position.
     */
    private class Snap(
        val title: String, val artist: String, val position: Long, val duration: Long, val playing: Boolean,
        val positionMs: Long, val ageMs: Long,
    )

    /**
     * Reads the Windows media session (SMTC) through a separate PowerShell process (resources/nowplaying.ps1).
     * The native library used before threw C++ exceptions that nothing could catch, and each one took the
     * whole app down with it (hs_err_pid*.log, thread "ghostly-now-playing"). Now the worst a broken session
     * can do is end the helper, which is started again on the next read. It sits in [WindowsJob], so it
     * never outlives Ghostly.
     */
    private inner class NowPlayingHelper {
        private var proc: Process? = null
        private var reader: java.io.BufferedReader? = null

        /** The next reading (blocks for about one poll): a session, null when nothing plays. */
        fun next(): Result<Snap?> = runCatching {
            val r = reader?.takeIf { proc?.isAlive == true } ?: start()
            val line = r.readLine() ?: run { close(); error("now-playing helper ended") }
            val o = json.parseToJsonElement(line) as JsonObject
            if (o.containsKey("err")) error("now-playing read failed")
            if (o.isEmpty()) return@runCatching null
            fun str(k: String) = o[k]?.jsonPrimitive?.contentOrNull.orEmpty()
            fun num(k: String) = o[k]?.jsonPrimitive?.doubleOrNull?.toLong() ?: 0L
            val pos = num("pos")
            Snap(
                str("t"), str("a"), pos, num("dur"), o["play"]?.jsonPrimitive?.contentOrNull == "true",
                positionMs = if (o.containsKey("pms")) num("pms") else pos * 1000,
                ageMs = if (o.containsKey("age")) num("age") else -1L,
            )
        }

        private fun start(): java.io.BufferedReader {
            close()
            val script = File(cacheDir.parentFile, "nowplaying.ps1")
            javaClass.getResourceAsStream("/nowplaying.ps1")!!.use { src -> script.outputStream().use { src.copyTo(it) } }
            val p = ProcessBuilder(
                "powershell.exe", "-NoProfile", "-NonInteractive", "-ExecutionPolicy", "Bypass",
                "-File", script.absolutePath, "-PollMs", POLL_MS.toString(),
            ).redirectError(ProcessBuilder.Redirect.DISCARD).start()
            WindowsJob.adopt(p)
            proc = p
            return p.inputStream.bufferedReader(Charsets.UTF_8).also { reader = it }
        }

        fun close() {
            runCatching { reader?.close() }
            proc?.destroyForcibly()
            proc = null
            reader = null
        }
    }

    private suspend fun pollNowPlaying() {
        val helper = NowPlayingHelper()
        var key = ""
        try {
            while (currentCoroutineContextActive()) {
                val ok = runCatching {
                    val m = helper.next().getOrThrow()
                    if (m == null) {
                        if (_track.value != null) { _track.value = null; key = "" }
                        return@runCatching
                    }
                    val now = System.currentTimeMillis()
                    val raw = m.position
                    val delta = if (lastRawPos < 0) -1 else raw - lastRawPos
                    lastRawPos = raw
                    val moving = m.playing || (delta > 0 && delta <= POLL_MS * 3 / msFactor.coerceAtLeast(0.01))
                    // Hysteresis: the flag flickers on some players (Spotify) — a pause needs two quiet polls in a row.
                    quietPolls = if (moving) 0 else quietPolls + 1
                    val playing = moving || (quietPolls < 2 && _track.value?.playing == true)
                    val newKey = (m.artist + "|" + m.title).lowercase()
                    if (newKey != key) {
                        key = newKey
                        trackKey = newKey
                        AudioReactiveEngine.notifyTrackChanged()
                        msFactor = 1000.0
                        _track.value = NowPlaying(m.title, m.artist, (m.duration * msFactor).toLong(), playing, lyricsLoading = true)
                        val title = m.title; val artist = m.artist; val dur = m.duration
                        scope.launch(Dispatchers.IO) { loadLines(newKey, title, artist, dur) }
                    }
                    // The position is reported with the moment the player published it, not the moment it
                    // was read: Spotify publishes about once in 4.5 s, and the same number read again a
                    // second later is not a track standing still (the lyric clock ran ahead of it, took the
                    // growing gap for a rewind and put the previous line back).
                    val publishedAt = if (m.ageMs >= 0) now - m.ageMs else now
                    synchronized(lyricTime) { lyricTime.report(newKey, m.positionMs, playing, publishedAt) }
                    _track.value = _track.value?.copy(playing = playing, durationMs = (m.duration * msFactor).toLong())
                }.isSuccess
                // The helper paces the loop (one line per poll); a failure backs off before it is restarted.
                if (!ok) delay(15_000)
            }
        } finally {
            helper.close()
        }
    }

    private suspend fun currentCoroutineContextActive() = kotlinx.coroutines.currentCoroutineContext().isActive

    // ------------------------------------------------------------------ lines (lrclib, at runtime)

    private val http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(6)).followRedirects(HttpClient.Redirect.NORMAL).build()
    private val json = Json { ignoreUnknownKeys = true }
    private val cacheDir = File(dataDir, "cache/lines").apply { mkdirs() }
    private val memory = ConcurrentHashMap<String, Pair<List<LyricLine>, Boolean>>()

    private fun loadLines(key: String, title: String, artist: String, rawDuration: Long) {
        val found = memory[key] ?: readCache(key) ?: runCatching { lookup(title, artist, rawDuration) }.getOrNull()
        if (found != null) { memory[key] = found; writeCache(key, found) }
        val (lines, synced) = found ?: (emptyList<LyricLine>() to false)
        // Players report the timeline in different units; the looked-up duration tells which.
        _track.value?.let { t ->
            if ((t.artist + "|" + t.title).lowercase() == key) _track.value = t.copy(lines = lines, synced = synced, lyricsLoading = false)
        }
    }

    private fun lookup(title: String, artist: String, rawDuration: Long): Pair<List<LyricLine>, Boolean>? {
        val cleanTitle = strip(title)
        val queries = buildList {
            add("track_name=${enc(cleanTitle)}" + if (artist.isNotBlank()) "&artist_name=${enc(strip(artist))}" else "")
            if (artist.isNotBlank()) add("track_name=${enc(cleanTitle)}")
            add("q=${enc((artist + " " + cleanTitle).trim())}")
        }
        for (q in queries) {
            val body = get("https://lrclib.net/api/search?$q") ?: continue
            val best = pick(body, cleanTitle, artist, rawDuration) ?: continue
            return best
        }
        return null
    }

    private fun pick(body: String, title: String, artist: String, rawDuration: Long): Pair<List<LyricLine>, Boolean>? {
        val arr = runCatching { json.parseToJsonElement(body) as? JsonArray }.getOrNull() ?: return null
        val wantArtist = norm(strip(artist))
        val wantTitle = norm(title.replace(Regex("(?i)\\s+(feat\\.?|ft\\.?)\\s+.*$"), ""))
        var best: Pair<List<LyricLine>, Boolean>? = null
        var bestScore = Double.NEGATIVE_INFINITY
        for (e in arr) {
            val o = e as? JsonObject ?: continue
            val a = norm(o.str("artistName"))
            // Same-name songs by other artists must never be picked.
            if (wantArtist.isNotEmpty() && a.isNotEmpty() && !(a.contains(wantArtist) || wantArtist.contains(a))) continue
            val syncedText = o.str("syncedLyrics")
            val lines = if (syncedText.isNotBlank()) parseLrc(syncedText) else emptyList()
            val synced = lines.isNotEmpty()
            val durMs = ((o["duration"]?.jsonPrimitive?.doubleOrNull ?: 0.0) * 1000).toLong()
            val usable = if (synced) lines else plain(o.str("plainLyrics"), durMs)
            if (usable.isEmpty()) continue
            var score = if (synced) 35.0 else 0.0
            val tn = norm(o.str("trackName"))
            if (wantTitle == tn) score += 120 else if (tn.contains(wantTitle)) score += 70
            if (wantArtist.isNotEmpty()) score += if (a == wantArtist) 90 else 45
            if (rawDuration > 0 && durMs > 0) score += max(0.0, 80.0 - abs(rawDuration * 1000 - durMs) / 1000.0)
            if (score > bestScore) { bestScore = score; best = usable to synced }
        }
        return best
    }

    private fun parseLrc(text: String): List<LyricLine> {
        val stamp = Regex("\\[(\\d{1,3}):(\\d{2})(?:[.:](\\d{1,3}))?]")
        val out = ArrayList<LyricLine>()
        text.lines().forEach { row ->
            val times = stamp.findAll(row).map { m ->
                val frac = m.groupValues[3]
                val ms = when (frac.length) { 0 -> 0L; 1 -> frac.toLong() * 100; 2 -> frac.toLong() * 10; else -> frac.take(3).toLong() }
                (m.groupValues[1].toLong() * 60 + m.groupValues[2].toLong()) * 1000 + ms
            }.toList()
            val words = stamp.replace(row, "").trim()
            if (times.isNotEmpty() && words.isNotEmpty()) times.forEach { out += LyricLine(it, words) }
        }
        return out.sortedBy { it.timeMs }
    }

    private fun plain(text: String, durMs: Long): List<LyricLine> {
        val rows = text.lines().map { it.trim() }.filter { it.isNotEmpty() }
        if (rows.isEmpty()) return emptyList()
        val step = max(1500L, (if (durMs > 0) durMs else rows.size * 3500L) / rows.size)
        return rows.mapIndexed { i, r -> LyricLine(i * step, r) }
    }

    private fun get(url: String): String? = runCatching {
        val r = http.send(
            HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(20))
                .header("User-Agent", "GhostlyVPN (https://github.com/Nelxi/ghostly-vpn)").GET().build(),
            HttpResponse.BodyHandlers.ofString(),
        )
        r.body().takeIf { r.statusCode() in 200..299 }
    }.getOrNull()

    private fun cacheFile(key: String) = File(cacheDir, Integer.toHexString(key.hashCode()) + ".lrc")

    private fun readCache(key: String): Pair<List<LyricLine>, Boolean>? = runCatching {
        val f = cacheFile(key).takeIf { it.isFile } ?: return null
        val rows = f.readLines()
        if (rows.firstOrNull() != key) return null
        val synced = rows.getOrNull(1) == "synced"
        rows.drop(2).mapNotNull { r -> r.indexOf('\t').takeIf { it > 0 }?.let { LyricLine(r.substring(0, it).toLong(), r.substring(it + 1)) } } to synced
    }.getOrNull()

    private fun writeCache(key: String, v: Pair<List<LyricLine>, Boolean>) {
        runCatching {
            cacheFile(key).writeText(buildString {
                appendLine(key); appendLine(if (v.second) "synced" else "plain")
                v.first.forEach { appendLine("${it.timeMs}\t${it.text}") }
            })
        }
    }

    private fun JsonObject.str(k: String) = this[k]?.jsonPrimitive?.contentOrNull.orEmpty()
    private fun enc(s: String) = URLEncoder.encode(s, Charsets.UTF_8)
    private fun norm(s: String) = s.lowercase().replace(Regex("\\s+"), " ").trim()
    private fun strip(s: String) = s.replace(Regex("\\s*\\([^)]*\\)\\s*$"), "").replace(Regex("\\s*\\[[^]]*]\\s*$"), "")
        .replace(Regex("(?i)\\s*[-–—]\\s*(official.*|audio|video|lyrics?)\\s*$"), "").trim()

    // ------------------------------------------------------------------ mood

    private suspend fun analyse() {
        var longE = 0f; var shortE = 0f; var dark = 0.3f; var tempo = 0.4f
        var bassOut = 0f; var beatOut = 0f; var aggrAvg = 0f
        var drop = 0f; var build = 0f; var lastDrop = 0L; var quietSince = System.currentTimeMillis()
        var last = System.nanoTime()
        while (currentCoroutineContextActive()) {
            val s = AudioReactiveEngine.current()
            val now = System.nanoTime()
            val dt = ((now - last) / 1e9f).coerceIn(0.001f, 0.2f)
            last = now
            val e = s.energy()
            fun ema(cur: Float, target: Float, tau: Float) = cur + (target - cur) * (1f - exp(-dt / tau))
            shortE = ema(shortE, e, 0.25f)
            longE = ema(longE, e, 7f)
            val ms = System.currentTimeMillis()
            if (shortE < 0.3f) quietSince = min(quietSince, ms) else if (shortE > 0.45f) quietSince = ms

            // Build-up: energy and treble climbing steadily above the long average.
            val rising = ((shortE - longE) * 3f).coerceIn(0f, 1f) * (0.5f + 0.5f * s.treble())
            build = ema(build, rising, 1.2f)
            // Drop: a sharp jump well above the recent level with the bass kicking in.
            val jump = s.active() && shortE > 0.42f && shortE > longE * 1.8f + 0.08f && s.bass() > 0.45f
            if (jump && ms - lastDrop > 9000) { drop = 1f; lastDrop = ms; build = 0f }
            drop = max(0f, drop - dt / 3.2f)

            // Darkness: calm, low-treble, slow material; aggression and brightness pull it back.
            val bpm = s.bpm()
            // Hazy/cloud-rap and ambient (Clams Casino-like) often read as double tempo, so slowness is
            // judged mostly by calm, soft highs and a lack of sustained aggression, not only by BPM.
            aggrAvg = ema(aggrAvg, s.aggression(), 6f)
            val slow = when {
                bpm in 1f..95f -> 0.35f
                bpm > 0f && s.calm() > 0.5f && aggrAvg < 0.35f -> 0.25f
                else -> 0f
            }
            // Dark wins over light: a raised baseline, and only real aggression pulls the stage bright.
            val darkTarget = (0.25f + s.calm() * 0.6f + (1f - s.treble()) * 0.35f + slow - aggrAvg * 0.45f).coerceIn(0f, 1f)
            dark = ema(dark, if (s.active()) darkTarget else 0f, 5f)  // silence: fade back to the plain look, never a jump
            // Calm songs move calmly: the bass swells slowly and kicks barely jolt the stage.
            bassOut = ema(bassOut, s.bass(), 0.05f + 0.55f * dark)
            beatOut = s.beat() * (1f - 0.7f * dark)
            val tempoTarget = if (bpm > 0 && s.bpmConfidence() > 0.2f) ((bpm - 70f) / 100f).coerceIn(0f, 1f) else 0.4f
            tempo = ema(tempo, tempoTarget, 3f)

            _audio.value = StageAudio(
                active = s.active(), bass = bassOut, melody = s.melody(), vocal = s.vocalPresence(), treble = s.treble(),
                energy = e, beat = beatOut, bpm = bpm, beatPhase = s.beatPhase(), aggression = s.aggression(),
                calm = s.calm(), darkness = dark, tempo = tempo, drop = drop, build = build.coerceIn(0f, 1f),
            )
            delay(16)
        }
    }

    private companion object {
        const val POLL_MS = 1200L
    }
}

