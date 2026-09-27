package app.ghostly.core.stage

import kotlinx.coroutines.flow.StateFlow

/**
 * "Stage mode": the ghost sings along with whatever is playing on the computer and the whole
 * interface plays with the music. The platform supplies the audio analysis and the now-playing
 * track (with time-synced lines looked up at runtime); the shared UI only renders them.
 */
interface StageSource {
    /** Audio + mood, refreshed ~60 times a second while running. */
    val audio: StateFlow<StageAudio>
    val track: StateFlow<NowPlaying?>
    /** Current playback position of [track], extrapolated between player polls. */
    fun positionMs(): Long
    fun start()
    fun stop()

    /** What the user still has to allow for the stage to work fully (null = nothing), in plain words. */
    val setupNeeded: StateFlow<String?> get() = NoSetup

    /** Opens the system screen/dialog for [setupNeeded]. */
    fun requestSetup() {}

    /** Where the music is heard: "на компьютере", "на телефоне". */
    val whereLabel: String get() = "на компьютере"
}

private val NoSetup: StateFlow<String?> = kotlinx.coroutines.flow.MutableStateFlow(null)

/** Everything 0..1 unless noted. */
data class StageAudio(
    val active: Boolean = false,
    val bass: Float = 0f,
    val melody: Float = 0f,
    val vocal: Float = 0f,
    val treble: Float = 0f,
    val energy: Float = 0f,
    /** Beat pulse: jumps to ~1 on a kick, decays. */
    val beat: Float = 0f,
    /** Beats per minute (0 = unknown) and 0..1 phase inside the current beat. */
    val bpm: Float = 0f,
    val beatPhase: Float = 0f,
    val aggression: Float = 0f,
    val calm: Float = 0f,
    /** Slow, dark, quiet songs push this up: the stage dims and moves slowly. */
    val darkness: Float = 0f,
    /** 0 = slow ballad … 1 = fast. */
    val tempo: Float = 0.4f,
    /** Flashes to 1 when a drop hits after a quiet stretch, then fades over a few seconds. */
    val drop: Float = 0f,
    /** Rising tension before a likely drop. */
    val build: Float = 0f,
) {
    /** Darkness that may touch the UI: only while music is actually analysed. In silence (or on phones,
     *  where there is no audio analysis) the look stays exactly as without the stage. */
    val mood: Float get() = if (active) darkness else 0f
}

data class LyricLine(val timeMs: Long, val text: String)

data class NowPlaying(
    val title: String,
    val artist: String,
    val durationMs: Long,
    val playing: Boolean,
    val lines: List<LyricLine> = emptyList(),
    /** Lines carry real timestamps (otherwise they are spread evenly and the ghost only hums). */
    val synced: Boolean = false,
    val lyricsLoading: Boolean = false,
) {
    /** Index of the line playing at [positionMs], -1 before the first one. */
    fun lineAt(positionMs: Long): Int {
        var lo = 0
        var hi = lines.size - 1
        var found = -1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            if (lines[mid].timeMs <= positionMs) { found = mid; lo = mid + 1 } else hi = mid - 1
        }
        return found
    }
}
