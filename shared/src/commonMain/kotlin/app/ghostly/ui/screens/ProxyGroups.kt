package app.ghostly.ui.screens

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AccountTree
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.NetworkPing
import androidx.compose.material.icons.rounded.SwapVert
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.ghostly.core.mihomo.ProxyGroupInfo
import app.ghostly.ui.theme.Motion
import app.ghostly.ui.components.Spinner
import app.ghostly.ui.theme.Ghost

/**
 * mihomo proxy groups (selectors) as list items: a header per group with what it points at, and —
 * when opened — its members with delays. Selector members are picked by a tap; automatic groups
 * (url-test, fallback, load-balance) only show their choice.
 */
internal fun LazyListScope.proxyGroups(
    groups: List<ProxyGroupInfo>,
    open: Set<String>,
    testing: Set<String>,
    pad: Dp,
    onToggle: (String) -> Unit,
    onSelect: (group: String, member: String) -> Unit,
    onTest: (String) -> Unit,
) {
    if (groups.isEmpty()) return
    item(key = "mihomo:title") {
        val c = Ghost.colors
        Row(Modifier.padding(start = pad + 6.dp, top = 14.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.AccountTree, null, tint = c.accent, modifier = Modifier.size(14.dp))
            Spacer(Modifier.width(6.dp))
            Text("ГРУППЫ MIHOMO", style = MaterialTheme.typography.labelSmall, color = c.ink3)
        }
    }
    groups.forEach { g ->
        item(key = "mihomo:g:" + g.name) {
            GroupHeader(g, g.name in open, g.name in testing, pad, { onToggle(g.name) }, { onTest(g.name) })
        }
        if (g.name in open) {
            items(g.members, key = { "mihomo:m:" + g.name + "\u0000" + it }) { m ->
                MemberRow(m, g, pad) { onSelect(g.name, m) }
            }
        }
    }
}

private fun typeLabel(type: String): String = when (type.lowercase()) {
    "selector" -> "выбор"
    "urltest" -> "авто по пингу"
    "fallback" -> "резерв"
    "loadbalance" -> "балансировка"
    "relay" -> "цепочка"
    "smart" -> "умный"
    else -> type.lowercase()
}

@Composable
private fun GroupHeader(g: ProxyGroupInfo, open: Boolean, testing: Boolean, pad: Dp, onToggle: () -> Unit, onTest: () -> Unit) {
    val c = Ghost.colors
    val arrow by animateFloatAsState(if (open) 0f else -90f, Motion.bouncy())
    Row(
        Modifier.fillMaxWidth().padding(horizontal = pad, vertical = 2.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(Color.White.copy(alpha = 0.035f))
            .pointerHoverIcon(PointerIcon.Hand)
            .clickable(remember { MutableInteractionSource() }, null, onClick = onToggle)
            .padding(start = 10.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Rounded.ExpandMore, null, tint = c.ink3, modifier = Modifier.size(20.dp).rotate(arrow))
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                app.ghostly.ui.components.FlagText(g.name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                Text("  " + typeLabel(g.type), style = MaterialTheme.typography.labelSmall, color = c.ink3, maxLines = 1)
            }
            val now = g.now
            if (now != null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(if (g.selectable) Icons.Rounded.SwapVert else Icons.Rounded.AutoAwesome, null, tint = c.accent, modifier = Modifier.size(12.dp))
                    Spacer(Modifier.width(4.dp))
                    app.ghostly.ui.components.FlagText(now, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                    g.delays[now]?.let { Text("  " + delayText(it), style = MaterialTheme.typography.labelSmall, color = delayColor(it)) }
                }
            }
        }
        Text("${g.members.size}", style = MaterialTheme.typography.labelMedium, color = c.ink3, modifier = Modifier.padding(horizontal = 6.dp))
        Box(Modifier.size(34.dp).clip(RoundedCornerShape(10.dp)).clickable(onClick = onTest), contentAlignment = Alignment.Center) {
            if (testing) Spinner(c.accent, Modifier.size(16.dp))
            else Icon(Icons.Rounded.NetworkPing, "Проверить группу", tint = c.ink3, modifier = Modifier.size(18.dp))
        }
    }
}

@Composable
private fun MemberRow(name: String, g: ProxyGroupInfo, pad: Dp, onClick: () -> Unit) {
    val c = Ghost.colors
    val chosen = name == g.now
    val nested = name in g.nestedGroups
    Row(
        Modifier.fillMaxWidth().padding(start = pad + 18.dp, end = pad, top = 1.dp, bottom = 1.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(if (chosen) c.accent.copy(alpha = 0.11f) else Color.Transparent)
            .then(if (g.selectable) Modifier.pointerHoverIcon(PointerIcon.Hand).clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(18.dp), contentAlignment = Alignment.Center) {
            if (chosen) Icon(Icons.Rounded.CheckCircle, null, tint = c.accent, modifier = Modifier.size(16.dp))
            else Box(Modifier.size(6.dp).clip(CircleShape).background(c.ink3.copy(alpha = 0.5f)))
        }
        Spacer(Modifier.width(10.dp))
        app.ghostly.ui.components.FlagText(name, style = MaterialTheme.typography.bodyMedium, color = if (chosen) c.ink else c.ink2, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        if (nested) Text("группа", style = MaterialTheme.typography.labelSmall, color = c.ink3, modifier = Modifier.padding(horizontal = 6.dp))
        g.delays[name]?.let { Text(delayText(it), style = MaterialTheme.typography.labelMedium.copy(fontFeatureSettings = "tnum"), color = delayColor(it)) }
    }
}

private fun delayText(ms: Int) = if (ms <= 0) "нет" else "$ms мс"

@Composable
private fun delayColor(ms: Int): Color {
    val c = Ghost.colors
    return when {
        ms <= 0 -> c.bad
        ms < 180 -> c.ok
        ms < 450 -> c.warn
        else -> c.bad
    }
}
