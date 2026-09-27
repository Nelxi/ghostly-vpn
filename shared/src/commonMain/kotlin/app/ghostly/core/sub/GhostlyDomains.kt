package app.ghostly.core.sub

/**
 * Ghostly publishes every subscription (and the app updates) on several domains:
 * - ghostlinknex.online — main, through a Russian CDN;
 * - srv.ghostlinknex.online — straight to the server (works from abroad);
 * - ghostlynex.fun — backup: another CDN → the DE node, which also keeps a cached copy of every
 *   subscription, so it answers even while the main server is down or its domain is blocked by RKN.
 */
object GhostlyDomains {
    const val MAIN = "ghostlinknex.online"
    const val DIRECT = "srv.ghostlinknex.online"
    const val BACKUP = "ghostlynex.fun"

    private val ALL = listOf(MAIN, DIRECT, BACKUP)

    /** How long the link's own domain must stay dead before the app moves the subscription to a mirror. */
    const val SWITCH_AFTER_MS = 3 * 60_000L

    fun hostOf(url: String): String = url.substringAfter("://").substringBefore('/').substringBefore(':').lowercase()

    fun isOurs(url: String): Boolean = hostOf(url).let { h -> ALL.any { h == it || h == "www.$it" } }

    /** The same link on Ghostly's other domains; empty for anyone else's subscription. */
    fun mirrorsOf(url: String): List<String> {
        val scheme = url.substringBefore("://", "")
        if (scheme.isEmpty() || !isOurs(url)) return emptyList()
        val rest = url.substringAfter("://")
        val host = rest.substringBefore('/').substringBefore(':')
        val own = host.lowercase().removePrefix("www.")
        return ALL.filter { it != own }.map { "$scheme://$it" + rest.removePrefix(host) }
    }
}
