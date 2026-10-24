package pro.devsnip.api.core

import java.net.URI

/** cURL export and import, ported from `generateCurlCommand` / `parseCurlCommand` in the VS Code extension. */
object Curl {
    /** POSIX single-quote escaping: the result is always exactly one shell word. */
    fun shellQuote(value: String): String = "'" + value.replace("'", "'\\''") + "'"

    /**
     * An equivalent cURL command for a built request. [cookieHeader] is the
     * stored cookies for the target host, added unless the request sets its own.
     */
    fun generate(prepared: PreparedRequest, cookieHeader: String? = null, verifySsl: Boolean = true, proxy: ProxyConfig? = null): String {
        val parts = mutableListOf("curl", "-X", prepared.method)
        prepared.headers.forEach { (name, value) -> parts += listOf("-H", shellQuote("$name: $value")) }
        if (!cookieHeader.isNullOrEmpty() && prepared.header("Cookie") == null) {
            parts += listOf("-H", shellQuote("Cookie: $cookieHeader"))
        }
        if (prepared.body != null) parts += listOf("-d", shellQuote(prepared.body))
        if (!verifySsl) parts += "-k"
        if (proxy != null) parts += listOf("-x", shellQuote("http://${proxy.host}:${proxy.port}"))
        parts += shellQuote(prepared.url)
        return parts.joinToString(" ")
    }

    /** Splits a shell command line, respecting single and double quotes and line continuations. */
    fun tokenize(input: String): List<String> {
        val tokens = mutableListOf<String>()
        val current = StringBuilder()
        var inToken = false
        var quote: Char? = null
        var i = 0
        while (i < input.length) {
            val ch = input[i]
            when {
                quote != null -> when {
                    ch == quote -> quote = null
                    ch == '\\' && quote == '"' && i + 1 < input.length -> current.append(input[++i])
                    else -> current.append(ch)
                }
                ch == '"' || ch == '\'' -> {
                    quote = ch
                    inToken = true
                }
                ch == '\\' && i + 1 < input.length && (input[i + 1] == '\n' || input[i + 1] == '\r') -> {
                    i++
                    if (input[i] == '\r' && i + 1 < input.length && input[i + 1] == '\n') i++
                }
                ch == '\\' && i + 1 < input.length -> {
                    current.append(input[++i])
                    inToken = true
                }
                ch.isWhitespace() -> if (inToken || current.isNotEmpty()) {
                    tokens += current.toString()
                    current.clear()
                    inToken = false
                }
                else -> {
                    current.append(ch)
                    inToken = true
                }
            }
            i++
        }
        if (quote != null) throw RequestBuildException("Unterminated quote in cURL command")
        if (inToken || current.isNotEmpty()) tokens += current.toString()
        return tokens
    }

    /** Parses a cURL command (as copied from a browser, Postman, ...) into a request for the form. */
    fun parse(command: String): ApiRequest {
        val tokens = tokenize(command.trim())
        if (tokens.isEmpty() || !tokens[0].equals("curl", ignoreCase = true)) throw RequestBuildException("Not a valid cURL command")

        var method: String? = null
        var url: String? = null
        var data: String? = null
        val headers = mutableListOf<KeyValue>()
        var auth: AuthConfig = AuthConfig.None
        var verifySsl = true
        var followRedirects: Boolean? = null
        var proxy: ProxyConfig? = null

        var i = 1
        fun next(): String = tokens.getOrNull(++i) ?: ""
        while (i < tokens.size) {
            when (val token = tokens[i]) {
                "-X", "--request" -> method = next().uppercase().ifEmpty { "GET" }
                "-H", "--header" -> {
                    val header = next()
                    val idx = header.indexOf(':')
                    if (idx > -1) headers += KeyValue(header.substring(0, idx).trim(), header.substring(idx + 1).trim())
                }
                "-d", "--data", "--data-raw", "--data-binary", "--data-urlencode", "--data-ascii" -> {
                    data = next()
                    if (method == null) method = "POST"
                }
                "-u", "--user" -> {
                    val credentials = next()
                    val sep = credentials.indexOf(':')
                    auth = if (sep > -1) AuthConfig.Basic(credentials.substring(0, sep), credentials.substring(sep + 1)) else AuthConfig.Basic(credentials, "")
                }
                "-b", "--cookie" -> headers += KeyValue("Cookie", next())
                "-A", "--user-agent" -> headers += KeyValue("User-Agent", next())
                "-e", "--referer" -> headers += KeyValue("Referer", next())
                "-k", "--insecure" -> verifySsl = false
                "-L", "--location" -> followRedirects = true
                "--url" -> url = next()
                "-x", "--proxy" -> {
                    val value = next()
                    proxy = try {
                        val uri = URI(if (value.contains("://")) value else "http://$value")
                        uri.host?.let { ProxyConfig(it, if (uri.port > 0) uri.port else 8080) }
                    } catch (_: Exception) {
                        null // Ignore a malformed proxy value.
                    }
                }
                else -> if (!token.startsWith("-") && url == null) url = token
            }
            i++
        }

        if (url == null) throw RequestBuildException("No URL found in cURL command")
        val bodyType = when {
            data == null || Json.isValid(data) -> BodyType.JSON
            headers.any { it.key.equals("Content-Type", true) && it.value.contains("x-www-form-urlencoded", true) } -> BodyType.FORM_URLENCODED
            else -> BodyType.TEXT
        }
        return ApiRequest(
            method = method ?: "GET",
            url = url,
            data = data,
            headers = headers,
            auth = auth,
            bodyType = bodyType,
            verifySsl = verifySsl,
            followRedirects = followRedirects ?: true,
            proxy = proxy,
        )
    }
}
