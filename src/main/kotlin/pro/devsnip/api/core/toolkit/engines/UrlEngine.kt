package pro.devsnip.api.core.toolkit.engines

import pro.devsnip.api.core.toolkit.Js
import pro.devsnip.api.core.toolkit.JsUrl
import pro.devsnip.api.core.toolkit.ToolInputError

/** URLs and query strings. Port of the URL part of `src/toolkits/engines/web-http.ts`. */
object UrlEngine {
    data class UrlReport(val parts: List<List<String>>, val params: List<List<String>>, val warnings: List<String>)

    fun safeDecode(s: String): String = try {
        Js.decodeURIComponent(s.replace("+", " "))
    } catch (_: Js.UriError) {
        s
    }

    fun parseUrlReport(input: String): UrlReport {
        val raw = input.trim()
        if (raw.isEmpty()) throw ToolInputError("Enter a URL.")
        val url = JsUrl.tryParse(raw) ?: JsUrl.tryParse("https://$raw") ?: throw ToolInputError("\"$raw\" is not a valid URL.")
        val warnings = mutableListOf<String>()
        val isDb = Regex("^(postgres(ql)?|mysql|mariadb|mongodb(\\+srv)?|redis|rediss|amqps?|sqlserver|mssql):$").matches(url.protocol)
        val parts = mutableListOf(listOf("Protocol", url.protocol.removeSuffix(":")))
        if (url.username.isNotEmpty()) parts += listOf("Username", safeDecode(url.username))
        if (url.password.isNotEmpty()) parts += listOf("Password", "•".repeat(minOf(8, url.password.length)) + " (hidden)")
        parts += listOf("Host", url.hostname)
        parts += listOf("Port", url.port.ifEmpty { "${defaultPort(url.protocol) ?: "-"} (default)" })
        parts += listOf("Path", safeDecode(url.pathname))
        parts += listOf("Query", url.search)
        parts += listOf("Fragment", url.hash)
        parts += listOf("Origin", if (url.origin == "null") "-" else url.origin)
        val segments = url.pathname.split("/").filter { it.isNotEmpty() }
        if (segments.isNotEmpty()) parts += listOf("Path segments", segments.joinToString("  ›  ") { safeDecode(it) })
        // URLSearchParams decodes + as a space, which is right for form-encoded queries.
        val rawQuery = if (raw.contains("?")) raw.substring(raw.indexOf("?") + 1).split("#")[0] else ""
        val params = rawQuery.split("&").filter { it.isNotEmpty() }.map { pair ->
            val eq = pair.indexOf('=')
            val k = if (eq < 0) pair else pair.substring(0, eq)
            val v = if (eq < 0) "" else pair.substring(eq + 1)
            listOf(safeDecode(k), safeDecode(v), v)
        }
        if (url.password.isNotEmpty() && !isDb) warnings += "The URL contains a password. Browsers strip credentials from URLs and they end up in logs; send them in an Authorization header."
        if (url.password.isNotEmpty() && isDb && Regex("[@:/?#\\[\\]%]").containsMatchIn(safeDecode(url.password)) && !Regex("%[0-9A-F]{2}", RegexOption.IGNORE_CASE).containsMatchIn(url.password)) {
            warnings += "Special characters in a database password must be percent-encoded (e.g. @ → %40)."
        }
        if (url.protocol == "http:" && !Regex("^(localhost|127\\.|10\\.0\\.2\\.2|0\\.0\\.0\\.0|\\[::1\\])").containsMatchIn(url.hostname)) {
            warnings += "http:// is not encrypted. Mobile apps block it by default (Android cleartext policy, iOS ATS)."
        }
        if (Regex("%25[0-9A-F]{2}", RegexOption.IGNORE_CASE).containsMatchIn(raw)) warnings += "The URL contains %25xx sequences: it was probably encoded twice."
        if (raw.any { it.isJsWhitespace() }) warnings += "The URL contains spaces; encode them as %20 (or + inside a form-encoded query)."
        val keys = params.map { it[0] }
        val dupes = keys.filterIndexed { i, k -> keys.indexOf(k) != i }
        if (dupes.isNotEmpty()) warnings += "Repeated parameters: ${dupes.distinct().joinToString(", ")}. Frameworks read these differently (first value, last value or an array)."
        for ((k, v) in params.map { it[0] to it[1] }) {
            if (Regex("^(token|access_token|api_key|apikey|key|secret|password|client_secret|sig|signature)$", RegexOption.IGNORE_CASE).matches(k) && v.isNotEmpty()) {
                warnings += "\"$k\" in the query string ends up in server logs, browser history and Referer headers; prefer a header."
            }
        }
        if (raw.length > 2000) warnings += "The URL is ${raw.length} characters; some proxies and browsers reject URLs over ~2,000-8,000 characters."
        if (url.hostname != url.hostname.lowercase() || url.hostname.contains("xn--")) warnings += "Host as sent: ${url.hostname} (internationalised names are sent in punycode)."
        return UrlReport(parts, params, warnings)
    }

    private fun defaultPort(protocol: String): Int? = mapOf(
        "http:" to 80, "https:" to 443, "ws:" to 80, "wss:" to 443, "ftp:" to 21, "postgres:" to 5432, "postgresql:" to 5432,
        "mysql:" to 3306, "mongodb:" to 27017, "redis:" to 6379, "rediss:" to 6380, "amqp:" to 5672, "amqps:" to 5671,
    )[protocol]

    /** encodeURIComponent plus the characters RFC 3986 reserves. */
    fun enc(s: String): String = Js.encodeURIComponent(s).replace(Regex("[!'()*]")) { "%" + it.value[0].code.toString(16).uppercase() }

    /** Builds a URL from a base and "key=value" lines (raw values, encoded here). Lines starting with "-" remove a parameter. */
    fun buildUrl(base: String, lines: String): String {
        val url = JsUrl.tryParse(base.trim()) ?: throw ToolInputError("\"$base\" is not a valid base URL (include https://).")
        var result = url.searchParams.toMutableList()
        for (line in lines.split(Regex("\\r?\\n")).map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }) {
            if (line.startsWith("-")) {
                val key = line.substring(1).trim()
                result = result.filter { it.first != key }.toMutableList()
                continue
            }
            val eq = line.indexOf('=')
            val colon = line.indexOf(':')
            val at = if (eq >= 0) eq else colon
            if (at <= 0) throw ToolInputError("\"$line\" should be key=value.")
            result += line.substring(0, at).trim() to line.substring(at + 1).trim()
        }
        val query = result.joinToString("&") { (k, v) -> "${enc(k)}=${enc(v)}" }
        val origin = if (url.origin == "null") "${url.protocol}//${url.host}" else url.origin
        return "$origin${url.pathname}${if (query.isNotEmpty()) "?$query" else ""}${url.hash}"
    }

    /** Query string → nested object, the way qs / Express / Rails / PHP read brackets. */
    fun queryToObject(query: String): MutableMap<String, Any?> {
        val q = query.trim().replace(Regex("^.*?\\?"), "").replace(Regex("#.*$"), "")
        val root = LinkedHashMap<String, Any?>()
        for (pair in q.split("&").filter { it.isNotEmpty() }) {
            val eq = pair.indexOf('=')
            val key = safeDecode(if (eq < 0) pair else pair.substring(0, eq))
            val value = if (eq < 0) "" else safeDecode(pair.substring(eq + 1))
            val path = key.replace("]", "").split("[")
            var node: Any = root
            for (i in path.indices) {
                val seg = path[i]
                val last = i == path.size - 1
                val nextIsArray = !last && (path[i + 1] == "" || path[i + 1].matches(Regex("^\\d+$")))
                if (node is JsArray) {
                    // arr[Number(seg)]: a non-index key is a plain property, invisible to JSON.stringify.
                    val number = if (seg == "") node.size.toDouble() else Js.toNumber(seg)
                    val idx = number.takeIf { it >= 0 && it == Math.floor(it) && it < 100_000 }?.toInt()
                    if (idx == null) {
                        if (last) break
                        node = LinkedHashMap<String, Any?>()
                        continue
                    }
                    if (last) {
                        node[idx] = value
                    } else {
                        if (node.getOrNull(idx) == null) node[idx] = if (nextIsArray) JsArray() else LinkedHashMap<String, Any?>()
                        node = node[idx]!!
                    }
                } else {
                    @Suppress("UNCHECKED_CAST")
                    val map = node as MutableMap<String, Any?>
                    if (last) {
                        if (map.containsKey(seg)) {
                            val existing = map[seg]
                            map[seg] = if (existing is JsArray) JsArray(existing + value) else JsArray(listOf(existing, value))
                        } else {
                            map[seg] = value
                        }
                    } else {
                        val existing = map[seg]
                        if (existing !is MutableMap<*, *> && existing !is JsArray) map[seg] = if (nextIsArray) JsArray() else LinkedHashMap<String, Any?>()
                        node = map[seg]!!
                    }
                }
            }
        }
        return root
    }

    /**
     * A JavaScript array that may have holes (`a[5] = x` on an empty array),
     * serialised by JSON.stringify with `null` in the holes.
     */
    class JsArray(initial: List<Any?> = emptyList()) : ArrayList<Any?>(initial) {
        override fun set(index: Int, element: Any?): Any? {
            while (size <= index) add(null)
            return super.set(index, element)
        }
    }

    fun objectToQuery(value: Any?, format: String): String {
        if (value !is Map<*, *>) throw ToolInputError("Enter a JSON object, e.g. {\"page\": 2, \"tags\": [\"a\", \"b\"]}.")
        val out = mutableListOf<String>()
        fun isObject(x: Any?) = x is Map<*, *> || x is List<*> || x == null
        fun walk(v: Any?, prefix: String) {
            if (v == null) {
                out += "${enc(prefix)}="
                return
            }
            if (v is List<*>) {
                if (format == "comma" && v.none { isObject(it) }) {
                    out += "${enc(prefix)}=${v.joinToString(",") { enc(Js.string(it)) }}"
                    return
                }
                v.forEachIndexed { i, item ->
                    walk(item, if (format == "repeat") prefix else if (format == "indices" || isObject(item)) "$prefix[$i]" else "$prefix[]")
                }
                return
            }
            if (v is Map<*, *>) {
                @Suppress("UNCHECKED_CAST")
                for ((k, x) in Js.entries(v as Map<String, Any?>)) walk(x, if (prefix.isNotEmpty()) "$prefix[$k]" else k)
                return
            }
            out += "${enc(prefix)}=${enc(Js.string(v))}"
        }
        walk(value, "")
        return out.joinToString("&").replace("%5B", "[").replace("%5D", "]")
    }
}
