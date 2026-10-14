package app.ghostly.core.mihomo

import com.charleskorn.kaml.AnchorsAndAliases
import com.charleskorn.kaml.Yaml
import com.charleskorn.kaml.YamlConfiguration
import com.charleskorn.kaml.YamlList
import com.charleskorn.kaml.YamlMap
import com.charleskorn.kaml.YamlNode
import com.charleskorn.kaml.YamlNull
import com.charleskorn.kaml.YamlScalar
import com.charleskorn.kaml.YamlTaggedNode
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import app.ghostly.i18n.tr

/**
 * Clash/mihomo YAML → JSON. The config is kept and edited as JSON (like Xray configs) and written
 * back as JSON: mihomo reads it with a YAML parser, and JSON is valid YAML.
 */
object MihomoYaml {

    private val yaml = Yaml(
        configuration = YamlConfiguration(
            strictMode = false,
            anchorsAndAliases = AnchorsAndAliases.Permitted(maxAliasCount = 10_000u),
        ),
    )

    private val TOP_KEYS = Regex("""(?m)^(proxies|proxy-providers|proxy-groups)\s*:""")

    /** A Clash/mihomo profile rather than a list of links or an Xray JSON. */
    fun looksLikeClash(text: String): Boolean = !text.startsWith("{") && !text.startsWith("[") && TOP_KEYS.containsMatchIn(text)

    fun parse(text: String): JsonObject =
        toJson(yaml.parseToYamlNode(text)) as? JsonObject ?: throw IllegalArgumentException(tr("YAML без полей верхнего уровня"))

    private fun toJson(node: YamlNode): JsonElement = when (node) {
        is YamlNull -> JsonNull
        is YamlScalar -> scalar(node.content)
        is YamlList -> JsonArray(node.items.map(::toJson))
        is YamlMap -> {
            val out = LinkedHashMap<String, JsonElement>()
            val merges = mutableListOf<JsonObject>()
            node.entries.forEach { (k, v) ->
                if (k.content == "<<") {
                    // Merge key: `<<: *anchor` or `<<: [*a, *b]`; explicit keys win.
                    when (val m = toJson(v)) {
                        is JsonObject -> merges += m
                        is JsonArray -> m.filterIsInstance<JsonObject>().forEach { merges += it }
                        else -> {}
                    }
                } else {
                    out[k.content] = toJson(v)
                }
            }
            merges.forEach { m -> m.forEach { (k, v) -> if (k !in out) out[k] = v } }
            JsonObject(out)
        }
        is YamlTaggedNode -> toJson(node.innerNode)
        else -> JsonNull
    }

    private val INT = Regex("""-?(0|[1-9][0-9]{0,14})""")
    private val FLOAT = Regex("""-?(0|[1-9][0-9]*)\.[0-9]+""")

    /**
     * Typed scalars. Only canonical numbers become numbers ("0123", uuids and short ids stay strings);
     * mihomo decodes proxies weakly typed, so a number where it expects a string is fine either way.
     */
    private fun scalar(s: String): JsonElement = when {
        s == "true" || s == "True" || s == "TRUE" -> JsonPrimitive(true)
        s == "false" || s == "False" || s == "FALSE" -> JsonPrimitive(false)
        s == "~" || s == "null" -> JsonNull
        INT.matches(s) -> JsonPrimitive(s.toLong())
        FLOAT.matches(s) -> JsonPrimitive(s.toDouble())
        else -> JsonPrimitive(s)
    }
}
