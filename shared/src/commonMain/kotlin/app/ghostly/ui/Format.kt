package app.ghostly.ui

import app.ghostly.core.model.Server
import kotlin.math.roundToLong

object Format {

    fun bytes(b: Long): String {
        if (b < 1024) return "$b Б"
        val units = listOf("КБ", "МБ", "ГБ", "ТБ")
        var v = b.toDouble() / 1024
        var i = 0
        while (v >= 1024 && i < units.lastIndex) { v /= 1024; i++ }
        return "${oneDecimal(v)} ${units[i]}"
    }

    fun speed(bps: Long): String = if (bps < 1024) "$bps Б/с" else bytes(bps) + "/с"

    fun duration(ms: Long): String {
        val s = (ms / 1000).coerceAtLeast(0)
        val h = s / 3600
        val m = (s % 3600) / 60
        val sec = s % 60
        return "${two(h)}:${two(m)}:${two(sec)}"
    }

    /** "Осталось 3 дня" / "Истекла" — a full phrase for subscription cards. */
    fun expiryPhrase(expireSec: Long, nowMs: Long): String =
        if (expireSec * 1000 <= nowMs) "Подписка истекла" else "Осталось ${remaining(expireSec, nowMs)}"

    /** "3 дня", "21 день", "5 часов" — Russian plural forms. */
    fun remaining(expireSec: Long, nowMs: Long): String {
        val left = expireSec * 1000 - nowMs
        if (left <= 0) return "0 дней"
        val days = left / 86_400_000
        if (days >= 1) return "$days ${plural(days, "день", "дня", "дней")}"
        val hours = (left / 3_600_000).coerceAtLeast(1)
        return "$hours ${plural(hours, "час", "часа", "часов")}"
    }

    /** "обновлена 5 мин назад" — when a subscription was last fetched. */
    fun updatedAgo(atMs: Long, nowMs: Long): String {
        if (atMs <= 0) return "ещё не обновлялась"
        val min = (nowMs - atMs).coerceAtLeast(0) / 60_000
        return when {
            min < 1 -> "обновлена только что"
            min < 60 -> "обновлена $min мин назад"
            min < 24 * 60 -> "обновлена ${min / 60} ч назад"
            else -> (min / (24 * 60)).let { d -> "обновлена $d ${plural(d, "день", "дня", "дней")} назад" }
        }
    }

    /** "12 октября 2026" for a unix-seconds moment in the given UTC offset. */
    fun date(epochSec: Long, offsetMin: Int): String {
        // Days since 1970-01-01 → civil date (H. Hinnant's days_from_civil, inverted).
        val z = (epochSec + offsetMin * 60L).floorDiv(86_400L) + 719_468
        val era = z.floorDiv(146_097L)
        val doe = z - era * 146_097
        val yoe = (doe - doe / 1460 + doe / 36_524 - doe / 146_096) / 365
        val doy = doe - (365 * yoe + yoe / 4 - yoe / 100)
        val mp = (5 * doy + 2) / 153
        val d = doy - (153 * mp + 2) / 5 + 1
        val m = if (mp < 10) mp + 3 else mp - 9
        val y = yoe + era * 400 + if (m <= 2) 1 else 0
        return "$d ${MONTHS[(m - 1).toInt()]} $y"
    }

    private val MONTHS = listOf("января", "февраля", "марта", "апреля", "мая", "июня", "июля", "августа", "сентября", "октября", "ноября", "декабря")

    fun plural(n: Long, one: String, few: String, many: String): String {
        val m10 = n % 10
        val m100 = n % 100
        return when {
            m10 == 1L && m100 != 11L -> one
            m10 in 2..4 && m100 !in 12..14 -> few
            else -> many
        }
    }

    private fun two(v: Long) = v.toString().padStart(2, '0')

    private fun oneDecimal(v: Double): String {
        val r = (v * 10).roundToLong()
        return if (r % 10 == 0L || v >= 100) "${(v).roundToLong()}" else "${r / 10},${r % 10}"
    }
}

/** Server name split for display: flag emoji, title, subtitle ("Обычный · Стандарт" → "Обычный" / "Стандарт"). */
data class ServerTitle(val flag: String?, val title: String, val subtitle: String?)

fun Server.title(): ServerTitle {
    var n = name.trim()
    // The row's icon is the FIRST flag anywhere in the name (like Happ): "Автовыбор 🇪🇺 🤝🇷🇺" → EU.
    val flag = FLAG_ANYWHERE.find(n)?.value
    if (flag != null) n = n.replaceFirst(flag, " ").replace(Regex("\\s+"), " ").trim()
    val parts = n.split(" · ", " | ", " - ").map { it.trim() }.filter { it.isNotEmpty() }
    return if (parts.size >= 2) ServerTitle(flag, parts.first(), parts.drop(1).joinToString(" · "))
    else ServerTitle(flag, n.ifEmpty { name }, null)
}

/** A regional-indicator pair (a flag emoji) anywhere in a string. */
private val FLAG_ANYWHERE = Regex("[\\x{1F1E6}-\\x{1F1FF}]{2}")

fun Server.protocolLabel(): String = when (protocol) {
    "vless" -> "VLESS"
    "vmess" -> "VMess"
    "trojan" -> "Trojan"
    "shadowsocks" -> "SS"
    "hysteria" -> "Hysteria2"
    "balancer" -> "Авто"
    else -> protocol.uppercase()
}

fun Server.transportLabel(): String? = when {
    protocol == app.ghostly.core.model.MIHOMO_PROFILE -> "группы: $transport"
    isAuto -> "$transport узлов"
    protocol == "hysteria" -> "QUIC"
    security == "reality" -> "${transport?.uppercase()} · Reality"
    transport != null -> transport.uppercase()
    else -> null
}
