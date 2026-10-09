package app.ghostly.ui.screens

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
    AnimatedContent(
        modifier = Modifier.fillMaxSize().predictiveCard(peek, peekBrush()),
        targetState = page,
        transitionSpec = {
            val forward = targetState != Page.MAIN
            // slide only (a fade cuts the groups' shadows); the old page leaves at once
            slideInHorizontally(app.ghostly.ui.theme.Motion.quick(320)) { if (forward) it / 4 else -it / 4 } togetherWith androidx.compose.animation.ExitTransition.None
        },
    ) { p ->
        val back = { page = Page.MAIN }
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
    }}
}

@Composable
private fun PageScaffold(title: String, contentPadding: PaddingValues, onBack: (() -> Unit)?, content: @Composable () -> Unit) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(contentPadding).padding(horizontal = 18.dp)) {
        Row(Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            if (onBack != null) {
                IconBubble(Icons.AutoMirrored.Rounded.ArrowBack, onBack)
                Spacer(Modifier.width(12.dp))
            }
            Text(title, style = MaterialTheme.typography.headlineMedium)
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
    PageScaffold("Настройки", contentPadding, null) {
        // Ghostly's own subscription: the account (what the bot and the site do) is one tap away.
        if (ghostly != null) {
            val go: (String) -> Unit = { section -> controller.haptic(); controller.openAccount(section) }
            SectionTitle("Аккаунт Ghostly")
            Group {
                SettingRow("Личный кабинет", ghostly.name, Icons.Rounded.AccountCircle, onClick = { go("cabinet") }) { Chevron() }
                SettingRow("Продлить или сменить тариф", "Картой или по СБП, дни прибавятся к текущему сроку", Icons.Rounded.Payments, onClick = { go("renew") }) { Chevron() }
                SettingRow("Устройства, трафик, промокоды", "Отключить устройство, докупить гигабайты, ввести промокод, баланс и рефералы", Icons.Rounded.Devices, onClick = { go("cabinet") }) { Chevron() }
                SettingRow("Уведомления", "Сообщения сервиса об этой подписке", Icons.Rounded.Notifications, onClick = { go("notices") }) { Chevron() }
                ghostly.supportUrl?.let { support ->
                    SettingRow("Поддержка", "Чат в Telegram, отвечаем сами", Icons.Rounded.SupportAgent, onClick = { controller.haptic(); controller.platform.openUrl(support) }) { Chevron() }
                }
                Text(
                    "Кабинет открывается в браузере уже с вашим аккаунтом — на устройстве, где подписку добавили первой. " +
                        "На другом своём устройстве войдите на сайте один раз и подтвердите его: дальше откроется так же. " +
                        "Пароль и двухэтапная защита меняются только после обычного входа на сайте.",
                    style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(horizontal = 6.dp, vertical = 8.dp),
                )
            }
        }

        SectionTitle("Подключение")
        Group {
            SettingRow("Ядро", null, Icons.Rounded.Memory)
            Segmented(listOf(CoreType.XRAY to "Xray", CoreType.MIHOMO to "Mihomo"), s.core, { v -> set { it.copy(core = v) } })
            Text(
                when (s.core) {
                    CoreType.XRAY -> "Рекомендуется. Одна кнопка «Авто», белые списки и умное переключение серверов. Подписки в формате Clash на этом ядре скрыты."
                    CoreType.MIHOMO -> "Для подписок в формате Clash с группами-селекторами (выбор сервера внутри группы). Подписки только в формате Xray на этом ядре скрыты."
                },
                style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(horizontal = 6.dp, vertical = 8.dp),
            )
            SettingRow("Способ пинга", "${s.pingMethodOf(s.core).title} · для ${coreName(s.core)}", Icons.Rounded.Speed)
            Segmented(
                listOf(PingMethod.PROXY_GET to "GET", PingMethod.PROXY_HEAD to "HEAD", PingMethod.TCP to "TCP", PingMethod.ICMP to "ICMP"),
                s.pingMethodOf(s.core), { v -> set { it.withPing(it.core, method = v) } },
            )
            Spacer(Modifier.height(8.dp))
            SettingRow("Маршрутизация", when (s.routingMode) {
                RoutingMode.SMART -> "Умная: российские сайты напрямую"
                RoutingMode.GLOBAL -> "Весь трафик через VPN"
            }, Icons.AutoMirrored.Rounded.AltRoute, onClick = { open(Page.ROUTING) }) { Chevron() }
            SettingRow(
                "DNS",
                (if (s.dns == DnsPreset.CUSTOM) s.customDns.ifBlank { "Свой" } else if (s.dns == DnsPreset.PROVIDER) "Как в подписке" else s.dns.title) +
                    if (s.dnsDefaults) "" else " · настроен",
                Icons.Rounded.Dns, onClick = { open(Page.DNS) },
            ) { Chevron() }
            if (controller.platform.supportsPerAppSplit) {
                SettingRow("Приложения", when (s.splitMode) {
                    SplitMode.OFF -> "Все приложения через VPN"
                    SplitMode.ONLY_SELECTED -> "Только выбранные: ${s.splitApps.size}"
                    SplitMode.BYPASS_SELECTED -> {
                        val extra = (s.splitApps - RuApps.PACKAGES).size
                        when {
                            !s.splitApps.containsAll(RuApps.PACKAGES) -> "В обход VPN: ${s.splitApps.size}"
                            extra == 0 -> "Российские — в обход VPN"
                            else -> "Российские и ещё $extra — в обход VPN"
                        }
                    }
                }, Icons.AutoMirrored.Rounded.CallSplit, onClick = { open(Page.APPS) }) { Chevron() }
            }
            if (controller.platform.isDesktop) {
                SettingRow("Режим", null, Icons.Rounded.Cable)
                Segmented(
                    listOf(DesktopMode.TUN to "TUN", DesktopMode.SYSTEM_PROXY to "Системный прокси", DesktopMode.PROXY_ONLY to "Только прокси"),
                    s.desktopMode, { m -> set { it.copy(desktopMode = m) } },
                )
                Text(
                    when (s.desktopMode) {
                        DesktopMode.TUN -> "Весь трафик компьютера, включая игры и программы без настроек прокси. Нужны права администратора."
                        DesktopMode.SYSTEM_PROXY -> "Браузеры и большинство программ идут через VPN автоматически. Без прав администратора."
                        DesktopMode.PROXY_ONLY -> "Ghostly поднимает только SOCKS5/HTTP-прокси и ничего не меняет в Windows — укажите его в нужных программах."
                    },
                    style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(horizontal = 6.dp, vertical = 8.dp),
                )
            }
            SettingRow(
                "Локальный прокси",
                if (!controller.platform.isDesktop && !s.localProxy) "Выключен"
                else "SOCKS5 :${s.socksPort} · HTTP :${s.httpPort}" + if (s.proxyAuth) " · с паролем" else "",
                Icons.Rounded.Cable, onClick = { open(Page.PROXY) },
            ) { Chevron() }
            ToggleRow("Блокировать рекламу", "Рекламные и трекинговые домены отсекаются на лету", s.blockAds, Icons.Rounded.Block) { v -> set { it.copy(blockAds = v) } }
        }

        SectionTitle("Поведение")
        Group {
            ToggleRow(
                if (controller.platform.isDesktop) "Запуск вместе с системой" else "Включать при старте телефона",
                if (controller.platform.isDesktop) "Ghostly стартует свёрнутым и сразу подключается"
                else "VPN поднимется сам после перезагрузки — даже без открытия приложения",
                s.startOnBoot, Icons.Rounded.RocketLaunch,
            ) { v -> set { it.copy(startOnBoot = v) } }
            ToggleRow("Автоподключение", "Подключаться при открытии приложения", s.autoConnect, Icons.Rounded.PowerSettingsNew) { v -> set { it.copy(autoConnect = v) } }
            if (controller.platform.isDesktop) {
                ToggleRow("Сворачивать в трей", "Крестик прячет окно, VPN продолжает работать. Выйти — через меню призрака в трее", s.closeToTray, Icons.Rounded.Layers) { v -> set { it.copy(closeToTray = v) } }
            }
            ToggleRow("Переподключение", "Восстанавливать туннель при смене сети", s.autoReconnect, Icons.Rounded.Refresh) { v -> set { it.copy(autoReconnect = v) } }
            ToggleRow("Автосмена сервера", "Если сервер перестал отвечать — переключиться на самый быстрый", s.autoFailover, Icons.Rounded.SwapHoriz) { v -> set { it.copy(autoFailover = v) } }
            ToggleRow("Сторож соединения", "Каждые 20 секунд проверяет, что трафик реально идёт, и сам меняет сервер, если нет", s.smartGuard, Icons.Rounded.Shield) { v -> set { it.copy(smartGuard = v) } }
            ToggleRow("Беречь белые списки", "Сама уходит на белые списки при блокировках и возвращается на обычные серверы, как только интернет снова нормальный", s.saveWhitelist, Icons.Rounded.Savings) { v -> set { it.copy(saveWhitelist = v) } }
            ToggleRow("Обновлять подписки", "Автоматически, как просит провайдер", s.autoUpdateSubs, Icons.Rounded.AutoMode) { v -> set { it.copy(autoUpdateSubs = v) } }
            if (controller.platform.updateAsset != null) {
                ToggleRow("Искать обновления приложения", "Каждые 2 минуты", s.autoCheckUpdates, Icons.Rounded.Refresh) { v -> set { it.copy(autoCheckUpdates = v) } }
                ToggleRow(
                    "Ставить обновления автоматически",
                    if (controller.platform.isDesktop) "Скачается, проверится по SHA-256 и установится само; VPN включится обратно"
                    else "Скачается само и откроет окно установки",
                    s.autoInstallUpdates, Icons.Rounded.RocketLaunch,
                ) { v -> set { it.copy(autoInstallUpdates = v) } }
            }
            controller.platform.killSwitch?.let { ks ->
                // Asking the firewall spawns netsh — do it off the UI thread, once per screen.
                val reason by androidx.compose.runtime.produceState<String?>("Проверяю брандмауэр…", ks) {
                    value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { ks.unavailableReason() }
                }
                ToggleRow(
                    "Kill switch",
                    reason ?: "Если VPN неожиданно упадёт, интернет блокируется, пока туннель не вернётся — ни один пакет мимо VPN",
                    s.killSwitch && reason == null, Icons.Rounded.Lock,
                ) { v -> val r = reason; if (r == null) set { it.copy(killSwitch = v) } else controller.toast(r) }
            }
            controller.platform.systemVpnSettings?.let { open ->
                SettingRow("Kill switch", "Системная настройка: «Постоянная VPN» + «Блокировать соединения без VPN»", Icons.Rounded.Lock, onClick = open) { Chevron() }
            }
        }

        SectionTitle("Внешний вид")
        Group {
            val monetAccent = remember { controller.platform.systemAccent() }
            if (monetAccent != null) {
                ToggleRow("Цвета системы", if (controller.platform.isDesktop) "Акцент как в Windows (Параметры → Персонализация → Цвета)" else "Акцент берётся из обоев (Material You)", s.monet, Icons.Rounded.Palette) { v -> set { it.copy(monet = v) } }
            }
            SettingRow("Акцент", if (s.monet && monetAccent != null) "Сейчас из обоев — выбор ниже вернёт свой" else null, Icons.Rounded.Palette) {
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
            ToggleRow("Меньше анимаций", "Спокойнее и экономнее для батареи", s.reduceMotion, Icons.Rounded.Animation) { v -> set { it.copy(reduceMotion = v) } }
            controller.platform.stage?.let { stage ->
                ToggleRow(
                    "Сцена", "Призрак подпевает музыке, которая играет ${stage.whereLabel}, а интерфейс светится и движется в её ритме и настроении",
                    s.stageMode, Icons.Rounded.MusicNote,
                ) { v -> set { it.copy(stageMode = v) }; if (v) stage.requestSetup() }
                val need by stage.setupNeeded.collectAsState()
                if (s.stageMode && need != null) {
                    SettingRow("Дать доступ для сцены", need, Icons.Rounded.MusicNote, onClick = { stage.requestSetup() }) { Chevron() }
                }
            }
            ToggleRow("Звуки", "Мягкие звуки при подключении, ошибках и выборе сервера", s.sounds, Icons.Rounded.MusicNote) { v -> set { it.copy(sounds = v) } }
            if (s.sounds) SoundVolume(controller, s.soundVolume) { v -> set { it.copy(soundVolume = v) } }
            if (!controller.platform.isDesktop) {
                ToggleRow("Вибрация", "Отклик на нажатия", s.haptics, Icons.Rounded.Vibration) { v -> set { it.copy(haptics = v) } }
                if (s.haptics) HapticStrength(controller, s.hapticStrength) { v -> set { it.copy(hapticStrength = v) } }
            }
        }

        SectionTitle("Ещё")
        Group {
            SettingRow("Для продвинутых", "MTU, мультиплекс, фрагментация, IPv6, логи", Icons.Rounded.Tune, onClick = { open(Page.ADVANCED) }) { Chevron() }
            SettingRow("О приложении", "Версия, ядро, исходный код", Icons.Rounded.Info, onClick = { open(Page.ABOUT) }) { Chevron() }
        }
    }
}

// ============================================================================ routing

@Composable
private fun RoutingPage(controller: GhostlyController, contentPadding: PaddingValues, back: () -> Unit) {
    val s by controller.settings.collectAsState()
    val set = controller::updateSettings
    PageScaffold("Маршрутизация", contentPadding, back) {
        Spacer(Modifier.height(12.dp))
        Segmented(listOf(RoutingMode.SMART to "Умная", RoutingMode.GLOBAL to "Всё через VPN"), s.routingMode, { m -> set { it.copy(routingMode = m) } })
        Text(
            when (s.routingMode) {
                RoutingMode.SMART -> "Госуслуги, банки, маркетплейсы и другие российские сайты открываются напрямую — быстрее и без капчи. Остальное идёт через VPN. Для подписок с собственными правилами используются правила провайдера."
                RoutingMode.GLOBAL -> "Весь трафик, кроме локальной сети, идёт через VPN."
            },
            style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(6.dp, 10.dp),
        )
        SectionTitle("Свои правила")
        Text("По одному домену на строку: example.com, geosite:youtube, full:api.site.ru, regexp:…", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(start = 6.dp, bottom = 10.dp))
        DomainListEditor("Напрямую", s.directDomains) { l -> set { it.copy(directDomains = l) } }
        Spacer(Modifier.height(10.dp))
        DomainListEditor("Через VPN", s.proxyDomains) { l -> set { it.copy(proxyDomains = l) } }
        Spacer(Modifier.height(10.dp))
        DomainListEditor("Блокировать", s.blockDomains) { l -> set { it.copy(blockDomains = l) } }
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
            if (text.isEmpty()) Text("пусто", style = MaterialTheme.typography.bodySmall)
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
            "Запросы устройства перехватывает ядро VPN. DNS «через VPN» идёт внутри туннеля, поэтому провайдер и ТСПУ не могут подменить ответы.",
            style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(6.dp, 12.dp),
        )

        SectionTitle("Через VPN")
        Group {
            DnsPreset.entries.forEach { p ->
                SettingRow(
                    if (p == DnsPreset.PROVIDER) "Как в подписке" else p.title,
                    if (p == DnsPreset.PROVIDER) "DNS, который задал провайдер подписки" else p.address,
                    onClick = { set { it.copy(dns = p) } },
                ) { if (s.dns == p) Icon(Icons.Rounded.Check, null, tint = c.accent) }
            }
            if (s.dns == DnsPreset.CUSTOM) DnsField(
                "Адрес", s.customDns, "https://dns.example/dns-query, tls://1.1.1.1 или 9.9.9.9",
            ) { v -> set { it.copy(customDns = v) } }
        }

        SectionTitle("Без VPN")
        Group {
            ToggleRow(
                "Российские сайты через свой DNS",
                if (s.routingMode == RoutingMode.SMART) "Они открываются напрямую, и их адреса лучше узнавать у российского DNS"
                else "Работает только в умной маршрутизации",
                s.dnsSplitRu, Icons.AutoMirrored.Rounded.AltRoute,
            ) { v -> set { it.copy(dnsSplitRu = v) } }
            if (s.dnsSplitRu) {
                app.ghostly.core.model.DirectDns.entries.forEach { d ->
                    SettingRow(d.title, when (d) {
                        app.ghostly.core.model.DirectDns.YANDEX -> "77.88.8.8 по HTTPS, мимо туннеля"
                        app.ghostly.core.model.DirectDns.SYSTEM -> "DNS сети или провайдера"
                        app.ghostly.core.model.DirectDns.CUSTOM -> "Любой адрес"
                    }, onClick = { set { it.copy(dnsDirect = d) } }) { if (s.dnsDirect == d) Icon(Icons.Rounded.Check, null, tint = c.accent) }
                }
                if (s.dnsDirect == app.ghostly.core.model.DirectDns.CUSTOM) DnsField(
                    "Адрес", s.dnsDirectCustom, "https+local://dns.example/dns-query (+local: мимо туннеля), 77.88.8.1",
                ) { v -> set { it.copy(dnsDirectCustom = v) } }
            }
        }

        SectionTitle("Дополнительно")
        Group {
            SettingRow("Адреса IPv4 / IPv6", s.dnsStrategy.title, Icons.Rounded.Language)
            Segmented(
                listOf(
                    app.ghostly.core.model.DnsStrategy.AUTO to "Авто",
                    app.ghostly.core.model.DnsStrategy.IPV4 to "IPv4",
                    app.ghostly.core.model.DnsStrategy.IPV4_FIRST to "4 → 6",
                    app.ghostly.core.model.DnsStrategy.IPV6_FIRST to "6 → 4",
                    app.ghostly.core.model.DnsStrategy.BOTH to "Оба",
                ),
                s.dnsStrategy, { v -> set { it.copy(dnsStrategy = v) } },
            )
            Spacer(Modifier.height(6.dp))
            ToggleRow("Кэш DNS", "Повторные запросы отвечаются мгновенно", s.dnsCache, Icons.Rounded.Speed) { v -> set { it.copy(dnsCache = v) } }
            ToggleRow(
                "Fake-IP (Mihomo)", "Сайты открываются без ожидания DNS, имена узнаёт сервер. Выключите, если какое-то приложение не работает",
                s.dnsFakeIp, Icons.Rounded.RocketLaunch,
            ) { v -> set { it.copy(dnsFakeIp = v) } }
            DnsField(
                "Загрузочный DNS (Mihomo)", s.dnsBootstrap, "Обычный IP. Узнаёт адреса DoH-серверов до того, как поднимется туннель",
            ) { v -> set { it.copy(dnsBootstrap = v) } }
        }

        SectionTitle("Свои записи")
        DomainListEditor("hosts: домен и IP через пробел", s.dnsHosts) { v -> set { it.copy(dnsHosts = v) } }
        Text(
            "Например: example.com 93.184.216.34. Серверы Ghostly прописаны всегда, поэтому подключение работает, даже если их домены блокируют.",
            style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(6.dp, 8.dp),
        )
        if (!s.dnsDefaults) {
            SectionTitle("Сброс")
            Group {
                SettingRow("Вернуть DNS по умолчанию", "Подписки снова используют свой DNS", Icons.Rounded.Refresh, onClick = {
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
                    Text("Приложения", style = MaterialTheme.typography.headlineMedium)
                }
                Segmented(
                    listOf(SplitMode.OFF to "Все", SplitMode.ONLY_SELECTED to "Только эти", SplitMode.BYPASS_SELECTED to "Кроме этих"),
                    s.splitMode, { m -> controller.updateSettings { it.withSplitMode(m) } },
                )
                Text(
                    when (s.splitMode) {
                        SplitMode.OFF -> "Все приложения работают через VPN."
                        SplitMode.ONLY_SELECTED -> "Через VPN пойдут только отмеченные приложения."
                        SplitMode.BYPASS_SELECTED -> "Отмеченные приложения работают напрямую. Российские (банки, Госуслуги, MAX, маркетплейсы) отмечены сразу: так они видят твой обычный IP, а не адрес VPN."
                    },
                    style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(6.dp, 10.dp),
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.weight(1f).height(42.dp).clip(RoundedCornerShape(16.dp)).background(Color.White.copy(alpha = 0.06f)).padding(horizontal = 12.dp), contentAlignment = Alignment.CenterStart) {
                        if (query.isEmpty()) Text("Поиск приложений", style = MaterialTheme.typography.bodyMedium, color = c.ink3)
                        BasicTextField(query, { query = it }, singleLine = true, textStyle = MaterialTheme.typography.bodyMedium.copy(color = c.ink), cursorBrush = SolidColor(c.accent), modifier = Modifier.fillMaxWidth())
                    }
                    Spacer(Modifier.width(10.dp))
                    Text("Системные", style = MaterialTheme.typography.labelMedium, color = c.ink3)
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
    PageScaffold("Локальный прокси", contentPadding, back) {
        Text(
            if (controller.platform.isDesktop) "SOCKS5 и HTTP-прокси на этом компьютере — для браузеров, Telegram, игр и программ, которые умеют работать через прокси."
            else "SOCKS5 и HTTP-прокси на телефоне, пока VPN включён — например, для Telegram или приложений, которые нужно пустить через прокси.",
            style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(6.dp, 12.dp),
        )
        Group {
            if (!controller.platform.isDesktop) {
                ToggleRow("Включить", "Поднимать прокси вместе с VPN", s.localProxy, Icons.Rounded.PowerSettingsNew) { v -> set { it.copy(localProxy = v) } }
            }
            ToggleRow("Доступ из локальной сети", "Раздавать прокси другим устройствам в твоей Wi-Fi сети", s.allowLan, Icons.Rounded.Language) { v -> set { it.copy(allowLan = v) } }
        }
        SectionTitle("Порты")
        Group {
            Row(Modifier.fillMaxWidth().padding(4.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                PortField("SOCKS5", s.socksPort, Modifier.weight(1f)) { p -> set { it.copy(socksPort = p) } }
                PortField("HTTP", s.httpPort, Modifier.weight(1f)) { p -> set { it.copy(httpPort = p) } }
            }
            if (s.socksPort == s.httpPort) Text("Порты должны отличаться", style = MaterialTheme.typography.bodySmall, color = c.bad, modifier = Modifier.padding(6.dp))
        }
        SectionTitle("Авторизация")
        Group {
            ToggleRow("Логин и пароль", "Чтобы чужие программы и устройства не пользовались твоим прокси", s.proxyAuth, Icons.Rounded.Lock) { v -> set { it.copy(proxyAuth = v) } }
            if (s.proxyAuth) {
                CredentialField("Логин", s.proxyUser, controller) { v -> set { it.copy(proxyUser = v) } }
                Spacer(Modifier.height(8.dp))
                CredentialField("Пароль", s.proxyPass, controller, secret = true) { v -> set { it.copy(proxyPass = v) } }
                Spacer(Modifier.height(6.dp))
                SettingRow("Сгенерировать новые", "ghostly_… и случайный пароль", Icons.Rounded.Refresh, onClick = { controller.regenerateProxyCredentials() })
            }
        }
        if (controller.platform.isDesktop) {
            Text(
                "Системный прокси Windows/macOS ходит через отдельный внутренний порт без пароля — браузеры не будут спрашивать логин.",
                style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(6.dp, 12.dp),
            )
        }
        Spacer(Modifier.height(12.dp))
        val lan = remember(s.allowLan) { if (s.allowLan) controller.platform.lanAddress() else null }
        val host = if (s.allowLan) lan ?: "адрес не найден — подключись к Wi-Fi" else "127.0.0.1"
        if (s.allowLan) {
            Text(
                "Для других устройств в твоей сети адрес прокси — $host. Для программ на этом устройстве — 127.0.0.1.",
                style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(6.dp, 0.dp, 6.dp, 10.dp),
            )
        }
        val auth = if (s.proxyAuth) "${s.proxyUser}:${s.proxyPass}@" else ""
        val link = "socks5://$auth$host:${s.socksPort}"
        val shownLink = if (s.proxyAuth) "socks5://${s.proxyUser}:••••••@$host:${s.socksPort}" else link
        GlassCard(Modifier.fillMaxWidth(), padding = 14.dp, onClick = { controller.platform.copyToClipboard(link) }) {
            Text("Нажми, чтобы скопировать", style = MaterialTheme.typography.labelSmall, color = c.ink3)
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
    PageScaffold("Для продвинутых", contentPadding, back) {
        SectionTitle("Туннель")
        Group {
            ToggleRow("Мультиплекс (mux)", "Несколько соединений в одном — меньше рукопожатий. Не для Vision/XHTTP", s.mux, Icons.Rounded.Layers) { v -> set { it.copy(mux = v) } }
            ToggleRow("Фрагментация TLS", "Режет ClientHello на части — помогает против DPI для TLS-серверов", s.fragment, Icons.Rounded.Code) { v -> set { it.copy(fragment = v) } }
            ToggleRow("Сниффинг", "Определять домен по трафику для точной маршрутизации", s.sniffing, Icons.Rounded.Speed) { v -> set { it.copy(sniffing = v) } }
            ToggleRow("IPv6", "Пускать IPv6 через туннель", s.ipv6, Icons.Rounded.Language) { v -> set { it.copy(ipv6 = v) } }
            ToggleRow("Блокировать QUIC", "YouTube и браузеры переходят с UDP на TCP — стабильнее на мобильном интернете и в сетях, режущих UDP", s.blockQuic, Icons.Rounded.Block) { v -> set { it.copy(blockQuic = v) } }
            ToggleRow("TCP Fast Open", "На одно рукопожатие меньше при каждом новом соединении", s.tcpFastOpen, Icons.Rounded.RocketLaunch) { v -> set { it.copy(tcpFastOpen = v) } }
            SettingRow("MTU", "${s.mtu}", Icons.Rounded.Tune)
            Segmented(listOf(1280 to "1280", 1400 to "1400", 1500 to "1500", 9000 to "9000"), s.mtu, { v -> set { it.copy(mtu = v) } })
            Spacer(Modifier.height(6.dp))
        }
        // Ping and the log are set per core; the switch picks which core's settings are shown.
        var core by remember { mutableStateOf(s.core) }
        SectionTitle("Настройки ядра")
        Group {
            Segmented(listOf(CoreType.XRAY to "Xray", CoreType.MIHOMO to "mihomo"), core, { core = it })
            Text(
                "Пинг и журнал настраиваются отдельно для каждого ядра." + if (core != s.core) " Сейчас используется ${coreName(s.core)}." else "",
                style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(horizontal = 6.dp, vertical = 8.dp),
            )
        }
        val method = s.pingMethodOf(core)
        SectionTitle("Пинг · ${coreName(core)}")
        Group {
            SettingRow("Способ", method.title, Icons.Rounded.Speed)
            Segmented(
                listOf(PingMethod.PROXY_GET to "GET", PingMethod.PROXY_HEAD to "HEAD", PingMethod.TCP to "TCP", PingMethod.ICMP to "ICMP"),
                method, { v -> set { it.withPing(core, method = v) } },
            )
            Text(
                when (method) {
                    PingMethod.PROXY_GET -> "Запрос через сервер к адресу проверки — настоящая задержка туннеля."
                    PingMethod.PROXY_HEAD -> "То же через сервер, но запрос HEAD — без тела ответа, чуть быстрее."
                    PingMethod.TCP -> "Только соединение с сервером: быстро, но не проверяет, что туннель работает. UDP-серверы проверяются через ядро."
                    PingMethod.ICMP -> "Системный ping адреса сервера. Многие серверы его не пропускают — тогда будет «нет», хотя сервер рабочий."
                },
                style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(horizontal = 6.dp, vertical = 8.dp),
            )
        }
        if (method == PingMethod.PROXY_GET || method == PingMethod.PROXY_HEAD) SectionTitle("Адрес проверки")
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
        SectionTitle("Проверка блокировок")
        Group {
            ToggleRow(
                "Проверять недоступные серверы",
                "Если сервер не пингуется, Ghostly стучится к нему напрямую и пишет причину: блок IP, обрыв TLS (DPI) или заморозка ТСПУ после ~16 КБ",
                s.blockCheck, Icons.Rounded.Shield,
            ) { v -> set { it.copy(blockCheck = v) } }
        }
        SectionTitle("Журнал · ${coreName(core)}")
        Group {
            Segmented(listOf("none" to "Выкл", "error" to "Ошибки", "warning" to "Важное", "info" to "Всё", "debug" to "Debug"), s.logLevelOf(core), { v -> set { it.withLogLevel(core, v) } })
            SettingRow("Открыть журнал", "Что ядро писало при последнем запуске — посмотреть, скопировать, отправить", Icons.Rounded.Code, onClick = { open(Page.LOGS) }) { Chevron() }
        }
        SectionTitle("Сброс")
        Group {
            var armed by remember { mutableStateOf(false) }
            LaunchedEffect(armed) { if (armed) { kotlinx.coroutines.delay(4000); armed = false } }
            SettingRow(
                if (armed) "Нажми ещё раз для сброса" else "Сбросить настройки",
                "Все параметры — по умолчанию. Подписки, серверы и логин/пароль прокси остаются",
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
    PageScaffold("О приложении", contentPadding, back) {
        Column(Modifier.fillMaxWidth().padding(vertical = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            GhostMark(Modifier.size(110.dp))
            Spacer(Modifier.height(10.dp))
            Text("Ghostly VPN", style = MaterialTheme.typography.headlineMedium)
            Text("версия ${controller.platform.appVersion}", style = MaterialTheme.typography.bodySmall)
        }
        app.ghostly.ui.components.UpdateBanner(controller, Modifier.padding(bottom = 12.dp))
        Group {
            SettingRow("Ядро", controller.backend.coreVersion(), Icons.Rounded.Speed)
            SettingRow(
                "Журнал ядра", "Скопировать журнал последнего запуска для поддержки", Icons.Rounded.ContentCopy,
                onClick = { controller.haptic(); controller.exportLogs(controller.settings.value.core, share = false) },
            ) { Chevron() }
            if (controller.platform.updateAsset != null) {
                val last by controller.updater.lastCheck.collectAsState()
                SettingRow("Проверить обновления", last ?: "Скачиваются с нашего сервера и проверяются по SHA-256", Icons.Rounded.Refresh, onClick = { controller.checkUpdates(manual = true) }) { Chevron() }
            }
            SettingRow("Исходный код", "Открытый проект на GitHub · GPL-3.0", Icons.Rounded.Code, onClick = { controller.platform.openUrl(GITHUB_URL) }) { Chevron() }
            SettingRow("Сайт", "ghostlynex.fun", Icons.Rounded.Language, onClick = { controller.platform.openUrl("https://ghostlinknex.online") }) { Chevron() }
        }
        Spacer(Modifier.height(18.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
            Text("Сделано с ", style = MaterialTheme.typography.bodySmall)
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
            Text("Сила", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            Text(
                when {
                    v < 0.25f -> "едва заметно"
                    v < 0.5f -> "мягко"
                    v <= 0.7f -> "как в системе"
                    v < 0.9f -> "ощутимо"
                    else -> "максимум"
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
        Text("Если отклик почти не чувствуется, двигай вправо: в конце шкалы к нажатию добавляется плотный удар", style = MaterialTheme.typography.bodySmall)
    }
}

/** Volume of the UI sounds; lifting the finger plays the "connected" chime at the new level. */
@Composable
private fun SoundVolume(controller: GhostlyController, value: Float, onChange: (Float) -> Unit) {
    val c = Ghost.colors
    var v by remember(value) { mutableStateOf(value) }
    Column(Modifier.padding(start = 6.dp, end = 6.dp, bottom = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Громкость", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
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
    PageScaffold("Журнал ядра", contentPadding, back) {
        Group {
            Segmented(listOf(CoreType.XRAY to "Xray", CoreType.MIHOMO to "mihomo"), core, { core = it })
            ToggleRow("Только ошибки и предупреждения", null, onlyErrors, Icons.Rounded.Block) { onlyErrors = it }
            SettingRow("Скопировать", "С версией приложения и устройства — для поддержки", Icons.Rounded.ContentCopy, onClick = { controller.haptic(); controller.exportLogs(core, share = false) }) { Chevron() }
            if (!controller.platform.isDesktop) {
                SettingRow("Отправить", "В Telegram или куда удобно", Icons.Rounded.Language, onClick = { controller.haptic(); controller.exportLogs(core, share = true) }) { Chevron() }
            }
        }
        Spacer(Modifier.height(12.dp))
        val lines = text?.lines()?.filter { it.isNotBlank() }.orEmpty()
            .let { all -> if (onlyErrors) all.filter { logLevel(it) >= 2 } else all }
            .takeLast(400)
        Group {
            when {
                text == null -> Text("Загружаю…", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(8.dp))
                lines.isEmpty() -> Text(
                    if (s.logLevelOf(core) == "none") "Журнал выключен — выбери уровень в «Для продвинутых»."
                    else "Пусто. Подключись на ядре ${coreName(core)}, и здесь появятся его сообщения.",
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
