package app.ghostly.core.link

import app.ghostly.i18n.tr

/** Happ deep links. `happ://add/<url>` is a plain wrapper (handled on import); `happ://crypt…` is not. */
object HappLinks {
    /** `happ://crypt/…`, `crypt2` … `crypt5`: a subscription address encrypted for the Happ app alone. */
    fun isEncrypted(text: String): Boolean = Regex("""^happ://crypt\d*/""", RegexOption.IGNORE_CASE).containsMatchIn(text.trim())

    val ENCRYPTED_HINT: String get() =
        tr("Это зашифрованная ссылка Happ — её может открыть только сам Happ. Попросите у провайдера обычную ссылку подписки (https://…)")
}
