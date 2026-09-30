package app.ghostly.ui.components

import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.addOutline
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.dropShadow
import androidx.compose.ui.draw.innerShadow
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.ghostly.ui.theme.Ghost
import app.ghostly.ui.theme.LocalDesign
import app.ghostly.ui.theme.LocalReduceMotion
import app.ghostly.ui.theme.Motion
import kotlinx.coroutines.delay
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

/**
 * The site's backdrop: deep violet night, slowly drifting aurora blobs and twinkling "ghost dust".
 * [energy] 0..1 brightens it (connected state) and pulls a blob towards mint.
 */
@Composable
fun AuroraBackground(energy: Float, modifier: Modifier = Modifier, content: @Composable BoxScope.() -> Unit) {
    val c = Ghost.colors
    val reduce = LocalReduceMotion.current
    val phase by ambientFloat(0f, (2 * PI).toFloat(), if (reduce) 70_000 else 30_000, LinearEasing)
    val drift by ambientFloat(0f, 1f, if (reduce) 120_000 else 60_000, LinearEasing)
    val e by animateFloatAsState(energy, tween(1400, easing = Motion.Ease))
    val stars = remember {
        val r = Random(7)
        List(70) { floatArrayOf(r.nextFloat(), r.nextFloat(), 0.6f + r.nextFloat() * 1.6f, r.nextFloat() * 6.28f, 0.4f + r.nextFloat()) }
    }
    // Parallax: the aurora leans away from the cursor, stars (nearer) move more — depth for free.
    var mouse by remember { mutableStateOf(Offset.Zero) }
    val par by androidx.compose.animation.core.animateOffsetAsState(mouse, androidx.compose.animation.core.spring(dampingRatio = 1f, stiffness = 18f))
    val remote = LocalDesign.current
    val stage = app.ghostly.ui.stage.LocalStage.current
    Box(
        modifier.fillMaxSize().background(c.bg).pointerInput(Unit) {
            awaitPointerEventScope {
                while (true) {
                    val ev = awaitPointerEvent(androidx.compose.ui.input.pointer.PointerEventPass.Initial)
                    val p = ev.changes.firstOrNull()?.position ?: continue
                    if (!reduce) mouse = Offset(p.x / size.width - 0.5f, p.y / size.height - 0.5f)
                }
            }
        },
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val w = size.width
            val h = size.height
            val m = maxOf(w, h)
            val px = -par.x * 46.dp.toPx() * remote.parallax
            val py = -par.y * 34.dp.toPx() * remote.parallax
            // Stage: the aurora breathes with the bass, sinks with dark songs, blazes on a drop.
            val sa = stage?.a
            // Darkness dominates: bass/beat only brighten bright songs, a drop flares less in a dark one.
            val glow = remote.aurora * (1f + (sa?.let {
                (it.bass * 0.6f + it.beat * 0.2f) * (1f - it.mood) + it.drop * 1.2f * (1f - 0.6f * it.mood) - it.mood * 0.9f
            } ?: 0f)).coerceAtLeast(0.12f)
            fun blob(cx0: Float, cy0: Float, r0: Float, color: Color, alpha0: Float) {
                val cx = cx0 + px; val cy = cy0 + py
                val r = r0 * (1f + (sa?.let { it.bass * 0.10f + it.drop * 0.18f } ?: 0f))
                val alpha = (alpha0 * glow).coerceIn(0f, 1f)
                drawCircle(
                    Brush.radialGradient(
                        listOf(color.copy(alpha = alpha), color.copy(alpha = alpha * 0.4f), Color.Transparent),
                        center = Offset(cx, cy), radius = r,
                    ),
                    radius = r, center = Offset(cx, cy),
                )
            }
            blob(w * (0.15f + 0.12f * cos(phase)), h * (0.10f + 0.07f * sin(phase * 2)), m * 0.62f, c.accent2, 0.34f + 0.14f * e)
            blob(w * (0.90f + 0.08f * sin(phase)), h * (0.32f + 0.08f * cos(phase)), m * 0.50f, c.accent, 0.15f + 0.08f * e)
            blob(w * (0.45f + 0.22f * cos(phase + 2f)), h * (1.0f + 0.05f * sin(phase)), m * 0.66f, lerp(c.accent2, c.ok, e), 0.18f + 0.14f * e)
            blob(w * (0.65f + 0.1f * sin(phase * 1.5f + 1f)), h * (0.62f + 0.06f * cos(phase)), m * 0.35f, Color(0xFFFF9AC8), 0.05f + 0.04f * e)

            // Ghost dust: tiny stars drifting up and twinkling.
            stars.forEach { s ->
                val y = ((s[1] - drift * s[4] * 0.35f) % 1f + 1f) % 1f
                val tw = (0.5f + 0.5f * sin(phase * 3f * s[4] + s[3])) * (1f + (sa?.let { it.treble * 1.2f + it.drop * 2f } ?: 0f))
                val depth = 1.2f + s[4] * 1.4f
                drawCircle(Color.White.copy(alpha = (0.10f + 0.35f * tw) * (0.7f + 0.3f * e)), s[2].dp.toPx() * 0.8f, Offset(s[0] * w + px * depth, y * h + py * depth))
            }
            // Vignette keeps text readable at the bottom.
            drawRect(Brush.verticalGradient(listOf(Color.Transparent, c.bg.copy(alpha = 0.6f)), startY = h * 0.55f, endY = h))
        }
        content()
    }
}

/** Frosted-glass surface: translucent fill, hairline border with a lit top edge, cursor spotlight on desktop. */
@Composable
fun GlassCard(
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(28.dp),
    padding: Dp = 18.dp,
    strong: Boolean = false,
    glow: Color? = null,
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val c = Ghost.colors
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val lit by animateFloatAsState(if (hovered) 1f else 0f, Motion.quick(260))
    var m = modifier
    if (onClick != null) m = m.pressScale(interaction, 0.975f, hover = 1.012f)
    // Neverlose-style heavy black shadows, drawn only OUTSIDE the outline (see outerShadow): a dense dark
    // rim hugging the edge, a soft mid shadow and a wide heavy tail. The glass itself keeps its colour.
    m = m.outerShadow(shape, if (strong) 1f else 0.85f)
    m = m.clip(shape)
        .background(
            Brush.verticalGradient(
                listOf(
                    Color.White.copy(alpha = (if (strong) 0.10f else 0.065f) + 0.03f * lit),
                    Color.White.copy(alpha = 0.025f + 0.015f * lit),
                ),
            ),
        )
    if (glow != null) m = m.background(Brush.radialGradient(listOf(glow.copy(alpha = 0.16f), Color.Transparent)))
    // Soft inner depth: edges of the glass sink a little, and a thin light line catches the top edge.
    m = m.innerShadow(shape, androidx.compose.ui.graphics.shadow.Shadow(radius = 18.dp, color = Color.Black.copy(alpha = 0.32f)))
        .innerShadow(shape, androidx.compose.ui.graphics.shadow.Shadow(radius = 2.dp, color = Color.White.copy(alpha = 0.07f), offset = androidx.compose.ui.unit.DpOffset(0.dp, 1.dp)))
    val stageForCard = app.ghostly.ui.stage.LocalStage.current
    if (stageForCard != null) m = m.drawWithContent {
        drawContent()
        val a = stageForCard.a
        val k = (a.beat * 0.35f + a.drop * 0.5f) * (1f - a.mood * 0.6f)
        if (k > 0.02f) drawRect(Brush.verticalGradient(listOf(c.accent.copy(alpha = 0.22f * k), Color.Transparent), endY = size.height * 0.5f))
    }
    m = m.spotlight(c.accent)
        .border(
            1.dp,
            Brush.verticalGradient(
                listOf(
                    lerp(Color.White.copy(alpha = 0.16f), c.accent.copy(alpha = 0.55f), lit),
                    Color.White.copy(alpha = 0.04f + 0.06f * lit),
                ),
            ),
            shape,
        )
    m = if (onClick != null) m.hoverSound().clickable(interactionSource = interaction, indication = null, onClick = onClick)
    else m.hoverable(interaction) // plain cards light up under the cursor too, like the site
    Column(m.padding(padding), content = content)
}

/**
 * Springy "dock" feel: lifts a little under the mouse, shrinks while held, overshoots on release.
 * Also switches the cursor to a hand on desktop.
 */
fun Modifier.pressScale(interaction: MutableInteractionSource, pressed: Float = 0.94f, hover: Float = 1.04f): Modifier = composed {
    val isPressed by interaction.collectIsPressedAsState()
    val isHovered by interaction.collectIsHoveredAsState()
    val scale by animateFloatAsState(
        when {
            isPressed -> pressed
            isHovered -> hover
            else -> 1f
        },
        Motion.bouncy(),
    )
    pointerHoverIcon(PointerIcon.Hand).graphicsLayer { scaleX = scale; scaleY = scale }
}

/** Soft light that follows the cursor across a surface (the site's card spotlight). No-op on touch. */
fun Modifier.spotlight(color: Color, radius: Dp = 240.dp): Modifier = composed {
    var pos by remember { mutableStateOf(Offset.Zero) }
    var inside by remember { mutableStateOf(false) }
    val a by animateFloatAsState(if (inside) 1f else 0f, tween(if (inside) 220 else 520))
    pointerInput(Unit) {
        awaitPointerEventScope {
            while (true) {
                val e = awaitPointerEvent()
                when (e.type) {
                    PointerEventType.Enter, PointerEventType.Move -> {
                        // Only mouse hover counts; touch drags would leave a light trail.
                        if (e.changes.none { it.pressed } || inside) {
                            pos = e.changes.first().position
                            inside = e.type == PointerEventType.Move || e.type == PointerEventType.Enter
                        }
                    }
                    PointerEventType.Exit -> inside = false
                }
            }
        }
    }.drawWithContent {
        drawContent()
        if (a > 0.01f) {
            val r = radius.toPx()
            drawCircle(Brush.radialGradient(listOf(color.copy(alpha = 0.13f * a), Color.Transparent), pos, r), r, pos)
        }
    }
}

/**
 * Staggered entrance: fade + rise, [index] sets the delay. [enabled] = false shows the item at once —
 * lazy lists pass false for rows that were already seen, so fast scrolling doesn't replay it.
 */
fun Modifier.appear(index: Int, step: Long = 35L, enabled: Boolean = true): Modifier = composed {
    if (!enabled) return@composed this
    val reduce = LocalReduceMotion.current
    var shown by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        delay((index.coerceAtMost(14) * step))
        shown = true
    }
    val p by animateFloatAsState(if (shown) 1f else 0f, if (reduce) tween(200) else Motion.bouncy())
    graphicsLayer {
        // Motion only, no alpha: a fade renders through an offscreen buffer clipped to the card's box (the
        // shadow gets cut, then pops), and ModulateAlpha doesn't reach child layers (the text shows at once
        // while the shadow lags). A card with its shadow is fully drawn from the first frame instead.
        translationY = (1f - p) * 18.dp.toPx()
        val s = 0.96f + 0.04f * p
        scaleX = s; scaleY = s
    }
}

/** Diagonal light band that sweeps across a surface each time the cursor enters it (the site's button shine). */
fun Modifier.sheen(interaction: MutableInteractionSource, color: Color = Color.White, strength: Float = 0.26f): Modifier = composed {
    val hovered by interaction.collectIsHoveredAsState()
    val reduce = LocalReduceMotion.current
    val p = remember { Animatable(1f) }
    LaunchedEffect(hovered) {
        if (hovered && !reduce) {
            p.snapTo(0f)
            p.animateTo(1f, tween(760, easing = FastOutSlowInEasing))
        }
    }
    drawWithContent {
        drawContent()
        val v = p.value
        if (v < 0.999f) {
            val band = size.width * 0.32f
            val x = -band + (size.width + band * 2) * v
            drawRect(
                Brush.linearGradient(
                    listOf(Color.Transparent, color.copy(alpha = strength), Color.Transparent),
                    start = Offset(x - band, 0f), end = Offset(x + band, size.height),
                ),
            )
        }
    }
}

/** A light arc that orbits the border of the selected item (fades in/out with [active]). */
fun Modifier.orbitBorder(active: Boolean, color: Color, corner: Dp, width: Dp = 1.5.dp): Modifier = composed {
    val a by animateFloatAsState(if (active) 1f else 0f, tween(450))
    val reduce = LocalReduceMotion.current
    val phase = if (active && !reduce) {
        ambientFloat(0f, 1f, 3400, LinearEasing).value
    } else 0.125f
    drawWithContent {
        drawContent()
        if (a > 0.01f) {
            val n = 36
            val stops = Array(n + 1) { i ->
                val x = i.toFloat() / n
                val d = abs(x - phase).let { min(it, 1f - it) }
                x to color.copy(alpha = a * (1f - d / 0.17f).coerceIn(0f, 1f))
            }
            val sw = width.toPx()
            drawRoundRect(
                Brush.sweepGradient(*stops, center = center),
                topLeft = Offset(sw / 2, sw / 2), size = Size(size.width - sw, size.height - sw),
                cornerRadius = CornerRadius(corner.toPx() - sw / 2), style = Stroke(sw),
            )
        }
    }
}

@Composable
fun Hairline(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize().background(Ghost.colors.line))
}


/**
 * Heavy black shadow around [shape] that never shows through translucent content: the shadow is drawn with
 * the platform's blurred DropShadowPainter and the shape itself is cut out of it (ClipOp.Difference).
 * Three layers: a dense rim at the edge, a soft mid shadow, a wide heavy tail below.
 */
fun Modifier.outerShadow(shape: Shape, weight: Float = 1f): Modifier = drawBehind {
    // Blur-free soft shadow: each layer is the shape grown step by step with a falling alpha, so the sum
    // fades smoothly like a gaussian. Pure geometry, no cached blur bitmaps: stable on every device
    // (the blurred painter flickered on Android while it re-rendered its cache).
    val cut = androidx.compose.ui.graphics.Path().apply { addOutline(shape.createOutline(size, layoutDirection, this@drawBehind)) }
    clipPath(cut, androidx.compose.ui.graphics.ClipOp.Difference) {
        softLayer(shape, radius = 10.dp.toPx(), dy = 2.dp.toPx(), alpha = 0.5f * weight)
        softLayer(shape, radius = 22.dp.toPx(), dy = 8.dp.toPx(), alpha = 0.45f * weight)
        softLayer(shape, radius = 44.dp.toPx(), dy = 18.dp.toPx(), alpha = 0.5f * weight)
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.softLayer(shape: Shape, radius: Float, dy: Float, alpha: Float) {
    val steps = 14
    for (i in steps downTo 1) {
        val k = i.toFloat() / steps
        val grow = radius * k
        // bell-ish falloff: dense near the edge, a long faint tail
        val a = (alpha.coerceAtMost(0.95f) * (1f - k) * (1f - k) * 2.2f / steps).coerceIn(0f, 1f)
        if (a <= 0.001f) continue
        val outline = shape.createOutline(Size(size.width + grow * 2, size.height + grow * 2), layoutDirection, this)
        translate(-grow, -grow + dy * k) {
            drawOutline(outline, Color.Black.copy(alpha = a))
        }
    }
}


/**
 * Fade of an AnimatedContent page drawn with ModulateAlpha. The built-in fadeIn/fadeOut render the page
 * into an offscreen buffer clipped to its bounds, which cuts the cards' shadows at the page edge during the
 * transition and makes them "blink" when it ends. Pair with transitions that have no fade of their own.
 */
@Composable
fun Modifier.pageFade(scope: androidx.compose.animation.AnimatedVisibilityScope, inMs: Int, outMs: Int): Modifier {
    val a by scope.transition.animateFloat(
        transitionSpec = {
            if (targetState == androidx.compose.animation.EnterExitState.Visible) tween(inMs) else tween(outMs)
        },
        label = "pageFade",
    ) { if (it == androidx.compose.animation.EnterExitState.Visible) 1f else 0f }
    return graphicsLayer { compositingStrategy = androidx.compose.ui.graphics.CompositingStrategy.ModulateAlpha; alpha = a }
}
