package app.ghostly.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.rounded.Block
import androidx.compose.material.icons.rounded.CardGiftcard
import androidx.compose.material.icons.rounded.ChatBubble
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material.icons.rounded.Verified
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.ghostly.core.GhostlyController
import app.ghostly.core.notice.Notice
import app.ghostly.core.vpn.Haptic
import app.ghostly.ui.theme.Ghost
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt

private class NoticeLook(val a: Color, val b: Color, val icon: ImageVector)

/** Colours come from the theme (the user's accent, Monet, a seasonal accent), so a notice looks like the rest of the app. */
@Composable
private fun lookOf(kind: String): NoticeLook {
    val c = Ghost.colors
    fun deep(x: Color) = androidx.compose.ui.graphics.lerp(x, Color(0xFF0A0614), 0.38f)
    return when (kind) {
        "gift" -> NoticeLook(deep(c.ok), c.ok, Icons.Rounded.CardGiftcard)
        "warn" -> NoticeLook(deep(c.warn), c.warn, Icons.Rounded.WarningAmber)
        "danger" -> NoticeLook(deep(c.bad), c.bad, Icons.Rounded.Block)
        else -> NoticeLook(c.accent2, c.accent, Icons.Rounded.ChatBubble)
    }
}

/**
 * Ghostly's message in the app window, like an incoming Telegram message: drops in from the top,
 * swipe it away (sideways or up) or tap ✕. One at a time; the next one follows.
 */
@Composable
fun NoticeOverlay(controller: GhostlyController, modifier: Modifier = Modifier) {
    val queue by controller.notices.queue.collectAsState()
    val head = queue.firstOrNull()
    // Keep the last notice while it slides out.
    var shown by remember { mutableStateOf<Notice?>(null) }
    if (head != null) shown = head
    AnimatedVisibility(
        head != null, modifier,
        enter = slideInVertically(spring(dampingRatio = 0.62f, stiffness = 380f)) { -it * 2 } + fadeIn(tween(180)) + scaleIn(initialScale = 0.9f),
        exit = slideOutVertically(tween(260)) { -it } + fadeOut(tween(220)),
    ) {
        val n = shown ?: return@AnimatedVisibility
        androidx.compose.runtime.key(n.id) {
            NoticeCard(n, more = queue.size - 1, controller = controller)
        }
    }
}

@Composable
private fun NoticeCard(n: Notice, more: Int, controller: GhostlyController) {
    val c = Ghost.colors
    val look = lookOf(n.kind)
    val scope = rememberCoroutineScope()
    val dx = remember { Animatable(0f) }
    val dy = remember { Animatable(0f) }
    val shake = remember { Animatable(0f) }

    LaunchedEffect(n.id) {
        controller.haptic(if (n.kind == "danger") Haptic.ERROR else Haptic.SUCCESS)
        if (n.kind == "danger") {
            delay(420)
            for (x in listOf(-14f, 12f, -8f, 5f, -2f, 0f)) shake.animateTo(x, tween(55))
        }
    }

    fun dismiss() = controller.dismissNotice(n.id)

    val shape = RoundedCornerShape(24.dp)
    Box(
        Modifier.widthIn(max = 440.dp).fillMaxWidth()
            .offset { IntOffset((dx.value + shake.value).roundToInt(), dy.value.roundToInt()) }
            .graphicsLayer {
                rotationZ = dx.value / 40f
                alpha = 1f - ((abs(dx.value) + abs(dy.value)) / 700f).coerceIn(0f, 0.8f)
            }
            .pointerInput(n.id) {
                detectDragGestures(
                    onDragEnd = {
                        scope.launch {
                            if (abs(dx.value) > 140f || dy.value < -70f) {
                                launch { dx.animateTo(dx.value * 3f, tween(200)) }
                                dy.animateTo(dy.value * 3f, tween(200))
                                dismiss()
                            } else {
                                launch { dx.animateTo(0f, spring(Spring.DampingRatioMediumBouncy)) }
                                dy.animateTo(0f, spring(Spring.DampingRatioMediumBouncy))
                            }
                        }
                    },
                ) { change, drag ->
                    change.consume()
                    scope.launch {
                        dx.snapTo(dx.value + drag.x)
                        dy.snapTo((dy.value + drag.y).coerceAtMost(0f))
                    }
                }
            }
            .shadow(28.dp, shape, ambientColor = look.a, spotColor = look.a.copy(alpha = 0.6f))
            .clip(shape)
            .background(Brush.verticalGradient(listOf(androidx.compose.ui.graphics.lerp(Color(0xF01A1328), look.a, 0.10f), Color(0xF20E091A))))
            .background(Brush.radialGradient(listOf(look.a.copy(alpha = 0.22f), Color.Transparent), radius = 420f, center = androidx.compose.ui.geometry.Offset(0f, 0f)))
            .border(1.dp, Brush.linearGradient(listOf(look.a.copy(alpha = 0.7f), Color.White.copy(alpha = 0.06f), look.b.copy(alpha = 0.45f))), shape)
            .padding(start = 12.dp, end = 12.dp, top = 12.dp, bottom = 13.dp),
    ) {
        Row {
            // Avatar: the ghost on the kind's colour, with the kind's badge.
            Box(Modifier.size(44.dp)) {
                Box(
                    Modifier.size(44.dp).clip(CircleShape)
                        .background(Brush.radialGradient(listOf(look.b, look.a, Color(0xFF1B1230)))),
                    contentAlignment = Alignment.Center,
                ) { GhostMark(Modifier.size(28.dp), pokeable = false) }
                Box(
                    Modifier.align(Alignment.BottomEnd).offset(3.dp, 3.dp).size(20.dp).clip(CircleShape)
                        .background(Color(0xFF1C1529)).padding(3.dp).clip(CircleShape).background(look.a),
                    contentAlignment = Alignment.Center,
                ) { Icon(look.icon, null, tint = Color.White, modifier = Modifier.size(10.dp)) }
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(n.sender.ifBlank { "Ghostly" }, style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.SemiBold, fontSize = 15.sp), color = c.ink)
                    Spacer(Modifier.width(4.dp))
                    Icon(Icons.Rounded.Verified, null, tint = look.b, modifier = Modifier.size(15.dp))
                    Spacer(Modifier.weight(1f))
                    Text(ago(n.ts), style = MaterialTheme.typography.labelSmall, color = c.ink3)
                    Spacer(Modifier.width(6.dp))
                    Icon(
                        Icons.Rounded.Close, "Закрыть", tint = c.ink2,
                        modifier = Modifier.size(24.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.07f))
                            .hoverSound().clickable { controller.haptic(Haptic.TICK); dismiss() }.padding(5.dp),
                    )
                }
                if (n.personal) {
                    Spacer(Modifier.height2())
                    Text(
                        "Лично вам", color = look.b,
                        style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.SemiBold),
                        modifier = Modifier.clip(CircleShape).background(look.a.copy(alpha = 0.2f)).padding(horizontal = 8.dp, vertical = 1.dp),
                    )
                }
                if (n.title.isNotBlank()) {
                    Spacer(Modifier.height2())
                    Text(n.title, style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.SemiBold), color = c.ink)
                }
                if (n.text.isNotBlank()) {
                    Spacer(Modifier.height2())
                    Box(Modifier.heightIn(max = 260.dp).verticalScroll(rememberScrollState())) {
                        Text(rich(n.text, look.b), style = MaterialTheme.typography.bodyMedium.copy(lineHeight = 20.sp), color = c.ink2)
                    }
                }
                val b = n.button
                if (b?.url != null || more > 0) {
                    Spacer(Modifier.height(10.dp))
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        if (b?.url != null) {
                            Row(
                                Modifier.clip(RoundedCornerShape(12.dp)).background(Brush.linearGradient(listOf(look.b, androidx.compose.ui.graphics.lerp(look.b, look.a, 0.45f))))
                                    .hoverSound().clickable { controller.haptic(); controller.platform.openUrl(b.url) }
                                    .padding(horizontal = 14.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(b.text.ifBlank { "Открыть" }, color = Color(0xFF150A2C), style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold))
                                Spacer(Modifier.width(6.dp))
                                Icon(Icons.AutoMirrored.Rounded.ArrowForward, null, tint = Color(0xFF150A2C), modifier = Modifier.size(15.dp))
                            }
                        }
                        if (more > 0) Text("ещё $more", style = MaterialTheme.typography.labelMedium, color = c.ink3)
                    }
                }
            }
        }
    }
}

private fun Modifier.height2() = this.then(Modifier.padding(top = 3.dp))

private fun ago(ts: Long): String {
    if (ts <= 0) return "сейчас"
    val d = (app.ghostly.core.GhostlyController.now() / 1000 - ts).coerceAtLeast(0)
    return when {
        d < 60 -> "сейчас"
        d < 3600 -> "${d / 60} мин"
        d < 86400 -> "${d / 3600} ч"
        else -> "${d / 86400} дн"
    }
}

/** `**bold**` and `_italic_` — the same marks the admin page offers. */
private fun rich(text: String, accent: Color): AnnotatedString = buildAnnotatedString {
    val re = Regex("""\*\*(.+?)\*\*|(?<=^|\s)_(.+?)_(?=\s|$)|(https?://\S+)""")
    var i = 0
    for (m in re.findAll(text)) {
        append(text.substring(i, m.range.first))
        when {
            m.groupValues[1].isNotEmpty() -> withStyle(SpanStyle(fontWeight = FontWeight.Bold, color = Color.White)) { append(m.groupValues[1]) }
            m.groupValues[2].isNotEmpty() -> withStyle(SpanStyle(fontStyle = FontStyle.Italic)) { append(m.groupValues[2]) }
            else -> withStyle(SpanStyle(color = accent)) { append(m.value) }
        }
        i = m.range.last + 1
    }
    append(text.substring(i))
}


/** Bell in the header: unread count, and a window with the last 14 days of notices. */
@Composable
fun NoticeBell(controller: GhostlyController) {
    val c = Ghost.colors
    val unread by controller.notices.queue.collectAsState()
    var open by remember { mutableStateOf(false) }
    Box {
        IconBubble(Icons.Rounded.Notifications, onClick = { open = true; controller.loadNoticeHistory() }, active = open)
        if (unread.isNotEmpty()) {
            Box(
                Modifier.align(Alignment.TopEnd).offset(2.dp, (-2).dp).size(18.dp).clip(CircleShape).background(c.bad),
                contentAlignment = Alignment.Center,
            ) { Text("${unread.size}", color = Color(0xFF1A0610), style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold, fontSize = 10.sp)) }
        }
    }
    if (open) {
        androidx.compose.ui.window.Dialog(onDismissRequest = { open = false }) {
            val items by controller.notices.history.collectAsState()
            Column(
                Modifier.widthIn(max = 480.dp).fillMaxWidth().heightIn(max = 640.dp).clip(RoundedCornerShape(26.dp))
                    .background(Color(0xFF130E1D))
                    .border(1.dp, Brush.verticalGradient(listOf(c.accent.copy(alpha = 0.4f), Color.White.copy(alpha = 0.05f))), RoundedCornerShape(26.dp))
                    .padding(18.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Уведомления", style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold), color = c.ink, modifier = Modifier.weight(1f))
                    Icon(Icons.Rounded.Close, "Закрыть", tint = c.ink2, modifier = Modifier.size(30.dp).clip(CircleShape).clickable { open = false }.padding(5.dp))
                }
                Spacer(Modifier.height(12.dp))
                if (items.isEmpty()) {
                    Text("Пока тихо. Здесь будут объявления Ghostly и сообщения лично вам.", color = c.ink3, style = MaterialTheme.typography.bodyMedium)
                } else {
                    Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        items.forEach { n -> NoticeRow(n, controller) }
                    }
                }
            }
        }
        // Opening the window counts as reading: pending pop-ups go away.
        LaunchedEffect(Unit) { controller.notices.queue.value.forEach { controller.dismissNotice(it.id) } }
    }
}

@Composable
private fun NoticeRow(n: Notice, controller: GhostlyController) {
    val c = Ghost.colors
    val look = lookOf(n.kind)
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp))
            .background(Brush.verticalGradient(listOf(look.a.copy(alpha = 0.12f), Color.White.copy(alpha = 0.02f))))
            .border(1.dp, look.b.copy(alpha = 0.25f), RoundedCornerShape(18.dp)).padding(14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(22.dp).clip(CircleShape).background(look.a), contentAlignment = Alignment.Center) {
                Icon(look.icon, null, tint = Color.White, modifier = Modifier.size(12.dp))
            }
            Spacer(Modifier.width(8.dp))
            Text(n.sender.ifBlank { "Ghostly" }, style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold), color = c.ink)
            if (n.personal) {
                Spacer(Modifier.width(6.dp))
                Text(
                    "лично вам", color = look.b, style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.clip(CircleShape).background(look.a.copy(alpha = 0.2f)).padding(horizontal = 7.dp, vertical = 1.dp),
                )
            }
            Spacer(Modifier.weight(1f))
            Text(ago(n.ts), style = MaterialTheme.typography.labelSmall, color = c.ink3)
        }
        if (n.title.isNotBlank()) Text(n.title, style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.SemiBold), color = c.ink, modifier = Modifier.padding(top = 6.dp))
        if (n.text.isNotBlank()) Text(rich(n.text, look.b), style = MaterialTheme.typography.bodyMedium, color = c.ink2, modifier = Modifier.padding(top = 3.dp))
        val b = n.button
        if (b?.url != null) {
            Text(
                b.text.ifBlank { "Открыть" } + " →", color = look.b, style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
                modifier = Modifier.padding(top = 8.dp).clip(RoundedCornerShape(10.dp)).clickable { controller.platform.openUrl(b.url) }.padding(vertical = 4.dp),
            )
        }
    }
}
