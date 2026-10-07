package app.ghostly.core.sub

/**
 * Ghostly's own addresses. The service lives on ghostlynex.fun (through a CDN). The domain it used
 * before, ghostlinknex.online, runs out in November 2026: subscriptions saved with it are still
 * recognised as ours and are moved to the current one (see [current]), and while it is alive it
 * serves as a second address for the same requests.
 */
object GhostlyDomains {
    const val MAIN = "ghostlynex.fun"

    /** The one name of the previous domain that may still answer (its direct-to-server name does not). */
    const val LEGACY_ALIVE = "ghostlinknex.online"

    /** The previous domain's names: still ours, no longer handed out. */
    private val LEGACY = listOf(LEGACY_ALIVE, "srv.ghostlinknex.online")

    private val ALL = listOf(MAIN) + LEGACY

    /** How long the link's own domain must stay dead before the app moves the subscription to a mirror. */
    const val SWITCH_AFTER_MS = 3 * 60_000L

    fun hostOf(url: String): String = url.substringAfter("://").substringBefore('/').substringBefore(':').lowercase()

    fun isOurs(url: String): Boolean = hostOf(url).let { h -> ALL.any { h == it || h == "www.$it" } }

    private fun withHost(url: String, newHost: String): String {
        val scheme = url.substringBefore("://", "")
        val rest = url.substringAfter("://")
        val host = rest.substringBefore('/').substringBefore(':')
        return "$scheme://$newHost" + rest.removePrefix(host)
    }

    /** The same link on Ghostly's other addresses, in the order they are tried; empty for anyone else's. */
    fun mirrorsOf(url: String): List<String> {
        val scheme = url.substringBefore("://", "")
        if (scheme.isEmpty() || !isOurs(url)) return emptyList()
        val own = hostOf(url).removePrefix("www.")
        return listOf(MAIN, LEGACY_ALIVE).filter { it != own }.map { withHost(url, it) }
    }

    /**
     * A subscription link on the current domain: links saved with a previous domain are rewritten,
     * everything else (the current domain, other providers) comes back untouched.
     */
    fun current(url: String): String {
        val host = hostOf(url).removePrefix("www.")
        return if (host in LEGACY) withHost(url, MAIN) else url
    }

    /**
     * The link to save for a subscription that is being added: ours always on the current domain;
     * and when the pasted address itself couldn't be reached but a mirror answered, the profile keeps
     * the mirror right away instead of a link that doesn't work on this network.
     */
    fun linkToSave(url: String, fetchedFrom: String?, ownAddressFailed: Boolean): String {
        val picked = if (ownAddressFailed && fetchedFrom != null && isOurs(url) && isOurs(fetchedFrom)) fetchedFrom else url
        return current(picked)
    }
}
