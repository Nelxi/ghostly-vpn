package app.ghostly.ui.screens

/** Desktop range/toggle selection; explicit checkboxes provide the same workflow on touch. */
internal data class ProxySelection(val ids: Set<String> = emptySet(), val anchor: String? = null) {
    fun click(id: String, order: List<String>, toggle: Boolean, range: Boolean): ProxySelection {
        val end = order.indexOf(id)
        if (end < 0) return this
        val start = anchor?.let(order::indexOf) ?: -1
        if (range && start >= 0) {
            val span = order.subList(minOf(start, end), maxOf(start, end) + 1).toSet()
            return copy(ids = if (toggle) ids + span else span)
        }
        return ProxySelection(if (toggle) { if (id in ids) ids - id else ids + id } else setOf(id), id)
    }

    fun retain(order: List<String>) = copy(ids = ids.intersect(order.toSet()), anchor = anchor?.takeIf { it in order })
}
