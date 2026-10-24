package pro.devsnip.api.core.toolkit

import org.yaml.snakeyaml.DumperOptions
import org.yaml.snakeyaml.LoaderOptions
import org.yaml.snakeyaml.constructor.AbstractConstruct
import org.yaml.snakeyaml.constructor.SafeConstructor
import org.yaml.snakeyaml.nodes.Node
import org.yaml.snakeyaml.nodes.ScalarNode
import org.yaml.snakeyaml.nodes.Tag
import org.yaml.snakeyaml.representer.Representer
import org.yaml.snakeyaml.resolver.Resolver
import java.util.regex.Pattern
import org.yaml.snakeyaml.Yaml as SnakeYaml

/**
 * YAML as the `yaml` npm package (v2) reads and writes it, so OpenAPI
 * documents parse and generated specs print exactly as in VS Code.
 *
 * Parsing uses the YAML 1.2 core schema: `yes`, `on` and dates stay strings,
 * `0o17` is octal and `<<` is an ordinary key. Values come back in the
 * [Js] model (maps, lists, doubles, strings, booleans, null).
 */
object Yaml {
    class YamlError(message: String) : IllegalArgumentException(message)

    /** YAML 1.2 core schema tags only (no timestamps, no 1.1 booleans, no merge keys). */
    private class CoreResolver : Resolver() {
        override fun addImplicitResolvers() {
            addImplicitResolver(Tag.BOOL, Pattern.compile("^(?:true|True|TRUE|false|False|FALSE)$"), "tTfF")
            addImplicitResolver(Tag.INT, Pattern.compile("^(?:[-+]?[0-9]+|0o[0-7]+|0x[0-9a-fA-F]+)$"), "-+0123456789")
            addImplicitResolver(
                Tag.FLOAT,
                Pattern.compile("^(?:[-+]?(?:\\.[0-9]+|[0-9]+(?:\\.[0-9]*)?)(?:[eE][-+]?[0-9]+)?|[-+]?\\.(?:inf|Inf|INF)|\\.(?:nan|NaN|NAN))$"),
                "-+0123456789.",
            )
            addImplicitResolver(Tag.NULL, Pattern.compile("^(?:~|null|Null|NULL)$"), "~nN")
            addImplicitResolver(Tag.NULL, Pattern.compile("^$"), null)
        }
    }

    /** Numbers become JavaScript numbers (doubles), parsed with core-schema rules. */
    private class CoreConstructor(options: LoaderOptions) : SafeConstructor(options) {
        init {
            val number = object : AbstractConstruct() {
                override fun construct(node: Node): Any {
                    val text = (node as ScalarNode).value
                    return when {
                        text.startsWith("0o") -> text.substring(2).toBigInteger(8).toDouble()
                        text.startsWith("0x") -> text.substring(2).toBigInteger(16).toDouble()
                        text.matches(Regex("[-+]?\\.(inf|Inf|INF)")) -> if (text.startsWith("-")) Double.NEGATIVE_INFINITY else Double.POSITIVE_INFINITY
                        text.matches(Regex("\\.(nan|NaN|NAN)")) -> Double.NaN
                        else -> text.toDouble()
                    }
                }
            }
            yamlConstructors[Tag.INT] = number
            yamlConstructors[Tag.FLOAT] = number
        }
    }

    /** `YAML.parse(text, { maxAliasCount: 1000 })`. */
    fun parse(text: String): Any? {
        val options = LoaderOptions().apply {
            maxAliasesForCollections = 1000
            isAllowDuplicateKeys = false
        }
        val yaml = SnakeYaml(CoreConstructor(options), Representer(DumperOptions()), DumperOptions(), options, CoreResolver())
        val loaded = try {
            yaml.load<Any?>(text)
        } catch (e: Exception) {
            throw YamlError(e.message?.trim() ?: e.javaClass.simpleName)
        }
        return toJs(loaded)
    }

    private fun toJs(value: Any?): Any? = when (value) {
        null -> null
        is Map<*, *> -> LinkedHashMap<String, Any?>().apply { value.forEach { (k, v) -> put(if (k == null) "" else Js.string(toJs(k)), toJs(v)) } }
        is List<*> -> value.mapTo(ArrayList()) { toJs(it) }
        is Number -> value.toDouble()
        is Boolean, is String -> value
        is ByteArray -> java.util.Base64.getEncoder().encodeToString(value)
        is Set<*> -> LinkedHashMap<String, Any?>().apply { value.forEach { put(Js.string(toJs(it)), null) } }
        else -> value.toString()
    }

    // ---------------------------------------------------------------- output

    /** `YAML.stringify(value, { lineWidth: 0, aliasDuplicateObjects: false })` for JSON-like values. */
    fun stringify(value: Any?): String {
        val out = StringBuilder()
        when {
            value is Map<*, *> && value.isNotEmpty() -> writeMap(out, value, 0)
            value is List<*> && value.isNotEmpty() -> writeSeq(out, value, 0)
            else -> out.append(scalar(value, 0, implicitKey = false)).append('\n')
        }
        return out.toString()
    }

    private fun writeMap(out: StringBuilder, map: Map<*, *>, indent: Int) {
        @Suppress("UNCHECKED_CAST")
        val entries = Js.entries(map as Map<String, Any?>)
        entries.forEachIndexed { i, (key, value) ->
            if (i > 0) out.append(" ".repeat(indent))
            out.append(plainOrQuoted(key, implicitKey = true)).append(':')
            writeValue(out, value, indent, inSeq = false)
        }
    }

    private fun writeSeq(out: StringBuilder, list: List<*>, indent: Int) {
        list.forEachIndexed { i, item ->
            if (i > 0) out.append(" ".repeat(indent))
            out.append('-')
            writeValue(out, item, indent, inSeq = true)
        }
    }

    /** Writes what follows `key:` or `-`, including the line break. */
    private fun writeValue(out: StringBuilder, value: Any?, indent: Int, inSeq: Boolean) {
        val child = indent + 2
        when {
            value is Map<*, *> && value.isNotEmpty() -> {
                if (inSeq) {
                    out.append(' ')
                    writeMap(out, value, child)
                } else {
                    out.append('\n').append(" ".repeat(child))
                    writeMap(out, value, child)
                }
            }
            value is List<*> && value.isNotEmpty() -> {
                if (inSeq) {
                    out.append(' ')
                    writeSeq(out, value, child)
                } else {
                    out.append('\n').append(" ".repeat(child))
                    writeSeq(out, value, child)
                }
            }
            else -> out.append(' ').append(scalar(value, child, implicitKey = false)).append('\n')
        }
    }

    private fun scalar(value: Any?, indent: Int, implicitKey: Boolean): String = when (value) {
        null -> "null"
        is Boolean -> value.toString()
        is Number -> {
            val d = value.toDouble()
            when {
                d.isNaN() -> ".nan"
                d == Double.POSITIVE_INFINITY -> ".inf"
                d == Double.NEGATIVE_INFINITY -> "-.inf"
                else -> Js.number(d)
            }
        }
        is Map<*, *> -> "{}"
        is List<*> -> "[]"
        is String -> if (!implicitKey && value.contains('\n')) blockOrPlain(value, indent) else plainOrQuoted(value, implicitKey)
        else -> plainOrQuoted(value.toString(), implicitKey)
    }

    private val NOT_PLAIN = Regex("^[\\n\\t ,\\[\\]{}#&*!|>'\"%@`]|^[?-]$|^[?-][ \\t]|[\\n:][ \\t]|[ \\t]\\n|[\\n\\t ]#|[\\n\\t :]$")
    private val CORE_TAGS = listOf(
        Regex("^(?:~|[Nn]ull|NULL)?$"),
        Regex("^(?:[Tt]rue|TRUE|[Ff]alse|FALSE)$"),
        Regex("^0o[0-7]+$"),
        Regex("^[-+]?[0-9]+$"),
        Regex("^0x[0-9a-fA-F]+$"),
        Regex("^(?:[-+]?\\.(?:inf|Inf|INF)|\\.nan|\\.NaN|\\.NAN)$"),
        Regex("^[-+]?(?:\\.[0-9]+|[0-9]+(?:\\.[0-9]*)?)[eE][-+]?[0-9]+$"),
        Regex("^[-+]?(?:\\.[0-9]+|[0-9]+\\.[0-9]*)$"),
    )

    private fun needsQuotes(value: String, implicitKey: Boolean): Boolean =
        value.isEmpty() || (implicitKey && value.contains('\n')) || NOT_PLAIN.containsMatchIn(value) ||
            Regex("(^|\\n)(---|\\.\\.\\.)").containsMatchIn(value) || CORE_TAGS.any { it.matches(value) }

    private fun blockOrPlain(value: String, indent: Int): String {
        // Multi-line values that are not keys become literal block scalars.
        val trailing = value.length - value.trimEnd('\n').length
        val chomp = when (trailing) { 0 -> "-"; 1 -> ""; else -> "+" }
        val body = value.trimEnd('\n')
        val indicator = if (body.startsWith(" ") || body.startsWith("\n")) "2" else ""
        val pad = " ".repeat(indent)
        val lines = (body + "\n".repeat(if (trailing > 1) trailing - 1 else 0)).split("\n").joinToString("\n") { if (it.isEmpty()) "" else pad + it }
        return "|$indicator$chomp\n$lines".removeSuffix("\n").let { if (trailing > 1) it + "\n" else it }
    }

    private fun plainOrQuoted(value: String, implicitKey: Boolean): String {
        if (!needsQuotes(value, implicitKey)) return value
        val hasDouble = value.contains('"')
        val hasSingle = value.contains('\'')
        return if (hasDouble && !hasSingle && !(implicitKey && value.contains('\n'))) "'" + value.replace("'", "''") + "'" else doubleQuoted(value)
    }

    private fun doubleQuoted(value: String): String {
        val json = Js.quote(value)
        return json.replace(Regex("\\\\u([0-9a-fA-F]{4})")) { m ->
            when (val code = m.groupValues[1].lowercase()) {
                "0000" -> "\\0"
                "0007" -> "\\a"
                "000b" -> "\\v"
                "001b" -> "\\e"
                "0085" -> "\\N"
                "00a0" -> "\\_"
                "2028" -> "\\L"
                "2029" -> "\\P"
                else -> if (code.startsWith("00")) "\\x" + code.substring(2) else m.value
            }
        }
    }
}
