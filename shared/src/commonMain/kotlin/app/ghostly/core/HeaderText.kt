package app.ghostly.core

private val cyrillic = mapOf(
    'а' to "a", 'б' to "b", 'в' to "v", 'г' to "g", 'д' to "d", 'е' to "e", 'ё' to "e", 'ж' to "zh",
    'з' to "z", 'и' to "i", 'й' to "y", 'к' to "k", 'л' to "l", 'м' to "m", 'н' to "n", 'о' to "o",
    'п' to "p", 'р' to "r", 'с' to "s", 'т' to "t", 'у' to "u", 'ф' to "f", 'х' to "kh", 'ц' to "ts",
    'ч' to "ch", 'ш' to "sh", 'щ' to "sch", 'ъ' to "", 'ы' to "y", 'ь' to "", 'э' to "e", 'ю' to "yu",
    'я' to "ya", 'і' to "i", 'ї' to "yi", 'є' to "ye", 'ґ' to "g", 'ў' to "u",
)

/**
 * [s] made fit for an HTTP header value: HTTP clients (OkHttp, Darwin) refuse to send anything
 * outside printable ASCII, so a PC named «комп» broke every subscription fetch. Cyrillic is
 * transliterated («комп» → «komp»), other characters become '_'.
 */
fun headerSafe(s: String): String {
    val out = StringBuilder(s.length)
    for (c in s) {
        when {
            c in ' '..'~' -> out.append(c)
            else -> {
                val t = cyrillic[c.lowercaseChar()]
                when {
                    t == null -> out.append('_')
                    c.isUpperCase() -> out.append(t.replaceFirstChar { it.uppercaseChar() })
                    else -> out.append(t)
                }
            }
        }
    }
    return out.toString().trim().ifEmpty { "PC" }
}
