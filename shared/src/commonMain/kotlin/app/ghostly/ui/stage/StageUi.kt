package app.ghostly.ui.stage

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
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
import app.ghostly.core.stage.NowPlaying
import app.ghostly.core.stage.StageAudio
import app.ghostly.ui.theme.Ghost
import app.ghostly.ui.theme.LocalReduceMotion
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
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
 * The line the ghost is singing: letters appear with the song (typed across the line's own time),
 * each one popping in; when the next line starts the old one floats up and away.
 */
@Composable
fun SungLine(stage: StageState, modifier: Modifier = Modifier) {
    val c = Ghost.colors
    val track = stage.track.value ?: return
    if (track.lines.isEmpty() || !track.playing) {
        // Playing but nothing to sing: show what's on, quietly.
        if (track.playing) NowPlayingTag(track, modifier)
        return
    }
    // Keyed by the song itself: the track object is re-emitted on every poll and must not reset the line.
    val songKey = track.artist + "|" + track.title + "|" + track.lines.size
    var index by remember(songKey) { mutableStateOf(-1) }
    LaunchedEffect(songKey) {
        while (true) {
            val t = stage.track.value
            if (t != null) {
                val i = t.lineAt(stage.positionMs())
                if (i != index) index = i
            }
            withFrameNanos { }
        }
    }
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        AnimatedContent(
            index,
            transitionSpec = {
                (fadeIn(tween(260)) + slideInVertically(spring(0.8f, 300f)) { it / 2 } + scaleIn(initialScale = 0.96f)) togetherWith
                    (fadeOut(tween(300)) + slideOutVertically(tween(420)) { -it } + scaleOut(targetScale = 0.9f))
            },
        ) { i ->
            val line = track.lines.getOrNull(i)
            if (line == null) Text("♪", style = MaterialTheme.typography.headlineSmall, color = c.ink3)
            else {
                val next = track.lines.getOrNull(i + 1)?.timeMs ?: (line.timeMs + 4000)
                TypedLine(line.text, line.timeMs, next, stage)
            }
        }
        Spacer(Modifier.height(6.dp))
        NowPlayingTag(track)
    }
}

@Composable
private fun TypedLine(text: String, start: Long, end: Long, stage: StageState) {
    val c = Ghost.colors
    // Type across most of the line's slot, but never slower than a natural singing pace.
    val span = min((end - start) * 0.8f, text.length * 95f + 500f).coerceAtLeast(300f)
    // Typed position in "letters × 8" steps: recomposes only this line, ~a few dozen times per line.
    var typed by remember(text) { mutableStateOf(0) }
    LaunchedEffect(text) {
        while (true) {
            val p = ((stage.positionMs() - start) / span).coerceIn(0f, 1f)
            val v = (p * text.length * 8).toInt()
            if (v != typed) typed = v
            withFrameNanos { }
        }
    }
    val full = typed / 8
    val frac = (typed % 8) / 8f
    val size = if (text.length > 42) 17.sp else 21.sp
    Text(
        // Unsung letters stay in the layout but invisible, so the centred line never jumps.
        androidx.compose.ui.text.buildAnnotatedString {
            text.forEachIndexed { i, ch ->
                val col = when {
                    i < full -> c.ink
                    i == full -> c.accent.copy(alpha = frac)
                    else -> Color.Transparent
                }
                withStyle(androidx.compose.ui.text.SpanStyle(color = col)) { append(ch) }
            }
        },
        style = MaterialTheme.typography.headlineSmall.copy(fontSize = size, fontWeight = FontWeight.Bold, lineHeight = size * 1.25f),
        textAlign = TextAlign.Center,
        modifier = Modifier.widthIn(max = 560.dp).graphicsLayer {
            val a = stage.a
            translationY = -a.beat * 2.dp.toPx()
            val k = 1f + a.vocal * 0.03f
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
