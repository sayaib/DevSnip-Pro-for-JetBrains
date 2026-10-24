package pro.devsnip.api.core

import com.google.gson.JsonObject
import java.net.URI
import java.net.URISyntaxException
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.Base64

/**
 * Resolves variables and computes the final URL, headers and body without any
 * network I/O. Port of `buildRequestParts()` from the VS Code extension, so
 * sending a request and exporting it as cURL always agree.
 */
object RequestBuilder {
    private val VARIABLE = Regex("\\{\\{(\\w+)\\}\\}")
    private val BODY_METHODS = setOf("POST", "PUT", "PATCH", "DELETE", "OPTIONS")

    fun resolveVariables(text: String, environment: Environment?): String {
        if (text.isEmpty() || environment == null) return text
        return VARIABLE.replace(text) { match -> environment.variables[match.groupValues[1]] ?: match.value }
    }

    fun build(request: ApiRequest, environment: Environment?): PreparedRequest {
        fun resolve(text: String?) = text?.let { resolveVariables(it, environment) }

        val method = request.method.trim().uppercase().ifEmpty { "GET" }
        if (!method.all { it.isLetter() || it == '-' || it == '_' }) throw RequestBuildException("Invalid HTTP method \"$method\"")

        val resolvedUrl = resolve(request.url.trim())!!
        val unresolved = VARIABLE.findAll(resolvedUrl).map { it.value }.distinct().toList()
        val baseUri = parseHttpUrl(resolvedUrl)
        if (baseUri == null) {
            if (unresolved.isNotEmpty()) {
                val verb = if (unresolved.size > 1) "are" else "is"
                val where = if (environment != null) "not defined in the active environment \"${environment.name}\"" else "used but no environment is selected"
                throw RequestBuildException("Invalid URL format: ${unresolved.joinToString(", ")} $verb $where")
            }
            throw RequestBuildException("Invalid URL format")
        }

        val params = LinkedHashMap<String, String>()
        request.params.filter { it.enabled && it.key.isNotBlank() }.forEach { params[resolve(it.key)!!] = resolve(it.value)!! }

        val headers = HeaderMap()
        request.headers.filter { it.enabled && it.key.isNotBlank() }.forEach { headers.set(resolve(it.key)!!.trim(), resolve(it.value)!!) }

        val auth = request.auth
        if (auth is AuthConfig.ApiKey && auth.location == ApiKeyLocation.QUERY) {
            val name = resolve(auth.name).orEmpty()
            val value = resolve(auth.value).orEmpty()
            if (name.isNotEmpty() && value.isNotEmpty()) params[name] = value
        }

        val finalUrl = if (params.isEmpty()) baseUri.toASCIIString() else withQueryParams(baseUri, params)

        var data = resolve(request.data)
        if (request.requestType == RequestType.GRAPHQL) {
            val graphql = JsonObject()
            graphql.addProperty("query", resolve(request.graphqlQuery?.ifEmpty { null } ?: data).orEmpty())
            val variables = resolve(request.graphqlVariables)
            if (!variables.isNullOrBlank()) {
                val parsed = Json.parseOrNull(variables)
                if (parsed == null || !parsed.isJsonObject) throw RequestBuildException("Invalid GraphQL variables JSON")
                graphql.add("variables", parsed)
            }
            val operation = resolve(request.graphqlOperationName)
            if (!operation.isNullOrBlank()) graphql.addProperty("operationName", operation)
            data = graphql.toString()
            headers.set("Content-Type", "application/json")
        }

        var body: String? = null
        if (method in BODY_METHODS && !data.isNullOrEmpty()) {
            val contentType = headers.get("Content-Type")?.lowercase().orEmpty()
            when (request.bodyType) {
                BodyType.FORM_URLENCODED -> {
                    val formObject = parseJson(data)?.takeIf { it.isJsonObject }?.asJsonObject
                    body = formObject?.entrySet()?.joinToString("&") { (key, value) ->
                        val text = if (value.isJsonPrimitive) value.asString else if (value.isJsonNull) "null" else value.toString()
                        "${formEncode(key)}=${formEncode(text)}"
                    } ?: data
                    headers.setIfAbsent("Content-Type", "application/x-www-form-urlencoded")
                }
                BodyType.TEXT -> {
                    body = data
                    headers.setIfAbsent("Content-Type", "text/plain")
                }
                BodyType.JSON -> {
                    body = data
                    if (parseJson(data) != null) {
                        headers.setIfAbsent("Content-Type", "application/json")
                    } else {
                        if (contentType.contains("application/json")) throw RequestBuildException("Invalid JSON in request body")
                        headers.setIfAbsent("Content-Type", "text/plain")
                    }
                }
            }
        }

        when (auth) {
            is AuthConfig.Bearer -> resolve(auth.token)?.takeIf { it.isNotEmpty() }?.let { headers.set("Authorization", "Bearer $it") }
            // Some APIs (Stripe, for one) use the key as the username and an empty password.
            is AuthConfig.Basic -> resolve(auth.username)?.takeIf { it.isNotEmpty() }?.let { user ->
                val encoded = Base64.getEncoder().encodeToString("$user:${resolve(auth.password).orEmpty()}".toByteArray(Charsets.UTF_8))
                headers.set("Authorization", "Basic $encoded")
            }
            is AuthConfig.ApiKey -> if (auth.location == ApiKeyLocation.HEADER) {
                val name = resolve(auth.name).orEmpty()
                val value = resolve(auth.value).orEmpty()
                if (name.isNotEmpty() && value.isNotEmpty()) headers.set(name, value)
            }
            AuthConfig.None -> Unit
        }

        return PreparedRequest(method, finalUrl, headers.toList(), body)
    }

    /** An absolute http(s) URL, with characters that browsers accept but [URI] does not percent-encoded. */
    fun parseHttpUrl(text: String): URI? {
        if (text.isBlank()) return null
        val escaped = buildString {
            for (ch in text.trim()) {
                when (ch) {
                    ' ' -> append("%20")
                    '"', '<', '>', '\\', '^', '`', '{', '|', '}' -> append("%%%02X".format(ch.code))
                    else -> append(ch)
                }
            }
        }
        return try {
            val uri = URI(escaped)
            val scheme = uri.scheme?.lowercase()
            if ((scheme == "http" || scheme == "https") && !uri.host.isNullOrEmpty()) uri else null
        } catch (_: URISyntaxException) {
            null
        }
    }

    /** Sets query parameters like `URLSearchParams.set`: an existing key is replaced, others are kept. */
    private fun withQueryParams(uri: URI, params: Map<String, String>): String {
        val kept = uri.rawQuery.orEmpty().split('&').filter { it.isNotEmpty() }.filter { pair ->
            val key = formDecode(pair.substringBefore('='))
            key !in params
        }
        val added = params.map { (key, value) -> "${formEncode(key)}=${formEncode(value)}" }
        val query = (kept + added).joinToString("&")
        val base = uri.toASCIIString().substringBefore('#').substringBefore('?')
        val fragment = uri.rawFragment?.let { "#$it" }.orEmpty()
        return "$base?$query$fragment"
    }

    private fun parseJson(text: String) = Json.parseOrNull(text)

    private fun formEncode(text: String): String = URLEncoder.encode(text, Charsets.UTF_8)

    private fun formDecode(text: String): String = try {
        URLDecoder.decode(text, Charsets.UTF_8)
    } catch (_: IllegalArgumentException) {
        text
    }

    /** Header names are case-insensitive: setting `content-type` replaces `Content-Type`. */
    private class HeaderMap {
        private val entries = LinkedHashMap<String, Pair<String, String>>()
        fun set(name: String, value: String) {
            entries[name.lowercase()] = name to value
        }
        fun setIfAbsent(name: String, value: String) {
            if (name.lowercase() !in entries) set(name, value)
        }
        fun get(name: String) = entries[name.lowercase()]?.second
        fun toList() = entries.values.toList()
    }
}
