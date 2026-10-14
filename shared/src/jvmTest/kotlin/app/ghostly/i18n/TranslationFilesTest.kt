package app.ghostly.i18n

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TranslationFilesTest {
    private val dir = File("src/commonMain/composeResources/files/i18n")
    private val placeholder = Regex("\\{\\d+\\}")

    @AfterTest
    fun reset() = L10n.install("ru", emptyMap())

    @Test
    fun everyShippedLanguageHasAFile() {
        for (lang in L10n.SUPPORTED.drop(1)) assertTrue(File(dir, "$lang.json").isFile, "no translation file for $lang")
    }

    @Test
    fun placeholdersSurviveTranslation() {
        for (file in dir.listFiles { f -> f.extension == "json" }!!) {
            val obj = Json.parseToJsonElement(file.readText()) as JsonObject
            for ((key, value) in obj) {
                val v = value.jsonPrimitive.content
                assertEquals(placeholder.findAll(key).map { it.value }.sorted().toList(), placeholder.findAll(v).map { it.value }.sorted().toList(), "${file.name}: $key -> $v")
            }
        }
    }

    @Test
    fun loadsTheBundledFile() = runBlocking {
        L10n.load("en")
        assertEquals("en", L10n.language)
        assertEquals("Connected", tr("Подключено"))
        L10n.load("ru")
        assertEquals("Подключено", tr("Подключено"))
    }
}
