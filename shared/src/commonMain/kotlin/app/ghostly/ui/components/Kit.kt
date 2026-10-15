package app.ghostly.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Remove
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.ghostly.i18n.tr
import app.ghostly.ui.theme.Ghost
import app.ghostly.ui.theme.LocalReduceMotion
import app.ghostly.ui.theme.Motion

/**
 * The shared shape scale: cards [Radius.card], tiles and inputs inside them [Radius.tile], small chips
 * [Radius.chip]. Buttons use [Radius.control]. One scale for the whole app.
 */
object Radius {
    val card = 24.dp
    val tile = 16.dp
    val control = 16.dp
    val chip = 10.dp
}

enum class Tone { NEUTRAL, ACCENT, OK, WARN, BAD }

@Composable
fun toneColor(tone: Tone): Color {
    val c = Ghost.colors
    return when (tone) {
        Tone.NEUTRAL -> c.ink2
        Tone.ACCENT -> c.accent
        Tone.OK -> c.ok
        Tone.WARN -> c.warn
        Tone.BAD -> c.bad
    }
}

/** Small status label: «Активна», «из бота», «Скоро закончится». */
@Composable
fun Chip(text: String, tone: Tone = Tone.NEUTRAL, modifier: Modifier = Modifier, icon: ImageVector? = null) {
    val color = toneColor(tone)
    Row(
        modifier.clip(RoundedCornerShape(Radius.chip)).background(color.copy(alpha = if (tone == Tone.NEUTRAL) 0.08f else 0.14f))
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        icon?.let {
            Icon(it, null, tint = color, modifier = Modifier.size(13.dp))
            Spacer(Modifier.width(5.dp))
        }
        Text(text, style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 0.1.sp), color = color, maxLines = 1)
    }
}

/** A note inside a card or a sheet: an explanation, a warning, an error. */
@Composable
fun Note(text: String, tone: Tone = Tone.NEUTRAL, modifier: Modifier = Modifier, icon: ImageVector? = null) {
    val c = Ghost.colors
    val color = toneColor(tone)
    Row(
        modifier.fillMaxWidth().clip(RoundedCornerShape(Radius.tile))
            .background(if (tone == Tone.NEUTRAL) c.surface2 else color.copy(alpha = 0.1f))
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.Top,
    ) {
        icon?.let {
            Icon(it, null, tint = color, modifier = Modifier.size(18.dp).padding(top = 1.dp))
            Spacer(Modifier.width(10.dp))
        }
        Text(text, style = MaterialTheme.typography.bodyMedium.copy(color = if (tone == Tone.NEUTRAL) c.ink2 else c.ink))
    }
}

/** Text input with its label above (never a placeholder in place of a label) and the error under it. */
@Composable
fun Field(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    placeholder: String? = null,
    password: Boolean = false,
    keyboard: KeyboardType = KeyboardType.Text,
    error: String? = null,
    hint: String? = null,
    enabled: Boolean = true,
    imeAction: ImeAction = ImeAction.Next,
    onDone: (() -> Unit)? = null,
) {
    val c = Ghost.colors
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    var reveal by remember { mutableStateOf(false) }
    val edge by animateColorAsState(
        when {
            error != null -> c.bad.copy(alpha = 0.7f)
            focused -> c.accent.copy(alpha = 0.65f)
            else -> c.hairline
        },
        Motion.quick(),
    )
    Column(modifier) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = c.ink2)
        Spacer(Modifier.height(6.dp))
        Row(
            Modifier.fillMaxWidth().heightIn(min = 50.dp).clip(RoundedCornerShape(Radius.tile)).background(c.surface3)
                .border(1.dp, edge, RoundedCornerShape(Radius.tile)).padding(start = 14.dp, end = if (password) 4.dp else 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.weight(1f).padding(vertical = 14.dp)) {
                if (value.isEmpty() && placeholder != null) Text(placeholder, style = MaterialTheme.typography.bodyLarge, color = c.ink3, maxLines = 1)
                BasicTextField(
                    value, onValueChange,
                    enabled = enabled,
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodyLarge.copy(color = c.ink),
                    cursorBrush = SolidColor(c.accent),
                    visualTransformation = if (password && !reveal) PasswordVisualTransformation() else VisualTransformation.None,
                    keyboardOptions = KeyboardOptions(keyboardType = if (password) KeyboardType.Password else keyboard, imeAction = imeAction, autoCorrectEnabled = false),
                    keyboardActions = KeyboardActions(onDone = { onDone?.invoke() }, onGo = { onDone?.invoke() }),
                    interactionSource = interaction,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            if (password) {
                Box(
                    Modifier.size(42.dp).clip(CircleShape).hoverSound().clickable { reveal = !reveal },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(if (reveal) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility, if (reveal) tr("Скрыть") else tr("Показать"), tint = c.ink3, modifier = Modifier.size(20.dp))
                }
            }
        }
        when {
            error != null -> Text(error, style = MaterialTheme.typography.bodySmall.copy(color = c.bad), modifier = Modifier.padding(top = 6.dp))
            hint != null -> Text(hint, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 6.dp))
        }
    }
}

/** − value + with limits; [format] renders the number («3 дня», «+5 ГБ»). */
@Composable
fun Stepper(value: Int, min: Int, max: Int, onChange: (Int) -> Unit, modifier: Modifier = Modifier, step: Int = 1, format: (Int) -> String = { it.toString() }) {
    val c = Ghost.colors
    val feel = LocalHapticOf.current
    Row(
        modifier.fillMaxWidth().height(58.dp).clip(RoundedCornerShape(Radius.tile)).background(c.surface3).padding(5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        StepButton(Icons.Rounded.Remove, value - step >= min) { feel(app.ghostly.core.vpn.Haptic.TICK); onChange((value - step).coerceAtLeast(min)) }
        RollingText(
            format(value), modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold, fontFeatureSettings = "tnum", textAlign = TextAlign.Center),
            color = c.ink,
        )
        StepButton(Icons.Rounded.Add, value + step <= max) { feel(app.ghostly.core.vpn.Haptic.TICK); onChange((value + step).coerceAtMost(max)) }
    }
}

@Composable
private fun StepButton(icon: ImageVector, enabled: Boolean, onClick: () -> Unit) {
    val c = Ghost.colors
    val interaction = remember { MutableInteractionSource() }
    Box(
        Modifier.size(48.dp).pressScale(interaction, 0.88f).clip(RoundedCornerShape(12.dp))
            .background(if (enabled) c.surface1 else Color.Transparent)
            .hoverSound().clickable(interaction, null, enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, null, tint = if (enabled) c.ink else c.ink3.copy(alpha = 0.4f), modifier = Modifier.size(20.dp))
    }
}

/** Quick choices under a stepper or a field: «7 дней», «30 дней»… */
@Composable
fun <T> ChoiceRow(options: List<Pair<T, String>>, selected: T?, onSelect: (T) -> Unit, modifier: Modifier = Modifier) {
    val c = Ghost.colors
    val feel = LocalHapticOf.current
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        options.forEach { (value, label) ->
            val on = value == selected
            val bg by animateColorAsState(if (on) c.accent.copy(alpha = 0.18f) else c.surface2, Motion.quick())
            val edge by animateColorAsState(if (on) c.accent.copy(alpha = 0.55f) else c.hairline, Motion.quick())
            val interaction = remember { MutableInteractionSource() }
            Box(
                Modifier.weight(1f).height(40.dp).pressScale(interaction, 0.94f).clip(RoundedCornerShape(12.dp)).background(bg)
                    .border(1.dp, edge, RoundedCornerShape(12.dp))
                    .hoverSound().clickable(interaction, null) { feel(app.ghostly.core.vpn.Haptic.TICK); onSelect(value) },
                contentAlignment = Alignment.Center,
            ) {
                Text(label, style = MaterialTheme.typography.labelMedium, color = if (on) c.ink else c.ink2, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

/** A square action in a grid: icon over a short label. */
@Composable
fun ActionTile(icon: ImageVector, label: String, onClick: () -> Unit, modifier: Modifier = Modifier, primary: Boolean = false, enabled: Boolean = true, badge: String? = null) {
    val c = Ghost.colors
    val interaction = remember { MutableInteractionSource() }
    Box(modifier) {
        Column(
            Modifier.fillMaxWidth().pressScale(interaction, 0.94f).clip(RoundedCornerShape(Radius.tile))
                .background(if (primary) c.accent else c.surface2)
                .then(if (primary) Modifier else Modifier.border(1.dp, c.hairline, RoundedCornerShape(Radius.tile)))
                .graphicsLayer { alpha = if (enabled) 1f else 0.45f }
                .hoverSound().clickable(interaction, null, enabled = enabled, onClick = onClick)
                .padding(horizontal = 6.dp, vertical = 13.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Icon(icon, null, tint = if (primary) c.accentInk else c.accent, modifier = Modifier.size(22.dp))
            Spacer(Modifier.height(7.dp))
            Text(label, style = MaterialTheme.typography.labelMedium.copy(fontSize = 12.5.sp), color = if (primary) c.accentInk else c.ink, maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
        }
        badge?.let {
            Text(
                it, style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp), color = c.accentInk,
                modifier = Modifier.align(Alignment.TopEnd).padding(5.dp).clip(RoundedCornerShape(6.dp)).background(c.warn).padding(horizontal = 5.dp, vertical = 1.dp),
            )
        }
    }
}

/** A wide action for two-column grids: icon and label side by side. */
@Composable
fun ActionWide(icon: ImageVector, label: String, onClick: () -> Unit, modifier: Modifier = Modifier, primary: Boolean = false, enabled: Boolean = true) {
    val c = Ghost.colors
    val interaction = remember { MutableInteractionSource() }
    Row(
        modifier.height(50.dp).pressScale(interaction, 0.96f).clip(RoundedCornerShape(Radius.tile))
            .background(if (primary) c.accent else c.surface2)
            .then(if (primary) Modifier.background(Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.14f), Color.Transparent))) else Modifier.border(1.dp, c.hairline, RoundedCornerShape(Radius.tile)))
            .graphicsLayer { alpha = if (enabled) 1f else 0.45f }
            .hoverSound().clickable(interaction, null, enabled = enabled, onClick = onClick)
            .padding(horizontal = 12.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = if (primary) c.accentInk else c.accent, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(10.dp))
        Text(label, style = MaterialTheme.typography.labelLarge, color = if (primary) c.accentInk else c.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/**
 * The middle of a ring: a number over its unit, both trimmed to their glyphs so the pair sits in the
 * optical centre of the ring (default line heights push it up).
 */
@Composable
fun RingLabel(number: String, unit: String?, big: Boolean = true) {
    val c = Ghost.colors
    val trim = androidx.compose.ui.text.style.LineHeightStyle(
        androidx.compose.ui.text.style.LineHeightStyle.Alignment.Center,
        androidx.compose.ui.text.style.LineHeightStyle.Trim.Both,
    )
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        val numberStyle = (if (big) MaterialTheme.typography.headlineMedium else MaterialTheme.typography.titleLarge)
        Text(
            number,
            style = numberStyle.copy(
                fontWeight = FontWeight.ExtraBold, fontFeatureSettings = "tnum", letterSpacing = 0.sp,
                lineHeight = numberStyle.fontSize, lineHeightStyle = trim, textAlign = TextAlign.Center,
            ),
        )
        if (unit != null) {
            Spacer(Modifier.height(if (big) 4.dp else 3.dp))
            Text(
                unit,
                style = MaterialTheme.typography.labelSmall.copy(
                    fontSize = if (big) 11.sp else 9.5.sp, lineHeight = if (big) 11.sp else 9.5.sp, lineHeightStyle = trim,
                    letterSpacing = 0.sp, textAlign = TextAlign.Center,
                ),
                color = c.ink3,
            )
        }
    }
}

/** A progress ring with something in its middle (days left, a percentage). */
@Composable
fun RingProgress(fraction: Float, color: Color, modifier: Modifier = Modifier, stroke: Dp = 7.dp, content: @Composable BoxScope.() -> Unit = {}) {
    val c = Ghost.colors
    val f by animateFloatAsState(fraction.coerceIn(0f, 1f), Motion.soft())
    Box(modifier, contentAlignment = Alignment.Center) {
        Canvas(Modifier.matchParentSize()) {
            val sw = stroke.toPx()
            val d = size.minDimension - sw
            val tl = Offset((size.width - d) / 2, (size.height - d) / 2)
            drawArc(c.surface3, 0f, 360f, false, tl, Size(d, d), style = Stroke(sw))
            if (f > 0f) drawArc(
                Brush.sweepGradient(listOf(color.copy(alpha = 0.55f), color, color.copy(alpha = 0.55f))),
                -90f, 360f * f, false, tl, Size(d, d), style = Stroke(sw, cap = StrokeCap.Round),
            )
        }
        content()
    }
}

/** A placeholder block while something loads: a soft light sweeps across it. */
@Composable
fun Skeleton(modifier: Modifier, corner: Dp = Radius.tile) {
    val c = Ghost.colors
    val reduce = LocalReduceMotion.current
    val p by ambientFloat(0f, 1f, 1400, LinearEasing)
    Box(
        modifier.clip(RoundedCornerShape(corner)).background(c.surface2).then(
            if (reduce) Modifier else Modifier.background(
                Brush.linearGradient(
                    listOf(Color.Transparent, Color.White.copy(alpha = 0.05f), Color.Transparent),
                    start = Offset(-600f + 1600f * p, 0f), end = Offset(-200f + 1600f * p, 300f),
                ),
            ),
        ),
    )
}

/** Heading of a block: plain sentence case, no eyebrow capitals. */
@Composable
fun BlockTitle(text: String, modifier: Modifier = Modifier, trailing: @Composable (() -> Unit)? = null) {
    Row(modifier.fillMaxWidth().padding(start = 6.dp, end = 2.dp, top = 24.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(text, style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold), color = Ghost.colors.ink, modifier = Modifier.weight(1f))
        trailing?.invoke()
    }
}

/** A plain card surface: opaque night ink, a hairline lit from the top, one soft shadow. */
@Composable
fun Tile(modifier: Modifier = Modifier, padding: Dp = 18.dp, onClick: (() -> Unit)? = null, content: @Composable ColumnScope.() -> Unit) {
    GlassCard(modifier, padding = padding, onClick = onClick, content = content)
}

/**
 * What a «days left» ring shows for [leftMs]: whole days (the same count as «Осталось N дней»), hours in
 * the last day. (number, unit, fraction of a 30-day month).
 */
fun ringLeft(leftMs: Long): Triple<String, String, Float> {
    val left = leftMs.coerceAtLeast(0)
    val days = left / 86_400_000
    if (days >= 1) return Triple(days.toString(), app.ghostly.i18n.L10n.plural(days, "день", "дня", "дней"), (left / 86_400_000f / 30f).coerceAtMost(1f))
    val hours = if (left == 0L) 0L else ((left + 3_599_999) / 3_600_000).coerceAtLeast(1)
    return Triple(hours.toString(), app.ghostly.i18n.L10n.plural(hours, "час", "часа", "часов"), left / 86_400_000f / 30f)
}

/** Rubles: «129 ₽», «1 290 ₽», «12,5 ₽», «−140 ₽». */
fun rub(amount: Double): String {
    val cents = kotlin.math.round(amount * 100).toLong()
    val abs = kotlin.math.abs(cents)
    val whole = (abs / 100).toString().reversed().chunked(3).joinToString(" ").reversed()
    val frac = (abs % 100).toString().padStart(2, '0').trimEnd('0')
    return (if (cents < 0) "−" else "") + whole + (if (frac.isEmpty()) "" else ",$frac") + " ₽"
}

fun rub(amount: Int): String = rub(amount.toDouble())
