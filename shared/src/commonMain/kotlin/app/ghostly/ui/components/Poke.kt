package app.ghostly.ui.components

import androidx.compose.ui.composed
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import app.ghostly.ui.theme.Ghost
import app.ghostly.ui.theme.Motion
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.sin
import kotlin.random.Random

/** Light haptic tick, provided by the app (respects the user's vibration setting). */
val LocalHaptic = staticCompositionLocalOf<() -> Unit> { {} }

/** Haptic of a chosen strength for UI pieces (rows, switches, segmented controls). */
val LocalHapticOf = staticCompositionLocalOf<(app.ghostly.core.vpn.Haptic) -> Unit> { {} }

/** A quiet note when the cursor enters something clickable (desktop; the site's hover sound). */
val LocalHoverSound = staticCompositionLocalOf<() -> Unit> { {} }

/** Plays the hover note when the pointer enters this element. Put in front of every `clickable`. */
fun Modifier.hoverSound(): Modifier = composed {
    val sound = LocalHoverSound.current
    pointerInput(Unit) {
        awaitPointerEventScope {
            while (true) {
                val e = awaitPointerEvent(androidx.compose.ui.input.pointer.PointerEventPass.Initial)
                if (e.type == androidx.compose.ui.input.pointer.PointerEventType.Enter) sound()
            }
        }
    }
}

private class Particle(val start: Offset, val vx: Float, val heart: Boolean, val born: Long, val size: Float, val tint: Int)

/**
 * A ghost you can poke: it squishes and bounces, pulls a face (giggle, wink, surprise, love),
 * throws out hearts and sparkles — and gets dizzy if you poke it like crazy.
 */
@Composable
fun PokeGhost(modifier: Modifier = Modifier, happy: Float = 1f) {
    val c = Ghost.colors
    val haptic = LocalHaptic.current
    val scope = rememberCoroutineScope()
    val bob by ambientFloat(0f, 1f, 2400, Motion.EaseInOut, reverse = true)
    val clock by ambientFloat(0f, 1f, 1000, LinearEasing)

    val squish = remember { Animatable(0f) }
    val jump = remember { Animatable(0f) }
    val tilt = remember { Animatable(0f) }
    var expr by remember { mutableStateOf(GhostExpr.NONE) }
    var exprUntil by remember { mutableStateOf(0L) }
    val pokes = remember { ArrayDeque<Long>() }
    val particles = remember { mutableStateListOf<Particle>() }
    var hover by remember { mutableStateOf<Offset?>(null) }
    val hoverA by animateFloatAsState(if (hover != null) 1f else 0f, Motion.quick(250))

    fun poke(at: Offset, w: Float) {
        haptic()
        val now = kotlin.time.Clock.System.now().toEpochMilliseconds()
        pokes.addLast(now)
        while (pokes.isNotEmpty() && now - pokes.first() > 2200) pokes.removeFirst()
        val side = if (at.x < w / 2) -1f else 1f
        expr = when {
            pokes.size >= 6 -> GhostExpr.DIZZY
            else -> listOf(GhostExpr.GIGGLE, GhostExpr.WINK, GhostExpr.SURPRISED, GhostExpr.LOVE, GhostExpr.GIGGLE).random()
        }
        exprUntil = now + if (expr == GhostExpr.DIZZY) 2200 else 1100
        val count = if (expr == GhostExpr.LOVE) 6 else 3
        repeat(count) {
            particles += Particle(
                at, (Random.nextFloat() - 0.5f) * 1.6f, heart = expr == GhostExpr.LOVE || Random.nextFloat() < 0.4f,
                born = now, size = 0.6f + Random.nextFloat() * 0.6f, tint = Random.nextInt(3),
            )
        }
        particles.removeAll { now - it.born > 1200 }
        if (particles.size > 40) particles.removeRange(0, particles.size - 40)
        scope.launch {
            squish.snapTo(1f)
            squish.animateTo(0f, spring(dampingRatio = 0.28f, stiffness = Spring.StiffnessMediumLow))
        }
        scope.launch {
            jump.animateTo(1f, tween(140, easing = Motion.Ease))
            jump.animateTo(0f, spring(dampingRatio = 0.4f, stiffness = Spring.StiffnessLow))
        }
        scope.launch {
            tilt.animateTo(-side * (if (expr == GhostExpr.DIZZY) 18f else 9f), tween(120))
            tilt.animateTo(0f, spring(dampingRatio = 0.3f, stiffness = Spring.StiffnessLow))
        }
        scope.launch {
            delay(exprUntil - now + 20)
            if (kotlin.time.Clock.System.now().toEpochMilliseconds() >= exprUntil) expr = GhostExpr.NONE
        }
    }

    Canvas(
        modifier
            .pointerHoverIcon(PointerIcon.Hand)
            .pointerInput(Unit) { detectTapGestures { poke(it, size.width.toFloat()) } }
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        val e = awaitPointerEvent()
                        hover = if (e.type == PointerEventType.Exit) null else e.changes.first().position
                    }
                }
            },
    ) {
        @Suppress("UNUSED_EXPRESSION") clock // redraw particles every frame while they live
        val s = size.minDimension
        val now = kotlin.time.Clock.System.now().toEpochMilliseconds()
        val sq = squish.value
        val look = hover?.let { Offset(((it.x / s) - 0.5f) * 2f, ((it.y / s) - 0.5f) * 2f) } ?: Offset.Zero
        val dizzyWobble = if (expr == GhostExpr.DIZZY) sin(now / 90f) * 6f else 0f

        translate(0f, (bob - 0.5f) * s * 0.05f - jump.value * s * 0.12f - hoverA * s * 0.02f) {
            rotate(tilt.value + dizzyWobble, Offset(s / 2, s * 0.8f)) {
                // Squash & stretch around the hem: wider and flatter right after a poke.
                scale(1f + 0.16f * sq, 1f - 0.16f * sq, Offset(s / 2, s * 0.82f)) {
                    drawGhost(s, 1f, maxOf(happy, hoverA * 0.8f), 0f, c.accent, sleepy = 0f, look = look * (0.5f + 0.5f * hoverA), expr = expr)
                }
            }
        }
        // Hearts & sparkles
        particles.forEach { p ->
            val age = (now - p.born) / 1000f
            if (age > 1.1f) return@forEach
            val pos = Offset(p.start.x + p.vx * age * s * 0.5f, p.start.y - age * s * 0.7f)
            val a = (1f - age / 1.1f).coerceIn(0f, 1f)
            val col = when (p.tint) {
                0 -> Color(0xFFFF9AC8)
                1 -> c.accent
                else -> Color.White
            }.copy(alpha = a)
            if (p.heart) drawHeart(pos, s * 0.09f * p.size, col) else drawSparkle(pos, s * 0.06f * p.size, col, age)
        }
    }
}

private fun DrawScope.drawHeart(c: Offset, r: Float, color: Color) {
    val p = Path().apply {
        moveTo(c.x, c.y + r * 0.9f)
        cubicTo(c.x - r * 1.4f, c.y - r * 0.1f, c.x - r * 0.7f, c.y - r * 1.2f, c.x, c.y - r * 0.4f)
        cubicTo(c.x + r * 0.7f, c.y - r * 1.2f, c.x + r * 1.4f, c.y - r * 0.1f, c.x, c.y + r * 0.9f)
        close()
    }
    drawPath(p, color)
}

private fun DrawScope.drawSparkle(c: Offset, r: Float, color: Color, age: Float) {
    rotate(age * 180f, c) {
        val p = Path().apply {
            moveTo(c.x, c.y - r)
            quadraticTo(c.x, c.y, c.x + r, c.y)
            quadraticTo(c.x, c.y, c.x, c.y + r)
            quadraticTo(c.x, c.y, c.x - r, c.y)
            quadraticTo(c.x, c.y, c.x, c.y - r)
            close()
        }
        drawPath(p, color)
    }
}

internal val TAU = (2 * PI).toFloat()
