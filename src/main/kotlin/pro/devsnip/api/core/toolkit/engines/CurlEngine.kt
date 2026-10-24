package pro.devsnip.api.core.toolkit.engines

import pro.devsnip.api.core.toolkit.Js
import pro.devsnip.api.core.toolkit.JsUrl
import pro.devsnip.api.core.toolkit.ToolInputError
import java.util.Base64

/**
 * cURL → code. Port of `src/toolkits/engines/web-curl.ts`: the parser
 * understands what browsers' "Copy as cURL" and API docs produce (bash and
 * cmd quoting, line continuations, combined short flags, --data variants,
 * -F, -u, -b, -G, --json); generators emit idiomatic code for the HTTP
 * client of each stack, web and mobile.
 */
data class FormPart(val name: String, val value: String, val file: Boolean)

data class BasicAuth(val user: String, val password: String)

class CurlRequest {
    var method = ""
    var url = ""
    var headers: MutableList<Pair<String, String>> = mutableListOf()
    var body: String? = null

    /** json: body is valid JSON; form: urlencoded; multipart: see [form]; raw: anything else. */
    var bodyKind = "none"
    val form = mutableListOf<FormPart>()
    var basicAuth: BasicAuth? = null
    var insecure = false
    var followRedirects = false
    var compressed = false
    var timeoutSeconds: Double? = null
    var proxy: String? = null
    val ignored = mutableListOf<String>()
}

data class CurlTarget(val id: String, val label: String, val language: String, val file: String)

object CurlEngine {
    private const val D = "$"

    val TARGETS = listOf(
        CurlTarget("fetch", "JavaScript fetch (browser, React, React Native)", "javascript", "request.js"),
        CurlTarget("axios", "Axios", "javascript", "request.js"),
        CurlTarget("node-fetch", "Node.js 18+ (fetch, TypeScript)", "typescript", "request.ts"),
        CurlTarget("python", "Python requests", "python", "request.py"),
        CurlTarget("go", "Go net/http", "go", "main.go"),
        CurlTarget("php", "PHP (curl)", "php", "request.php"),
        CurlTarget("java", "Java HttpClient", "java", "Request.java"),
        CurlTarget("dart-http", "Dart / Flutter (http)", "dart", "request.dart"),
        CurlTarget("dart-dio", "Dart / Flutter (dio)", "dart", "request.dart"),
        CurlTarget("kotlin-okhttp", "Kotlin / Android (OkHttp)", "kotlin", "Request.kt"),
        CurlTarget("swift", "Swift / iOS (URLSession)", "swift", "Request.swift"),
        CurlTarget("http", "Raw HTTP request", "http", "request.http"),
        CurlTarget("httpie", "HTTPie", "shell", "request.sh"),
    )

    /** Splits a shell command line the way bash would (plus Windows cmd ^ continuations). */
    fun tokenizeShell(input: String): List<String> {
        val text = input
            .replace(Regex("\\\\\\r?\\n"), " ")
            .replace(Regex("\\^\\r?\\n"), " ")
            .replace(Regex("`\\r?\\n"), " ")
            .replace(Regex("\\r?\\n"), " ")
        val tokens = mutableListOf<String>()
        val current = StringBuilder()
        var inToken = false
        var i = 0
        fun at(index: Int) = text.getOrNull(index)
        while (i < text.length) {
            val ch = text[i]
            if (ch.isJsWhitespace()) {
                if (inToken) {
                    tokens += current.toString()
                    current.clear()
                    inToken = false
                }
                i++
                continue
            }
            inToken = true
            if (ch == '\'') {
                val end = text.indexOf('\'', i + 1)
                if (end < 0) throw ToolInputError("Unclosed single quote in the command.")
                current.append(text, i + 1, end)
                i = end + 1
            } else if (ch == '$' && at(i + 1) == '\'') {
                // ANSI-C quoting: $'...\n...'
                var j = i + 2
                val out = StringBuilder()
                while (j < text.length && text[j] != '\'') {
                    if (text[j] == '\\' && j + 1 < text.length) {
                        val n = text[j + 1]
                        val hex2 = text.substring(minOf(j + 2, text.length), minOf(j + 4, text.length))
                        val hex4 = text.substring(minOf(j + 2, text.length), minOf(j + 6, text.length))
                        if (n == 'x' && hex2.matches(Regex("^[0-9a-fA-F]{2}$"))) {
                            out.append(hex2.toInt(16).toChar())
                            j += 4
                            continue
                        }
                        if (n == 'u' && hex4.matches(Regex("^[0-9a-fA-F]{4}$"))) {
                            out.append(hex4.toInt(16).toChar())
                            j += 6
                            continue
                        }
                        out.append(
                            when (n) {
                                'n' -> '\n'; 't' -> '\t'; 'r' -> '\r'; '\\' -> '\\'; '\'' -> '\''; '"' -> '"'; '0' -> '\u0000'
                                else -> n
                            },
                        )
                        j += 2
                    } else {
                        out.append(text[j++])
                    }
                }
                if (j >= text.length) throw ToolInputError("Unclosed $D'...' quote in the command.")
                current.append(out)
                i = j + 1
            } else if (ch == '"') {
                var j = i + 1
                while (j < text.length && text[j] != '"') {
                    if (text[j] == '\\' && (at(j + 1)?.let { it in "\"\\$`" } == true)) {
                        current.append(text[j + 1])
                        j += 2
                    } else if (text[j] == '^' && at(j + 1) == '"') {
                        current.append('"') // cmd escaping
                        j += 2
                    } else {
                        current.append(text[j++])
                    }
                }
                if (j >= text.length) throw ToolInputError("Unclosed double quote in the command.")
                i = j + 1
            } else if (ch == '\\' && i + 1 < text.length) {
                current.append(text[i + 1])
                i += 2
            } else if (ch == '^' && i + 1 < text.length && text[i + 1] in "^&|<>\"") {
                current.append(text[i + 1]) // cmd escape
                i += 2
            } else {
                current.append(ch)
                i++
            }
        }
        if (inToken) tokens += current.toString()
        return tokens
    }

    private val WITH_VALUE = setOf(
        "-X", "--request", "-H", "--header", "-d", "--data", "--data-raw", "--data-binary", "--data-ascii", "--data-urlencode", "--json",
        "-F", "--form", "--form-string", "-u", "--user", "-b", "--cookie", "-A", "--user-agent", "-e", "--referer", "--url", "-m",
        "--max-time", "--connect-timeout", "-x", "--proxy", "-o", "--output", "-w", "--write-out", "--retry", "-c", "--cookie-jar",
        "--cacert", "--cert", "--key", "-T", "--upload-file", "--resolve", "--limit-rate", "-r", "--range", "-z", "--time-cond",
        "--oauth2-bearer", "-K", "--config", "--max-redirs", "--interface", "-y", "--speed-time", "-Y", "--speed-limit", "-D", "--dump-header",
    )
    private val SHORT_WITH_VALUE = setOf('X', 'H', 'd', 'F', 'u', 'b', 'A', 'e', 'm', 'x', 'o', 'w', 'c', 'T', 'r', 'z', 'K', 'y', 'Y', 'D')
    private val QUIET_FLAGS = setOf(
        "-s", "--silent", "-S", "--show-error", "-v", "--verbose", "-i", "--include", "-f", "--fail", "--fail-with-body", "-#",
        "--progress-bar", "-N", "--no-buffer", "--http1.1", "--http2", "--http2-prior-knowledge", "--globoff", "-g", "-n", "--netrc",
        "-O", "--remote-name", "-J", "--remote-header-name",
    )

    private fun looksLikeJson(text: String): Boolean {
        val t = text.trim()
        if (!t.startsWith("[") && !t.startsWith("{")) return false
        return Js.isJson(t)
    }

    private fun hasHeader(headers: List<Pair<String, String>>, name: String) = headers.any { it.first.equals(name, ignoreCase = true) }

    fun parseCurl(command: String): CurlRequest {
        val tokens = tokenizeShell(command.trim())
        if (tokens.isEmpty()) throw ToolInputError("Paste a cURL command.")
        val start = tokens.indexOfFirst { Regex("(^|[\\\\/])curl(\\.exe)?$", RegexOption.IGNORE_CASE).containsMatchIn(it) }
        if (start < 0) throw ToolInputError("This does not look like a cURL command: it should start with curl.")
        val r = CurlRequest()
        val data = mutableListOf<String>()
        var getMode = false
        var head = false
        var jsonFlag = false
        val expanded = mutableListOf<String>()
        // Expand combined short flags (-sSL, -XPOST, -H'x: y').
        for (arg in tokens.drop(start + 1)) {
            if (Regex("^-[A-Za-z]{2,}").containsMatchIn(arg) && !arg.startsWith("--")) {
                val letters = arg.substring(1)
                for (i in letters.indices) {
                    val l = letters[i]
                    if (l in SHORT_WITH_VALUE) {
                        expanded += "-$l"
                        if (i + 1 < letters.length) expanded += letters.substring(i + 1)
                        break
                    }
                    expanded += "-$l"
                }
            } else if (Regex("^--[a-z-]+=").containsMatchIn(arg)) {
                val eq = arg.indexOf('=')
                expanded += arg.substring(0, eq)
                expanded += arg.substring(eq + 1)
            } else {
                expanded += arg
            }
        }
        var i = 0
        while (i < expanded.size) {
            val flag = expanded[i]
            fun next(): String {
                if (i + 1 >= expanded.size) throw ToolInputError("$flag needs a value.")
                return expanded[++i]
            }
            if (!flag.startsWith("-") || flag == "-") {
                if (r.url.isEmpty()) r.url = flag else r.ignored += flag
                i++
                continue
            }
            when (flag) {
                "-X", "--request" -> r.method = next().uppercase()
                "-H", "--header" -> {
                    val h = next()
                    val colon = h.indexOf(':')
                    if (colon > 0) {
                        val value = h.substring(colon + 1).trim()
                        if (value.isNotEmpty() || !h.endsWith(";")) r.headers += h.substring(0, colon).trim() to value
                    } else if (h.endsWith(";")) {
                        r.headers += h.dropLast(1).trim() to ""
                    }
                }
                "-d", "--data", "--data-ascii" -> data += next().replace(Regex("[\\r\\n]"), "")
                "--data-raw", "--data-binary" -> data += next()
                "--data-urlencode" -> {
                    val v = next()
                    val eq = v.indexOf('=')
                    data += when {
                        eq < 0 -> Js.encodeURIComponent(v)
                        eq == 0 -> Js.encodeURIComponent(v.substring(1))
                        else -> "${v.substring(0, eq)}=${Js.encodeURIComponent(v.substring(eq + 1))}"
                    }
                }
                "--json" -> {
                    data += next()
                    jsonFlag = true
                }
                "-F", "--form", "--form-string" -> {
                    val v = next()
                    val eq = v.indexOf('=')
                    if (eq < 0) throw ToolInputError("-F expects name=value, got \"$v\".")
                    val value = v.substring(eq + 1)
                    val file = flag != "--form-string" && value.startsWith("@")
                    r.form += FormPart(v.substring(0, eq), if (file) value.substring(1).split(";")[0] else value.split(";type=")[0], file)
                }
                "-u", "--user" -> {
                    val v = next()
                    val colon = v.indexOf(':')
                    r.basicAuth = if (colon < 0) BasicAuth(v, "") else BasicAuth(v.substring(0, colon), v.substring(colon + 1))
                }
                "--oauth2-bearer" -> r.headers += "Authorization" to "Bearer ${next()}"
                "-b", "--cookie" -> {
                    val v = next()
                    if (v.contains("=")) r.headers += "Cookie" to v else r.ignored += "$flag $v (cookie file)"
                }
                "-A", "--user-agent" -> r.headers += "User-Agent" to next()
                "-e", "--referer" -> r.headers += "Referer" to next()
                "--url" -> r.url = next()
                "-G", "--get" -> getMode = true
                "-I", "--head" -> head = true
                "-L", "--location" -> r.followRedirects = true
                "-k", "--insecure" -> r.insecure = true
                "--compressed" -> r.compressed = true
                "-m", "--max-time" -> r.timeoutSeconds = Js.toNumber(next()).takeIf { Js.truthy(it) }
                "-x", "--proxy" -> r.proxy = next()
                else -> if (flag in WITH_VALUE) r.ignored += "$flag ${next()}" else if (flag !in QUIET_FLAGS) r.ignored += flag
            }
            i++
        }
        if (r.url.isEmpty()) throw ToolInputError("The command has no URL.")
        if (!Regex("^[a-z][a-z0-9+.-]*://", RegexOption.IGNORE_CASE).containsMatchIn(r.url)) r.url = "http://${r.url}"
        val parsed = JsUrl.tryParse(r.url) ?: throw ToolInputError("\"${r.url}\" is not a valid URL.")

        if (data.isNotEmpty() && getMode) {
            r.url = "${r.url}${if (parsed.search.isNotEmpty()) "&" else "?"}${data.joinToString("&")}"
        } else if (data.isNotEmpty()) {
            val body = data.joinToString("&")
            r.body = body
            if (jsonFlag) {
                if (!hasHeader(r.headers, "Content-Type")) r.headers += "Content-Type" to "application/json"
                if (!hasHeader(r.headers, "Accept")) r.headers += "Accept" to "application/json"
            }
            val type = (r.headers.firstOrNull { it.first.lowercase() == "content-type" }?.second ?: "").lowercase()
            r.bodyKind = when {
                type.contains("json") || (type.isEmpty() && looksLikeJson(body)) -> if (looksLikeJson(body)) "json" else "raw"
                type.isEmpty() || type.contains("x-www-form-urlencoded") -> "form"
                else -> "raw"
            }
            if (type.isEmpty() && r.bodyKind == "form") r.headers += "Content-Type" to "application/x-www-form-urlencoded"
            if (type.isEmpty() && r.bodyKind == "json") r.headers += "Content-Type" to "application/json"
        } else if (r.form.isNotEmpty()) {
            r.bodyKind = "multipart"
            // The client sets the multipart boundary itself; a copied header would break it.
            r.headers = r.headers.filterNot { (k, v) -> k.lowercase() == "content-type" && v.contains("multipart", ignoreCase = true) }.toMutableList()
        }
        if (r.method.isEmpty()) r.method = if (head) "HEAD" else if (r.body != null || r.form.isNotEmpty()) "POST" else "GET"
        return r
    }

    // ---------------------------------------------------------------------------
    // String literals per language
    // ---------------------------------------------------------------------------

    private fun js(s: String) = Js.quote(s)
    private fun py(s: String) = Js.quote(s) // JSON escapes are valid Python escapes
    private fun dart(s: String) = "'" + s.replace("\\", "\\\\").replace("'", "\\'").replace("$", "\\$").replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t") + "'"
    private fun kotlin(s: String) = Js.quote(s).replace("$", "\\$")
    private fun swift(s: String) = Js.quote(s).replace(Regex("\\\\u([0-9a-fA-F]{4})")) { "\\u{${it.groupValues[1]}}" }.replace("\\/", "/")
    private fun goStr(s: String) = Js.quote(s)
    private fun java(s: String) = Js.quote(s)
    private fun php(s: String) = "'" + s.replace("\\", "\\\\").replace("'", "\\'") + "'"
    private fun num(d: Double) = Js.number(d)

    /** JSON value → Python literal. */
    private fun pyLiteral(value: Any?, indent: Int = 0): String {
        val pad = "    ".repeat(indent + 1)
        val end = "    ".repeat(indent)
        return when (value) {
            null -> "None"
            true -> "True"
            false -> "False"
            is Double -> Js.number(value)
            is String -> py(value)
            is List<*> -> if (value.isNotEmpty()) "[\n${value.joinToString(",\n") { pad + pyLiteral(it, indent + 1) }},\n$end]" else "[]"
            is Map<*, *> -> {
                @Suppress("UNCHECKED_CAST")
                val entries = Js.entries(value as Map<String, Any?>)
                if (entries.isNotEmpty()) "{\n${entries.joinToString(",\n") { (k, v) -> "$pad${py(k)}: ${pyLiteral(v, indent + 1)}" }},\n$end}" else "{}"
            }
            else -> value.toString()
        }
    }

    /** JSON value → Dart literal (maps and lists). */
    private fun dartLiteral(value: Any?, indent: Int = 0): String {
        val pad = "  ".repeat(indent + 1)
        val end = "  ".repeat(indent)
        return when (value) {
            null -> "null"
            is Boolean -> value.toString()
            is Double -> Js.number(value)
            is String -> dart(value)
            is List<*> -> if (value.isNotEmpty()) "[\n${value.joinToString(",\n") { pad + dartLiteral(it, indent + 1) }},\n$end]" else "[]"
            is Map<*, *> -> {
                @Suppress("UNCHECKED_CAST")
                val entries = Js.entries(value as Map<String, Any?>)
                if (entries.isNotEmpty()) "{\n${entries.joinToString(",\n") { (k, v) -> "$pad${dart(k)}: ${dartLiteral(v, indent + 1)}" }},\n$end}" else "<String, dynamic>{}"
            }
            else -> value.toString()
        }
    }

    private fun indentJson(body: String, spaces: Int): String =
        Js.stringify(Js.parseJson(body), "  ").split("\n").mapIndexed { i, l -> if (i > 0) " ".repeat(spaces) + l else l }.joinToString("\n")

    private fun formPairs(body: String): List<Pair<String, String>> = body.split("&").filter { it.isNotEmpty() }.map { p ->
        val eq = p.indexOf('=')
        fun dec(s: String) = try {
            Js.decodeURIComponent(s.replace("+", " "))
        } catch (_: Js.UriError) {
            s
        }
        if (eq < 0) dec(p) to "" else dec(p.substring(0, eq)) to dec(p.substring(eq + 1))
    }

    /** Headers without the ones each client sets itself. */
    private fun plainHeaders(r: CurlRequest, drop: List<String> = emptyList()): List<Pair<String, String>> {
        val skip = setOf("content-length", "host") + drop.map { it.lowercase() }
        return r.headers.filter { it.first.lowercase() !in skip }
    }

    private fun basicHeader(r: CurlRequest): List<Pair<String, String>> = r.basicAuth?.let {
        listOf("Authorization" to "Basic ${Base64.getEncoder().encodeToString("${it.user}:${it.password}".toByteArray(Charsets.UTF_8))}")
    } ?: emptyList()

    private fun fileName(path: String) = path.split(Regex("[\\\\/]")).last()

    fun generate(r: CurlRequest, target: String): String = when (target) {
        "fetch" -> toFetch(r, false)
        "node-fetch" -> toFetch(r, true)
        "axios" -> toAxios(r)
        "python" -> toPython(r)
        "go" -> toGo(r)
        "php" -> toPhp(r)
        "java" -> toJava(r)
        "dart-http" -> toDartHttp(r)
        "dart-dio" -> toDio(r)
        "kotlin-okhttp" -> toOkHttp(r)
        "swift" -> toSwift(r)
        "http" -> toRawHttp(r)
        "httpie" -> toHttpie(r)
        else -> throw ToolInputError("Unknown target \"$target\".")
    }

    private fun toFetch(r: CurlRequest, node: Boolean): String {
        val headers = plainHeaders(r, if (r.bodyKind == "multipart") listOf("content-type") else emptyList()) + basicHeader(r)
        val lines = mutableListOf<String>()
        if (r.bodyKind == "multipart") {
            lines += "const form = new FormData();"
            for (f in r.form) {
                lines += if (f.file) {
                    if (node) "form.append(${js(f.name)}, await openAsBlob(${js(f.value)}), ${js(fileName(f.value))});" else "form.append(${js(f.name)}, fileInput.files[0]); // ${f.value}"
                } else {
                    "form.append(${js(f.name)}, ${js(f.value)});"
                }
            }
            lines += ""
        }
        val opts = mutableListOf("  method: ${js(r.method)},")
        if (headers.isNotEmpty()) opts += "  headers: {\n${headers.joinToString("\n") { (k, v) -> "    ${js(k)}: ${js(v)}," }}\n  },"
        when (r.bodyKind) {
            "json" -> opts += "  body: JSON.stringify(${indentJson(r.body!!, 2)}),"
            "form" -> opts += "  body: new URLSearchParams({\n${formPairs(r.body!!).joinToString("\n") { (k, v) -> "    ${js(k)}: ${js(v)}," }}\n  }),"
            "raw" -> opts += "  body: ${js(r.body!!)},"
            "multipart" -> opts += "  body: form,"
        }
        r.timeoutSeconds?.let { opts += "  signal: AbortSignal.timeout(${num(it * 1000)})," }
        val head = if (node && r.bodyKind == "multipart" && r.form.any { it.file }) "import { openAsBlob } from \"node:fs\";\n\n" else ""
        lines += "const response = await fetch(${js(r.url)}, {\n${opts.joinToString("\n")}\n});"
        lines += ""
        lines += "if (!response.ok) {"
        lines += "  throw new Error(`HTTP $D{response.status}: $D{await response.text()}`);"
        lines += "}"
        lines += "const data = await response.${if (expectsJson(r)) "json" else "text"}();"
        lines += "console.log(data);"
        return "$head${lines.joinToString("\n")}\n${if (node) "\nexport {};\n" else ""}"
    }

    private fun expectsJson(r: CurlRequest): Boolean {
        val accept = r.headers.firstOrNull { it.first.lowercase() == "accept" }?.second ?: ""
        return accept.contains("json", ignoreCase = true) || r.bodyKind == "json" ||
            Regex("/api/|\\.json(\\?|$)|graphql", RegexOption.IGNORE_CASE).containsMatchIn(r.url) || accept.isEmpty()
    }

    private fun toAxios(r: CurlRequest): String {
        val headers = plainHeaders(r, if (r.bodyKind == "multipart") listOf("content-type") else emptyList())
        val lines = mutableListOf("import axios from \"axios\";", "")
        if (r.bodyKind == "multipart") {
            lines += "const form = new FormData();"
            for (f in r.form) lines += if (f.file) "form.append(${js(f.name)}, file); // ${f.value}" else "form.append(${js(f.name)}, ${js(f.value)});"
            lines += ""
        }
        val u = JsUrl.parse(r.url)
        val params = u.searchParams
        val base = if (params.isNotEmpty()) "${u.origin}${u.pathname}" else r.url
        val opts = mutableListOf("  method: ${js(r.method.lowercase())},", "  url: ${js(base)},")
        if (params.isNotEmpty()) opts += "  params: {\n${params.joinToString("\n") { (k, v) -> "    ${js(k)}: ${js(v)}," }}\n  },"
        if (headers.isNotEmpty()) opts += "  headers: {\n${headers.joinToString("\n") { (k, v) -> "    ${js(k)}: ${js(v)}," }}\n  },"
        r.basicAuth?.let { opts += "  auth: { username: ${js(it.user)}, password: ${js(it.password)} }," }
        when (r.bodyKind) {
            "json" -> opts += "  data: ${indentJson(r.body!!, 2)},"
            "form" -> opts += "  data: new URLSearchParams({\n${formPairs(r.body!!).joinToString("\n") { (k, v) -> "    ${js(k)}: ${js(v)}," }}\n  }),"
            "raw" -> opts += "  data: ${js(r.body!!)},"
            "multipart" -> opts += "  data: form,"
        }
        r.timeoutSeconds?.let { opts += "  timeout: ${num(it * 1000)}," }
        if (!r.followRedirects) opts += "  maxRedirects: 0, // curl without -L does not follow redirects; remove to follow them"
        if (r.bodyKind == "multipart" && r.form.any { it.file }) lines.addAll(2, listOf("const file = document.querySelector(\"input[type=file]\").files[0];", ""))
        lines += "const { data, status } = await axios.request({\n${opts.joinToString("\n")}\n});"
        lines += "console.log(status, data);"
        return lines.joinToString("\n") + "\n"
    }

    private fun toPython(r: CurlRequest): String {
        val headers = plainHeaders(r, if (r.bodyKind == "json" || r.bodyKind == "multipart") listOf("content-type") else emptyList())
        val lines = mutableListOf("import requests", "")
        val args = mutableListOf("    ${py(r.url)},")
        if (headers.isNotEmpty()) {
            lines += "headers = {\n${headers.joinToString("\n") { (k, v) -> "    ${py(k)}: ${py(v)}," }}\n}"
            args += "    headers=headers,"
        }
        when (r.bodyKind) {
            "json" -> {
                lines += "payload = ${pyLiteral(Js.parseJson(r.body!!))}"
                args += "    json=payload,"
            }
            "form" -> {
                lines += "data = {\n${formPairs(r.body!!).joinToString("\n") { (k, v) -> "    ${py(k)}: ${py(v)}," }}\n}"
                args += "    data=data,"
            }
            "raw" -> {
                lines += "data = ${py(r.body!!)}"
                args += "    data=data.encode(\"utf-8\"),"
            }
            "multipart" -> {
                val files = r.form.filter { it.file }
                val fields = r.form.filter { !it.file }
                if (fields.isNotEmpty()) {
                    lines += "data = {\n${fields.joinToString("\n") { "    ${py(it.name)}: ${py(it.value)}," }}\n}"
                    args += "    data=data,"
                }
                if (files.isNotEmpty()) {
                    lines += "files = {\n${files.joinToString("\n") { "    ${py(it.name)}: open(${py(it.value)}, \"rb\")," }}\n}"
                    args += "    files=files,"
                }
            }
        }
        r.basicAuth?.let { args += "    auth=(${py(it.user)}, ${py(it.password)})," }
        args += "    timeout=${r.timeoutSeconds?.let(::num) ?: "30"},"
        if (r.insecure) args += "    verify=False,  # curl -k: TLS verification disabled - development only"
        if (!r.followRedirects && r.method == "GET") args += "    allow_redirects=False,"
        if (lines.size > 2) lines += ""
        lines += "response = requests.request(\n    ${py(r.method)},\n${args.joinToString("\n")}\n)"
        lines += "response.raise_for_status()"
        lines += if (expectsJson(r)) "print(response.json())" else "print(response.text)"
        return lines.joinToString("\n") + "\n"
    }

    private fun toGo(r: CurlRequest): String {
        val headers = plainHeaders(r, if (r.bodyKind == "multipart") listOf("content-type") else emptyList())
        val imports = linkedSetOf("fmt", "io", "net/http", "log")
        val body = mutableListOf<String>()
        var reader = "nil"
        if (r.bodyKind == "json" || r.bodyKind == "raw" || r.bodyKind == "form") {
            imports += "strings"
            val content = if (r.bodyKind == "json") Js.stringify(Js.parseJson(r.body!!), "\t") else r.body!!
            body += "\tbody := strings.NewReader(${if (content.contains("`")) goStr(content) else "`$content`"})"
            reader = "body"
        } else if (r.bodyKind == "multipart") {
            imports += "bytes"
            imports += "mime/multipart"
            if (r.form.any { it.file }) {
                imports += "os"
                imports += "path/filepath"
            }
            body += listOf("\tvar buf bytes.Buffer", "\tw := multipart.NewWriter(&buf)")
            for (f in r.form) {
                if (f.file) {
                    body += listOf(
                        "\tif file, err := os.Open(${goStr(f.value)}); err == nil {",
                        "\t\tpart, _ := w.CreateFormFile(${goStr(f.name)}, filepath.Base(${goStr(f.value)}))",
                        "\t\tio.Copy(part, file)", "\t\tfile.Close()", "\t} else {", "\t\tlog.Fatal(err)", "\t}",
                    )
                } else {
                    body += "\tw.WriteField(${goStr(f.name)}, ${goStr(f.value)})"
                }
            }
            body += "\tw.Close()"
            reader = "&buf"
        }
        val lines = body + listOf("\treq, err := http.NewRequest(${goStr(r.method)}, ${goStr(r.url)}, $reader)", "\tif err != nil {", "\t\tlog.Fatal(err)", "\t}")
        val all = lines.toMutableList()
        for ((k, v) in headers) all += "\treq.Header.Set(${goStr(k)}, ${goStr(v)})"
        if (r.bodyKind == "multipart") all += "\treq.Header.Set(\"Content-Type\", w.FormDataContentType())"
        r.basicAuth?.let { all += "\treq.SetBasicAuth(${goStr(it.user)}, ${goStr(it.password)})" }
        if (r.timeoutSeconds != null) imports += "time"
        val client = r.timeoutSeconds?.let { "&http.Client{Timeout: ${num(it)} * time.Second}" } ?: "&http.Client{}"
        all += listOf(
            "\tclient := $client", "\tresp, err := client.Do(req)", "\tif err != nil {", "\t\tlog.Fatal(err)", "\t}", "\tdefer resp.Body.Close()",
            "\tdata, err := io.ReadAll(resp.Body)", "\tif err != nil {", "\t\tlog.Fatal(err)", "\t}", "\tfmt.Println(resp.Status)", "\tfmt.Println(string(data))",
        )
        return "package main\n\nimport (\n${imports.sorted().joinToString("\n") { "\t\"$it\"" }}\n)\n\nfunc main() {\n${all.joinToString("\n")}\n}\n"
    }

    private fun toPhp(r: CurlRequest): String {
        val headers = plainHeaders(r, if (r.bodyKind == "multipart") listOf("content-type") else emptyList())
        val lines = mutableListOf("<?php", "", "${D}ch = curl_init(${php(r.url)});", "curl_setopt_array(${D}ch, [")
        lines += "    CURLOPT_RETURNTRANSFER => true,"
        lines += "    CURLOPT_CUSTOMREQUEST => ${php(r.method)},"
        if (headers.isNotEmpty()) lines += "    CURLOPT_HTTPHEADER => [\n${headers.joinToString("\n") { (k, v) -> "        ${php("$k: $v")}," }}\n    ],"
        if (r.bodyKind == "multipart") {
            lines += "    CURLOPT_POSTFIELDS => [\n${r.form.joinToString("\n") { "        ${php(it.name)} => ${if (it.file) "new CURLFile(${php(it.value)})" else php(it.value)}," }}\n    ],"
        } else if (r.body != null) {
            lines += "    CURLOPT_POSTFIELDS => ${php(r.body!!)},"
        }
        r.basicAuth?.let { lines += "    CURLOPT_USERPWD => ${php("${it.user}:${it.password}")}," }
        if (r.followRedirects) lines += "    CURLOPT_FOLLOWLOCATION => true,"
        r.timeoutSeconds?.let { lines += "    CURLOPT_TIMEOUT => ${num(it)}," }
        if (r.compressed) lines += "    CURLOPT_ENCODING => '',"
        lines += listOf(
            "]);", "${D}response = curl_exec(${D}ch);", "if (${D}response === false) {", "    throw new RuntimeException(curl_error(${D}ch));", "}",
            "${D}status = curl_getinfo(${D}ch, CURLINFO_HTTP_CODE);", "curl_close(${D}ch);", "echo ${D}status, PHP_EOL, ${D}response;",
        )
        return lines.joinToString("\n") + "\n"
    }

    private fun toJava(r: CurlRequest): String {
        val headers = plainHeaders(r) + basicHeader(r)
        val publisher = when {
            r.bodyKind == "multipart" -> "HttpRequest.BodyPublishers.noBody() /* multipart: use a library such as OkHttp or Apache HttpClient */"
            r.body != null -> "HttpRequest.BodyPublishers.ofString(${java(if (r.bodyKind == "json") Js.stringify(Js.parseJson(r.body!!)) else r.body!!)})"
            else -> "HttpRequest.BodyPublishers.noBody()"
        }
        val lines = listOf(
            "import java.net.URI;",
            "import java.net.http.HttpClient;",
            "import java.net.http.HttpRequest;",
            "import java.net.http.HttpResponse;",
            "import java.time.Duration;",
            "",
            "public class Request {",
            "    public static void main(String[] args) throws Exception {",
            "        HttpClient client = HttpClient.newBuilder()",
            "            .followRedirects(HttpClient.Redirect.${if (r.followRedirects) "NORMAL" else "NEVER"})",
            "            .connectTimeout(Duration.ofSeconds(${r.timeoutSeconds?.let(::num) ?: "30"}))",
            "            .build();",
            "        HttpRequest request = HttpRequest.newBuilder()",
            "            .uri(URI.create(${java(r.url)}))",
        ) + headers.map { (k, v) -> "            .header(${java(k)}, ${java(v)})" } + listOf(
            "            .method(${java(r.method)}, $publisher)",
            "            .build();",
            "        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());",
            "        System.out.println(response.statusCode());",
            "        System.out.println(response.body());",
            "    }",
            "}",
        )
        return lines.joinToString("\n") + "\n"
    }

    private fun toDartHttp(r: CurlRequest): String {
        val headers = plainHeaders(r, if (r.bodyKind == "multipart") listOf("content-type") else emptyList()) + basicHeader(r)
        val headerMap = if (headers.isNotEmpty()) "{\n${headers.joinToString("\n") { (k, v) -> "    ${dart(k)}: ${dart(v)}," }}\n  }" else "<String, String>{}"
        val imports = linkedSetOf("import 'package:http/http.dart' as http;")
        val lines = mutableListOf<String>()
        if (r.bodyKind == "multipart") {
            lines += "  final request = http.MultipartRequest(${dart(r.method)}, Uri.parse(${dart(r.url)}));"
            lines += "  request.headers.addAll($headerMap);"
            for (f in r.form) lines += if (f.file) "  request.files.add(await http.MultipartFile.fromPath(${dart(f.name)}, ${dart(f.value)}));" else "  request.fields[${dart(f.name)}] = ${dart(f.value)};"
            lines += "  final response = await http.Response.fromStream(await request.send());"
        } else {
            lines += "  final uri = Uri.parse(${dart(r.url)});"
            lines += "  final headers = $headerMap;"
            var body = ""
            when (r.bodyKind) {
                "json" -> {
                    imports += "import 'dart:convert';"
                    lines += "  final body = jsonEncode(${dartLiteral(Js.parseJson(r.body!!), 1)});"
                    body = ", body: body"
                }
                "form" -> {
                    lines += "  final body = {\n${formPairs(r.body!!).joinToString("\n") { (k, v) -> "    ${dart(k)}: ${dart(v)}," }}\n  };"
                    body = ", body: body"
                }
                "raw" -> {
                    lines += "  final body = ${dart(r.body!!)};"
                    body = ", body: body"
                }
            }
            val simple = r.method in listOf("GET", "POST", "PUT", "PATCH", "DELETE", "HEAD")
            if (simple) {
                val m = r.method.lowercase()
                val timeout = r.timeoutSeconds?.let { ".timeout(const Duration(seconds: ${num(it)}))" } ?: ""
                lines += "  final response = await http.$m(uri, headers: headers${if (m == "get" || m == "head") "" else body})$timeout;"
            } else {
                lines += "  final request = http.Request(${dart(r.method)}, uri)..headers.addAll(headers);"
                if (body.isNotEmpty()) lines += "  request.body = body is String ? body : jsonEncode(body);"
                lines += "  final response = await http.Response.fromStream(await request.send());"
            }
        }
        lines += listOf("  if (response.statusCode >= 400) {", "    throw Exception('HTTP $D{response.statusCode}: $D{response.body}');", "  }", "  print(response.body);")
        return "${imports.sorted().joinToString("\n")}\n\nFuture<void> main() async {\n${lines.joinToString("\n")}\n}\n"
    }

    private fun toDio(r: CurlRequest): String {
        val headers = plainHeaders(r, listOf("content-type")) + basicHeader(r)
        val contentType = r.headers.firstOrNull { it.first.lowercase() == "content-type" }?.second
        val timeout = r.timeoutSeconds?.let(::num) ?: "30"
        val lines = mutableListOf(
            "import 'package:dio/dio.dart';", "", "Future<void> main() async {", "  final dio = Dio(BaseOptions(",
            "    connectTimeout: const Duration(seconds: $timeout),", "    receiveTimeout: const Duration(seconds: $timeout),", "  ));",
        )
        var data = ""
        when (r.bodyKind) {
            "json" -> {
                lines += "  final data = ${dartLiteral(Js.parseJson(r.body!!), 1)};"
                data = "\n    data: data,"
            }
            "form" -> {
                lines += "  final data = {\n${formPairs(r.body!!).joinToString("\n") { (k, v) -> "    ${dart(k)}: ${dart(v)}," }}\n  };"
                data = "\n    data: data,"
            }
            "raw" -> {
                lines += "  final data = ${dart(r.body!!)};"
                data = "\n    data: data,"
            }
            "multipart" -> {
                lines += "  final data = FormData.fromMap({\n${r.form.joinToString("\n") { "    ${dart(it.name)}: ${if (it.file) "await MultipartFile.fromFile(${dart(it.value)})" else dart(it.value)}," }}\n  });"
                data = "\n    data: data,"
            }
        }
        val ct = when {
            r.bodyKind == "form" -> "\n      contentType: Headers.formUrlEncodedContentType,"
            !contentType.isNullOrEmpty() && r.bodyKind != "multipart" -> "\n      contentType: ${dart(contentType)},"
            else -> ""
        }
        val headerBlock = if (headers.isNotEmpty()) "\n      headers: {\n${headers.joinToString("\n") { (k, v) -> "        ${dart(k)}: ${dart(v)}," }}\n      }," else ""
        lines += "  final response = await dio.request(\n    ${dart(r.url)},$data\n    options: Options(\n      method: ${dart(r.method)},$headerBlock$ct${if (r.followRedirects) "" else "\n      followRedirects: false,"}\n    ),\n  );"
        lines += listOf("  print(response.data);", "}")
        return lines.joinToString("\n") + "\n"
    }

    private fun toOkHttp(r: CurlRequest): String {
        val headers = plainHeaders(r, listOf("content-type")) + basicHeader(r)
        val contentType = r.headers.firstOrNull { it.first.lowercase() == "content-type" }?.second ?: if (r.bodyKind == "json") "application/json" else "text/plain"
        val imports = linkedSetOf("okhttp3.OkHttpClient", "okhttp3.Request")
        val lines = mutableListOf<String>()
        var body = "null"
        if (r.bodyKind == "json" || r.bodyKind == "raw" || r.bodyKind == "form") {
            imports += "okhttp3.MediaType.Companion.toMediaType"
            imports += "okhttp3.RequestBody.Companion.toRequestBody"
            val content = if (r.bodyKind == "json") Js.stringify(Js.parseJson(r.body!!)) else r.body!!
            lines += "    val body = ${kotlin(content)}.toRequestBody(${kotlin(contentType)}.toMediaType())"
            body = "body"
        } else if (r.bodyKind == "multipart") {
            imports += "okhttp3.MultipartBody"
            imports += "okhttp3.RequestBody.Companion.asRequestBody"
            imports += "java.io.File"
            lines += listOf("    val body = MultipartBody.Builder()", "        .setType(MultipartBody.FORM)")
            for (f in r.form) {
                lines += if (f.file) "        .addFormDataPart(${kotlin(f.name)}, File(${kotlin(f.value)}).name, File(${kotlin(f.value)}).asRequestBody())" else "        .addFormDataPart(${kotlin(f.name)}, ${kotlin(f.value)})"
            }
            lines += "        .build()"
            body = "body"
        }
        if (r.timeoutSeconds != null) imports += "java.util.concurrent.TimeUnit"
        val client = listOf("    val client = OkHttpClient.Builder()") +
            (r.timeoutSeconds?.let { listOf("        .callTimeout(${num(it)}L, TimeUnit.SECONDS)") } ?: emptyList()) +
            listOf("        .followRedirects(${r.followRedirects})", "        .build()")
        val request = listOf("    val request = Request.Builder()", "        .url(${kotlin(r.url)})") +
            headers.map { (k, v) -> "        .addHeader(${kotlin(k)}, ${kotlin(v)})" } +
            listOf("        .method(${kotlin(r.method)}, $body)", "        .build()")
        return "${imports.sorted().joinToString("\n") { "import $it" }}\n\n// Run off the main thread on Android (coroutine with Dispatchers.IO, or client.newCall(request).enqueue).\nfun main() {\n${(client + lines + request).joinToString("\n")}\n    client.newCall(request).execute().use { response ->\n        if (!response.isSuccessful) error(\"HTTP $D{response.code}: $D{response.body?.string()}\")\n        println(response.body?.string())\n    }\n}\n"
    }

    private fun toSwift(r: CurlRequest): String {
        val headers = plainHeaders(r, if (r.bodyKind == "multipart") listOf("content-type") else emptyList()) + basicHeader(r)
        val lines = mutableListOf("import Foundation", "", "var request = URLRequest(url: URL(string: ${swift(r.url)})!)", "request.httpMethod = ${swift(r.method)}")
        r.timeoutSeconds?.let { lines += "request.timeoutInterval = ${num(it)}" }
        for ((k, v) in headers) lines += "request.setValue(${swift(v)}, forHTTPHeaderField: ${swift(k)})"
        when (r.bodyKind) {
            "json" -> lines += "request.httpBody = #\"\"\"\n${Js.stringify(Js.parseJson(r.body!!), "  ")}\n\"\"\"#.data(using: .utf8)"
            "form", "raw" -> lines += "request.httpBody = ${swift(r.body!!)}.data(using: .utf8)"
            "multipart" -> {
                lines += listOf("", "let boundary = \"Boundary-\\(UUID().uuidString)\"", "request.setValue(\"multipart/form-data; boundary=\\(boundary)\", forHTTPHeaderField: \"Content-Type\")", "var body = Data()")
                for (f in r.form) {
                    val name = f.name.replace("\"", "")
                    if (f.file) {
                        lines += listOf(
                            "let fileURL = URL(fileURLWithPath: ${swift(f.value)})",
                            "body.append(\"--\\(boundary)\\r\\n\".data(using: .utf8)!)",
                            "body.append(\"Content-Disposition: form-data; name=\\\"$name\\\"; filename=\\\"\\(fileURL.lastPathComponent)\\\"\\r\\n\\r\\n\".data(using: .utf8)!)",
                            "body.append(try! Data(contentsOf: fileURL))",
                            "body.append(\"\\r\\n\".data(using: .utf8)!)",
                        )
                    } else {
                        lines += listOf(
                            "body.append(\"--\\(boundary)\\r\\n\".data(using: .utf8)!)",
                            "body.append(\"Content-Disposition: form-data; name=\\\"$name\\\"\\r\\n\\r\\n\".data(using: .utf8)!)",
                            "body.append(${swift(f.value + "\r\n")}.data(using: .utf8)!)",
                        )
                    }
                }
                lines += listOf("body.append(\"--\\(boundary)--\\r\\n\".data(using: .utf8)!)", "request.httpBody = body")
            }
        }
        lines += listOf(
            "", "let (data, response) = try await URLSession.shared.data(for: request)",
            "guard let http = response as? HTTPURLResponse, (200..<300).contains(http.statusCode) else {", "    throw URLError(.badServerResponse)", "}",
            "print(String(decoding: data, as: UTF8.self))",
        )
        return lines.joinToString("\n") + "\n"
    }

    private fun toRawHttp(r: CurlRequest): String {
        val u = JsUrl.parse(r.url)
        val lines = mutableListOf("${r.method} ${u.pathname}${u.search} HTTP/1.1", "Host: ${u.host}") +
            plainHeaders(r).map { (k, v) -> "$k: $v" } + basicHeader(r).map { (k, v) -> "$k: $v" }
        val all = lines.toMutableList()
        if (r.bodyKind == "multipart") all += "Content-Type: multipart/form-data; boundary=----devsnip"
        val body = when {
            r.bodyKind == "json" -> Js.stringify(Js.parseJson(r.body!!), "  ")
            r.body != null -> r.body!!
            r.bodyKind == "multipart" -> r.form.joinToString("\r\n") {
                "------devsnip\r\nContent-Disposition: form-data; name=\"${it.name}\"${if (it.file) "; filename=\"${fileName(it.value)}\"" else ""}\r\n\r\n${if (it.file) "< ${it.value}" else it.value}"
            } + "\r\n------devsnip--"
            else -> ""
        }
        return "${all.joinToString("\n")}\n${if (body.isNotEmpty()) "\n$body\n" else ""}"
    }

    private fun shq(s: String): String = if (s.matches(Regex("^[A-Za-z0-9_./:@%+=,-]+$"))) s else "'" + s.replace("'", "'\\''") + "'"

    private fun toHttpie(r: CurlRequest): String {
        val parts = mutableListOf("http")
        if (r.bodyKind == "form" || r.bodyKind == "multipart") parts += if (r.bodyKind == "multipart") "--multipart" else "--form"
        if (r.followRedirects) parts += "--follow"
        if (r.insecure) parts += "--verify=no"
        r.timeoutSeconds?.let { parts += "--timeout=${num(it)}" }
        r.basicAuth?.let { parts += "--auth ${shq("${it.user}:${it.password}")}" }
        parts += r.method
        parts += shq(r.url)
        for ((k, v) in plainHeaders(r, if (r.bodyKind == "json") listOf("content-type") else emptyList())) parts += shq("$k:$v")
        if (r.bodyKind == "form") for ((k, v) in formPairs(r.body!!)) parts += shq("$k=$v")
        if (r.bodyKind == "multipart") for (f in r.form) parts += shq(if (f.file) "${f.name}@${f.value}" else "${f.name}=${f.value}")
        val prefix = when (r.bodyKind) {
            "json" -> "echo ${shq(Js.stringify(Js.parseJson(r.body!!)))} | "
            "raw" -> "printf %s ${shq(r.body!!)} | "
            else -> ""
        }
        return "$prefix${parts.joinToString(" ")}\n"
    }

    /** Back to a clean, canonical cURL command (one option per line). */
    fun toCurl(r: CurlRequest): String {
        val parts = mutableListOf("curl${if (r.followRedirects) " -L" else ""}${if (r.insecure) " -k" else ""}${if (r.compressed) " --compressed" else ""} -X ${r.method} ${shq(r.url)}")
        for ((k, v) in r.headers) parts += "-H ${shq("$k: $v")}"
        r.basicAuth?.let { parts += "-u ${shq("${it.user}:${it.password}")}" }
        if (r.bodyKind == "json") parts += "--data-raw ${shq(Js.stringify(Js.parseJson(r.body!!)))}" else if (r.body != null) parts += "--data-raw ${shq(r.body!!)}"
        for (f in r.form) parts += "-F ${shq("${f.name}=${if (f.file) "@" else ""}${f.value}")}"
        r.timeoutSeconds?.let { parts += "--max-time ${num(it)}" }
        return parts.joinToString(" \\\n  ") + "\n"
    }

    /** Notes worth knowing before running the converted request. */
    fun warnings(r: CurlRequest): List<String> {
        val out = mutableListOf<String>()
        val auth = r.headers.firstOrNull { it.first.matches(Regex("^(authorization|x-api-key|api-key|x-auth-token)$", RegexOption.IGNORE_CASE)) }
        if (auth != null || r.basicAuth != null) out += "The request carries credentials. Move them to environment variables or secure storage before committing the code."
        if (r.headers.any { it.first.lowercase() == "cookie" }) out += "A Cookie header was copied from the browser; session cookies expire, so replace it with a real login flow in code."
        if (r.insecure) out += "curl -k disables TLS certificate checks. The generated code keeps verification on (except Python, where it is marked); fix the certificate instead."
        r.proxy?.let { out += "The request used a proxy ($it); configure it in the client if you still need it." }
        if (r.url.startsWith("http://") && !Regex("//(localhost|127\\.0\\.0\\.1|10\\.0\\.2\\.2|0\\.0\\.0\\.0|192\\.168\\.|10\\.)").containsMatchIn(r.url)) {
            out += "Plain http:// is blocked by default on Android 9+ and by App Transport Security on iOS; use https."
        }
        val browserNoise = r.headers.filter { Regex("^(sec-|priority$|upgrade-insecure-requests$|dnt$|pragma$)", RegexOption.IGNORE_CASE).containsMatchIn(it.first) }
        if (browserNoise.isNotEmpty()) out += "Browser-only headers can usually be dropped: ${browserNoise.joinToString(", ") { it.first }}."
        if (r.ignored.isNotEmpty()) out += "Ignored options: ${r.ignored.joinToString(", ")}."
        return out
    }
}

/** JavaScript's `\s`: Unicode white space plus BOM. */
internal fun Char.isJsWhitespace() = isWhitespace() || this == '\uFEFF' || this == '\u00A0'
