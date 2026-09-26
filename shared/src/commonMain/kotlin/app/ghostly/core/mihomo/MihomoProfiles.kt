package app.ghostly.core.mihomo

import app.ghostly.core.model.MIHOMO_PROFILE
import app.ghostly.core.model.Profile
import app.ghostly.core.model.Server
import app.ghostly.core.model.guessPool
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive

/** What we read out of a Clash/mihomo config: server rows for the list and the group tree. */
object MihomoProfiles {

    /**
     * One row per proxy of the config. A config that only has proxy-providers (the proxies are
     * downloaded by the core) gets a single row that stands for the whole profile.
     */
    fun servers(cfg: JsonObject, idPrefix: String, inlineHeaders: MutableMap<String, String>): List<Server> {
        val proxies = (cfg["proxies"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
        val rows = proxies.mapIndexedNotNull { i, p ->
            val name = p.str("name") ?: return@mapIndexedNotNull null
            val type = p.str("type")?.lowercase() ?: return@mapIndexedNotNull null
            if (type in setOf("direct", "reject", "dns", "pass")) return@mapIndexedNotNull null
            Server(
                id = "$idPrefix:$i",
                name = name,
                protocol = when (type) {
                    "ss" -> "shadowsocks"
                    "hysteria2", "hy2" -> "hysteria"
                    else -> type
                },
                transport = p.str("network") ?: if (type in setOf("hysteria2", "hy2", "hysteria", "tuic")) null else "tcp",
                security = when {
                    p["reality-opts"] is JsonObject -> "reality"
                    p["tls"]?.jsonPrimitive?.contentOrNull == "true" -> "tls"
                    else -> null
                },
                host = p.str("server"),
                port = p["port"]?.jsonPrimitive?.let { it.intOrNull ?: it.contentOrNull?.toIntOrNull() } ?: 0,
                mihomo = p,
                pool = guessPool(name),
            )
        }
        if (rows.isNotEmpty()) return rows
        val providers = (cfg["proxy-providers"] as? JsonObject)?.size ?: 0
        if (providers == 0) return emptyList()
        val groups = (cfg["proxy-groups"] as? JsonArray)?.size ?: 0
        return listOf(
            Server(
                id = "$idPrefix:all",
                name = inlineHeaders["profile-title"] ?: "Все серверы подписки",
                protocol = MIHOMO_PROFILE,
                transport = "$groups",
                config = null,
            ),
        )
    }

    /** Groups as name → members (only `proxies:`; members from `use:` providers are not known before start). */
    fun groups(cfg: JsonObject): Map<String, Pair<String, List<String>>> =
        (cfg["proxy-groups"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }.mapNotNull { g ->
            val name = g.str("name") ?: return@mapNotNull null
            val members = (g["proxies"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
            name to ((g.str("type") ?: "select").lowercase() to members)
        }.toMap()

    /**
     * The groups a profile will have once mihomo runs it, built from the profile alone — so the
     * selectors can be shown and set before connecting. Members pulled from proxy-providers (`use:`)
     * are only known to a running core and are missing here. [now] and delays are filled by the caller.
     */
    fun offlineGroups(profile: Profile): List<ProxyGroupInfo> {
        val cfg = profile.mihomo
        if (cfg == null) {
            val names = profile.servers.filter { it.link != null && it.config == null }.map { it.name }.distinct()
            if (names.isEmpty()) return emptyList()
            val auto = MihomoConfigBuilder.AUTO_GROUP
            val fallback = MihomoConfigBuilder.FALLBACK_GROUP
            return listOf(
                ProxyGroupInfo(MihomoConfigBuilder.MAIN_GROUP, "Selector", null, listOf(auto, fallback) + names, emptyMap(), setOf(auto, fallback)),
                ProxyGroupInfo(auto, "URLTest", null, names, emptyMap(), emptySet()),
                ProxyGroupInfo(fallback, "Fallback", null, names, emptyMap(), emptySet()),
            )
        }
        val raw = (cfg["proxy-groups"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
        val groupNames = raw.mapNotNull { it.str("name") }.toSet()
        return raw.mapNotNull { g ->
            val name = g.str("name") ?: return@mapNotNull null
            if ((g["hidden"] as? JsonPrimitive)?.contentOrNull == "true") return@mapNotNull null
            val members = (g["proxies"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
            val type = when ((g.str("type") ?: "select").lowercase()) {
                "select" -> "Selector"
                "url-test" -> "URLTest"
                "fallback" -> "Fallback"
                "load-balance" -> "LoadBalance"
                "relay" -> "Relay"
                "smart" -> "Smart"
                else -> g.str("type") ?: "Selector"
            }
            ProxyGroupInfo(name, type, null, members, emptyMap(), members.filter { it in groupNames }.toSet())
        }
    }

    /** Target of the final `MATCH,<target>` rule — the group most traffic goes through. */
    fun matchTarget(cfg: JsonObject): String? =
        (cfg["rules"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
            .lastOrNull { it.trim().startsWith("MATCH,", ignoreCase = true) }
            ?.split(',')?.getOrNull(1)?.trim()

    /**
     * The chain of `select` groups leading to [proxy], starting from the MATCH group when possible:
     * [(outer, inner), (inner, proxy)]. Picking a server in the list selects it along this path.
     */
    fun selectPath(cfg: JsonObject, proxy: String): List<Pair<String, String>> {
        val groups = groups(cfg)
        val selectable = groups.filterValues { it.first == "select" }
        fun dfs(group: String, seen: Set<String>): List<Pair<String, String>>? {
            val members = groups[group]?.second ?: return null
            if (group !in selectable) return null
            if (proxy in members) return listOf(group to proxy)
            for (m in members) {
                if (m in seen || m !in selectable) continue
                dfs(m, seen + m)?.let { return listOf(group to m) + it }
            }
            return null
        }
        val roots = listOfNotNull(matchTarget(cfg)) + selectable.keys
        for (r in roots) dfs(r, setOf(r))?.let { return it }
        return emptyList()
    }

    /**
     * A minimal config that only measures [servers]: their proxies, a controller and nothing else.
     * Chains (`dialer-proxy` to a group that isn't here) are cut, so the config always loads;
     * duplicate names are dropped (mihomo refuses them).
     */
    fun pingConfig(servers: List<Server>, controllerPort: Int, secret: String): JsonObject {
        val seen = HashSet<String>()
        val proxies = servers.mapNotNull { s ->
            val p = s.mihomo ?: return@mapNotNull null
            val name = p.str("name") ?: return@mapNotNull null
            if (!seen.add(name)) null else JsonObject(p - "dialer-proxy")
        }
        return JsonObject(
            mapOf(
                "mode" to JsonPrimitive("rule"),
                "log-level" to JsonPrimitive("silent"),
                "ipv6" to JsonPrimitive(false),
                "external-controller" to JsonPrimitive("127.0.0.1:$controllerPort"),
                "secret" to JsonPrimitive(secret),
                "profile" to JsonObject(mapOf("store-selected" to JsonPrimitive(false), "store-fake-ip" to JsonPrimitive(false))),
                // Plain DNS (no fake-ip): otherwise the Android bridge turns on fake-ip with its store in
                // cache.db, which the running tunnel's process already holds.
                "dns" to JsonObject(
                    mapOf(
                        "enable" to JsonPrimitive(true),
                        "enhanced-mode" to JsonPrimitive("normal"),
                        "default-nameserver" to JsonArray(listOf(JsonPrimitive("77.88.8.8"), JsonPrimitive("1.1.1.1"))),
                        "nameserver" to JsonArray(listOf(JsonPrimitive("https://77.88.8.8/dns-query"), JsonPrimitive("https://1.1.1.1/dns-query"))),
                    ),
                ),
                "proxies" to JsonArray(proxies),
                "rules" to JsonArray(listOf(JsonPrimitive("MATCH,DIRECT"))),
            ),
        )
    }

    private fun JsonObject.str(key: String) = (this[key] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }

    private fun JsonArray?.orEmpty(): List<kotlinx.serialization.json.JsonElement> = this ?: emptyList()
}
