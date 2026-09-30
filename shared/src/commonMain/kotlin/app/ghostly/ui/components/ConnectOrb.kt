package app.ghostly.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.animateOffsetAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import kotlinx.coroutines.launch
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.ghostly.ui.theme.Ghost
import app.ghostly.ui.theme.LocalReduceMotion
import app.ghostly.ui.theme.Motion
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

enum class OrbState { IDLE, CONNECTING, CONNECTED, ERROR }

/**
 * The big connect button: a liquid-glass orb with a living ghost inside.
 * Idle — the ghost dozes, soft "tap me" pulses; connecting — a comet ring spins and dust speeds up;
 * connected — the ring closes into an aurora, energy waves roll out, the ghost smiles and floats.
 * On desktop the ghost's eyes follow the cursor.
 */
@Composable
fun ConnectOrb(
    state: OrbState, onClick: () -> Unit, modifier: Modifier = Modifier, size: Dp = 236.dp,
    /** Stage mode, read only while drawing (60 fps audio must not recompose): mouth, beat pulse, drop light. */
    sing: () -> Float = { 0f }, beat: () -> Float = { 0f }, flare: () -> Float = { 0f },
    music: () -> Boolean = { false },
) {
    val c = Ghost.colors
    val reduce = LocalReduceMotion.current
    val interaction = remember { MutableInteractionSource() }

    val spin by ambientFloat(0f, 360f, if (state == OrbState.CONNECTING) 1000 else 16_000, LinearEasing)
    val orbitState = ambientFloat(0f, (2 * PI).toFloat(), if (state == OrbState.CONNECTING) 2600 else 11_000, LinearEasing)
    val orbit by orbitState
    val swirl by ambientFloat(0f, 360f, 9000, LinearEasing)
    val wave by ambientFloat(0f, 1f, if (reduce) 4200 else 2600, LinearEasing)
    val breath by ambientFloat(0f, 1f, if (reduce) 5200 else 3000, Motion.EaseInOut, reverse = true)
    val floatY by ambientFloat(0f, 1f, 2600, Motion.EaseInOut, reverse = true)
    val blink by ambientKeyframes(4800, 0 to 1f, 4300 to 1f, 4400 to 0.08f, 4520 to 1f)

    val on by animateFloatAsState(if (state == OrbState.CONNECTED) 1f else 0f, tween(900, easing = Motion.Ease))
    val musicOn by remember { androidx.compose.runtime.derivedStateOf { music() } }
    // Springy on purpose: the shades bounce onto the face, the arm swings up.
    val showtime by animateFloatAsState(if (musicOn) 1f else 0f, spring(dampingRatio = 0.5f, stiffness = 90f))
    val busy by animateFloatAsState(if (state == OrbState.CONNECTING) 1f else 0f, Motion.quick(400))
    val err by animateFloatAsState(if (state == OrbState.ERROR) 1f else 0f, Motion.quick(400))

    // Cursor tracking: eyes look at it, the orb leans in a touch.
    var pointer by remember { mutableStateOf<Offset?>(null) }
    var hover by remember { mutableStateOf(false) }
    val hoverA by animateFloatAsState(if (hover) 1f else 0f, Motion.quick(300))
    // The spring follows only the cursor and settles when there is none; the idle sway is added on top from
    // the ambient clock. A spring chasing the always-moving sway never settled, and a running spring asks for
    // a frame on every screen refresh — the window kept redrawing at the monitor's full rate at rest.
    val lookSpring = animateOffsetAsState(
        pointer?.let { p -> Offset(p.x.coerceIn(-1f, 1f), p.y.coerceIn(-1f, 1f)) } ?: Offset.Zero,
        spring(dampingRatio = 0.6f, stiffness = 120f),
    )
    val idleLook = animateFloatAsState(if (pointer == null) 1f else 0f, spring(dampingRatio = 1f, stiffness = 120f))
    val look by remember(orbitState) {
        androidx.compose.runtime.derivedStateOf {
            val o = orbitState.value
            lookSpring.value + Offset(0.18f * sin(o * 0.5f), 0.12f * cos(o * 0.7f)) * idleLook.value
        }
    }

    // Every tap squishes the ghost and makes it giggle — it's a button, but a cute one.
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val squish = remember { Animatable(0f) }
    var expr by remember { mutableStateOf(GhostExpr.NONE) }
    val tap: () -> Unit = {
        onClick()
        expr = listOf(GhostExpr.GIGGLE, GhostExpr.WINK, GhostExpr.SURPRISED).random()
        scope.launch {
            squish.snapTo(1f)
            squish.animateTo(0f, spring(dampingRatio = 0.3f, stiffness = 300f))
        }
        scope.launch {
            kotlinx.coroutines.delay(900)
            expr = GhostExpr.NONE
        }
    }

    // A ring of light bursts outward every time we become connected.
    val burst = remember { Animatable(1f) }
    LaunchedEffect(state) {
        if (state == OrbState.CONNECTED) {
            burst.snapTo(0f)
            burst.animateTo(1f, tween(1400, easing = Motion.Ease))
        }
    }

    val dust = remember {
        val r = Random(3)
        List(18) { floatArrayOf(r.nextFloat() * 6.28f, 0.84f + r.nextFloat() * 0.16f, 0.5f + r.nextFloat(), 0.7f + r.nextFloat() * 1.6f, r.nextFloat()) }
    }

    val accent = lerp(lerp(c.accent, c.ok, on * 0.5f), c.bad, err)
    val deep = lerp(c.accent2, c.bad, err * 0.6f)

    Box(
        modifier
            .size(size)
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        val e = awaitPointerEvent()
                        val p = e.changes.first().position
                        val half = this.size.width / 2f
                        when (e.type) {
                            PointerEventType.Exit -> { hover = false; pointer = null }
                            else -> {
                                // Outside the orb the eyes still follow, but more lazily.
                                pointer = Offset((p.x - half) / half, (p.y - half) / half) * 0.8f
                                val d = sqrt((p.x - half) * (p.x - half) + (p.y - half) * (p.y - half))
                                hover = d < half * 0.8f
                            }
                        }
                    }
                }
            }
            .pressScale(interaction, 0.93f, hover = 1.03f)
            .graphicsLayer { val k = 1f + 0.045f * beat() + 0.06f * flare(); scaleX = k; scaleY = k }
            // Glass lens tilts towards the cursor in 3D (and sways a little on its own when idle).
            .then(if (reduce) Modifier else Modifier.graphicsLayer {
                // Full tilt only under the cursor; the idle sway stays a hint.
                val k = if (pointer != null) 11f else 3f
                rotationY = look.x * k
                rotationX = -look.y * k
                cameraDistance = 14f * density
            })
            .clip(CircleShape)
            .hoverSound().clickable(interactionSource = interaction, indication = null, onClick = tap),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val r = this.size.minDimension / 2
            val center = this.center

            // 1. Bloom
            val bloom = 0.20f + 0.10f * breath + 0.30f * on + 0.10f * hoverA + 0.25f * beat() + 0.45f * flare()
            drawCircle(Brush.radialGradient(listOf(accent.copy(alpha = bloom), accent.copy(alpha = bloom * 0.3f), Color.Transparent), center, r), r)

            // 2. Waves: gentle invitation pulses when idle, steady energy rings when connected.
            val waveCount = 3
            for (i in 0 until waveCount) {
                val p = (wave + i.toFloat() / waveCount) % 1f
                val alpha = (1f - p) * (0.10f * (1f - on) * (1f - busy) + 0.32f * on)
                if (alpha > 0.005f) drawCircle(accent.copy(alpha = alpha), r * (0.70f + 0.30f * p), style = Stroke(1.5.dp.toPx()))
            }
            if (burst.value < 1f) {
                drawCircle(Color.White.copy(alpha = (1f - burst.value) * 0.6f), r * (0.66f + 0.34f * burst.value), style = Stroke(4.dp.toPx() * (1f - burst.value) + 1f))
            }

            // 3. Orbiting dust
            dust.forEach { d ->
                val a = d[0] + orbit * d[2] * (if (d[4] > 0.5f) 1f else -1f)
                val rr = r * d[1] * (0.92f + 0.03f * sin(orbit * 3 + d[0]))
                val pos = center + Offset(cos(a) * rr, sin(a) * rr * 0.96f)
                val alpha = (0.25f + 0.55f * maxOf(on, busy)) * (0.4f + 0.6f * sin(orbit * 4 + d[0]).let { it * it })
                drawCircle(lerp(accent, Color.White, d[4] * 0.6f).copy(alpha = alpha), d[3].dp.toPx(), pos)
            }

            // 4. Liquid glass disc with a gently wobbling rim.
            val discR = r * 0.70f
            val wobble = Path().apply {
                val n = 90
                for (i in 0..n) {
                    val a = (i.toFloat() / n) * 2 * PI.toFloat()
                    val k = 1f + (0.012f + 0.01f * hoverA + 0.006f * busy) * sin(a * 3 + orbit * 2) + 0.008f * sin(a * 5 - orbit * 3)
                    val x = center.x + cos(a) * discR * k
                    val y = center.y + sin(a) * discR * k
                    if (i == 0) moveTo(x, y) else lineTo(x, y)
                }
                close()
            }
            drawPath(
                wobble,
                Brush.radialGradient(
                    listOf(Color.White.copy(alpha = 0.10f + 0.05f * on), deep.copy(alpha = 0.26f + 0.2f * on), Color(0xFF0B0812).copy(alpha = 0.94f)),
                    center = center - Offset(discR * 0.25f, discR * 0.35f), radius = discR * 1.6f,
                ),
            )
            // Aurora swirl inside the glass
            clipPath(wobble) {
                rotate(swirl, center) {
                    drawCircle(Brush.radialGradient(listOf(accent.copy(alpha = 0.30f * (0.35f + 0.65f * on)), Color.Transparent), center + Offset(discR * 0.45f, 0f), discR * 0.8f), discR * 0.8f, center + Offset(discR * 0.45f, 0f))
                    drawCircle(Brush.radialGradient(listOf(Color(0xFFFF9AC8).copy(alpha = 0.16f * (0.3f + 0.7f * on)), Color.Transparent), center - Offset(discR * 0.5f, discR * 0.2f), discR * 0.7f), discR * 0.7f, center - Offset(discR * 0.5f, discR * 0.2f))
                }
                // Specular highlight
                drawArc(
                    Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.35f), Color.Transparent), startY = center.y - discR, endY = center.y - discR * 0.4f),
                    startAngle = 200f, sweepAngle = 80f, useCenter = false,
                    topLeft = center - Offset(discR * 0.86f, discR * 0.86f), size = Size(discR * 1.72f, discR * 1.72f),
                    style = Stroke(discR * 0.06f, cap = StrokeCap.Round),
                )
            }
            drawPath(wobble, Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.32f), Color.White.copy(alpha = 0.03f)), startY = center.y - discR, endY = center.y + discR), style = Stroke(1.2.dp.toPx()))

            // 5. Ring: faint track + conic light. Connecting shows a comet arc.
            val ringR = r * 0.80f
            val stroke = 3.2.dp.toPx()
            drawCircle(Color.White.copy(alpha = 0.06f), ringR, style = Stroke(stroke))
            rotate(spin, center) {
                val sweep = Brush.sweepGradient(listOf(Color.Transparent, deep.copy(alpha = 0.7f), accent, Color.White.copy(alpha = 0.9f), Color.Transparent), center)
                val arc = 360f * (0.18f + 0.82f * on) * (1f - busy) + 110f * busy
                drawArc(
                    sweep, startAngle = -arc / 2, sweepAngle = arc.coerceAtLeast(40f), useCenter = false,
                    topLeft = center - Offset(ringR, ringR), size = Size(ringR * 2, ringR * 2),
                    style = Stroke(stroke + 1.5.dp.toPx() * on, cap = StrokeCap.Round),
                    alpha = 0.35f + 0.65f * maxOf(on, busy, 0.25f + 0.2f * breath + 0.3f * hoverA),
                )
            }

            // 6. The ghost
            val gh = discR * 1.02f
            val bob = (if (reduce) 1.5f else 5f).dp.toPx() * (floatY - 0.5f) * (0.4f + 0.6f * on)
            translate(center.x - gh / 2 + look.x * 3.dp.toPx(), center.y - gh / 2 + bob + look.y * 2.dp.toPx() - squish.value * gh * 0.06f) {
                scale(1f + 0.14f * squish.value, 1f - 0.14f * squish.value, Offset(gh / 2, gh * 0.82f)) {
                    drawGhost(gh, blink, on, err, accent, sleepy = if (expr != GhostExpr.NONE || showtime > 0.05f) 0f else (1f - maxOf(on, busy, hoverA * 0.9f)), look = look, expr = expr, sing = sing(), showtime = showtime)
                }
            }
        }
    }
}

/** Ghost glyph in a [s]×[s] box. [look] (-1..1) moves the pupils. */
fun DrawScope.drawGhost(
    s: Float, blink: Float, happy: Float, sad: Float, accent: Color, sleepy: Float,
    look: Offset = Offset.Zero, expr: GhostExpr = GhostExpr.NONE, sing: Float = 0f,
    /** 0..1: music started — shades drop onto the eyes, a hand comes out with a microphone. */
    showtime: Float = 0f,
) {
    val w = s * 0.62f
    val left = (s - w) / 2
    val top = s * 0.14f
    val bottom = s * 0.80f
    val body = Path().apply {
        moveTo(left, top + w / 2)
        arcTo(Rect(left, top, left + w, top + w), 180f, 180f, false)
        lineTo(left + w, bottom)
        // Three round lobes along the hem
        val scallop = w / 3
        for (i in 0 until 3) {
            val x0 = left + w - i * scallop
            val x1 = x0 - scallop
            quadraticTo((x0 + x1) / 2, bottom + scallop * 0.62f, x1, bottom)
        }
        close()
    }
    drawPath(body, Brush.verticalGradient(listOf(Color.White, lerp(Color(0xFFE9E2FF), accent, 0.18f)), startY = top, endY = bottom))
    drawPath(body, Brush.horizontalGradient(listOf(Color.Transparent, accent.copy(alpha = 0.16f)), startX = left + w * 0.45f, endX = left + w))

    val eyeY = top + w * 0.50f + look.y * w * 0.04f
    val eyeDx = w * 0.19f
    val eyeW = w * 0.13f
    val openH = w * 0.19f
    val ink = Color(0xFF1B1030)
    val lineW = w * 0.04f

    fun arcEye(cx: Float) {
        // "^" — squeezed happy eye
        val p = Path().apply {
            moveTo(cx - eyeW * 0.75f, eyeY + eyeW * 0.25f)
            quadraticTo(cx, eyeY - eyeW * 0.9f, cx + eyeW * 0.75f, eyeY + eyeW * 0.25f)
        }
        drawPath(p, ink, style = Stroke(lineW, cap = StrokeCap.Round))
    }

    fun spiralEye(cx: Float) {
        val p = Path()
        val turns = 2.2f
        val n = 40
        for (i in 0..n) {
            val t = i.toFloat() / n
            val a = t * turns * 2 * PI.toFloat() + look.x * 3f
            val r = eyeW * 0.85f * t
            val x = cx + cos(a) * r
            val y = eyeY + sin(a) * r
            if (i == 0) p.moveTo(x, y) else p.lineTo(x, y)
        }
        drawPath(p, ink, style = Stroke(lineW * 0.75f, cap = StrokeCap.Round))
    }

    fun roundEye(cx: Float, scale: Float) {
        val h = (openH * scale * (1f - 0.72f * sleepy)) * blink
        drawRoundRect(ink, Offset(cx - eyeW * scale / 2, eyeY - h / 2), Size(eyeW * scale, h.coerceAtLeast(1.2f)), CornerRadius(eyeW * scale / 2, eyeW * scale / 2))
        if (h > openH * 0.5f) drawCircle(Color.White.copy(alpha = 0.9f), eyeW * scale * 0.18f, Offset(cx + eyeW * 0.16f, eyeY - h * 0.22f))
    }

    val leftX = s / 2 - eyeDx + look.x * w * 0.05f
    val rightX = s / 2 + eyeDx + look.x * w * 0.05f
    when (expr) {
        GhostExpr.NONE -> { roundEye(leftX, 1f); roundEye(rightX, 1f) }
        GhostExpr.GIGGLE, GhostExpr.LOVE -> { arcEye(leftX); arcEye(rightX) }
        GhostExpr.WINK -> { roundEye(leftX, 1f); arcEye(rightX) }
        GhostExpr.SURPRISED -> { roundEye(leftX, 1.3f); roundEye(rightX, 1.3f) }
        GhostExpr.DIZZY -> { spiralEye(leftX); spiralEye(rightX) }
    }

    val mx = s / 2 + look.x * w * 0.04f
    val blushA = when (expr) {
        GhostExpr.GIGGLE, GhostExpr.LOVE, GhostExpr.WINK -> 0.7f
        GhostExpr.NONE -> 0.45f * happy
        else -> 0.3f
    }
    if (blushA > 0.01f) {
        for (dx in floatArrayOf(-eyeDx * 1.55f, eyeDx * 1.55f)) {
            drawCircle(Color(0xFFFF9AC8).copy(alpha = blushA), w * 0.07f, Offset(s / 2 + dx + look.x * w * 0.03f, eyeY + w * 0.16f))
        }
    }
    when {
        // Singing into the mic: a small soft "o" that opens with the vocal.
        showtime > 0.5f && expr == GhostExpr.NONE -> {
            val mw = w * (0.045f + 0.035f * sing)
            val mh = w * (0.025f + 0.07f * sing)
            drawOval(ink, Offset(mx - w * 0.03f - mw / 2, eyeY + w * 0.17f), Size(mw, mh))
        }
        expr == GhostExpr.SURPRISED -> drawOval(ink, Offset(mx - w * 0.04f, eyeY + w * 0.15f), Size(w * 0.08f, w * 0.1f))
        expr == GhostExpr.DIZZY -> {
            val wave = Path().apply {
                moveTo(mx - w * 0.09f, eyeY + w * 0.2f)
                quadraticTo(mx - w * 0.045f, eyeY + w * 0.15f, mx, eyeY + w * 0.2f)
                quadraticTo(mx + w * 0.045f, eyeY + w * 0.25f, mx + w * 0.09f, eyeY + w * 0.2f)
            }
            drawPath(wave, ink, style = Stroke(w * 0.03f, cap = StrokeCap.Round))
        }
        expr != GhostExpr.NONE -> {
            // Open laughing mouth
            val mouth = Path().apply {
                moveTo(mx - w * 0.09f, eyeY + w * 0.16f)
                quadraticTo(mx, eyeY + w * 0.32f, mx + w * 0.09f, eyeY + w * 0.16f)
                close()
            }
            drawPath(mouth, ink)
            drawCircle(Color(0xFFFF7FA8), w * 0.035f, Offset(mx, eyeY + w * 0.215f))
        }
        happy > 0.01f -> {
            val mouth = Path().apply {
                moveTo(mx - w * 0.08f, eyeY + w * 0.17f)
                quadraticTo(mx, eyeY + w * (0.17f + 0.10f * happy), mx + w * 0.08f, eyeY + w * 0.17f)
            }
            drawPath(mouth, ink.copy(alpha = happy), style = Stroke(w * 0.035f, cap = StrokeCap.Round))
        }
        sad > 0.01f -> drawCircle(ink.copy(alpha = sad), w * 0.045f, Offset(s / 2, eyeY + w * 0.2f), style = Stroke(w * 0.03f))
    }
    if (showtime > 0.01f) drawShowtime(w, left, top, bottom, eyeY, eyeDx, eyeW, openH, mx, look, sing, showtime, accent)
    if (sleepy > 0.6f && sad < 0.1f && expr == GhostExpr.NONE && showtime < 0.05f) {
        val a = (sleepy - 0.6f) / 0.4f
        val zx = left + w * 1.02f
        val zy = top + w * 0.05f
        val z = w * 0.12f
        val zPath = Path().apply { moveTo(zx, zy); lineTo(zx + z, zy); lineTo(zx, zy + z); lineTo(zx + z, zy + z) }
        drawPath(zPath, Color.White.copy(alpha = 0.55f * a), style = Stroke(w * 0.025f, cap = StrokeCap.Round))
    }
}

/** Stage outfit: sunglasses falling onto the eyes and an arm rising with a microphone. */
private fun DrawScope.drawShowtime(
    w: Float, left: Float, top: Float, bottom: Float, eyeY: Float, eyeDx: Float, eyeW: Float, openH: Float,
    mx: Float, look: Offset, sing: Float, t: Float, accent: Color,
) {
    val cx = left + w / 2 + look.x * w * 0.05f
    // --- sunglasses: drop from above with a little overshoot (t comes from a spring)
    val g = t.coerceIn(0f, 1.2f)
    val drop = (1f - g) * w * 0.9f
    val lensW = eyeW * 2.45f
    val lensH = openH * 1.15f
    val y = eyeY - lensH * 0.55f - drop
    val alpha = t.coerceIn(0f, 1f)
    val frame = Color(0xFF0E0818).copy(alpha = alpha)
    for (dir in floatArrayOf(-1f, 1f)) {
        val lx = cx + dir * eyeDx - lensW / 2
        val lens = Path().apply {
            // Flat top, rounded bottom — classic wayfarer-ish shape, slightly tilted outwards.
            moveTo(lx, y)
            lineTo(lx + lensW, y)
            quadraticTo(lx + lensW + lensW * 0.02f, y + lensH, lx + lensW * 0.55f, y + lensH)
            quadraticTo(lx - lensW * 0.04f, y + lensH * 1.02f, lx, y)
            close()
        }
        drawPath(lens, Brush.verticalGradient(listOf(Color(0xFF221237), Color(0xFF07040D)), startY = y, endY = y + lensH), alpha = alpha)
        drawPath(lens, lerp(accent, Color.White, 0.3f).copy(alpha = 0.55f * alpha), style = Stroke(w * 0.018f))
        // Glint that slides across the lens with the head's motion.
        val gx = lx + lensW * (0.25f + 0.35f * ((look.x + 1f) / 2f))
        drawLine(Color.White.copy(alpha = 0.55f * alpha), Offset(gx, y + lensH * 0.2f), Offset(gx - lensW * 0.18f, y + lensH * 0.72f), strokeWidth = w * 0.022f, cap = StrokeCap.Round)
    }
    drawLine(frame, Offset(cx - eyeDx + lensW / 2, y + lensH * 0.2f), Offset(cx + eyeDx - lensW / 2, y + lensH * 0.2f), strokeWidth = w * 0.035f, cap = StrokeCap.Round)
    // Thick top bar across both lenses.
    drawLine(frame, Offset(cx - eyeDx - lensW / 2, y), Offset(cx + eyeDx + lensW / 2, y), strokeWidth = w * 0.04f, cap = StrokeCap.Round)

    // --- arm + microphone: rises out of the right side of the body
    val a = ((t - 0.25f) / 0.75f).coerceIn(0f, 1.15f)
    if (a <= 0.01f) return
    val shoulder = Offset(left + w * 0.9f, top + w * 1.02f)
    val handRest = Offset(left + w * 0.88f, bottom - w * 0.05f)
    val handUp = Offset(mx + w * 0.2f, eyeY + w * 0.42f)
    val hand = Offset(handRest.x + (handUp.x - handRest.x) * a, handRest.y + (handUp.y - handRest.y) * a)
    val bodyWhite = lerp(Color.White, Color(0xFFE9E2FF), 0.5f)
    val arm = Path().apply {
        moveTo(shoulder.x, shoulder.y)
        quadraticTo(shoulder.x + w * 0.2f, (shoulder.y + hand.y) / 2 + w * 0.08f, hand.x, hand.y)
    }
    drawPath(arm, bodyWhite, style = Stroke(w * 0.11f, cap = StrokeCap.Round))
    drawPath(arm, accent.copy(alpha = 0.18f), style = Stroke(w * 0.11f, cap = StrokeCap.Round))
    // Handle from the hand towards the mouth; the head sits just below the lips.
    val head = Offset(hand.x - w * 0.13f, hand.y - w * 0.15f)
    drawLine(
        Brush.linearGradient(listOf(Color(0xFF3A3150), Color(0xFF16101F)), hand, head),
        Offset(hand.x + w * 0.03f, hand.y + w * 0.05f), head, strokeWidth = w * 0.065f, cap = StrokeCap.Round,
    )
    val hr = w * 0.085f * (1f + 0.1f * sing)
    drawCircle(Brush.radialGradient(listOf(Color(0xFFF4F1FF), Color(0xFF9C93B8), Color(0xFF4E4666)), head - Offset(hr * 0.35f, hr * 0.35f), hr * 1.6f), hr, head)
    // Mesh
    val mesh = Color(0xFF2A2338).copy(alpha = 0.5f)
    for (i in -2..2) {
        val d = i * hr * 0.38f
        val half = kotlin.math.sqrt((hr * hr - d * d).coerceAtLeast(0f))
        drawLine(mesh, Offset(head.x + d, head.y - half), Offset(head.x + d, head.y + half), strokeWidth = w * 0.008f)
        drawLine(mesh, Offset(head.x - half, head.y + d), Offset(head.x + half, head.y + d), strokeWidth = w * 0.008f)
    }
    drawCircle(accent.copy(alpha = 0.35f + 0.4f * sing), hr, head, style = Stroke(w * 0.014f))
    // The hand: a little mitten wrapped around the handle.
    drawCircle(bodyWhite, w * 0.075f, hand)
    drawCircle(accent.copy(alpha = 0.12f), w * 0.075f, hand)
}

enum class GhostExpr { NONE, GIGGLE, WINK, SURPRISED, LOVE, DIZZY }

/** Tiny ghost for headers and empty states — pokeable. */
@Composable
fun GhostMark(modifier: Modifier = Modifier, happy: Float = 1f, pokeable: Boolean = true) {
    if (pokeable) {
        PokeGhost(modifier, happy)
        return
    }
    val c = Ghost.colors
    val bob by ambientFloat(0f, 1f, 2400, Motion.EaseInOut, reverse = true)
    Canvas(modifier) {
        translate(0f, (bob - 0.5f) * size.height * 0.05f) {
            drawGhost(size.minDimension, 1f, happy, 0f, c.accent, sleepy = 0f)
        }
    }
}
