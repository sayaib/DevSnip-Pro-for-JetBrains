package pro.devsnip.api.ide.web

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonNull
import com.google.gson.JsonObject
import com.google.gson.JsonPrimitive
import pro.devsnip.api.core.ApiKeyLocation
import pro.devsnip.api.core.ApiRequest
import pro.devsnip.api.core.ApiResponse
import pro.devsnip.api.core.AuthConfig
import pro.devsnip.api.core.BodyType
import pro.devsnip.api.core.DecodedBody
import pro.devsnip.api.core.Environment
import pro.devsnip.api.core.HistoryItem
import pro.devsnip.api.core.KeyValue
import pro.devsnip.api.core.ProxyConfig
import pro.devsnip.api.core.RequestType
import java.math.BigDecimal
import java.math.RoundingMode

/**
 * The REST API Client page's message shapes (src/commands/api-test.ts in the
 * VS Code extension) mapped to and from the Kotlin engine's model.
 */
object RestClientWire {
    /** buildApiRequestFromMessage(): the page's request payload as an [ApiRequest]. */
    fun request(message: JsonObject): ApiRequest {
        val authType = message.str("authType")
        val auth = when (authType) {
            "Bearer" -> AuthConfig.Bearer(message.str("authToken"))
            "Basic" -> AuthConfig.Basic(message.str("username"), message.str("password"))
            "ApiKey" -> AuthConfig.ApiKey(
                message.str("apiKeyName"),
                message.str("apiKeyValue"),
                if (message.str("apiKeyLocation") == "query") ApiKeyLocation.QUERY else ApiKeyLocation.HEADER,
            )
            else -> AuthConfig.None
        }
        val proxy = (message["proxy"] as? JsonObject)?.let { p ->
            val host = p.str("host").trim()
            if (host.isEmpty()) null else ProxyConfig(host, p.num("port")?.toInt()?.takeIf { it in 1..65535 } ?: 8080)
        }
        val data = message["data"]?.let { if (it.isJsonPrimitive) it.asString else if (it.isJsonNull) null else it.toString() }
        return ApiRequest(
            method = message.str("method").ifEmpty { "GET" },
            url = message.str("url"),
            data = data?.ifEmpty { null },
            params = rows(message["params"]),
            headers = rows(message["headers"]),
            auth = auth,
            timeoutMs = message.num("timeout")?.toLong()?.takeIf { it > 0 },
            requestType = if (message.str("requestType") == "graphql") RequestType.GRAPHQL else RequestType.REST,
            graphqlQuery = message.str("graphqlQuery").ifEmpty { null },
            graphqlVariables = message.str("graphqlVariables").ifEmpty { null },
            graphqlOperationName = message.str("graphqlOperationName").ifEmpty { null },
            bodyType = bodyType(message.str("bodyType")),
            retries = message.num("retries")?.toInt()?.coerceIn(0, 5) ?: 0,
            retryDelayMs = message.num("retryDelay")?.toLong()?.coerceAtLeast(0) ?: 500,
            retryStatusCodes = (message["retryStatusCodes"] as? JsonArray)?.mapNotNull { e -> e.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }?.asInt }.orEmpty(),
            followRedirects = message["followRedirects"]?.takeIf { it.isJsonPrimitive }?.asBoolean ?: true,
            maxRedirects = message.num("maxRedirects")?.toInt()?.coerceAtLeast(0) ?: 5,
            verifySsl = message["rejectUnauthorized"]?.takeIf { it.isJsonPrimitive }?.asBoolean ?: true,
            proxy = proxy,
        )
    }

    fun bodyType(wire: String): BodyType = when (wire) {
        "text" -> BodyType.TEXT
        "form-urlencoded" -> BodyType.FORM_URLENCODED
        else -> BodyType.JSON
    }

    fun bodyTypeWire(type: BodyType): String = when (type) {
        BodyType.JSON -> "json"
        BodyType.TEXT -> "text"
        BodyType.FORM_URLENCODED -> "form-urlencoded"
    }

    private fun rows(value: JsonElement?): List<KeyValue> {
        val obj = value as? JsonObject ?: return emptyList()
        return obj.entrySet().filter { it.value.isJsonPrimitive }.map { (k, v) -> KeyValue(k, v.asString) }
    }

    /** What makeRequest() resolves with: the apiResponse message without its command. */
    fun response(response: ApiResponse): JsonObject = JsonObject().apply {
        addProperty("status", response.status)
        addProperty("statusText", response.statusText)
        add("headers", headers(response.headers))
        add("data", data(response.body))
        addProperty("responseTime", response.responseTimeMs)
        addProperty("size", formatBytes(response.sizeBytes))
        addProperty("truncated", response.truncated)
        addProperty("attempts", response.attempts)
    }

    /** Node's response headers: lower-case names, set-cookie as an array, repeats joined with ", ". */
    fun headers(headers: List<Pair<String, String>>): JsonObject {
        val grouped = LinkedHashMap<String, MutableList<String>>()
        for ((name, value) in headers) grouped.getOrPut(name.lowercase()) { mutableListOf() } += value
        return JsonObject().apply {
            for ((name, values) in grouped) {
                if (name == "set-cookie") add(name, JsonArray().apply { values.forEach(::add) }) else addProperty(name, values.joinToString(", "))
            }
        }
    }

    /** decodeBody(): JSON as JSON, text as text, binary described. */
    fun data(body: DecodedBody): JsonElement = when (body) {
        DecodedBody.Empty -> JsonPrimitive("")
        is DecodedBody.JsonBody -> body.element
        is DecodedBody.Text -> JsonPrimitive(body.text)
        is DecodedBody.Binary -> JsonPrimitive(
            "[Binary response: ${body.contentType.ifEmpty { "unknown type" }}, ${binarySize(body.size)}. " +
                "It is not shown as text; use cURL export or save it from your app to inspect the bytes.]",
        )
    }

    private fun binarySize(bytes: Long): String = when {
        bytes < 1024 -> "$bytes B"
        bytes < 1_048_576 -> "${fixed(bytes / 1024.0, 1)} KB"
        else -> "${fixed(bytes / 1_048_576.0, 1)} MB"
    }

    /** ApiTester.formatBytes(): "0 B", "512 B", "1.5 KB", "2 MB". */
    fun formatBytes(bytes: Long): String {
        if (bytes <= 0) return "0 B"
        val units = listOf("B", "KB", "MB", "GB")
        val i = minOf((Math.log(bytes.toDouble()) / Math.log(1024.0)).toInt(), units.size - 1)
        val value = BigDecimal(bytes / Math.pow(1024.0, i.toDouble())).setScale(2, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString()
        return "$value ${units[i]}"
    }

    private fun fixed(value: Double, digits: Int) = BigDecimal(value).setScale(digits, RoundingMode.HALF_UP).toPlainString()

    /** ApiHistoryItem[]: the snapshot carries the URL only when it is a template, as in VS Code. */
    fun history(items: List<HistoryItem>): JsonArray = JsonArray().apply {
        items.forEach { item ->
            add(JsonObject().apply {
                addProperty("id", item.id)
                addProperty("url", item.url)
                addProperty("method", item.method)
                addProperty("timestamp", item.timestamp)
                item.status?.let { addProperty("status", it) }
                item.responseTimeMs?.let { addProperty("responseTime", it) }
                item.sizeBytes?.let { addProperty("size", it) }
                addProperty("attempts", item.attempts)
                item.request?.let { snap ->
                    add("request", JsonObject().apply {
                        if (snap.url.contains("{{")) addProperty("url", snap.url)
                        add("headers", JsonObject().apply { snap.headers.filter { it.enabled }.forEach { addProperty(it.key, it.value) } })
                        snap.data?.let { addProperty("data", it) }
                        addProperty("bodyType", bodyTypeWire(snap.bodyType))
                        addProperty("requestType", if (snap.requestType == RequestType.GRAPHQL) "graphql" else "rest")
                        if (snap.requestType == RequestType.GRAPHQL) {
                            addProperty("graphqlQuery", snap.graphqlQuery.orEmpty())
                            addProperty("graphqlVariables", snap.graphqlVariables.orEmpty())
                            snap.graphqlOperationName?.let { addProperty("graphqlOperationName", it) }
                        }
                        if (snap.authType != "None") addProperty("authType", snap.authType)
                    })
                }
            })
        }
    }

    /** parseCurlCommand()'s partial request. */
    fun parsedCurl(request: ApiRequest): JsonObject = JsonObject().apply {
        addProperty("method", request.method)
        addProperty("url", request.url)
        add("headers", JsonObject().apply { request.headers.forEach { addProperty(it.key, it.value) } })
        request.data?.let { addProperty("data", it) }
        (request.auth as? AuthConfig.Basic)?.let {
            addProperty("authType", "Basic")
            addProperty("username", it.username)
            addProperty("password", it.password)
        }
        if (!request.verifySsl) addProperty("rejectUnauthorized", false)
        if (request.followRedirects) addProperty("followRedirects", true)
        request.proxy?.let { p -> add("proxy", JsonObject().apply { addProperty("host", p.host); addProperty("port", p.port) }) }
    }

    fun environments(list: List<Environment>): JsonArray = JsonArray().apply {
        list.forEach { env ->
            add(JsonObject().apply {
                addProperty("name", env.name)
                add("variables", JsonObject().apply { env.variables.forEach { (k, v) -> addProperty(k, v) } })
            })
        }
    }

    fun environment(value: JsonElement?): Environment {
        val obj = value as? JsonObject ?: JsonObject()
        val variables = LinkedHashMap<String, String>()
        (obj["variables"] as? JsonObject)?.entrySet()?.forEach { (k, v) ->
            variables[k] = if (v.isJsonPrimitive) v.asString else if (v.isJsonNull) "" else v.toString()
        }
        return Environment(obj.str("name"), variables)
    }

    fun JsonObject.str(key: String): String = get(key)?.takeIf { it.isJsonPrimitive }?.asString ?: ""

    fun JsonObject.num(key: String): Double? = get(key)?.takeIf { it.isJsonPrimitive }?.let { runCatching { it.asDouble }.getOrNull() }?.takeIf { it.isFinite() }

    val NULL: JsonElement = JsonNull.INSTANCE
}
