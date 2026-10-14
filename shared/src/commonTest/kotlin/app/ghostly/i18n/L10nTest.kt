package app.ghostly.i18n

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

class L10nTest {
    @AfterTest
    fun reset() = L10n.install("ru", emptyMap())

    @Test
    fun russianIsTheKeyAndTheFallback() {
        L10n.install("ru", emptyMap())
        assertEquals("Подключено", tr("Подключено"))
        assertEquals("Осталось 3 дня", tr("Осталось {0}", "3 дня"))
    }

    @Test
    fun translatesAndFillsPlaceholdersInAnyOrder() {
        L10n.install("en", mapOf("обновлена {0} {1} назад" to "updated {0} {1} ago", "{0} из {1}" to "{1}: {0}"))
        assertEquals("updated 5 days ago", tr("обновлена {0} {1} назад", 5, "days"))
        assertEquals("b: a", tr("{0} из {1}", "a", "b"))
        assertEquals("Не переведено", tr("Не переведено"))
    }

    @Test
    fun pluralsFollowTheLanguage() {
        L10n.install("ru", emptyMap())
        assertEquals("день", L10n.plural(21, "день", "дня", "дней"))
        assertEquals("дня", L10n.plural(3, "день", "дня", "дней"))
        assertEquals("дней", L10n.plural(11, "день", "дня", "дней"))

        L10n.install("en", mapOf("день" to "day", "дня" to "days", "дней" to "days"))
        assertEquals("day", L10n.plural(1, "день", "дня", "дней"))
        assertEquals("days", L10n.plural(21, "день", "дня", "дней"))
        assertEquals("days", L10n.plural(3, "день", "дня", "дней"))
    }

    @Test
    fun unknownLanguageFallsBackToEnglish() {
        assertEquals("en", L10n.resolve("xx"))
        assertEquals("fa", L10n.resolve("fa"))
        assertEquals("ru", L10n.resolve("ru"))
    }
}
