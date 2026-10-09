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
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.ghostly.core.GhostlyController
import app.ghostly.core.model.CoreType
import app.ghostly.core.model.ExtraProxy
import app.ghostly.core.model.ExtraProxyType
import app.ghostly.core.model.Server
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

/**
 * «Дополнительные прокси» on the local-proxy page: any number of SOCKS5/HTTP proxies, each with its own
 * port, login and server. They run next to the main connection; auto-switching only moves the main one.
 */
@Composable
internal fun ExtraProxiesSection(controller: GhostlyController, host: String) {
    val s by controller.settings.collectAsState()
    val profiles by controller.profiles.collectAsState()
    val c = Ghost.colors
    val servers = remember(profiles) { controller.extraProxyServers() }

    SectionTitle("Дополнительные прокси")
    Text(
        "Сколько угодно своих прокси: у каждого свой порт и свой сервер. Работают одновременно с основным подключением — " +
            "например, одни программы в Proxifier идут через Германию, другие через Нидерланды. Автосмена сервера их не трогает.",
        style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(6.dp, 0.dp, 6.dp, 10.dp),
    )
    if (s.core != CoreType.XRAY) Text(
        "Сейчас выбрано ядро Mihomo — дополнительные прокси работают на ядре Xray и поднимутся, когда вернёшься на него.",
        style = MaterialTheme.typography.bodySmall, color = c.warn, modifier = Modifier.padding(6.dp, 0.dp, 6.dp, 10.dp),
    )
    // A port may be used once: by the main proxy or by one additional proxy.
    val mainPorts = setOf(s.socksPort, s.httpPort)
    s.extraProxies.forEach { x ->
        key(x.id) {
            val clash = x.port in mainPorts || s.extraProxies.any { it.id != x.id && it.port == x.port }
            ExtraProxyCard(x, controller, servers, host, clash)
            Spacer(Modifier.height(10.dp))
        }
    }
    GlassCard(Modifier.fillMaxWidth(), padding = 14.dp, onClick = {
        controller.haptic()
        if (servers.isEmpty()) controller.toast("Сначала добавь подписку — прокси нужен сервер")
        else controller.addExtraProxy()
    }) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.Add, null, tint = c.accent, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(10.dp))
            Text("Добавить прокси", style = MaterialTheme.typography.titleSmall)
        }
    }
}

@Composable
private fun ExtraProxyCard(x: ExtraProxy, controller: GhostlyController, servers: List<Server>, host: String, portClash: Boolean) {
    val c = Ghost.colors
    val update = { f: (ExtraProxy) -> ExtraProxy -> controller.updateExtraProxy(x.id, f) }
    val server = controller.extraProxyServer(x)
    GlassCard(Modifier.fillMaxWidth(), padding = 12.dp) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            ServerPicker(server, x.serverName, servers, Modifier.weight(1f)) { picked -> controller.haptic(); controller.bindExtraProxy(x.id, picked.id) }
            Spacer(Modifier.width(10.dp))
            Box(Modifier.clip(RoundedCornerShape(14.dp)).hoverSound().clickable { controller.haptic(); update { it.copy(enabled = !it.enabled) } }.padding(4.dp)) {
                GhostSwitch(x.enabled)
            }
            Spacer(Modifier.width(4.dp))
            IconBubble(Icons.Rounded.Delete, { controller.haptic(); controller.removeExtraProxy(x.id) }, size = 36.dp, tint = c.bad)
        }
        if (server == null) Text(
            if (x.serverName == null) "Выбери сервер — без него прокси не поднимется"
            else "Сервера «${x.serverName}» больше нет в подписке — выбери другой",
            style = MaterialTheme.typography.bodySmall, color = c.bad, modifier = Modifier.padding(6.dp, 6.dp, 6.dp, 0.dp),
        )
        Spacer(Modifier.height(10.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            Segmented(ExtraProxyType.entries.map { it to it.title }, x.type, { t -> update { it.copy(type = t) } }, Modifier.weight(1f))
            PortField("Порт", x.port, Modifier.weight(1f)) { p -> update { it.copy(port = p) } }
        }
        if (portClash) Text(
            "Этот порт уже занят другим прокси Ghostly — поставь другой, иначе этот не поднимется",
            style = MaterialTheme.typography.bodySmall, color = c.bad, modifier = Modifier.padding(6.dp, 6.dp, 6.dp, 0.dp),
        )
        Spacer(Modifier.height(4.dp))
        ToggleRow("Логин и пароль", if (x.auth) null else "Сейчас прокси открыт без пароля", x.auth, Icons.Rounded.Lock) { v -> update { it.copy(auth = v) } }
        if (x.auth) {
            CredentialField("Логин", x.user, controller) { v -> update { it.copy(user = v) } }
            Spacer(Modifier.height(8.dp))
            CredentialField("Пароль", x.pass, controller, secret = true) { v -> update { it.copy(pass = v) } }
        }
        Spacer(Modifier.height(10.dp))
        val creds = if (x.auth) "${x.user}:${x.pass}@" else ""
        val link = "${x.type.scheme}://$creds$host:${x.port}"
        val shown = if (x.auth) "${x.type.scheme}://${x.user}:••••••@$host:${x.port}" else link
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Color.Black.copy(alpha = 0.22f)).border(1.dp, c.line, RoundedCornerShape(14.dp))
                .hoverSound().clickable { controller.haptic(); controller.platform.copyToClipboard(link); controller.toast("Адрес прокси скопирован") }
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
    Box(modifier) {
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Color.White.copy(alpha = 0.06f)).border(1.dp, c.line, RoundedCornerShape(14.dp))
                .hoverSound().clickable { open = true }.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val t = server?.title()
            if (t?.flag != null) FlagIcon(t.flag, 14.dp) else Icon(Icons.Rounded.Public, null, tint = c.ink3, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(10.dp))
            Text(
                t?.let { listOfNotNull(it.title, it.subtitle).joinToString(" · ") } ?: lostName ?: "Выбрать сервер",
                style = MaterialTheme.typography.titleSmall, color = if (server != null) c.ink else c.ink3,
                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
            )
            Icon(Icons.Rounded.ExpandMore, null, tint = c.ink3, modifier = Modifier.size(18.dp))
        }
        DropdownMenu(open, { open = false }, modifier = Modifier.heightIn(max = 420.dp)) {
            servers.forEach { srv ->
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
