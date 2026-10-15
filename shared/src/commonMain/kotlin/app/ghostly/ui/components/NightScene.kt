package app.ghostly.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.findRootCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import app.ghostly.ui.theme.Ghost
import app.ghostly.ui.theme.LocalDesign
import app.ghostly.ui.theme.LocalReduceMotion
import app.ghostly.ui.theme.Motion
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.tan
import kotlin.random.Random

/*
 * «Ночь и свет»: the whole app is one night over the sea, filmed by one camera, and colour means protection.
 * While the VPN is off the world is grey: a cold moon shooting rays, its path on the water, the ghost asleep
 * over its reflection. When the tunnel comes up colour spreads out of the ghost: an aurora lights the sky,
 * fireflies wake up, and the water mirrors it all.
 *
 * Every page is a shot of that same world. [nightSky] says where the page wants the horizon and how the
 * camera stands (turned by `yaw`, pulled back by `zoom`); [nightGhost] gives the ghost its box. Both are kept
 * per page key, so a page that is leaving, or a copy drawn beneath during predictive back, can't take them
 * over. On a page change the camera moves: the ghost flies to its new place, the horizon slides, and the far
 * things (stars, moon, aurora, islands) pan and shrink with depth.
 *
 * Nothing is blurred at draw time. The aurora is painted at a few pixels per strip into a small offscreen
 * picture about 30 times a second and stretched with bilinear filtering (soft for free; the same picture,
 * flipped, is its reflection); crisp rays, stars and glows are thin shapes and radial falloffs with cached
 * gradients. The scene draws on its own layer, so its ticking never redraws the interface above it.
 */

/** What the scene draws, and where; one per app ([LocalNight]). */
@Stable
class NightState {
    /** The page on screen (a tab, the first screen…): whose anchors the camera follows. */
    var page: Any by mutableStateOf(Unit)

    // Plain maps on purpose: pages report their anchors on every scroll frame (up to the monitor's 300 Hz),
    // and a snapshot map would redraw the whole night that often. The scene reads them on its own ~60 Hz
    // ambient tick instead.
    internal val ghosts = HashMap<Any, Rect>()
    internal val skies = HashMap<Any, SkyAnchor>()
    internal val rig = GhostRig()

    /** Last pointer position in root pixels while it is over the window (a mouse, or a finger that is down). */
    // Plain too: the mouse moves at the monitor's rate; the eyes catch up on the scene's own tick.
    internal var pointer: Offset? = null
    internal var mouse = false

    internal var energySource: State<Float> = mutableFloatStateOf(0f)

    /** 0 = the grey night, 1 = full colour. Animated: read it while drawing, not in composition. */
    val energy: Float get() = energySource.value
}

/** A page's shot: where the horizon is (root px) and how the camera stands. */
internal class SkyAnchor(val horizon: Float, val yaw: Float, val zoom: Float)

val LocalNight = staticCompositionLocalOf { NightState() }

/** The ghost's moods that pages and taps drive; the scene reads them while drawing. */
@Stable
internal class GhostRig {
    val squish = Animatable(0f)
    val waveR = Animatable(0f)
    val waveL = Animatable(0f)
    var expr by mutableStateOf(GhostExpr.NONE)
    var hover by mutableStateOf(false)
    var pressed by mutableStateOf(false)

    /** One hand (or both) up for a wave. */
    fun greet(scope: CoroutineScope, both: Boolean, holdMs: Long) {
        scope.launch {
            waveR.animateTo(1f, spring(dampingRatio = 0.55f, stiffness = 220f))
            delay(holdMs)
            waveR.animateTo(0f, tween(450, easing = Motion.Ease))
        }
        if (both) scope.launch {
            waveL.animateTo(1f, spring(dampingRatio = 0.55f, stiffness = 200f))
            delay(holdMs)
            waveL.animateTo(0f, tween(450, easing = Motion.Ease))
        }
    }

    /** A tap: the ghost squishes, waves and pulls a face for a moment. */
    fun poke(scope: CoroutineScope, reduce: Boolean) {
        if (!reduce) greet(scope, both = false, holdMs = 900)
        expr = listOf(GhostExpr.GIGGLE, GhostExpr.WINK, GhostExpr.SURPRISED).random()
        scope.launch {
            squish.snapTo(1f)
            squish.animateTo(0f, spring(dampingRatio = 0.3f, stiffness = 300f))
        }
        scope.launch {
            delay(900)
            expr = GhostExpr.NONE
        }
    }
}

private fun LayoutCoordinates.rootRect(): Rect = findRootCoordinates().localBoundingBoxOf(this, clipBounds = false)

/**
 * Marks the part of a page above the horizon: its bottom edge is where the sky meets the water.
 * [yaw] turns the camera (in screen widths, far things pan by it), [zoom] < 1 pulls it back.
 */
fun Modifier.nightSky(key: Any, yaw: Float = 0f, zoom: Float = 1f): Modifier = composed {
    val night = LocalNight.current
    onGloballyPositioned {
        val bottom = it.rootRect().bottom
        val old = night.skies[key]
        if (old == null || old.horizon != bottom || old.yaw != yaw || old.zoom != zoom) night.skies[key] = SkyAnchor(bottom, yaw, zoom)
    }
}

/**
 * The ghost's box on a page: the scene draws the ghost here. [onTap] (spoken as [label]) makes it a button;
 * a tap always makes it giggle.
 */
fun Modifier.nightGhost(key: Any, label: String? = null, onTap: (() -> Unit)? = null): Modifier = composed {
    val night = LocalNight.current
    val reduce = LocalReduceMotion.current
    val scope = rememberCoroutineScope()
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val pressed by interaction.collectIsPressedAsState()
    val mine = night.page == key
    LaunchedEffect(hovered, pressed, mine) {
        if (mine) {
            night.rig.hover = hovered
            night.rig.pressed = pressed
        }
    }
    this
        .onGloballyPositioned {
            val r = it.rootRect()
            if (night.ghosts[key] != r) night.ghosts[key] = r
        }
        .semantics {
            if (label != null) {
                contentDescription = label
                role = Role.Button
            }
        }
        .pointerHoverIcon(PointerIcon.Hand)
        .hoverSound()
        .clickable(interaction, null) {
            onTap?.invoke()
            night.rig.poke(scope, reduce)
        }
}

/**
 * Grey while unprotected, full colour once the tunnel is up (flags and other pictures on the home screen).
 * [floor] keeps some colour in the grey night.
 */
fun Modifier.nightColor(floor: Float = 0f): Modifier = composed {
    val night = LocalNight.current
    val paint = remember { Paint() }
    val matrix = remember { ColorMatrix() }
    drawWithContent {
        val sat = (floor + (1f - floor) * night.energy).coerceIn(0f, 1f)
        if (sat >= 0.995f) {
            drawContent()
        } else {
            matrix.setToSaturation(sat)
            paint.colorFilter = ColorFilter.colorMatrix(matrix)
            drawContext.canvas.saveLayer(Rect(Offset.Zero, size), paint)
            drawContent()
            drawContext.canvas.restore()
        }
    }
}

// ============================================================================ palette

/** Hue of [c] in degrees, or [fallback] for a grey. */
private fun hueOf(c: Color, fallback: Float): Float {
    val mx = max(c.red, max(c.green, c.blue))
    val mn = min(c.red, min(c.green, c.blue))
    val d = mx - mn
    if (d < 0.04f) return fallback
    val h = when (mx) {
        c.red -> 60f * (((c.green - c.blue) / d) % 6f)
        c.green -> 60f * ((c.blue - c.red) / d + 2f)
        else -> 60f * ((c.red - c.green) / d + 4f)
    }
    return (h + 360f) % 360f
}

private fun hsv(h: Float, s: Float, v: Float, a: Float = 1f): Color =
    Color.hsv(((h % 360f) + 360f) % 360f, s.coerceIn(0f, 1f), v.coerceIn(0f, 1f), a.coerceIn(0f, 1f))

/** [from] towards [to] by [t] along the shorter way round the colour wheel. */
private fun lerpHue(from: Float, to: Float, t: Float): Float {
    var d = (to - from) % 360f
    if (d > 180f) d -= 360f
    if (d < -180f) d += 360f
    return from + d * t
}

/**
 * The colours of the protected night, all from the user's accent: the sky leans to it from deep navy, the
 * aurora is the accent with a warmer and a colder curtain beside it (violet → magenta and cyan by default,
 * mint → cyan and green, rose → orange and purple).
 */
private class NightPalette(accent: Color) {
    val hue = hueOf(accent, 254f)
    private val skyHue = lerpHue(244f, hue, 0.35f)
    val skyTop = hsv(skyHue, 0.77f, 0.10f)
    val skyMid = hsv(skyHue, 0.73f, 0.17f)
    val skyHorizon = hsv(skyHue + 3f, 0.70f, 0.26f)
    val waterTop = hsv(skyHue + 2f, 0.72f, 0.125f)
    val waterBottom = hsv(skyHue + 2f, 0.75f, 0.03f)
    val island = hsv(skyHue, 0.62f, 0.085f)

    /** Light of the horizon line, ripples, rays and fireflies. */
    val light = hsv(hue, 0.20f, 1f)
    val halo = hsv(hue, 0.47f, 1f)
    val ghostTone = hsv(hue, 0.38f, 1f)

    // Aurora curtains, bottom-lit: a soft fade below the lower edge, the bright edge, mid colour, a faint top.
    private fun curtain(dh0: Float, s0: Float, dh1: Float, s1: Float, dh2: Float, s2: Float, v2: Float, peak: Float) =
        Brush.verticalGradient(
            0f to hsv(hue + dh2, s2, v2, 0f),
            0.40f to hsv(hue + dh2, s2, v2, 0.16f * peak),
            0.70f to hsv(hue + dh1, s1, 1f, 0.50f * peak),
            0.925f to hsv(hue + dh0, s0, 1f, 0.86f * peak),
            1f to hsv(hue + dh0, s0, 1f, 0f),
            startY = 0f, endY = 1f,
        )
    val main = curtain(0f, 0.35f, 0f, 0.60f, -5f, 0.75f, 0.94f, 1f)
    val warm = curtain(38f, 0.39f, 22f, 0.64f, 18f, 0.76f, 1f, 0.9f)
    val cold = curtain(-58f, 0.44f, -45f, 0.71f, -29f, 0.77f, 1f, 0.82f)
}

// The grey night.
private val OFF_SKY_TOP = Color(0xFF08090C)
private val OFF_SKY_MID = Color(0xFF0E1015)
private val OFF_SKY_HORIZON = Color(0xFF171A21)
private val OFF_WATER_TOP = Color(0xFF0A0B0E)
private val OFF_WATER_BOTTOM = Color(0xFF040406)
private val OFF_ISLAND = Color(0xFF0B0C10)
private val OFF_LIGHT = Color(0xFFC3C9D6)
private val OFF_HALO = Color(0xFFC8CCD8)
private val MOON = Color(0xFFD9DFEB)
private val MOONLIGHT = Color(0xFFDFE5F2)
private val OFF_GHOST = Color(0xFFB3B8C4)

/**
 * The camera's travel between shots: it eases out of one station, flies, and settles into the next. Pages
 * ride the same curve over the same time (see `travel` in App.kt), so the interface moves with the world.
 */
val TRAVEL = CubicBezierEasing(0.45f, 0f, 0.2f, 1f)
const val TRAVEL_MS = 950

// ============================================================================ the world

/**
 * One aurora curtain in world space: x in screen widths at yaw 0, heights as parts of the sky's height.
 * [palette] picks its colours.
 */
private class Curtain(
    val x0: Float, val x1: Float, val lift: Float, val height: Float, val amp: Float,
    val seed: Float, val alpha: Float, val rays: Boolean, val pick: (NightPalette) -> Brush,
) {
    /** Lower edge: a slow double wave travelling along the curtain (−1..1). */
    fun edge(xw: Float, ph1: Float, ph2: Float): Float =
        sin(TAU * (xw * 1.3f + seed + ph1)) * 0.6f + sin(TAU * (xw * 2.7f - ph2) + seed * 7f) * 0.4f

    /** 0 at the curtain's ends, 1 inside. */
    fun envelope(xw: Float): Float {
        val f = (xw - x0) / (x1 - x0)
        return smooth(f / 0.2f) * smooth((1f - f) / 0.2f)
    }
}

// Back to front. Wide enough for every page's yaw (the camera turns up to about half a screen).
private val CURTAINS = listOf(
    Curtain(-0.15f, 0.52f, 0.33f, 0.64f, 0.05f, 0.17f, 0.62f, false) { it.cold },
    Curtain(1.30f, 2.05f, 0.31f, 0.60f, 0.05f, 0.29f, 0.60f, false) { it.cold },
    Curtain(0.36f, 1.12f, 0.34f, 0.62f, 0.06f, 0.61f, 0.80f, false) { it.warm },
    Curtain(-0.75f, -0.02f, 0.32f, 0.60f, 0.06f, 0.47f, 0.70f, false) { it.warm },
    Curtain(-0.06f, 1.06f, 0.22f, 0.74f, 0.07f, 0.38f, 0.95f, true) { it.main },
    Curtain(0.98f, 1.95f, 0.25f, 0.70f, 0.07f, 0.83f, 0.85f, true) { it.main },
    Curtain(1.95f, 2.75f, 0.33f, 0.62f, 0.06f, 0.11f, 0.78f, false) { it.warm },
    Curtain(2.45f, 3.20f, 0.31f, 0.64f, 0.05f, 0.71f, 0.62f, false) { it.cold },
    Curtain(1.85f, 3.05f, 0.23f, 0.72f, 0.07f, 0.53f, 0.92f, true) { it.main },
)

/** How far things slide as the camera moves sideways (`yaw`): the farther, the less. */
private const val DEPTH_STARS = 0.22f
private const val DEPTH_MOON = 0.3f
private const val DEPTH_AURORA = 0.5f
private const val DEPTH_FAR_ISLES = 0.78f
private const val DEPTH_NEAR_ISLES = 1f

/** Stars on a tile 1.7 screens wide (it repeats as the camera turns); y is height above the horizon. */
private class Star(val x: Float, val y: Float, val r: Float, val tw: Float, val speed: Int, val glint: Boolean)

private const val STAR_TILE = 1.7f
private val STARS: List<Star> = Random(11).let { r ->
    List(78) { i -> Star(r.nextFloat() * STAR_TILE, 0.05f + r.nextFloat() * 0.97f, 0.5f + r.nextFloat() * 0.85f, r.nextFloat(), 2 + r.nextInt(3), i % 19 == 4) }
}

/** Crisp rays along the front curtains: world x, width (dp), height share, phase. */
private class Ray(val curtain: Curtain, val x: Float, val width: Float, val tall: Float, val ph: Float)

private val RAYS: List<Ray> = Random(5).let { r ->
    CURTAINS.filter { it.rays }.flatMap { cu ->
        val n = ((cu.x1 - cu.x0) * 22f).toInt()
        List(n) { j -> Ray(cu, cu.x0 + (j + 0.25f + r.nextFloat() * 0.5f) / n * (cu.x1 - cu.x0), 1f + r.nextFloat() * 1.4f, 0.42f + r.nextFloat() * 0.3f, r.nextFloat()) }
    }
}

/** Islands on the horizon: world x of the centre, half width (screen widths), height (sky heights). */
private val FAR_ISLES = floatArrayOf(
    0.16f, 0.11f, 0.020f, 1.02f, 0.17f, 0.030f, 1.55f, 0.14f, 0.024f, -0.42f, 0.16f, 0.026f,
    2.05f, 0.15f, 0.028f, 2.62f, 0.12f, 0.022f, 3.2f, 0.18f, 0.03f,
)
private val NEAR_ISLES = floatArrayOf(
    -0.04f, 0.15f, 0.036f, 0.9f, 0.09f, 0.022f, 1.32f, 0.19f, 0.042f, 1.9f, 0.12f, 0.03f,
    2.4f, 0.16f, 0.038f, 2.95f, 0.1f, 0.026f, 3.45f, 0.2f, 0.044f, 4.0f, 0.13f, 0.032f,
)

/** Fireflies around the ghost, in ghost sizes from its centre. */
private val FIREFLIES = floatArrayOf(
    -0.35f, -0.16f, 0.46f, -0.26f, 0.57f, 0.22f, -0.47f, 0.33f, 0.24f, -0.46f,
    -0.16f, 0.59f, 0.71f, -0.04f, -0.59f, 0.03f, 0.37f, 0.67f, -0.08f, -0.62f,
)

/** The moon's rays: angle (deg), length and width factors. */
private val MOON_RAYS: List<FloatArray> = Random(23).let { r ->
    List(11) { i -> floatArrayOf(i * 360f / 11f + r.nextFloat() * 14f, 0.55f + r.nextFloat() * 0.6f, 0.6f + r.nextFloat() * 0.8f, r.nextFloat()) }
}

private fun smooth(x: Float): Float {
    val t = x.coerceIn(0f, 1f)
    return t * t * (3f - 2f * t)
}

/** A unit wedge (apex at the origin, opening along +x) and unit falloffs: drawn scaled, their shaders stay cached. */
private val WEDGE = Path().apply { moveTo(0f, 0f); lineTo(1f, -1f); lineTo(1f, 1f); close() }
private val RAY_FADE = Brush.verticalGradient(0f to Color.Transparent, 0.82f to Color.White.copy(alpha = 0.55f), 1f to Color.Transparent, startY = 0f, endY = 1f)
private val DOT_GLOW = Brush.radialGradient(listOf(Color.White.copy(alpha = 0.55f), Color.Transparent), Offset.Zero, 1f)

/** What the camera drew last frame and where a move started. Plain fields: written while drawing. */
private class Camera {
    var ghost: Rect? = null
    var horizon = 0f
    var yaw = 0f
    var zoom = 1f
    var fromGhost: Rect? = null
    var fromHorizon = 0f
    var fromYaw = 0f
    var fromZoom = 1f
    var look = Offset.Zero
    var lastMs = 0L
    /** Which way the current move goes: 1 to a later page, -1 back. */
    var heading = 1f
    /** The page last drawn, and whether its move has been captured but not started yet. */
    var drawnPage: Any? = null
    var pending = false
}

/** The aurora's small offscreen picture. */
private class AuroraBuffer {
    var image: ImageBitmap? = null
    var canvas: Canvas? = null
    val scope = CanvasDrawScope()
    var ms = Long.MIN_VALUE
    var yaw = 0f
    var palette: NightPalette? = null
}

// ============================================================================ the scene

/**
 * The night behind every page. [orb] is the tunnel's state, [page] the page on screen; pages set up their
 * shot with [nightSky] / [nightGhost] under that key.
 */
@Composable
fun NightScene(night: NightState, orb: OrbState, page: Any, modifier: Modifier = Modifier, content: @Composable BoxScope.() -> Unit) {
    val c = Ghost.colors
    val reduce = LocalReduceMotion.current
    val design = LocalDesign.current
    val stage = app.ghostly.ui.stage.LocalStage.current
    val time = LocalAmbientTime.current
    val palette = remember(c.accent) { NightPalette(c.accent) }
    val rig = night.rig
    val scope = rememberCoroutineScope()
    androidx.compose.runtime.SideEffect { night.page = page }

    val connected = orb == OrbState.CONNECTED
    // Colour comes slowly (it spreads out of the ghost) and drains back into it a little faster.
    val energy = animateFloatAsState(if (connected) 1f else 0f, tween(if (connected) 2000 else 1300, easing = Motion.EaseInOut))
    night.energySource = energy
    val busy = animateFloatAsState(if (orb == OrbState.CONNECTING) 1f else 0f, Motion.quick(500))
    val err = animateFloatAsState(if (orb == OrbState.ERROR) 1f else 0f, Motion.quick(500))
    val hoverA = animateFloatAsState(if (rig.hover) 1f else 0f, Motion.quick(300))
    val press = animateFloatAsState(if (rig.pressed) 0.93f else 1f, Motion.bouncy())
    LaunchedEffect(rig.hover) { if (rig.hover && !reduce) rig.greet(scope, both = false, holdMs = 1200) }
    LaunchedEffect(orb) { if (connected && !reduce) rig.greet(scope, both = true, holdMs = 1600) }

    // Stage mode: shades and a microphone while music plays on the PC; a new track takes the outfit off for a
    // moment (design.json outfitReplayMs) so its entrance is seen again.
    val musicOn by remember(stage) { derivedStateOf { stage?.track?.value?.playing == true } }
    val song by remember(stage) { derivedStateOf { stage?.track?.value?.let { it.artist + "|" + it.title } ?: "" } }
    val replayMs = if (reduce) 0 else design.outfitReplayMs
    var outfitOff by remember { mutableStateOf(false) }
    var lastSong by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(song) {
        val previous = lastSong
        lastSong = song
        if (previous.isNullOrEmpty() || song.isEmpty() || previous == song || replayMs <= 0 || !musicOn) return@LaunchedEffect
        try {
            outfitOff = true
            delay(replayMs.toLong())
        } finally {
            outfitOff = false
        }
    }
    val showtime = animateFloatAsState(
        if (musicOn && !outfitOff) 1f else 0f,
        if (outfitOff) tween(220, easing = Motion.Ease) else spring(dampingRatio = 0.5f, stiffness = 90f),
    )

    // A new page: the camera moves from the shot it drew last to the new one.
    val cam = remember { Camera() }
    val move = remember { StepClock(1f) }
    var lastPage by remember { mutableStateOf(page) }
    LaunchedEffect(page) {
        val before = lastPage
        lastPage = page
        cam.heading = if (page is Enum<*> && before is Enum<*> && page.ordinal < before.ordinal) -1f else 1f
        if (cam.ghost == null) return@LaunchedEffect
        // The start of the move was captured by the frame that first drew the new page (see `pending`). Wait
        // that frame out like the pages do (rememberPageMove): the camera and the interface start together.
        androidx.compose.runtime.withFrameNanos { }
        move.set(0f)
        cam.pending = false
        move.run(if (reduce) 220 else TRAVEL_MS)
    }

    var origin by remember { mutableStateOf(Offset.Zero) }
    val buffer = remember { AuroraBuffer() }
    // The ghost and its reflection are dozens of fresh curves a frame, re-tessellated by the GPU each time:
    // on a phone that was more than half of every frame. They are recorded into layers ~30 times a second
    // and those are replayed in between; floating, leaning and pressing move the layer at full rate.
    val ghostLayer = androidx.compose.ui.graphics.rememberGraphicsLayer()
    val mirrorLayer = androidx.compose.ui.graphics.rememberGraphicsLayer()
    val ghostRec = remember { LongArray(2) { Long.MIN_VALUE } } // [time, size]
    val isles = remember { arrayOf(Path(), Path(), Path(), Path()) }

    Box(
        modifier.fillMaxSize().background(OFF_WATER_BOTTOM)
            .onGloballyPositioned { origin = it.positionInRoot() }
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        val ev = awaitPointerEvent(PointerEventPass.Initial)
                        val ch = ev.changes.firstOrNull() ?: continue
                        val isMouse = ch.type == PointerType.Mouse
                        night.mouse = isMouse
                        night.pointer = when {
                            ev.type == PointerEventType.Exit -> null
                            !isMouse && !ch.pressed -> null
                            else -> ch.position + origin
                        }
                    }
                }
            },
    ) {
        // Its own layer: the scene ticks every frame, the interface above it is not redrawn for that.
        Canvas(
            Modifier.fillMaxSize().graphicsLayer {
                val p = if (cam.pending) 0f else move.value
                if (p >= 1f || reduce) return@graphicsLayer
                // The night takes part in the page change like Kasane's menu background: it drifts a
                // touch in depth and softens out of focus at the cut, then comes back sharp.
                val k = TRAVEL.transform(p)
                val bump = sin(PI.toFloat() * k)
                val z = 1f + cam.heading * 0.025f * bump
                scaleX = z; scaleY = z
                val blur = 5.dp.toPx() * bump * (1f - k)
                renderEffect = if (blur > 0.5f) androidx.compose.ui.graphics.BlurEffect(blur, blur, androidx.compose.ui.graphics.TileMode.Clamp) else null
            },
        ) {
            val now = time.value
            val w = size.width
            val h = size.height
            val e = energy.value
            val b = busy.value
            val er = err.value
            val motion = if (reduce) 0.3f else 1f
            val slow = if (reduce) 4L else 1L
            // Seamless loops from the ambient clock (Long modulo keeps them exact after days of uptime).
            fun loop(ms: Long): Float = (now % (ms * slow)).toFloat() / (ms * slow)
            fun wave(ms: Long, shift: Float = 0f): Float = sin(TAU * (loop(ms) + shift))

            // ---- the shot (moving between pages)
            // The page changed in this very frame, before the move's clock was reset: hold the shot where it
            // was instead of jumping to the new one for a frame (that was the blink).
            if (cam.drawnPage != page) {
                if (cam.drawnPage != null && cam.ghost != null) {
                    cam.fromGhost = cam.ghost
                    cam.fromHorizon = cam.horizon
                    cam.fromYaw = cam.yaw
                    cam.fromZoom = cam.zoom
                    cam.pending = true
                }
                cam.drawnPage = page
            }
            val p = if (cam.pending) 0f else move.value
            val k = TRAVEL.transform(p)
            val moving = p < 1f
            val skyT = night.skies[page]
            val ghostT = night.ghosts[page]?.translate(-origin)
            val defGhost = Rect(Offset(w / 2f, h * 0.3f), min(w * 0.5f, 220.dp.toPx()) / 2f)
            val tGhost = ghostT ?: cam.ghost ?: defGhost
            val tHorizon = skyT?.let { it.horizon - origin.y } ?: if (cam.ghost == null) h * 0.53f else cam.horizon
            val tYaw = skyT?.yaw ?: cam.yaw
            val tZoom = skyT?.zoom ?: cam.zoom
            val ghostR = if (moving && cam.fromGhost != null) androidx.compose.ui.geometry.lerp(cam.fromGhost!!, tGhost, k) else tGhost
            // Mid-flight the camera lifts and stands back: the horizon rises a little, the world shrinks a little.
            val lift = if (moving) sin(PI.toFloat() * k) * motion else 0f
            val hz = ((if (moving) cam.fromHorizon + (tHorizon - cam.fromHorizon) * k else tHorizon) - lift * 22.dp.toPx()).coerceIn(-h, h)
            val yaw = if (moving) cam.fromYaw + (tYaw - cam.fromYaw) * k else tYaw
            val zoom = ((if (moving) cam.fromZoom + (tZoom - cam.fromZoom) * k else tZoom) * (1f - 0.1f * lift)).coerceIn(0.5f, 1.5f)
            // Which way the world slides: the ghost leans into it.
            val heading = if (moving) (if (tYaw >= cam.fromYaw) 1f else -1f) else 0f
            cam.ghost = ghostR
            cam.horizon = hz
            cam.yaw = yaw
            cam.zoom = zoom
            // The ghost arcs up a little on the way, like it is carried by the camera move.
            val arc = if (moving) -sin(PI.toFloat() * k) * 30.dp.toPx() * motion else 0f
            val gs = ghostR.width
            val gx = ghostR.center.x
            val gy = ghostR.center.y + arc
            val skyH = max(h * 0.55f, 300.dp.toPx())
            val cx = w / 2f
            // World → screen: x in screen widths at yaw 0, y as a height above the horizon (in sky heights).
            fun sx(xw: Float, depth: Float): Float = cx + ((xw - yaw * depth) - 0.5f) * w * zoom
            fun sy(f: Float): Float = hz - f * skyH * zoom

            // ---- the pointer: eyes follow it, far things lean away a touch (mouse only)
            val ptr = night.pointer?.minus(origin)
            val lookTarget = ptr?.let { q ->
                Offset(((q.x - gx) / (gs * 1.3f)).coerceIn(-1f, 1f), ((q.y - gy) / (gs * 1.3f)).coerceIn(-1f, 1f))
            } ?: Offset(0.18f * wave(11_000) * motion, 0.12f * wave(7_700, 0.3f) * motion)
            val dt = (now - cam.lastMs).coerceIn(0L, 100L)
            cam.lastMs = now
            cam.look = cam.look + (lookTarget - cam.look) * (1f - exp(-dt / 110f))
            val look = cam.look
            val par = if (night.mouse && ptr != null && !reduce) Offset(ptr.x / w - 0.5f, ptr.y / h - 0.5f) * design.parallax else Offset.Zero

            // ---- stage: light breathes with the bass
            val sa = stage?.a
            val singing = stage?.track?.value?.playing == true
            val bass = sa?.let { it.bass * (1f - it.mood) } ?: 0f
            val beat = sa?.let { it.beat * (1f - 0.6f * it.mood) } ?: 0f
            val sing = if (singing && sa != null) (sa.vocal * 1.4f).coerceIn(0f, 1f) else 0f

            // Colour reaches a point once the front spreading from the ghost has passed it.
            val dMax = hypot(max(gx, w - gx), max(gy, h - gy)).coerceAtLeast(1f)
            val band = 0.35f
            fun lit(x: Float, y: Float): Float = smooth((e * (1f + band) - hypot(x - gx, y - gy) / dMax) / band)
            val ghostLit = smooth(e * (1f + band) / band)

            // ---- sky
            if (hz > 0f) drawRect(
                Brush.verticalGradient(
                    0f to lerp(OFF_SKY_TOP, palette.skyTop, e),
                    0.62f to lerp(OFF_SKY_MID, palette.skyMid, e),
                    1f to lerp(OFF_SKY_HORIZON, palette.skyHorizon, e),
                    startY = sy(1.05f), endY = hz,
                ),
                size = Size(w, hz),
            )

            // ---- stars
            if (hz > 0f) clipRect(0f, 0f, w, hz) {
                STARS.forEach { s ->
                    var u = ((s.x - yaw * DEPTH_STARS) % STAR_TILE + STAR_TILE) % STAR_TILE
                    if (u > 1.35f) u -= STAR_TILE
                    val x = cx + (u - 0.5f) * w * zoom - par.x * 10.dp.toPx()
                    val y = sy(s.y) - par.y * 7.dp.toPx()
                    if (y < -4f || y > hz || x < -6f || x > w + 6f) return@forEach
                    val tw = 0.5f + 0.5f * wave(1_900L * s.speed, s.tw)
                    val nearHorizon = 0.5f + 0.5f * smooth((hz - y) / 30.dp.toPx())
                    val a = ((0.20f + 0.26f * e) + (0.28f + 0.25f * e) * tw) * (1f + bass * 0.4f) * nearHorizon
                    val r = s.r.dp.toPx() * (0.85f + 0.3f * tw) * (0.8f + 0.2f * zoom)
                    drawCircle(Color.White.copy(alpha = a.coerceIn(0f, 0.95f)), r, Offset(x, y))
                    if (s.glint) {
                        val g = r * (3.4f + 1.8f * tw)
                        val ga = a * 0.5f
                        drawLine(Color.White.copy(alpha = ga), Offset(x - g, y), Offset(x + g, y), 0.7.dp.toPx(), StrokeCap.Round)
                        drawLine(Color.White.copy(alpha = ga), Offset(x, y - g), Offset(x, y + g), 0.7.dp.toPx(), StrokeCap.Round)
                    }
                }
            }

            // ---- the moon: it shoots rays now and then, its path lies on the water
            val mx = sx(0.79f, DEPTH_MOON) - par.x * 5.dp.toPx()
            val my = sy(0.75f) - par.y * 4.dp.toPx()
            val moonTint = lerp(MOONLIGHT, palette.light, 0.45f * e)
            val moonPower = 1f - 0.45f * e
            val mr = 15.dp.toPx() * (0.85f + 0.15f * zoom)
            if (hz > 0f && mx > -260.dp.toPx() && mx < w + 260.dp.toPx() && my > -300.dp.toPx()) clipRect(0f, 0f, w, hz) {
                // a faint haze, then the rays
                val haze = 170.dp.toPx() * zoom
                drawCircle(Brush.radialGradient(listOf(moonTint.copy(alpha = 0.07f * moonPower), Color.Transparent), Offset(mx, my), haze), haze, Offset(mx, my))
                val rot = loop(240_000) * 360f
                val shotN = now / (3_400L * slow)
                val shotQ = (now % (3_400L * slow)).toFloat() / (3_400L * slow) / 0.55f
                val shotRay = Random(shotN.toInt()).nextInt(MOON_RAYS.size)
                val reach = 170.dp.toPx() * zoom
                val rayGrad = Brush.radialGradient(
                    0f to moonTint.copy(alpha = 1f), 0.25f to moonTint.copy(alpha = 0.45f), 1f to Color.Transparent,
                    center = Offset.Zero, radius = 1f,
                )
                MOON_RAYS.forEachIndexed { i, r ->
                    val shot = if (!reduce && i == shotRay && shotQ < 1f) sin(PI.toFloat() * shotQ) else 0f
                    val len = reach * r[1] * (0.88f + 0.12f * wave(7_000, r[3])) * (1f + 0.9f * shot)
                    val half = tan((0.55f + 0.7f * r[2]) * PI.toFloat() / 180f)
                    val a = (0.075f + 0.035f * wave(5_300, r[3]) + 0.12f * shot) * moonPower
                    withTransform({
                        translate(mx, my)
                        rotate(r[0] + rot * (if (i % 2 == 0) 1f else -0.6f), Offset.Zero)
                        scale(len, len * half, Offset.Zero)
                    }) { drawPath(WEDGE, rayGrad, alpha = a.coerceIn(0f, 1f)) }
                    if (shot > 0.01f) {
                        // the shot: a spark running out along the ray
                        val ang = (r[0] + rot * (if (i % 2 == 0) 1f else -0.6f)) * PI.toFloat() / 180f
                        val dist = mr * 1.4f + len * 0.92f * shotQ
                        val sp = Offset(mx + cos(ang) * dist, my + sin(ang) * dist)
                        val gr = 6.dp.toPx()
                        withTransform({ translate(sp.x, sp.y); scale(gr, gr, Offset.Zero) }) { drawCircle(DOT_GLOW, 1f, Offset.Zero, alpha = shot * moonPower) }
                        drawCircle(moonTint.copy(alpha = (0.85f * shot * moonPower).coerceIn(0f, 1f)), 1.3.dp.toPx(), sp)
                    }
                }
                val glow = 64.dp.toPx() * zoom
                drawCircle(Brush.radialGradient(listOf(moonTint.copy(alpha = 0.26f * moonPower), Color.Transparent), Offset(mx, my), glow), glow, Offset(mx, my))
                val crescent = Path().apply {
                    moveTo(mx, my - mr)
                    arcTo(Rect(Offset(mx, my), mr), -90f, -180f, false)
                    val ix = mr * 11f / 15f
                    arcTo(Rect(mx - ix, my - mr, mx + ix, my + mr), 90f, 180f, false)
                    close()
                }
                drawPath(crescent, lerp(MOON, palette.light, 0.3f * e).copy(alpha = 1f - 0.35f * e))
            }

            // ---- aurora: soft curtains from the small picture, crisp rays over them
            val glowK = design.aurora * (1f + bass * 0.55f + beat * 0.15f)
            val yawA = yaw * DEPTH_AURORA
            val ph1 = loop(41_000)
            val ph2 = loop(29_000)
            val ph3 = loop(17_000)
            val ph4 = loop(11_000)
            val ph5 = loop(5_300)
            val sc = max(2f, floor(3.dp.toPx()))
            val bw = ceil(w / sc).toInt().coerceAtLeast(1)
            val bh = ceil(skyH * 1.05f / sc).toInt().coerceAtLeast(1)
            val showAurora = e > 0.003f && hz > 0f
            if (showAurora) {
                // Re-paint about 30 times a second (and right away when the colours change).
                if (buffer.image?.width != bw || buffer.image?.height != bh) {
                    buffer.image = ImageBitmap(bw, bh).also { buffer.canvas = Canvas(it) }
                    buffer.ms = Long.MIN_VALUE
                }
                if (now - buffer.ms >= 33 || buffer.palette !== palette) {
                    buffer.ms = now
                    buffer.yaw = yawA
                    buffer.palette = palette
                    // ghost position in the picture's own (unzoomed) coordinates, for the spreading colour
                    val gxu = (cx + (gx - cx) / zoom) / sc
                    val gyu = (skyH * 1.05f - (hz - gy) / zoom) / sc
                    val dMaxB = dMax / zoom / sc
                    val baseY = skyH * 1.05f / sc
                    val skyB = skyH / sc
                    buffer.scope.draw(Density(1f), LayoutDirection.Ltr, buffer.canvas!!, Size(bw.toFloat(), bh.toFloat())) {
                        drawRect(Color.Transparent, blendMode = BlendMode.Clear)
                        CURTAINS.forEach { cu ->
                            val brush = cu.pick(palette)
                            for (bx in 0 until bw) {
                                val xw = (bx + 0.5f) * sc / w + yawA
                                if (xw < cu.x0 || xw > cu.x1) continue
                                val env = cu.envelope(xw)
                                if (env <= 0.01f) continue
                                val yb = baseY - (cu.lift + cu.amp * cu.edge(xw, ph1, ph2)) * skyB
                                val tall = cu.height * skyB * (0.70f + 0.30f * (0.5f + 0.5f * sin(TAU * (xw * 3.3f + ph3) + cu.seed * 5f)))
                                val ray = 0.5f + 0.5f * sin(TAU * (xw * 23f + 0.6f * sin(TAU * (xw * 3.1f + ph4)) + ph1 * 2f) + cu.seed * 11f)
                                val fine = 0.5f + 0.5f * sin(TAU * (xw * 9f - ph5) + cu.seed)
                                val bright = env * (0.40f + 0.45f * ray * ray + 0.15f * fine)
                                val litB = smooth((e * (1f + band) - hypot(bx - gxu, yb - gyu) / dMaxB) / band)
                                val a = (cu.alpha * bright * litB * glowK).coerceIn(0f, 1f)
                                if (a <= 0.004f) continue
                                withTransform({ translate(bx.toFloat(), yb - tall); scale(1f, tall * 1.08f, Offset.Zero) }) {
                                    drawRect(brush, size = Size(1f, 1f), alpha = a)
                                }
                            }
                        }
                    }
                }
                val img = buffer.image!!
                // the camera turned since the picture was painted: shift it the rest of the way
                val shift = -(yawA - buffer.yaw) * w
                val parX = par.x * 5.dp.toPx()
                val parY = par.y * 4.dp.toPx()
                clipRect(0f, 0f, w, hz) {
                    withTransform({
                        scale(zoom, zoom, Offset(cx, hz))
                        translate(shift - parX, hz - skyH * 1.05f - parY)
                        scale(sc, sc, Offset.Zero)
                    }) {
                        drawImage(img, IntOffset.Zero, IntSize(bw, bh), IntOffset.Zero, IntSize(bw, bh), filterQuality = FilterQuality.Low)
                    }
                }
                // crisp rays
                clipRect(0f, 0f, w, hz) {
                    RAYS.forEach { r ->
                        val x = sx(r.x, DEPTH_AURORA) - par.x * 5.dp.toPx()
                        if (x < -4f || x > w + 4f) return@forEach
                        val env = r.curtain.envelope(r.x)
                        if (env <= 0.02f) return@forEach
                        val bottom = sy(r.curtain.lift + r.curtain.amp * r.curtain.edge(r.x, ph1, ph2)) + 5.dp.toPx() * zoom
                        val tall = r.tall * r.curtain.height * skyH * zoom
                        val shimmer = 0.45f + 0.55f * (0.5f + 0.5f * wave(4_700, r.ph))
                        val a = (0.40f * env * shimmer * lit(x, bottom) * glowK).coerceIn(0f, 1f)
                        if (a <= 0.004f) return@forEach
                        val rw = r.width.dp.toPx() * (0.7f + 0.3f * zoom)
                        withTransform({ translate(x, bottom - tall); scale(rw, tall, Offset.Zero) }) {
                            drawRect(RAY_FADE, size = Size(1f, 1f), alpha = a)
                        }
                    }
                }
            }

            // ---- a shooting star now and then
            if (!reduce && hz > 40.dp.toPx()) {
                val period = if (e > 0.5f) 13_000L else 23_000L
                val n = now / period
                val q = (now % period).toFloat() / 900f
                if (q < 1f) {
                    val rnd = Random(n.toInt() * 31 + 7)
                    val start = Offset(w * (0.1f + rnd.nextFloat() * 0.6f), sy(0.62f + rnd.nextFloat() * 0.3f))
                    val len = 70.dp.toPx() * zoom
                    val dir = Offset(0.94f, 0.34f)
                    val head = start + dir * (len * 2.2f * q)
                    val tail = head - dir * (len * (0.4f + 0.6f * sin(PI.toFloat() * q)))
                    val a = sin(PI.toFloat() * q) * (0.45f + 0.4f * e)
                    if (head.y in 0f..hz) drawLine(
                        Brush.linearGradient(listOf(Color.Transparent, Color.White.copy(alpha = a)), tail, head),
                        tail, head, 1.3.dp.toPx(), StrokeCap.Round,
                    )
                }
            }

            // ---- islands on the horizon (and their dark reflections)
            fun isle(path: Path, edge: Path, list: FloatArray, depth: Float) {
                path.reset()
                edge.reset()
                val n = 72
                path.moveTo(-2f, hz + 1f)
                var open = false
                for (i in 0..n) {
                    val x = i * w / n
                    val xw = (x - cx) / (w * zoom) + 0.5f + yaw * depth
                    var hgt = 0f
                    for (j in list.indices step 3) {
                        val d = (xw - list[j]) / list[j + 1]
                        if (d > -1f && d < 1f) {
                            val bump = (1f - d * d).let { it * it }
                            hgt += bump * list[j + 2] * (1f + 0.14f * sin(xw * 47f + j) + 0.06f * sin(xw * 131f + j * 2f))
                        }
                    }
                    val y = hz - hgt * skyH * zoom
                    path.lineTo(x, y)
                    if (hgt > 0.0008f) {
                        if (!open) edge.moveTo(x, y) else edge.lineTo(x, y)
                        open = true
                    } else open = false
                }
                path.lineTo(w + 2f, hz + 1f)
                path.close()
            }
            val farIsle = lerp(lerp(OFF_ISLAND, OFF_SKY_HORIZON, 0.45f), lerp(palette.island, palette.skyHorizon, 0.4f), e)
            val nearIsle = lerp(OFF_ISLAND, palette.island, e)
            if (hz > 0f && hz < h) {
                isle(isles[0], isles[1], FAR_ISLES, DEPTH_FAR_ISLES)
                isle(isles[2], isles[3], NEAR_ISLES, DEPTH_NEAR_ISLES)
                drawPath(isles[0], farIsle)
                drawPath(isles[1], palette.light.copy(alpha = 0.10f * e), style = Stroke(0.8.dp.toPx()))
                drawPath(isles[2], nearIsle)
                drawPath(isles[3], palette.light.copy(alpha = 0.16f * e), style = Stroke(0.9.dp.toPx()))
            }

            // ---- halo behind the ghost
            val haloColor = lerp(lerp(OFF_HALO, palette.halo, ghostLit), Color(0xFFFF9AA9), er * 0.7f)
            val haloA = ((0.12f + (0.30f + 0.06f * wave(3_000)) * ghostLit + 0.10f * b * (0.5f + 0.5f * wave(1_200))) * (1f + beat * 0.5f) + 0.05f * hoverA.value) * design.halo
            val haloR = gs * 0.87f * (1f + 0.04f * bass)
            drawCircle(
                Brush.radialGradient(
                    0f to haloColor.copy(alpha = haloA.coerceIn(0f, 1f)), 0.45f to haloColor.copy(alpha = (haloA * 0.31f).coerceIn(0f, 1f)), 1f to Color.Transparent,
                    center = Offset(gx, gy), radius = haloR,
                ),
                haloR, Offset(gx, gy),
            )

            // ---- the water
            if (hz < h) {
                val top = max(hz, 0f)
                drawRect(
                    Brush.verticalGradient(
                        listOf(lerp(OFF_WATER_TOP, palette.waterTop, e), lerp(OFF_WATER_BOTTOM, palette.waterBottom, e)),
                        startY = hz, endY = hz + h * 0.5f,
                    ),
                    topLeft = Offset(0f, top), size = Size(w, h - top),
                )
                clipRect(0f, top, w, h) {
                    val lineColor = lerp(OFF_LIGHT, palette.light, e)
                    // the aurora, mirrored and faded
                    if (showAurora) {
                        val img = buffer.image!!
                        val shift = -(yawA - buffer.yaw) * w
                        withTransform({
                            translate(0f, 2f * hz)
                            scale(1f, -1f, Offset.Zero)
                            scale(zoom, zoom, Offset(cx, hz))
                            translate(shift, hz - skyH * 1.05f)
                            scale(sc, sc, Offset.Zero)
                        }) {
                            drawImage(img, IntOffset.Zero, IntSize(bw, bh), IntOffset.Zero, IntSize(bw, bh), alpha = 0.30f, filterQuality = FilterQuality.Low)
                        }
                    }
                    // islands mirrored, dark
                    if (hz > 0f) withTransform({ translate(0f, 2f * hz); scale(1f, -1f, Offset.Zero) }) {
                        drawPath(isles[0], farIsle.copy(alpha = 0.55f))
                        drawPath(isles[2], nearIsle.copy(alpha = 0.7f))
                    }
                    // the moon's path on the water
                    if (mx > -40.dp.toPx() && mx < w + 40.dp.toPx()) {
                        val n = 26
                        val depthH = h * 0.42f
                        for (i in 0 until n) {
                            val t = i / (n - 1f)
                            val y = hz + 3.dp.toPx() + t.pow(1.45f) * depthH
                            val half = (5f + 46f * t).dp.toPx() * zoom * (0.75f + 0.25f * (0.5f + 0.5f * wave(3_300, i * 0.17f)))
                            val x = mx + wave(4_100, i * 0.21f) * (2f + 6f * t).dp.toPx() * motion
                            val a = (0.30f * (1f - t).pow(0.8f) * (0.45f + 0.55f * (0.5f + 0.5f * wave(2_200, i * 0.37f))) * moonPower).coerceIn(0f, 1f)
                            drawLine(moonTint.copy(alpha = a), Offset(x - half, y), Offset(x + half, y), (1f + 0.6f * t).dp.toPx(), StrokeCap.Round)
                        }
                    }
                    // the surface: faint long glints drifting, denser near the horizon; the nearer a row, the
                    // faster it slides past as the camera travels (the water lies flat towards the viewer)
                    for (i in 0 until 14) {
                        val y = hz + (3f + 1.9f * (i + 1f).pow(1.55f)).dp.toPx() * zoom
                        val drift = loop(26_000L + i * 1_700L)
                        val depth = 0.55f + 0.12f * i
                        for (j in 0 until 3) {
                            val u = (((j * 0.41f + i * 0.17f + drift - yaw * depth) % 1.3f) + 1.3f) % 1.3f - 0.15f
                            val x0 = u * w
                            val len = w * (0.08f + 0.05f * ((i + j) % 3))
                            val a = (0.028f + 0.03f * e) * (0.6f + 0.4f * wave(3_700, i * 0.11f + j * 0.3f))
                            drawLine(lineColor.copy(alpha = a), Offset(x0, y), Offset(x0 + len, y), 0.8.dp.toPx(), StrokeCap.Round)
                        }
                    }
                    // the ghost, mirrored: slices that sway a little, fading with depth
                    val phase = loop(GHOST_LOOP_MS) * TAU
                    val sat = max(max(ghostLit, b * 0.55f), max(max(hoverA.value * 0.4f, showtime.value), er * 0.6f)).coerceIn(0f, 1f)
                    val tone = lerp(OFF_GHOST, palette.ghostTone, sat)
                    if (gy + gs * 0.3f < hz) {
                        val l = ceil(gs * 2f).toInt().coerceAtLeast(1)
                        if (now - ghostRec[0] >= 40L || ghostRec[1] != l.toLong() || mirrorLayer.size.width != l) {
                            val shape = mascotShape(phase, motion)
                            val body = SolidColor(lerp(tone, Color.White, 0.55f))
                            val flame = SolidColor(tone)
                            mirrorLayer.compositingStrategy = androidx.compose.ui.graphics.layer.CompositingStrategy.Offscreen
                            mirrorLayer.record(this, layoutDirection, androidx.compose.ui.unit.IntSize(l, l)) {
                                translate((l - gs) / 2f, (l - gs) / 2f) {
                                    drawMascotShape(shape, gs, phase, er, motion, body, flame, 1f)
                                }
                                // the far end of the reflection (the head, once mirrored) fades into the water
                                drawRect(
                                    Brush.verticalGradient(0f to Color.Transparent, 0.45f to Color.White.copy(alpha = 0.35f), 0.85f to Color.White, startY = 0f, endY = l.toFloat()),
                                    blendMode = BlendMode.DstIn,
                                )
                            }
                        }
                        val small = 0.45f + 0.55f * smooth((gs - 70.dp.toPx()) / 120.dp.toPx())
                        val depth = ((2f * hz - gy) - hz) / max(h * 0.32f, 1f)
                        mirrorLayer.alpha = ((0.22f + 0.08f * ghostLit) * small * (1f - depth * 0.6f)).coerceIn(0f, 1f)
                        val sway = sin(TAU * loop(2_600)) * 1.5.dp.toPx() * motion
                        val lx = gx - l / 2f + sway
                        val ly = 2f * hz - (gy - l / 2f)
                        withTransform({ translate(lx, ly); scale(1f, -1f, Offset.Zero) }) { drawLayer(mirrorLayer) }
                    }
                    // the aurora's rays, mirrored near the horizon
                    if (showAurora) RAYS.forEach { r ->
                        val x = sx(r.x, DEPTH_AURORA)
                        if (x < -4f || x > w + 4f) return@forEach
                        val env = r.curtain.envelope(r.x)
                        val bottom = sy(r.curtain.lift + r.curtain.amp * r.curtain.edge(r.x, ph1, ph2))
                        val tall = r.tall * r.curtain.height * skyH * zoom
                        val a = (0.12f * env * lit(x, bottom) * glowK).coerceIn(0f, 1f)
                        if (a <= 0.004f) return@forEach
                        val rw = r.width.dp.toPx()
                        withTransform({ translate(x, 2f * hz - bottom + tall); scale(rw, -tall, Offset.Zero) }) {
                            drawRect(RAY_FADE, size = Size(1f, 1f), alpha = a)
                        }
                    }

                    // horizon: a soft band of light and a hairline
                    val bandH = 6.dp.toPx()
                    drawRect(
                        Brush.verticalGradient(
                            0f to Color.Transparent, 0.5f to lineColor.copy(alpha = 0.12f + 0.16f * e), 1f to Color.Transparent,
                            startY = hz - bandH, endY = hz + bandH,
                        ),
                        topLeft = Offset(0f, hz - bandH), size = Size(w, bandH * 2f),
                    )
                    drawRect(lineColor.copy(alpha = 0.12f + 0.10f * e), topLeft = Offset(0f, hz), size = Size(w, max(1f, 0.8.dp.toPx())))

                    // ripples under the ghost, slowly widening
                    val rip = (gs / 195.dp.toPx()).coerceIn(0.4f, 1.5f)
                    for (i in 0 until 3) {
                        val q = (loop(5_200) + i / 3f) % 1f
                        val grow = 0.75f + 0.55f * q
                        val rx = gs * 0.42f * grow * (1f + i * 0.08f)
                        val ry = 5.6.dp.toPx() * rip * grow
                        val a = (0.07f + 0.20f * e) * sin(PI.toFloat() * q) * (1f + 0.6f * b)
                        if (a > 0.004f) drawOval(
                            lineColor.copy(alpha = a.coerceIn(0f, 1f)),
                            topLeft = Offset(gx - rx, hz + 2.dp.toPx() - ry), size = Size(rx * 2f, ry * 2f),
                            style = Stroke(1.dp.toPx()),
                        )
                    }
                    // connecting: rings run out over the water
                    if (b > 0.01f) for (i in 0 until 2) {
                        val q = (loop(1_500) + i * 0.5f) % 1f
                        val rx = gs * (0.3f + 1.5f * q)
                        val ry = rx * 0.05f
                        drawOval(
                            lineColor.copy(alpha = (1f - q) * 0.34f * b),
                            topLeft = Offset(gx - rx, hz + 3.dp.toPx() - ry), size = Size(rx * 2f, ry * 2f),
                            style = Stroke(1.2.dp.toPx()),
                        )
                    }
                    // glints right under the ghost
                    val glints = floatArrayOf(18f, -0.02f, 0.52f, 0.26f, 34f, 0.02f, 0.35f, 0.2f, 53f, -0.2f, 0.25f, 0.12f, 57f, 0.2f, 0.35f, 0.14f)
                    for (i in 0 until 4) {
                        val dy = glints[i * 4].dp.toPx() * rip
                        val gxi = gx + glints[i * 4 + 1] * gs + wave(6_000, i * 0.27f) * 5.dp.toPx() * motion
                        val half = glints[i * 4 + 2] * gs / 2f
                        val a = glints[i * 4 + 3] * (0.4f + 0.6f * e) * (0.6f + 0.4f * wave(2_300, i * 0.31f))
                        drawLine(lineColor.copy(alpha = a.coerceIn(0f, 1f)), Offset(gxi - half, hz + dy), Offset(gxi + half, hz + dy), 1.1.dp.toPx(), StrokeCap.Round)
                    }
                }
            }

            // ---- the ghost
            run {
                val phase = loop(GHOST_LOOP_MS) * TAU
                val bob = gs * 0.035f * (if (reduce) 0.3f else 1f) * wave(5_200) * (0.45f + 0.55f * max(e, b))
                val blink = run {
                    val t = (now % 4_800L).toInt()
                    when {
                        t < 4_300 -> 1f
                        t < 4_400 -> 1f - 0.92f * (t - 4_300) / 100f
                        t < 4_520 -> 0.08f + 0.92f * (t - 4_400) / 120f
                        else -> 1f
                    }
                }
                val sq = rig.squish.value
                val expr = rig.expr
                val hv = hoverA.value
                val show = showtime.value
                val awake = max(max(ghostLit, b), max(max(hv * 0.9f, show), er))
                val sleepy = if (expr != GhostExpr.NONE || show > 0.05f) 0f else (1f - awake)
                val doze = smooth((sleepy - 0.55f) / 0.4f)
                val sat = max(max(ghostLit, b * 0.55f), max(max(hv * 0.4f, show), er * 0.6f)).coerceIn(0f, 1f)
                val tone = lerp(lerp(OFF_GHOST, palette.ghostTone, sat), Color(0xFFFF9AA9), er * 0.55f)
                val scaleK = press.value * (1f + 0.045f * beat)
                val l = ceil(gs * 2f).toInt().coerceAtLeast(1)
                if (now - ghostRec[0] >= 40L || ghostRec[1] != l.toLong() || ghostLayer.size.width != l) {
                    ghostRec[0] = now
                    ghostRec[1] = l.toLong()
                    val waveR = rig.waveR.value
                    val waveL = rig.waveL.value
                    // Offscreen: the layer is kept as a picture and only re-rendered when re-recorded, every other
                    // frame just shows it.
                    ghostLayer.compositingStrategy = androidx.compose.ui.graphics.layer.CompositingStrategy.Offscreen
                    ghostLayer.record(this, layoutDirection, androidx.compose.ui.unit.IntSize(l, l)) {
                        val inset = (l - gs) / 2f
                        withTransform({
                            translate(inset + look.x * gs * 0.015f, inset + look.y * gs * 0.01f - sq * gs * 0.06f)
                            scale(1f + 0.14f * sq, 1f - 0.14f * sq, Offset(gs / 2f, gs * 0.82f))
                        }) {
                            drawMascot(
                                gs, blink, ghostLit, er, tone, sleepy, look, expr, sing, show,
                                phase, waveR, waveL, motion, doze = doze, mono = 1f - sat,
                            )
                        }
                    }
                }
                withTransform({
                    rotate(heading * lift * 13f, Offset(gx, gy))
                    scale(scaleK, scaleK, Offset(gx, gy))
                    translate(gx - l / 2f, gy - l / 2f + bob)
                }) { drawLayer(ghostLayer) }
                // connecting: a sonar ring around the ghost
                if (b > 0.01f) {
                    val q = loop(1_500)
                    drawCircle(palette.light.copy(alpha = (1f - q) * 0.22f * b), gs * (0.46f + 0.55f * q), Offset(gx, gy), style = Stroke(1.4.dp.toPx()))
                }
            }

            // ---- fireflies
            val fa = ghostLit * e
            if (fa > 0.01f) {
                for (i in 0 until FIREFLIES.size / 2) {
                    val fx = gx + FIREFLIES[i * 2] * gs + gs * 0.035f * wave(7_000, i * 0.13f) * motion
                    val fy = gy + FIREFLIES[i * 2 + 1] * gs + gs * 0.03f * wave(9_100, i * 0.29f + 0.25f) * motion
                    val tw = 0.45f + 0.55f * (0.5f + 0.5f * wave(3_100, i * 0.37f))
                    val a = (fa * tw * (1f + beat * 0.6f)).coerceIn(0f, 1f)
                    val core = (1.0f + (i % 3) * 0.4f).dp.toPx() * (0.7f + 0.3f * zoom)
                    val col = if (i % 2 == 0) Color(0xFFEFE9FF) else palette.light
                    val gr = core * 4.5f
                    withTransform({ translate(fx, fy); scale(gr, gr, Offset.Zero) }) { drawCircle(DOT_GLOW, 1f, Offset.Zero, alpha = a, blendMode = BlendMode.Plus) }
                    drawCircle(col.copy(alpha = a), core, Offset(fx, fy))
                }
            }
        }
        content()
    }
}
