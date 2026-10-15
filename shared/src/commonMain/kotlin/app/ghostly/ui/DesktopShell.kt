package app.ghostly.ui

import app.ghostly.ui.components.pageFade
import app.ghostly.ui.components.plate
import app.ghostly.ui.components.softShadow
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material.icons.outlined.Tune
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
import app.ghostly.ui.components.ambientFloat
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
import androidx.compose.material.icons.rounded.AccountCircle
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
import androidx.compose.ui.draw.drawBehind
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
import app.ghostly.i18n.tr

private val NAV_ITEM_H = 48.dp
private val NAV_GAP = 6.dp

/**
 * Desktop / tablet layout: the night fills the whole window, a floating plate holds the navigation and the
 * page stands in the rest. Tabs change with the same camera move as on the phone.
 */
@Composable
internal fun DesktopShell(controller: GhostlyController, tabs: List<Tab>, tab: Tab, onTab: (Tab) -> Unit, onAdd: () -> Unit) {
    Row(Modifier.fillMaxSize()) {
        Sidebar(controller, tabs, tab, onTab, onAdd)
        val heading = rememberHeading(tab)
        AnimatedContent(
            targetState = tab,
            transitionSpec = { travelSpec() },
            modifier = Modifier.weight(1f).fillMaxHeight(),
        ) { t ->
          Box(Modifier.fillMaxSize().travel(this@AnimatedContent, heading)) {
            when (t) {
                Tab.HOME -> HomeDesktop(controller, onAdd, onServers = { onTab(Tab.SERVERS) })
                Tab.SERVERS -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
                    Box(Modifier.widthIn(max = 980.dp).fillMaxSize()) {
                        ServersScreen(controller, PaddingValues(top = 20.dp, bottom = 24.dp), onAdd = onAdd)
                    }
                }
                Tab.ACCOUNT -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
                    Box(Modifier.widthIn(max = 1040.dp).fillMaxSize()) {
                        app.ghostly.ui.screens.cabinet.CabinetScreen(controller, PaddingValues(top = 20.dp, bottom = 24.dp), wide = true)
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
private fun Sidebar(controller: GhostlyController, tabs: List<Tab>, tab: Tab, onTab: (Tab) -> Unit, onAdd: () -> Unit) {
    val c = Ghost.colors
    val state by controller.state.collectAsState()
    val profiles by controller.profiles.collectAsState()
    val connected = state is VpnState.Connected

    val panel = RoundedCornerShape(26.dp)
    Column(
        Modifier.padding(start = 14.dp, top = 14.dp, bottom = 14.dp).width(236.dp).fillMaxHeight()
            .softShadow(panel, 1f)
            .plate(panel, strong = true)
            .padding(horizontal = 14.dp, vertical = 18.dp),
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
        val index = tabs.indexOf(tab).coerceAtLeast(0)
        val indicatorY by animateDpAsState((NAV_ITEM_H + NAV_GAP) * index, Motion.bouncy())
        Box {
            Box(
                Modifier.offset(y = indicatorY).fillMaxWidth().height(NAV_ITEM_H).clip(RoundedCornerShape(NAV_ITEM_H / 2))
                    .background(androidx.compose.ui.graphics.lerp(Color.White.copy(alpha = 0.08f), c.accent.copy(alpha = 0.18f), c.lit)),
            )
            Column(verticalArrangement = Arrangement.spacedBy(NAV_GAP)) {
                tabs.forEach { t ->
                    val icon = when (t) {
                        Tab.HOME -> Icons.Rounded.Home
                        Tab.SERVERS -> Icons.Outlined.Public
                        Tab.ACCOUNT -> Icons.Outlined.Person
                        Tab.SETTINGS -> Icons.Outlined.Tune
                    }
                    NavItem(icon, t.title, tab == t) { onTab(t) }
                }
            }
        }
        Spacer(Modifier.height(14.dp))
        NavItem(Icons.Rounded.Add, tr("Добавить подписку"), false, tint = c.accent, onClick = onAdd)

        Spacer(Modifier.weight(1f))

        // Subscription mini card
        val profile = remember(profiles) { controller.selectedServer()?.let { controller.profileOf(it.id) } ?: profiles.firstOrNull() }
        profile?.info?.let { info ->
            Column(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(Color.White.copy(alpha = 0.04f))
                    .border(1.dp, c.hairline, RoundedCornerShape(18.dp)).spotlight(c.accent, 140.dp).padding(14.dp),
            ) {
                app.ghostly.ui.components.FlagText(profile.name, style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(2.dp))
                Text(
                    if (info.unlimitedTime) tr("Бессрочно") else Format.expiryPhrase(info.expire, GhostlyController.now()),
                    style = MaterialTheme.typography.bodySmall,
                )
                if (info.pools.isNotEmpty()) {
                    info.pools.forEach { p ->
                        Spacer(Modifier.height(10.dp))
                        Text(p.title, style = MaterialTheme.typography.bodySmall)
                        Spacer(Modifier.height(4.dp))
                        GlowBar(if (p.total > 0) p.used.toFloat() / p.total else 0f, if (p.isWhitelist) c.accent else c.ok)
                        Spacer(Modifier.height(3.dp))
                        Text(if (p.total > 0) tr("{0} из {1}", Format.bytes(p.used), Format.bytes(p.total)) else tr("без лимита"), style = MaterialTheme.typography.bodySmall)
                    }
                } else if (!info.unlimitedTraffic) {
                    Spacer(Modifier.height(10.dp))
                    GlowBar(info.used.toFloat() / info.total, c.accent)
                    Spacer(Modifier.height(6.dp))
                    Text(tr("{0} из {1}", Format.bytes(info.used), Format.bytes(info.total)), style = MaterialTheme.typography.bodySmall)
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
        Modifier.fillMaxWidth().height(NAV_ITEM_H).clip(RoundedCornerShape(NAV_ITEM_H / 2))
            .background(Color.White.copy(alpha = 0.05f * hoverBg))
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
    val pulse by ambientFloat(0f, 1f, 1600)
    val interaction = remember { MutableInteractionSource() }
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp))
            .background(Color.White.copy(alpha = 0.04f))
            .border(1.dp, color.copy(alpha = 0.35f), RoundedCornerShape(18.dp))
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
        AnimatedContent(
            when {
                connected -> tr("Защищено") to tr("Нажми, чтобы отключить")
                busy -> tr("Подождите…") to tr("Нажми, чтобы подключить")
                state is VpnState.Failed -> tr("Ошибка") to tr("Нажми, чтобы подключить")
                else -> tr("Выключено") to tr("Нажми, чтобы подключить")
            },
            transitionSpec = { (fadeIn(tween(220, delayMillis = 60)) + slideInVertically(tween(300)) { it / 3 }) togetherWith fadeOut(tween(120)) },
            modifier = Modifier.weight(1f),
            label = "chip",
        ) { (head, hint) ->
            Column {
                Text(head, style = MaterialTheme.typography.labelLarge, color = c.ink)
                Text(hint, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}
