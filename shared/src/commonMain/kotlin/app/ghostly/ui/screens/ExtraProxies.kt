package app.ghostly.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Public
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import app.ghostly.ui.components.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.isPrimaryPressed
import androidx.compose.ui.input.pointer.isCtrlPressed
import androidx.compose.ui.input.pointer.isMetaPressed
import androidx.compose.ui.input.pointer.isShiftPressed
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.ghostly.core.GhostlyController
import app.ghostly.core.model.CoreType
import app.ghostly.core.model.ExtraProxy
import app.ghostly.core.model.ExtraProxyType
import app.ghostly.core.model.Server
import app.ghostly.core.model.address
import app.ghostly.ui.components.FlagIcon
import app.ghostly.ui.components.GhostSwitch
import app.ghostly.ui.components.GlassCard
import app.ghostly.ui.components.IconBubble
import app.ghostly.ui.components.SectionTitle
import app.ghostly.ui.components.Segmented
import app.ghostly.ui.components.ToggleRow
import app.ghostly.ui.components.hoverSound
import app.ghostly.ui.theme.Ghost
import app.ghostly.ui.title
import app.ghostly.ui.countryFlag
import app.ghostly.ui.countryName
import app.ghostly.ui.matchesQuery
import app.ghostly.ui.components.SoftButton
import androidx.compose.material3.Checkbox
import app.ghostly.i18n.tr

/**
 * «Дополнительные прокси» on the local-proxy page: any number of SOCKS5/HTTP proxies, each with its own
 * port, login and server. They run next to the main connection; auto-switching only moves the main one.
 */
@Composable
@OptIn(ExperimentalComposeUiApi::class)
internal fun ExtraProxiesSection(controller: GhostlyController, host: String) {
    val s by controller.settings.collectAsState()
    val profiles by controller.profiles.collectAsState()
    val c = Ghost.colors
    val servers = remember(profiles) { controller.extraProxyServers() }
    var selection by remember { mutableStateOf(ProxySelection()) }
    val order = s.extraProxies.map { it.id }
    LaunchedEffect(order) { selection = selection.retain(order) }

    SectionTitle(tr("Дополнительные прокси"))
    Text(
        tr("Сколько угодно своих прокси: у каждого свой порт и свой сервер. Работают одновременно с основным подключением — ") +
            tr("например, одни программы в Proxifier идут через Германию, другие через Нидерланды. Автосмена сервера их не трогает."),
        style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(6.dp, 0.dp, 6.dp, 10.dp),
    )
    if (s.core != CoreType.XRAY) Text(
        tr("Сейчас выбрано ядро Mihomo — дополнительные прокси работают на ядре Xray и поднимутся, когда вернёшься на него."),
        style = MaterialTheme.typography.bodySmall, color = c.warn, modifier = Modifier.padding(6.dp, 0.dp, 6.dp, 10.dp),
    )
    // A port may be used once: by the main proxy or by one additional proxy.
    val mainPorts = setOf(s.socksPort, s.httpPort)
    if (order.isNotEmpty()) {
        Text(tr("Выбрано: {0}", selection.ids.size), style = MaterialTheme.typography.labelLarge)
        Row(Modifier.fillMaxWidth()) {
            SoftButton(tr("Выбрать все"), { selection = ProxySelection(order.toSet(), order.firstOrNull()) }, Modifier.weight(1f))
            SoftButton(tr("Снять выделение"), { selection = ProxySelection() }, Modifier.weight(1f))
        }
        if (controller.platform.isDesktop) Text(tr("Ctrl — выбрать несколько, Shift — диапазон"), style = MaterialTheme.typography.bodySmall, color = c.ink3)
        if (selection.ids.isNotEmpty()) {
            var countriesOpen by remember { mutableStateOf(false) }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f)) {
                    SoftButton(tr("Сменить страну"), { countriesOpen = true }, Modifier.fillMaxWidth())
                    DropdownMenu(countriesOpen, { countriesOpen = false }, modifier = Modifier.heightIn(max = 360.dp)) {
                        servers.mapNotNull { it.countryFlag() }.distinct().forEach { flag ->
                            DropdownMenuItem(text = { Text(countryName(flag)) }, leadingIcon = { FlagIcon(flag, 14.dp) }, onClick = {
                                countriesOpen = false
                                controller.bindExtraProxyCountry(selection.ids, flag)
                            })
                        }
                    }
                }
                Spacer(Modifier.width(8.dp))
                ServerPicker(null, tr("Сменить сервер"), servers, Modifier.weight(1f)) { controller.bindExtraProxies(selection.ids, it.id) }
            }
            Row {
                SoftButton(tr("Включить"), { controller.enableExtraProxies(selection.ids, true) })
                SoftButton(tr("Выключить"), { controller.enableExtraProxies(selection.ids, false) })
            }
            SoftButton(tr("Скопировать адреса"), {
                controller.platform.copyToClipboard(s.extraProxies.filter { it.id in selection.ids }.joinToString("\n") { it.address(host) })
                controller.toast(tr("Скопировано"))
            }, Modifier.fillMaxWidth())
        }
        Spacer(Modifier.height(10.dp))
    }
    s.extraProxies.forEach { x ->
        key(x.id) {
            val clash = x.enabled && (x.port in mainPorts || s.extraProxies.any { it.enabled && it.id != x.id && it.port == x.port })
            val m = Modifier.pointerInput(order) {
                awaitPointerEventScope {
                    var selecting = false
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        val keys = event.keyboardModifiers
                        if (event.type == PointerEventType.Press && event.buttons.isPrimaryPressed && (keys.isCtrlPressed || keys.isMetaPressed || keys.isShiftPressed)) {
                            selection = selection.click(x.id, order, keys.isCtrlPressed || keys.isMetaPressed, keys.isShiftPressed)
                            selecting = true
                        }
                        if (selecting) {
                            event.changes.forEach { it.consume() }
                            if (event.type == PointerEventType.Release || event.changes.none { it.pressed }) selecting = false
                        }
                    }
                }
            }
            ExtraProxyCard(x, controller, servers, host, clash, x.id in selection.ids, {
                selection = selection.click(x.id, order, toggle = true, range = false)
            }, m)
            Spacer(Modifier.height(10.dp))
        }
    }
    GlassCard(Modifier.fillMaxWidth(), padding = 14.dp, onClick = {
        controller.haptic()
        if (servers.isEmpty()) controller.toast(tr("Сначала добавь подписку — прокси нужен сервер"))
        else controller.addExtraProxy()
    }) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.Add, null, tint = c.accent, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(10.dp))
            Text(tr("Добавить прокси"), style = MaterialTheme.typography.titleSmall)
        }
    }
}

@Composable
private fun ExtraProxyCard(x: ExtraProxy, controller: GhostlyController, servers: List<Server>, host: String, portClash: Boolean, selected: Boolean, onSelect: () -> Unit, modifier: Modifier) {
    val c = Ghost.colors
    val update = { f: (ExtraProxy) -> ExtraProxy -> controller.updateExtraProxy(x.id, f) }
    val server = controller.extraProxyServer(x)
    GlassCard(modifier.fillMaxWidth().then(if (selected) Modifier.border(1.dp, c.accent, RoundedCornerShape(app.ghostly.ui.components.Radius.card)) else Modifier), padding = 12.dp) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(selected, { onSelect() })
            ServerPicker(server, x.serverName, servers, Modifier.weight(1f)) { picked -> controller.haptic(); controller.bindExtraProxy(x.id, picked.id) }
            Spacer(Modifier.width(10.dp))
            Box(Modifier.clip(RoundedCornerShape(14.dp)).hoverSound().clickable { controller.haptic(); update { it.copy(enabled = !it.enabled) } }.padding(4.dp)) {
                GhostSwitch(x.enabled)
            }
            Spacer(Modifier.width(4.dp))
            IconBubble(Icons.Rounded.Delete, { controller.haptic(); controller.removeExtraProxy(x.id) }, size = 36.dp, tint = c.bad)
        }
        if (server == null) Text(
            if (x.serverName == null) tr("Выбери сервер — без него прокси не поднимется")
            else tr("Сервера «{0}» больше нет в подписке — выбери другой", x.serverName),
            style = MaterialTheme.typography.bodySmall, color = c.bad, modifier = Modifier.padding(6.dp, 6.dp, 6.dp, 0.dp),
        )
        Spacer(Modifier.height(10.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            Segmented(ExtraProxyType.entries.map { it to it.title }, x.type, { t -> update { it.copy(type = t) } }, Modifier.weight(1f))
            PortField(tr("Порт"), x.port, Modifier.weight(1f)) { p -> update { it.copy(port = p) } }
        }
        if (portClash) Text(
            tr("Этот порт уже занят другим прокси Ghostly — поставь другой, иначе этот не поднимется"),
            style = MaterialTheme.typography.bodySmall, color = c.bad, modifier = Modifier.padding(6.dp, 6.dp, 6.dp, 0.dp),
        )
        Spacer(Modifier.height(4.dp))
        ToggleRow(tr("Логин и пароль"), if (x.auth) null else tr("Сейчас прокси открыт без пароля"), x.auth, Icons.Rounded.Lock) { v -> update { it.copy(auth = v) } }
        if (x.auth) {
            CredentialField(tr("Логин"), x.user, controller) { v -> update { it.copy(user = v) } }
            Spacer(Modifier.height(8.dp))
            CredentialField(tr("Пароль"), x.pass, controller, secret = true) { v -> update { it.copy(pass = v) } }
        }
        Spacer(Modifier.height(10.dp))
        val link = x.address(host)
        val shown = x.address(host, maskPassword = true)
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Color.Black.copy(alpha = 0.22f)).border(1.dp, c.line, RoundedCornerShape(14.dp))
                .hoverSound().clickable { controller.haptic(); controller.platform.copyToClipboard(link); controller.toast(tr("Адрес прокси скопирован")) }
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                shown, style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace, color = if (x.ready && !portClash) c.ink else c.ink3),
                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
            )
            Icon(Icons.Rounded.ContentCopy, null, tint = c.ink3, modifier = Modifier.size(16.dp))
        }
    }
}

/** The server of an additional proxy: flag + name, a tap opens the list of the subscriptions' servers. */
@Composable
private fun ServerPicker(server: Server?, lostName: String?, servers: List<Server>, modifier: Modifier, onPick: (Server) -> Unit) {
    val c = Ghost.colors
    var open by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    Box(modifier) {
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(app.ghostly.ui.components.Radius.tile)).background(c.surface3).border(1.dp, c.hairline, RoundedCornerShape(app.ghostly.ui.components.Radius.tile))
                .hoverSound().clickable { open = true }.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val t = server?.title()
            if (t?.flag != null) FlagIcon(t.flag, 14.dp) else Icon(Icons.Rounded.Public, null, tint = c.ink3, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(10.dp))
            Text(
                t?.let { listOfNotNull(it.title, it.subtitle).joinToString(" · ") } ?: lostName ?: tr("Выбрать сервер"),
                style = MaterialTheme.typography.titleSmall, color = if (server != null) c.ink else c.ink3,
                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
            )
            Icon(Icons.Rounded.ExpandMore, null, tint = c.ink3, modifier = Modifier.size(18.dp))
        }
        DropdownMenu(open, { open = false }, modifier = Modifier.heightIn(max = 420.dp)) {
            androidx.compose.material3.OutlinedTextField(query, { query = it }, placeholder = { Text(tr("Поиск")) }, singleLine = true, modifier = Modifier.padding(horizontal = 8.dp))
            servers.filter { it.matchesQuery(query) }.forEach { srv ->
                val t = srv.title()
                DropdownMenuItem(
                    text = { Text(listOfNotNull(t.title, t.subtitle).joinToString(" · "), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    leadingIcon = { if (t.flag != null) FlagIcon(t.flag, 13.dp) else Icon(Icons.Rounded.Public, null, modifier = Modifier.size(16.dp)) },
                    trailingIcon = { if (srv.id == server?.id) Icon(Icons.Rounded.CheckCircle, null, tint = c.accent) },
                    onClick = { open = false; onPick(srv) },
                )
            }
        }
    }
}
