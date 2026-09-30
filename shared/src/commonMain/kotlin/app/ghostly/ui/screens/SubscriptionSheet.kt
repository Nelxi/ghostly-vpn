package app.ghostly.ui.screens

import app.ghostly.ui.components.hoverSound
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Campaign
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Payments
import androidx.compose.material.icons.rounded.Public
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.SupportAgent
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
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.ghostly.core.GhostlyController
import app.ghostly.core.model.Profile
import app.ghostly.core.model.ProviderNotice
import app.ghostly.ui.Format
import app.ghostly.ui.components.AccentButton
import app.ghostly.ui.components.FlagText
import app.ghostly.ui.components.GlowBar
import app.ghostly.ui.components.SoftButton
import app.ghostly.ui.components.Spinner
import app.ghostly.ui.theme.Ghost
import kotlinx.coroutines.delay

/** How any screen opens the subscription page or the "add" sheet; provided by the app shell. */
class SubscriptionNav(val open: (profileId: String) -> Unit, val add: () -> Unit)

val LocalSubscriptionNav = staticCompositionLocalOf { SubscriptionNav({}, {}) }

/**
 * Where "Продлить" leads: the provider's renew link (`sub-expire-button-link`), else its support
 * link, which for most providers is the bot that sells the subscription.
 */
fun Profile.renewLink(): String? = renewUrl ?: supportUrl

/** The provider's info block (Happ `sub-info-*`), coloured as asked, with its button. */
@Composable
fun ProviderNoticeBlock(n: ProviderNotice, controller: GhostlyController, modifier: Modifier = Modifier) {
    val c = Ghost.colors
    val tone = when (n.color) { "red" -> c.bad; "green" -> c.ok; "blue" -> Color(0xFF6EA8FF); else -> c.accent }
    Column(
        modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(tone.copy(alpha = 0.10f))
            .border(1.dp, tone.copy(alpha = 0.28f), RoundedCornerShape(20.dp)).padding(14.dp),
    ) {
        FlagText(n.text, style = MaterialTheme.typography.bodyMedium)
        n.buttonUrl?.let { url ->
            Spacer(Modifier.height(10.dp))
            Text(
                n.buttonText ?: "Открыть", style = MaterialTheme.typography.labelLarge, color = tone,
                modifier = Modifier.clip(RoundedCornerShape(12.dp)).background(tone.copy(alpha = 0.14f))
                    .hoverSound().clickable { controller.haptic(); controller.platform.openUrl(url) }.padding(horizontal = 14.dp, vertical = 8.dp),
            )
        }
    }
}

/** Profiles that count as subscriptions (a URL or provider info), in list order. */
fun List<Profile>.subscriptions() = filter { it.url != null || it.info != null }

/**
 * The subscription page: everything the provider's headers said (title, traffic, expiry, the
 * full `announce` note, update interval, cabinet and support links), its actions, and every other
 * subscription with a way to add one more.
 */
@Composable
fun SubscriptionPage(controller: GhostlyController, profileId: String, onAdd: () -> Unit, onClose: () -> Unit) {
    val c = Ghost.colors
    val profiles by controller.profiles.collectAsState()
    val refreshing by controller.refreshing.collectAsState()
    val selected by controller.selectedServerId.collectAsState()
    var shownId by remember(profileId) { mutableStateOf(profileId) }
    val profile = profiles.firstOrNull { it.id == shownId } ?: profiles.firstOrNull { it.id == profileId }
    if (profile == null) {
        LaunchedEffect(Unit) { onClose() }
        return
    }
    var now by remember { mutableLongStateOf(GhostlyController.now()) }
    LaunchedEffect(Unit) { while (true) { delay(30_000); now = GhostlyController.now() } }
    val active = profile.servers.any { it.id == selected }
    val info = profile.info
    val offset = controller.platform.utcOffsetMinutes()

    Column(
        Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 28.dp),
    ) {
        // Title
        Row(verticalAlignment = Alignment.CenterVertically) {
            FlagText(profile.name, style = MaterialTheme.typography.headlineSmall, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
            if (active) {
                Spacer(Modifier.width(10.dp))
                Text(
                    "активна", style = MaterialTheme.typography.labelSmall, color = c.ok,
                    modifier = Modifier.clip(RoundedCornerShape(8.dp)).background(c.ok.copy(alpha = 0.14f)).padding(horizontal = 8.dp, vertical = 3.dp),
                )
            }
        }
        Text(
            listOfNotNull(
                "${profile.servers.size} ${Format.plural(profile.servers.size.toLong(), "сервер", "сервера", "серверов")}",
                profile.url?.let { Format.updatedAgo(profile.updatedAt, now) },
            ).joinToString(" · "),
            style = MaterialTheme.typography.bodySmall, color = c.ink3,
        )

        // Expiry and traffic
        if (info != null) {
            Spacer(Modifier.height(16.dp))
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(Color.White.copy(alpha = 0.05f)).padding(14.dp)) {
                Text(
                    if (info.unlimitedTime) "Бессрочно" else Format.expiryPhrase(info.expire, now),
                    style = MaterialTheme.typography.titleMedium,
                    color = when {
                        info.unlimitedTime -> c.ink
                        info.expire * 1000 <= now -> c.bad
                        info.expire * 1000 - now < 3 * 86_400_000L -> c.warn
                        else -> c.ink
                    },
                )
                if (!info.unlimitedTime) Text("до ${Format.date(info.expire, offset)}", style = MaterialTheme.typography.bodySmall, color = c.ink3)
                Spacer(Modifier.height(12.dp))
                if (info.pools.isNotEmpty()) {
                    info.pools.forEachIndexed { i, p ->
                        if (i > 0) Spacer(Modifier.height(10.dp))
                        PoolBar(p)
                    }
                    if (info.cycleEnd > 0) {
                        Spacer(Modifier.height(8.dp))
                        Text("Трафик обновится через ${Format.remaining(info.cycleEnd, now)}", style = MaterialTheme.typography.bodySmall)
                    }
                } else {
                    Row {
                        Text("Трафик", style = MaterialTheme.typography.labelMedium, modifier = Modifier.weight(1f))
                        Text(
                            if (info.unlimitedTraffic) "${Format.bytes(info.used)} · без лимита" else "${Format.bytes(info.used)} из ${Format.bytes(info.total)}",
                            style = MaterialTheme.typography.labelMedium, color = c.ink2,
                        )
                    }
                    if (!info.unlimitedTraffic) {
                        Spacer(Modifier.height(8.dp))
                        val frac = info.used.toFloat() / info.total.toFloat()
                        GlowBar(frac, if (frac > 0.9f) c.bad else if (frac > 0.7f) c.warn else c.accent)
                    }
                }
            }
        }

        // The provider's note, in full.
        profile.announce?.let { note ->
            Spacer(Modifier.height(12.dp))
            Column(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(c.accent.copy(alpha = 0.09f))
                    .border(1.dp, c.accent.copy(alpha = 0.22f), RoundedCornerShape(20.dp)).padding(14.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.Campaign, null, tint = c.accent, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Сообщение от провайдера", style = MaterialTheme.typography.labelMedium, color = c.accent)
                }
                Spacer(Modifier.height(8.dp))
                FlagText(note, style = MaterialTheme.typography.bodyMedium)
            }
        }

        profile.notice?.let { n ->
            Spacer(Modifier.height(12.dp))
            ProviderNoticeBlock(n, controller)
        }

        // Facts from the headers (links are the buttons below, not repeated here)
        val traffic = info != null && info.pools.isEmpty() && info.used > 0
        if (traffic || profile.url != null) {
            Spacer(Modifier.height(12.dp))
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(Color.White.copy(alpha = 0.05f)).padding(horizontal = 14.dp, vertical = 6.dp)) {
                if (traffic) {
                    Fact("Скачано", Format.bytes(info!!.download))
                    Fact("Отдано", Format.bytes(info.upload))
                }
                if (profile.url != null) Fact("Автообновление", everyHours(profile.updateIntervalHours))
            }
        }

        // Actions
        Spacer(Modifier.height(14.dp))
        val busy = profile.id in refreshing
        val actions = buildList<Triple<ImageVector, String, () -> Unit>> {
            if (profile.url != null) add(Triple(Icons.Rounded.Refresh, if (busy) "Обновляю…" else "Обновить") { controller.haptic(); controller.refresh(profile.id, manual = true) })
            profile.webPageUrl?.let { url -> add(Triple(Icons.Rounded.Public, "Подписка") { controller.platform.openUrl(url) }) }
            profile.renewLink()?.let { url -> add(Triple(Icons.Rounded.Payments, "Продлить") { controller.platform.openUrl(url) }) }
            // Without its own renew link "Продлить" already opens the support link: no second button for it.
            profile.supportUrl?.takeIf { profile.renewUrl != null }?.let { url -> add(Triple(Icons.Rounded.SupportAgent, "Поддержка") { controller.platform.openUrl(url) }) }
            profile.url?.let { url -> add(Triple(Icons.Rounded.ContentCopy, "Ссылка") { controller.platform.copyToClipboard(url) }) }
        }
        actions.chunked(2).forEach { row ->
            Row(Modifier.fillMaxWidth().padding(bottom = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { (icon, label, onClick) ->
                    Row(
                        Modifier.weight(1f).clip(RoundedCornerShape(16.dp)).background(Color.White.copy(alpha = 0.06f))
                            .hoverSound().clickable(onClick = onClick).padding(vertical = 12.dp),
                        horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (icon == Icons.Rounded.Refresh && busy) Spinner(c.accent, Modifier.size(16.dp))
                        else Icon(icon, null, tint = c.accent, modifier = Modifier.size(17.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(label, style = MaterialTheme.typography.labelLarge, maxLines = 1)
                    }
                }
                if (row.size == 1) Spacer(Modifier.weight(1f))
            }
        }
        if (!active && profile.servers.isNotEmpty()) {
            AccentButton("Сделать активной", { controller.haptic(); controller.switchProfile(profile.id) }, Modifier.fillMaxWidth().padding(top = 4.dp), Icons.Rounded.CheckCircle)
        }

        // Every subscription + add one more
        val subs = profiles.subscriptions()
        Spacer(Modifier.height(22.dp))
        Text("МОИ ПОДПИСКИ", style = MaterialTheme.typography.labelSmall, color = c.ink3, modifier = Modifier.padding(start = 4.dp, bottom = 6.dp))
        subs.forEach { p ->
            val isActive = p.servers.any { it.id == selected }
            Row(
                Modifier.fillMaxWidth().padding(vertical = 2.dp).clip(RoundedCornerShape(16.dp))
                    .background(if (p.id == profile.id) c.accent.copy(alpha = 0.10f) else Color.Transparent)
                    .hoverSound().clickable { shownId = p.id }.padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.size(8.dp).clip(CircleShape).background(if (isActive) c.ok else c.ink3.copy(alpha = 0.4f)))
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    FlagText(p.name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    val sub = p.info?.let { i -> if (i.unlimitedTime) "бессрочно" else Format.expiryPhrase(i.expire, now).lowercase() }
                        ?: "${p.servers.size} ${Format.plural(p.servers.size.toLong(), "сервер", "сервера", "серверов")}"
                    Text(sub, style = MaterialTheme.typography.bodySmall, color = c.ink3, maxLines = 1)
                }
                if (isActive) Text("активна", style = MaterialTheme.typography.labelSmall, color = c.ok)
            }
        }
        Spacer(Modifier.height(8.dp))
        SoftButton("Добавить подписку", onAdd, Modifier.fillMaxWidth(), Icons.Rounded.Add, tint = c.accent)

        // Delete: a second tap confirms.
        var confirm by remember(profile.id) { mutableStateOf(false) }
        Spacer(Modifier.height(18.dp))
        Row(
            Modifier.align(Alignment.CenterHorizontally).clip(RoundedCornerShape(12.dp))
                .hoverSound().clickable {
                    if (confirm) { controller.deleteProfile(profile.id); if (profile.id == profileId) onClose() else shownId = profileId }
                    else confirm = true
                }.padding(horizontal = 14.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Rounded.Delete, null, tint = c.bad, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
            Text(if (confirm) "Нажмите ещё раз, чтобы удалить" else "Удалить подписку", style = MaterialTheme.typography.labelMedium, color = c.bad)
        }
    }
}

private fun everyHours(h: Int): String =
    if (h <= 1) "каждый час" else "каждые $h ${Format.plural(h.toLong(), "час", "часа", "часов")}"

@Composable
private fun Fact(label: String, value: String) {
    val c = Ghost.colors
    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = c.ink3, modifier = Modifier.padding(end = 12.dp))
        Text(value, style = MaterialTheme.typography.bodyMedium, color = c.ink, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f), textAlign = androidx.compose.ui.text.style.TextAlign.End)
    }
}
