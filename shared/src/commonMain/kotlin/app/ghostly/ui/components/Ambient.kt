package app.ghostly.ui.components

import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlin.time.TimeSource

/**
 * Ambient motion: the looping decorations (aurora, orb breathing, orbit lights, pulses) run off one shared
 * clock instead of `rememberInfiniteTransition`.
 *
 * An infinite transition asks for a frame on every refresh of the screen as long as it is composed — on a
 * 165 Hz monitor that redrew the whole window 165 times a second even when nothing else happened (≈80 % of
 * a core at rest). The shared clock ticks about every 16 ms and stops completely while the window is hidden
 * or minimized, so an idle window costs a fraction of that. The loops compute exactly what the transitions
 * did (same duration, easing and repeat mode); only the number of frames they get differs, and a slow
 * breathing or drifting loop looks the same at 60 frames as at 165. Touch, hover and scrolling keep their
 * own full-rate animations.
 */
object AmbientMotion {
    /** The app is on screen (window shown and not minimized, activity started). Set by the platform shell. */
    val visible = MutableStateFlow(true)

    /** Clock step while visible. */
    const val STEP_MS = 16L
}

/** Milliseconds of the shared ambient clock; provided at the root of the app (GhostlyApp) from [rememberAmbientClock]. */
val LocalAmbientTime = staticCompositionLocalOf<State<Long>> { mutableLongStateOf(0L) }

private val start = TimeSource.Monotonic.markNow()

/** One clock for the whole app: a single coroutine, paused while [AmbientMotion.visible] is false. */
@Composable
fun rememberAmbientClock(): State<Long> {
    val time = remember { mutableLongStateOf(start.elapsedNow().inWholeMilliseconds) }
    val visible by AmbientMotion.visible.collectAsState()
    LaunchedEffect(visible) {
        if (!visible) return@LaunchedEffect
        while (true) {
            time.longValue = start.elapsedNow().inWholeMilliseconds
            delay(AmbientMotion.STEP_MS)
        }
    }
    return time
}

/**
 * `infiniteRepeatable(tween(durationMs, easing = easing), repeatMode)` of [from]..[to], driven by the ambient
 * clock. Starts from [from] when it enters the composition, like an infinite transition does. The value is
 * read lazily, so reading it inside a draw lambda only redraws (no recomposition).
 */
@Composable
fun ambientFloat(
    from: Float, to: Float, durationMs: Int,
    easing: Easing = FastOutSlowInEasing, reverse: Boolean = false,
): State<Float> {
    val time = LocalAmbientTime.current
    val origin = remember(durationMs) { time.value }
    return remember(from, to, durationMs, easing, reverse, time) {
        object : State<Float> {
            override val value: Float
                get() {
                    val d = durationMs.coerceAtLeast(1).toLong()
                    val t = (time.value - origin).coerceAtLeast(0)
                    val cycle = t / d
                    var f = (t % d).toFloat() / d
                    // RepeatMode.Reverse plays every other iteration backwards.
                    if (reverse && cycle % 2 == 1L) f = 1f - f
                    return from + (to - from) * easing.transform(f)
                }
        }
    }
}

/** `infiniteRepeatable(keyframes { durationMillis = durationMs; v at ms … })`, linear between the frames. */
@Composable
fun ambientKeyframes(durationMs: Int, vararg frames: Pair<Int, Float>): State<Float> {
    val time = LocalAmbientTime.current
    val origin = remember(durationMs) { time.value }
    return remember(durationMs, time) {
        val keys = frames.sortedBy { it.first }
        object : State<Float> {
            override val value: Float
                get() {
                    val t = ((time.value - origin).coerceAtLeast(0) % durationMs.coerceAtLeast(1)).toInt()
                    if (t <= keys.first().first) return keys.first().second
                    for (i in 1 until keys.size) {
                        val (t1, v1) = keys[i]
                        if (t <= t1) {
                            val (t0, v0) = keys[i - 1]
                            return v0 + (v1 - v0) * LinearEasing.transform((t - t0).toFloat() / (t1 - t0))
                        }
                    }
                    return keys.last().second
                }
        }
    }
}
