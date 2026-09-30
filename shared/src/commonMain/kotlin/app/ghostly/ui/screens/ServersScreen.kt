package app.ghostly.ui.screens

import app.ghostly.ui.components.hoverSound
import app.ghostly.ui.components.outerShadow
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Campaign
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.MoreHoriz
import androidx.compose.material.icons.rounded.NetworkPing
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.automirrored.rounded.Sort
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.SupportAgent
import androidx.compose.material.icons.rounded.StarBorder
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.unit.sp
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.Public
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.ghostly.core.GhostlyController
import app.ghostly.core.model.Ping
import app.ghostly.core.model.Profile
import app.ghostly.core.model.Server
import app.ghostly.ui.Format
import app.ghostly.ui.components.GhostMark
import app.ghostly.ui.components.IconBubble
import app.ghostly.ui.components.PingPill
import app.ghostly.ui.components.Spinner
import app.ghostly.ui.components.Tag
import app.ghostly.ui.components.orbitBorder
import app.ghostly.ui.components.pressScale
import app.ghostly.ui.components.spotlight
import app.ghostly.ui.components.appear
import app.ghostly.ui.protocolLabel
import app.ghostly.ui.theme.Ghost
import app.ghostly.ui.theme.Motion
import app.ghostly.ui.title
import app.ghostly.ui.transportLabel

private enum class Sort { LIST, PING, NAME }

/**
 * @param onPicked called after a tap selects a server (the picker sheet closes itself with it).
 */
@Composable
fun ServersScreen(
    controller: GhostlyController,
    contentPadding: PaddingValues,
    onAdd: () -> Unit,
    onPicked: () -> Unit = {},
    showHeader: Boolean = true,
    compact: Boolean = false,
) {
    CompositionLocalProvider(LocalListPad provides if (compact) 12.dp else 18.dp) {
        ServersList(controller, contentPadding, onAdd, onPicked, showHeader)
    }
}

/** Horizontal gutter of the list: tighter inside the desktop side panel. */
private val LocalListPad = staticCompositionLocalOf { 18.dp }

@Composable
private fun ServersList(
    controller: GhostlyController,
    contentPadding: PaddingValues,
    onAdd: () -> Unit,
    onPicked: () -> Unit,
    showHeader: Boolean,
) {
    val c = Ghost.colors
    val profiles by controller.profiles.collectAsState()
    val pings by controller.pings.collectAsState()
    val pinging by controller.pinging.collectAsState()
    val refreshing by controller.refreshing.collectAsState()
    val selected by controller.selectedServerId.collectAsState()
    val favorites by controller.favorites.collectAsState()
    val liveGroups by controller.mihomoGroups.groups.collectAsState()
    val staticGroups by controller.staticMihomoGroups.collectAsState()
    // Selectors are there before the first connect: drawn from the profile until the core reports live ones.
    val mihomoGroups = liveGroups.ifEmpty { staticGroups }
    val liveTesting by controller.mihomoGroups.testing.collectAsState()
    val staticTesting by controller.groupsPinging.collectAsState()
    val groupsByProfile by controller.staticMihomoGroupsByProfile.collectAsState()
    var openGroups by rememberSaveable { mutableStateOf(setOf<String>()) }
    val listPad = LocalListPad.current

    var query by rememberSaveable { mutableStateOf("") }
    // One list, not two: rows already offered inside the mihomo groups don't repeat below (search still finds them).
    // Every subscription shows its own selectors under its header: the active one live from the
    // core when it runs, the others from their configs (a pick there makes that one active).
    val activeId = (profiles.firstOrNull { p -> p.servers.any { it.id == selected } } ?: profiles.firstOrNull())?.id
    var sort by rememberSaveable { mutableStateOf(Sort.LIST) }
    var collapsed by rememberSaveable { mutableStateOf(setOf<String>()) }
    // Rows animate in once; after that (scrolling back, recycling) they just appear.
    val seen = remember { HashSet<String>() }

    fun matches(s: Server) = query.isBlank() || s.name.contains(query.trim(), ignoreCase = true) || s.protocol.contains(query.trim(), true)
    fun sorted(list: List<Server>): List<Server> = when (sort) {
        Sort.LIST -> list
        Sort.NAME -> list.sortedBy { it.name.lowercase() }
        Sort.PING -> list.sortedWith(compareBy({ !it.isAuto }, { pings[it.id]?.takeIf(Ping::ok)?.ms ?: Long.MAX_VALUE }))
    }
    val pick: (Server) -> Unit = { s ->
        controller.haptic()
        controller.select(s.id)
        onPicked()
    }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = contentPadding) {
        if (showHeader) item {
            Row(Modifier.fillMaxWidth().padding(start = LocalListPad.current, end = LocalListPad.current, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Серверы", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.weight(1f))
                IconBubble(Icons.Rounded.Add, onAdd, tint = c.accent)
            }
        }
        item {
            Row(Modifier.fillMaxWidth().padding(horizontal = LocalListPad.current, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                SearchField(query, { query = it }, Modifier.weight(1f))
                Spacer(Modifier.width(8.dp))
                var sortMenu by remember { mutableStateOf(false) }
                Box {
                    IconBubble(Icons.AutoMirrored.Rounded.Sort, { sortMenu = true }, active = sort != Sort.LIST)
                    DropdownMenu(sortMenu, { sortMenu = false }) {
                        listOf(Sort.LIST to "Как в подписке", Sort.PING to "По пингу", Sort.NAME to "По имени").forEach { (v, l) ->
                            DropdownMenuItem(text = { Text(l) }, onClick = { sort = v; sortMenu = false },
                                trailingIcon = { if (sort == v) Icon(Icons.Rounded.CheckCircle, null, tint = c.accent) })
                        }
                    }
                }
                Spacer(Modifier.width(8.dp))
                IconBubble(Icons.Rounded.NetworkPing, { controller.haptic(app.ghostly.core.vpn.Haptic.TICK); controller.pingAll() }, active = pinging.isNotEmpty())
            }
        }

        if (profiles.isEmpty()) item { EmptyServers(onAdd) }

        // Quick pick: the recommended server right now (Finnish Hysteria2 first, see bestServer) — with mihomo the selectors decide, so the
        // groups right below are the whole story and no quick-pick card is needed.
        if (query.isBlank() && mihomoGroups.isEmpty()) controller.bestServer()?.let { best ->
            item { BestRow(best, pings[best.id]?.ms) { pick(best) } }
        }

        val inGroups = if (query.isBlank()) (mihomoGroups + groupsByProfile.filterKeys { it != activeId }.values.flatten()).flatMap { it.members }.toSet() else emptySet()
        val favs = profiles.flatMap { it.servers }.filter { it.id in favorites && matches(it) && it.name !in inGroups }
        if (favs.isNotEmpty()) {
            item { GroupLabel("Избранное", Icons.Rounded.Star) }
            items(sorted(favs), key = { "fav:" + it.id }) { s ->
                ServerRow(s, s.id == selected, pings[s.id], s.id in pinging, true, controller, Modifier.animateItem()) { pick(s) }
            }
        }

        // The subscription in use comes first; the rest keep the order they were added in.
        profiles.sortedByDescending { it.id == activeId }.forEach { profile ->
            val active = profile.id == activeId
            val groups = if (active) mihomoGroups else groupsByProfile[profile.id].orEmpty()
            val groupMembers = if (query.isBlank()) groups.flatMap { it.members }.toSet() else emptySet()
            val testing = (if (active) liveTesting else emptySet()) +
                groups.map { it.name }.filter { controller.groupTestKey(profile.id, it) in staticTesting }
            val list = sorted(profile.servers.filter { matches(it) && it.name !in groupMembers })
            item(key = "profile:" + profile.id) {
                ProfileHeader(profile, profile.id in collapsed, profile.id in refreshing, controller) {
                    collapsed = if (profile.id in collapsed) collapsed - profile.id else collapsed + profile.id
                }
            }
            if (profile.id !in collapsed && query.isBlank()) {
                val prefix = profile.id + "\u0000"
                proxyGroups(
                    groups, openGroups.filter { it.startsWith(prefix) }.map { it.removePrefix(prefix) }.toSet(), testing, listPad,
                    onToggle = { g -> (prefix + g).let { k -> openGroups = if (k in openGroups) openGroups - k else openGroups + k } },
                    onSelect = { g, m -> controller.haptic(); controller.pickGroup(g, m, profile.id) },
                    onTest = { g -> controller.haptic(app.ghostly.core.vpn.Haptic.TICK); controller.testGroup(g, profile.id) },
                    scope = profile.id,
                )
            }
            if (profile.id !in collapsed) {
                // Several countries in one list: consecutive servers of a country get one header with the
                // flag and the country's name, and the rows show the protocol instead of repeating the flag.
                // Sorting by ping mixes countries, so there the flags stay on the rows.
                val byCountry = sort != Sort.PING && list.mapNotNull { it.title().flag }.distinct().size > 1
                // One section per country (order of first appearance; the provider's order inside it),
                // so a torrent server at the end doesn't split "Финляндия" into two sections.
                val rows = if (byCountry) {
                    val order = list.mapNotNull { it.title().flag }.distinct()
                    list.sortedBy { srv -> srv.title().flag?.let { order.indexOf(it) } ?: -1 }
                } else list
                val flags = rows.map { it.title().flag }
                rows.forEachIndexed { i, s ->
                    val flag = flags[i]
                    if (byCountry && flag != null && (i == 0 || flags[i - 1] != flag)) {
                        item(key = "country:" + profile.id + ":" + i) { CountryHeader(flag, Modifier.animateItem()) }
                    }
                    item(key = s.id) {
                        val animate = remember(s.id) { seen.add(s.id) && i < 12 }
                        ServerRow(s, s.id == selected, pings[s.id], s.id in pinging, s.id in favorites, controller, Modifier.appear(i, enabled = animate).animateItem(), showFlag = !(byCountry && flag != null)) { pick(s) }
                    }
                }
            }
        }
        if (profiles.isNotEmpty() && query.isBlank()) item(key = "add") { AddSubscriptionRow(onAdd) }
        item { Spacer(Modifier.height(24.dp)) }
    }
}

@Composable
private fun SearchField(value: String, onChange: (String) -> Unit, modifier: Modifier) {
    val c = Ghost.colors
    Row(
        modifier.height(42.dp).outerShadow(RoundedCornerShape(16.dp), 0.6f).clip(RoundedCornerShape(16.dp)).background(Color.White.copy(alpha = 0.06f))
            .border(1.dp, c.line, RoundedCornerShape(16.dp)).padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Rounded.Search, null, tint = c.ink3, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        Box(Modifier.weight(1f)) {
            if (value.isEmpty()) Text("Поиск", style = MaterialTheme.typography.bodyMedium, color = c.ink3)
            BasicTextField(
                value, onChange, singleLine = true,
                textStyle = MaterialTheme.typography.bodyMedium.copy(color = c.ink),
                cursorBrush = SolidColor(c.accent), modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

/** Country section inside a subscription: flag + name, quieter than the subscription header. */
@Composable
private fun CountryHeader(flag: String, modifier: Modifier = Modifier) {
    val c = Ghost.colors
    Row(modifier.padding(start = LocalListPad.current + 14.dp, top = 14.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        app.ghostly.ui.components.FlagIcon(flag, 13.dp)
        Spacer(Modifier.width(8.dp))
        Text(app.ghostly.ui.countryName(flag), style = MaterialTheme.typography.labelMedium, color = c.ink2)
    }
}

@Composable
private fun GroupLabel(text: String, icon: androidx.compose.ui.graphics.vector.ImageVector) {
    val c = Ghost.colors
    Row(Modifier.padding(start = 24.dp, top = 14.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = c.warn, modifier = Modifier.size(14.dp))
        Spacer(Modifier.width(6.dp))
        Text(text.uppercase(), style = MaterialTheme.typography.labelSmall, color = c.ink3)
    }
}

@Composable
private fun ProfileHeader(profile: Profile, collapsed: Boolean, refreshing: Boolean, controller: GhostlyController, onToggle: () -> Unit) {
    val c = Ghost.colors
    val nav = LocalSubscriptionNav.current
    val arrow by animateFloatAsState(if (collapsed) -90f else 0f, Motion.bouncy())
    var menu by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth().padding(start = LocalListPad.current - 6.dp, end = 8.dp, top = 16.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // The arrow folds the list; the rest of the header opens the subscription page.
        Icon(Icons.Rounded.ExpandMore, if (collapsed) "Развернуть" else "Свернуть", tint = c.ink3,
            modifier = Modifier.size(34.dp).clip(RoundedCornerShape(12.dp)).hoverSound().clickable(onClick = onToggle).padding(7.dp).rotate(arrow))
        Column(
            Modifier.weight(1f).clip(RoundedCornerShape(12.dp)).pointerHoverIcon(PointerIcon.Hand)
                .hoverSound().clickable { nav.open(profile.id) }.padding(horizontal = 6.dp, vertical = 6.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                app.ghostly.ui.components.FlagText(profile.name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                // There is a note from the provider: it is on the subscription page.
                if (profile.announce != null) {
                    Spacer(Modifier.width(6.dp))
                    Icon(Icons.Rounded.Campaign, "Есть сообщение", tint = c.accent, modifier = Modifier.size(15.dp))
                }
                Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null, tint = c.ink3, modifier = Modifier.size(18.dp))
            }
            val info = profile.info
            val parts = buildList {
                add("${profile.servers.size} ${Format.plural(profile.servers.size.toLong(), "сервер", "сервера", "серверов")}")
                if (info != null && !info.unlimitedTime) add(Format.expiryPhrase(info.expire, GhostlyController.now()).lowercase())
            }
            Text(parts.joinToString(" · "), style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (profile.url != null) {
            Box(Modifier.size(34.dp), contentAlignment = Alignment.Center) {
                if (refreshing) Spinner(c.accent, Modifier.size(18.dp))
                else Icon(Icons.Rounded.Refresh, "Обновить", tint = c.ink3,
                    modifier = Modifier.size(34.dp).clip(RoundedCornerShape(12.dp)).hoverSound().clickable { controller.refresh(profile.id, manual = true) }.padding(8.dp))
            }
        }
        Box {
            Icon(Icons.Rounded.MoreHoriz, null, tint = c.ink3,
                modifier = Modifier.size(34.dp).clip(RoundedCornerShape(12.dp)).hoverSound().clickable { menu = true }.padding(7.dp))
            DropdownMenu(menu, { menu = false }) {
                DropdownMenuItem(text = { Text("О подписке") }, leadingIcon = { Icon(Icons.Rounded.Info, null) },
                    onClick = { menu = false; nav.open(profile.id) })
                DropdownMenuItem(text = { Text("Проверить пинг") }, leadingIcon = { Icon(Icons.Rounded.NetworkPing, null) },
                    onClick = { menu = false; controller.pingAll(profile.id) })
                profile.webPageUrl?.let { url ->
                    DropdownMenuItem(text = { Text("Кабинет подписки") }, leadingIcon = { Icon(Icons.Rounded.Public, null) },
                        onClick = { menu = false; controller.platform.openUrl(url) })
                }
                profile.supportUrl?.let { url ->
                    DropdownMenuItem(text = { Text("Поддержка") }, leadingIcon = { Icon(Icons.Rounded.SupportAgent, null) },
                        onClick = { menu = false; controller.platform.openUrl(url) })
                }
                profile.url?.let { url ->
                    DropdownMenuItem(text = { Text("Скопировать ссылку") }, leadingIcon = { Icon(Icons.Rounded.ContentCopy, null) },
                        onClick = { menu = false; controller.platform.copyToClipboard(url) })
                }
                DropdownMenuItem(text = { Text("Удалить", color = c.bad) }, leadingIcon = { Icon(Icons.Rounded.Delete, null, tint = c.bad) },
                    onClick = { menu = false; controller.deleteProfile(profile.id) })
            }
        }
    }
}

/** Always at the end of the list, so another subscription is one tap away (also in the picker). */
@Composable
private fun AddSubscriptionRow(onAdd: () -> Unit) {
    val c = Ghost.colors
    Row(
        Modifier.fillMaxWidth().padding(horizontal = LocalListPad.current, vertical = 14.dp)
            .clip(RoundedCornerShape(16.dp)).border(1.dp, c.accent.copy(alpha = 0.3f), RoundedCornerShape(16.dp))
            .background(c.accent.copy(alpha = 0.06f)).pointerHoverIcon(PointerIcon.Hand)
            .hoverSound().clickable(onClick = onAdd).padding(vertical = 14.dp),
        horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Rounded.Add, null, tint = c.accent, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        Text("Добавить подписку", style = MaterialTheme.typography.labelLarge, color = c.accent)
    }
}

@Composable
private fun BestRow(server: Server, ms: Long?, onClick: () -> Unit) {
    val c = Ghost.colors
    val interaction = remember { MutableInteractionSource() }
    Row(
        Modifier.fillMaxWidth().padding(horizontal = LocalListPad.current, vertical = 4.dp)
            .pressScale(interaction, 0.97f, hover = 1.015f)
            .clip(RoundedCornerShape(20.dp))
            .background(Color.White.copy(alpha = 0.045f))
            .spotlight(c.accent, 180.dp)
            .border(1.dp, c.line, RoundedCornerShape(20.dp))
            .hoverSound().clickable(interaction, null, onClick = onClick)
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Rounded.AutoAwesome, null, tint = c.accent, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text("Лучший сейчас", style = MaterialTheme.typography.titleSmall)
            val t = server.title()
            Row(verticalAlignment = Alignment.CenterVertically) {
                t.flag?.let { app.ghostly.ui.components.FlagIcon(it, 11.dp); Spacer(Modifier.width(6.dp)) }
                app.ghostly.ui.components.FlagText(listOfNotNull(t.title, t.subtitle).joinToString(" "), style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        PingPill(ms, false)
    }
}

@Composable
private fun ServerRow(
    server: Server, selected: Boolean, ping: Ping?, loading: Boolean, favorite: Boolean,
    controller: GhostlyController, modifier: Modifier = Modifier, showFlag: Boolean = true, onClick: () -> Unit,
) {
    val c = Ghost.colors
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    // A list, not a stack of cards: rows are flat, the chosen one gets a tint and an accent bar.
    val bg by animateColorAsState(
        when {
            selected -> c.accent.copy(alpha = 0.11f)
            hovered -> Color.White.copy(alpha = 0.045f)
            else -> Color.Transparent
        },
        Motion.quick(),
    )
    val bar by animateFloatAsState(if (selected) 1f else 0f, Motion.bouncy())
    val showTools = !controller.platform.isDesktop || hovered || selected
    var menu by remember { mutableStateOf(false) }
    Row(
        modifier.fillMaxWidth().padding(horizontal = LocalListPad.current, vertical = 1.dp)
            .pressScale(interaction, 0.985f, hover = 1f)
            .clip(RoundedCornerShape(16.dp))
            .background(bg)
            .drawBehind {
                if (bar > 0.01f) drawRoundRect(
                    c.accent,
                    topLeft = Offset(0f, size.height * (0.5f - 0.28f * bar)),
                    size = Size(3.dp.toPx(), size.height * 0.56f * bar),
                    cornerRadius = CornerRadius(2.dp.toPx()),
                )
            }
            .pointerHoverIcon(PointerIcon.Hand)
            .hoverSound().clickable(interaction, null, onClick = onClick)
            .padding(start = 12.dp, end = 6.dp, top = 9.dp, bottom = 9.dp)
            .animateContentSize(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ServerGlyph(server, showFlag)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            val t = server.title()
            Row(verticalAlignment = Alignment.Bottom) {
                // The name stays whole; the variant ("Стабильный", "Резерв") is what gets ellipsized.
                app.ghostly.ui.components.FlagText(t.title, style = MaterialTheme.typography.titleSmall, maxLines = 1, softWrap = false)
                t.subtitle?.let {
                    app.ghostly.ui.components.FlagText(" · $it", style = MaterialTheme.typography.bodyMedium.copy(color = c.ink2), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                }
            }
            // One quiet line instead of coloured badges; the protocol is skipped when the name already says it.
            val proto = server.protocolLabel()
            val meta = listOfNotNull(proto.takeUnless { t.title.contains(it, ignoreCase = true) }, server.transportLabel()).joinToString(" · ")
            if (meta.isNotEmpty()) Text(meta, style = MaterialTheme.typography.bodySmall, maxLines = 1)
            // Why it doesn't answer, from the direct check (IP / DPI / 16 KB).
            ping?.block?.takeIf { !ping.ok && it != app.ghostly.core.vpn.BlockVerdict.REACHABLE && it != app.ghostly.core.vpn.BlockVerdict.OFFLINE }?.let {
                Text(it.title, style = MaterialTheme.typography.bodySmall, color = c.bad, maxLines = 2)
            }
        }
        Spacer(Modifier.width(8.dp))
        if (server.canPing) PingText(ping?.ms, loading, ping?.block, Modifier.clip(RoundedCornerShape(8.dp)).hoverSound().clickable { controller.ping(server.id) }.padding(horizontal = 6.dp, vertical = 4.dp))
        if (favorite || showTools) {
            Icon(
                if (favorite) Icons.Rounded.Star else Icons.Rounded.StarBorder, null,
                tint = if (favorite) c.warn else c.ink3.copy(alpha = 0.6f),
                modifier = Modifier.size(32.dp).clip(RoundedCornerShape(12.dp)).hoverSound().clickable { controller.toggleFavorite(server.id) }.padding(7.dp),
            )
        } else Spacer(Modifier.width(32.dp))
        Box {
            Icon(Icons.Rounded.MoreHoriz, null, tint = c.ink3.copy(alpha = if (showTools) 1f else 0f),
                modifier = Modifier.size(30.dp).clip(RoundedCornerShape(12.dp)).hoverSound().clickable { menu = true }.padding(6.dp))
            DropdownMenu(menu, { menu = false }) {
                DropdownMenuItem(text = { Text("Проверить пинг") }, leadingIcon = { Icon(Icons.Rounded.NetworkPing, null) },
                    onClick = { menu = false; controller.ping(server.id) })
                controller.shareLink(server.id)?.let { link ->
                    DropdownMenuItem(text = { Text(if (server.link != null) "Скопировать ссылку" else "Скопировать конфиг") },
                        leadingIcon = { Icon(Icons.Rounded.ContentCopy, null) },
                        onClick = { menu = false; controller.platform.copyToClipboard(link) })
                }
                if (controller.profileOf(server.id)?.url == null) {
                    DropdownMenuItem(text = { Text("Удалить", color = c.bad) }, leadingIcon = { Icon(Icons.Rounded.Delete, null, tint = c.bad) },
                        onClick = { menu = false; controller.deleteServer(server.id) })
                }
            }
        }
    }
}

/** Flag if the name has one, otherwise a small muted protocol glyph — no filled tiles on every row. */
@Composable
private fun ServerGlyph(server: Server, showFlag: Boolean = true) {
    val c = Ghost.colors
    val flag = server.title().flag?.takeIf { showFlag }
    Box(Modifier.size(28.dp), contentAlignment = Alignment.Center) {
        when {
            flag != null -> app.ghostly.ui.components.FlagIcon(flag, 15.dp)
            server.isAuto -> Icon(Icons.Rounded.AutoAwesome, null, tint = c.accent, modifier = Modifier.size(19.dp))
            server.isWhitelist -> Icon(Icons.Rounded.Shield, null, tint = c.ink2, modifier = Modifier.size(18.dp))
            server.protocol == "hysteria" -> Icon(Icons.Rounded.Bolt, null, tint = c.ink2, modifier = Modifier.size(19.dp))
            else -> Icon(Icons.Rounded.Public, null, tint = c.ink3.copy(alpha = 0.7f), modifier = Modifier.size(17.dp))
        }
    }
}

/** Ping as coloured text with a dot — lighter than a pill in a long list. */
@Composable
private fun PingText(ms: Long?, loading: Boolean, block: app.ghostly.core.vpn.BlockVerdict?, modifier: Modifier = Modifier) {
    val c = Ghost.colors
    val color by animateColorAsState(
        when {
            loading || ms == null -> c.ink3
            ms <= 0 -> c.bad
            ms < 180 -> c.ok
            ms < 450 -> c.warn
            else -> c.bad
        },
        Motion.quick(),
    )
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(6.dp).clip(CircleShape).background(color))
        Spacer(Modifier.width(6.dp))
        app.ghostly.ui.components.RollingText(
            when {
                loading -> "···"
                ms == null -> "—"
                ms <= 0 -> block?.short ?: "нет"
                else -> "$ms мс"
            },
            style = MaterialTheme.typography.labelMedium.copy(fontFeatureSettings = "tnum"), color = color,
        )
    }
}

@Composable
private fun EmptyServers(onAdd: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(40.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        GhostMark(Modifier.size(96.dp), happy = 0f)
        Spacer(Modifier.height(12.dp))
        Text("Здесь пока пусто", style = MaterialTheme.typography.titleLarge)
        Text("Добавь подписку или ссылку на сервер", style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.height(16.dp))
        app.ghostly.ui.components.AccentButton("Добавить", onAdd, icon = Icons.Rounded.Add)
    }
}
