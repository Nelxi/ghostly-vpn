package app.ghostly.ui

import app.ghostly.ui.components.pageFade
import app.ghostly.ui.components.hoverSound
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.Public
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.animation.core.animateFloat
import androidx.compose.material.icons.rounded.AccountCircle
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
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import app.ghostly.core.GhostlyController
import app.ghostly.core.vpn.VpnState
import app.ghostly.ui.components.AccentButton
import app.ghostly.ui.components.LocalNight
import app.ghostly.ui.components.NightScene
import app.ghostly.ui.components.NightState
import app.ghostly.ui.components.OrbState
import app.ghostly.ui.components.nightGhost
import app.ghostly.ui.components.nightSky
import app.ghostly.ui.components.plate
import app.ghostly.ui.components.GhostMark
import app.ghostly.ui.components.SoftButton
import app.ghostly.ui.components.pressScale
import app.ghostly.ui.components.sheen
import app.ghostly.ui.components.softShadow
import app.ghostly.ui.screens.HomeScreen
import app.ghostly.ui.screens.ServersScreen
import app.ghostly.ui.screens.SettingsScreen
import app.ghostly.ui.theme.Ghost
import app.ghostly.ui.theme.GhostlyTheme
import app.ghostly.ui.theme.Motion
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import app.ghostly.i18n.tr

const val SITE_URL = "https://ghostlynex.fun"

internal enum class Tab {
    HOME, SERVERS, ACCOUNT, SETTINGS;

    /** Read on every composition: the language can change while the app runs. */
    val title: String
        get() = when (this) {
            HOME -> tr("Главная")
            SERVERS -> tr("Серверы")
            ACCOUNT -> tr("Аккаунт")
            SETTINGS -> tr("Настройки")
        }
}

/**
 * The camera's shot of the night on each tab: it moves sideways one step per tab (far things slide less) and
 * stands back from the ghost everywhere but Home.
 */
internal val Tab.yaw: Float get() = ordinal * 1f
internal val Tab.zoom: Float
    get() = when (this) {
        Tab.HOME -> 1f
        Tab.SERVERS -> 0.9f
        Tab.ACCOUNT -> 0.93f
        Tab.SETTINGS -> 0.88f
    }

/** This tab's horizon: the bottom edge of what it marks (see nightSky). */
internal fun Modifier.tabSky(tab: Tab): Modifier = nightSky(tab, tab.yaw, tab.zoom)

/**
 * The tabs shown: the account tab is there for Ghostly's own subscription, a signed-in account, an app with
 * nothing added yet, or when something asked for it ([forced]); someone on another provider's
 * subscription only does not get a Ghostly shop in the tab bar.
 */
internal fun visibleTabs(controller: GhostlyController, forced: Boolean): List<Tab> {
    val profiles = controller.profiles.value
    val ours = profiles.isEmpty() || controller.ghostlyProfile() != null || controller.cabinet.api.hasSession
    return Tab.entries.filter { it != Tab.ACCOUNT || ours || forced }
}

@OptIn(ExperimentalMaterial3Api::class, androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
fun GhostlyApp(controller: GhostlyController) {
    val settings by controller.settings.collectAsState()
    LaunchedEffect(settings.language) { app.ghostly.i18n.L10n.load(settings.language) }
    val onboarded by controller.onboarded.collectAsState()
    val state by controller.state.collectAsState()

    val design by controller.design.tokens.collectAsState()
    androidx.compose.runtime.SideEffect {
        app.ghostly.ui.components.ShadowLook.strength = design.shadow
        app.ghostly.ui.components.ShadowLook.spread = design.shadowSoft
    }
    // A seasonal accent from the server applies only while the user keeps the default colour.
    // Monet (the wallpaper's colour) wins over both when the user turned it on.
    val accent = settings.monet.takeIf { it }?.let { controller.platform.systemAccent() }
        ?: design.accentArgb()?.takeIf { settings.accent == app.ghostly.core.model.ThemeAccent.GHOST } ?: settings.accent.argb
    // One clock for every looping decoration, paused while the app is off screen (see Ambient.kt).
    val ambientTime = app.ghostly.ui.components.rememberAmbientClock()
    GhostlyTheme(accent, settings.reduceMotion, protected = state is VpnState.Connected) { androidx.compose.runtime.CompositionLocalProvider(app.ghostly.ui.components.LocalAmbientTime provides ambientTime, app.ghostly.ui.components.LocalHaptic provides { controller.haptic(app.ghostly.core.vpn.Haptic.TICK) }, app.ghostly.ui.components.LocalHapticOf provides { k -> controller.haptic(k) }, app.ghostly.ui.components.LocalHoverSound provides { controller.hoverSound() }, app.ghostly.ui.theme.LocalDesign provides design, androidx.compose.ui.platform.LocalLayoutDirection provides if (app.ghostly.i18n.L10n.isRtl) androidx.compose.ui.unit.LayoutDirection.Rtl else androidx.compose.ui.unit.LayoutDirection.Ltr) {
        // GHOSTLY_DEMO=tab:<NAME> (screenshots) opens on that tab.
        var tab by rememberSaveable {
            mutableStateOf(controller.platform.demo?.takeIf { it.startsWith("tab:") }?.removePrefix("tab:")?.let { d -> Tab.entries.firstOrNull { it.name == d } } ?: Tab.HOME)
        }
        // The account tab follows what is added; a request from elsewhere (Settings, «Продлить») brings it up.
        val profilesForTabs by controller.profiles.collectAsState()
        val cabinetUi by controller.cabinet.ui.collectAsState()
        var cabinetForced by remember { mutableStateOf(false) }
        val tabs = remember(profilesForTabs, cabinetUi, cabinetForced) { visibleTabs(controller, cabinetForced) }
        LaunchedEffect(tabs) { if (tab !in tabs) tab = Tab.HOME }
        // Over the first screen (nothing added yet) the cabinet opens as a page of its own.
        var cabinetOverlay by remember { mutableStateOf(false) }
        LaunchedEffect(Unit) {
            controller.openCabinet.collect {
                if (controller.onboarded.value) {
                    cabinetForced = true
                    tab = Tab.ACCOUNT
                } else cabinetOverlay = true
            }
        }
        LaunchedEffect(onboarded) {
            if (onboarded && cabinetOverlay) {
                cabinetOverlay = false
                cabinetForced = true
                tab = Tab.ACCOUNT
            }
        }
        controller.platform.demo?.takeIf { it.startsWith("tabs:") }?.let { spec ->
            val every = spec.removePrefix("tabs:").toLongOrNull() ?: 2500L
            androidx.compose.runtime.LaunchedEffect(Unit) {
                val order = listOf(Tab.SETTINGS, Tab.SERVERS, Tab.HOME)
                var i = 0
                while (true) { kotlinx.coroutines.delay(every); tab = order[i++ % order.size] }
            }
        }
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
            toast = tr("Свайпните ещё раз, чтобы выйти")
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
        val night = remember { NightState() }
        val orb = when (state) {
            is VpnState.Connected -> OrbState.CONNECTED
            VpnState.Connecting, VpnState.Disconnecting -> OrbState.CONNECTING
            is VpnState.Failed -> OrbState.ERROR
            VpnState.Idle -> OrbState.IDLE
        }
        // Whose shot the camera takes: the first screen, the cabinet opened over it, or the tab.
        val page: Any = when {
            !onboarded && cabinetOverlay -> Tab.ACCOUNT
            !onboarded -> ONBOARDING
            else -> tab
        }
        androidx.compose.runtime.CompositionLocalProvider(app.ghostly.ui.stage.LocalStage provides stage, app.ghostly.ui.screens.LocalSubscriptionNav provides subNav, LocalNight provides night) {
        NightScene(night, orb, page) {
            val insets = WindowInsets.safeDrawing.asPaddingValues()
            BoxWithConstraints(Modifier.fillMaxSize()) {
                val wide = maxWidth >= 900.dp
                when {
                    // Nothing added yet and «Купить подписку» / a trial asked: the cabinet as a page of its own.
                    !onboarded && cabinetOverlay -> {
                        androidx.compose.ui.backhandler.BackHandler { cabinetOverlay = false }
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
                            Box(Modifier.widthIn(max = if (wide) 1040.dp else 620.dp).fillMaxSize()) {
                                app.ghostly.ui.screens.cabinet.CabinetScreen(
                                    controller,
                                    PaddingValues(top = insets.calculateTopPadding() + 8.dp, bottom = insets.calculateBottomPadding() + 24.dp),
                                    wide = wide,
                                    onClose = { cabinetOverlay = false },
                                )
                            }
                        }
                    }
                    !onboarded -> Onboarding(controller, onAdd = { addOpen = true }, onBuy = { controller.openAccount("renew") })
                    wide -> DesktopShell(controller, tabs, tab, { tab = it }, onAdd = { addOpen = true })
                    else -> {
                        val pad = PaddingValues(top = insets.calculateTopPadding() + 8.dp, bottom = insets.calculateBottomPadding() + 96.dp)
                        // Predictive back to Главная: it is drawn beneath while the current tab slides off as a card.
                        if (tabPeek > 0f && tab != Tab.HOME) {
                            Box(Modifier.fillMaxSize().graphicsLayer { compositingStrategy = androidx.compose.ui.graphics.CompositingStrategy.ModulateAlpha; alpha = 0.8f + 0.2f * tabPeek }, contentAlignment = Alignment.TopCenter) {
                                Box(Modifier.widthIn(max = 620.dp).fillMaxSize()) {
                                    HomeScreen(controller, onPickServer = {}, contentPadding = pad)
                                }
                            }
                        }
                        val heading = rememberHeading(tab)
                        AnimatedContent(
                            targetState = tab,
                            // The pages ride the camera (see travel): no transition of their own, the old one is
                            // kept until its turn is over.
                            transitionSpec = { travelSpec() },
                            modifier = Modifier.fillMaxSize().predictiveCard(tabPeek, app.ghostly.ui.screens.peekBrush()),
                        ) { t ->
                            Box(Modifier.fillMaxSize().travel(this@AnimatedContent, heading), contentAlignment = Alignment.TopCenter) {
                                Box(Modifier.widthIn(max = 620.dp).fillMaxSize()) {
                                    when (t) {
                                        Tab.HOME -> HomeScreen(controller, onPickServer = { pickerOpen = true }, contentPadding = pad)
                                        Tab.SERVERS -> ServersScreen(controller, pad, onAdd = { addOpen = true })
                                        Tab.ACCOUNT -> app.ghostly.ui.screens.cabinet.CabinetScreen(controller, pad)
                                        Tab.SETTINGS -> SettingsScreen(controller, pad)
                                    }
                                }
                            }
                        }
                        TabBar(tabs, tab, { controller.haptic(); tab = it }, Modifier.align(Alignment.BottomCenter).padding(bottom = insets.calculateBottomPadding() + 14.dp))
                    }
                }
                SideEffect { wideLayout = wide }
            }
            // Music on the PC: the whole composition plays along (rim lights with the bass).
            stage?.let { app.ghostly.ui.stage.StageOverlay(it) }

            // Server picker: our own sheet (see PickerSheet for why not ModalBottomSheet).
            PickerSheet(pickerOpen, onClose = { pickerOpen = false }, title = tr("Выбор сервера")) {
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
                    Modifier.widthIn(max = 520.dp).softShadow(RoundedCornerShape(22.dp), 1f)
                        .plate(RoundedCornerShape(22.dp), strong = true)
                        .hoverSound().clickable { toast = null }.padding(horizontal = 18.dp, vertical = 13.dp),
                ) { Text(last, style = MaterialTheme.typography.bodyMedium.copy(color = Ghost.colors.ink)) }
            }

            // Ghostly's messages (broadcast or personal): on top of everything, like a Telegram message.
            if (onboarded) {
                app.ghostly.ui.components.NoticeOverlay(
                    controller,
                    Modifier.align(if (wideLayout) Alignment.TopEnd else Alignment.TopCenter)
                        .padding(top = insets.calculateTopPadding() + 10.dp, start = 12.dp, end = if (wideLayout) 20.dp else 12.dp),
                )
            }
        }

        }
        subOpen?.let { id ->
            if (wideLayout) {
                Dialog(onDismissRequest = { subOpen = null }) {
                    Box(
                        Modifier.widthIn(max = 520.dp).heightIn(max = 760.dp).clip(RoundedCornerShape(app.ghostly.ui.components.Radius.card)).background(Ghost.colors.surface1)
                            .border(1.dp, Ghost.colors.hairline, RoundedCornerShape(app.ghostly.ui.components.Radius.card))
                            .padding(top = 24.dp),
                    ) { app.ghostly.ui.screens.SubscriptionPage(controller, id, onAdd = subNav.add, onClose = { subOpen = null }) }
                }
            } else {
                ModalBottomSheet(
                    onDismissRequest = { subOpen = null },
                    sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
                    containerColor = Ghost.colors.surface1, scrimColor = Color.Black.copy(alpha = 0.6f),
                ) { app.ghostly.ui.screens.SubscriptionPage(controller, id, onAdd = subNav.add, onClose = { subOpen = null }) }
            }
        }
        if (addOpen && wideLayout) {
            Dialog(onDismissRequest = { addOpen = false }) {
                Box(
                    Modifier.widthIn(max = 520.dp).clip(RoundedCornerShape(app.ghostly.ui.components.Radius.card)).background(Ghost.colors.surface1)
                        .border(1.dp, Ghost.colors.hairline, RoundedCornerShape(app.ghostly.ui.components.Radius.card))
                        .padding(top = 24.dp),
                ) { AddSheet(controller) { addOpen = false } }
            }
        } else if (addOpen) {
            ModalBottomSheet(
                onDismissRequest = { addOpen = false },
                sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
                containerColor = Ghost.colors.surface1, scrimColor = Color.Black.copy(alpha = 0.6f),
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
            Modifier.fillMaxSize().graphicsLayer { compositingStrategy = androidx.compose.ui.graphics.CompositingStrategy.ModulateAlpha; alpha = (1f - off / hidden).coerceIn(0f, 1f) }
                .background(Color.Black.copy(alpha = 0.55f))
                .hoverSound().clickable(remember { MutableInteractionSource() }, null, onClick = onClose),
        )
        Column(
            Modifier.align(Alignment.BottomCenter).widthIn(max = 620.dp).fillMaxWidth()
                .height(with(androidx.compose.ui.platform.LocalDensity.current) { full.toDp() })
                .graphicsLayer { translationY = off }
                .clip(RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp))
                .background(Ghost.colors.surface1)
                // Taps on the panel itself must not fall through to the scrim.
                .hoverSound().clickable(remember { MutableInteractionSource() }, null) {}
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

/** The first screen's key for the night's camera (nothing added yet). */
internal const val ONBOARDING = "onboarding"

/** Which way the camera travels to [to]: 1 to a later tab or page (the world slides left), -1 back. */
@Composable
internal fun <T : Enum<T>> rememberHeading(to: T): Int {
    var last by remember { mutableStateOf(to) }
    val heading = remember(to) { if (to.ordinal >= last.ordinal) 1 else -1 }
    SideEffect { last = to }
    return heading
}

/**
 * Tabs bring no transition of their own: [travel] moves them. The leaving page is held for the whole turn by
 * an exit that changes nothing (a scale to 1) and lasts as long as the travel.
 */
internal fun travelSpec(): androidx.compose.animation.ContentTransform =
    androidx.compose.animation.ContentTransform(
        targetContentEnter = androidx.compose.animation.EnterTransition.None,
        initialContentExit = scaleOut(androidx.compose.animation.core.tween(app.ghostly.ui.components.TRAVEL_MS / 2), targetScale = 1f),
        sizeTransform = androidx.compose.animation.SizeTransform(clip = false),
    )

/**
 * A page rides the camera's travel: the pages are faces of one turning cube. The leaving face swings away
 * around the edge it shares with the arriving one, the arriving face swings in on that same edge, so the
 * two never part — and the night behind them travels the same way with depth. Same curve and time as the
 * camera (TRAVEL / TRAVEL_MS). With reduced motion: a plain cross-fade.
 */
@Composable
internal fun Modifier.travel(scope: androidx.compose.animation.AnimatedVisibilityScope, heading: Int): Modifier {
    val reduce = app.ghostly.ui.theme.LocalReduceMotion.current
    val t by scope.transition.animateFloat(
        transitionSpec = { androidx.compose.animation.core.tween(if (reduce) 260 else app.ghostly.ui.components.TRAVEL_MS, easing = if (reduce) androidx.compose.animation.core.LinearEasing else app.ghostly.ui.components.TRAVEL) },
        label = "travel",
    ) {
        when (it) {
            androidx.compose.animation.EnterExitState.PreEnter -> -1f
            androidx.compose.animation.EnterExitState.Visible -> 0f
            androidx.compose.animation.EnterExitState.PostExit -> 1f
        }
    }
    return graphicsLayer {
        val a = kotlin.math.abs(t)
        if (reduce) {
            alpha = 1f - a
            return@graphicsLayer
        }
        // The scene does the travelling; the interface only breathes in depth: the leaving page swells a
        // touch and dissolves early, the arriving one comes up from slightly smaller and drifts in from the
        // side the camera heads to.
        if (t > 0f) {
            val k = (t / 0.35f).coerceIn(0f, 1f)
            val s = 1f + 0.06f * k
            scaleX = s; scaleY = s
            alpha = 1f - k
            translationX = -heading * k * 24.dp.toPx()
        } else {
            val k = ((a - 0.0f) / 0.75f).coerceIn(0f, 1f) // 1 → 0 as it arrives
            val s = 1f - 0.07f * k
            scaleX = s; scaleY = s
            alpha = (1f - k * 1.25f).coerceIn(0f, 1f)
            translationX = heading * k * 40.dp.toPx()
        }
    }
}


/** The logo's ghost as a flat glyph (100×110 box), for the dock. */
private val GHOST_GLYPH: androidx.compose.ui.graphics.Path by lazy {
    androidx.compose.ui.graphics.vector.PathParser().parsePathString(
        "M82.96 24.28C94.42 30.89 96 44.03 89.39 55.48C82.77 66.94 79.01 76.99 76.51 85.73C75.07 89.99 72.22 91.4 69.38 89.25" +
            "C65.87 91.81 61.49 92.33 56.63 91.05C58.83 87.24 57.87 83.62 55.25 81.09C51.4 86 44.94 88.38 37.44 88.13" +
            "C39.65 84.31 39.28 80.53 36.89 77.62C30.65 79.62 23.45 74.44 16.93 64.57C25.24 64.28 32.53 61.36 38.03 55.37" +
            "C43.58 48.39 47.55 41.52 51.96 33.88C58.58 22.42 71.5 17.67 82.96 24.28Z",
    ).toPath()
}

@Composable
private fun GhostGlyph(color: Color, modifier: Modifier) {
    androidx.compose.foundation.Canvas(modifier) {
        val k = minOf(size.width / 100f, size.height / 110f)
        val dx = (size.width - 100f * k) / 2f
        val dy = (size.height - 110f * k) / 2f
        withTransform({
            translate(dx, dy)
            scale(k, k, androidx.compose.ui.geometry.Offset.Zero)
        }) { drawPath(GHOST_GLYPH, color) }
    }
}

/**
 * The floating dock: a plate over the water, the current tab's pill with its name, the others as quiet
 * icons. The pill is moonlight while unprotected and takes the accent once the colour is back.
 */
@Composable
private fun TabBar(tabs: List<Tab>, selected: Tab, onSelect: (Tab) -> Unit, modifier: Modifier) {
    val c = Ghost.colors
    val dock = RoundedCornerShape(32.dp)
    Row(
        modifier
            .softShadow(dock, 1f)
            .plate(dock, strong = true)
            .padding(7.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        tabs.forEach { t ->
            val active = t == selected
            val interaction = remember { MutableInteractionSource() }
            val pillColor = androidx.compose.ui.graphics.lerp(Color.White.copy(alpha = 0.08f), c.accent.copy(alpha = 0.18f), c.lit)
            val bg by animateColorAsState(if (active) pillColor else Color.Transparent, Motion.quick())
            val lit = androidx.compose.ui.graphics.lerp(c.ink, androidx.compose.ui.graphics.lerp(c.accent, Color.White, 0.32f), c.lit)
            val tint by animateColorAsState(if (active) lit else c.ink4, Motion.quick())
            val w by animateDpAsState(if (active) 116.dp else 56.dp, Motion.bouncy())
            // The chosen tab's icon pops and tilts a little, like a tap on the site's dock.
            val pop by animateFloatAsState(if (active) 1f else 0f, Motion.bouncy())
            Row(
                Modifier.width(w).height(50.dp).pressScale(interaction, 0.9f).clip(RoundedCornerShape(25.dp)).background(bg)
                    .hoverSound().clickable(interaction, null) { onSelect(t) },
                horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically,
            ) {
                val iconFx = Modifier.graphicsLayer { val k = 1f + 0.08f * pop; scaleX = k; scaleY = k; rotationZ = -6f * pop }
                if (t == Tab.HOME) GhostGlyph(tint, Modifier.size(22.dp).then(iconFx))
                else Icon(
                    when (t) {
                        Tab.SERVERS -> Icons.Outlined.Public
                        Tab.ACCOUNT -> Icons.Outlined.Person
                        else -> Icons.Outlined.Tune
                    },
                    t.title, tint = tint, modifier = Modifier.size(22.dp).then(iconFx),
                )
                AnimatedVisibility(active, enter = fadeIn() + scaleIn(initialScale = 0.6f), exit = fadeOut() + scaleOut(targetScale = 0.6f)) {
                    Text(t.title, style = MaterialTheme.typography.labelLarge.copy(fontSize = 14.sp), color = c.ink, modifier = Modifier.padding(start = 8.dp), maxLines = 1)
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------- onboarding

@Composable
private fun Onboarding(controller: GhostlyController, onAdd: () -> Unit, onBuy: () -> Unit) {
    val c = Ghost.colors
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val sky = (maxHeight * 0.46f).coerceIn(230.dp, 440.dp)
        val ghost = minOf(sky * 0.62f, maxWidth * 0.56f, 232.dp)
        Column(
            Modifier.fillMaxSize().verticalScroll(androidx.compose.foundation.rememberScrollState())
                .statusBarsPadding().navigationBarsPadding().padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(Modifier.fillMaxWidth().height(sky).nightSky(ONBOARDING, yaw = -0.2f, zoom = 1.04f), contentAlignment = Alignment.Center) {
                Box(Modifier.size(ghost).nightGhost(ONBOARDING, label = "Ghostly"))
            }
            Spacer(Modifier.height(30.dp))
            Text("Ghostly VPN", style = MaterialTheme.typography.displayMedium, textAlign = TextAlign.Center)
            Spacer(Modifier.height(10.dp))
            Text(
                tr("Свободный интернет без тормозов.\nYouTube, Instagram, Telegram — как будто ничего и не блокировали."),
                style = MaterialTheme.typography.bodyLarge.copy(color = c.ink2), textAlign = TextAlign.Center,
                modifier = Modifier.widthIn(max = 360.dp),
            )
            Spacer(Modifier.height(30.dp))
            Column(Modifier.widthIn(max = 380.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                AccentButton(tr("Вставить ссылку из буфера"), {
                    controller.haptic()
                    controller.import(controller.platform.readClipboard().orEmpty())
                }, Modifier.fillMaxWidth(), Icons.Rounded.ContentPaste)
                SoftButton(tr("Другие способы"), onAdd, Modifier.fillMaxWidth(), Icons.Rounded.QrCodeScanner)
                SoftButton(tr("Купить подписку"), onBuy, Modifier.fillMaxWidth(), Icons.Rounded.ShoppingBag, tint = c.accent)
            }
            Spacer(Modifier.height(20.dp))
            Text(tr("Подходит любая подписка VLESS / VMess / Trojan / Shadowsocks / Hysteria2"), style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center)
            Spacer(Modifier.height(24.dp))
        }
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
        Text(tr("Добавить"), style = MaterialTheme.typography.headlineSmall)
        Text(tr("Подписка, ссылка на сервер или JSON-конфиг"), style = MaterialTheme.typography.bodySmall)
        Spacer(Modifier.height(16.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            SourceTile(tr("Из буфера"), Icons.Rounded.ContentPaste, Modifier.weight(1f)) {
                busy = true
                controller.import(controller.platform.readClipboard().orEmpty(), done)
            }
            controller.platform.qrScanner?.let { scan ->
                SourceTile(tr("QR-код"), Icons.Rounded.QrCodeScanner, Modifier.weight(1f)) {
                    scan { result -> busy = true; controller.import(result, done) }
                }
            }
            SourceTile(tr("Купить"), Icons.Rounded.ShoppingBag, Modifier.weight(1f)) { close(); controller.openAccount("renew") }
        }
        Spacer(Modifier.height(16.dp))
        Box(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(app.ghostly.ui.components.Radius.tile)).background(c.surface3)
                .border(1.dp, c.hairline, RoundedCornerShape(app.ghostly.ui.components.Radius.tile)).padding(14.dp),
        ) {
            if (text.isEmpty()) Text(tr("https://… или vless://…"), style = MaterialTheme.typography.bodyMedium, color = c.ink3)
            BasicTextField(
                text, { text = it },
                textStyle = MaterialTheme.typography.bodyMedium.copy(color = c.ink),
                cursorBrush = SolidColor(c.accent), modifier = Modifier.fillMaxWidth().height(80.dp),
            )
        }
        Spacer(Modifier.height(12.dp))
        AccentButton(if (busy) tr("Загружаю…") else tr("Добавить"), {
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
        modifier.pressScale(interaction, 0.95f).clip(RoundedCornerShape(app.ghostly.ui.components.Radius.tile))
            .background(c.surface2)
            .border(1.dp, c.hairline, RoundedCornerShape(app.ghostly.ui.components.Radius.tile))
            .hoverSound().clickable(interaction, null, onClick = onClick).padding(vertical = 18.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(icon, null, tint = c.accent, modifier = Modifier.size(26.dp))
        Spacer(Modifier.height(8.dp))
        Text(label, style = MaterialTheme.typography.labelMedium)
    }
}
