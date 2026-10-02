package app.ghostly.ui.components

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.lerp
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/*
 * The brand ghost (same shape as the site logo and the bot avatar): an upright ghost with a dome head and a hem
 * of flame points, tilted forward as a whole so the head leads top-right; thin flame strokes trail along its back.
 * Drawn in a 200×200 local box. Everything that moves is a function of [phase] (one 0..2π loop, integer harmonics
 * only), so the animation loops without a seam.
 */

private val BODY_BASE = Color(0xFFCFC4FF)
private val EYE_STOPS = arrayOf(0f to Color(0xFF1D1238), 0.6f to Color(0xFF0C0619), 1f to Color(0xFF05020B))
private const val EX1 = 88f
private const val EX2 = 119f
private const val EY = 70f

/** Flame strokes along the back: start, 2 controls, tip, 2 controls, end — and opacity. */
private val FLAMES = arrayOf(
    floatArrayOf(56f, 84f, 38f, 86f, 18f, 98f, -4f, 120f, 22f, 104f, 42f, 100f, 55f, 100f, .9f),
    floatArrayOf(55f, 108f, 34f, 112f, 12f, 128f, -12f, 160f, 16f, 138f, 38f, 128f, 54f, 126f, .85f),
    floatArrayOf(54f, 134f, 34f, 140f, 16f, 160f, 0f, 192f, 20f, 166f, 40f, 154f, 54f, 150f, .8f),
    floatArrayOf(58f, 96f, 44f, 102f, 30f, 116f, 16f, 136f, 34f, 120f, 48f, 114f, 57f, 112f, .6f),
    floatArrayOf(56f, 120f, 42f, 126f, 28f, 138f, 14f, 156f, 30f, 142f, 44f, 136f, 55f, 136f, .5f),
)

/**
 * Brand ghost in a [s]×[s] box.
 * [phase] drives the idle motion (flames, hem, sway); [waveR]/[waveL] 0..1 raise the right/left hand and wave it;
 * [happy] softens the eyes into a content squint, [sad] droops them; [sleepy] half-closes them.
 */
fun DrawScope.drawMascot(
    s: Float, blink: Float, happy: Float, sad: Float, accent: Color, sleepy: Float,
    look: Offset = Offset.Zero, expr: GhostExpr = GhostExpr.NONE, sing: Float = 0f, showtime: Float = 0f,
    phase: Float = 0f, waveR: Float = 0f, waveL: Float = 0f, motion: Float = 1f,
) {
    val tone = accent
    val deep = lerp(accent, Color(0xFF2A0FB0), 0.5f)
    val k = s / 200f * 1.12f
    val tilt = 30f + 2.5f * motion * sin(phase) + 7f * sad
    // Pointer direction in the ghost's own (tilted) frame.
    val a = -tilt * PI.toFloat() / 180f
    val lk = Offset(look.x * cos(a) - look.y * sin(a), look.x * sin(a) + look.y * cos(a))

    withTransform({
        translate(s / 2 - 100f * k + 4f * k, s / 2 - 100f * k)
        scale(k, k, Offset.Zero)
        rotate(tilt, Offset(100f, 100f))
    }) {
        val body = bodyPath(phase, motion)

        // Aura: a soft glow that hugs the shape.
        drawCircle(Brush.radialGradient(0f to tone.copy(alpha = 0.34f), 0.55f to tone.copy(alpha = 0.14f), 1f to Color.Transparent, center = Offset(104f, 104f), radius = 112f), 112f, Offset(104f, 104f))

        // Flames trail behind.
        val flameBrush = Brush.horizontalGradient(
            0f to tone.copy(alpha = 0.95f), 0.6f to deep.copy(alpha = 0.7f), 1f to deep.copy(alpha = 0f),
            startX = 60f, endX = -12f,
        )
        FLAMES.forEachIndexed { i, f -> drawPath(flamePath(f, i, phase, motion), flameBrush, alpha = f[14] * (0.88f + 0.12f * sin(5 * phase + i * 1.9f))) }

        // Arms come out from behind the body.
        if (waveL > 0.01f) arm(left = true, waveL, phase, tone)
        if (waveR > 0.01f) arm(left = false, waveR, phase, tone)

        // Body, painted, fading to smoke towards the tail.
        drawContext.canvas.saveLayer(Rect(-80f, -80f, 280f, 280f), Paint())
        paintCloth(body, tone, deep, accent)
        drawRect(
            Brush.horizontalGradient(0f to Color.White, 0.7f to Color.White.copy(alpha = 0.85f), 1f to Color.White.copy(alpha = 0.35f), startX = 70f, endX = 16f),
            Offset(-80f, -80f), Size(360f, 360f), blendMode = BlendMode.DstIn,
        )
        drawContext.canvas.restore()

        face(blink, happy, sad, sleepy, lk, expr, sing, showtime, deep)
        if (showtime > 0.01f) stage(showtime, sing, lk, tone)
    }

    // Z's float up from the head when it dozes (screen space, not tilted).
    if (sleepy > 0.6f && sad < 0.1f && expr == GhostExpr.NONE && showtime < 0.05f) {
        val al = (sleepy - 0.6f) / 0.4f
        for (j in 0 until 2) {
            val t = ((phase / (2 * PI.toFloat())) + j * 0.5f) % 1f
            val z = s * (0.07f - 0.02f * j)
            val zx = s * 0.74f + t * s * 0.06f
            val zy = s * 0.16f - t * s * 0.1f
            val p = Path().apply { moveTo(zx, zy); lineTo(zx + z, zy); lineTo(zx, zy + z); lineTo(zx + z, zy + z) }
            drawPath(p, Color.White.copy(alpha = 0.6f * al * sin(t * PI.toFloat())), style = Stroke(s * 0.014f, cap = StrokeCap.Round))
        }
    }
}

/** Ghost outline; the hem points ride a wave travelling from the front to the tail. */
private fun bodyPath(ph: Float, m: Float): Path {
    fun d(i: Int, ax: Float, ay: Float) = Offset(ax * m * sin(2 * ph + i * 0.9f), ay * m * sin(2 * ph + i * 0.9f + 1.1f))
    val dA = d(0, 1.5f, 2.5f); val dB = d(1, 2f, 3f); val dC = d(2, 2.5f, 3.5f); val dD = d(3, 3f, 6.5f)
    return Path().apply {
        moveTo(104f, 14f)
        cubicTo(134f, 14f, 152f, 38f, 152f, 68f)
        cubicTo(152f, 98f, 156f, 122f, 161f, 142f)
        cubicTo(163f, 152f, 159f + dA.x * .6f, 158f + dA.y * .6f, 151f + dA.x, 157f + dA.y)
        cubicTo(147f + dA.x * .6f, 166f + dA.y * .6f, 139f + dB.x * .6f, 172f + dB.y * .6f, 128f + dB.x, 175f + dB.y)
        cubicTo(128f + dB.x * .5f, 165f + dB.y * .5f, 122f, 159f, 114f, 157f)
        cubicTo(112f, 171f, 102f + dC.x * .6f, 183f + dC.y * .6f, 87f + dC.x, 191f + dC.y)
        cubicTo(87f + dC.x * .5f, 181f + dC.y * .5f, 82f, 174f, 74f, 171f)
        cubicTo(64f, 182f, 44f + dD.x * .7f, 180f + dD.y * .7f, 20f + dD.x, 168f + dD.y)
        cubicTo(36f + dD.x * .6f, 158f + dD.y * .6f, 47f, 144f, 51f, 126f)
        cubicTo(54f, 106f, 54f, 88f, 54f, 68f)
        cubicTo(54f, 38f, 74f, 14f, 104f, 14f)
        close()
    }
}

/** One flame stroke; its tip flutters (each flame on its own beat) and stretches a little. */
private fun flamePath(f: FloatArray, i: Int, ph: Float, m: Float): Path {
    val h = 3 + i % 2
    val dx = m * (2.5f * sin(h * ph + i * 1.3f) - 3f * (0.5f + 0.5f * cos(2 * ph + i)))
    val dy = m * 7f * sin(h * ph + i * 1.7f + 1f)
    return Path().apply {
        moveTo(f[0], f[1])
        cubicTo(f[2], f[3], f[4] + dx * .55f, f[5] + dy * .55f, f[6] + dx, f[7] + dy)
        cubicTo(f[8] + dx * .45f, f[9] + dy * .45f, f[10], f[11], f[12], f[13])
        close()
    }
}

/** A soft, blurred-looking ellipse (radial falloff). */
private fun DrawScope.soft(cx: Float, cy: Float, rx: Float, ry: Float, color: Color, alpha: Float, blur: Float, rot: Float = 0f) {
    val r = rx + blur * 1.4f
    val c = Offset(cx, cy)
    withTransform({ rotate(rot, c); scale(1f, (ry + blur * 1.4f) / r, c) }) {
        val inner = (rx / r * 0.7f).coerceIn(0.05f, 0.95f)
        drawCircle(Brush.radialGradient(0f to color.copy(alpha = alpha), inner to color.copy(alpha = alpha * 0.8f), 1f to color.copy(alpha = 0f), center = c, radius = r), r, c)
    }
}

/** A stroke with blurred edges (three passes). */
private fun DrawScope.softStroke(p: Path, color: Color, width: Float, alpha: Float, blur: Float) {
    val n = 7
    for (j in 0 until n) {
        val w = width * 0.5f + (width * 0.5f + blur * 2.6f) * (1f - j / (n - 1f))
        drawPath(p, color.copy(alpha = alpha * 0.13f), style = Stroke(w, cap = StrokeCap.Round))
    }
}

private fun line(vararg v: Float) = Path().apply {
    moveTo(v[0], v[1]); cubicTo(v[2], v[3], v[4], v[5], v[6], v[7])
}

/** Cloth: base colour, tail tint, inner light, back shadow, folds running to the flame points, rim light. */
private fun DrawScope.paintCloth(body: Path, tone: Color, deep: Color, accent: Color) {
    drawPath(body, lerp(BODY_BASE, accent, 0.10f))
    clipPath(body) {
        soft(52f, 196f, 54f, 40f, tone, 1f, 10f)
        soft(34f, 204f, 30f, 22f, deep, 1f, 10f)
        soft(110f, 70f, 46f, 62f, Color.White, 0.95f, 16f)
        soft(108f, 52f, 34f, 32f, Color.White, 1f, 10f)
        soft(168f, 90f, 22f, 22f, Color.White, 1f, 10f)
        soft(54f, 120f, 16f, 70f, tone, 0.5f, 10f)
        softStroke(line(128f, 112f, 126f, 136f, 122f, 150f, 116f, 160f), tone, 6f, 0.35f, 5f)
        softStroke(line(104f, 118f, 100f, 142f, 94f, 162f, 86f, 182f), tone, 7f, 0.38f, 5f)
        softStroke(line(80f, 124f, 76f, 150f, 66f, 174f, 48f, 194f), deep, 7f, 0.3f, 5f)
        softStroke(line(116f, 116f, 114f, 138f, 108f, 154f, 100f, 168f), Color.White, 5f, 0.45f, 5f)
        softStroke(line(92f, 122f, 88f, 148f, 80f, 166f, 68f, 184f), Color.White, 4f, 0.3f, 5f)
        val rim = Path().apply {
            moveTo(72f, 30f); cubicTo(86f, 18f, 110f, 12f, 130f, 22f)
            cubicTo(146f, 32f, 151f, 50f, 150f, 70f); cubicTo(150f, 100f, 151f, 124f, 150f, 148f)
        }
        softStroke(rim, Color.White, 3f, 1f, 2.2f)
        soft(112f, 28f, 16f, 6f, Color.White, 0.9f, 2.2f, rot = -10f)
    }
}

/** Arm from behind the body; [up] 0..1 raises it, then it waves. The left one is the mirror image. */
private fun DrawScope.arm(left: Boolean, up: Float, ph: Float, tone: Color) {
    val p = Path().apply {
        moveTo(144f, 92f); cubicTo(156f, 86f, 164f, 74f, 168f, 60f)
        cubicTo(171f, 48f, 188f, 46f, 190f, 58f)
        cubicTo(192f, 72f, 182f, 94f, 164f, 108f)
        cubicTo(158f, 113f, 150f, 114f, 146f, 112f)
        close()
    }
    val shoulder = Offset(150f, 104f)
    val e = up.coerceIn(0f, 1f)
    // Tucked = swung down into the body; up = raised against the body's forward tilt and swinging.
    // (the left arm is mirrored, so the same tilt already leaves it pointing up)
    val angle = (1f - e) * 75f + e * ((if (left) 0f else -16f) + 16f * sin(4 * ph))
    withTransform({
        if (left) scale(-1f, 1f, Offset(103f, 100f))
        // raised, the hand also leans out of the body a little so it doesn't hide behind the head
        translate(6f * e, -2f * e)
        rotate(angle, shoulder)
    }) {
        drawPath(p, lerp(BODY_BASE, Color.White, 0.25f), alpha = e.coerceAtMost(1f))
        clipPath(p) {
            soft(172f, 66f, 18f, 16f, Color.White, 1f, 6f)
            soft(150f, 110f, 12f, 10f, tone, 0.35f, 6f)
        }
        drawPath(p, Color.White.copy(alpha = 0.5f * e), style = Stroke(1.6f))
    }
}

/** Eye outline: an oval whose lower lid can push up into a content squint ([q] 0..1). */
private fun eye(cx: Float, cy: Float, rx: Float, ry: Float, q: Float): Path {
    val top = cy - ry * 1.33f * (1f - 0.12f * q)
    val endY = cy + 2f * q
    val bottom = cy + ry * 1.33f * (1f - q) - 5.5f * q
    return Path().apply {
        moveTo(cx - rx, endY)
        cubicTo(cx - rx, top, cx + rx, top, cx + rx, endY)
        cubicTo(cx + rx * (1f - 0.5f * q), bottom, cx - rx * (1f - 0.5f * q), bottom, cx - rx, endY)
        close()
    }
}

/** Matte dark eyes with a soft socket shadow — calm, a little mysterious (no highlights, no blush). */
private fun DrawScope.face(
    blink: Float, happy: Float, sad: Float, sleepy: Float, lk: Offset, expr: GhostExpr,
    sing: Float, showtime: Float, deep: Color,
) {
    val ox = lk.x * 3.5f
    val oy = lk.y * 3f
    fun one(x: Float, rx: Float, ry: Float, q: Float, rot: Float, dy: Float) {
        val cx = x + ox
        val cy = EY + oy + dy
        rotate(rot, Offset(cx, cy)) {
            soft(cx, cy + 1f, rx + 1.8f, ry + 1.8f, deep, 0.22f, 2.2f)
            val ryy = (ry * blink).coerceAtLeast(1.2f)
            drawPath(
                eye(cx, cy, rx, ryy, q),
                Brush.radialGradient(*EYE_STOPS, center = Offset(cx - rx * 0.1f, cy + ryy * 0.4f), radius = maxOf(rx, ryy) * 1.5f),
            )
        }
    }
    // Calm oval; sad droops and tilts outward; sleepy lowers the lids; happy pushes the lower lid up a bit.
    fun calm(x: Float, side: Float, scale: Float = 1f) {
        val rx = (10.4f - 1.4f * sad) * scale
        val ry = (15.8f - 3.3f * sad) * scale * (1f - 0.55f * sleepy)
        one(x, rx, ry, 0.35f * happy, side * 16f * sad, 4f * sad + 5f * sleepy)
    }
    fun squint(x: Float) = one(x, 9.8f, 11.5f, 1f, 0f, 0f)
    fun spiral(x: Float) {
        val p = Path()
        val n = 40
        for (i in 0..n) {
            val t = i.toFloat() / n
            val an = t * 2.2f * 2 * PI.toFloat() + lk.x * 3f
            val r = 10f * t
            val px = x + ox + cos(an) * r
            val py = EY + oy + sin(an) * r
            if (i == 0) p.moveTo(px, py) else p.lineTo(px, py)
        }
        drawPath(p, Color(0xFF0C0619), style = Stroke(2.6f, cap = StrokeCap.Round))
    }
    when (expr) {
        GhostExpr.NONE -> { calm(EX1, -1f); calm(EX2, 1f) }
        GhostExpr.GIGGLE, GhostExpr.LOVE -> { squint(EX1); squint(EX2) }
        GhostExpr.WINK -> { calm(EX1, -1f); squint(EX2) }
        GhostExpr.SURPRISED -> { calm(EX1, -1f, 1.08f); calm(EX2, 1f, 1.08f) }
        GhostExpr.DIZZY -> { spiral(EX1); spiral(EX2) }
    }
    // No mouth by default (the brand ghost has none); it shows only when there's something to say.
    val mx = 103.5f + ox
    val my = 95f + oy
    val ink = Color(0xFF0C0619)
    when {
        showtime > 0.5f && expr == GhostExpr.NONE -> {
            val w = 4.5f + 4f * sing
            val h = 3f + 8f * sing
            drawOval(ink, Offset(mx - w / 2, my - 1f), Size(w, h))
        }
        expr == GhostExpr.SURPRISED -> drawOval(ink, Offset(mx - 3.5f, my - 2f), Size(7f, 9f))
        expr == GhostExpr.DIZZY -> {
            val p = Path().apply {
                moveTo(mx - 9f, my); quadraticTo(mx - 4.5f, my - 4f, mx, my); quadraticTo(mx + 4.5f, my + 4f, mx + 9f, my)
            }
            drawPath(p, ink, style = Stroke(2.6f, cap = StrokeCap.Round))
        }
    }
}

/** Stage outfit: sunglasses falling onto the eyes and an arm rising with a microphone. */
private fun DrawScope.stage(t: Float, sing: Float, lk: Offset, tone: Color) {
    val ox = lk.x * 3.5f
    val al = t.coerceIn(0f, 1f)
    val drop = (1f - t.coerceIn(0f, 1.2f)) * 70f
    val lw = 26f
    val lh = 19f
    val y = EY - 10f - drop + lk.y * 3f
    for (x in floatArrayOf(EX1, EX2)) {
        val lx = x + ox - lw / 2
        val lens = Path().apply {
            moveTo(lx, y); lineTo(lx + lw, y)
            quadraticTo(lx + lw * 1.02f, y + lh, lx + lw * 0.55f, y + lh)
            quadraticTo(lx - lw * 0.04f, y + lh * 1.02f, lx, y)
            close()
        }
        drawPath(lens, Brush.verticalGradient(listOf(Color(0xFF221237), Color(0xFF07040D)), startY = y, endY = y + lh), alpha = al)
        drawPath(lens, lerp(tone, Color.White, 0.3f).copy(alpha = 0.55f * al), style = Stroke(1.6f))
        val gx = lx + lw * (0.25f + 0.35f * ((lk.x + 1f) / 2f))
        drawLine(Color.White.copy(alpha = 0.55f * al), Offset(gx, y + lh * 0.2f), Offset(gx - lw * 0.18f, y + lh * 0.72f), strokeWidth = 2f, cap = StrokeCap.Round)
    }
    val frame = Color(0xFF0E0818).copy(alpha = al)
    drawLine(frame, Offset(EX1 + ox - lw / 2, y), Offset(EX2 + ox + lw / 2, y), strokeWidth = 3.4f, cap = StrokeCap.Round)

    // Arm with a microphone, in front of the body.
    val a = ((t - 0.25f) / 0.75f).coerceIn(0f, 1.15f)
    if (a <= 0.01f) return
    val shoulder = Offset(148f, 118f)
    val rest = Offset(150f, 150f)
    val up = Offset(128f, 106f)
    val hand = Offset(rest.x + (up.x - rest.x) * a, rest.y + (up.y - rest.y) * a)
    val bodyWhite = lerp(Color.White, BODY_BASE, 0.5f)
    val armP = Path().apply { moveTo(shoulder.x, shoulder.y); quadraticTo(shoulder.x + 12f, (shoulder.y + hand.y) / 2 + 8f, hand.x, hand.y) }
    drawPath(armP, bodyWhite, style = Stroke(11f, cap = StrokeCap.Round))
    drawPath(armP, tone.copy(alpha = 0.18f), style = Stroke(11f, cap = StrokeCap.Round))
    val head = Offset(hand.x - 13f, hand.y - 12f)
    drawLine(Brush.linearGradient(listOf(Color(0xFF3A3150), Color(0xFF16101F)), hand, head), Offset(hand.x + 3f, hand.y + 5f), head, strokeWidth = 6.5f, cap = StrokeCap.Round)
    val hr = 8.5f * (1f + 0.1f * sing)
    drawCircle(Brush.radialGradient(listOf(Color(0xFFF4F1FF), Color(0xFF9C93B8), Color(0xFF4E4666)), head - Offset(hr * 0.35f, hr * 0.35f), hr * 1.6f), hr, head)
    drawCircle(tone.copy(alpha = 0.35f + 0.4f * sing), hr, head, style = Stroke(1.4f))
    drawCircle(bodyWhite, 7.5f, hand)
    drawCircle(tone.copy(alpha = 0.12f), 7.5f, hand)
}
