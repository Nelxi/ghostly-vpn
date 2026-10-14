package app.ghostly.ui.components

import androidx.compose.ui.draw.dropShadow
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.ghostly.ui.theme.Ghost
import app.ghostly.ui.theme.Motion
import androidx.compose.material3.MaterialTheme
import app.ghostly.i18n.tr

// ---------------------------------------------------------------- pills & badges

@Composable
fun PingPill(ms: Long?, loading: Boolean, modifier: Modifier = Modifier) {
    val c = Ghost.colors
    val color = when {
        loading || ms == null -> c.ink3
        ms <= 0 -> c.bad
        ms < 180 -> c.ok
        ms < 450 -> c.warn
        else -> c.bad
    }
    val animated by animateColorAsState(color, Motion.quick())
    Row(
        modifier.clip(CircleShape).background(animated.copy(alpha = 0.12f)).padding(horizontal = 9.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (loading) {
            val a by ambientFloat(0.25f, 1f, 600, reverse = true)
            Box(Modifier.size(6.dp).graphicsLayer { alpha = a }.clip(CircleShape).background(animated))
        } else {
            Box(Modifier.size(6.dp).clip(CircleShape).background(animated))
        }
        Spacer(Modifier.width(6.dp))
        // Digits roll like on the site's counters; the words just swap.
        RollingText(
            when {
                loading -> "···"
                ms == null -> "—"
                ms <= 0 -> tr("нет")
                else -> tr("{0} мс", ms)
            },
            style = MaterialTheme.typography.labelSmall, color = animated,
        )
    }
}

@Composable
fun Tag(text: String, modifier: Modifier = Modifier, color: Color = Ghost.colors.ink3) {
    Text(
        text.uppercase(),
        modifier.clip(RoundedCornerShape(8.dp)).background(color.copy(alpha = 0.12f)).padding(horizontal = 6.dp, vertical = 2.dp),
        style = MaterialTheme.typography.labelSmall.copy(fontSize = 9.5.sp, letterSpacing = 0.6.sp),
        color = color, maxLines = 1,
    )
}

// ---------------------------------------------------------------- buttons

/** Primary action: a solid accent button with a lit top edge (no glow under it). */
@Composable
fun AccentButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, icon: ImageVector? = null, enabled: Boolean = true) {
    val c = Ghost.colors
    val interaction = remember { MutableInteractionSource() }
    Row(
        modifier
            .pressScale(interaction, 0.97f)
            .softShadow(RoundedCornerShape(Radius.control), if (enabled) 0.6f else 0f)
            .clip(RoundedCornerShape(Radius.control))
            .background(c.accent)
            .background(Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.16f), Color.Transparent), endY = 60f))
            .sheen(interaction, strength = 0.18f)
            .graphicsLayer { compositingStrategy = androidx.compose.ui.graphics.CompositingStrategy.ModulateAlpha; alpha = if (enabled) 1f else 0.45f }
            .hoverSound().clickable(interaction, null, enabled = enabled, onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 15.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        icon?.let { Icon(it, null, tint = c.accentInk, modifier = Modifier.size(19.dp)); Spacer(Modifier.width(9.dp)) }
        Text(text, style = MaterialTheme.typography.labelLarge, color = c.accentInk)
    }
}

@Composable
fun SoftButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, icon: ImageVector? = null, tint: Color = Ghost.colors.ink) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val c = Ghost.colors
    val bg by animateColorAsState(if (hovered) c.surface3 else c.surface2, Motion.quick())
    val edge by animateColorAsState(if (hovered) c.accent.copy(alpha = 0.45f) else c.hairline, Motion.quick())
    Row(
        modifier
            .pressScale(interaction, 0.97f)
            .clip(RoundedCornerShape(Radius.control))
            .background(bg)
            .sheen(interaction, strength = 0.08f)
            .border(1.dp, edge, RoundedCornerShape(Radius.control))
            .hoverSound().clickable(interaction, null, onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 14.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        icon?.let { Icon(it, null, tint = tint, modifier = Modifier.size(19.dp)); Spacer(Modifier.width(9.dp)) }
        Text(text, style = MaterialTheme.typography.labelLarge, color = tint)
    }
}

@Composable
fun IconBubble(icon: ImageVector, onClick: () -> Unit, modifier: Modifier = Modifier, size: Dp = 42.dp, tint: Color = Ghost.colors.ink2, active: Boolean = false) {
    val c = Ghost.colors
    val feel = LocalHapticOf.current
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val bg by animateColorAsState(
        when {
            active -> c.accent.copy(alpha = 0.18f)
            hovered -> c.surface3
            else -> c.surface2
        },
        Motion.quick(),
    )
    val edge by animateColorAsState(if (active || hovered) c.accent.copy(alpha = 0.4f) else c.hairline, Motion.quick())
    val tilt by animateFloatAsState(if (hovered) -8f else 0f, Motion.bouncy())
    Box(
        modifier
            .size(size)
            .pressScale(interaction, 0.88f, hover = 1.06f)
            .softShadow(CircleShape, 0.5f).clip(CircleShape)
            .background(bg)
            .border(1.dp, edge, CircleShape)
            .hoverSound().clickable(interaction, null, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, null, tint = if (active || hovered) c.accent else tint, modifier = Modifier.size(size * 0.46f).graphicsLayer { rotationZ = tilt })
    }
}

// ---------------------------------------------------------------- settings rows

@Composable
fun GhostSwitch(checked: Boolean, modifier: Modifier = Modifier) {
    val c = Ghost.colors
    val f by animateFloatAsState(if (checked) 1f else 0f, Motion.bouncy())
    val track by animateColorAsState(if (checked) c.accent else Color.White.copy(alpha = 0.12f), Motion.quick())
    // Liquid knob: stretches mid-flight, like a drop being dragged.
    val stretch = (4f * f * (1f - f)).coerceIn(0f, 1f) * 8f
    Box(modifier.size(46.dp, 26.dp).outerShadow(CircleShape, 0.45f).clip(CircleShape).background(track).padding(3.dp)) {
        Box(
            Modifier.offset(x = (20f * f - stretch * f).dp).size((20f + stretch).dp, 20.dp).clip(CircleShape)
                .background(if (checked) c.accentInk else Color.White.copy(alpha = 0.85f)),
        )
    }
}

@Composable
fun SettingRow(
    title: String,
    subtitle: String? = null,
    icon: ImageVector? = null,
    onClick: (() -> Unit)? = null,
    trailing: @Composable RowScope.() -> Unit = {},
) {
    val c = Ghost.colors
    val feel = LocalHapticOf.current
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val pressed by interaction.collectIsPressedAsState()
    val live = onClick != null && (hovered || pressed)
    val bg by animateColorAsState(Color.White.copy(alpha = if (pressed) 0.07f else if (live) 0.045f else 0f), Motion.quick())
    val lift by animateFloatAsState(if (live) 1f else 0f, Motion.bouncy())
    Row(
        Modifier.fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(bg)
            .then(if (onClick != null) Modifier.spotlight(c.accent, 180.dp).hoverSound().clickable(interaction, null) { feel(app.ghostly.core.vpn.Haptic.TICK); onClick() } else Modifier)
            .padding(vertical = 11.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Box(
                Modifier.size(34.dp)
                    .graphicsLayer { val s = 1f + 0.08f * lift; scaleX = s; scaleY = s; rotationZ = -6f * lift }
                    .clip(RoundedCornerShape(12.dp))
                    .background(c.accent.copy(alpha = 0.13f + 0.1f * lift)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(icon, null, tint = c.accent, modifier = Modifier.size(18.dp))
            }
            Spacer(Modifier.width(13.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Medium, fontSize = 15.sp))
            subtitle?.let { Text(it, style = MaterialTheme.typography.bodySmall, maxLines = 3, overflow = TextOverflow.Ellipsis) }
        }
        Spacer(Modifier.width(10.dp))
        // Chevrons and values lean towards the cursor a bit.
        Row(Modifier.graphicsLayer { translationX = 3.dp.toPx() * lift }, verticalAlignment = Alignment.CenterVertically) { trailing() }
    }
}

@Composable
fun ToggleRow(title: String, subtitle: String?, checked: Boolean, icon: ImageVector? = null, onChange: (Boolean) -> Unit) {
    SettingRow(title, subtitle, icon, onClick = { onChange(!checked) }) { GhostSwitch(checked) }
}

/** iOS-like segmented control with a sliding liquid thumb. */
@Composable
fun <T> Segmented(options: List<Pair<T, String>>, selected: T, onSelect: (T) -> Unit, modifier: Modifier = Modifier) {
    val feel = LocalHapticOf.current
    val c = Ghost.colors
    val index = options.indexOfFirst { it.first == selected }.coerceAtLeast(0)
    val pos by animateFloatAsState(index.toFloat(), Motion.bouncy())
    BoxWithConstraints(
        modifier.fillMaxWidth().height(42.dp).clip(RoundedCornerShape(14.dp)).background(c.surface3).padding(3.dp),
    ) {
        val segW = maxWidth / options.size
        Box(
            Modifier.offset(x = segW * pos).width(segW).fillMaxHeight().softShadow(RoundedCornerShape(11.dp), 0.5f).clip(RoundedCornerShape(11.dp))
                .background(c.surface1)
                .border(1.dp, c.accent.copy(alpha = 0.45f), RoundedCornerShape(11.dp)),
        )
        Row(Modifier.fillMaxWidth().fillMaxHeight()) {
            options.forEach { (value, label) ->
                Box(
                    Modifier.weight(1f).fillMaxHeight().clip(RoundedCornerShape(12.dp))
                        .hoverSound().clickable(remember { MutableInteractionSource() }, null) { if (value != selected) feel(app.ghostly.core.vpn.Haptic.CLICK); onSelect(value) },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(label, style = MaterialTheme.typography.labelMedium, color = if (value == selected) c.ink else c.ink3, maxLines = 1)
                }
            }
        }
    }
}

@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        modifier.padding(start = 6.dp, top = 24.dp, bottom = 10.dp),
        style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold, fontSize = 15.sp),
        color = Ghost.colors.ink2,
    )
}

// ---------------------------------------------------------------- charts

/** Smooth area sparkline for live speed. */
@Composable
fun Sparkline(values: List<Float>, color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        if (values.size < 2) return@Canvas
        val max = (values.maxOrNull() ?: 0f).coerceAtLeast(1f)
        val stepX = size.width / (values.size - 1)
        fun y(v: Float) = size.height - (v / max) * size.height * 0.9f
        val line = Path()
        values.forEachIndexed { i, v ->
            val x = i * stepX
            if (i == 0) line.moveTo(x, y(v)) else {
                val px = (i - 1) * stepX
                val pv = values[i - 1]
                line.cubicTo(px + stepX / 2, y(pv), x - stepX / 2, y(v), x, y(v))
            }
        }
        val area = Path().apply { addPath(line); lineTo(size.width, size.height); lineTo(0f, size.height); close() }
        drawPath(area, Brush.verticalGradient(listOf(color.copy(alpha = 0.28f), Color.Transparent)))
        drawPath(line, color, style = Stroke(1.6.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
    }
}

/** Thin progress bar with a glowing head. */
@Composable
fun GlowBar(progress: Float, color: Color, modifier: Modifier = Modifier) {
    val p by animateFloatAsState(progress.coerceIn(0f, 1f), Motion.soft())
    Canvas(modifier.fillMaxWidth().height(6.dp)) {
        val r = size.height / 2
        drawRoundRect(Color.White.copy(alpha = 0.07f), cornerRadius = androidx.compose.ui.geometry.CornerRadius(r, r))
        if (p > 0f) {
            drawRoundRect(
                Brush.horizontalGradient(listOf(color.copy(alpha = 0.7f), color)),
                size = size.copy(width = (size.width * p).coerceAtLeast(size.height)), cornerRadius = androidx.compose.ui.geometry.CornerRadius(r, r),
            )
        }
    }
}

@Composable
fun Spinner(color: Color, modifier: Modifier = Modifier) {
    val a by ambientFloat(0f, 360f, 900, LinearEasing)
    Canvas(modifier) {
        drawArc(color, a, 260f, false, style = Stroke(2.dp.toPx(), cap = StrokeCap.Round))
    }
}
