package app.ghostly.ui.screens

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.tween
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AccountTree
import androidx.compose.material.icons.rounded.ArrowDownward
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.PowerSettingsNew
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.SupportAgent
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.ghostly.core.GhostlyController
import app.ghostly.core.model.Profile
import app.ghostly.core.model.Server
import app.ghostly.core.vpn.Haptic
import app.ghostly.core.vpn.Traffic
import app.ghostly.core.vpn.VpnState
import app.ghostly.ui.Format
import app.ghostly.ui.Tab
import app.ghostly.ui.components.AccentButton
import app.ghostly.ui.components.AnnouncementCard
import app.ghostly.ui.components.FlagCircle
import app.ghostly.ui.components.GlassCard
import app.ghostly.ui.components.GlowBar
import app.ghostly.ui.components.IconBubble
import app.ghostly.ui.components.KineticText
import app.ghostly.ui.components.OrbState
import app.ghostly.ui.components.RingProgress
import app.ghostly.ui.components.RollingText
import app.ghostly.ui.components.SoftButton
import app.ghostly.ui.components.Sparkline
import app.ghostly.ui.components.appear
import app.ghostly.ui.components.hoverSound
import app.ghostly.ui.components.nightColor
import app.ghostly.ui.components.nightGhost
import app.ghostly.ui.components.ringLeft
import app.ghostly.ui.countryName
import app.ghostly.ui.protocolLabel
import app.ghostly.ui.tabSky
import app.ghostly.ui.theme.Ghost
import app.ghostly.ui.theme.Motion
import app.ghostly.ui.title
import app.ghostly.ui.transportLabel
import kotlinx.coroutines.delay
import app.ghostly.i18n.tr

/** Everything the home screen shows, gathered once and shared by the phone and desktop layouts. */
@Stable
class HomeModel(
    val state: VpnState,
    val orb: OrbState,
    val traffic: Traffic,
    val server: Server?,
    val profile: Profile?,
    val ping: Long?,
    val pinging: Boolean,
    val now: Long,
    val down: SnapshotStateList<Float>,
    val up: SnapshotStateList<Float>,
    /** mihomo: how many selector groups drive the traffic (0 when Xray or none). */
    val mihomoGroups: Int,
)

@Composable
fun rememberHome(controller: GhostlyController): HomeModel {
    val state by controller.state.collectAsState()
    val traffic by controller.backend.traffic.collectAsState()
    val selectedId by controller.selectedServerId.collectAsState()
    val profiles by controller.profiles.collectAsState()
    val pings by controller.pings.collectAsState()
    val pinging by controller.pinging.collectAsState()
    val liveGroups by controller.mihomoGroups.groups.collectAsState()
    val staticGroups by controller.staticMihomoGroups.collectAsState()
    val settings by controller.settings.collectAsState()

    val server = remember(selectedId, profiles) { controller.selectedServer() }
    val profile = remember(server, profiles) { server?.let { controller.profileOf(it.id) } ?: profiles.firstOrNull() }
    val orb = when (state) {
        is VpnState.Connected -> OrbState.CONNECTED
        VpnState.Connecting, VpnState.Disconnecting -> OrbState.CONNECTING
        is VpnState.Failed -> OrbState.ERROR
        VpnState.Idle -> OrbState.IDLE
    }
    val down = remember { mutableStateListOf<Float>() }
    val up = remember { mutableStateListOf<Float>() }
    LaunchedEffect(traffic) {
        down.add(traffic.downSpeed.toFloat()); if (down.size > 48) down.removeAt(0)
        up.add(traffic.upSpeed.toFloat()); if (up.size > 48) up.removeAt(0)
    }
    LaunchedEffect(orb) { if (orb != OrbState.CONNECTED) { down.clear(); up.clear() } }
    var now by remember { mutableLongStateOf(GhostlyController.now()) }
    LaunchedEffect(Unit) { while (true) { now = GhostlyController.now(); delay(1000) } }
    return HomeModel(
        state, orb, traffic, server, profile, pings[server?.id]?.ms, server?.id in pinging, now, down, up,
        // Selectors are mihomo's: right after a switch to Xray the old groups must not linger on the card.
        mihomoGroups = if (settings.core == app.ghostly.core.model.CoreType.MIHOMO) liveGroups.ifEmpty { staticGroups }.size else 0,
    )
}

/** The ghost is the big button: what a tap on it does, for screen readers. */
private fun ghostLabel(m: HomeModel): String = if (m.orb == OrbState.CONNECTED || m.orb == OrbState.CONNECTING) tr("Отключить") else tr("Подключить")

private fun toggle(controller: GhostlyController) {
    controller.haptic(Haptic.HEAVY)
    controller.toggle()
}

// ============================================================================ phone

/**
 * Home: the ghost hangs in the sky over its reflection; below the horizon the tunnel's state with its
 * switch, then two plates: where the traffic goes and how long the subscription lasts. The sky takes the
 * height the rest leaves on the screen, so the whole composition fills it without scrolling.
 */
@Composable
fun HomeScreen(controller: GhostlyController, onPickServer: () -> Unit, contentPadding: PaddingValues) {
    val m = rememberHome(controller)
    val density = LocalDensity.current
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val viewport = maxHeight - contentPadding.calculateTopPadding() - contentPadding.calculateBottomPadding()
        var belowH by remember { mutableStateOf(230.dp) }
        val skyTarget = (viewport - belowH).coerceIn(330.dp, 560.dp)
        val skyH by animateDpAsState(skyTarget, Motion.soft())
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(contentPadding).padding(horizontal = 18.dp),
        ) {
            // Above the horizon: the header, banners, and the ghost in what is left of the sky.
            Column(Modifier.fillMaxWidth().height(skyH).tabSky(Tab.HOME)) {
                HomeHeader(m, controller)
                app.ghostly.ui.components.UpdateBanner(controller, Modifier.padding(top = 12.dp))
                AnnouncementCard(controller, Modifier.padding(top = 12.dp))
                BoxWithConstraints(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                    val ghost = minOf(maxHeight * 0.8f, maxWidth * 0.58f, 236.dp).coerceAtLeast(96.dp)
                    Box(Modifier.size(ghost).nightGhost(Tab.HOME, ghostLabel(m)) { toggle(controller) })
                }
            }
            // On the water.
            Column(Modifier.fillMaxWidth().onSizeChanged { belowH = with(density) { it.height.toDp() } }) {
                Spacer(Modifier.height(26.dp))
                StatusBlock(m, controller, Modifier.padding(start = 4.dp))
                Spacer(Modifier.height(24.dp))
                HomePlates(m, controller, onPickServer)
                m.profile?.notice?.let { n ->
                    Spacer(Modifier.height(12.dp))
                    GlassCard(Modifier.fillMaxWidth(), padding = 14.dp) { ProviderNoticeBlock(n, controller) }
                }
                Spacer(Modifier.height(14.dp))
            }
        }
    }
}

@Composable
private fun HomeHeader(m: HomeModel, controller: GhostlyController) {
    Row(Modifier.fillMaxWidth().padding(top = 6.dp).heightIn(min = 44.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(
            "Ghostly",
            style = MaterialTheme.typography.titleLarge.copy(fontSize = 21.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.4).sp),
            modifier = Modifier.weight(1f).padding(start = 4.dp),
        )
        app.ghostly.ui.components.NoticeBell(controller)
        m.profile?.supportUrl?.let { url ->
            Spacer(Modifier.width(8.dp))
            IconBubble(Icons.Rounded.SupportAgent, onClick = { controller.platform.openUrl(url) })
        }
    }
}

/** The two plates: the server (or the selectors) and the subscription. */
@Composable
private fun HomePlates(m: HomeModel, controller: GhostlyController, onPickServer: () -> Unit, modifier: Modifier = Modifier) {
    val sub = m.profile?.takeIf { it.url != null || it.info != null }
    Row(modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        ServerPlate(m, onPickServer, Modifier.weight(1f).fillMaxHeight().appear(0))
        if (sub != null) SubscriptionPlate(sub, m.now, controller, Modifier.weight(1f).fillMaxHeight().appear(1))
    }
}

// ============================================================================ desktop

/**
 * Wide layout: the main column has the shot (the ghost in its sky, the tunnel's state and the plates on the
 * water), the servers stand at full height on the right as before. The night still fills the window behind.
 */
@Composable
fun HomeDesktop(controller: GhostlyController, onAdd: () -> Unit, onServers: () -> Unit = {}) {
    val m = rememberHome(controller)
    val c = Ghost.colors
    val density = LocalDensity.current
    BoxWithConstraints(Modifier.fillMaxSize().padding(start = 28.dp, end = 24.dp, top = 22.dp, bottom = 22.dp)) {
        // The servers panel narrows first and hides when the main column would drop under 480 dp (they stay
        // one click away on the «Серверы» tab).
        val panel = (maxWidth * 0.36f).coerceIn(320.dp, 400.dp)
        val showPanel = maxWidth - panel - 22.dp >= 480.dp
        Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(22.dp)) {
            BoxWithConstraints(Modifier.weight(1f).fillMaxHeight()) {
                var belowH by remember { mutableStateOf(250.dp) }
                val skyH by animateDpAsState((maxHeight - belowH).coerceIn(250.dp, 430.dp), Motion.soft())
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                    Column(Modifier.fillMaxWidth().height(skyH).tabSky(Tab.HOME)) {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(greeting(m.now, controller.platform.utcOffsetMinutes()), style = MaterialTheme.typography.headlineMedium)
                                m.profile?.let { app.ghostly.ui.components.FlagText(it.name, style = MaterialTheme.typography.bodyMedium, color = c.ink2, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                            }
                            app.ghostly.ui.components.NoticeBell(controller)
                            m.profile?.supportUrl?.let { url ->
                                Spacer(Modifier.width(8.dp))
                                IconBubble(Icons.Rounded.SupportAgent, onClick = { controller.platform.openUrl(url) })
                            }
                        }
                        AnnouncementCard(controller, Modifier.widthIn(max = 760.dp).padding(top = 12.dp))
                        BoxWithConstraints(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                            val ghost = minOf(maxHeight * 0.86f, 250.dp).coerceAtLeast(96.dp)
                            Box(Modifier.size(ghost).nightGhost(Tab.HOME, ghostLabel(m)) { toggle(controller) })
                        }
                    }
                    Column(Modifier.fillMaxWidth().onSizeChanged { belowH = with(density) { it.height.toDp() } }) {
                        Spacer(Modifier.height(22.dp))
                        StatusBlock(m, controller, Modifier.padding(start = 4.dp))
                        Spacer(Modifier.height(20.dp))
                        HomePlates(m, controller, onPickServer = onServers)
                        AnimatedVisibility(m.orb == OrbState.CONNECTED, enter = fadeIn() + expandVertically(), exit = fadeOut() + shrinkVertically()) {
                            Row(Modifier.fillMaxWidth().padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                SpeedPlate(tr("Загрузка"), Icons.Rounded.ArrowDownward, m.traffic.downSpeed, m.traffic.downTotal, m.down, c.ok, Modifier.weight(1f))
                                SpeedPlate(tr("Отдача"), Icons.Rounded.ArrowUpward, m.traffic.upSpeed, m.traffic.upTotal, m.up, c.accent, Modifier.weight(1f))
                            }
                        }
                        m.profile?.notice?.let { n ->
                            Spacer(Modifier.height(12.dp))
                            GlassCard(Modifier.fillMaxWidth(), padding = 14.dp) { ProviderNoticeBlock(n, controller) }
                        }
                        Spacer(Modifier.height(8.dp))
                    }
                }
            }
            if (showPanel) GlassCard(Modifier.width(panel).fillMaxHeight().appear(1), padding = 0.dp, strong = true) {
                Text(tr("Серверы"), style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(start = 20.dp, end = 12.dp, top = 18.dp))
                ServersScreen(controller, PaddingValues(bottom = 12.dp), onAdd = onAdd, showHeader = false, compact = true)
            }
        }
    }
}

/** Time-of-day greeting for the desktop header (local clock). */
private fun greeting(nowMs: Long, offsetMin: Int): String {
    val h = (((nowMs / 60_000 + offsetMin) / 60) % 24).toInt()
    return when (h) {
        in 5..11 -> tr("Доброе утро")
        in 12..17 -> tr("Добрый день")
        in 18..22 -> tr("Добрый вечер")
        else -> tr("Доброй ночи")
    }
}

// ============================================================================ pieces

/** What the line under the status headline shows. */
private enum class StatusLine { SINGING, CONNECTED, FAILED, CONNECTING, DISCONNECTING, IDLE }

/** Which switch stands beside the headline. */
private enum class SwitchKind { CONNECT, DISCONNECT, CANCEL, WAIT, RETRY }

/**
 * Under the horizon: what the tunnel is doing in big letters with its switch beside them (off it is the one
 * coloured thing on the screen, on it turns into a quiet plate), and a line about it across the full width.
 * Every change moves: the headline types itself, the switch morphs, the line slides and the block's height
 * follows smoothly — a song starting or a connection dropping never makes the layout jump.
 */
@Composable
private fun StatusBlock(m: HomeModel, controller: GhostlyController, modifier: Modifier = Modifier) {
    val c = Ghost.colors
    val stage = app.ghostly.ui.stage.LocalStage.current
    val singing = stage?.track?.value?.playing == true
    val title = when (m.orb) {
        OrbState.IDLE -> tr("Не подключено")
        OrbState.CONNECTING -> if (m.state == VpnState.Disconnecting) tr("Отключаюсь…") else tr("Подключаюсь…")
        OrbState.CONNECTED -> tr("Под защитой")
        OrbState.ERROR -> tr("Не удалось подключиться")
    }
    Column(modifier.fillMaxWidth().animateContentSize(androidx.compose.animation.core.spring(dampingRatio = 0.9f, stiffness = 260f))) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            BoxWithConstraints(Modifier.weight(1f)) {
                val room = maxWidth
                FittedKinetic(title, MaterialTheme.typography.displayMedium, if (m.orb == OrbState.ERROR) c.bad else c.ink, room, 22.sp)
            }
            Spacer(Modifier.width(12.dp))
            val kind = when {
                m.orb == OrbState.CONNECTED -> SwitchKind.DISCONNECT
                m.state == VpnState.Connecting -> SwitchKind.CANCEL
                m.state == VpnState.Disconnecting -> SwitchKind.WAIT
                m.orb == OrbState.ERROR -> SwitchKind.RETRY
                else -> SwitchKind.CONNECT
            }
            AnimatedContent(
                kind,
                transitionSpec = {
                    (fadeIn(tween(220, delayMillis = 70)) + scaleIn(tween(380, easing = Motion.Ease), initialScale = 0.86f)) togetherWith
                        (fadeOut(tween(120)) + scaleOut(tween(180), targetScale = 0.9f)) using SizeTransform(clip = false)
                },
                contentAlignment = Alignment.CenterEnd,
                label = "switch",
            ) { k ->
                val go = { toggle(controller) }
                when (k) {
                    SwitchKind.DISCONNECT -> SoftButton(tr("Отключить"), go, icon = Icons.Rounded.PowerSettingsNew)
                    SwitchKind.CANCEL -> SoftButton(tr("Отменить"), go, icon = Icons.Rounded.Close)
                    SwitchKind.WAIT -> SoftButton(tr("Отключаю…"), {}, tint = c.ink3)
                    SwitchKind.RETRY -> AccentButton(tr("Ещё раз"), go, icon = Icons.Rounded.Refresh)
                    SwitchKind.CONNECT -> AccentButton(tr("Подключить"), go, icon = Icons.Rounded.PowerSettingsNew, enabled = m.server != null || m.mihomoGroups > 0)
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        val lineKind = when {
            singing && stage != null && m.state !is VpnState.Failed -> StatusLine.SINGING
            m.state is VpnState.Connected -> StatusLine.CONNECTED
            m.state is VpnState.Failed -> StatusLine.FAILED
            m.state == VpnState.Connecting -> StatusLine.CONNECTING
            m.state == VpnState.Disconnecting -> StatusLine.DISCONNECTING
            else -> StatusLine.IDLE
        }
        val line = MaterialTheme.typography.bodyLarge.copy(fontSize = 15.sp, color = c.ink2, fontFeatureSettings = "tnum")
        AnimatedContent(
            lineKind,
            transitionSpec = {
                (fadeIn(tween(260, delayMillis = 90)) + slideInVertically(tween(420, easing = Motion.Ease)) { it / 3 }) togetherWith
                    (fadeOut(tween(140)) + slideOutVertically(tween(220)) { -it / 4 }) using SizeTransform(clip = false)
            },
            modifier = Modifier.fillMaxWidth(),
            label = "line",
        ) { k ->
            when (k) {
                // The song: the ghost sings it across the full width.
                StatusLine.SINGING -> Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    stage?.let { app.ghostly.ui.stage.SungLine(it) }
                }
                StatusLine.CONNECTED -> Column {
                    val since = (m.state as? VpnState.Connected)?.since
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(placeName(m) + " · ", style = line, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                        if (since != null) RollingText(Format.duration(m.now - since), style = line)
                    }
                    Spacer(Modifier.height(6.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        val small = MaterialTheme.typography.bodySmall.copy(fontFeatureSettings = "tnum")
                        Icon(Icons.Rounded.ArrowDownward, null, tint = c.ok, modifier = Modifier.size(13.dp))
                        Spacer(Modifier.width(3.dp))
                        RollingText(Format.speed(m.traffic.downSpeed), style = small, color = c.ink3)
                        Spacer(Modifier.width(14.dp))
                        Icon(Icons.Rounded.ArrowUpward, null, tint = c.accent, modifier = Modifier.size(13.dp))
                        Spacer(Modifier.width(3.dp))
                        RollingText(Format.speed(m.traffic.upSpeed), style = small, color = c.ink3)
                    }
                }
                StatusLine.FAILED -> Column {
                    val message = (m.state as? VpnState.Failed)?.message.orEmpty()
                    Text(message, style = MaterialTheme.typography.bodySmall, color = c.ink3, maxLines = 3, overflow = TextOverflow.Ellipsis)
                    Row(
                        Modifier.padding(top = 6.dp).clip(RoundedCornerShape(10.dp)).hoverSound()
                            .clickable { controller.platform.copyToClipboard(message); controller.haptic() }
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Icons.Rounded.ContentCopy, null, tint = c.accent, modifier = Modifier.size(14.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(tr("Скопировать ошибку"), style = MaterialTheme.typography.labelMedium, color = c.accent)
                    }
                }
                StatusLine.CONNECTING -> Text(tr("Устанавливаю туннель"), style = line, maxLines = 2)
                StatusLine.DISCONNECTING -> Text(placeName(m), style = line, maxLines = 1, overflow = TextOverflow.Ellipsis)
                StatusLine.IDLE -> Text(
                    if (controller.platform.isDesktop) tr("Нажми на призрака, чтобы включить") else tr("Коснись призрака, чтобы включить"),
                    style = line, maxLines = 2,
                )
            }
        }
    }
}

/** A server's name without the decoration a plate has no room for: leading emoji, a note in brackets. */
private fun plainName(name: String): String =
    name.replace(Regex("^[^\\p{L}\\p{N}]+"), "").substringBefore(" (").trim().ifEmpty { name }

/** One line of text that cross-fades (with a small rise) when it changes instead of flipping at once. */
@Composable
private fun SwapText(text: String, style: TextStyle, color: Color = Color.Unspecified) {
    AnimatedContent(
        text,
        transitionSpec = {
            (fadeIn(tween(220, delayMillis = 60)) + slideInVertically(tween(300, easing = Motion.Ease)) { it / 3 }) togetherWith
                fadeOut(tween(120)) using SizeTransform(clip = false)
        },
        label = "swap",
    ) { t -> Text(t, style = style, color = color, maxLines = 1, overflow = TextOverflow.Ellipsis) }
}

/** "Нидерланды" for a server with a flag, otherwise its own title. */
private fun placeName(m: HomeModel): String {
    val s = m.server ?: return tr("Сервер не выбран")
    if (m.mihomoGroups > 0) return tr("Селекторы")
    val t = s.title()
    return t.flag?.let { countryName(it) } ?: plainName(t.title)
}

/** The status headline, letters flying in; shrinks to stay on one line beside the switch. */
@Composable
private fun FittedKinetic(text: String, style: TextStyle, color: Color, maxWidth: Dp, min: androidx.compose.ui.unit.TextUnit) {
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val fitted = remember(text, style, maxWidth) {
        fun at(size: Float) = style.copy(fontSize = size.sp, lineHeight = (size * 1.08f).sp, letterSpacing = (size * -0.03f).sp)
        // KineticText sets every letter on its own (no kerning between them): measure it the same way.
        fun width(size: Float): Int = text.sumOf { ch -> measurer.measure(ch.toString(), at(size), maxLines = 1, softWrap = false).size.width }
        var size = style.fontSize.value
        val limit = with(density) { maxWidth.toPx() }
        while (size > min.value && width(size) > limit) size -= 1f
        at(size) to (width(size) <= limit)
    }
    // Too long even small (a long error, a long language): two lines of plain text rather than a cut word.
    if (fitted.second) KineticText(text, style = fitted.first, color = color)
    else Text(text, style = fitted.first, color = color, maxLines = 2, overflow = TextOverflow.Ellipsis)
}

/** Where the traffic goes: the server (flag, country, ping) or, on mihomo, its selectors. */
@Composable
private fun ServerPlate(m: HomeModel, onClick: () -> Unit, modifier: Modifier) {
    val c = Ghost.colors
    val server = m.server
    GlassCard(modifier, shape = RoundedCornerShape(22.dp), padding = 14.dp, onClick = onClick) {
        Row(Modifier.fillMaxHeight().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
            val t = server?.title()
            when {
                m.mihomoGroups > 0 -> Bubble(Icons.Rounded.AccountTree)
                t?.flag != null -> FlagCircle(t.flag, 30.dp, Modifier.nightColor())
                server?.isAuto == true -> Bubble(Icons.Rounded.AutoAwesome)
                else -> Bubble(Icons.Rounded.Shield)
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                val name = when {
                    m.mihomoGroups > 0 -> tr("Селекторы")
                    server == null -> tr("Не выбран")
                    t?.flag != null -> countryName(t.flag)
                    else -> plainName(t?.title.orEmpty())
                }
                SwapText(name, MaterialTheme.typography.titleSmall.copy(fontSize = 15.sp))
                Spacer(Modifier.height(3.dp))
                val sub = when {
                    m.mihomoGroups > 0 -> tr("группы: {0}", m.mihomoGroups)
                    server == null -> tr("Выбрать сервер")
                    else -> {
                        val ping = when {
                            m.pinging -> "···"
                            m.ping != null && m.ping > 0 -> tr("{0} мс", m.ping)
                            else -> null
                        }
                        // The country is the title already: the line names the server itself (or its protocol).
                        val what = if (t?.flag != null) plainName(listOfNotNull(t.title, t.subtitle).joinToString(" · ")) else (server.transportLabel() ?: server.protocolLabel())
                        listOfNotNull(ping, what.ifBlank { null }).joinToString(" · ")
                    }
                }
                SwapText(sub, MaterialTheme.typography.bodySmall.copy(fontSize = 12.5.sp, fontFeatureSettings = "tnum"))
            }
        }
    }
}

@Composable
private fun Bubble(icon: ImageVector) {
    val c = Ghost.colors
    Box(Modifier.size(30.dp).clip(CircleShape).background(lerp(Color.White.copy(alpha = 0.08f), c.accent.copy(alpha = 0.18f), c.lit)), contentAlignment = Alignment.Center) {
        Icon(icon, null, tint = lerp(c.ink2, c.accent, c.lit), modifier = Modifier.size(16.dp))
    }
}

/** How long the subscription lasts: a small ring and the date (or the traffic when there is no end). */
@Composable
private fun SubscriptionPlate(profile: Profile, now: Long, controller: GhostlyController, modifier: Modifier) {
    val c = Ghost.colors
    val nav = LocalSubscriptionNav.current
    val info = profile.info
    GlassCard(modifier, shape = RoundedCornerShape(22.dp), padding = 14.dp, onClick = { nav.open(profile.id) }) {
        Row(Modifier.fillMaxHeight().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
            val left = (info?.expire ?: 0) * 1000 - now
            val tone = when {
                info == null || info.unlimitedTime -> lerp(Color(0xFFBFC4CF), c.accent, c.lit)
                left <= 0 -> c.bad
                left < 3 * 86_400_000L -> c.warn
                else -> lerp(Color(0xFFBFC4CF), c.accent, c.lit)
            }
            val (num, unit, frac) = ringLeft(left)
            RingProgress(if (info == null || info.unlimitedTime) 1f else frac, tone, Modifier.size(34.dp), stroke = 3.dp)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                val head = when {
                    info == null -> profile.name
                    info.unlimitedTime -> tr("Бессрочно")
                    left <= 0 -> tr("Истекла")
                    else -> "$num $unit"
                }
                SwapText(head, MaterialTheme.typography.titleSmall.copy(fontSize = 15.sp), if (tone == c.bad || tone == c.warn) tone else c.ink)
                Spacer(Modifier.height(3.dp))
                val sub = when {
                    info == null -> Format.updatedAgo(profile.updatedAt, now)
                    !info.unlimitedTime && left > 0 -> tr("до {0}", Format.dateShort(info.expire, controller.platform.utcOffsetMinutes()))
                    info.unlimitedTraffic -> "${Format.bytes(info.used)} · ∞"
                    else -> tr("{0} из {1}", Format.bytes(info.used), Format.bytes(info.total))
                }
                Text(sub, style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.5.sp), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

/** Live traffic one way (desktop): the speed rolling, the total, a sparkline. */
@Composable
private fun SpeedPlate(label: String, icon: ImageVector, speed: Long, total: Long, history: List<Float>, color: Color, modifier: Modifier) {
    val c = Ghost.colors
    GlassCard(modifier, shape = RoundedCornerShape(22.dp), padding = 14.dp) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, tint = color, modifier = Modifier.size(15.dp))
            Spacer(Modifier.width(6.dp))
            Text(label, style = MaterialTheme.typography.labelMedium, color = c.ink3, modifier = Modifier.weight(1f))
            Text(tr("всего {0}", Format.bytes(total)), style = MaterialTheme.typography.bodySmall)
        }
        Spacer(Modifier.height(6.dp))
        RollingText(Format.speed(speed), style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold, fontFeatureSettings = "tnum"), color = c.ink)
        Spacer(Modifier.height(6.dp))
        Sparkline(history, color, Modifier.fillMaxWidth().height(30.dp))
    }
}

/** One traffic pool: title, used / total and a glowing bar (mint for regular, violet for white lists). */
@Composable
fun PoolBar(p: app.ghostly.core.model.TrafficPool) {
    val c = Ghost.colors
    val frac = if (p.total > 0) p.used.toFloat() / p.total else 0f
    val color = when {
        frac > 0.95f -> c.bad
        frac > 0.8f -> c.warn
        p.isWhitelist -> c.accent
        else -> c.ok
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(color))
        Spacer(Modifier.width(8.dp))
        Text(p.title, style = MaterialTheme.typography.labelMedium, modifier = Modifier.weight(1f))
        Text(
            if (p.total > 0) tr("{0} из {1}", Format.bytes(p.used), Format.bytes(p.total)) else "${Format.bytes(p.used)} · ∞",
            style = MaterialTheme.typography.labelMedium, color = c.ink2,
        )
    }
    Spacer(Modifier.height(6.dp))
    GlowBar(frac, color)
}
