package app.ghostly.ui.screens.cabinet

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Login
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material.icons.rounded.CalendarMonth
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.Key
import androidx.compose.material.icons.rounded.Redeem
import androidx.compose.material.icons.rounded.VpnKey
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
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.ghostly.core.GhostlyController
import app.ghostly.core.account.Cabinet
import app.ghostly.core.account.CabinetUi
import app.ghostly.i18n.tr
import app.ghostly.ui.components.AccentButton
import app.ghostly.ui.components.Field
import app.ghostly.ui.components.GhostMark
import app.ghostly.ui.components.Note
import app.ghostly.ui.components.SoftButton
import app.ghostly.ui.components.Spinner
import app.ghostly.ui.components.Tile
import app.ghostly.ui.components.Tone
import app.ghostly.ui.components.appear
import app.ghostly.ui.components.hoverSound
import app.ghostly.ui.theme.Ghost
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Not signed in. With the app's own Ghostly subscription the app usually signs in by itself; when it can't
 * (another device was first, two-step verification), the person signs in once: through the Telegram bot
 * (one tap there) or with a login and password. Without a subscription: a trial or a plan, bought right
 * here (the account is made on the way).
 */
@Composable
internal fun SignInBody(controller: GhostlyController, u: CabinetUi.SignedOut, open: (CabSheet) -> Unit) {
    val c = Ghost.colors
    val cab = controller.cabinet
    val tg by cab.telegram.collectAsState()
    val ticket by cab.ticket.collectAsState()
    val busy by cab.busy.collectAsState()
    val scope = rememberCoroutineScope()
    val hasSub = controller.ghostlyProfile() != null
    var form by remember { mutableStateOf(false) }
    var register by remember { mutableStateOf(false) }

    // The night's own ghost watches from the header: no second one here.
    Column(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            if (hasSub) tr("Ваш аккаунт Ghostly") else tr("Ghostly без браузера"),
            style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            if (hasSub) tr("Продление, баланс, устройства и трафик прямо в приложении")
            else tr("Войдите или оформите подписку: оплата картой или СБП, VPN подключится сам"),
            style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = 12.dp),
        )
    }
    Cabinet.reasonText(u.why, u.bot)?.let {
        Spacer(Modifier.height(16.dp))
        Note(it, Tone.ACCENT, icon = Icons.Rounded.Info)
    }
    Spacer(Modifier.height(18.dp))

    // Waiting for «Войти» in the bot.
    tg?.let { w ->
        Tile(Modifier.fillMaxWidth()) {
            var left by remember(w.startedAt) { mutableLongStateOf(w.login.ttl.toLong()) }
            LaunchedEffect(w.startedAt) {
                while (left > 0) { delay(1000); left = (w.login.ttl - (Cabinet.now() - w.startedAt) / 1000).coerceAtLeast(0) }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Spinner(c.accent, Modifier.size(22.dp))
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(tr("Подтвердите вход в Telegram"), style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(2.dp))
                    Text(tr("Бот @{0} пришлёт кнопку «Войти». Ссылка действует ещё {1}:{2}", w.login.bot, left / 60, (left % 60).toString().padStart(2, '0')), style = MaterialTheme.typography.bodySmall)
                }
            }
            Spacer(Modifier.height(14.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                SoftButton(tr("Отмена"), { cab.cancelTelegram() }, Modifier.weight(1f))
                AccentButton(tr("Открыть Telegram"), { cab.openTelegram() }, Modifier.weight(1f), Icons.AutoMirrored.Rounded.Send)
            }
        }
        return
    }

    // Password accepted: the two-step code is next.
    ticket?.let {
        var code by remember { mutableStateOf("") }
        var error by remember { mutableStateOf<String?>(null) }
        Tile(Modifier.fillMaxWidth()) {
            Text(tr("Код двухэтапной защиты"), style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(4.dp))
            Text(tr("6 цифр из приложения-аутентификатора или резервный код"), style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.height(14.dp))
            val submit = { scope.launch { error = cab.totp(code) } }
            Field(code, { code = it.take(12); error = null }, tr("Код"), keyboard = KeyboardType.Number, error = error, imeAction = ImeAction.Done, onDone = { submit() })
            Spacer(Modifier.height(14.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                SoftButton(tr("Назад"), { cab.cancelTicket() }, Modifier.weight(1f))
                AccentButton(if (busy == "login") tr("Проверяю…") else tr("Войти"), { submit() }, Modifier.weight(1f), enabled = code.length >= 6 && busy == null)
            }
        }
        return
    }

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        AccentButton(tr("Войти через Telegram"), { controller.haptic(); cab.telegram() }, Modifier.fillMaxWidth(), Icons.AutoMirrored.Rounded.Send)
        SoftButton(tr("Войти по логину и паролю"), { form = !form; register = false }, Modifier.fillMaxWidth(), Icons.Rounded.Key)
        if (hasSub && (u.why != null || !cab.api.autoSignIn)) {
            SoftButton(
                if (busy == "auto") tr("Проверяю подписку…") else tr("Войти с подпиской этого устройства"),
                { controller.haptic(); cab.signInWithSubscription() }, Modifier.fillMaxWidth(), Icons.Rounded.VpnKey,
            )
        }
    }

    AnimatedVisibility(form, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
        PasswordForm(controller, register, onRegister = { register = it })
    }

    // No subscription yet: a trial or a plan right here.
    val anon = u.anon
    if (!hasSub && anon != null) {
        Spacer(Modifier.height(24.dp))
        Text(tr("Ещё нет подписки?"), style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(start = 4.dp))
        Spacer(Modifier.height(10.dp))
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (anon.trial) {
                AccentButton(
                    if (busy == "trial") tr("Включаю…") else tr("24 часа бесплатно"),
                    { scope.launch { cab.trial() } }, Modifier.fillMaxWidth(), Icons.Rounded.Redeem, enabled = busy == null,
                )
            }
            SoftButton(tr("Выбрать тариф"), { open(CabSheet.Renew(null)) }, Modifier.fillMaxWidth(), Icons.Rounded.CalendarMonth, tint = c.accent)
        }
        Spacer(Modifier.height(10.dp))
        Text(
            tr("Аккаунт создастся сам при покупке. Потом можно задать пароль или привязать Telegram"),
            style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(horizontal = 4.dp),
        )
    }
}

@Composable
private fun PasswordForm(controller: GhostlyController, register: Boolean, onRegister: (Boolean) -> Unit) {
    val cab = controller.cabinet
    val busy by cab.busy.collectAsState()
    val scope = rememberCoroutineScope()
    var login by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    val ok = login.trim().length >= 3 && password.length >= (if (register) 8 else 1)
    val submit = {
        if (ok && busy == null) scope.launch {
            error = if (register) cab.register(login, password) else cab.login(login, password)
        }
    }
    Tile(Modifier.fillMaxWidth().padding(top = 12.dp)) {
        Text(if (register) tr("Новый аккаунт") else tr("Вход по логину"), style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(14.dp))
        Field(login, { login = it.take(32); error = null }, tr("Логин"), placeholder = if (register) tr("латиница, 3–32 символа") else null)
        Spacer(Modifier.height(12.dp))
        Field(
            password, { password = it.take(200); error = null }, tr("Пароль"), password = true,
            hint = if (register) tr("Не короче 8 символов") else null, error = error,
            imeAction = ImeAction.Done, onDone = { submit() },
        )
        Spacer(Modifier.height(16.dp))
        AccentButton(
            when {
                busy == "login" -> tr("Секунду…")
                register -> tr("Создать аккаунт")
                else -> tr("Войти")
            },
            { submit() }, Modifier.fillMaxWidth(), Icons.AutoMirrored.Rounded.Login, enabled = ok && busy == null,
        )
        Spacer(Modifier.height(10.dp))
        Text(
            if (register) tr("Уже есть аккаунт? Войти") else tr("Нет аккаунта? Зарегистрироваться"),
            style = MaterialTheme.typography.labelLarge, color = Ghost.colors.accent,
            modifier = Modifier.align(Alignment.CenterHorizontally).clip(RoundedCornerShape(8.dp)).hoverSound()
                .clickable { onRegister(!register); error = null }.padding(8.dp),
        )
        if (register) {
            Text(
                tr("Создавая аккаунт, вы принимаете оферту и условия сервиса и соглашаетесь на обработку персональных данных"),
                style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(4.dp))
            Text(
                tr("Открыть документы"), style = MaterialTheme.typography.labelMedium, color = Ghost.colors.accent,
                modifier = Modifier.align(Alignment.CenterHorizontally).clip(RoundedCornerShape(8.dp)).hoverSound()
                    .clickable { controller.platform.openUrl("https://${app.ghostly.core.sub.GhostlyDomains.MAIN}/docs/oferta") }.padding(6.dp),
            )
        }
    }
}
