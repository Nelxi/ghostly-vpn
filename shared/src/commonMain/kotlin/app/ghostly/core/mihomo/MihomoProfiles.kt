package app.ghostly.core.mihomo

import app.ghostly.core.model.MIHOMO_PROFILE
import app.ghostly.core.model.Server
import app.ghostly.core.model.guessPool
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import app.ghostly.i18n.tr

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
                name = inlineHeaders["profile-title"] ?: tr("Все серверы подписки"),
                protocol = MIHOMO_PROFILE,
                transport = "$groups",
                config = null,
            ),
        )
    }

    /** One `proxy-groups:` entry: type, `proxies:` members, and whether the config hides it from pickers. */
    data class GroupDef(val type: String, val members: List<String>, val hidden: Boolean)

    /** Groups as name → [GroupDef] (only `proxies:`; members from `use:` providers are not known before start). */
    fun groups(cfg: JsonObject): Map<String, GroupDef> =
        (cfg["proxy-groups"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }.mapNotNull { g ->
            val name = g.str("name") ?: return@mapNotNull null
            val members = (g["proxies"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
            name to GroupDef(
                type = (g.str("type") ?: "select").lowercase(),
                members = members,
                hidden = g["hidden"]?.jsonPrimitive?.contentOrNull == "true",
            )
        }.toMap()

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
        val selectable = groups.filterValues { it.type == "select" }
        fun dfs(group: String, seen: Set<String>): List<Pair<String, String>>? {
            val members = groups[group]?.members ?: return null
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

    private fun JsonObject.str(key: String) = (this[key] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }

    private fun JsonArray?.orEmpty(): List<kotlinx.serialization.json.JsonElement> = this ?: emptyList()
}
