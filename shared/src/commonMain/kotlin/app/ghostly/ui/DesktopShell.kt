package app.ghostly.ui

import app.ghostly.ui.components.pageFade
import androidx.compose.animation.ExitTransition
import app.ghostly.ui.components.hoverSound
import app.ghostly.ui.components.outerShadow
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Dns
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.ghostly.core.GhostlyController
import app.ghostly.core.vpn.VpnState
import app.ghostly.ui.components.GhostMark
import app.ghostly.ui.components.GlowBar
import app.ghostly.ui.components.sheen
import app.ghostly.ui.components.spotlight
import app.ghostly.ui.screens.HomeDesktop
import app.ghostly.ui.screens.ServersScreen
import app.ghostly.ui.screens.SettingsScreen
import app.ghostly.ui.theme.Ghost
import app.ghostly.ui.theme.Motion

private val NAV_ITEM_H = 48.dp
private val NAV_GAP = 6.dp

/** Desktop / tablet layout: glass sidebar + roomy content area. */
@Composable
internal fun DesktopShell(controller: GhostlyController, tab: Tab, onTab: (Tab) -> Unit, onAdd: () -> Unit) {
    Row(Modifier.fillMaxSize()) {
        Sidebar(controller, tab, onTab, onAdd)
        AnimatedContent(
            targetState = tab,
            transitionSpec = {
                // no fade (it cuts or delays the shadows): the old page leaves at once, the new one slides in
                slideInVertically(Motion.quick(360)) { it / 28 } togetherWith ExitTransition.None
            },
            modifier = Modifier.weight(1f).fillMaxHeight(),
        ) { t ->
          Box(Modifier.fillMaxSize()) {
            when (t) {
                Tab.HOME -> HomeDesktop(controller, onAdd)
                Tab.SERVERS -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
                    Box(Modifier.widthIn(max = 980.dp).fillMaxSize()) {
                        ServersScreen(controller, PaddingValues(top = 20.dp, bottom = 24.dp), onAdd = onAdd)
                    }
                }
                Tab.SETTINGS -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
                    Box(Modifier.widthIn(max = 820.dp).fillMaxSize()) {
                        SettingsScreen(controller, PaddingValues(top = 20.dp, bottom = 24.dp))
                    }
                }
            }
          }
        }
    }
}

@Composable
private fun Sidebar(controller: GhostlyController, tab: Tab, onTab: (Tab) -> Unit, onAdd: () -> Unit) {
    val c = Ghost.colors
    val state by controller.state.collectAsState()
    val profiles by controller.profiles.collectAsState()
    val connected = state is VpnState.Connected

    Column(
        Modifier.width(250.dp).fillMaxHeight()
            .background(Brush.horizontalGradient(listOf(Color(0x66100B19), Color(0x33100B19))))
            .padding(horizontal = 16.dp, vertical = 20.dp),
    ) {
        // Brand
        Row(Modifier.padding(start = 6.dp, bottom = 26.dp), verticalAlignment = Alignment.CenterVertically) {
            GhostMark(Modifier.size(38.dp), happy = if (connected) 1f else 0.4f)
            Spacer(Modifier.width(10.dp))
            Column {
                Text("Ghostly", style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.ExtraBold))
                Text("VPN", style = MaterialTheme.typography.labelSmall, color = c.accent)
            }
        }

        // Navigation with a liquid indicator sliding between items.
        val index = Tab.entries.indexOf(tab)
        val indicatorY by animateDpAsState((NAV_ITEM_H + NAV_GAP) * index, Motion.bouncy())
        Box {
            Box(
                Modifier.offset(y = indicatorY).fillMaxWidth().height(NAV_ITEM_H).outerShadow(RoundedCornerShape(16.dp), 0.6f).clip(RoundedCornerShape(16.dp))
                    .background(Brush.horizontalGradient(listOf(c.accent.copy(alpha = 0.26f), c.accent2.copy(alpha = 0.10f))))
                    .border(1.dp, c.accent.copy(alpha = 0.32f), RoundedCornerShape(16.dp)),
            )
            Column(verticalArrangement = Arrangement.spacedBy(NAV_GAP)) {
                NavItem(Icons.Rounded.Home, "Главная", tab == Tab.HOME) { onTab(Tab.HOME) }
                NavItem(Icons.Rounded.Dns, "Серверы", tab == Tab.SERVERS) { onTab(Tab.SERVERS) }
                NavItem(Icons.Rounded.Settings, "Настройки", tab == Tab.SETTINGS) { onTab(Tab.SETTINGS) }
            }
        }
        Spacer(Modifier.height(14.dp))
        NavItem(Icons.Rounded.Add, "Добавить подписку", false, tint = c.accent, onClick = onAdd)

        Spacer(Modifier.weight(1f))

        // Subscription mini card
        val profile = remember(profiles) { controller.selectedServer()?.let { controller.profileOf(it.id) } ?: profiles.firstOrNull() }
        profile?.info?.let { info ->
            Column(
                Modifier.fillMaxWidth().outerShadow(RoundedCornerShape(20.dp), 0.8f).clip(RoundedCornerShape(20.dp)).background(Color.White.copy(alpha = 0.045f))
                    .border(1.dp, c.line, RoundedCornerShape(20.dp)).spotlight(c.accent, 140.dp).padding(14.dp),
            ) {
                app.ghostly.ui.components.FlagText(profile.name, style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(2.dp))
                Text(
                    if (info.unlimitedTime) "Бессрочно" else Format.expiryPhrase(info.expire, GhostlyController.now()),
                    style = MaterialTheme.typography.bodySmall,
                )
                if (info.pools.isNotEmpty()) {
                    info.pools.forEach { p ->
                        Spacer(Modifier.height(10.dp))
                        Text(p.title, style = MaterialTheme.typography.bodySmall)
                        Spacer(Modifier.height(4.dp))
                        GlowBar(if (p.total > 0) p.used.toFloat() / p.total else 0f, if (p.isWhitelist) c.accent else c.ok)
                        Spacer(Modifier.height(3.dp))
                        Text(if (p.total > 0) "${Format.bytes(p.used)} из ${Format.bytes(p.total)}" else "без лимита", style = MaterialTheme.typography.bodySmall)
                    }
                } else if (!info.unlimitedTraffic) {
                    Spacer(Modifier.height(10.dp))
                    GlowBar(info.used.toFloat() / info.total, c.accent)
                    Spacer(Modifier.height(6.dp))
                    Text("${Format.bytes(info.used)} из ${Format.bytes(info.total)}", style = MaterialTheme.typography.bodySmall)
                }
            }
            Spacer(Modifier.height(10.dp))
        }

        app.ghostly.ui.components.UpdateBanner(controller, Modifier.padding(bottom = 10.dp), compact = true)
        StatusChip(state) { controller.haptic(app.ghostly.core.vpn.Haptic.HEAVY); controller.toggle() }
        Spacer(Modifier.height(10.dp))
        Text("v${controller.platform.appVersion}", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(start = 8.dp))
    }
}

@Composable
private fun NavItem(icon: ImageVector, label: String, active: Boolean, tint: Color? = null, onClick: () -> Unit) {
    val c = Ghost.colors
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val color by animateColorAsState(tint ?: if (active) c.ink else if (hovered) c.ink2 else c.ink3, Motion.quick())
    val shift by animateDpAsState(if (hovered && !active) 4.dp else 0.dp, Motion.bouncy())
    val hoverBg by animateFloatAsState(if (hovered && !active) 1f else 0f, Motion.quick())
    val wiggle by animateFloatAsState(if (hovered) -10f else 0f, Motion.bouncy())
    Row(
        Modifier.fillMaxWidth().height(NAV_ITEM_H).clip(RoundedCornerShape(16.dp))
            .background(Color.White.copy(alpha = 0.04f * hoverBg))
            .pointerHoverIcon(PointerIcon.Hand)
            .hoverSound().clickable(interaction, null, onClick = onClick)
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = if (active) c.accent else color, modifier = Modifier.offset(x = shift).size(21.dp).graphicsLayer { rotationZ = wiggle; val k = 1f + wiggle / -100f; scaleX = k; scaleY = k })
        Spacer(Modifier.width(12.dp))
        Text(label, style = MaterialTheme.typography.labelLarge.copy(fontWeight = if (active) FontWeight.SemiBold else FontWeight.Medium), color = color, modifier = Modifier.offset(x = shift))
    }
}

/** Compact connect toggle at the bottom of the sidebar, with a live pulse when protected. */
@Composable
private fun StatusChip(state: VpnState, onClick: () -> Unit) {
    val c = Ghost.colors
    val connected = state is VpnState.Connected
    val busy = state == VpnState.Connecting || state == VpnState.Disconnecting
    val color by animateColorAsState(
        when {
            connected -> c.ok
            busy -> c.warn
            state is VpnState.Failed -> c.bad
            else -> c.ink3
        },
        Motion.quick(),
    )
    val t = rememberInfiniteTransition()
    val pulse by t.animateFloat(0f, 1f, infiniteRepeatable(tween(1600), RepeatMode.Restart))
    val interaction = remember { MutableInteractionSource() }
    Row(
        Modifier.fillMaxWidth().outerShadow(RoundedCornerShape(16.dp), 0.8f).clip(RoundedCornerShape(16.dp))
            .background(color.copy(alpha = 0.10f))
            .border(1.dp, color.copy(alpha = 0.28f), RoundedCornerShape(16.dp))
            .sheen(interaction, color, 0.16f)
            .pointerHoverIcon(PointerIcon.Hand)
            .hoverSound().clickable(interaction, null, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(contentAlignment = Alignment.Center) {
            if (connected || busy) Box(
                Modifier.size(10.dp).graphicsLayer { scaleX = 1f + pulse * 1.6f; scaleY = 1f + pulse * 1.6f; alpha = 1f - pulse }
                    .clip(CircleShape).background(color),
            )
            Box(Modifier.size(10.dp).clip(CircleShape).background(color))
        }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(
                when {
                    connected -> "Защищено"
                    busy -> "Подождите…"
                    state is VpnState.Failed -> "Ошибка"
                    else -> "Выключено"
                },
                style = MaterialTheme.typography.labelLarge, color = c.ink,
            )
            Text(if (connected) "Нажми, чтобы отключить" else "Нажми, чтобы подключить", style = MaterialTheme.typography.bodySmall)
        }
    }
}
