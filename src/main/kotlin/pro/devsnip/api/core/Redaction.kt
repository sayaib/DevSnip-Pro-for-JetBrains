package pro.devsnip.api.core

/**
 * Keeps credentials out of history, exports and logs. Ported from the
 * redaction helpers in the VS Code extension's `api-test.ts`.
 */
object Redaction {
    /** Header and variable names that usually carry a secret. */
    val SENSITIVE_NAME = Regex("(auth|token|secret|password|passwd|pwd|key|cookie|session|signature|credential)", RegexOption.IGNORE_CASE)

    /** Query parameter names treated as credentials when a URL is stored. */
    private val SENSITIVE_QUERY_KEY = Regex("(key|token|secret|password|passwd|pwd|auth|signature|sig|credential)", RegexOption.IGNORE_CASE)

    /** A header value that is only a variable reference (optionally after a scheme). */
    val VARIABLE_ONLY = Regex("^(Bearer\\s+|Basic\\s+)?\\{\\{\\w+\\}\\}$", RegexOption.IGNORE_CASE)

    private val QUERY_PAIR = Regex("([?&])([^=&#]*)=([^&#]*)")
    private val VARIABLE = Regex("^\\{\\{\\w+\\}\\}$")
    const val REDACTED = "[redacted]"

    fun isSensitiveName(name: String) = SENSITIVE_NAME.containsMatchIn(name)

    /** Strips credential-looking query values before a resolved URL is stored or exported. */
    fun redactUrl(url: String): String = QUERY_PAIR.replace(url) { match ->
        val (sep, key, _) = match.destructured
        if (SENSITIVE_QUERY_KEY.containsMatchIn(decode(key))) "$sep$key=$REDACTED" else match.value
    }

    /** Like [redactUrl], but keeps `{{variable}}` references, which are not secrets themselves. */
    fun redactTemplateUrl(url: String): String = QUERY_PAIR.replace(url) { match ->
        val (sep, key, value) = match.destructured
        if (isSensitiveName(key) && !VARIABLE.matches(value)) "$sep$key=$REDACTED" else match.value
    }

    /** A short preview of a secret that is safe to show: `abcdef...wxyz (40 chars)`. */
    fun mask(value: String?): String {
        val text = value.orEmpty()
        if (text.length <= 12) return "***"
        return "${text.take(6)}...${text.takeLast(4)} (${text.length} chars)"
    }

    /** Header list with sensitive values replaced, for logs and diagnostics. */
    fun sanitizeHeaders(headers: List<Pair<String, String>>): List<Pair<String, String>> =
        headers.map { (name, value) -> if (isSensitiveHeader(name)) name to REDACTED else name to value }

    fun isSensitiveHeader(name: String): Boolean =
        isSensitiveName(name) || name.equals("Set-Cookie", ignoreCase = true) || name.equals("Proxy-Authorization", ignoreCase = true)

    private fun decode(text: String): String = try {
        java.net.URLDecoder.decode(text, Charsets.UTF_8)
    } catch (_: IllegalArgumentException) {
        text
    }
}
