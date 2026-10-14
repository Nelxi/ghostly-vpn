package app.ghostly.core.vpn

/**
 * «Следующий» in the VPN notification: steps through the favourite servers in list order,
 * the one after [current], wrapping around. Null when there is nowhere else to go.
 */
object QuickSwitch {
    fun next(order: List<String>, favorites: Set<String>, current: String?): String? {
        val favs = order.filter { it in favorites }
        if (favs.isEmpty()) return null
        // Not on a favourite (index -1) → the first one.
        val next = favs[(favs.indexOf(current) + 1) % favs.size]
        return next.takeIf { it != current }
    }
}
