package app.ghostly.i18n

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.intl.Locale
import app.ghostly.resources.Res
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.jetbrains.compose.resources.ExperimentalResourceApi

/**
 * App translations. Russian is the source language: the Russian text itself is the key
 * (`tr("Подключено")`), and `files/i18n/<lang>.json` maps it to the translation. A missing
 * translation falls back to the Russian text, so a half-done language never shows empty labels.
 */
object L10n {
    /** Languages the app ships; the first one is the source. */
    val SUPPORTED = listOf(
        "ru", "en", "uk", "be", "kk", "uz", "fa", "ar", "tr", "zh", "es", "pt", "de", "fr", "hi", "id",
    )

    /** Native names for the language picker. */
    val NATIVE_NAMES = mapOf(
        "ru" to "Русский", "en" to "English", "uk" to "Українська", "be" to "Беларуская", "kk" to "Қазақша",
        "uz" to "Oʻzbekcha", "fa" to "فارسی", "ar" to "العربية", "tr" to "Türkçe", "zh" to "中文",
        "es" to "Español", "pt" to "Português", "de" to "Deutsch", "fr" to "Français", "hi" to "हिन्दी",
        "id" to "Bahasa Indonesia",
    )

    /** A flag for each language in the picker (a language is not a country — the most common choice). */
    val FLAGS = mapOf(
        "ru" to "🇷🇺", "en" to "🇬🇧", "uk" to "🇺🇦", "be" to "🇧🇾", "kk" to "🇰🇿", "uz" to "🇺🇿", "fa" to "🇮🇷",
        "ar" to "🇸🇦", "tr" to "🇹🇷", "zh" to "🇨🇳", "es" to "🇪🇸", "pt" to "🇧🇷", "de" to "🇩🇪", "fr" to "🇫🇷",
        "hi" to "🇮🇳", "id" to "🇮🇩",
    )

    private val RTL = setOf("fa", "ar")
    private val SLAVIC_PLURALS = setOf("ru", "uk", "be")

    // Snapshot state: composables that call tr() recompose when the language changes.
    private var dict by mutableStateOf<Map<String, String>>(emptyMap())
    var language by mutableStateOf("ru")
        private set

    val isRtl: Boolean get() = language in RTL

    /** "system" (or blank) follows the device; an unknown device language falls back to English. */
    fun resolve(setting: String): String {
        val code = (if (setting.isBlank() || setting == "system") Locale.current.language else setting).lowercase()
        return code.takeIf { it in SUPPORTED } ?: "en"
    }

    @OptIn(ExperimentalResourceApi::class)
    suspend fun load(setting: String) {
        val lang = resolve(setting)
        dict = if (lang == "ru") emptyMap() else runCatching {
            val obj = Json.parseToJsonElement(Res.readBytes("files/i18n/$lang.json").decodeToString()) as JsonObject
            obj.mapValues { it.value.jsonPrimitive.content }
        }.getOrDefault(emptyMap())
        language = lang
    }

    internal fun lookup(ru: String): String = dict[ru]?.takeIf { it.isNotEmpty() } ?: ru

    /** For tests: install a dictionary directly. */
    internal fun install(lang: String, entries: Map<String, String>) {
        dict = entries
        language = lang
    }

    /** Picks a plural form; the forms are the Russian words (keys), translated here. */
    fun plural(n: Long, one: String, few: String, many: String): String {
        if (language !in SLAVIC_PLURALS) return lookup(if (n == 1L) one else many)
        val m10 = n % 10
        val m100 = n % 100
        return lookup(
            when {
                m10 == 1L && m100 != 11L -> one
                m10 in 2..4 && m100 !in 12..14 -> few
                else -> many
            },
        )
    }
}

/** The translation of a Russian UI string; `{0}`, `{1}`… are replaced with [args]. */
fun tr(ru: String, vararg args: Any?): String {
    var out = L10n.lookup(ru)
    args.forEachIndexed { i, a -> out = out.replace("{$i}", a.toString()) }
    return out
}
