package app.ghostly.ui

import app.ghostly.core.model.Server
import kotlin.math.roundToLong
import app.ghostly.i18n.tr

object Format {

    fun bytes(b: Long): String {
        if (b < 1024) return tr("{0} Б", b)
        val units = listOf(tr("КБ"), tr("МБ"), tr("ГБ"), tr("ТБ"))
        var v = b.toDouble() / 1024
        var i = 0
        while (v >= 1024 && i < units.lastIndex) { v /= 1024; i++ }
        return "${oneDecimal(v)} ${units[i]}"
    }

    fun speed(bps: Long): String = if (bps < 1024) tr("{0} Б/с", bps) else bytes(bps) + tr("/с")

    fun duration(ms: Long): String {
        val s = (ms / 1000).coerceAtLeast(0)
        val h = s / 3600
        val m = (s % 3600) / 60
        val sec = s % 60
        return "${two(h)}:${two(m)}:${two(sec)}"
    }

    /** "Осталось 3 дня" / "Истекла" — a full phrase for subscription cards. */
    fun expiryPhrase(expireSec: Long, nowMs: Long): String =
        if (expireSec * 1000 <= nowMs) tr("Подписка истекла") else tr("Осталось {0}", remaining(expireSec, nowMs))

    /** "Ghostly · Осталось 12 дней · 34 ГБ из 100 ГБ" — the subscription line of the VPN notification. */
    fun subscriptionLine(name: String?, info: app.ghostly.core.model.SubscriptionInfo?, nowMs: Long): String? =
        listOfNotNull(
            name?.takeIf { it.isNotBlank() },
            info?.takeIf { !it.unlimitedTime }?.let { expiryPhrase(it.expire, nowMs) },
            info?.takeIf { !it.unlimitedTraffic }?.let { tr("{0} из {1}", bytes(it.used), bytes(it.total)) },
        ).joinToString(" · ").ifEmpty { null }

    /** "3 дня", "21 день", "5 часов" — plural forms, translated (see [app.ghostly.i18n.L10n.plural]). */
    fun remaining(expireSec: Long, nowMs: Long): String {
        val left = expireSec * 1000 - nowMs
        if (left <= 0) return tr("0 дней")
        val days = left / 86_400_000
        if (days >= 1) return "$days ${plural(days, "день", "дня", "дней")}"
        val hours = (left / 3_600_000).coerceAtLeast(1)
        return "$hours ${plural(hours, "час", "часа", "часов")}"
    }

    /** "обновлена 5 мин назад" — when a subscription was last fetched. */
    fun updatedAgo(atMs: Long, nowMs: Long): String {
        if (atMs <= 0) return tr("ещё не обновлялась")
        val min = (nowMs - atMs).coerceAtLeast(0) / 60_000
        return when {
            min < 1 -> tr("обновлена только что")
            min < 60 -> tr("обновлена {0} мин назад", min)
            min < 24 * 60 -> tr("обновлена {0} ч назад", min / 60)
            else -> (min / (24 * 60)).let { d -> tr("обновлена {0} {1} назад", d, plural(d, "день", "дня", "дней")) }
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
        return tr("{0} {1} {2}", d, tr(MONTHS[(m - 1).toInt()]), y)
    }

    /** "12 октября": [date] without the year, for tight places. */
    fun dateShort(epochSec: Long, offsetMin: Int): String {
        val full = date(epochSec, offsetMin)
        return full.substringBeforeLast(' ').ifEmpty { full }
    }

    private val MONTHS = listOf("января", "февраля", "марта", "апреля", "мая", "июня", "июля", "августа", "сентября", "октября", "ноября", "декабря")

    fun plural(n: Long, one: String, few: String, many: String): String = app.ghostly.i18n.L10n.plural(n, one, few, many)

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
    // Names come from the subscription in Russian; each part is translated if the dictionary knows it.
    val parts = n.split(" · ", " | ", " - ").map { it.trim() }.filter { it.isNotEmpty() }.map { tr(it) }
    return if (parts.size >= 2) ServerTitle(flag, parts.first(), parts.drop(1).joinToString(" · "))
    else ServerTitle(flag, tr(n).ifEmpty { name }, null)
}

/**
 * A country's part of the server list. Ghostly ships every country twice: its regular servers and an
 * «LTE» group — the same country over the routes that pass mobile white lists.
 */
data class ServerSection(val flag: String, val lte: Boolean)

fun Server.section(): ServerSection? = title().flag?.let { ServerSection(it, isWhitelist) }

/** Under an «LTE» header the rows don't repeat it: "LTE · Резерв" → "Резерв". */
fun ServerTitle.withoutLte(): ServerTitle {
    if (!title.equals("LTE", ignoreCase = true) || subtitle == null) return this
    val parts = subtitle.split(" · ")
    return ServerTitle(flag, parts.first(), parts.drop(1).joinToString(" · ").ifEmpty { null })
}

/** A regional-indicator pair (a flag emoji) anywhere in a string. */
private val FLAG_ANYWHERE = Regex("[\\x{1F1E6}-\\x{1F1FF}]{2}")

fun Server.protocolLabel(): String = when (protocol) {
    "vless" -> "VLESS"
    "vmess" -> "VMess"
    "trojan" -> "Trojan"
    "shadowsocks" -> "SS"
    "hysteria" -> "Hysteria2"
    "balancer" -> tr("Авто")
    else -> protocol.uppercase()
}

fun Server.transportLabel(): String? = when {
    protocol == app.ghostly.core.model.MIHOMO_PROFILE -> tr("группы: {0}", transport)
    isAuto -> tr("{0} узлов", transport)
    protocol == "hysteria" -> "QUIC"
    security == "reality" -> "${transport?.uppercase()} · Reality"
    transport != null -> transport.uppercase()
    else -> null
}


private val COUNTRY_NAMES = mapOf(
    "FI" to "Финляндия", "DE" to "Германия", "NL" to "Нидерланды", "RU" to "Россия", "US" to "США",
    "GB" to "Великобритания", "FR" to "Франция", "SE" to "Швеция", "NO" to "Норвегия", "PL" to "Польша",
    "TR" to "Турция", "KZ" to "Казахстан", "EE" to "Эстония", "LV" to "Латвия", "LT" to "Литва",
    "AT" to "Австрия", "CH" to "Швейцария", "JP" to "Япония", "SG" to "Сингапур", "HK" to "Гонконг",
    "UA" to "Украина", "CA" to "Канада", "EU" to "Европа", "AE" to "ОАЭ", "IT" to "Италия", "ES" to "Испания",
    "CZ" to "Чехия", "RO" to "Румыния", "BG" to "Болгария", "RS" to "Сербия", "AM" to "Армения",
    "GE" to "Грузия", "BY" to "Беларусь", "KR" to "Корея", "IN" to "Индия", "AU" to "Австралия",
    "BR" to "Бразилия", "IL" to "Израиль", "HU" to "Венгрия", "MD" to "Молдова", "IE" to "Ирландия",
    "DK" to "Дания", "BE" to "Бельгия", "PT" to "Португалия", "GR" to "Греция", "CY" to "Кипр",
)

/** Name (translated) of a flag emoji's country ("🇫🇮" → "Финляндия"); the two-letter code when unknown. */
fun countryName(flag: String): String {
    val cps = flag.flagCodePoints()
    if (cps.size < 2) return flag
    val code = cps.take(2).map { ('A'.code + (it - 0x1F1E6)).toChar() }.joinToString("")
    return COUNTRY_NAMES[code]?.let { tr(it) } ?: code
}

private fun String.flagCodePoints(): List<Int> {
    val out = ArrayList<Int>()
    var i = 0
    while (i < length) {
        val c = this[i]
        if (c.isHighSurrogate() && i + 1 < length) {
            out += ((c.code - 0xD800) shl 10) + (this[i + 1].code - 0xDC00) + 0x10000
            i += 2
        } else {
            out += c.code
            i++
        }
    }
    return out.filter { it in 0x1F1E6..0x1F1FF }
}
