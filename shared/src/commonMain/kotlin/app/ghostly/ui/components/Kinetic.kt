package app.ghostly.ui.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Campaign
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import app.ghostly.core.GhostlyController
import app.ghostly.ui.theme.Ghost
import app.ghostly.ui.theme.LocalDesign
import app.ghostly.ui.theme.LocalReduceMotion
import app.ghostly.ui.theme.Motion
import kotlinx.coroutines.delay

/**
 * Headline whose letters fly in one after another (rise + unfold) whenever the text changes —
 * the "kinetic type" move from the site's hero.
 */
@Composable
fun KineticText(text: String, style: TextStyle, color: Color, modifier: Modifier = Modifier) {
    val reduce = LocalReduceMotion.current
    AnimatedContent(text, modifier, transitionSpec = { fadeIn(tween(120)) togetherWith fadeOut(tween(160)) }) { t ->
        Row {
            t.forEachIndexed { i, ch ->
                val p = remember { Animatable(if (reduce) 1f else 0f) }
                LaunchedEffect(Unit) {
                    if (!reduce) {
                        delay(i * 24L)
                        p.animateTo(1f, spring(dampingRatio = 0.55f, stiffness = 320f))
                    }
                }
                Text(
                    ch.toString(), style = style, color = color,
                    modifier = Modifier.graphicsLayer {
                        val v = p.value
                        alpha = v.coerceIn(0f, 1f)
                        translationY = (1f - v) * 16.dp.toPx()
                        rotationX = (1f - v) * -70f
                        cameraDistance = 10f * density
                    },
                )
            }
        }
    }
}

/**
 * Living light behind the connect orb: a soft state-coloured glow with slowly turning rays,
 * much larger than the orb itself (drawn outside the bounds). Violet idle, amber while
 * connecting, mint when protected, red on error.
 */
@Composable
fun Modifier.orbHalo(state: OrbState): Modifier {
    val c = Ghost.colors
    val design = LocalDesign.current
    val reduce = LocalReduceMotion.current
    val target = when (state) {
        OrbState.CONNECTED -> c.ok
        OrbState.CONNECTING -> lerp(c.accent, c.warn, 0.45f)
        OrbState.ERROR -> c.bad
        OrbState.IDLE -> c.accent
    }
    val col by animateColorAsState(target, tween(900))
    val power by animateFloatAsState(
        when (state) {
            OrbState.CONNECTED -> 1f
            OrbState.CONNECTING -> 0.85f
            OrbState.ERROR -> 0.6f
            OrbState.IDLE -> 0.55f
        },
        tween(900),
    )
    val t = rememberInfiniteTransition()
    val rot by t.animateFloat(0f, 360f, infiniteRepeatable(tween(if (state == OrbState.CONNECTING) 7000 else 46_000, easing = LinearEasing)))
    val breath by t.animateFloat(0f, 1f, infiniteRepeatable(tween(3600, easing = Motion.EaseInOut), RepeatMode.Reverse))
    return drawBehind {
        val k = design.halo
        if (k <= 0.01f) return@drawBehind
        val r = size.minDimension * 1.0f
        val a = (0.20f + 0.10f * breath) * power * k
        drawCircle(Brush.radialGradient(listOf(col.copy(alpha = a), col.copy(alpha = a * 0.35f), Color.Transparent), center, r), r, center)
        if (!reduce) {
            // Rays: a sweep gradient, faded out radially through an offscreen layer.
            drawIntoCanvas { canvas ->
                canvas.saveLayer(Rect(center.x - r, center.y - r, center.x + r, center.y + r), Paint())
                rotate(rot, center) {
                    val ray = col.copy(alpha = (a * 1.1f).coerceAtMost(1f))
                    drawCircle(
                        Brush.sweepGradient(
                            listOf(
                                Color.Transparent, ray, Color.Transparent, ray.copy(alpha = ray.alpha * 0.5f), Color.Transparent,
                                ray, Color.Transparent, ray.copy(alpha = ray.alpha * 0.6f), Color.Transparent,
                            ),
                            center,
                        ),
                        r, center,
                    )
                }
                drawCircle(
                    Brush.radialGradient(listOf(Color.Transparent, Color.White, Color.White.copy(alpha = 0.4f), Color.Transparent), center, r),
                    r, center, blendMode = BlendMode.DstIn,
                )
                canvas.restore()
            }
        }
    }
}

/** Small pulsing dot for "live" data. */
@Composable
fun LiveDot(color: Color, modifier: Modifier = Modifier) {
    val t = rememberInfiniteTransition()
    val p by t.animateFloat(0f, 1f, infiniteRepeatable(tween(1400, easing = LinearEasing)))
    Box(modifier.size(14.dp), contentAlignment = Alignment.Center) {
        Box(Modifier.size(14.dp).graphicsLayer { val s = 0.4f + 0.9f * p; scaleX = s; scaleY = s; alpha = (1f - p) * 0.6f }.clip(CircleShape).background(color))
        Box(Modifier.size(6.dp).clip(CircleShape).background(color))
    }
}

/** Announcement from the server (design.json → banner): news, promos, outages. */
@Composable
fun AnnouncementCard(controller: GhostlyController, modifier: Modifier = Modifier) {
    val c = Ghost.colors
    val design = LocalDesign.current
    val dismissed by controller.design.dismissed.collectAsState()
    val b = design.banner ?: return
    if (b.id in dismissed) return
    if (b.until > 0 && GhostlyController.now() / 1000 > b.until) return
    GlassCard(
        modifier.fillMaxWidth().orbitBorder(true, c.accent, 26.dp),
        padding = 14.dp, strong = true,
        onClick = b.url?.let { u -> { controller.platform.openUrl(u) } },
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(36.dp).clip(RoundedCornerShape(12.dp)).background(c.accent.copy(alpha = 0.16f)), contentAlignment = Alignment.Center) {
                Icon(Icons.Rounded.Campaign, null, tint = c.accent, modifier = Modifier.size(20.dp))
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(b.title, style = MaterialTheme.typography.titleSmall)
                if (b.text.isNotBlank()) Text(b.text, style = MaterialTheme.typography.bodySmall)
            }
            Icon(
                Icons.Rounded.Close, null, tint = c.ink3,
                modifier = Modifier.size(30.dp).clip(CircleShape).hoverSound().clickable { controller.design.dismiss(b.id) }.padding(6.dp),
            )
        }
    }
}
