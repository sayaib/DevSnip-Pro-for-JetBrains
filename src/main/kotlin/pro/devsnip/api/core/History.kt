package pro.devsnip.api.core

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.reflect.TypeToken
import java.time.Instant

/**
 * What is needed to reopen a request from history. Values are the request's
 * templates (with `{{variables}}` unresolved); anything that looks like a
 * literal credential is left out. Port of `historySnapshot()`.
 */
data class HistorySnapshot(
    val url: String,
    val method: String,
    val headers: List<KeyValue> = emptyList(),
    val params: List<KeyValue> = emptyList(),
    val data: String? = null,
    val bodyType: BodyType = BodyType.JSON,
    val requestType: RequestType = RequestType.REST,
    val graphqlQuery: String? = null,
    val graphqlVariables: String? = null,
    val graphqlOperationName: String? = null,
    val authType: String = "None",
    /** Non-secret auth fields (user name, API key name and location) and secret fields that are only `{{variable}}` references. */
    val auth: Map<String, String> = emptyMap(),
)

data class HistoryItem(
    val id: String,
    /** The resolved URL, with credential-looking query values redacted. */
    val url: String,
    val method: String,
    val timestamp: Long,
    val status: Int? = null,
    val responseTimeMs: Long? = null,
    val sizeBytes: Long? = null,
    val attempts: Int = 1,
    val request: HistorySnapshot? = null,
)

object HistorySnapshots {
    const val BODY_LIMIT = 20_000
    private val VARIABLE_ONLY = Regex("^\\{\\{\\w+\\}\\}$")

    fun snapshot(request: ApiRequest): HistorySnapshot {
        fun keep(row: KeyValue) = !Redaction.isSensitiveName(row.key) || Redaction.VARIABLE_ONLY.matches(row.value.trim())
        val auth = when (val a = request.auth) {
            is AuthConfig.Bearer -> mapOfNotNull("token" to variableOnly(a.token))
            is AuthConfig.Basic -> mapOfNotNull("username" to a.username.ifEmpty { null }, "password" to variableOnly(a.password))
            is AuthConfig.ApiKey -> mapOfNotNull("keyName" to a.name.ifEmpty { null }, "keyLocation" to a.location.name, "keyValue" to variableOnly(a.value))
            AuthConfig.None -> emptyMap()
        }
        val graphql = request.requestType == RequestType.GRAPHQL
        return HistorySnapshot(
            url = Redaction.redactTemplateUrl(request.url),
            method = request.method.uppercase(),
            headers = request.headers.filter(::keep),
            params = request.params.filter(::keep),
            data = request.data?.takeIf { it.length <= BODY_LIMIT },
            bodyType = request.bodyType,
            requestType = request.requestType,
            graphqlQuery = if (graphql) request.graphqlQuery?.take(BODY_LIMIT) else null,
            graphqlVariables = if (graphql) request.graphqlVariables?.take(BODY_LIMIT) else null,
            graphqlOperationName = if (graphql) request.graphqlOperationName else null,
            authType = request.auth.typeName,
            auth = auth,
        )
    }

    /** Turns a snapshot back into a request for the form. Secrets that were dropped come back empty. */
    fun toRequest(snapshot: HistorySnapshot): ApiRequest {
        val auth = when (snapshot.authType) {
            "Bearer" -> AuthConfig.Bearer(snapshot.auth["token"].orEmpty())
            "Basic" -> AuthConfig.Basic(snapshot.auth["username"].orEmpty(), snapshot.auth["password"].orEmpty())
            "ApiKey" -> AuthConfig.ApiKey(
                snapshot.auth["keyName"].orEmpty(),
                snapshot.auth["keyValue"].orEmpty(),
                runCatching { ApiKeyLocation.valueOf(snapshot.auth["keyLocation"].orEmpty()) }.getOrDefault(ApiKeyLocation.HEADER),
            )
            else -> AuthConfig.None
        }
        return ApiRequest(
            method = snapshot.method,
            url = snapshot.url,
            data = snapshot.data,
            params = snapshot.params,
            headers = snapshot.headers,
            auth = auth,
            requestType = snapshot.requestType,
            graphqlQuery = snapshot.graphqlQuery,
            graphqlVariables = snapshot.graphqlVariables,
            graphqlOperationName = snapshot.graphqlOperationName,
            bodyType = snapshot.bodyType,
        )
    }

    private fun variableOnly(value: String): String? = value.trim().takeIf { VARIABLE_ONLY.matches(it) }

    private fun mapOfNotNull(vararg pairs: Pair<String, String?>): Map<String, String> =
        pairs.mapNotNull { (k, v) -> v?.let { k to it } }.toMap()
}

/** The most recent requests, newest first. Not thread-safe; the IDE service guards it. */
class HistoryLog(items: List<HistoryItem> = emptyList(), private val maxSize: Int = MAX_SIZE) {
    private val items = items.take(maxSize).toMutableList()

    fun all(): List<HistoryItem> = items.toList()

    fun add(item: HistoryItem) {
        items.add(0, item.copy(url = Redaction.redactUrl(item.url)))
        while (items.size > maxSize) items.removeAt(items.size - 1)
    }

    fun clear() = items.clear()

    /** JSON export leaves the reopen snapshot out: it is for this machine only. */
    fun exportJson(): String {
        val array = JsonArray()
        items.forEach { item ->
            array.add(JsonObject().apply {
                addProperty("id", item.id)
                addProperty("url", item.url)
                addProperty("method", item.method)
                addProperty("timestamp", item.timestamp)
                item.status?.let { addProperty("status", it) }
                item.responseTimeMs?.let { addProperty("responseTime", it) }
                item.sizeBytes?.let { addProperty("size", it) }
                addProperty("attempts", item.attempts)
            })
        }
        return Json.prettyPrint(array)
    }

    fun exportCsv(): String {
        val header = "id,method,url,status,responseTime,size,attempts,timestamp"
        val rows = items.map { h ->
            listOf(csv(h.id), csv(h.method), csv(h.url), h.status?.toString().orEmpty(), h.responseTimeMs?.toString().orEmpty(), h.sizeBytes?.toString().orEmpty(), h.attempts.toString(), Instant.ofEpochMilli(h.timestamp).toString())
                .joinToString(",")
        }
        return (listOf(header) + rows).joinToString("\n")
    }

    /** Serialised form for IDE storage (includes snapshots). */
    fun toStorageJson(): String = Json.toJson(items)

    companion object {
        const val MAX_SIZE = 50

        fun fromStorageJson(json: String?): HistoryLog {
            if (json.isNullOrBlank()) return HistoryLog()
            return try {
                val type = object : TypeToken<List<HistoryItem>>() {}.type
                val parsed: List<HistoryItem>? = com.google.gson.Gson().fromJson(json, type)
                HistoryLog(parsed.orEmpty().filter { it.id.isNotEmpty() && it.method.isNotEmpty() })
            } catch (_: Exception) {
                HistoryLog()
            }
        }

        /** Quotes a CSV field and neutralises spreadsheet formula injection. */
        fun csv(value: String): String {
            val safe = if (value.firstOrNull() in setOf('=', '+', '-', '@', '\t', '\r')) "'$value" else value
            return "\"" + safe.replace("\"", "\"\"") + "\""
        }
    }
}
