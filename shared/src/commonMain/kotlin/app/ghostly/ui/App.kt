package app.ghostly.ui

import androidx.compose.animation.AnimatedContent
import app.ghostly.ui.screens.predictiveCard
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ContentPaste
import androidx.compose.material.icons.rounded.Dns
import androidx.compose.material.icons.rounded.QrCodeScanner
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.ShoppingBag
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import app.ghostly.core.GhostlyController
import app.ghostly.core.vpn.VpnState
import app.ghostly.ui.components.AccentButton
import app.ghostly.ui.components.AuroraBackground
import app.ghostly.ui.components.GhostMark
import app.ghostly.ui.components.SoftButton
import app.ghostly.ui.components.pressScale
import app.ghostly.ui.components.sheen
import app.ghostly.ui.screens.HomeScreen
import app.ghostly.ui.screens.ServersScreen
import app.ghostly.ui.screens.SettingsScreen
import app.ghostly.ui.theme.Ghost
import app.ghostly.ui.theme.GhostlyTheme
import app.ghostly.ui.theme.Motion
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

const val SITE_URL = "https://ghostlinknex.online"

internal enum class Tab(val title: String) { HOME("Главная"), SERVERS("Серверы"), SETTINGS("Настройки") }

@OptIn(ExperimentalMaterial3Api::class, androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
fun GhostlyApp(controller: GhostlyController) {
    val settings by controller.settings.collectAsState()
    val onboarded by controller.onboarded.collectAsState()
    val state by controller.state.collectAsState()

    val design by controller.design.tokens.collectAsState()
    // A seasonal accent from the server applies only while the user keeps the default colour.
    // Monet (the wallpaper's colour) wins over both when the user turned it on.
    val accent = settings.monet.takeIf { it }?.let { controller.platform.systemAccent() }
        ?: design.accentArgb()?.takeIf { settings.accent == app.ghostly.core.model.ThemeAccent.GHOST } ?: settings.accent.argb
    GhostlyTheme(accent, settings.reduceMotion) { androidx.compose.runtime.CompositionLocalProvider(app.ghostly.ui.components.LocalHaptic provides { controller.haptic(app.ghostly.core.vpn.Haptic.TICK) }, app.ghostly.ui.components.LocalHapticOf provides { k -> controller.haptic(k) }, app.ghostly.ui.theme.LocalDesign provides design) {
        var tab by rememberSaveable { mutableStateOf(Tab.HOME) }
        var addOpen by remember { mutableStateOf(false) }
        var pickerOpen by remember { mutableStateOf(false) }
        // The subscription page (all the provider's headers, actions, every subscription): which one is open.
        var subOpen by remember { mutableStateOf<String?>(null) }
        val subNav = remember { app.ghostly.ui.screens.SubscriptionNav(open = { subOpen = it }, add = { subOpen = null; pickerOpen = false; addOpen = true }) }
        var toast by remember { mutableStateOf<String?>(null) }
        var wideLayout by remember { mutableStateOf(false) }

        // Android back gesture: any tab goes back to Home first; only Home leaves the app.
        // Registered before the screens, so a screen's own handler (settings sub-pages) wins.
        // While dragging back the current tab shrinks toward Home (predictive back, Android 14+).
        var tabPeek by remember { androidx.compose.runtime.mutableFloatStateOf(0f) }
        androidx.compose.ui.backhandler.PredictiveBackHandler(enabled = onboarded && !wideLayout && tab != Tab.HOME) { progress ->
            try {
                progress.collect { tabPeek = it.progress }
                tabPeek = 0f
                tab = Tab.HOME
            } catch (e: kotlin.coroutines.cancellation.CancellationException) {
                tabPeek = 0f
                throw e
            }
        }
        // On Home the first back only warns; a second one within 2 s leaves the app.
        var exitArmed by remember { mutableStateOf(false) }
        androidx.compose.ui.backhandler.BackHandler(enabled = onboarded && !wideLayout && tab == Tab.HOME && !exitArmed) {
            exitArmed = true
            toast = "Свайпните ещё раз, чтобы выйти"
        }
        LaunchedEffect(exitArmed) { if (exitArmed) { delay(2000); exitArmed = false } }

        LaunchedEffect(Unit) {
            controller.events.collect { msg ->
                toast = msg
                delay(3200)
                if (toast == msg) toast = null
            }
        }
        LaunchedEffect(Unit) {
            if (settings.autoConnect && controller.state.value == VpnState.Idle && controller.selectedServer() != null) controller.connect()
        }

        val stage = app.ghostly.ui.stage.rememberStage(controller, settings.stageMode)
        androidx.compose.runtime.CompositionLocalProvider(app.ghostly.ui.stage.LocalStage provides stage, app.ghostly.ui.screens.LocalSubscriptionNav provides subNav) {
        AuroraBackground(energy = if (state is VpnState.Connected) 1f else 0f) {
            val insets = WindowInsets.safeDrawing.asPaddingValues()
            BoxWithConstraints(Modifier.fillMaxSize()) {
                val wide = maxWidth >= 900.dp
                when {
                    !onboarded -> Onboarding(controller, onAdd = { addOpen = true })
                    wide -> DesktopShell(controller, tab, { tab = it }, onAdd = { addOpen = true })
                    else -> {
                        val pad = PaddingValues(top = insets.calculateTopPadding() + 8.dp, bottom = insets.calculateBottomPadding() + 96.dp)
                        // Predictive back to Главная: it is drawn beneath while the current tab slides off as a card.
                        if (tabPeek > 0f && tab != Tab.HOME) {
                            Box(Modifier.fillMaxSize().graphicsLayer { alpha = 0.8f + 0.2f * tabPeek }, contentAlignment = Alignment.TopCenter) {
                                Box(Modifier.widthIn(max = 620.dp).fillMaxSize()) {
                                    HomeScreen(controller, onPickServer = {}, contentPadding = pad)
                                }
                            }
                        }
                        AnimatedContent(
                            targetState = tab,
                            transitionSpec = { (fadeIn(Motion.quick(260)) + scaleIn(initialScale = 0.985f)) togetherWith fadeOut(Motion.quick(160)) },
                            modifier = Modifier.fillMaxSize().predictiveCard(tabPeek, app.ghostly.ui.screens.peekBrush()),
                        ) { t ->
                            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
                                Box(Modifier.widthIn(max = 620.dp).fillMaxSize()) {
                                    when (t) {
                                        Tab.HOME -> HomeScreen(controller, onPickServer = { pickerOpen = true }, contentPadding = pad)
                                        Tab.SERVERS -> ServersScreen(controller, pad, onAdd = { addOpen = true })
                                        Tab.SETTINGS -> SettingsScreen(controller, pad)
                                    }
                                }
                            }
                        }
                        TabBar(tab, { controller.haptic(); tab = it }, Modifier.align(Alignment.BottomCenter).padding(bottom = insets.calculateBottomPadding() + 14.dp))
                    }
                }
                SideEffect { wideLayout = wide }
            }
            // Music on the PC: the whole composition plays along (dimming, rim lights, drop flash, sparks).
            stage?.let { app.ghostly.ui.stage.StageOverlay(it) }

            // Server picker: our own sheet (see PickerSheet for why not ModalBottomSheet).
            PickerSheet(pickerOpen, onClose = { pickerOpen = false }, title = "Выбор сервера") {
                ServersScreen(
                    controller, PaddingValues(bottom = insets.calculateBottomPadding() + 24.dp),
                    onAdd = { pickerOpen = false; addOpen = true },
                    onPicked = { pickerOpen = false }, showHeader = false,
                )
            }

            // Toast
            AnimatedVisibility(
                toast != null,
                enter = slideInVertically { -it } + fadeIn(),
                exit = slideOutVertically { -it } + fadeOut(),
                modifier = Modifier.align(Alignment.TopCenter).padding(top = insets.calculateTopPadding() + 10.dp, start = 16.dp, end = 16.dp),
            ) {
                var last by remember { mutableStateOf("") }
                toast?.let { last = it }
                Box(
                    Modifier.widthIn(max = 520.dp).clip(RoundedCornerShape(20.dp)).background(Color(0xF01A1328))
                        .border(1.dp, Ghost.colors.accent.copy(alpha = 0.3f), RoundedCornerShape(20.dp))
                        .clickable { toast = null }.padding(horizontal = 16.dp, vertical = 12.dp),
                ) { Text(last, style = MaterialTheme.typography.bodyMedium.copy(color = Ghost.colors.ink)) }
            }
        }

        }
        subOpen?.let { id ->
            if (wideLayout) {
                Dialog(onDismissRequest = { subOpen = null }) {
                    Box(
                        Modifier.widthIn(max = 520.dp).heightIn(max = 760.dp).clip(RoundedCornerShape(28.dp)).background(Color(0xFF130E1D))
                            .border(1.dp, Brush.verticalGradient(listOf(Ghost.colors.accent.copy(alpha = 0.4f), Color.White.copy(alpha = 0.05f))), RoundedCornerShape(28.dp))
                            .padding(top = 24.dp),
                    ) { app.ghostly.ui.screens.SubscriptionPage(controller, id, onAdd = subNav.add, onClose = { subOpen = null }) }
                }
            } else {
                ModalBottomSheet(
                    onDismissRequest = { subOpen = null },
                    sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
                    containerColor = Color(0xFF110C1A), scrimColor = Color.Black.copy(alpha = 0.55f),
                ) { app.ghostly.ui.screens.SubscriptionPage(controller, id, onAdd = subNav.add, onClose = { subOpen = null }) }
            }
        }
        if (addOpen && wideLayout) {
            Dialog(onDismissRequest = { addOpen = false }) {
                Box(
                    Modifier.widthIn(max = 520.dp).clip(RoundedCornerShape(28.dp)).background(Color(0xFF130E1D))
                        .border(1.dp, Brush.verticalGradient(listOf(Ghost.colors.accent.copy(alpha = 0.4f), Color.White.copy(alpha = 0.05f))), RoundedCornerShape(28.dp))
                        .padding(top = 24.dp),
                ) { AddSheet(controller) { addOpen = false } }
            }
        } else if (addOpen) {
            ModalBottomSheet(
                onDismissRequest = { addOpen = false },
                sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
                containerColor = Color(0xFF110C1A), scrimColor = Color.Black.copy(alpha = 0.55f),
            ) { AddSheet(controller) { addOpen = false } }
        }
    }
}}

// ---------------------------------------------------------------------------- picker sheet

/**
 * Bottom sheet over the whole app, shaped like Material's: opens half-way, a swipe up on the list
 * first raises it to full height, a swipe down with the list at its top pulls it down or closes it.
 *
 * Our own instead of ModalBottomSheet because that one kept the fling of a swipe that moved the
 * sheet — the list "sometimes scrolled, sometimes not" — and its dialog window could swallow the
 * next tap. Here a swipe moves the sheet only while the sheet actually has somewhere to go; once it
 * is up, every swipe and fling belongs to the list.
 */
@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
private fun BoxScope.PickerSheet(
    open: Boolean,
    onClose: () -> Unit,
    title: String,
    content: @Composable ColumnScope.() -> Unit,
) {
    androidx.compose.ui.backhandler.BackHandler(enabled = open, onBack = onClose)
    var shown by remember { mutableStateOf(false) }
    BoxWithConstraints(Modifier.matchParentSize()) {
        val screen = constraints.maxHeight.toFloat()
        val full = screen * 0.92f           // panel height; offset 0 = fully up
        val half = full - screen * 0.58f    // offset of the half-open state
        val hidden = full                   // offset that puts the panel below the screen
        var off by remember { mutableFloatStateOf(hidden) }
        val scope = rememberCoroutineScope()
        var settling by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
        fun animateTo(target: Float, then: () -> Unit = {}) {
            settling?.cancel()
            settling = scope.launch {
                androidx.compose.animation.core.animate(off, target, animationSpec = androidx.compose.animation.core.spring(stiffness = 500f)) { v, _ -> off = v }
                then()
            }
        }
        fun settle(velocity: Float) {
            val target = when {
                velocity > 1400f -> if (off < half - 1f) half else hidden
                velocity < -1400f -> 0f
                off > half + (hidden - half) * 0.4f -> hidden
                off > half / 2 -> half
                else -> 0f
            }
            if (target == hidden) onClose() else animateTo(target)
        }
        LaunchedEffect(open) {
            if (open) {
                shown = true
                off = hidden
                animateTo(half)
            } else if (shown) {
                animateTo(hidden) { shown = false }
            }
        }
        if (!shown) return@BoxWithConstraints

        // The sheet takes the part of a swipe it can use and hands the rest to the list.
        val connection = remember(half, hidden) {
            object : androidx.compose.ui.input.nestedscroll.NestedScrollConnection {
                var moved = false
                override fun onPreScroll(available: androidx.compose.ui.geometry.Offset, source: androidx.compose.ui.input.nestedscroll.NestedScrollSource): androidx.compose.ui.geometry.Offset {
                    val dy = available.y
                    if (source != androidx.compose.ui.input.nestedscroll.NestedScrollSource.UserInput || dy >= 0f || off <= 0f) return androidx.compose.ui.geometry.Offset.Zero
                    settling?.cancel()
                    val next = (off + dy).coerceAtLeast(0f)
                    val used = next - off
                    off = next
                    moved = true
                    return androidx.compose.ui.geometry.Offset(0f, used)
                }
                override fun onPostScroll(consumed: androidx.compose.ui.geometry.Offset, available: androidx.compose.ui.geometry.Offset, source: androidx.compose.ui.input.nestedscroll.NestedScrollSource): androidx.compose.ui.geometry.Offset {
                    val dy = available.y
                    if (source != androidx.compose.ui.input.nestedscroll.NestedScrollSource.UserInput || dy <= 0f) return androidx.compose.ui.geometry.Offset.Zero
                    settling?.cancel()
                    off = (off + dy).coerceAtMost(hidden)
                    moved = true
                    return androidx.compose.ui.geometry.Offset(0f, dy)
                }
                override suspend fun onPreFling(available: androidx.compose.ui.unit.Velocity): androidx.compose.ui.unit.Velocity {
                    if (!moved) return androidx.compose.ui.unit.Velocity.Zero
                    moved = false
                    val wasUp = off <= 0.5f
                    settle(available.y)
                    // Sheet reached the top during this swipe: the rest of the fling scrolls the list.
                    return if (wasUp && available.y < 0f) androidx.compose.ui.unit.Velocity.Zero else available
                }
            }
        }

        Box(
            Modifier.fillMaxSize().graphicsLayer { alpha = (1f - off / hidden).coerceIn(0f, 1f) }
                .background(Color.Black.copy(alpha = 0.55f))
                .clickable(remember { MutableInteractionSource() }, null, onClick = onClose),
        )
        Column(
            Modifier.align(Alignment.BottomCenter).widthIn(max = 620.dp).fillMaxWidth()
                .height(with(androidx.compose.ui.platform.LocalDensity.current) { full.toDp() })
                .graphicsLayer { translationY = off }
                .clip(RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp))
                .background(Color(0xFF110C1A))
                // Taps on the panel itself must not fall through to the scrim.
                .clickable(remember { MutableInteractionSource() }, null) {}
                .nestedScroll(connection),
        ) {
            // Handle and title drag the sheet directly.
            Column(
                Modifier.fillMaxWidth().draggable(
                    orientation = Orientation.Vertical,
                    state = rememberDraggableState { d ->
                        settling?.cancel()
                        off = (off + d).coerceIn(0f, hidden)
                    },
                    onDragStopped = { v -> settle(v) },
                ),
            ) {
                Box(Modifier.fillMaxWidth().height(28.dp), contentAlignment = Alignment.Center) {
                    Box(Modifier.size(width = 36.dp, height = 4.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.28f)))
                }
                Text(title, style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(start = 22.dp, end = 22.dp, bottom = 4.dp))
            }
            content()
        }
    }
}

// ---------------------------------------------------------------------------- tab bar

@Composable
private fun TabBar(selected: Tab, onSelect: (Tab) -> Unit, modifier: Modifier) {
    val c = Ghost.colors
    Row(
        modifier
            .clip(RoundedCornerShape(28.dp))
            .background(Color(0xE6130E1D))
            .border(1.dp, Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.16f), Color.White.copy(alpha = 0.04f))), RoundedCornerShape(28.dp))
            .padding(6.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Tab.entries.forEach { t ->
            val active = t == selected
            val interaction = remember { MutableInteractionSource() }
            val bg by animateColorAsState(if (active) c.accent.copy(alpha = 0.2f) else Color.Transparent, Motion.quick())
            val tint by animateColorAsState(if (active) c.accent else c.ink3, Motion.quick())
            val w by animateDpAsState(if (active) 122.dp else 56.dp, Motion.bouncy())
            // The chosen tab's icon pops and tilts a little, like a tap on the site's dock.
            val pop by animateFloatAsState(if (active) 1f else 0f, Motion.bouncy())
            Row(
                Modifier.width(w).height(48.dp).pressScale(interaction, 0.9f).clip(RoundedCornerShape(24.dp)).background(bg)
                    .clickable(interaction, null) { onSelect(t) },
                horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically,
            ) {
                val iconFx = Modifier.graphicsLayer { val k = 1f + 0.08f * pop; scaleX = k; scaleY = k; rotationZ = if (t == Tab.SETTINGS) 60f * pop else -6f * pop }
                if (t == Tab.HOME) GhostMark(Modifier.size(24.dp).then(iconFx), happy = if (active) 1f else 0f, pokeable = false)
                else Icon(if (t == Tab.SERVERS) Icons.Rounded.Dns else Icons.Rounded.Settings, null, tint = tint, modifier = Modifier.size(22.dp).then(iconFx))
                AnimatedVisibility(active, enter = fadeIn() + scaleIn(initialScale = 0.6f), exit = fadeOut() + scaleOut(targetScale = 0.6f)) {
                    Text(t.title, style = MaterialTheme.typography.labelMedium, color = tint, modifier = Modifier.padding(start = 7.dp), maxLines = 1)
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------- onboarding

@Composable
private fun Onboarding(controller: GhostlyController, onAdd: () -> Unit) {
    val c = Ghost.colors
    Column(
        Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center,
    ) {
        GhostMark(Modifier.size(150.dp))
        Spacer(Modifier.height(18.dp))
        Text("Ghostly VPN", style = MaterialTheme.typography.displayMedium)
        Spacer(Modifier.height(8.dp))
        Text(
            "Свободный интернет без тормозов.\nYouTube, Instagram, Telegram — как будто ничего и не блокировали.",
            style = MaterialTheme.typography.bodyLarge.copy(color = c.ink2), textAlign = TextAlign.Center,
            modifier = Modifier.widthIn(max = 360.dp),
        )
        Spacer(Modifier.height(34.dp))
        Column(Modifier.widthIn(max = 380.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            AccentButton("Вставить ссылку из буфера", {
                controller.haptic()
                controller.import(controller.platform.readClipboard().orEmpty())
            }, Modifier.fillMaxWidth(), Icons.Rounded.ContentPaste)
            SoftButton("Другие способы", onAdd, Modifier.fillMaxWidth(), Icons.Rounded.QrCodeScanner)
            SoftButton("Купить подписку", { controller.platform.openUrl(SITE_URL) }, Modifier.fillMaxWidth(), Icons.Rounded.ShoppingBag, tint = c.accent)
        }
        Spacer(Modifier.height(20.dp))
        Text("Подходит любая подписка VLESS / VMess / Trojan / Shadowsocks / Hysteria2", style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center)
    }
}

// ---------------------------------------------------------------------------- add sheet

@Composable
private fun AddSheet(controller: GhostlyController, close: () -> Unit) {
    val c = Ghost.colors
    var text by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    val done: (Boolean) -> Unit = { ok -> busy = false; if (ok) close() }

    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 28.dp)) {
        Text("Добавить", style = MaterialTheme.typography.headlineSmall)
        Text("Подписка, ссылка на сервер или JSON-конфиг", style = MaterialTheme.typography.bodySmall)
        Spacer(Modifier.height(16.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            SourceTile("Из буфера", Icons.Rounded.ContentPaste, Modifier.weight(1f)) {
                busy = true
                controller.import(controller.platform.readClipboard().orEmpty(), done)
            }
            controller.platform.qrScanner?.let { scan ->
                SourceTile("QR-код", Icons.Rounded.QrCodeScanner, Modifier.weight(1f)) {
                    scan { result -> busy = true; controller.import(result, done) }
                }
            }
            SourceTile("Купить", Icons.Rounded.ShoppingBag, Modifier.weight(1f)) { controller.platform.openUrl(SITE_URL) }
        }
        Spacer(Modifier.height(16.dp))
        Box(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Color.White.copy(alpha = 0.05f))
                .border(1.dp, c.line, RoundedCornerShape(16.dp)).padding(14.dp),
        ) {
            if (text.isEmpty()) Text("https://… или vless://…", style = MaterialTheme.typography.bodyMedium, color = c.ink3)
            BasicTextField(
                text, { text = it },
                textStyle = MaterialTheme.typography.bodyMedium.copy(color = c.ink),
                cursorBrush = SolidColor(c.accent), modifier = Modifier.fillMaxWidth().height(80.dp),
            )
        }
        Spacer(Modifier.height(12.dp))
        AccentButton(if (busy) "Загружаю…" else "Добавить", {
            busy = true
            controller.import(text, done)
        }, Modifier.fillMaxWidth(), enabled = text.isNotBlank() && !busy)
    }
}

@Composable
private fun SourceTile(label: String, icon: ImageVector, modifier: Modifier, onClick: () -> Unit) {
    val c = Ghost.colors
    val interaction = remember { MutableInteractionSource() }
    Column(
        modifier.pressScale(interaction, 0.94f).clip(RoundedCornerShape(20.dp))
            .background(Brush.verticalGradient(listOf(c.accent.copy(alpha = 0.14f), c.accent2.copy(alpha = 0.06f))))
            .border(1.dp, c.accent.copy(alpha = 0.22f), RoundedCornerShape(20.dp))
            .sheen(interaction, strength = 0.14f)
            .clickable(interaction, null, onClick = onClick).padding(vertical = 18.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(icon, null, tint = c.accent, modifier = Modifier.size(26.dp))
        Spacer(Modifier.height(8.dp))
        Text(label, style = MaterialTheme.typography.labelMedium)
    }
}
