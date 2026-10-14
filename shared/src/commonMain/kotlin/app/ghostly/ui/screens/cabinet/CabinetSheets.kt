package app.ghostly.ui.screens.cabinet

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.rounded.AccountBalanceWallet
import androidx.compose.material.icons.rounded.Block
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.CreditCard
import androidx.compose.material.icons.rounded.Devices
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.LinkOff
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.SwapHoriz
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
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
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import app.ghostly.core.GhostlyController
import app.ghostly.core.account.CabinetState
import app.ghostly.core.account.CabinetSub
import app.ghostly.core.account.CabinetUi
import app.ghostly.core.account.Device
import app.ghostly.core.account.Outcome
import app.ghostly.core.account.Plan
import app.ghostly.i18n.tr
import app.ghostly.ui.Format
import app.ghostly.ui.components.AccentButton
import app.ghostly.ui.components.ChoiceRow
import app.ghostly.ui.components.Chip
import app.ghostly.ui.components.Field
import app.ghostly.ui.components.GhostSwitch
import app.ghostly.ui.components.IconBubble
import app.ghostly.ui.components.Note
import app.ghostly.ui.components.Radius
import app.ghostly.ui.components.Segmented
import app.ghostly.ui.components.SoftButton
import app.ghostly.ui.components.Stepper
import app.ghostly.ui.components.Tone
import app.ghostly.ui.components.hoverSound
import app.ghostly.ui.components.pressScale
import app.ghostly.ui.components.rub
import app.ghostly.ui.theme.Ghost
import app.ghostly.ui.theme.Motion
import kotlinx.coroutines.launch

/** Bottom sheet on a phone, a dialog on a wide window: the frame every cabinet sheet sits in. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun CabinetSheetHost(controller: GhostlyController, sheet: CabSheet, wide: Boolean, onOpen: (CabSheet) -> Unit, onClose: () -> Unit) {
    val c = Ghost.colors
    val body: @Composable ColumnScope.() -> Unit = {
        when (sheet) {
            is CabSheet.Renew -> RenewSheet(controller, sheet.subId, onOpen, onClose)
            is CabSheet.Gb -> GbSheet(controller, sheet.subId, onOpen, onClose)
            is CabSheet.MoreDevices -> MoreDevicesSheet(controller, sheet.subId, onOpen, onClose)
            is CabSheet.Devices -> DevicesSheet(controller, sheet.subId, onClose)
            CabSheet.Topup -> TopupSheet(controller, onClose)
            CabSheet.History -> HistorySheet(controller, onClose)
            CabSheet.Sessions -> SessionsSheet(controller, onClose)
            CabSheet.Password -> PasswordSheet(controller, onClose)
            CabSheet.Register -> RegisterSheet(controller, onClose)
            CabSheet.ClaimTransfer -> ClaimSheet(controller, onClose)
            is CabSheet.Transfer -> TransferSheet(controller, sheet.subId, onClose)
        }
    }
    if (wide) {
        Dialog(onDismissRequest = onClose) {
            Column(
                Modifier.widthIn(max = 520.dp).heightIn(max = 760.dp).clip(RoundedCornerShape(Radius.card)).background(c.surface1)
                    .border(1.dp, c.hairline, RoundedCornerShape(Radius.card))
                    .verticalScroll(rememberScrollState()).padding(horizontal = 22.dp, vertical = 20.dp),
                content = body,
            )
        }
    } else {
        ModalBottomSheet(
            onDismissRequest = onClose,
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            containerColor = c.surface1, scrimColor = Color.Black.copy(alpha = 0.6f),
        ) {
            Column(
                Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).imePadding().navigationBarsPadding()
                    .padding(start = 20.dp, end = 20.dp, bottom = 22.dp),
                content = body,
            )
        }
    }
}

@Composable
private fun SheetHead(title: String, subtitle: String?, onClose: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(bottom = 16.dp), verticalAlignment = Alignment.Top) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.headlineSmall)
            subtitle?.let {
                Spacer(Modifier.height(4.dp))
                Text(it, style = MaterialTheme.typography.bodyMedium)
            }
        }
        Spacer(Modifier.width(10.dp))
        IconBubble(Icons.Rounded.Close, onClose, size = 38.dp)
    }
}

@Composable
private fun cabState(controller: GhostlyController): CabinetState? {
    val ui by controller.cabinet.ui.collectAsState()
    return (ui as? CabinetUi.Ready)?.state ?: (ui as? CabinetUi.SignedOut)?.anon
}

/** The result of the last tap, under the button: the error and, when money is short, a way to top up. */
@Composable
private fun OutcomeNote(outcome: Outcome?, onTopup: (() -> Unit)?) {
    val o = outcome ?: return
    if (o.ok) return
    Spacer(Modifier.height(12.dp))
    Note(o.message ?: tr("Не получилось"), Tone.BAD)
    if (o.code == "funds" && onTopup != null) {
        Spacer(Modifier.height(10.dp))
        SoftButton(tr("Пополнить баланс"), onTopup, Modifier.fillMaxWidth(), Icons.Rounded.AccountBalanceWallet, tint = Ghost.colors.accent)
    }
}

@Composable
private fun BalanceLine(st: CabinetState, cost: Int) {
    val c = Ghost.colors
    val short = cost - st.balance
    Row(Modifier.fillMaxWidth().padding(top = 14.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(tr("На балансе {0}", rub(st.balance)), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        if (short > 0.0001) Text(tr("не хватает {0}", rub(short)), style = MaterialTheme.typography.labelMedium, color = c.warn)
    }
}

// ============================================================================ renew / buy a plan

@Composable
private fun RenewSheet(controller: GhostlyController, subId: String?, onOpen: (CabSheet) -> Unit, onClose: () -> Unit) {
    val st = cabState(controller) ?: return
    val sub = subId?.let { id -> st.subs.firstOrNull { it.id == id } }
    val signedIn = st.auth
    val canDays = sub != null && signedIn && st.prices.day > 0 && (!sub.bot || st.bridge)
    var mode by remember { mutableIntStateOf(0) }
    SheetHead(
        if (sub == null) tr("Новая подписка") else tr("Продлить подписку"),
        if (sub == null) tr("Ссылка придёт сразу после оплаты и сама добавится в приложение") else tr("Дни прибавятся к текущему сроку, ссылка останется той же"),
        onClose,
    )
    if (canDays) {
        Segmented(listOf(0 to tr("Тариф"), 1 to tr("По дням с баланса")), mode, { mode = it })
        Spacer(Modifier.height(16.dp))
    }
    if (mode == 1 && sub != null) ExtendDays(controller, st, sub, onOpen, onClose) else PlanPicker(controller, st, sub, onOpen, onClose)
}

@Composable
private fun PlanPicker(controller: GhostlyController, st: CabinetState, sub: CabinetSub?, onOpen: (CabSheet) -> Unit, onClose: () -> Unit) {
    val c = Ghost.colors
    val scope = rememberCoroutineScope()
    val busy by controller.cabinet.busy.collectAsState()
    val bot = sub?.bot == true
    val plans = st.plans
    if (plans.isEmpty()) {
        Note(tr("Тарифы не загрузились. Обновите кабинет"), Tone.WARN)
        return
    }
    var picked by remember { mutableStateOf(plans.firstOrNull { it.id == sub?.planId && it.amount > 0 } ?: plans.firstOrNull { it.id == st.popular } ?: plans.first()) }
    var promo by remember { mutableStateOf("") }
    var promoOpen by remember { mutableStateOf(false) }
    var quote by remember { mutableStateOf<Pair<Int, String?>?>(null) } // (total, error)
    var useBalance by remember { mutableStateOf(st.auth && st.balance > 0) }
    var outcome by remember { mutableStateOf<Outcome?>(null) }

    fun priceOf(p: Plan): Int = if (bot) st.botState?.prices?.get(p.id)?.amount ?: p.amount else p.amount
    val monthly = plans.minOfOrNull { priceOf(it).toDouble() / it.days.coerceAtLeast(1) } ?: 0.0

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        plans.forEach { p ->
            val on = p.id == picked.id
            val price = priceOf(p)
            val perMonth = price * 30.0 / p.days.coerceAtLeast(1)
            val edge by animateColorAsState(if (on) c.accent.copy(alpha = 0.7f) else c.hairline, Motion.quick())
            val interaction = remember { MutableInteractionSource() }
            Row(
                Modifier.fillMaxWidth().pressScale(interaction, 0.98f).clip(RoundedCornerShape(Radius.tile))
                    .background(if (on) c.accent.copy(alpha = 0.12f) else c.surface2)
                    .border(1.dp, edge, RoundedCornerShape(Radius.tile))
                    .hoverSound().clickable(interaction, null) { controller.haptic(app.ghostly.core.vpn.Haptic.TICK); picked = p; quote = null; outcome = null }
                    .padding(horizontal = 16.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(p.title, style = MaterialTheme.typography.titleMedium)
                        if (p.id == st.popular) {
                            Spacer(Modifier.width(8.dp))
                            Chip(tr("популярный"), Tone.ACCENT)
                        }
                    }
                    Spacer(Modifier.height(2.dp))
                    Text(
                        if (p.days >= 60) tr("{0} в месяц", rub(kotlin.math.round(perMonth))) else tr("{0} {1}", p.days, Format.plural(p.days.toLong(), "день", "дня", "дней")),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text(rub(price), style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold, fontFeatureSettings = "tnum"))
                    if (price < p.amount) Text(rub(p.amount), style = MaterialTheme.typography.bodySmall.copy(textDecoration = TextDecoration.LineThrough))
                    else if (p.days >= 60 && perMonth / 30 < monthly * 1.02 && monthly > 0) Text(tr("выгоднее всего"), style = MaterialTheme.typography.labelSmall, color = c.ok)
                }
            }
        }
    }

    val base = priceOf(picked)
    val total = quote?.takeIf { it.second == null }?.first ?: base
    val payByBalance = useBalance && st.auth && st.balance > 0 && !(bot && promo.isNotBlank())
    val fromBalance = if (payByBalance) minOf(st.balance, total.toDouble()) else 0.0
    val toPay = (total - fromBalance).coerceAtLeast(0.0)

    Spacer(Modifier.height(14.dp))
    if (!promoOpen) {
        Text(
            tr("У меня есть промокод"), style = MaterialTheme.typography.labelLarge, color = c.accent,
            modifier = Modifier.clip(RoundedCornerShape(8.dp)).hoverSound().clickable { promoOpen = true }.padding(6.dp),
        )
    } else {
        Row(verticalAlignment = Alignment.Bottom) {
            Field(
                promo, { promo = it.uppercase().take(32); quote = null }, tr("Промокод"), Modifier.weight(1f),
                error = quote?.second, imeAction = ImeAction.Done,
                onDone = { scope.launch { val (q, e) = controller.cabinet.quote(picked, promo, sub); quote = (q?.total ?: base) to e } },
            )
            Spacer(Modifier.width(10.dp))
            SoftButton(tr("Применить"), {
                if (promo.isNotBlank()) scope.launch { val (q, e) = controller.cabinet.quote(picked, promo, sub); quote = (q?.total ?: base) to e }
            }, Modifier.padding(bottom = if (quote?.second != null) 24.dp else 0.dp))
        }
        quote?.takeIf { it.second == null && it.first < base }?.let {
            Spacer(Modifier.height(8.dp))
            Text(tr("Скидка {0}", rub(base - it.first)), style = MaterialTheme.typography.labelLarge, color = c.ok)
        }
    }

    if (st.auth && st.balance > 0) {
        Spacer(Modifier.height(12.dp))
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(Radius.tile)).background(c.surface2)
                .hoverSound().clickable { useBalance = !useBalance }.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(tr("Списать с баланса"), style = MaterialTheme.typography.titleSmall)
                Text(
                    if (bot && promo.isNotBlank()) tr("В боте баланс не сочетается с промокодом") else tr("На балансе {0}", rub(st.balance)),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            GhostSwitch(payByBalance)
        }
    }

    Spacer(Modifier.height(16.dp))
    AccentButton(
        when {
            busy == "checkout" -> tr("Создаю заказ…")
            toPay <= 0.0 -> tr("Оплатить балансом {0}", rub(total))
            else -> tr("Оплатить {0}", rub(toPay))
        },
        {
            controller.haptic(app.ghostly.core.vpn.Haptic.HEAVY)
            scope.launch {
                val o = controller.cabinet.checkout(picked, sub, promo, payByBalance)
                outcome = o
                if (o.ok) onClose()
            }
        },
        Modifier.fillMaxWidth(), if (toPay <= 0.0) Icons.Rounded.AccountBalanceWallet else Icons.Rounded.CreditCard, enabled = busy == null,
    )
    if (toPay > 0.0) {
        Spacer(Modifier.height(8.dp))
        Text(tr("Карта или СБП. Страница оплаты откроется в браузере, потом возвращайтесь сюда"), style = MaterialTheme.typography.bodySmall)
    }
    OutcomeNote(outcome) { onOpen(CabSheet.Topup) }
}

@Composable
private fun ExtendDays(controller: GhostlyController, st: CabinetState, sub: CabinetSub, onOpen: (CabSheet) -> Unit, onClose: () -> Unit) {
    val scope = rememberCoroutineScope()
    val busy by controller.cabinet.busy.collectAsState()
    var days by remember { mutableIntStateOf(7) }
    var outcome by remember { mutableStateOf<Outcome?>(null) }
    val cost = days * st.prices.day
    Text(tr("{0} за день, сколько угодно дней", rub(st.prices.day)), style = MaterialTheme.typography.bodyMedium)
    Spacer(Modifier.height(12.dp))
    Stepper(days, 1, 365, { days = it; outcome = null }, format = { "$it ${Format.plural(it.toLong(), "день", "дня", "дней")}" })
    Spacer(Modifier.height(10.dp))
    ChoiceRow(listOf(3 to tr("3 дня"), 7 to tr("7 дней"), 14 to tr("14 дней"), 30 to tr("30 дней")), days, { days = it; outcome = null })
    BalanceLine(st, cost)
    Spacer(Modifier.height(16.dp))
    AccentButton(
        if (busy == "extend") tr("Продлеваю…") else tr("Продлить за {0}", rub(cost)),
        {
            scope.launch {
                val o = controller.cabinet.extend(sub, days)
                outcome = o
                if (o.ok) onClose()
            }
        },
        Modifier.fillMaxWidth(), enabled = busy == null,
    )
    OutcomeNote(outcome) { onOpen(CabSheet.Topup) }
}

// ============================================================================ gigabytes / devices

@Composable
private fun GbSheet(controller: GhostlyController, subId: String, onOpen: (CabSheet) -> Unit, onClose: () -> Unit) {
    val st = cabState(controller) ?: return
    val sub = st.subs.firstOrNull { it.id == subId } ?: return
    val scope = rememberCoroutineScope()
    val busy by controller.cabinet.busy.collectAsState()
    val pools = sub.pools?.all().orEmpty().filter { (_, p) -> p.topupLeft > 0 }
    SheetHead(tr("Докупить трафик"), tr("Гигабайты добавятся к текущему периоду"), onClose)
    if (pools.isEmpty()) {
        Note(tr("В этом периоде докупать больше нельзя: лимит докупки исчерпан. Трафик обновится сам в начале нового периода"), Tone.WARN)
        return
    }
    var pool by remember { mutableStateOf(pools.first().first) }
    val p = pools.firstOrNull { it.first == pool }?.second ?: pools.first().second
    var gb by remember(pool) { mutableIntStateOf(minOf(5, p.topupLeft)) }
    var outcome by remember { mutableStateOf<Outcome?>(null) }
    if (pools.size > 1) {
        Segmented(pools.map { it.first to it.second.title }, pool, { pool = it; outcome = null })
        Spacer(Modifier.height(14.dp))
    }
    Text(tr("{0} за 1 ГБ · можно ещё {1} ГБ", rub(p.price), p.topupLeft), style = MaterialTheme.typography.bodyMedium)
    Spacer(Modifier.height(12.dp))
    Stepper(gb, 1, p.topupLeft, { gb = it; outcome = null }, format = { tr("+{0} ГБ", it) })
    val cost = gb * p.price
    BalanceLine(st, cost)
    Spacer(Modifier.height(16.dp))
    AccentButton(
        if (busy == "gb") tr("Покупаю…") else tr("Купить за {0}", rub(cost)),
        { scope.launch { val o = controller.cabinet.buyGb(sub, pool, gb); outcome = o; if (o.ok) onClose() } },
        Modifier.fillMaxWidth(), enabled = busy == null,
    )
    OutcomeNote(outcome) { onOpen(CabSheet.Topup) }
}

@Composable
private fun MoreDevicesSheet(controller: GhostlyController, subId: String, onOpen: (CabSheet) -> Unit, onClose: () -> Unit) {
    val st = cabState(controller) ?: return
    val sub = st.subs.firstOrNull { it.id == subId } ?: return
    val scope = rememberCoroutineScope()
    val busy by controller.cabinet.busy.collectAsState()
    val left = (st.prices.devicesMax - sub.devices).coerceAtLeast(0)
    SheetHead(tr("Ещё устройства"), tr("Сейчас можно подключить {0}. Докупка разовая, на весь срок подписки", sub.devices), onClose)
    if (left <= 0) {
        Note(tr("Уже максимум: {0} устройств", st.prices.devicesMax), Tone.WARN)
        return
    }
    var n by remember { mutableIntStateOf(1) }
    var outcome by remember { mutableStateOf<Outcome?>(null) }
    Stepper(n, 1, left, { n = it; outcome = null }, format = { "+$it" })
    val cost = n * st.prices.device
    BalanceLine(st, cost)
    Spacer(Modifier.height(16.dp))
    AccentButton(
        if (busy == "devices") tr("Покупаю…") else tr("Добавить за {0}", rub(cost)),
        { scope.launch { val o = controller.cabinet.buyDevices(sub, n); outcome = o; if (o.ok) onClose() } },
        Modifier.fillMaxWidth(), enabled = busy == null,
    )
    OutcomeNote(outcome) { onOpen(CabSheet.Topup) }
}

/** Devices on the subscription: disconnect one, ban it, allow a banned one back. */
@Composable
private fun DevicesSheet(controller: GhostlyController, subId: String, onClose: () -> Unit) {
    val st = cabState(controller) ?: return
    val sub = st.subs.firstOrNull { it.id == subId } ?: return
    val c = Ghost.colors
    val busy by controller.cabinet.busy.collectAsState()
    val scope = rememberCoroutineScope()
    var confirm by remember { mutableStateOf<Pair<String, String>?>(null) } // (device id, act)
    val me = controller.cabinet.deviceId
    SheetHead(
        tr("Устройства"),
        if (sub.devices > 0) tr("Подключено {0} из {1}", sub.hw.count { it.active }, sub.devices) else tr("Без лимита устройств"),
        onClose,
    )
    if (sub.hw.isEmpty()) {
        Note(tr("Пока ни одно устройство не загружало подписку. Устройство появится здесь после первого обновления подписки в приложении"))
    }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        sub.hw.forEach { d ->
            DeviceRow(d, d.id == me) {
                val asking = confirm?.first == d.id
                if (asking) {
                    val act = confirm!!.second
                    Spacer(Modifier.height(10.dp))
                    Text(
                        if (act == "ban") tr("Запретить? Устройство потеряет доступ и не вернётся, пока вы его не разрешите")
                        else tr("Отключить? Устройство потеряет доступ, но сможет вернуться, если освободится место"),
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        SoftButton(tr("Отмена"), { confirm = null }, Modifier.weight(1f))
                        AccentButton(
                            if (busy == "device:${d.id}") "…" else if (act == "ban") tr("Запретить") else tr("Отключить"),
                            { scope.launch { controller.cabinet.device(sub, d, act); confirm = null } },
                            Modifier.weight(1f), enabled = busy == null,
                        )
                    }
                } else {
                    Spacer(Modifier.height(10.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        SoftButton(tr("Отключить"), { confirm = d.id to "remove" }, Modifier.weight(1f), Icons.Rounded.LinkOff)
                        SoftButton(tr("Запретить"), { confirm = d.id to "ban" }, Modifier.weight(1f), Icons.Rounded.Block, tint = c.bad)
                    }
                }
            }
        }
    }
    if (sub.hwb.isNotEmpty()) {
        Spacer(Modifier.height(18.dp))
        Text(tr("Запрещённые"), style = MaterialTheme.typography.titleSmall)
        Spacer(Modifier.height(8.dp))
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            sub.hwb.forEach { d ->
                DeviceRow(d, d.id == me) {
                    Spacer(Modifier.height(10.dp))
                    SoftButton(
                        if (busy == "device:${d.id}") "…" else tr("Разрешить"),
                        { scope.launch { controller.cabinet.device(sub, d, "unban") } }, Modifier.fillMaxWidth(), Icons.Rounded.CheckCircle,
                    )
                }
            }
        }
    }
    Spacer(Modifier.height(14.dp))
    Text(
        tr("После отключения у подписки меняется ключ: остальные устройства получат новый при следующем обновлении, обычно в течение часа"),
        style = MaterialTheme.typography.bodySmall,
    )
}

@Composable
private fun DeviceRow(d: Device, mine: Boolean, actions: @Composable ColumnScope.() -> Unit) {
    val c = Ghost.colors
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(Radius.tile)).background(c.surface2)
            .border(1.dp, if (mine) c.accent.copy(alpha = 0.4f) else c.hairline, RoundedCornerShape(Radius.tile)).padding(14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(38.dp).clip(RoundedCornerShape(12.dp)).background(c.surface3), contentAlignment = Alignment.Center) {
                Icon(Icons.Rounded.Devices, null, tint = if (d.active) c.accent else c.ink3, modifier = Modifier.size(20.dp))
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(d.model.ifBlank { tr("Устройство") }, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                    if (mine) {
                        Spacer(Modifier.width(8.dp))
                        Chip(tr("это устройство"), Tone.ACCENT)
                    }
                }
                Text(
                    listOfNotNull(d.os?.takeIf { it.isNotBlank() }, d.ago?.takeIf { it.isNotBlank() }, if (!d.active) tr("давно не заходило") else null).joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
        }
        actions()
    }
}

// ============================================================================ balance

@Composable
private fun TopupSheet(controller: GhostlyController, onClose: () -> Unit) {
    val st = cabState(controller) ?: return
    val scope = rememberCoroutineScope()
    val busy by controller.cabinet.busy.collectAsState()
    var text by remember { mutableStateOf("300") }
    var outcome by remember { mutableStateOf<Outcome?>(null) }
    val amount = text.filter { it.isDigit() }.take(6).toIntOrNull() ?: 0
    val ok = amount in st.topup.min..st.topup.max
    SheetHead(tr("Пополнить баланс"), tr("С баланса можно продлевать подписку, докупать трафик и устройства"), onClose)
    Field(
        text, { text = it.filter { ch -> ch.isDigit() }.take(6); outcome = null }, tr("Сумма, ₽"), keyboard = KeyboardType.Number,
        error = if (text.isNotEmpty() && !ok) tr("От {0} до {1}", rub(st.topup.min), rub(st.topup.max)) else null,
        imeAction = ImeAction.Done,
    )
    Spacer(Modifier.height(10.dp))
    ChoiceRow(listOf(100 to "100 ₽", 300 to "300 ₽", 500 to "500 ₽", 1000 to "1000 ₽"), amount, { text = it.toString(); outcome = null })
    Spacer(Modifier.height(16.dp))
    AccentButton(
        if (busy == "topup") tr("Создаю платёж…") else tr("Пополнить на {0}", rub(amount)),
        { scope.launch { val o = controller.cabinet.topup(amount); outcome = o; if (o.ok) onClose() } },
        Modifier.fillMaxWidth(), Icons.Rounded.CreditCard, enabled = ok && busy == null,
    )
    Spacer(Modifier.height(8.dp))
    Text(tr("Карта или СБП, страница оплаты откроется в браузере"), style = MaterialTheme.typography.bodySmall)
    OutcomeNote(outcome, null)
}

@Composable
private fun HistorySheet(controller: GhostlyController, onClose: () -> Unit) {
    val st = cabState(controller) ?: return
    SheetHead(tr("Операции"), tr("Пополнения, списания и бонусы"), onClose)
    if (st.history.isEmpty()) Note(tr("Операций пока не было"))
    st.history.forEach { HistoryRow(it) }
    if (st.orders.isNotEmpty()) {
        Spacer(Modifier.height(16.dp))
        Text(tr("Заказы"), style = MaterialTheme.typography.titleSmall)
        Spacer(Modifier.height(6.dp))
        st.orders.forEach { o ->
            Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(o.title ?: o.kind, style = MaterialTheme.typography.bodyMedium.copy(color = Ghost.colors.ink), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(agoText(o.created), style = MaterialTheme.typography.bodySmall)
                }
                Text(rub(o.amount), style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.width(10.dp))
                Chip(
                    when (o.status) {
                        "issued" -> tr("готово")
                        "cancelled" -> tr("отменён")
                        "paid", "provisioning" -> tr("выдаётся")
                        else -> tr("ждёт оплаты")
                    },
                    when (o.status) {
                        "issued" -> Tone.OK
                        "cancelled" -> Tone.NEUTRAL
                        else -> Tone.WARN
                    },
                )
            }
        }
    }
}

// ============================================================================ security

@Composable
private fun SessionsSheet(controller: GhostlyController, onClose: () -> Unit) {
    val st = cabState(controller) ?: return
    val scope = rememberCoroutineScope()
    val busy by controller.cabinet.busy.collectAsState()
    SheetHead(tr("Сессии"), tr("Где сейчас открыт ваш аккаунт"), onClose)
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        st.sessions.forEach { s ->
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(Radius.tile)).background(Ghost.colors.surface2).padding(14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(uaName(s.ua), style = MaterialTheme.typography.titleSmall)
                        if (s.current) {
                            Spacer(Modifier.width(8.dp))
                            Chip(tr("эта"), Tone.ACCENT)
                        }
                    }
                    Text(listOf(s.ip, agoText(s.last)).filter { it.isNotBlank() }.joinToString(" · "), style = MaterialTheme.typography.bodySmall)
                }
                if (!s.current && st.fullAccess) {
                    SoftButton(if (busy == "session:${s.id}") "…" else tr("Завершить"), { scope.launch { controller.cabinet.revokeSession(s.id) } })
                }
            }
        }
    }
    if (st.fullAccess && st.sessions.size > 1) {
        Spacer(Modifier.height(14.dp))
        SoftButton(tr("Завершить все, кроме этой"), { scope.launch { controller.cabinet.revokeSession("all") } }, Modifier.fillMaxWidth(), tint = Ghost.colors.bad)
    }
    if (!st.fullAccess) {
        Spacer(Modifier.height(12.dp))
        Note(tr("Завершать сессии можно после обычного входа: через Telegram или по логину"))
    }
}

private fun uaName(ua: String): String {
    if (ua.contains("GhostlyVPN/")) {
        val os = ua.substringAfter('(', "").substringBefore(';').substringBefore(')').trim()
        return "Ghostly" + if (os.isNotBlank()) " · $os" else ""
    }
    val browser = when {
        "YaBrowser" in ua -> tr("Яндекс Браузер")
        "Edg/" in ua -> "Edge"
        "OPR/" in ua || "Opera" in ua -> "Opera"
        "Firefox/" in ua -> "Firefox"
        "Chrome/" in ua -> "Chrome"
        "Safari/" in ua -> "Safari"
        else -> tr("Браузер")
    }
    val os = when {
        "iPhone" in ua || "iPad" in ua -> "iOS"
        "Android" in ua -> "Android"
        "Windows" in ua -> "Windows"
        "Mac OS X" in ua -> "macOS"
        "Linux" in ua -> "Linux"
        else -> null
    }
    return browser + (os?.let { " · $it" } ?: "")
}

@Composable
private fun PasswordSheet(controller: GhostlyController, onClose: () -> Unit) {
    val scope = rememberCoroutineScope()
    val busy by controller.cabinet.busy.collectAsState()
    var old by remember { mutableStateOf("") }
    var new by remember { mutableStateOf("") }
    var again by remember { mutableStateOf("") }
    var outcome by remember { mutableStateOf<Outcome?>(null) }
    val mismatch = again.isNotEmpty() && again != new
    SheetHead(tr("Сменить пароль"), tr("Остальные сессии завершатся"), onClose)
    Field(old, { old = it; outcome = null }, tr("Текущий пароль"), password = true)
    Spacer(Modifier.height(12.dp))
    Field(new, { new = it; outcome = null }, tr("Новый пароль"), password = true, hint = tr("Не короче 8 символов"))
    Spacer(Modifier.height(12.dp))
    Field(again, { again = it }, tr("Ещё раз"), password = true, error = if (mismatch) tr("Пароли не совпадают") else null, imeAction = ImeAction.Done)
    Spacer(Modifier.height(16.dp))
    AccentButton(
        if (busy == "password") tr("Сохраняю…") else tr("Сменить пароль"),
        { scope.launch { val o = controller.cabinet.changePassword(old, new); outcome = o; if (o.ok) onClose() } },
        Modifier.fillMaxWidth(), enabled = old.isNotEmpty() && new.length >= 8 && again == new && busy == null,
    )
    OutcomeNote(outcome, null)
}

@Composable
private fun RegisterSheet(controller: GhostlyController, onClose: () -> Unit) {
    val scope = rememberCoroutineScope()
    val busy by controller.cabinet.busy.collectAsState()
    var login by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    SheetHead(tr("Логин и пароль"), tr("Подписки и баланс останутся здесь, просто появится вход с любого устройства"), onClose)
    Field(login, { login = it.take(32); error = null }, tr("Логин"), placeholder = tr("латиница, 3–32 символа"))
    Spacer(Modifier.height(12.dp))
    Field(password, { password = it; error = null }, tr("Пароль"), password = true, hint = tr("Не короче 8 символов"), error = error, imeAction = ImeAction.Done)
    Spacer(Modifier.height(16.dp))
    AccentButton(
        if (busy == "login") tr("Сохраняю…") else tr("Сохранить"),
        { scope.launch { error = controller.cabinet.register(login, password); if (error == null) onClose() } },
        Modifier.fillMaxWidth(), enabled = login.trim().length >= 3 && password.length >= 8 && busy == null,
    )
    Spacer(Modifier.height(8.dp))
    Text(tr("Сохраняя, вы принимаете оферту и условия сервиса и соглашаетесь на обработку персональных данных"), style = MaterialTheme.typography.bodySmall)
}

@Composable
private fun ClaimSheet(controller: GhostlyController, onClose: () -> Unit) {
    val scope = rememberCoroutineScope()
    val busy by controller.cabinet.busy.collectAsState()
    var code by remember { mutableStateOf("") }
    var outcome by remember { mutableStateOf<Outcome?>(null) }
    SheetHead(tr("Подписка по коду"), tr("Владелец подписки создаёт код передачи в своём кабинете. Код действует сутки"), onClose)
    Field(code, { code = it.uppercase().filter { ch -> ch.isLetterOrDigit() }.take(16); outcome = null }, tr("Код передачи"), imeAction = ImeAction.Done)
    Spacer(Modifier.height(16.dp))
    AccentButton(
        if (busy == "claim") tr("Проверяю…") else tr("Получить подписку"),
        { scope.launch { val o = controller.cabinet.claimTransfer(code); outcome = o; if (o.ok) onClose() } },
        Modifier.fillMaxWidth(), Icons.Rounded.SwapHoriz, enabled = code.length >= 6 && busy == null,
    )
    OutcomeNote(outcome, null)
}

@Composable
private fun TransferSheet(controller: GhostlyController, subId: String, onClose: () -> Unit) {
    val st = cabState(controller) ?: return
    val sub = st.subs.firstOrNull { it.id == subId } ?: return
    val scope = rememberCoroutineScope()
    var link by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var working by remember { mutableStateOf(false) }
    SheetHead(
        tr("Передать подписку"),
        if (sub.bot) tr("Отправьте ссылку новому владельцу: он откроет её в Telegram, и подписка перейдёт к нему")
        else tr("Новый владелец вводит код в своём кабинете. Код действует сутки"),
        onClose,
    )
    Note(tr("После передачи у подписки сменится ключ: на ваших устройствах она перестанет работать"), Tone.WARN, icon = Icons.Rounded.Info)
    Spacer(Modifier.height(14.dp))
    val l = link
    if (l == null) {
        AccentButton(if (working) tr("Создаю…") else tr("Создать ссылку передачи"), {
            working = true
            scope.launch {
                val (got, e) = controller.cabinet.transfer(sub)
                link = got
                error = e
                working = false
            }
        }, Modifier.fillMaxWidth(), Icons.Rounded.SwapHoriz, enabled = !working)
        error?.let { Spacer(Modifier.height(10.dp)); Note(it, Tone.BAD) }
    } else {
        val code = l.substringAfter("xfer=", "").substringBefore('#').ifEmpty { null }
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(Radius.tile)).background(Ghost.colors.surface2).padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(code ?: l, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            IconBubble(Icons.Rounded.ContentCopy, { controller.platform.copyToClipboard(code ?: l); controller.toast(tr("Скопировано")) }, size = 38.dp)
        }
        Spacer(Modifier.height(12.dp))
        SoftButton(tr("Поделиться ссылкой"), { controller.platform.share(l) }, Modifier.fillMaxWidth(), if (sub.bot) Icons.AutoMirrored.Rounded.Send else Icons.Rounded.Share)
    }
}
