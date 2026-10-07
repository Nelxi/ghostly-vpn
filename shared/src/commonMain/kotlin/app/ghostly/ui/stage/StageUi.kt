package app.ghostly.ui.stage

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.ghostly.core.GhostlyController
import app.ghostly.core.stage.LyricLayout
import app.ghostly.core.stage.NowPlaying
import app.ghostly.core.stage.StageAudio
import app.ghostly.ui.theme.Ghost
import app.ghostly.ui.theme.LocalReduceMotion
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin
import kotlin.random.Random

/**
 * Live stage state for the whole UI. [audio] changes ~60×/s — read it only inside draw /
 * graphicsLayer lambdas so the music repaints the screen without recomposing it.
 */
@Stable
class StageState(
    val audio: State<StageAudio>,
    val track: State<NowPlaying?>,
    val positionMs: () -> Long,
) {
    val a: StageAudio get() = audio.value
}

val LocalStage = compositionLocalOf<StageState?> { null }

/** Starts the platform's stage source while stage mode is on; null when unavailable/off. */
@Composable
fun rememberStage(controller: GhostlyController, enabled: Boolean): StageState? {
    val source = controller.platform.stage ?: return null
    DisposableEffect(source, enabled) {
        if (enabled) source.start() else source.stop()
        onDispose { source.stop() }
    }
    if (!enabled) return null
    val audio = source.audio.collectAsState()
    val track = source.track.collectAsState()
    return remember(source) { StageState(audio, track, source::positionMs) }
}

/**
 * Full-window light show on top of everything (never takes input): bass gently lights the window's
 * rims; dark songs may dim the edges when design.json asks for it (`stageDim`). No flashes, no sparks.
 */
@Composable
fun StageOverlay(stage: StageState) {
    val c = Ghost.colors
    val design = app.ghostly.ui.theme.LocalDesign.current
    var frame by remember { mutableLongStateOf(0L) }
    LaunchedEffect(Unit) { while (true) withFrameNanos { frame = it } }
    Canvas(Modifier.fillMaxSize()) {
        frame // redraw every frame
        val a = stage.a
        val w = size.width
        val h = size.height
        val dim = (a.mood * 0.85f * design.stageDim).coerceIn(0f, 0.8f)
        if (dim > 0.01f) drawRect(
            Brush.radialGradient(listOf(Color.Transparent, Color.Black.copy(alpha = dim)), Offset(w / 2, h * 0.42f), max(w, h) * 0.72f),
        )
        // Bass lights the rims of the window like stage wash lights.
        val rim = (a.bass * 0.22f + a.beat * 0.08f) * (1f - a.mood * 0.9f)
        if (rim > 0.01f) {
            drawRect(Brush.verticalGradient(listOf(c.accent.copy(alpha = rim), Color.Transparent), startY = h, endY = h * 0.7f))
            drawRect(Brush.horizontalGradient(listOf(c.accent.copy(alpha = rim * 0.6f), Color.Transparent), startX = 0f, endX = w * 0.18f))
            drawRect(Brush.horizontalGradient(listOf(Color.Transparent, c.accent.copy(alpha = rim * 0.6f)), startX = w * 0.82f, endX = w))
        }
    }
}

/**
 * The line the ghost is singing — drawn the way the Kasane media bar draws it.
 *
 * One clock ([StageState.positionMs], the lyric clock) is read once per frame, and everything on
 * screen is a plain function of that number: which line is the current one, how many of its letters
 * are typed, where it sits and how bright it is, and the same for the line that is leaving. Nothing
 * is remembered between frames, so a line cannot "come back": there is no stored current line to
 * fall out of step with the clock, and no enter/exit animation running on its own time.
 */
@Composable
fun SungLine(stage: StageState, modifier: Modifier = Modifier) {
    val c = Ghost.colors
    val track = stage.track.value ?: return
    if (track.lines.isEmpty()) {
        // Playing but nothing to sing: show what's on, quietly.
        if (track.playing) NowPlayingTag(track, modifier)
        return
    }
    // Keyed by the song itself: the track object is re-emitted on every poll and must not reset anything.
    val songKey = track.artist + "|" + track.title + "|" + track.lines.size
    val lines = track.lines
    val clock = remember(songKey) { mutableLongStateOf(stage.positionMs()) }
    LaunchedEffect(songKey) {
        while (true) withFrameNanos { clock.longValue = stage.positionMs() }
    }
    // Recomposes only when the clock crosses into another line.
    val active by remember(songKey) { derivedStateOf { lineIndexAt(lines, clock.longValue) } }
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Box(contentAlignment = Alignment.Center) {
            if (active < 0) Text("♪", style = MaterialTheme.typography.headlineSmall, color = c.ink3)
            // Only the current line and the one leaving during the change are drawn. The next line is
            // not drawn at all until its time, so it can never show up with letters already typed.
            else for (i in max(0, active - 1)..active) key(i) {
                LyricLineView(
                    text = lines[i].text,
                    startMs = lines[i].timeMs,
                    endMs = LyricLayout.lineEndMs(lines, i, track.durationMs),
                    hasNext = i + 1 < lines.size,
                    current = i == active,
                    clock = clock,
                    stage = stage,
                )
            }
        }
        Spacer(Modifier.height(6.dp))
        NowPlayingTag(track)
    }
}

private fun lineIndexAt(lines: List<app.ghostly.core.stage.LyricLine>, positionMs: Long): Int {
    var lo = 0
    var hi = lines.size - 1
    var found = -1
    while (lo <= hi) {
        val mid = (lo + hi) ushr 1
        if (lines[mid].timeMs <= positionMs) { found = mid; lo = mid + 1 } else hi = mid - 1
    }
    return found
}

/** One line: its place, brightness and typed letters all come from [clock]. */
@Composable
private fun LyricLineView(
    text: String,
    startMs: Long,
    endMs: Long,
    hasNext: Boolean,
    current: Boolean,
    clock: androidx.compose.runtime.LongState,
    stage: StageState,
) {
    val c = Ghost.colors
    // Typed position in "letters × 8" steps: this line recomposes a few dozen times while it types,
    // never per frame. The line that is leaving stands complete.
    val typed by remember(startMs, endMs, text, current) {
        derivedStateOf {
            if (!current) text.length * 8
            else (LyricLayout.typedProgress(clock.longValue, startMs, endMs) * text.length * 8).toInt()
        }
    }
    val full = typed / 8
    val frac = (typed % 8) / 8f
    val size = if (text.length > 42) 17.sp else 21.sp
    val lineHeight = size * 1.25f
    val ink = if (current) c.ink else c.ink2
    Text(
        // Unsung letters stay in the layout but invisible, so the centred line never jumps.
        androidx.compose.ui.text.buildAnnotatedString {
            text.forEachIndexed { i, ch ->
                val col = when {
                    i < full -> ink
                    i == full -> c.accent.copy(alpha = frac)
                    else -> Color.Transparent
                }
                withStyle(androidx.compose.ui.text.SpanStyle(color = col)) { append(ch) }
            }
        },
        style = MaterialTheme.typography.headlineSmall.copy(fontSize = size, fontWeight = FontWeight.Bold, lineHeight = lineHeight),
        textAlign = TextAlign.Center,
        modifier = Modifier.widthIn(max = 560.dp).graphicsLayer {
            // Read in the draw phase: the line moves and fades every frame without recomposing.
            val pose = LyricLayout.pose(clock.longValue, startMs, endMs, hasNext)
            alpha = if (pose.visible) pose.alpha else 0f
            val a = stage.a
            translationY = pose.offset * lineHeight.toPx() - (if (current) a.beat * 2.dp.toPx() else 0f)
            val k = if (current) 1f + a.vocal * 0.03f else 1f
            scaleX = k; scaleY = k
        },
    )
}

@Composable
private fun NowPlayingTag(track: NowPlaying, modifier: Modifier = Modifier) {
    val c = Ghost.colors
    Text(
        "♪  " + listOf(track.artist, track.title).filter { it.isNotBlank() }.joinToString(" — "),
        modifier, style = MaterialTheme.typography.bodySmall, color = c.ink3, maxLines = 1,
    )
}
