package app.ghostly.ui.screens

import app.ghostly.ui.tabSky
import app.ghostly.ui.travel
import app.ghostly.ui.components.nightGhost
import app.ghostly.ui.components.hoverSound
import androidx.compose.animation.AnimatedContent
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.CallSplit
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.rounded.AltRoute
import androidx.compose.material.icons.rounded.AccountCircle
import androidx.compose.material.icons.rounded.Devices
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material.icons.rounded.Payments
import androidx.compose.material.icons.rounded.SupportAgent
import androidx.compose.material.icons.rounded.AcUnit
import androidx.compose.material.icons.rounded.Animation
import androidx.compose.material.icons.rounded.AutoMode
import androidx.compose.material.icons.rounded.Block
import androidx.compose.material.icons.rounded.Cable
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Code
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.RocketLaunch
import androidx.compose.material.icons.rounded.Savings
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.Dns
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.Layers
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Memory
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.PowerSettingsNew
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.rounded.SwapHoriz
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.Vibration
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import app.ghostly.core.GhostlyController
import app.ghostly.core.model.AppSettings
import app.ghostly.core.model.CoreType
import app.ghostly.core.model.DesktopMode
import app.ghostly.core.model.DnsPreset
import app.ghostly.core.model.PingMethod
import app.ghostly.core.model.RoutingMode
import app.ghostly.core.model.RuApps
import app.ghostly.core.model.SplitMode
import app.ghostly.core.model.ThemeAccent
import app.ghostly.core.vpn.AppEntry
import app.ghostly.ui.components.GhostMark
import app.ghostly.ui.components.GhostSwitch
import app.ghostly.ui.components.GlassCard
import app.ghostly.ui.components.IconBubble
import app.ghostly.ui.components.SectionTitle
import app.ghostly.ui.components.Segmented
import app.ghostly.ui.components.SettingRow
import app.ghostly.ui.components.Spinner
import app.ghostly.ui.components.ToggleRow
import app.ghostly.ui.components.appear
import app.ghostly.ui.theme.Ghost
import app.ghostly.i18n.tr

private enum class Page { MAIN, ROUTING, DNS, APPS, PROXY, ADVANCED, ABOUT, LOGS }

const val GITHUB_URL = "https://github.com/Nelxi/ghostly-vpn"

@Composable
@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
fun SettingsScreen(controller: GhostlyController, contentPadding: PaddingValues) {
    var page by rememberSaveable { mutableStateOf(Page.MAIN) }
    // The system back gesture climbs out of a sub-page instead of leaving the app.
    // Predictive back: while the finger drags, the page slides aside and the main list shows beneath.
    var peek by remember { androidx.compose.runtime.mutableFloatStateOf(0f) }
    androidx.compose.ui.backhandler.PredictiveBackHandler(enabled = page != Page.MAIN) { progress ->
        try {
            progress.collect { peek = it.progress }
            peek = 0f
            page = Page.MAIN
        } catch (e: kotlin.coroutines.cancellation.CancellationException) {
            peek = 0f
            throw e
        }
    }
    androidx.compose.foundation.layout.Box(Modifier.fillMaxSize()) {
    // Where back leads, fully drawn beneath; the page on top turns into an opaque card that shrinks and slides off.
    if (peek > 0f && page != Page.MAIN) {
        androidx.compose.foundation.layout.Box(Modifier.fillMaxSize().graphicsLayer { compositingStrategy = androidx.compose.ui.graphics.CompositingStrategy.ModulateAlpha; alpha = 0.8f + 0.2f * peek }) {
            MainSettings(controller, contentPadding) {}
        }
    }
    val heading = app.ghostly.ui.rememberPageMove(page)
    AnimatedContent(
        modifier = Modifier.fillMaxSize().predictiveCard(peek, peekBrush()),
        targetState = page,
        // The pages turn like the tabs do: deeper into a section forwards, back out the other way.
        transitionSpec = { app.ghostly.ui.travelSpec() },
    ) { p ->
        val back = { page = Page.MAIN }
        androidx.compose.runtime.CompositionLocalProvider(app.ghostly.ui.components.LocalAnchorsActive provides (p == page)) {
        androidx.compose.foundation.layout.Box(Modifier.fillMaxSize().travel(this@AnimatedContent, heading, leaving = p != page)) {
        when (p) {
            Page.MAIN -> MainSettings(controller, contentPadding) { page = it }
            Page.ROUTING -> RoutingPage(controller, contentPadding, back)
            Page.DNS -> DnsPage(controller, contentPadding, back)
            Page.APPS -> AppsPage(controller, contentPadding, back)
            Page.PROXY -> ProxyPage(controller, contentPadding, back)
            Page.ADVANCED -> AdvancedPage(controller, contentPadding, back) { page = it }
            Page.ABOUT -> AboutPage(controller, contentPadding, back)
            Page.LOGS -> LogsPage(controller, contentPadding) { page = Page.ADVANCED }
        }
        }
        }
    }}
}

@Composable
private fun PageScaffold(title: String, contentPadding: PaddingValues, onBack: (() -> Unit)?, content: @Composable () -> Unit) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(contentPadding).padding(horizontal = 18.dp)) {
        // Above the horizon: the title, the ghost keeping watch beside it.
        Column(Modifier.fillMaxWidth().tabSky(app.ghostly.ui.Tab.SETTINGS)) {
            Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                if (onBack != null) {
                    IconBubble(Icons.AutoMirrored.Rounded.ArrowBack, onBack)
                    Spacer(Modifier.width(12.dp))
                }
                Text(title, style = MaterialTheme.typography.headlineMedium, modifier = Modifier.weight(1f).padding(start = 4.dp), maxLines = 2)
                androidx.compose.foundation.layout.Box(Modifier.size(64.dp).nightGhost(app.ghostly.ui.Tab.SETTINGS, label = "Ghostly"))
            }
            Spacer(Modifier.height(10.dp))
        }
        androidx.compose.runtime.CompositionLocalProvider(LocalCascade provides remember { Cascade() }) { content() }
        Spacer(Modifier.height(28.dp))
    }
}

@Composable
private fun Group(content: @Composable () -> Unit) {
    val cascade = LocalCascade.current
    val index = remember { cascade.next() }
    GlassCard(Modifier.fillMaxWidth().appear(index, step = 55L), padding = 10.dp) { content() }
}

/** Hands out entrance order to a page's groups so they float in one after another. */
private class Cascade {
    private var n = 0
    fun next() = n++
}

private val LocalCascade = androidx.compose.runtime.staticCompositionLocalOf { Cascade() }

@Composable
private fun Chevron() = Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null, tint = Ghost.colors.ink3)

// ============================================================================ main

@Composable
private fun MainSettings(controller: GhostlyController, contentPadding: PaddingValues, open: (Page) -> Unit) {
    val s by controller.settings.collectAsState()
    val c = Ghost.colors
    val set = controller::updateSettings
    val profiles by controller.profiles.collectAsState()
    val ghostly = remember(profiles) { controller.ghostlyProfile() }
    PageScaffold(tr("Настройки"), contentPadding, null) {
        // The Ghostly account lives in the app now (its own tab); these rows lead straight to its parts.
        val signedIn = controller.cabinet.api.hasSession
        if (ghostly != null || signedIn) {
            val go: (String) -> Unit = { section -> controller.haptic(); controller.openAccount(section) }
            SectionTitle(tr("Аккаунт Ghostly"))
            Group {
                SettingRow(tr("Личный кабинет"), ghostly?.name ?: tr("Подписка, баланс и устройства"), Icons.Rounded.AccountCircle, onClick = { go("cabinet") }) { Chevron() }
                SettingRow(tr("Продлить или сменить тариф"), tr("Картой, по СБП или с баланса, дни прибавятся к текущему сроку"), Icons.Rounded.Payments, onClick = { go("renew") }) { Chevron() }
                SettingRow(tr("Устройства"), tr("Отключить или запретить устройство на подписке"), Icons.Rounded.Devices, onClick = { go("devices") }) { Chevron() }
                SettingRow(tr("Пополнить баланс"), tr("Для продления, трафика и устройств"), Icons.Rounded.Savings, onClick = { go("topup") }) { Chevron() }
                ghostly?.supportUrl?.let { support ->
                    SettingRow(tr("Поддержка"), tr("Чат в Telegram, отвечаем сами"), Icons.Rounded.SupportAgent, onClick = { controller.haptic(); controller.platform.openUrl(support) }) { Chevron() }
                }
            }
        }

        SectionTitle(tr("Подключение"))
        Group {
            SettingRow(tr("Ядро"), null, Icons.Rounded.Memory)
            Segmented(listOf(CoreType.XRAY to "Xray", CoreType.MIHOMO to "Mihomo"), s.core, { v -> set { it.copy(core = v) } })
            Text(
                when (s.core) {
                    CoreType.XRAY -> tr("Рекомендуется. Одна кнопка «Авто», белые списки и умное переключение серверов. Подписки в формате Clash на этом ядре скрыты.")
                    CoreType.MIHOMO -> tr("Для подписок в формате Clash с группами-селекторами (выбор сервера внутри группы). Подписки только в формате Xray на этом ядре скрыты.")
                },
                style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(horizontal = 6.dp, vertical = 8.dp),
            )
            SettingRow(tr("Способ пинга"), tr("{0} · для {1}", s.pingMethodOf(s.core).title, coreName(s.core)), Icons.Rounded.Speed)
            Segmented(
                listOf(PingMethod.PROXY_GET to "GET", PingMethod.PROXY_HEAD to "HEAD", PingMethod.TCP to "TCP", PingMethod.ICMP to "ICMP"),
                s.pingMethodOf(s.core), { v -> set { it.withPing(it.core, method = v) } },
            )
            Spacer(Modifier.height(8.dp))
            SettingRow(tr("Маршрутизация"), when (s.routingMode) {
                RoutingMode.SMART -> tr("Умная: российские сайты напрямую")
                RoutingMode.GLOBAL -> tr("Весь трафик через VPN")
            }, Icons.AutoMirrored.Rounded.AltRoute, onClick = { open(Page.ROUTING) }) { Chevron() }
            SettingRow(
                "DNS",
                (if (s.dns == DnsPreset.CUSTOM) s.customDns.ifBlank { tr("Свой") } else if (s.dns == DnsPreset.PROVIDER) tr("Как в подписке") else s.dns.title) +
                    if (s.dnsDefaults) "" else tr(" · настроен"),
                Icons.Rounded.Dns, onClick = { open(Page.DNS) },
            ) { Chevron() }
            if (controller.platform.supportsPerAppSplit) {
                SettingRow(tr("Приложения"), when (s.splitMode) {
                    SplitMode.OFF -> tr("Все приложения через VPN")
                    SplitMode.ONLY_SELECTED -> tr("Только выбранные: {0}", s.splitApps.size)
                    SplitMode.BYPASS_SELECTED -> {
                        val extra = (s.splitApps - RuApps.PACKAGES).size
                        when {
                            !s.splitApps.containsAll(RuApps.PACKAGES) -> tr("В обход VPN: {0}", s.splitApps.size)
                            extra == 0 -> tr("Российские — в обход VPN")
                            else -> tr("Российские и ещё {0} — в обход VPN", extra)
                        }
                    }
                }, Icons.AutoMirrored.Rounded.CallSplit, onClick = { open(Page.APPS) }) { Chevron() }
            }
            if (controller.platform.isDesktop) {
                SettingRow(tr("Режим"), null, Icons.Rounded.Cable)
                Segmented(
                    listOf(DesktopMode.TUN to "TUN", DesktopMode.SYSTEM_PROXY to tr("Системный прокси"), DesktopMode.PROXY_ONLY to tr("Только прокси")),
                    s.desktopMode, { m -> set { it.copy(desktopMode = m) } },
                )
                Text(
                    when (s.desktopMode) {
                        DesktopMode.TUN -> tr("Весь трафик компьютера, включая игры и программы без настроек прокси. Нужны права администратора.")
                        DesktopMode.SYSTEM_PROXY -> tr("Браузеры и большинство программ идут через VPN автоматически. Без прав администратора.")
                        DesktopMode.PROXY_ONLY -> tr("Ghostly поднимает только SOCKS5/HTTP-прокси и ничего не меняет в Windows — укажите его в нужных программах.")
                    },
                    style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(horizontal = 6.dp, vertical = 8.dp),
                )
            }
            SettingRow(
                tr("Локальный прокси"),
                if (!controller.platform.isDesktop && !s.localProxy) tr("Выключен")
                else "SOCKS5 :${s.socksPort} · HTTP :${s.httpPort}" + if (s.proxyAuth) tr(" · с паролем") else "",
                Icons.Rounded.Cable, onClick = { open(Page.PROXY) },
            ) { Chevron() }
            ToggleRow(tr("Блокировать рекламу"), tr("Рекламные и трекинговые домены отсекаются на лету"), s.blockAds, Icons.Rounded.Block) { v -> set { it.copy(blockAds = v) } }
        }

        SectionTitle(tr("Поведение"))
        Group {
            ToggleRow(
                if (controller.platform.isDesktop) tr("Запуск вместе с системой") else tr("Включать при старте телефона"),
                if (controller.platform.isDesktop) tr("Ghostly стартует свёрнутым и сразу подключается")
                else tr("VPN поднимется сам после перезагрузки — даже без открытия приложения"),
                s.startOnBoot, Icons.Rounded.RocketLaunch,
            ) { v -> set { it.copy(startOnBoot = v) } }
            ToggleRow(tr("Автоподключение"), tr("Подключаться при открытии приложения"), s.autoConnect, Icons.Rounded.PowerSettingsNew) { v -> set { it.copy(autoConnect = v) } }
            if (controller.platform.isDesktop) {
                ToggleRow(tr("Сворачивать в трей"), tr("Крестик прячет окно, VPN продолжает работать. Выйти — через меню призрака в трее"), s.closeToTray, Icons.Rounded.Layers) { v -> set { it.copy(closeToTray = v) } }
            }
            ToggleRow(tr("Переподключение"), tr("Восстанавливать туннель при смене сети"), s.autoReconnect, Icons.Rounded.Refresh) { v -> set { it.copy(autoReconnect = v) } }
            ToggleRow(tr("Автосмена сервера"), tr("Если сервер перестал отвечать — переключиться на самый быстрый"), s.autoFailover, Icons.Rounded.SwapHoriz) { v -> set { it.copy(autoFailover = v) } }
            ToggleRow(tr("Сторож соединения"), tr("Каждые 20 секунд проверяет, что трафик реально идёт, и сам меняет сервер, если нет"), s.smartGuard, Icons.Rounded.Shield) { v -> set { it.copy(smartGuard = v) } }
            ToggleRow(tr("Беречь белые списки"), tr("Сама уходит на белые списки при блокировках и возвращается на обычные серверы, как только интернет снова нормальный"), s.saveWhitelist, Icons.Rounded.Savings) { v -> set { it.copy(saveWhitelist = v) } }
            ToggleRow(tr("Обновлять подписки"), tr("Автоматически, как просит провайдер"), s.autoUpdateSubs, Icons.Rounded.AutoMode) { v -> set { it.copy(autoUpdateSubs = v) } }
            if (controller.platform.updateAsset != null) {
                ToggleRow(tr("Искать обновления приложения"), tr("Каждые 2 минуты"), s.autoCheckUpdates, Icons.Rounded.Refresh) { v -> set { it.copy(autoCheckUpdates = v) } }
                ToggleRow(
                    tr("Ставить обновления автоматически"),
                    if (controller.platform.isDesktop) tr("Скачается, проверится по SHA-256 и установится само; VPN включится обратно")
                    else tr("Скачается само и откроет окно установки"),
                    s.autoInstallUpdates, Icons.Rounded.RocketLaunch,
                ) { v -> set { it.copy(autoInstallUpdates = v) } }
            }
            controller.platform.killSwitch?.let { ks ->
                // Asking the firewall spawns netsh — do it off the UI thread, once per screen.
                val reason by androidx.compose.runtime.produceState<String?>(tr("Проверяю брандмауэр…"), ks) {
                    value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { ks.unavailableReason() }
                }
                ToggleRow(
                    "Kill switch",
                    reason ?: tr("Если VPN неожиданно упадёт, интернет блокируется, пока туннель не вернётся — ни один пакет мимо VPN"),
                    s.killSwitch && reason == null, Icons.Rounded.Lock,
                ) { v -> val r = reason; if (r == null) set { it.copy(killSwitch = v) } else controller.toast(r) }
            }
            controller.platform.systemVpnSettings?.let { open ->
                SettingRow("Kill switch", tr("Системная настройка: «Постоянная VPN» + «Блокировать соединения без VPN»"), Icons.Rounded.Lock, onClick = open) { Chevron() }
            }
        }

        SectionTitle(tr("Внешний вид"))
        Group {
            val monetAccent = remember { controller.platform.systemAccent() }
            if (monetAccent != null) {
                ToggleRow(tr("Цвета системы"), if (controller.platform.isDesktop) tr("Акцент как в Windows (Параметры → Персонализация → Цвета)") else tr("Акцент берётся из обоев (Material You)"), s.monet, Icons.Rounded.Palette) { v -> set { it.copy(monet = v) } }
            }
            SettingRow(tr("Акцент"), if (s.monet && monetAccent != null) tr("Сейчас из обоев — выбор ниже вернёт свой") else null, Icons.Rounded.Palette) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ThemeAccent.entries.forEach { a ->
                        val col = Color(a.argb.toInt())
                        Box(
                            Modifier.size(26.dp).clip(CircleShape).background(col)
                                .border(2.dp, if (s.accent == a && !s.monet) c.ink else Color.Transparent, CircleShape)
                                .hoverSound().clickable { set { it.copy(accent = a, monet = false) } },
                            contentAlignment = Alignment.Center,
                        ) { if (s.accent == a && !s.monet) Icon(Icons.Rounded.Check, null, tint = Color.Black.copy(alpha = 0.7f), modifier = Modifier.size(15.dp)) }
                    }
                }
            }
            Box {
                var langOpen by remember { mutableStateOf(false) }
                val current = if (s.language == "system" || s.language.isBlank()) null else s.language
                val shown = current ?: app.ghostly.i18n.L10n.language
                SettingRow(
                    tr("Язык"), current?.let { app.ghostly.i18n.L10n.NATIVE_NAMES[it] } ?: tr("Как в системе"),
                    Icons.Rounded.Language, onClick = { langOpen = true },
                ) {
                    app.ghostly.i18n.L10n.FLAGS[shown]?.let { app.ghostly.ui.components.FlagIcon(it, 16.dp) }
                    Chevron()
                }
                androidx.compose.material3.DropdownMenu(langOpen, { langOpen = false }, modifier = Modifier.heightIn(max = 420.dp)) {
                    (listOf<String?>(null) + app.ghostly.i18n.L10n.SUPPORTED).forEach { code ->
                        androidx.compose.material3.DropdownMenuItem(
                            text = { Text(code?.let { app.ghostly.i18n.L10n.NATIVE_NAMES[it] } ?: tr("Как в системе")) },
                            leadingIcon = {
                                val flag = code?.let { app.ghostly.i18n.L10n.FLAGS[it] }
                                if (flag != null) app.ghostly.ui.components.FlagIcon(flag, 14.dp)
                                else Icon(Icons.Rounded.Language, null, modifier = Modifier.size(18.dp))
                            },
                            trailingIcon = { if (current == code) Icon(Icons.Rounded.Check, null, tint = c.accent) },
                            onClick = { langOpen = false; set { it.copy(language = code ?: "system") } },
                        )
                    }
                }
            }
            ToggleRow(tr("Меньше анимаций"), tr("Спокойнее и экономнее для батареи"), s.reduceMotion, Icons.Rounded.Animation) { v -> set { it.copy(reduceMotion = v) } }
            ToggleRow(tr("Снег"), tr("Зимой над ночным морем идёт снег"), s.snow, Icons.Rounded.AcUnit) { v -> set { it.copy(snow = v) } }
            controller.platform.stage?.let { stage ->
                ToggleRow(
                    tr("Сцена"), tr("Призрак подпевает музыке, которая играет {0}, а интерфейс светится и движется в её ритме и настроении", stage.whereLabel),
                    s.stageMode, Icons.Rounded.MusicNote,
                ) { v -> set { it.copy(stageMode = v) }; if (v) stage.requestSetup() }
                val need by stage.setupNeeded.collectAsState()
                if (s.stageMode && need != null) {
                    SettingRow(tr("Дать доступ для сцены"), need, Icons.Rounded.MusicNote, onClick = { stage.requestSetup() }) { Chevron() }
                }
            }
            ToggleRow(tr("Звуки"), tr("Мягкие звуки при подключении, ошибках и выборе сервера"), s.sounds, Icons.Rounded.MusicNote) { v -> set { it.copy(sounds = v) } }
            if (s.sounds) SoundVolume(controller, s.soundVolume) { v -> set { it.copy(soundVolume = v) } }
            if (!controller.platform.isDesktop) {
                ToggleRow(tr("Вибрация"), tr("Отклик на нажатия"), s.haptics, Icons.Rounded.Vibration) { v -> set { it.copy(haptics = v) } }
                if (s.haptics) HapticStrength(controller, s.hapticStrength) { v -> set { it.copy(hapticStrength = v) } }
            }
        }

        SectionTitle(tr("Ещё"))
        Group {
            SettingRow(tr("Для продвинутых"), tr("MTU, мультиплекс, фрагментация, IPv6, логи"), Icons.Rounded.Tune, onClick = { open(Page.ADVANCED) }) { Chevron() }
            SettingRow(tr("О приложении"), tr("Версия, ядро, исходный код"), Icons.Rounded.Info, onClick = { open(Page.ABOUT) }) { Chevron() }
        }
    }
}

// ============================================================================ routing

@Composable
private fun RoutingPage(controller: GhostlyController, contentPadding: PaddingValues, back: () -> Unit) {
    val s by controller.settings.collectAsState()
    val set = controller::updateSettings
    PageScaffold(tr("Маршрутизация"), contentPadding, back) {
        Spacer(Modifier.height(12.dp))
        Segmented(listOf(RoutingMode.SMART to tr("Умная"), RoutingMode.GLOBAL to tr("Всё через VPN")), s.routingMode, { m -> set { it.copy(routingMode = m) } })
        Text(
            when (s.routingMode) {
                RoutingMode.SMART -> tr("Госуслуги, банки, маркетплейсы и другие российские сайты открываются напрямую — быстрее и без капчи. Остальное идёт через VPN. Для подписок с собственными правилами используются правила провайдера.")
                RoutingMode.GLOBAL -> tr("Весь трафик, кроме локальной сети, идёт через VPN.")
            },
            style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(6.dp, 10.dp),
        )
        SectionTitle(tr("Свои правила"))
        Text(tr("По одному домену на строку: example.com, geosite:youtube, full:api.site.ru, regexp:…"), style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(start = 6.dp, bottom = 10.dp))
        DomainListEditor(tr("Напрямую"), s.directDomains) { l -> set { it.copy(directDomains = l) } }
        Spacer(Modifier.height(10.dp))
        DomainListEditor(tr("Через VPN"), s.proxyDomains) { l -> set { it.copy(proxyDomains = l) } }
        Spacer(Modifier.height(10.dp))
        DomainListEditor(tr("Блокировать"), s.blockDomains) { l -> set { it.copy(blockDomains = l) } }
    }
}

@Composable
private fun DomainListEditor(title: String, value: List<String>, onChange: (List<String>) -> Unit) {
    val c = Ghost.colors
    var text by remember(value) { mutableStateOf(value.joinToString("\n")) }
    GlassCard(Modifier.fillMaxWidth(), padding = 14.dp) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(title, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
            Text("${value.size}", style = MaterialTheme.typography.labelMedium, color = c.ink3)
        }
        Spacer(Modifier.height(8.dp))
        Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Color.Black.copy(alpha = 0.25f)).padding(12.dp)) {
            if (text.isEmpty()) Text(tr("пусто"), style = MaterialTheme.typography.bodySmall)
            BasicTextField(
                text,
                { t ->
                    text = t
                    onChange(t.lines().map { it.trim() }.filter { it.isNotEmpty() })
                },
                textStyle = MaterialTheme.typography.bodyMedium.copy(color = c.ink, fontFamily = FontFamily.Monospace),
                cursorBrush = SolidColor(c.accent),
                modifier = Modifier.fillMaxWidth().height(96.dp),
            )
        }
    }
}

// ============================================================================ dns

@Composable
private fun DnsPage(controller: GhostlyController, contentPadding: PaddingValues, back: () -> Unit) {
    val s by controller.settings.collectAsState()
    val c = Ghost.colors
    val set = controller::updateSettings
    PageScaffold("DNS", contentPadding, back) {
        Text(
            tr("Запросы устройства перехватывает ядро VPN. DNS «через VPN» идёт внутри туннеля, поэтому провайдер и ТСПУ не могут подменить ответы."),
            style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(6.dp, 12.dp),
        )

        SectionTitle(tr("Через VPN"))
        Group {
            DnsPreset.entries.forEach { p ->
                SettingRow(
                    if (p == DnsPreset.PROVIDER) tr("Как в подписке") else p.title,
                    if (p == DnsPreset.PROVIDER) tr("DNS, который задал провайдер подписки") else p.address,
                    onClick = { set { it.copy(dns = p) } },
                ) { if (s.dns == p) Icon(Icons.Rounded.Check, null, tint = c.accent) }
            }
            if (s.dns == DnsPreset.CUSTOM) DnsField(
                tr("Адрес"), s.customDns, tr("https://dns.example/dns-query, tls://1.1.1.1 или 9.9.9.9"),
            ) { v -> set { it.copy(customDns = v) } }
        }

        SectionTitle(tr("Без VPN"))
        Group {
            ToggleRow(
                tr("Российские сайты через свой DNS"),
                if (s.routingMode == RoutingMode.SMART) tr("Они открываются напрямую, и их адреса лучше узнавать у российского DNS")
                else tr("Работает только в умной маршрутизации"),
                s.dnsSplitRu, Icons.AutoMirrored.Rounded.AltRoute,
            ) { v -> set { it.copy(dnsSplitRu = v) } }
            if (s.dnsSplitRu) {
                app.ghostly.core.model.DirectDns.entries.forEach { d ->
                    SettingRow(d.title, when (d) {
                        app.ghostly.core.model.DirectDns.YANDEX -> tr("77.88.8.8 по HTTPS, мимо туннеля")
                        app.ghostly.core.model.DirectDns.SYSTEM -> tr("DNS сети или провайдера")
                        app.ghostly.core.model.DirectDns.CUSTOM -> tr("Любой адрес")
                    }, onClick = { set { it.copy(dnsDirect = d) } }) { if (s.dnsDirect == d) Icon(Icons.Rounded.Check, null, tint = c.accent) }
                }
                if (s.dnsDirect == app.ghostly.core.model.DirectDns.CUSTOM) DnsField(
                    tr("Адрес"), s.dnsDirectCustom, tr("https+local://dns.example/dns-query (+local: мимо туннеля), 77.88.8.1"),
                ) { v -> set { it.copy(dnsDirectCustom = v) } }
            }
        }

        SectionTitle(tr("Дополнительно"))
        Group {
            SettingRow(tr("Адреса IPv4 / IPv6"), s.dnsStrategy.title, Icons.Rounded.Language)
            Segmented(
                listOf(
                    app.ghostly.core.model.DnsStrategy.AUTO to tr("Авто"),
                    app.ghostly.core.model.DnsStrategy.IPV4 to "IPv4",
                    app.ghostly.core.model.DnsStrategy.IPV4_FIRST to "4 → 6",
                    app.ghostly.core.model.DnsStrategy.IPV6_FIRST to "6 → 4",
                    app.ghostly.core.model.DnsStrategy.BOTH to tr("Оба"),
                ),
                s.dnsStrategy, { v -> set { it.copy(dnsStrategy = v) } },
            )
            Spacer(Modifier.height(6.dp))
            ToggleRow(tr("Кэш DNS"), tr("Повторные запросы отвечаются мгновенно"), s.dnsCache, Icons.Rounded.Speed) { v -> set { it.copy(dnsCache = v) } }
            ToggleRow(
                "Fake-IP (Mihomo)", tr("Сайты открываются без ожидания DNS, имена узнаёт сервер. Выключите, если какое-то приложение не работает"),
                s.dnsFakeIp, Icons.Rounded.RocketLaunch,
            ) { v -> set { it.copy(dnsFakeIp = v) } }
            DnsField(
                tr("Загрузочный DNS (Mihomo)"), s.dnsBootstrap, tr("Обычный IP. Узнаёт адреса DoH-серверов до того, как поднимется туннель"),
            ) { v -> set { it.copy(dnsBootstrap = v) } }
        }

        SectionTitle(tr("Свои записи"))
        DomainListEditor(tr("hosts: домен и IP через пробел"), s.dnsHosts) { v -> set { it.copy(dnsHosts = v) } }
        Text(
            tr("Например: example.com 93.184.216.34. Серверы Ghostly прописаны всегда, поэтому подключение работает, даже если их домены блокируют."),
            style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(6.dp, 8.dp),
        )
        if (!s.dnsDefaults) {
            SectionTitle(tr("Сброс"))
            Group {
                SettingRow(tr("Вернуть DNS по умолчанию"), tr("Подписки снова используют свой DNS"), Icons.Rounded.Refresh, onClick = {
                    set { it.copy(dns = DnsPreset.PROVIDER, dnsSplitRu = true, dnsDirect = app.ghostly.core.model.DirectDns.YANDEX,
                        dnsStrategy = app.ghostly.core.model.DnsStrategy.AUTO, dnsCache = true, dnsHosts = emptyList(),
                        dnsFakeIp = true, dnsBootstrap = "77.88.8.8") }
                }) {}
            }
        }
    }
}

/** A labelled one-line input inside a settings group. */
@Composable
private fun DnsField(label: String, value: String, hint: String, onChange: (String) -> Unit) {
    val c = Ghost.colors
    var text by remember(value) { mutableStateOf(value) }
    Column(Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 8.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = c.ink3)
        Spacer(Modifier.height(6.dp))
        Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Color.Black.copy(alpha = 0.25f)).padding(12.dp)) {
            BasicTextField(
                text, { t -> text = t; onChange(t.trim()) }, singleLine = true,
                textStyle = MaterialTheme.typography.bodyMedium.copy(color = c.ink, fontFamily = FontFamily.Monospace),
                cursorBrush = SolidColor(c.accent), modifier = Modifier.fillMaxWidth(),
            )
        }
        Spacer(Modifier.height(4.dp))
        Text(hint, style = MaterialTheme.typography.bodySmall)
    }
}

// ============================================================================ per-app

@Composable
private fun AppsPage(controller: GhostlyController, contentPadding: PaddingValues, back: () -> Unit) {
    val s by controller.settings.collectAsState()
    val c = Ghost.colors
    var apps by remember { mutableStateOf<List<AppEntry>?>(null) }
    var showSystem by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    LaunchedEffect(Unit) { apps = controller.platform.installedApps().sortedBy { it.label.lowercase() } }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = contentPadding) {
        item {
            Column(Modifier.padding(horizontal = 18.dp)) {
                Row(Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconBubble(Icons.AutoMirrored.Rounded.ArrowBack, back)
                    Spacer(Modifier.width(12.dp))
                    Text(tr("Приложения"), style = MaterialTheme.typography.headlineMedium)
                }
                Segmented(
                    listOf(SplitMode.OFF to tr("Все"), SplitMode.ONLY_SELECTED to tr("Только эти"), SplitMode.BYPASS_SELECTED to tr("Кроме этих")),
                    s.splitMode, { m -> controller.updateSettings { it.withSplitMode(m) } },
                )
                Text(
                    when (s.splitMode) {
                        SplitMode.OFF -> tr("Все приложения работают через VPN.")
                        SplitMode.ONLY_SELECTED -> tr("Через VPN пойдут только отмеченные приложения.")
                        SplitMode.BYPASS_SELECTED -> tr("Отмеченные приложения работают напрямую. Российские (банки, Госуслуги, MAX, маркетплейсы) отмечены сразу: так они видят твой обычный IP, а не адрес VPN.")
                    },
                    style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(6.dp, 10.dp),
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.weight(1f).height(44.dp).clip(RoundedCornerShape(app.ghostly.ui.components.Radius.tile)).background(Ghost.colors.surface3).border(1.dp, Ghost.colors.hairline, RoundedCornerShape(app.ghostly.ui.components.Radius.tile)).padding(horizontal = 12.dp), contentAlignment = Alignment.CenterStart) {
                        if (query.isEmpty()) Text(tr("Поиск приложений"), style = MaterialTheme.typography.bodyMedium, color = c.ink3)
                        BasicTextField(query, { query = it }, singleLine = true, textStyle = MaterialTheme.typography.bodyMedium.copy(color = c.ink), cursorBrush = SolidColor(c.accent), modifier = Modifier.fillMaxWidth())
                    }
                    Spacer(Modifier.width(10.dp))
                    Text(tr("Системные"), style = MaterialTheme.typography.labelMedium, color = c.ink3)
                    Spacer(Modifier.width(6.dp))
                    Box(Modifier.hoverSound().clickable { showSystem = !showSystem }) { GhostSwitch(showSystem) }
                }
                Spacer(Modifier.height(8.dp))
            }
        }
        val list = apps
        if (list == null) {
            item { Box(Modifier.fillMaxWidth().padding(40.dp), contentAlignment = Alignment.Center) { Spinner(c.accent, Modifier.size(28.dp)) } }
        } else {
            val shown = list.filter { (showSystem || !it.isSystem || it.packageName in s.splitApps) && (query.isBlank() || it.label.contains(query, true) || it.packageName.contains(query, true)) }
                .sortedByDescending { it.packageName in s.splitApps }
            items(shown, key = { it.packageName }) { app ->
                val checked = app.packageName in s.splitApps
                Row(
                    Modifier.fillMaxWidth().hoverSound().clickable {
                        controller.updateSettings { st -> st.copy(splitApps = if (checked) st.splitApps - app.packageName else st.splitApps + app.packageName) }
                    }.padding(horizontal = 22.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(Modifier.size(36.dp).clip(RoundedCornerShape(12.dp)).background(c.accent.copy(alpha = 0.12f)), contentAlignment = Alignment.Center) {
                        Text(app.label.take(1).uppercase(), style = MaterialTheme.typography.titleSmall, color = c.accent)
                    }
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(app.label, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(app.packageName, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    Box(
                        Modifier.size(24.dp).clip(RoundedCornerShape(8.dp))
                            .background(if (checked) c.accent else Color.Transparent)
                            .border(1.5.dp, if (checked) c.accent else c.ink3, RoundedCornerShape(8.dp)),
                        contentAlignment = Alignment.Center,
                    ) { if (checked) Icon(Icons.Rounded.Check, null, tint = c.accentInk, modifier = Modifier.size(16.dp)) }
                }
            }
        }
        item { Spacer(Modifier.height(24.dp)) }
    }
}

// ============================================================================ local proxy

@Composable
private fun ProxyPage(controller: GhostlyController, contentPadding: PaddingValues, back: () -> Unit) {
    val s by controller.settings.collectAsState()
    val c = Ghost.colors
    val set = controller::updateSettings
    PageScaffold(tr("Локальный прокси"), contentPadding, back) {
        Text(
            if (controller.platform.isDesktop) tr("SOCKS5 и HTTP-прокси на этом компьютере — для браузеров, Telegram, игр и программ, которые умеют работать через прокси.")
            else tr("SOCKS5 и HTTP-прокси на телефоне, пока VPN включён — например, для Telegram или приложений, которые нужно пустить через прокси."),
            style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(6.dp, 12.dp),
        )
        Group {
            if (!controller.platform.isDesktop) {
                ToggleRow(tr("Включить"), tr("Поднимать прокси вместе с VPN"), s.localProxy, Icons.Rounded.PowerSettingsNew) { v -> set { it.copy(localProxy = v) } }
            }
            ToggleRow(tr("Доступ из локальной сети"), tr("Раздавать прокси другим устройствам в твоей Wi-Fi сети"), s.allowLan, Icons.Rounded.Language) { v -> set { it.copy(allowLan = v) } }
        }
        SectionTitle(tr("Порты"))
        Group {
            Row(Modifier.fillMaxWidth().padding(4.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                PortField("SOCKS5", s.socksPort, Modifier.weight(1f)) { p -> set { it.copy(socksPort = p) } }
                PortField("HTTP", s.httpPort, Modifier.weight(1f)) { p -> set { it.copy(httpPort = p) } }
            }
            if (s.socksPort == s.httpPort) Text(tr("Порты должны отличаться"), style = MaterialTheme.typography.bodySmall, color = c.bad, modifier = Modifier.padding(6.dp))
        }
        SectionTitle(tr("Авторизация"))
        Group {
            ToggleRow(tr("Логин и пароль"), tr("Чтобы чужие программы и устройства не пользовались твоим прокси"), s.proxyAuth, Icons.Rounded.Lock) { v -> set { it.copy(proxyAuth = v) } }
            if (s.proxyAuth) {
                CredentialField(tr("Логин"), s.proxyUser, controller) { v -> set { it.copy(proxyUser = v) } }
                Spacer(Modifier.height(8.dp))
                CredentialField(tr("Пароль"), s.proxyPass, controller, secret = true) { v -> set { it.copy(proxyPass = v) } }
                Spacer(Modifier.height(6.dp))
                SettingRow(tr("Сгенерировать новые"), tr("ghostly_… и случайный пароль"), Icons.Rounded.Refresh, onClick = { controller.regenerateProxyCredentials() })
            }
        }
        if (controller.platform.isDesktop) {
            Text(
                tr("Системный прокси Windows/macOS ходит через отдельный внутренний порт без пароля — браузеры не будут спрашивать логин."),
                style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(6.dp, 12.dp),
            )
        }
        Spacer(Modifier.height(12.dp))
        val lan = remember(s.allowLan) { if (s.allowLan) controller.platform.lanAddress() else null }
        val host = if (s.allowLan) lan ?: tr("адрес не найден — подключись к Wi-Fi") else "127.0.0.1"
        if (s.allowLan) {
            Text(
                tr("Для других устройств в твоей сети адрес прокси — {0}. Для программ на этом устройстве — 127.0.0.1.", host),
                style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(6.dp, 0.dp, 6.dp, 10.dp),
            )
        }
        val auth = if (s.proxyAuth) "${s.proxyUser}:${s.proxyPass}@" else ""
        val link = "socks5://$auth$host:${s.socksPort}"
        val shownLink = if (s.proxyAuth) "socks5://${s.proxyUser}:••••••@$host:${s.socksPort}" else link
        GlassCard(Modifier.fillMaxWidth(), padding = 14.dp, onClick = { controller.platform.copyToClipboard(link) }) {
            Text(tr("Нажми, чтобы скопировать"), style = MaterialTheme.typography.labelSmall, color = c.ink3)
            Text(shownLink, style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace, color = c.ink))
        }
        ExtraProxiesSection(controller, if (s.allowLan) lan ?: "127.0.0.1" else "127.0.0.1")
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
internal fun PortField(label: String, value: Int, modifier: Modifier, onChange: (Int) -> Unit) {
    val c = Ghost.colors
    var text by remember(value) { mutableStateOf(value.toString()) }
    val valid = text.toIntOrNull()?.let { it in 1024..65535 } == true
    Column(
        modifier.clip(RoundedCornerShape(16.dp)).background(Color.Black.copy(alpha = 0.22f))
            .border(1.dp, if (valid) c.line else c.bad.copy(alpha = 0.6f), RoundedCornerShape(16.dp)).padding(12.dp),
    ) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = c.ink3)
        BasicTextField(
            text,
            { t ->
                text = t.filter { it.isDigit() }.take(5)
                text.toIntOrNull()?.takeIf { it in 1024..65535 }?.let(onChange)
            },
            singleLine = true,
            textStyle = MaterialTheme.typography.titleLarge.copy(color = c.ink, fontFamily = FontFamily.Monospace),
            cursorBrush = SolidColor(c.accent),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
internal fun CredentialField(label: String, value: String, controller: GhostlyController, secret: Boolean = false, onChange: (String) -> Unit) {
    val c = Ghost.colors
    var text by remember(value) { mutableStateOf(value) }
    var shown by remember { mutableStateOf(!secret) }
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Color.Black.copy(alpha = 0.22f))
            .border(1.dp, c.line, RoundedCornerShape(16.dp)).padding(start = 12.dp, top = 8.dp, bottom = 8.dp, end = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.labelSmall, color = c.ink3)
            BasicTextField(
                text,
                { t ->
                    text = t.filterNot { it.isWhitespace() || it == ':' || it == '@' }.take(64)
                    if (text.isNotEmpty()) onChange(text)
                },
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyLarge.copy(color = c.ink, fontFamily = FontFamily.Monospace),
                cursorBrush = SolidColor(c.accent), modifier = Modifier.fillMaxWidth(),
                visualTransformation = if (shown) androidx.compose.ui.text.input.VisualTransformation.None
                else androidx.compose.ui.text.input.PasswordVisualTransformation('•'),
            )
        }
        if (secret) {
            IconBubble(if (shown) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility, { shown = !shown }, size = 36.dp)
            Spacer(Modifier.width(6.dp))
        }
        IconBubble(Icons.Rounded.ContentCopy, { controller.platform.copyToClipboard(text) }, size = 36.dp)
    }
}

// ============================================================================ advanced

@Composable
private fun AdvancedPage(controller: GhostlyController, contentPadding: PaddingValues, back: () -> Unit, open: (Page) -> Unit) {
    val s by controller.settings.collectAsState()
    val set = controller::updateSettings
    PageScaffold(tr("Для продвинутых"), contentPadding, back) {
        SectionTitle(tr("Туннель"))
        Group {
            ToggleRow(tr("Мультиплекс (mux)"), tr("Несколько соединений в одном — меньше рукопожатий. Не для Vision/XHTTP"), s.mux, Icons.Rounded.Layers) { v -> set { it.copy(mux = v) } }
            ToggleRow(tr("Фрагментация TLS"), tr("Режет ClientHello на части — помогает против DPI для TLS-серверов"), s.fragment, Icons.Rounded.Code) { v -> set { it.copy(fragment = v) } }
            ToggleRow(tr("Сниффинг"), tr("Определять домен по трафику для точной маршрутизации"), s.sniffing, Icons.Rounded.Speed) { v -> set { it.copy(sniffing = v) } }
            ToggleRow("IPv6", tr("Только если сервер умеет IPv6 — у серверов Ghostly его нет, сайты тогда не откроются"), s.ipv6, Icons.Rounded.Language) { v -> set { it.copy(ipv6 = v) } }
            ToggleRow(tr("Блокировать QUIC"), tr("YouTube и браузеры переходят с UDP на TCP — стабильнее на мобильном интернете и в сетях, режущих UDP"), s.blockQuic, Icons.Rounded.Block) { v -> set { it.copy(blockQuic = v) } }
            ToggleRow("TCP Fast Open", tr("На одно рукопожатие меньше при каждом новом соединении"), s.tcpFastOpen, Icons.Rounded.RocketLaunch) { v -> set { it.copy(tcpFastOpen = v) } }
            SettingRow("MTU", "${s.mtu}", Icons.Rounded.Tune)
            Segmented(listOf(1280 to "1280", 1400 to "1400", 1500 to "1500", 9000 to "9000"), s.mtu, { v -> set { it.copy(mtu = v) } })
            Spacer(Modifier.height(6.dp))
        }
        // Ping and the log are set per core; the switch picks which core's settings are shown.
        var core by remember { mutableStateOf(s.core) }
        SectionTitle(tr("Настройки ядра"))
        Group {
            Segmented(listOf(CoreType.XRAY to "Xray", CoreType.MIHOMO to "mihomo"), core, { core = it })
            Text(
                tr("Пинг и журнал настраиваются отдельно для каждого ядра.") + if (core != s.core) tr(" Сейчас используется {0}.", coreName(s.core)) else "",
                style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(horizontal = 6.dp, vertical = 8.dp),
            )
        }
        val method = s.pingMethodOf(core)
        SectionTitle(tr("Пинг · {0}", coreName(core)))
        Group {
            SettingRow(tr("Способ"), method.title, Icons.Rounded.Speed)
            Segmented(
                listOf(PingMethod.PROXY_GET to "GET", PingMethod.PROXY_HEAD to "HEAD", PingMethod.TCP to "TCP", PingMethod.ICMP to "ICMP"),
                method, { v -> set { it.withPing(core, method = v) } },
            )
            Text(
                when (method) {
                    PingMethod.PROXY_GET -> tr("Запрос через сервер к адресу проверки — настоящая задержка туннеля.")
                    PingMethod.PROXY_HEAD -> tr("То же через сервер, но запрос HEAD — без тела ответа, чуть быстрее.")
                    PingMethod.TCP -> tr("Только соединение с сервером: быстро, но не проверяет, что туннель работает. UDP-серверы проверяются через ядро.")
                    PingMethod.ICMP -> tr("Системный ping адреса сервера. Многие серверы его не пропускают — тогда будет «нет», хотя сервер рабочий.")
                },
                style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(horizontal = 6.dp, vertical = 8.dp),
            )
        }
        if (method == PingMethod.PROXY_GET || method == PingMethod.PROXY_HEAD) SectionTitle(tr("Адрес проверки"))
        if (method == PingMethod.PROXY_GET || method == PingMethod.PROXY_HEAD) Group {
            Segmented(
                listOf(
                    "https://www.gstatic.com/generate_204" to "Google",
                    "https://cp.cloudflare.com/generate_204" to "Cloudflare",
                    "https://www.apple.com/library/test/success.html" to "Apple",
                ),
                s.pingUrlOf(core), { v -> set { it.withPing(core, url = v) } },
            )
        }
        SectionTitle(tr("Проверка блокировок"))
        Group {
            ToggleRow(
                tr("Проверять недоступные серверы"),
                tr("Если сервер не пингуется, Ghostly стучится к нему напрямую и пишет причину: блок IP, обрыв TLS (DPI) или заморозка ТСПУ после ~16 КБ"),
                s.blockCheck, Icons.Rounded.Shield,
            ) { v -> set { it.copy(blockCheck = v) } }
        }
        SectionTitle(tr("Журнал · {0}", coreName(core)))
        Group {
            Segmented(listOf("none" to tr("Выкл"), "error" to tr("Ошибки"), "warning" to tr("Важное"), "info" to tr("Всё"), "debug" to "Debug"), s.logLevelOf(core), { v -> set { it.withLogLevel(core, v) } })
            SettingRow(tr("Открыть журнал"), tr("Что ядро писало при последнем запуске — посмотреть, скопировать, отправить"), Icons.Rounded.Code, onClick = { open(Page.LOGS) }) { Chevron() }
        }
        SectionTitle(tr("Сброс"))
        Group {
            var armed by remember { mutableStateOf(false) }
            LaunchedEffect(armed) { if (armed) { kotlinx.coroutines.delay(4000); armed = false } }
            SettingRow(
                if (armed) tr("Нажми ещё раз для сброса") else tr("Сбросить настройки"),
                tr("Все параметры — по умолчанию. Подписки, серверы и логин/пароль прокси остаются"),
                Icons.Rounded.Refresh,
                onClick = {
                    if (!armed) armed = true
                    else {
                        armed = false
                        set { AppSettings(proxyUser = it.proxyUser, proxyPass = it.proxyPass, socksPort = it.socksPort, httpPort = it.httpPort) }
                    }
                },
            ) {}
        }
    }
}

// ============================================================================ about

@Composable
private fun AboutPage(controller: GhostlyController, contentPadding: PaddingValues, back: () -> Unit) {
    val c = Ghost.colors
    PageScaffold(tr("О приложении"), contentPadding, back) {
        Column(Modifier.fillMaxWidth().padding(vertical = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            GhostMark(Modifier.size(110.dp))
            Spacer(Modifier.height(10.dp))
            Text("Ghostly VPN", style = MaterialTheme.typography.headlineMedium)
            Text(tr("версия {0}", controller.platform.appVersion), style = MaterialTheme.typography.bodySmall)
        }
        app.ghostly.ui.components.UpdateBanner(controller, Modifier.padding(bottom = 12.dp))
        Group {
            SettingRow(tr("Ядро"), controller.backend.coreVersion(), Icons.Rounded.Speed)
            SettingRow(
                tr("Журнал ядра"), tr("Скопировать журнал последнего запуска для поддержки"), Icons.Rounded.ContentCopy,
                onClick = { controller.haptic(); controller.exportLogs(controller.settings.value.core, share = false) },
            ) { Chevron() }
            if (controller.platform.updateAsset != null) {
                val last by controller.updater.lastCheck.collectAsState()
                SettingRow(tr("Проверить обновления"), last ?: tr("Скачиваются с нашего сервера и проверяются по SHA-256"), Icons.Rounded.Refresh, onClick = { controller.checkUpdates(manual = true) }) { Chevron() }
            }
            SettingRow(tr("Исходный код"), tr("Открытый проект на GitHub · GPL-3.0"), Icons.Rounded.Code, onClick = { controller.platform.openUrl(GITHUB_URL) }) { Chevron() }
            SettingRow(tr("Сайт"), "ghostlynex.fun", Icons.Rounded.Language, onClick = { controller.platform.openUrl("https://ghostlinknex.online") }) { Chevron() }
        }
        Spacer(Modifier.height(18.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
            Text(tr("Сделано с "), style = MaterialTheme.typography.bodySmall)
            Icon(Icons.Rounded.Favorite, null, tint = c.accent, modifier = Modifier.size(12.dp))
            Text(" · Xray-core, Compose Multiplatform", style = MaterialTheme.typography.bodySmall)
        }
    }
}


private fun coreName(core: CoreType) = if (core == CoreType.MIHOMO) "mihomo" else "Xray"

// ============================================================================ haptics

/** Strength of the vibration: motors differ a lot, so the user tunes it; lifting the finger plays a sample. */
@Composable
private fun HapticStrength(controller: GhostlyController, value: Float, onChange: (Float) -> Unit) {
    val c = Ghost.colors
    var v by remember(value) { mutableStateOf(value) }
    Column(Modifier.padding(start = 6.dp, end = 6.dp, bottom = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(tr("Сила"), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            Text(
                when {
                    v < 0.25f -> tr("едва заметно")
                    v < 0.5f -> tr("мягко")
                    v <= 0.7f -> tr("как в системе")
                    v < 0.9f -> tr("ощутимо")
                    else -> tr("максимум")
                },
                style = MaterialTheme.typography.bodySmall,
            )
        }
        androidx.compose.material3.Slider(
            value = v,
            onValueChange = { v = it },
            onValueChangeFinished = {
                onChange(v)
                controller.platform.haptic(app.ghostly.core.vpn.Haptic.HEAVY, v)
            },
            colors = androidx.compose.material3.SliderDefaults.colors(
                thumbColor = c.accent, activeTrackColor = c.accent, inactiveTrackColor = c.ink3.copy(alpha = 0.25f),
            ),
        )
        Text(tr("Если отклик почти не чувствуется, двигай вправо: в конце шкалы к нажатию добавляется плотный удар"), style = MaterialTheme.typography.bodySmall)
    }
}

/** Volume of the UI sounds; lifting the finger plays the "connected" chime at the new level. */
@Composable
private fun SoundVolume(controller: GhostlyController, value: Float, onChange: (Float) -> Unit) {
    val c = Ghost.colors
    var v by remember(value) { mutableStateOf(value) }
    Column(Modifier.padding(start = 6.dp, end = 6.dp, bottom = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(tr("Громкость"), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            Text("${(v * 100).toInt()}%", style = MaterialTheme.typography.bodySmall)
        }
        androidx.compose.material3.Slider(
            value = v,
            onValueChange = { v = it },
            onValueChangeFinished = {
                onChange(v)
                controller.platform.playSound(app.ghostly.core.vpn.Haptic.SUCCESS, v)
            },
            colors = androidx.compose.material3.SliderDefaults.colors(
                thumbColor = c.accent, activeTrackColor = c.accent, inactiveTrackColor = c.ink3.copy(alpha = 0.25f),
            ),
        )
    }
}

// ============================================================================ logs

/** The core's log of the last run: coloured by level, refreshed live, copy / share for support. */
@Composable
private fun LogsPage(controller: GhostlyController, contentPadding: PaddingValues, back: () -> Unit) {
    val c = Ghost.colors
    val s by controller.settings.collectAsState()
    var core by remember { mutableStateOf(s.core) }
    var onlyErrors by remember { mutableStateOf(false) }
    var text by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(core) {
        text = null
        while (true) {
            text = controller.coreLogs(core) ?: ""
            kotlinx.coroutines.delay(2000)
        }
    }
    PageScaffold(tr("Журнал ядра"), contentPadding, back) {
        Group {
            Segmented(listOf(CoreType.XRAY to "Xray", CoreType.MIHOMO to "mihomo"), core, { core = it })
            ToggleRow(tr("Только ошибки и предупреждения"), null, onlyErrors, Icons.Rounded.Block) { onlyErrors = it }
            SettingRow(tr("Скопировать"), tr("С версией приложения и устройства — для поддержки"), Icons.Rounded.ContentCopy, onClick = { controller.haptic(); controller.exportLogs(core, share = false) }) { Chevron() }
            if (!controller.platform.isDesktop) {
                SettingRow(tr("Отправить"), tr("В Telegram или куда удобно"), Icons.Rounded.Language, onClick = { controller.haptic(); controller.exportLogs(core, share = true) }) { Chevron() }
            }
        }
        Spacer(Modifier.height(12.dp))
        val lines = text?.lines()?.filter { it.isNotBlank() }.orEmpty()
            .let { all -> if (onlyErrors) all.filter { logLevel(it) >= 2 } else all }
            .takeLast(400)
        Group {
            when {
                text == null -> Text(tr("Загружаю…"), style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(8.dp))
                lines.isEmpty() -> Text(
                    if (s.logLevelOf(core) == "none") tr("Журнал выключен — выбери уровень в «Для продвинутых».")
                    else tr("Пусто. Подключись на ядре {0}, и здесь появятся его сообщения.", coreName(core)),
                    style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(8.dp),
                )
                else -> androidx.compose.foundation.text.selection.SelectionContainer {
                    Column(Modifier.padding(vertical = 6.dp)) {
                        lines.forEach { line ->
                            Text(
                                line,
                                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace, fontSize = 11.sp, lineHeight = 14.sp),
                                color = when (logLevel(line)) {
                                    3 -> c.bad
                                    2 -> c.warn
                                    0 -> c.ink3
                                    else -> c.ink2
                                },
                                modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}

/** 0 debug · 1 info · 2 warning · 3 error — Xray writes [Warning], mihomo level=warning / WRN. */
private fun logLevel(line: String): Int {
    val l = line.lowercase()
    return when {
        "[error]" in l || "level=error" in l || " err " in l || "failed" in l || "fatal" in l || "panic" in l -> 3
        "[warning]" in l || "level=warning" in l || " wrn " in l || "[warn" in l -> 2
        "[debug]" in l || "level=debug" in l || " dbg " in l -> 0
        else -> 1
    }
}

/**
 * The screen under the finger during a predictive back gesture: shrinks, slides right, gets rounded
 * corners, a shadow and an opaque background (screens are transparent over the aurora, so without it
 * the destination beneath couldn't be seen). The background is [peekBrush], a still copy of the
 * aurora's tint, so the card doesn't turn into a black slab.
 */
internal fun Modifier.predictiveCard(p: Float, bg: androidx.compose.ui.graphics.Brush): Modifier = if (p <= 0f) this else this
    .graphicsLayer {
        val k = 1f - 0.14f * p
        scaleX = k; scaleY = k
        translationX = size.width * 0.22f * p
        shadowElevation = 24.dp.toPx() * p
        shape = androidx.compose.foundation.shape.RoundedCornerShape(32.dp * p)
        clip = true
    }
    .background(bg)

/** Opaque stand-in for the aurora: violet glow on top, the base colour in the middle, a faint glow below. */
@Composable
internal fun peekBrush(): androidx.compose.ui.graphics.Brush {
    val c = app.ghostly.ui.theme.Ghost.colors
    return androidx.compose.ui.graphics.Brush.verticalGradient(
        listOf(
            androidx.compose.ui.graphics.lerp(c.bgRaised, c.accent2, 0.30f),
            androidx.compose.ui.graphics.lerp(c.bgRaised, c.accent2, 0.12f),
            androidx.compose.ui.graphics.lerp(c.bgRaised, c.accent2, 0.18f),
        ),
    )
}
