package app.ghostly.core.vpn

/**
 * Turns the last lines of a core's log (Xray or mihomo) into one sentence a person can act on, and
 * says whether starting the core again may help. The raw tail used to be shown as is: four lines of
 * Go error chains for what is usually "the port is busy" or "the old adapter is still closing".
 */
object CoreErrors {

    private class Rule(val transient: Boolean, val text: String, vararg val marks: String)

    private val rules = listOf(
        Rule(
            true, "Адаптер TUN ещё занят прошлым подключением",
            "cannot create a file when that file already exists", "failed to create tun", "create tun", "wintun",
            "device or resource busy", "utun",
        ),
        Rule(
            true, "Локальный порт занят другой программой",
            "address already in use", "only one usage of each socket address", "failed to listen", "bind:",
        ),
        Rule(
            false, "Не хватает прав: запусти Ghostly от имени администратора или выбери «Системный прокси»",
            "access is denied", "permission denied", "operation not permitted", "requires elevation",
        ),
        Rule(
            false, "Не найдены файлы geoip/geosite — переустанови приложение",
            "geoip.dat", "geosite.dat", "failed to load geo",
        ),
        Rule(
            false, "Сервер прислал настройки, которые ядро не понимает",
            "failed to parse", "failed to build", "failed to load config", "invalid config", "unknown field",
            "unknown protocol", "unknown transport", "unknown cipher", "unmarshal", "yaml:", "parse config",
        ),
    )

    private fun match(tail: List<String>): Rule? {
        val text = tail.joinToString("\n").lowercase()
        return rules.firstOrNull { r -> r.marks.any { it in text } }
    }

    /** May the same start succeed a moment later? Unknown failures count as yes: one more try is cheap. */
    fun transient(tail: List<String>): Boolean = match(tail)?.transient ?: true

    /** One sentence for the screen; the core's own last line follows when it adds something. */
    fun describe(tail: List<String>, tun: Boolean = false): String {
        val last = tail.map { clean(it) }.lastOrNull { it.isNotBlank() && !it.startsWith("[app]") }
        val rule = match(tail)
        return when {
            rule != null -> if (rule.transient || last == null) rule.text else "${rule.text}: ${last.take(160)}"
            last != null -> last.take(240)
            tun -> "Ядро не успело поднять TUN-адаптер"
            else -> "Ядро не запустилось"
        }
    }

    /** Drops the timestamp and level a core puts in front of every line. */
    private fun clean(line: String): String {
        var s = line.trim()
        // Xray: "2026/10/07 04:20:38.840347 [Warning] …"; mihomo: time="…" level=error msg="…"
        Regex("""^\d{4}/\d{2}/\d{2} \d{2}:\d{2}:\d{2}(\.\d+)?\s*""").find(s)?.let { s = s.substring(it.range.last + 1) }
        Regex("""msg="(.*)"\s*$""").find(s)?.let { s = it.groupValues[1] }
        return s.removePrefix("[Error]").removePrefix("[Warning]").removePrefix("Failed to start:").trim()
    }
}
