package pro.devsnip.api.core.toolkit.engines

import pro.devsnip.api.core.toolkit.Js
import pro.devsnip.api.core.toolkit.ToolInputError

/** Port of `inspectApiResponse` (data-inspect.ts) and `flatten` (data-convert.ts). */
object ApiResponseEngine {
    data class Field(val path: String, val type: String, val example: String)

    data class Report(
        val status: Int?,
        val statusText: String?,
        val headers: List<Pair<String, String>>,
        val body: Any?,
        val bodyText: String,
        val isJson: Boolean,
        val bytes: Long,
        val insights: List<String>,
        val fields: List<Field>,
    )

    private val STATUS_HINTS = mapOf(
        400 to "Bad Request - the payload or parameters are invalid; read the error body for the field.",
        401 to "Unauthorized - missing or invalid credentials.",
        403 to "Forbidden - authenticated but not allowed (scope, role, IP allow-list).",
        404 to "Not Found - wrong path, id, or API version.",
        409 to "Conflict - the resource changed or already exists; retry with fresh state.",
        422 to "Unprocessable - validation failed; the body usually lists the fields.",
        429 to "Too Many Requests - back off; honour Retry-After.",
        500 to "Server error - retry with backoff if the request is idempotent.",
        502 to "Bad Gateway - an upstream failed; usually transient.",
        503 to "Service Unavailable - overloaded or in maintenance; retry later.",
        504 to "Gateway Timeout - the upstream took too long.",
    )

    fun inspect(raw: String): Report {
        val text = raw.replace("\r\n", "\n").trim()
        if (text.isEmpty()) throw ToolInputError("Paste an API response (raw HTTP or just the body).")
        var status: Int? = null
        var statusText: String? = null
        val headers = mutableListOf<Pair<String, String>>()
        var bodyText = text
        val statusLine = Regex("^HTTP/[\\d.]+\\s+(\\d{3})\\s*(.*)$", RegexOption.MULTILINE).find(text.split("\n")[0])
        if (statusLine != null) {
            status = statusLine.groupValues[1].toInt()
            statusText = statusLine.groupValues[2]
            val blank = text.indexOf("\n\n")
            val firstBreak = text.indexOf("\n")
            // String.prototype.slice: an end before the start gives "".
            val headerBlock = when {
                blank >= 0 -> if (blank > firstBreak + 1) text.substring(firstBreak + 1, blank) else ""
                else -> text.substring(firstBreak + 1)
            }
            for (line in headerBlock.split("\n")) {
                val idx = line.indexOf(':')
                if (idx > 0) headers += line.substring(0, idx).trim() to line.substring(idx + 1).trim()
            }
            bodyText = if (blank >= 0) text.substring(blank + 2) else ""
        }
        var body: Any? = bodyText
        var isJson = false
        if (bodyText.trim().isNotEmpty()) {
            try {
                body = Js.parseJson(bodyText)
                isJson = true
            } catch (_: Js.JsonSyntaxError) {
                // not JSON
            }
        }
        fun header(name: String) = headers.firstOrNull { it.first.lowercase() == name }?.second
        val insights = mutableListOf<String>()
        if (status != null && status != 0) {
            val hint = STATUS_HINTS[status]?.let { " - $it" } ?: if (status < 300) " - success" else ""
            insights += "$status ${statusText ?: ""}".trim() + hint
        }
        val contentType = header("content-type")
        if (!contentType.isNullOrEmpty() && isJson && !contentType.contains("json", ignoreCase = true)) {
            insights += "Content-Type is \"$contentType\" but the body is JSON - clients that trust the header will mis-parse it."
        }
        header("retry-after")?.takeIf { it.isNotEmpty() }?.let { insights += "Retry-After: $it" }
        val remaining = header("x-ratelimit-remaining") ?: header("ratelimit-remaining")
        if (!remaining.isNullOrEmpty()) {
            insights += "Rate limit remaining: $remaining${header("x-ratelimit-limit")?.takeIf { it.isNotEmpty() }?.let { " of $it" } ?: ""}"
        }
        if (header("link")?.contains("rel=\"next\"") == true) insights += "Paginated via the Link header (rel=\"next\")."
        if (isJson && body != null && (body is Map<*, *> || body is List<*>)) {
            @Suppress("UNCHECKED_CAST")
            val record = (body as? Map<String, Any?>) ?: listIndexMap(body as List<Any?>)
            val meta = record["meta"] as? Map<*, *>
            val pagination = record["pagination"] as? Map<*, *>
            val pageKeys = listOf("next", "next_cursor", "nextCursor", "cursor", "page", "total", "total_count", "has_more", "hasMore", "nextPageToken")
                .filter { k -> record.containsKey(k) || (meta != null && meta.containsKey(k)) || (pagination != null && pagination.containsKey(k)) }
            if (pageKeys.isNotEmpty()) insights += "Pagination fields: ${pageKeys.joinToString(", ")}"
            val errorKey = listOf("error", "errors", "message", "detail", "title", "problem").firstOrNull { record.containsKey(it) }
            if (errorKey != null && (status == null || status >= 400)) {
                val problem = Js.truthy(record["type"]) && Regex("problem").containsMatchIn(contentType ?: "undefined")
                insights += "Error payload under \"$errorKey\"${if (problem) " (RFC 9457 problem details)" else ""}."
            }
            for ((k, v) in Js.entries(record)) if (v is List<*>) insights += "\"$k\" holds ${v.size} item(s)."
            if (body is List<*>) insights += "Top-level array with ${body.size} item(s)."
        }
        val fields = mutableListOf<Field>()
        if (isJson) {
            val sample = if (body is List<*>) body.firstOrNull() else body
            val flat = flatten(sample ?: LinkedHashMap<String, Any?>())
            for ((path, value) in Js.entries(flat).take(200)) {
                val type = when (value) {
                    null -> "null"
                    is List<*> -> "array"
                    is Map<*, *> -> "object"
                    is String -> "string"
                    is Boolean -> "boolean"
                    else -> "number"
                }
                fields += Field(if (body is List<*>) "[].$path" else path, type, jsSlice(Js.stringify(value), 60))
            }
        }
        return Report(status, statusText, headers, body, bodyText, isJson, bodyText.toByteArray(Charsets.UTF_8).size.toLong(), insights, fields)
    }

    /** An array seen as an object: its keys are the indices (what `k in array` and Object.entries see). */
    private fun listIndexMap(list: List<Any?>): Map<String, Any?> = LinkedHashMap<String, Any?>().apply { list.forEachIndexed { i, v -> put(i.toString(), v) } }

    /** `String.prototype.slice(0, n)` on UTF-16 code units. */
    private fun jsSlice(s: String, n: Int) = if (s.length <= n) s else s.substring(0, n)

    /** `flatten` from data-convert.ts: nested objects become dotted paths, arrays of objects get [i]. */
    fun flatten(value: Any?, prefix: String = "", out: MutableMap<String, Any?> = LinkedHashMap()): MutableMap<String, Any?> {
        if (value is Map<*, *>) {
            @Suppress("UNCHECKED_CAST")
            val entries = Js.entries(value as Map<String, Any?>)
            if (entries.isEmpty() && prefix.isNotEmpty()) out[prefix] = LinkedHashMap<String, Any?>()
            for ((key, v) in entries) flatten(v, if (prefix.isNotEmpty()) "$prefix.$key" else key, out)
        } else if (value is List<*> && value.any { it != null && (it is Map<*, *> || it is List<*>) }) {
            value.forEachIndexed { i, v -> flatten(v, "$prefix[$i]", out) }
        } else {
            out[prefix.ifEmpty { "value" }] = value
        }
        return out
    }
}
