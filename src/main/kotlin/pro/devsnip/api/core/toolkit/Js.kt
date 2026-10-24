package pro.devsnip.api.core.toolkit

import com.google.gson.Strictness
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import java.io.ByteArrayOutputStream
import java.io.StringReader
import java.math.BigDecimal
import java.math.RoundingMode
import java.net.IDN
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction

/**
 * The few JavaScript semantics the ported tools depend on, so generated code
 * and reports match the VS Code extension character for character:
 * `JSON.stringify` / `JSON.parse`, `String(number)`, `toFixed`,
 * `encodeURIComponent`, object key order and the WHATWG URL parser.
 *
 * JSON values are modelled as plain Kotlin values: `Map<String, Any?>`
 * (insertion-ordered), `List<Any?>`, `String`, `Double`, `Boolean` and `null`.
 */
object Js {
    class JsonSyntaxError(message: String) : IllegalArgumentException(message)
    class UriError(message: String) : IllegalArgumentException(message)

    // ------------------------------------------------------------------ JSON

    /** `JSON.parse`: strict JSON into maps, lists, doubles, strings, booleans and null. */
    fun parseJson(text: String): Any? {
        val reader = JsonReader(StringReader(text))
        reader.setStrictness(Strictness.STRICT)
        try {
            if (text.isBlank()) throw JsonSyntaxError("Unexpected end of JSON input")
            val value = read(reader)
            if (reader.peek() != JsonToken.END_DOCUMENT) throw JsonSyntaxError("Unexpected non-whitespace character after JSON")
            return value
        } catch (e: JsonSyntaxError) {
            throw e
        } catch (e: Exception) {
            throw JsonSyntaxError(e.message?.substringBefore("\nSee ")?.substringBefore(" See https") ?: "Invalid JSON")
        }
    }

    private fun read(reader: JsonReader): Any? = when (reader.peek()) {
        JsonToken.BEGIN_OBJECT -> {
            val map = LinkedHashMap<String, Any?>()
            reader.beginObject()
            while (reader.hasNext()) {
                val key = reader.nextName()
                map[key] = read(reader)
            }
            reader.endObject()
            map
        }
        JsonToken.BEGIN_ARRAY -> {
            val list = ArrayList<Any?>()
            reader.beginArray()
            while (reader.hasNext()) list += read(reader)
            reader.endArray()
            list
        }
        JsonToken.STRING -> reader.nextString()
        JsonToken.NUMBER -> reader.nextString().toDouble()
        JsonToken.BOOLEAN -> reader.nextBoolean()
        JsonToken.NULL -> reader.nextNull().let { null }
        else -> throw JsonSyntaxError("Unexpected token")
    }

    /** `JSON.parse` that returns null instead of throwing. */
    fun parseJsonOrNull(text: String): Any? = try {
        parseJson(text)
    } catch (_: JsonSyntaxError) {
        null
    }

    fun isJson(text: String): Boolean = try {
        parseJson(text)
        true
    } catch (_: JsonSyntaxError) {
        false
    }

    /** `JSON.stringify(value, null, indent)`. */
    fun stringify(value: Any?, indent: String = ""): String = buildString { write(this, value, indent, "") }

    private fun write(out: StringBuilder, value: Any?, indent: String, current: String) {
        when (value) {
            null -> out.append("null")
            is String -> out.append(quote(value))
            is Boolean -> out.append(value)
            is Number -> {
                val d = value.toDouble()
                out.append(if (d.isFinite()) number(d) else "null")
            }
            is Map<*, *> -> {
                @Suppress("UNCHECKED_CAST")
                val map = value as Map<String, Any?>
                val keys = keys(map)
                if (keys.isEmpty()) {
                    out.append("{}")
                    return
                }
                val inner = current + indent
                out.append('{')
                keys.forEachIndexed { i, key ->
                    if (i > 0) out.append(',')
                    if (indent.isNotEmpty()) out.append('\n').append(inner)
                    out.append(quote(key)).append(if (indent.isNotEmpty()) ": " else ":")
                    write(out, map[key], indent, inner)
                }
                if (indent.isNotEmpty()) out.append('\n').append(current)
                out.append('}')
            }
            is List<*> -> {
                if (value.isEmpty()) {
                    out.append("[]")
                    return
                }
                val inner = current + indent
                out.append('[')
                value.forEachIndexed { i, item ->
                    if (i > 0) out.append(',')
                    if (indent.isNotEmpty()) out.append('\n').append(inner)
                    write(out, item, indent, inner)
                }
                if (indent.isNotEmpty()) out.append('\n').append(current)
                out.append(']')
            }
            else -> out.append(quote(value.toString()))
        }
    }

    /** A JSON string literal, escaped exactly as `JSON.stringify` does. */
    fun quote(s: String): String = buildString(s.length + 2) {
        append('"')
        var i = 0
        while (i < s.length) {
            val c = s[i]
            when {
                c == '"' -> append("\\\"")
                c == '\\' -> append("\\\\")
                c == '\b' -> append("\\b")
                c == '\u000C' -> append("\\f")
                c == '\n' -> append("\\n")
                c == '\r' -> append("\\r")
                c == '\t' -> append("\\t")
                c < ' ' -> append("\\u%04x".format(c.code))
                Character.isHighSurrogate(c) && i + 1 < s.length && Character.isLowSurrogate(s[i + 1]) -> {
                    append(c).append(s[i + 1])
                    i++
                }
                Character.isSurrogate(c) -> append("\\u%04x".format(c.code))
                else -> append(c)
            }
            i++
        }
        append('"')
    }

    /** Own enumerable keys in JavaScript order: integer-like keys ascending, then insertion order. */
    fun keys(map: Map<String, *>): List<String> {
        val (indices, names) = map.keys.partition(::isArrayIndex)
        return indices.sortedBy { it.toLong() } + names
    }

    fun entries(map: Map<String, *>): List<Pair<String, Any?>> = keys(map).map { it to map[it] }

    private fun isArrayIndex(key: String) = key.matches(Regex("0|[1-9][0-9]{0,9}")) && key.toLong() < 4294967295L

    // --------------------------------------------------------------- numbers

    /** `String(n)` for a JavaScript number (ECMAScript Number::toString). */
    fun number(d: Double): String {
        if (d.isNaN()) return "NaN"
        if (d == Double.POSITIVE_INFINITY) return "Infinity"
        if (d == Double.NEGATIVE_INFINITY) return "-Infinity"
        if (d == 0.0) return "0"
        if (d < 0) return "-" + number(-d)
        val decimal = BigDecimal(d.toString()).stripTrailingZeros()
        val digits = decimal.unscaledValue().toString()
        val k = digits.length
        val n = k - decimal.scale()
        return when {
            n in k..21 -> digits + "0".repeat(n - k)
            n in 1..21 -> digits.substring(0, n) + "." + digits.substring(n)
            n in -5..0 -> "0." + "0".repeat(-n) + digits
            else -> {
                val e = n - 1
                val sign = if (e >= 0) "+" else "-"
                (if (k == 1) digits else digits[0] + "." + digits.substring(1)) + "e" + sign + kotlin.math.abs(e)
            }
        }
    }

    /** `Number(string)`. */
    fun toNumber(text: String): Double {
        val s = text.trim { it.isWhitespace() || it == '﻿' || it == ' ' }
        if (s.isEmpty()) return 0.0
        when (s) {
            "Infinity", "+Infinity" -> return Double.POSITIVE_INFINITY
            "-Infinity" -> return Double.NEGATIVE_INFINITY
        }
        val radix = Regex("^0([xXoObB])([0-9a-fA-F]+)$").find(s)
        if (radix != null) {
            val base = when (radix.groupValues[1].lowercase()) { "x" -> 16; "o" -> 8; else -> 2 }
            return radix.groupValues[2].toBigIntegerOrNull(base)?.toDouble() ?: Double.NaN
        }
        if (!s.matches(Regex("^[+-]?(\\d+\\.?\\d*|\\.\\d+)([eE][+-]?\\d+)?$"))) return Double.NaN
        return s.toDouble()
    }

    /** `Number.prototype.toFixed`. */
    fun toFixed(d: Double, digits: Int): String {
        if (!d.isFinite() || kotlin.math.abs(d) >= 1e21) return number(d)
        val negative = d < 0
        val fixed = BigDecimal(kotlin.math.abs(d)).setScale(digits, RoundingMode.HALF_UP).toPlainString()
        return if (negative && fixed.any { it in '1'..'9' }) "-$fixed" else fixed
    }

    /** `String(value)` for form values. */
    fun string(value: Any?): String = when (value) {
        null -> "null"
        is String -> value
        is Double -> number(value)
        is Float -> number(value.toDouble())
        is Number -> number(value.toDouble())
        is Boolean -> value.toString()
        is List<*> -> value.joinToString(",") { if (it == null) "" else string(it) }
        is Map<*, *> -> "[object Object]"
        else -> value.toString()
    }

    /** JavaScript truthiness for parsed JSON values. */
    fun truthy(value: Any?): Boolean = when (value) {
        null -> false
        is Boolean -> value
        is Number -> value.toDouble().let { it != 0.0 && !it.isNaN() }
        is String -> value.isNotEmpty()
        else -> true
    }

    // ------------------------------------------------------------------ URIs

    private const val URI_UNRESERVED = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_.!~*'()"

    /** `encodeURIComponent`. */
    fun encodeURIComponent(s: String): String = percentEncode(s) { it.code < 128 && URI_UNRESERVED.indexOf(it) >= 0 }

    /** `decodeURIComponent`; throws [UriError] on a malformed sequence, as JavaScript does. */
    fun decodeURIComponent(s: String): String {
        if (!s.contains('%')) return s
        val out = StringBuilder()
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c != '%') {
                out.append(c)
                i++
                continue
            }
            val bytes = ByteArrayOutputStream()
            while (i < s.length && s[i] == '%') {
                val hex = s.substring(i + 1, minOf(i + 3, s.length))
                if (hex.length != 2 || !hex.all { it.isDigit() || it.lowercaseChar() in 'a'..'f' }) throw UriError("URI malformed")
                bytes.write(hex.toInt(16))
                i += 3
            }
            out.append(decodeUtf8Strict(bytes.toByteArray()) ?: throw UriError("URI malformed"))
        }
        return out.toString()
    }

    private fun decodeUtf8Strict(bytes: ByteArray): String? = try {
        Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes)).toString()
    } catch (_: CharacterCodingException) {
        null
    }

    /** Percent-encodes every code point for which [keep] is false, as UTF-8 (`%XX`, upper case). */
    fun percentEncode(s: String, keep: (Char) -> Boolean): String {
        val out = StringBuilder(s.length)
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c.code < 128 && keep(c)) {
                out.append(c)
                i++
                continue
            }
            val end = if (Character.isHighSurrogate(c) && i + 1 < s.length && Character.isLowSurrogate(s[i + 1])) i + 2 else i + 1
            val chunk = s.substring(i, end).let { if (it.length == 1 && Character.isSurrogate(it[0])) "�" else it }
            for (b in chunk.toByteArray(Charsets.UTF_8)) out.append('%').append("%02X".format(b.toInt() and 0xFF))
            i = end
        }
        return out.toString()
    }

    /** Decodes `%XX` sequences leniently (invalid ones stay as typed), as URLSearchParams does. */
    fun percentDecodeLenient(s: String): String {
        if (!s.contains('%')) return s
        val bytes = ByteArrayOutputStream()
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '%' && i + 2 < s.length && s[i + 1].isHex() && s[i + 2].isHex()) {
                bytes.write(s.substring(i + 1, i + 3).toInt(16))
                i += 3
            } else {
                val end = if (Character.isHighSurrogate(c) && i + 1 < s.length) i + 2 else i + 1
                bytes.write(s.substring(i, end).toByteArray(Charsets.UTF_8))
                i = end
            }
        }
        return String(bytes.toByteArray(), Charsets.UTF_8)
    }

    private fun Char.isHex() = isDigit() || lowercaseChar() in 'a'..'f'

    /** `application/x-www-form-urlencoded` parsing, as `URLSearchParams` does it. */
    fun parseSearchParams(query: String): List<Pair<String, String>> =
        query.removePrefix("?").split('&').filter { it.isNotEmpty() }.map { pair ->
            val eq = pair.indexOf('=')
            val name = if (eq < 0) pair else pair.substring(0, eq)
            val value = if (eq < 0) "" else pair.substring(eq + 1)
            percentDecodeLenient(name.replace('+', ' ')) to percentDecodeLenient(value.replace('+', ' '))
        }
}

/**
 * The parts of the WHATWG URL parser (`new URL(...)` in Node) the tools use:
 * validation, host normalisation, default ports, path/query encoding,
 * `origin` and `searchParams`.
 */
class JsUrl private constructor(
    val protocol: String,
    val username: String,
    val password: String,
    val hostname: String,
    val port: String,
    val pathname: String,
    private val query: String?,
    private val fragment: String?,
    private val special: Boolean,
) {
    val host: String get() = if (port.isEmpty()) hostname else "$hostname:$port"
    val search: String get() = if (query.isNullOrEmpty()) "" else "?$query"
    val hash: String get() = if (fragment.isNullOrEmpty()) "" else "#$fragment"
    val origin: String get() = if (special && protocol != "file:") "$protocol//$host" else "null"
    val searchParams: List<Pair<String, String>> get() = Js.parseSearchParams(query.orEmpty())
    val href: String
        get() = buildString {
            append(protocol)
            if (hostname.isNotEmpty() || special) {
                append("//")
                if (username.isNotEmpty() || password.isNotEmpty()) {
                    append(username)
                    if (password.isNotEmpty()) append(':').append(password)
                    append('@')
                }
                append(host)
            }
            append(pathname)
            if (query != null) append('?').append(query)
            if (fragment != null) append('#').append(fragment)
        }

    override fun toString() = href

    companion object {
        private val SPECIAL = mapOf("http" to "80", "https" to "443", "ws" to "80", "wss" to "443", "ftp" to "21", "file" to "")

        private fun inSet(c: Char, extra: String) = c.code in 0x21..0x7E && extra.indexOf(c) < 0
        private val fragmentKeep: (Char) -> Boolean = { inSet(it, " \"<>`") }
        private fun queryKeep(special: Boolean): (Char) -> Boolean = { inSet(it, if (special) " \"#<>'" else " \"#<>") }
        private val pathKeep: (Char) -> Boolean = { inSet(it, " \"#<>?^`{}") }
        private val userinfoKeep: (Char) -> Boolean = { inSet(it, " \"#<>?^`{}/:;=@[\\]|") }
        private val c0Keep: (Char) -> Boolean = { it.code in 0x20..0x7E }

        /** `new URL(input)`; throws [IllegalArgumentException] when Node would throw "Invalid URL". */
        fun parse(input: String): JsUrl = tryParse(input) ?: throw IllegalArgumentException("Invalid URL")

        fun tryParse(input: String): JsUrl? {
            val cleaned = input.trim { it.code <= 0x20 }.replace(Regex("[\t\n\r]"), "")
            val m = Regex("^([A-Za-z][A-Za-z0-9+.\\-]*):(.*)$", RegexOption.DOT_MATCHES_ALL).find(cleaned) ?: return null
            val scheme = m.groupValues[1].lowercase()
            var rest = m.groupValues[2]
            val special = scheme in SPECIAL

            var fragment: String? = null
            rest.indexOf('#').takeIf { it >= 0 }?.let {
                fragment = Js.percentEncode(rest.substring(it + 1), fragmentKeep)
                rest = rest.substring(0, it)
            }
            var query: String? = null
            rest.indexOf('?').takeIf { it >= 0 }?.let {
                query = Js.percentEncode(rest.substring(it + 1), queryKeep(special))
                rest = rest.substring(0, it)
            }

            if (!special && !rest.startsWith("//")) {
                // Opaque path, e.g. mailto:a@b.io or localhost:3000/x.
                return JsUrl("$scheme:", "", "", "", "", Js.percentEncode(rest, c0Keep), query, fragment, false)
            }
            rest = if (special) rest.trimStart('/', '\\') else rest.removePrefix("//")
            val authorityEnd = rest.indexOfFirst { it == '/' || (special && it == '\\') }.let { if (it < 0) rest.length else it }
            val authority = rest.substring(0, authorityEnd)
            val rawPath = rest.substring(authorityEnd)

            var username = ""
            var password = ""
            var hostPort = authority
            authority.lastIndexOf('@').takeIf { it >= 0 }?.let { at ->
                val userinfo = authority.substring(0, at)
                hostPort = authority.substring(at + 1)
                val colon = userinfo.indexOf(':')
                username = Js.percentEncode(if (colon < 0) userinfo else userinfo.substring(0, colon), userinfoKeep)
                password = if (colon < 0) "" else Js.percentEncode(userinfo.substring(colon + 1), userinfoKeep)
            }

            val hostEnd = if (hostPort.startsWith("[")) hostPort.indexOf(']').let { if (it < 0) return null else it + 1 } else hostPort.indexOf(':').let { if (it < 0) hostPort.length else it }
            val rawHost = hostPort.substring(0, hostEnd)
            val portText = hostPort.substring(hostEnd).removePrefix(":")
            if (hostEnd < hostPort.length && hostPort[hostEnd] != ':') return null
            if (portText.isNotEmpty() && (!portText.all { it.isDigit() } || portText.toBigInteger() > 65535.toBigInteger())) return null
            var port = if (portText.isEmpty()) "" else portText.toBigInteger().toString()
            if (special && port == SPECIAL[scheme]) port = ""

            val hostname = if (special) specialHost(rawHost, scheme) ?: return null else opaqueHost(rawHost) ?: return null
            if (hostname.isEmpty() && (username.isNotEmpty() || password.isNotEmpty() || port.isNotEmpty())) return null

            val pathname = if (special) normalisePath(rawPath.replace('\\', '/')) else Js.percentEncode(rawPath, pathKeep)
            return JsUrl("$scheme:", username, password, hostname, port, pathname, query, fragment, special)
        }

        private fun specialHost(raw: String, scheme: String): String? {
            if (raw.isEmpty()) return if (scheme == "file") "" else null
            if (raw.startsWith("[")) return if (raw.endsWith("]") && raw.drop(1).dropLast(1).all { it.isLetterOrDigit() || it == ':' || it == '.' }) raw.lowercase() else null
            val decoded = Js.percentDecodeLenient(raw)
            if (decoded.any { it.code <= 0x20 || it == '\u007F' || it in "#%/:<>?@[\\]^|" }) return null
            val ascii = try {
                IDN.toASCII(decoded, IDN.ALLOW_UNASSIGNED)
            } catch (_: IllegalArgumentException) {
                return null
            }
            return ascii.lowercase().takeIf { it.isNotEmpty() }
        }

        private fun opaqueHost(raw: String): String? {
            if (raw.startsWith("[")) return if (raw.endsWith("]")) raw.lowercase() else null
            if (raw.any { it.code <= 0x20 || it in "#/:<>?@[\\]^|" }) return null
            return Js.percentEncode(raw, c0Keep)
        }

        private fun normalisePath(path: String): String {
            val segments = path.split('/').drop(1)
            val out = ArrayList<String>()
            segments.forEachIndexed { index, segment ->
                val last = index == segments.size - 1
                when (segment.lowercase()) {
                    ".", "%2e" -> if (last) out += ""
                    "..", ".%2e", "%2e.", "%2e%2e" -> {
                        if (out.isNotEmpty()) out.removeAt(out.size - 1)
                        if (last) out += ""
                    }
                    else -> out += Js.percentEncode(segment, pathKeep)
                }
            }
            return "/" + out.joinToString("/")
        }
    }
}
