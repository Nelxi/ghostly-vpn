package app.ghostly.core.vpn

import app.ghostly.core.model.Ping
import app.ghostly.core.model.Server

/**
 * The order in which the app looks for a server to move to when the current one stops passing traffic.
 *
 * When an address gets blocked every entry on it dies at once (Vision, XHTTP, torrent and WebSocket of a
 * country share it), and when a node goes down so does every entry of that country. Picking the next
 * server by an old ping bounced people from «🇫🇮 Торрент» to «🇫🇮 XHTTP» and back while all of Finland was
 * cut. So:
 * - a server that just failed sits out [PENALTY_MS] (picking it by hand forgives it);
 * - servers of another country on another address come first, the failed one's siblings last;
 * - inside a group: a fresh working ping (lowest first), an older working one, none, a fresh failure.
 * The controller then measures the candidates in this order, [BATCH] at a time, and moves only to one that
 * answers right now.
 */
object Failover {
    /** How long a server that failed is left out of automatic picks. */
    const val PENALTY_MS = 10 * 60_000L

    /** A ping younger than this counts as what the server does now. */
    const val FRESH_MS = 2 * 60_000L

    /** Candidates measured together before the next ones are tried. */
    const val BATCH = 6

    /** The address clients connect to: entries that share it go down together when it is blocked. */
    fun entry(s: Server): String? = s.host?.trim()?.lowercase()?.removePrefix("[")?.removeSuffix("]")?.ifEmpty { null }

    /** The first flag in the name («🇳🇱 Vision» → 🇳🇱), null when there is none. */
    fun country(s: Server): String? {
        val n = s.name
        var i = 0
        while (i + 3 < n.length) {
            if (isRegional(n, i) && isRegional(n, i + 2)) return n.substring(i, i + 4)
            i++
        }
        return null
    }

    /** A regional indicator letter (U+1F1E6..U+1F1FF) as a UTF-16 pair at [i]. */
    private fun isRegional(s: String, i: Int): Boolean =
        s[i] == '\uD83C' && s[i + 1] in '\uDDE6'..'\uDDFF'

    /** Penalties still in force. */
    fun active(penalties: Map<String, Long>, now: Long): Map<String, Long> =
        penalties.filterValues { now - it in 0 until PENALTY_MS }

    /**
     * [candidates] in the order to try them after [failed] stopped working: penalised servers dropped,
     * the rest by how unrelated they are to every server that failed lately, then by what their pings say.
     */
    fun rank(
        candidates: List<Server>,
        failed: Server?,
        pings: Map<String, Ping>,
        penalties: Map<String, Long>,
        now: Long,
    ): List<Server> {
        val boxed = active(penalties, now).keys
        val dead = candidates.filter { it.id in boxed } + listOfNotNull(failed)
        val deadEntries = dead.mapNotNull { entry(it) }.toSet()
        val deadCountries = dead.mapNotNull { country(it) }.toSet()
        return candidates
            .filter { it.id !in boxed && it.id != failed?.id && !it.isAuto }
            .sortedWith(
                compareBy<Server>(
                    { s -> (if (entry(s)?.let { it in deadEntries } == true) 1 else 0) + (if (country(s)?.let { it in deadCountries } == true) 1 else 0) },
                    { s -> health(pings[s.id], now) },
                    { s -> pings[s.id]?.takeIf { it.ok }?.ms ?: Long.MAX_VALUE },
                ),
            )
    }

    /** 0 fresh and working, 1 working a while ago, 2 never measured or long ago failed, 3 failing right now. */
    private fun health(p: Ping?, now: Long): Int {
        val fresh = p != null && now - p.at <= FRESH_MS
        return when {
            p == null -> 2
            p.ok && fresh -> 0
            p.ok -> 1
            fresh -> 3
            else -> 2
        }
    }
}
