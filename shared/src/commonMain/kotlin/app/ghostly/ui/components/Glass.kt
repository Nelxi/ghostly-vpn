package app.ghostly.ui.components

import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.addOutline
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithCache
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
 * The app's card: a plate over the night. Translucent dark ink (the scene shows through a little), a hairline
 * edge, a top edge that catches the light of the sky (violet once protected, the colour fading in with the
 * scene), one soft shadow tinted like the night, and a cursor spotlight on desktop.
 * [strong]: a slightly denser plate for the one that matters most on a screen.
 */
@Composable
fun GlassCard(
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(Radius.card),
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
    if (onClick != null) m = m.pressScale(interaction, 0.98f, hover = 1.006f)
    m = m.softShadow(shape, if (strong) 0.9f else 0.7f).plate(shape, strong) { lit }
    if (glow != null) m = m.background(Brush.radialGradient(listOf(glow.copy(alpha = 0.13f), Color.Transparent)))
    val stageForCard = app.ghostly.ui.stage.LocalStage.current
    if (stageForCard != null) m = m.drawWithContent {
        drawContent()
        val a = stageForCard.a
        val k = a.beat * 0.35f * (1f - a.mood * 0.6f)
        if (k > 0.02f) drawRect(Brush.verticalGradient(listOf(c.accent.copy(alpha = 0.22f * k), Color.Transparent), endY = size.height * 0.5f))
    }
    m = m.spotlight(c.accent)
    m = if (onClick != null) m.hoverSound().clickable(interactionSource = interaction, indication = null, onClick = onClick)
    else m.hoverable(interaction) // plain cards light up under the cursor too, like the site
    Column(m.padding(padding), content = content)
}

/**
 * A plate over the night (cards, the dock, the sidebar, pills): clipped to [shape], translucent ink, the
 * lit top edge and a hairline border. [lit] 0..1 (hover) warms the border towards the accent.
 */
fun Modifier.plate(shape: Shape, strong: Boolean = false, lit: () -> Float = { 0f }): Modifier = composed {
    val c = Ghost.colors
    val fill = if (strong) c.plate.copy(alpha = (c.plate.alpha + 0.05f).coerceAtMost(1f)) else c.plate
    // From the theme (switched once on connect), not the scene's animated energy: reading that here made
    // every plate redraw on every frame of the 2-second colour spread.
    val e = c.lit
    clip(shape)
        .drawBehind {
            drawRect(fill)
            // Sky light on the plate: a faint wash from the top, and the edge that catches it.
            if (e > 0.01f) drawRect(Brush.verticalGradient(listOf(c.accent.copy(alpha = 0.045f * e), Color.Transparent), endY = 90.dp.toPx()))
        }
        .border(
            1.dp,
            Brush.verticalGradient(
                listOf(
                    lerp(Color.White.copy(alpha = 0.06f), c.accent.copy(alpha = 0.5f), lit()),
                    Color.White.copy(alpha = 0.06f + 0.03f * lit()),
                ),
            ),
            shape,
        )
}

private val PLATE_EDGE_GREY = Color(0x12FFFFFF)
private val PLATE_EDGE_LIT = Color(0x26C8B4FF)

/** A soft coloured glow under a lit control (the accent button), cut out of the shape like [softShadow]. */
fun Modifier.glowShadow(shape: Shape, color: Color, weight: Float = 1f): Modifier = drawBehind {
    if (weight <= 0.001f) return@drawBehind
    val cut = androidx.compose.ui.graphics.Path().apply { addOutline(shape.createOutline(size, layoutDirection, this@drawBehind)) }
    clipPath(cut, androidx.compose.ui.graphics.ClipOp.Difference) {
        softLayer(shape, radius = 22.dp.toPx(), dy = 10.dp.toPx(), alpha = 0.55f * weight, tint = color)
    }
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
/** Server-tunable shadow look (design.json `shadow` / `shadowSoft`); snapshot state, so a change redraws. */
object ShadowLook {
    var strength by androidx.compose.runtime.mutableFloatStateOf(app.ghostly.core.design.DesignTokens().shadow)
    var spread by androidx.compose.runtime.mutableFloatStateOf(app.ghostly.core.design.DesignTokens().shadowSoft)
}

fun Modifier.outerShadow(shape: Shape, weight0: Float = 1f): Modifier = drawBehind {
    val weight = weight0 * ShadowLook.strength
    val s = ShadowLook.spread
    if (weight <= 0.001f) return@drawBehind
    // Blur-free soft shadow: each layer is the shape grown step by step with a falling alpha, so the sum
    // fades smoothly like a gaussian. Pure geometry, no cached blur bitmaps: stable on every device
    // (the blurred painter flickered on Android while it re-rendered its cache).
    val cut = androidx.compose.ui.graphics.Path().apply { addOutline(shape.createOutline(size, layoutDirection, this@drawBehind)) }
    clipPath(cut, androidx.compose.ui.graphics.ClipOp.Difference) {
        softLayer(shape, radius = 10.dp.toPx() * s, dy = 2.dp.toPx() * s, alpha = 0.5f * weight)
        softLayer(shape, radius = 22.dp.toPx() * s, dy = 8.dp.toPx() * s, alpha = 0.45f * weight)
        softLayer(shape, radius = 44.dp.toPx() * s, dy = 18.dp.toPx() * s, alpha = 0.5f * weight)
    }
}

/**
 * One soft shadow under [shape], tinted like the night it falls on (never pure black): a short dense rim and
 * a gentle tail. Cut out of the shape like [outerShadow], so translucent content never shows it through.
 * Scaled by the server-tunable [ShadowLook] like the old heavy one.
 */
fun Modifier.softShadow(shape: Shape, weight0: Float = 1f): Modifier = drawWithCache {
    val weight = weight0 * ShadowLook.strength.coerceAtMost(1.2f)
    val s = ShadowLook.spread
    // Built once per size (and look): the grown outlines are geometry, replayed on every draw.
    val cut = androidx.compose.ui.graphics.Path().apply { addOutline(shape.createOutline(size, layoutDirection, this@drawWithCache)) }
    val layers = if (weight <= 0.001f) emptyList() else
        shadowRings(shape, 6.dp.toPx() * s, 2.dp.toPx() * s, 0.35f * weight) + shadowRings(shape, 26.dp.toPx() * s, 12.dp.toPx() * s, 0.32f * weight)
    onDrawBehind {
        if (layers.isEmpty()) return@onDrawBehind
        clipPath(cut, androidx.compose.ui.graphics.ClipOp.Difference) {
            layers.forEach { (outline, offset, color) -> translate(offset.x, offset.y) { drawOutline(outline, color) } }
        }
    }
}

/** The rings of one soft layer: each the shape grown a step further with a falling alpha. */
private fun androidx.compose.ui.draw.CacheDrawScope.shadowRings(shape: Shape, radius: Float, dy: Float, alpha: Float): List<Triple<androidx.compose.ui.graphics.Outline, Offset, Color>> {
    // Few rings: the night behind the interface changes every frame, so the GPU redraws every shadow every
    // frame too; 28 stacked rings per plate cost a phone a third of its frame time. On the dark scene 4 per
    // layer look the same.
    val steps = 4
    return (steps downTo 1).mapNotNull { i ->
        val k = i.toFloat() / steps
        val grow = radius * k
        val a = (alpha.coerceAtMost(0.95f) * (1f - k) * (1f - k) * 2.2f / steps).coerceIn(0f, 1f)
        if (a <= 0.001f) null
        else Triple(shape.createOutline(Size(size.width + grow * 2, size.height + grow * 2), layoutDirection, this), Offset(-grow, -grow + dy * k), SHADOW_TINT.copy(alpha = a))
    }
}

private val SHADOW_TINT = Color(0xFF030207)

private fun androidx.compose.ui.graphics.drawscope.DrawScope.softLayer(shape: Shape, radius: Float, dy: Float, alpha: Float, tint: Color = Color.Black) {
    val steps = 14
    for (i in steps downTo 1) {
        val k = i.toFloat() / steps
        val grow = radius * k
        // bell-ish falloff: dense near the edge, a long faint tail
        val a = (alpha.coerceAtMost(0.95f) * (1f - k) * (1f - k) * 2.2f / steps).coerceIn(0f, 1f)
        if (a <= 0.001f) continue
        val outline = shape.createOutline(Size(size.width + grow * 2, size.height + grow * 2), layoutDirection, this)
        translate(-grow, -grow + dy * k) {
            drawOutline(outline, tint.copy(alpha = a))
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
