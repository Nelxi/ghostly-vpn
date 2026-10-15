package app.ghostly.ui.screens.cabinet

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.rounded.Logout
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.rounded.AccountBalanceWallet
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.AddLink
import androidx.compose.material.icons.rounded.CalendarMonth
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.DataUsage
import androidx.compose.material.icons.rounded.Devices
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.Language
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.MoreHoriz
import androidx.compose.material.icons.rounded.PhoneAndroid
import androidx.compose.material.icons.rounded.Redeem
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.SwapHoriz
import androidx.compose.material.icons.rounded.WarningAmber
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.ghostly.core.GhostlyController
import app.ghostly.core.account.CabinetState
import app.ghostly.core.account.CabinetSub
import app.ghostly.core.account.CabinetUi
import app.ghostly.core.account.HistoryItem
import app.ghostly.core.account.Order
import app.ghostly.i18n.tr
import app.ghostly.ui.Format
import androidx.compose.material.icons.rounded.DesktopWindows
import app.ghostly.ui.Tab
import app.ghostly.ui.tabSky
import app.ghostly.ui.components.nightGhost
import app.ghostly.ui.components.pressScale
import androidx.compose.foundation.layout.heightIn
import app.ghostly.ui.components.AccentButton
import app.ghostly.ui.components.ActionTile
import app.ghostly.ui.components.BlockTitle
import app.ghostly.ui.components.Chip
import app.ghostly.ui.components.GhostMark
import app.ghostly.ui.components.GlowBar
import app.ghostly.ui.components.IconBubble
import app.ghostly.ui.components.Note
import app.ghostly.ui.components.Radius
import app.ghostly.ui.components.RingProgress
import app.ghostly.ui.components.SettingRow
import app.ghostly.ui.components.Skeleton
import app.ghostly.ui.components.SoftButton
import app.ghostly.ui.components.Spinner
import app.ghostly.ui.components.Tile
import app.ghostly.ui.components.Tone
import app.ghostly.ui.components.appear
import app.ghostly.ui.components.hoverSound
import app.ghostly.ui.components.rub
import app.ghostly.ui.theme.Ghost
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** A sheet of the cabinet: what is open over the page. */
internal sealed interface CabSheet {
    data class Renew(val subId: String?) : CabSheet
    data class Gb(val subId: String) : CabSheet
    data class MoreDevices(val subId: String) : CabSheet
    data class Devices(val subId: String) : CabSheet
    data object Topup : CabSheet
    data object History : CabSheet
    data object Sessions : CabSheet
    data object Password : CabSheet
    data object Register : CabSheet
    data object ClaimTransfer : CabSheet
    data class Transfer(val subId: String) : CabSheet
}

/**
 * The Ghostly account, in the app: sign-in, the subscription with its days and traffic, balance, devices,
 * renewals and purchases, referrals, security. [wide]: desktop layout (two columns, dialogs for sheets).
 * [onClose]: shown over the first screen (no tabs yet), with a close button.
 */
@Composable
fun CabinetScreen(controller: GhostlyController, contentPadding: PaddingValues, wide: Boolean = false, onClose: (() -> Unit)? = null) {
    val cab = controller.cabinet
    val ui by cab.ui.collectAsState()
    val intent by cab.intent.collectAsState()
    var sheet by remember { mutableStateOf<CabSheet?>(null) }
    LaunchedEffect(Unit) { cab.open() }
    // Back from the browser (a payment, the bot): look at the order right away.
    val visible by app.ghostly.ui.components.AmbientMotion.visible.collectAsState()
    LaunchedEffect(visible) { if (visible) cab.onReturn() }
    // A section asked from elsewhere (Settings, «Продлить» on the subscription card, a notice).
    LaunchedEffect(intent, ui) {
        val i = intent ?: return@LaunchedEffect
        val st = (ui as? CabinetUi.Ready)?.state
        if (ui is CabinetUi.Loading || ui is CabinetUi.Idle) return@LaunchedEffect
        cab.consumeIntent()
        val sub = st?.let { pickSub(it, i.subId, controller) }
        sheet = when (i.section) {
            "renew" -> CabSheet.Renew(sub?.id)
            "topup" -> if (st != null) CabSheet.Topup else null
            "devices" -> sub?.let { CabSheet.Devices(it.id) }
            "gb" -> sub?.let { CabSheet.Gb(it.id) }
            else -> null
        }
    }

    Box(Modifier.fillMaxSize()) {
        AnimatedContent(
            targetState = ui,
            contentKey = { it::class },
            transitionSpec = { (fadeIn(app.ghostly.ui.theme.Motion.quick(260)) togetherWith fadeOut(app.ghostly.ui.theme.Motion.quick(160))) },
            modifier = Modifier.fillMaxSize(),
        ) { u ->
            Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(contentPadding).padding(horizontal = 18.dp),
            ) {
                // Above the horizon: who is signed in, the ghost, the balance.
                Column(Modifier.fillMaxWidth().tabSky(Tab.ACCOUNT)) {
                    Header(controller, u, onClose)
                    if (u is CabinetUi.Ready) BalanceRow(controller, u.state) { sheet = it }
                    Spacer(Modifier.height(if (u is CabinetUi.Ready) 36.dp else 22.dp))
                }
                when (u) {
                    CabinetUi.Idle, CabinetUi.Loading -> LoadingBody()
                    is CabinetUi.Failed -> FailedBody(u.message) { cab.refresh() }
                    is CabinetUi.SignedOut -> SignInBody(controller, u) { sheet = it }
                    is CabinetUi.Ready -> ReadyBody(controller, u.state, wide) { sheet = it }
                }
                Spacer(Modifier.height(28.dp))
            }
        }
        PaymentBanner(controller, Modifier.align(Alignment.BottomCenter).padding(contentPadding.bottomOnly()).padding(horizontal = 14.dp, vertical = 10.dp))
    }
    sheet?.let { s -> CabinetSheetHost(controller, s, wide, onOpen = { sheet = it }) { sheet = null } }
}

private fun PaddingValues.bottomOnly(): PaddingValues = PaddingValues(bottom = calculateBottomPadding())

/** The subscription a section opens on: the one asked, else the one the app uses, else the first active. */
internal fun pickSub(st: CabinetState, subId: String?, controller: GhostlyController): CabinetSub? {
    subId?.let { id -> st.subs.firstOrNull { it.id == id }?.let { return it } }
    val inApp = controller.ghostlyProfile()?.url?.let { app.ghostly.core.account.Cabinet.subIdOf(it) }
    return st.subs.firstOrNull { it.id == inApp } ?: st.subs.firstOrNull { it.active } ?: st.subs.firstOrNull()
}

// ============================================================================ header

@Composable
private fun Header(controller: GhostlyController, ui: CabinetUi, onClose: (() -> Unit)?) {
    val c = Ghost.colors
    val busy by controller.cabinet.busy.collectAsState()
    Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.Top) {
        onClose?.let {
            IconBubble(Icons.Rounded.Close, it)
            Spacer(Modifier.width(12.dp))
        }
        Column(Modifier.weight(1f).padding(top = 2.dp, start = 4.dp)) {
            Text(tr("Аккаунт"), style = MaterialTheme.typography.headlineMedium)
            Spacer(Modifier.height(4.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                val who = when (ui) {
                    is CabinetUi.Ready -> ui.state.account.let { a ->
                        when {
                            a?.tg?.username != null -> "@${a.tg.username} · Telegram"
                            a?.login != null -> a.login
                            else -> tr("Гостевой аккаунт")
                        }
                    }
                    else -> tr("Подписка, баланс и устройства")
                }
                Text(who, style = MaterialTheme.typography.bodyMedium.copy(fontSize = 14.sp), color = c.ink2, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                if (ui is CabinetUi.Ready) {
                    Spacer(Modifier.width(4.dp))
                    Box(
                        Modifier.size(30.dp).clip(CircleShape).hoverSound().clickable { controller.haptic(); controller.cabinet.refresh() },
                        contentAlignment = Alignment.Center,
                    ) {
                        if (busy != null) Spinner(c.accent, Modifier.size(14.dp))
                        else Icon(Icons.Rounded.Refresh, tr("Обновить"), tint = c.ink3, modifier = Modifier.size(16.dp))
                    }
                } else if (busy != null) {
                    Spacer(Modifier.width(10.dp))
                    Spinner(c.accent, Modifier.size(14.dp))
                }
            }
        }
        Box(Modifier.size(92.dp).nightGhost(Tab.ACCOUNT, label = "Ghostly"))
    }
}

/** The balance, big, right on the sky, with «Пополнить» beside it. */
@Composable
private fun BalanceRow(controller: GhostlyController, st: CabinetState, open: (CabSheet) -> Unit) {
    val c = Ghost.colors
    Row(Modifier.fillMaxWidth().padding(top = 18.dp, start = 4.dp), verticalAlignment = Alignment.Bottom) {
        Column(Modifier.weight(1f)) {
            Text(tr("Баланс"), style = MaterialTheme.typography.labelMedium, color = c.ink2)
            Spacer(Modifier.height(4.dp))
            Text(rub(st.balance), style = MaterialTheme.typography.displayLarge.copy(fontFeatureSettings = "tnum"), maxLines = 1)
        }
        SoftButton(tr("Пополнить"), { controller.haptic(); open(CabSheet.Topup) }, Modifier.padding(bottom = 6.dp))
    }
}

// ============================================================================ loading / failed

@Composable
private fun LoadingBody() {
    Column(verticalArrangement = Arrangement.spacedBy(14.dp), modifier = Modifier.padding(top = 10.dp)) {
        Skeleton(Modifier.fillMaxWidth().height(128.dp), Radius.card)
        Skeleton(Modifier.fillMaxWidth().height(260.dp), Radius.card)
        Skeleton(Modifier.fillMaxWidth().height(110.dp), Radius.card)
    }
}

@Composable
private fun FailedBody(message: String, retry: () -> Unit) {
    Tile(Modifier.fillMaxWidth().padding(top = 10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            GhostMark(Modifier.size(56.dp), happy = 0f, pokeable = false)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(tr("Кабинет не загрузился"), style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(4.dp))
                Text(message, style = MaterialTheme.typography.bodyMedium)
            }
        }
        Spacer(Modifier.height(16.dp))
        AccentButton(tr("Повторить"), retry, Modifier.fillMaxWidth(), Icons.Rounded.Refresh)
    }
}

// ============================================================================ signed in

@Composable
private fun ReadyBody(controller: GhostlyController, st: CabinetState, wide: Boolean, open: (CabSheet) -> Unit) {
    val claim by controller.cabinet.claim.collectAsState()
    var now by remember { mutableLongStateOf(app.ghostly.core.account.Cabinet.now()) }
    LaunchedEffect(Unit) { while (true) { delay(60_000); now = app.ghostly.core.account.Cabinet.now() } }
    val subs = remember(st) { st.subs.sortedByDescending { it.active } }

    claim?.let { info ->
        Note(
            tr("Подписку «{0}» на этом устройстве ({1}) никто ещё не привязал к аккаунту. Привязать её к вашему?", info.plan.ifBlank { "Ghostly" }, info.device),
            Tone.ACCENT, Modifier.padding(top = 8.dp), Icons.Rounded.AddLink,
        )
        Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            SoftButton(tr("Не надо"), { controller.cabinet.answerClaim(false) }, Modifier.weight(1f))
            AccentButton(tr("Привязать"), { controller.cabinet.answerClaim(true) }, Modifier.weight(1f))
        }
    }

    if (wide) {
        Row(Modifier.fillMaxWidth().padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(18.dp)) {
            Column(Modifier.weight(1.15f)) {
                ProfileCard(controller, st, open, Modifier.appear(0))
                OpenOrders(controller, st)
                SubsBlock(controller, st, subs, now, open)
            }
            Column(Modifier.weight(1f)) {
                ReferralCard(controller, st, Modifier.padding(top = 0.dp).appear(2))
                Spacer(Modifier.height(12.dp))
                ActivityBlock(st, open)
                AccountBlock(controller, st, open)
            }
        }
    } else {
        ProfileCard(controller, st, open, Modifier.appear(0))
        OpenOrders(controller, st)
        SubsBlock(controller, st, subs, now, open)
        ReferralCard(controller, st, Modifier.padding(top = 12.dp).appear(3))
        ActivityBlock(st, open)
        AccountBlock(controller, st, open)
    }
}

/** What needs saying about the account itself: a discount running out, a guest account without a password. */
@Composable
private fun ProfileCard(controller: GhostlyController, st: CabinetState, open: (CabSheet) -> Unit, modifier: Modifier) {
    val a = st.account
    val discount = st.botState?.takeIf { it.discountLeft > 0 }?.let { b -> (b.prices.values.maxOfOrNull { it.percent } ?: 0).takeIf { it > 0 }?.let { it to b.discountLeft } }
    if (discount == null && a?.guest != true) return
    Tile(modifier.fillMaxWidth().padding(bottom = 12.dp), padding = 16.dp) {
        discount?.let { (pct, left) ->
            Note(
                tr("Скидка {0}% на тарифы после пробного периода. Осталось {1}", pct, Format.remaining(app.ghostly.core.account.Cabinet.now() / 1000 + left, app.ghostly.core.account.Cabinet.now())),
                Tone.OK, icon = Icons.Rounded.Redeem,
            )
        }
        if (a?.guest == true) {
            if (discount != null) Spacer(Modifier.height(12.dp))
            Note(tr("Аккаунт без пароля живёт только в этом приложении. Задайте логин и пароль или привяжите Telegram, чтобы не потерять подписку"), Tone.WARN, icon = Icons.Rounded.WarningAmber)
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                SoftButton(tr("Задать пароль"), { open(CabSheet.Register) }, Modifier.weight(1f), Icons.Rounded.Key)
                if (st.fullAccess) {
                    val scope = rememberCoroutineScope()
                    SoftButton("Telegram", { scope.launch { controller.cabinet.linkTelegram()?.let { controller.toast(it) } } }, Modifier.weight(1f), Icons.AutoMirrored.Rounded.Send)
                }
            }
        }
    }
}

/** Orders started but not paid: pay or cancel them right here. */
@Composable
private fun OpenOrders(controller: GhostlyController, st: CabinetState) {
    val payment by controller.cabinet.payment.collectAsState()
    val open = buildList {
        st.orders.filter { it.open && it.url != null }.forEach { add(it to false) }
        st.botState?.pending?.takeIf { it.open }?.let { add(it to true) }
    }.filter { (o, _) -> o.id != payment?.orderId }
    if (open.isEmpty()) return
    val scope = rememberCoroutineScope()
    open.forEach { (o, bot) ->
        Tile(Modifier.fillMaxWidth().padding(top = 12.dp), padding = 16.dp) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.WarningAmber, null, tint = Ghost.colors.warn, modifier = Modifier.size(22.dp))
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(tr("Заказ ждёт оплаты"), style = MaterialTheme.typography.titleSmall)
                    Text(listOfNotNull(o.title, rub(o.amount)).joinToString(" · "), style = MaterialTheme.typography.bodySmall)
                }
            }
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                SoftButton(tr("Отменить"), { scope.launch { controller.cabinet.cancelOrder(o, bot) } }, Modifier.weight(1f))
                if (o.url != null) AccentButton(tr("Оплатить"), { controller.cabinet.payOrder(o, bot) }, Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun SubsBlock(controller: GhostlyController, st: CabinetState, subs: List<CabinetSub>, now: Long, open: (CabSheet) -> Unit) {
    BlockTitle(if (subs.size > 1) tr("Подписки") else tr("Подписка"))
    if (subs.isEmpty()) {
        Tile(Modifier.fillMaxWidth().appear(1)) {
            Text(tr("Подписки пока нет"), style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(4.dp))
            Text(tr("Оформите тариф: оплата картой или СБП, подписка сразу появится в приложении"), style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(14.dp))
            AccentButton(tr("Выбрать тариф"), { open(CabSheet.Renew(null)) }, Modifier.fillMaxWidth(), Icons.Rounded.CalendarMonth)
            if (st.trial) {
                Spacer(Modifier.height(10.dp))
                val scope = rememberCoroutineScope()
                SoftButton(tr("24 часа бесплатно"), { scope.launch { controller.cabinet.trial() } }, Modifier.fillMaxWidth(), Icons.Rounded.Redeem)
            }
        }
        return
    }
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        subs.forEachIndexed { i, s -> SubCard(controller, st, s, now, open, Modifier.appear(1 + i)) }
    }
}

/**
 * One subscription: the plan and its state, the date, days left on a ring, the traffic pools side by side,
 * then what can be done (renew first). Its devices follow on a plate of their own.
 */
@Composable
private fun SubCard(controller: GhostlyController, st: CabinetState, s: CabinetSub, now: Long, open: (CabSheet) -> Unit, modifier: Modifier) {
    val c = Ghost.colors
    val scope = rememberCoroutineScope()
    val (leftNum, leftUnit, _) = app.ghostly.ui.components.ringLeft(s.expiry - now)
    val planDays = st.plan(s.planId)?.days?.takeIf { it > 0 } ?: 30
    val soon = s.active && !s.unlimitedTime && s.expiry - now < 3 * 86_400_000L
    val profiles by controller.profiles.collectAsState()
    val inApp = remember(s.id, profiles) { controller.hasSubscription(s.id) }
    val manageable = !s.bot || st.bridge
    Column(modifier.fillMaxWidth()) {
    Tile(Modifier.fillMaxWidth(), padding = 18.dp) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(s.plan.ifBlank { "Ghostly" }, style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                    Spacer(Modifier.width(8.dp))
                    when {
                        !s.active -> Chip(tr("Закончилась"), Tone.BAD)
                        soon -> Chip(tr("Скоро закончится"), Tone.WARN)
                        else -> Chip(tr("Активна"), Tone.ACCENT)
                    }
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    when {
                        s.unlimitedTime -> tr("Бессрочно")
                        s.active -> tr("до {0}", Format.date(s.expiry / 1000, controller.platform.utcOffsetMinutes()))
                        else -> tr("закончилась {0}", Format.date(s.expiry / 1000, controller.platform.utcOffsetMinutes()))
                    },
                    style = MaterialTheme.typography.bodyMedium.copy(fontSize = 13.sp), color = c.ink3,
                )
                if (s.bot || inApp) {
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        if (s.bot) Chip(tr("из бота"), Tone.NEUTRAL, icon = Icons.AutoMirrored.Rounded.Send)
                        if (inApp) Chip(tr("в приложении"), Tone.NEUTRAL)
                    }
                }
            }
            Spacer(Modifier.width(12.dp))
            RingProgress(
                if (s.unlimitedTime) 1f else ((s.expiry - now).coerceAtLeast(0) / 86_400_000f / planDays).coerceAtMost(1f),
                when { !s.active -> c.bad; soon -> c.warn; else -> c.accent },
                Modifier.size(64.dp), stroke = 4.dp,
            ) {
                app.ghostly.ui.components.RingLabel(if (s.unlimitedTime) "∞" else leftNum, if (s.unlimitedTime) null else leftUnit, big = false)
            }
        }
        // Traffic: the two pools side by side (regular servers / white lists) or one plain limit.
        val pools = s.pools?.takeIf { !it.unlimited }?.all().orEmpty()
        if (pools.isNotEmpty()) {
            Spacer(Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                pools.forEach { (id, p) ->
                    val total = p.limitGb.toLong() * 1_073_741_824L
                    val frac = if (total > 0) p.used.toFloat() / total else 0f
                    Column(Modifier.weight(1f)) {
                        Text(p.title + if (p.blocked) tr(" · закончился") else "", style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.5.sp), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Spacer(Modifier.height(2.dp))
                        Text(tr("{0} из {1} ГБ", Format.bytes(p.used), p.limitGb), style = MaterialTheme.typography.bodySmall.copy(fontSize = 13.sp, fontFeatureSettings = "tnum"), color = c.ink, maxLines = 1)
                        Spacer(Modifier.height(7.dp))
                        GlowBar(frac, when { p.blocked || frac > 0.95f -> c.bad; frac > 0.8f -> c.warn; id == "wl" -> c.accent; else -> c.ok }, Modifier.height(4.dp))
                    }
                }
            }
            s.pools?.cycleEnd?.takeIf { it > 0 }?.let {
                Spacer(Modifier.height(10.dp))
                Text(tr("Трафик обновится {0}", Format.date(it / 1000, controller.platform.utcOffsetMinutes())), style = MaterialTheme.typography.bodySmall)
            }
        } else if (!s.unlimitedTraffic) {
            Spacer(Modifier.height(16.dp))
            val total = s.trafficGb.toLong() * 1_073_741_824L
            Row {
                Text(tr("Трафик"), style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.5.sp), modifier = Modifier.weight(1f))
                Text(tr("{0} из {1} ГБ", Format.bytes(s.used), s.trafficGb), style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.5.sp, fontFeatureSettings = "tnum"), color = c.ink)
            }
            Spacer(Modifier.height(7.dp))
            GlowBar(if (total > 0) s.used.toFloat() / total else 0f, c.accent, Modifier.height(4.dp))
        }

        if (!inApp && s.active && s.url.isNotBlank()) {
            Spacer(Modifier.height(14.dp))
            SoftButton(tr("Добавить в приложение"), { controller.haptic(); controller.cabinet.addToApp(s) }, Modifier.fillMaxWidth(), Icons.Rounded.Add, tint = c.accent)
        }

        Spacer(Modifier.height(16.dp))
        if (!manageable) {
            Note(tr("Подписка из бота: бот сейчас не отвечает, управлять ею можно в Telegram"), icon = Icons.AutoMirrored.Rounded.Send)
            Spacer(Modifier.height(10.dp))
            SoftButton(tr("Открыть бота"), { controller.platform.openUrl("https://t.me/${st.botName}?start=renew") }, Modifier.fillMaxWidth())
            return@Tile
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            if (!s.unlimitedTime || !s.active) {
                PillButton(tr("Продлить"), { controller.haptic(); open(CabSheet.Renew(s.id)) }, Modifier.weight(1.3f), primary = true)
            }
            if (s.active && !s.unlimitedTraffic) PillButton(tr("Трафик"), { controller.haptic(); open(CabSheet.Gb(s.id)) }, Modifier.weight(1f))
            if (s.active && s.devices > 0 && s.devices < st.prices.devicesMax) PillButton(tr("Места"), { controller.haptic(); open(CabSheet.MoreDevices(s.id)) }, Modifier.weight(1f))
            SubMenu(controller, st, s, open)
        }
        if (!s.active && !s.bot) {
            Spacer(Modifier.height(10.dp))
            Text(
                tr("Убрать из кабинета"), style = MaterialTheme.typography.labelMedium, color = c.ink3,
                modifier = Modifier.clip(RoundedCornerShape(8.dp)).hoverSound().clickable { scope.launch { controller.cabinet.hide(s) } }.padding(6.dp),
            )
        }
    }
    if (s.hw.isNotEmpty() || s.devices > 0) DevicesPlate(controller, s, manageable) { open(CabSheet.Devices(s.id)) }
    }
}

/** A row of the subscription's actions: the accent pill for the main one, outlined pills for the rest. */
@Composable
private fun PillButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, primary: Boolean = false) {
    val c = Ghost.colors
    val interaction = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
    Box(
        modifier.height(42.dp).pressScale(interaction, 0.96f).clip(CircleShape)
            .then(if (primary) Modifier.background(c.accent) else Modifier.border(1.dp, Color.White.copy(alpha = 0.12f), CircleShape))
            .hoverSound().clickable(interaction, null, onClick = onClick)
            .padding(horizontal = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text, style = MaterialTheme.typography.labelLarge.copy(fontSize = 14.sp, fontWeight = if (primary) FontWeight.Bold else FontWeight.SemiBold),
            color = if (primary) c.accentInk else c.ink, maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
    }
}

/** The subscription's devices: this one marked, the others with when they were last seen. */
@Composable
private fun DevicesPlate(controller: GhostlyController, s: CabinetSub, manageable: Boolean, onOpen: () -> Unit) {
    val c = Ghost.colors
    val me = controller.cabinet.deviceId
    val list = s.hw.filter { it.active }.sortedByDescending { it.id == me }
    Tile(Modifier.fillMaxWidth().padding(top = 12.dp), padding = 6.dp, onClick = if (manageable) onOpen else null) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                if (s.devices > 0) tr("Устройства: {0} из {1}", list.size, s.devices) else tr("Устройства: {0}, без лимита", list.size),
                style = MaterialTheme.typography.labelMedium, color = c.ink3, modifier = Modifier.weight(1f),
            )
            if (manageable) Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null, tint = c.ink4, modifier = Modifier.size(18.dp))
        }
        list.take(3).forEachIndexed { i, d ->
            if (i > 0) Box(Modifier.padding(horizontal = 12.dp).fillMaxWidth().height(1.dp).background(Color.White.copy(alpha = 0.06f)))
            val mine = d.id == me
            Row(Modifier.fillMaxWidth().heightIn(min = 44.dp).padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                val desk = (d.os ?: d.model).let { it.contains("Windows", true) || it.contains("mac", true) || it.contains("Linux", true) }
                Icon(if (desk) Icons.Rounded.DesktopWindows else Icons.Rounded.PhoneAndroid, null, tint = if (mine) c.accent else c.ink4, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(12.dp))
                Text(d.model.ifBlank { d.os ?: "—" }, style = MaterialTheme.typography.bodyLarge.copy(fontSize = 15.sp), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                Text(
                    if (mine) tr("это устройство") else d.ago.orEmpty(),
                    style = MaterialTheme.typography.bodySmall, color = if (mine) c.accent else c.ink3, maxLines = 1,
                )
            }
        }
    }
}

/** ⋯ on a subscription: copy its link, hand it over. */
@Composable
private fun SubMenu(controller: GhostlyController, st: CabinetState, s: CabinetSub, open: (CabSheet) -> Unit) {
    var menu by remember { mutableStateOf(false) }
    Box {
        IconBubble(Icons.Rounded.MoreHoriz, { menu = true }, size = 38.dp)
        DropdownMenu(menu, { menu = false }) {
            if (s.url.isNotBlank()) DropdownMenuItem(
                text = { Text(tr("Скопировать ссылку")) },
                leadingIcon = { Icon(Icons.Rounded.ContentCopy, null) },
                onClick = { menu = false; controller.platform.copyToClipboard(s.url); controller.toast(tr("Ссылка скопирована")) },
            )
            s.backup?.let { b ->
                DropdownMenuItem(
                    text = { Text(tr("Запасная ссылка")) },
                    leadingIcon = { Icon(Icons.Rounded.ContentCopy, null) },
                    onClick = { menu = false; controller.platform.copyToClipboard(b); controller.toast(tr("Ссылка скопирована")) },
                )
            }
            if (st.fullAccess && s.active && (!s.bot || st.bridge)) DropdownMenuItem(
                text = { Text(tr("Передать другому")) },
                leadingIcon = { Icon(Icons.Rounded.SwapHoriz, null) },
                onClick = { menu = false; open(CabSheet.Transfer(s.id)) },
            )
            s.page?.let { page ->
                DropdownMenuItem(
                    text = { Text(tr("Страница подписки")) },
                    leadingIcon = { Icon(Icons.Rounded.Language, null) },
                    onClick = { menu = false; controller.platform.openUrl(page) },
                )
            }
        }
    }
}

/** Invite friends: what they brought, the terms, share the link (a tap on the plate copies it). */
@Composable
private fun ReferralCard(controller: GhostlyController, st: CabinetState, modifier: Modifier) {
    val r = st.ref ?: return
    val link = r.link ?: return
    val c = Ghost.colors
    Tile(modifier.fillMaxWidth(), padding = 14.dp, onClick = { controller.platform.copyToClipboard(link); controller.toast(tr("Ссылка скопирована")) }) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f).padding(start = 4.dp)) {
                Text(
                    if (r.earned > 0) tr("Друзья принесли {0}", rub(r.earned)) else tr("Приглашайте друзей"),
                    style = MaterialTheme.typography.titleSmall.copy(fontSize = 15.sp), maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(3.dp))
                Text(
                    tr("{0}% от первой оплаты друга придёт вам на баланс, до {1}", r.percent, rub(r.max)),
                    style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.5.sp), maxLines = 2,
                )
            }
            Spacer(Modifier.width(12.dp))
            Box(
                Modifier.size(46.dp).clip(CircleShape).background(c.accent.copy(alpha = 0.18f)).hoverSound()
                    .clickable { controller.platform.share(tr("Ghostly VPN: быстрый VPN без блокировок. Моя ссылка: {0}", link)) },
                contentAlignment = Alignment.Center,
            ) { Icon(Icons.Rounded.Share, tr("Поделиться"), tint = androidx.compose.ui.graphics.lerp(c.accent, Color.White, 0.3f), modifier = Modifier.size(19.dp)) }
        }
    }
}

/** The latest balance operations; the rest in a sheet. */
@Composable
private fun ActivityBlock(st: CabinetState, open: (CabSheet) -> Unit) {
    if (st.history.isEmpty()) return
    BlockTitle(tr("Операции")) {
        if (st.history.size > 4) Text(
            tr("Все"), style = MaterialTheme.typography.labelLarge, color = Ghost.colors.accent,
            modifier = Modifier.clip(RoundedCornerShape(8.dp)).hoverSound().clickable { open(CabSheet.History) }.padding(horizontal = 8.dp, vertical = 4.dp),
        )
    }
    Tile(Modifier.fillMaxWidth(), padding = 8.dp) {
        st.history.take(4).forEach { HistoryRow(it) }
    }
}

@Composable
internal fun HistoryRow(h: HistoryItem) {
    val c = Ghost.colors
    Row(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(historyTitle(h), style = MaterialTheme.typography.bodyMedium.copy(color = c.ink), maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(agoText(h.ts), style = MaterialTheme.typography.bodySmall)
        }
        Text(
            (if (h.delta > 0) "+" else "") + rub(h.delta),
            style = MaterialTheme.typography.titleSmall.copy(fontFeatureSettings = "tnum"),
            color = if (h.delta > 0) c.ok else c.ink2,
        )
    }
}

private fun historyTitle(h: HistoryItem): String = when (h.type) {
    "web_topup", "topup", "platega_topup", "stars_topup" -> tr("Пополнение")
    "referral_bonus" -> tr("Бонус за друга")
    "extend_days", "web_extend" -> tr("Продление по дням")
    "gb_addon", "pool_gb" -> tr("Докупка трафика")
    "devices" -> tr("Докупка устройств")
    "web_order_balance", "order_balance" -> if (h.delta > 0) tr("Возврат за заказ") else tr("Оплата тарифа")
    "admin", "manual" -> tr("Начисление")
    else -> h.note?.takeIf { it.isNotBlank() } ?: h.type
}

internal fun agoText(sec: Long): String {
    if (sec <= 0) return ""
    val now = app.ghostly.core.account.Cabinet.now() / 1000
    val d = (now - sec).coerceAtLeast(0)
    return when {
        d < 90 -> tr("только что")
        d < 3600 -> tr("{0} мин назад", d / 60)
        d < 86400 -> tr("{0} ч назад", d / 3600)
        else -> Format.date(sec, 180)
    }
}

/** Sign-in, password, Telegram, sessions, the site, sign-out. */
@Composable
private fun AccountBlock(controller: GhostlyController, st: CabinetState, open: (CabSheet) -> Unit) {
    val scope = rememberCoroutineScope()
    val a = st.account
    BlockTitle(tr("Безопасность"))
    Tile(Modifier.fillMaxWidth(), padding = 10.dp) {
        if (a?.tg == null && st.fullAccess) {
            SettingRow(tr("Привязать Telegram"), tr("Вход в одно касание и общий баланс с ботом"), Icons.AutoMirrored.Rounded.Send, onClick = {
                scope.launch { controller.cabinet.linkTelegram()?.let { controller.toast(it) } }
            }) { Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null, tint = Ghost.colors.ink3) }
        } else if (a?.tg != null) {
            SettingRow("Telegram", a.tg.username?.let { "@$it" } ?: tr("привязан"), Icons.AutoMirrored.Rounded.Send)
        }
        if (a?.guest == true) {
            SettingRow(tr("Задать логин и пароль"), tr("Чтобы входить с любого устройства"), Icons.Rounded.Key, onClick = { open(CabSheet.Register) }) {
                Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null, tint = Ghost.colors.ink3)
            }
        } else if (st.fullAccess && a?.login != null) {
            SettingRow(tr("Пароль"), tr("Сменить пароль"), Icons.Rounded.Key, onClick = { open(CabSheet.Password) }) {
                Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null, tint = Ghost.colors.ink3)
            }
        }
        SettingRow(tr("Сессии"), tr("Где открыт аккаунт: {0}", st.sessions.size), Icons.Rounded.History, onClick = { open(CabSheet.Sessions) }) {
            Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null, tint = Ghost.colors.ink3)
        }
        SettingRow(tr("Получить подписку по коду"), tr("Код передачи от другого аккаунта"), Icons.Rounded.SwapHoriz, onClick = { open(CabSheet.ClaimTransfer) }) {
            Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null, tint = Ghost.colors.ink3)
        }
        SettingRow(tr("Кабинет на сайте"), tr("Двухэтапная защита и всё остальное"), Icons.Rounded.Language, onClick = { controller.openAccountOnSite() }) {
            Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null, tint = Ghost.colors.ink3)
        }
    }
    if (!st.fullAccess) {
        Spacer(Modifier.height(10.dp))
        Note(tr("Приложение вошло в аккаунт само. Пароль, двухэтапная защита и передача подписки доступны после обычного входа: через Telegram или по логину"))
    }
    Spacer(Modifier.height(14.dp))
    var armed by remember { mutableStateOf(false) }
    LaunchedEffect(armed) { if (armed) { delay(4000); armed = false } }
    SoftButton(
        when {
            armed && a?.guest == true -> tr("Точно? Без пароля в этот аккаунт не вернуться")
            armed -> tr("Нажмите ещё раз, чтобы выйти")
            else -> tr("Выйти из аккаунта")
        },
        {
            if (!armed) armed = true else { armed = false; controller.cabinet.logout() }
        },
        Modifier.fillMaxWidth(), Icons.AutoMirrored.Rounded.Logout, tint = if (armed) Ghost.colors.bad else Ghost.colors.ink2,
    )
}

// ============================================================================ payment banner

/** While a card payment is open in the browser: what is being paid and how it is going. */
@Composable
private fun PaymentBanner(controller: GhostlyController, modifier: Modifier) {
    val p by controller.cabinet.payment.collectAsState()
    AnimatedVisibility(p != null, modifier = modifier, enter = slideInVertically { it } + fadeIn(), exit = slideOutVertically { it } + fadeOut()) {
        var last by remember { mutableStateOf(p) }
        p?.let { last = it }
        val w = last ?: return@AnimatedVisibility
        val c = Ghost.colors
        Row(
            Modifier.widthIn(max = 560.dp).fillMaxWidth().clip(RoundedCornerShape(Radius.card)).background(c.surface2)
                .border(1.dp, c.accent.copy(alpha = 0.35f), RoundedCornerShape(Radius.card)).padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Spinner(c.accent, Modifier.size(20.dp))
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    if (w.status == "paid" || w.status == "provisioning") tr("Оплата получена, продлеваю") else tr("Ждём оплату"),
                    style = MaterialTheme.typography.titleSmall,
                )
                Text(listOfNotNull(w.title, w.amount.takeIf { it > 0 }?.let { rub(it) }).joinToString(" · "), style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            w.url?.let { u ->
                Text(
                    tr("Оплатить"), style = MaterialTheme.typography.labelLarge, color = c.accent,
                    modifier = Modifier.clip(RoundedCornerShape(10.dp)).hoverSound().clickable { controller.platform.openUrl(u) }.padding(8.dp),
                )
            }
            Box(Modifier.size(34.dp).clip(CircleShape).hoverSound().clickable { controller.cabinet.forgetPayment() }, contentAlignment = Alignment.Center) {
                Icon(Icons.Rounded.Close, tr("Скрыть"), tint = c.ink3, modifier = Modifier.size(18.dp))
            }
        }
    }
}

internal fun Order.isOpenPayable(): Boolean = open && url != null

@Composable
internal fun ColorDot(color: Color) {
    Box(Modifier.size(8.dp).clip(CircleShape).background(color))
}
