package app.ghostly.shots

import app.ghostly.core.stage.LyricLine
import app.ghostly.core.stage.NowPlaying
import app.ghostly.core.stage.StageAudio
import app.ghostly.core.stage.StageSource
import kotlinx.coroutines.flow.MutableStateFlow

/** Silent, deterministic outfit preview; no audio capture, music player or network is touched. */
internal class DemoStage : StageSource {
    private val started = System.nanoTime()
    override val audio = MutableStateFlow(StageAudio(active = true, bass = 0.24f, melody = 0.3f, vocal = 0.45f, energy = 0.35f, bpm = 140f))
    override val track = MutableStateFlow<NowPlaying?>(NowPlaying("Y2K outfit preview", "Ghostly demo", 180_000, true,
        lines = listOf(LyricLine(0, "Очки и микрофон · демо"), LyricLine(8_000, "Прозрачное стекло, хром и свет")), synced = true))
    override fun positionMs() = (System.nanoTime() - started) / 1_000_000L % 180_000L
    override fun start() {}
    override fun stop() {}
}
