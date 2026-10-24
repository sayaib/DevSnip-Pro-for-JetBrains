package pro.devsnip.api.core

/**
 * The request model, ported from `ApiRequest` in the VS Code extension's
 * `src/commands/api-test.ts`. Values are templates: `{{variables}}` are
 * resolved against the active environment when the request is built.
 *
 * This package has no IntelliJ dependency, so everything here is unit-tested
 * without starting an IDE.
 */
data class ApiRequest(
    val method: String = "GET",
    val url: String = "",
    val data: String? = null,
    val params: List<KeyValue> = emptyList(),
    val headers: List<KeyValue> = emptyList(),
    val auth: AuthConfig = AuthConfig.None,
    /** Milliseconds; null or <= 0 uses the configured default. */
    val timeoutMs: Long? = null,
    val requestType: RequestType = RequestType.REST,
    val graphqlQuery: String? = null,
    val graphqlVariables: String? = null,
    val graphqlOperationName: String? = null,
    val bodyType: BodyType = BodyType.JSON,
    /** Automatic retries on network errors / retryable status codes (max 5). */
    val retries: Int = 0,
    /** Base delay for exponential backoff between retries. */
    val retryDelayMs: Long = 500,
    /** Status codes that trigger a retry. Empty means [DEFAULT_RETRY_STATUS_CODES]. */
    val retryStatusCodes: List<Int> = emptyList(),
    val followRedirects: Boolean = true,
    val maxRedirects: Int = 5,
    /** false disables TLS certificate and host name verification. */
    val verifySsl: Boolean = true,
    /** Explicit proxy; null uses the default (the IDE's proxy settings inside the IDE). */
    val proxy: ProxyConfig? = null,
) {
    companion object {
        val DEFAULT_RETRY_STATUS_CODES = listOf(429, 502, 503, 504)
        const val MAX_TIMEOUT_MS = 300_000L
    }
}

/** One row of a params or headers table. Disabled rows are kept but not sent. */
data class KeyValue(val key: String, val value: String, val enabled: Boolean = true)

enum class RequestType { REST, GRAPHQL }

enum class BodyType(val label: String) {
    JSON("JSON"),
    TEXT("Text"),
    FORM_URLENCODED("Form URL-encoded");

    override fun toString() = label
}

sealed class AuthConfig {
    abstract val typeName: String

    object None : AuthConfig() {
        override val typeName = "None"
    }

    data class Bearer(val token: String) : AuthConfig() {
        override val typeName = "Bearer"
        override fun toString() = "Bearer(token=${Redaction.mask(token)})"
    }

    data class Basic(val username: String, val password: String) : AuthConfig() {
        override val typeName = "Basic"
        override fun toString() = "Basic(username=$username, password=***)"
    }

    data class ApiKey(val name: String, val value: String, val location: ApiKeyLocation = ApiKeyLocation.HEADER) : AuthConfig() {
        override val typeName = "ApiKey"
        override fun toString() = "ApiKey(name=$name, value=${Redaction.mask(value)}, location=$location)"
    }
}

enum class ApiKeyLocation { HEADER, QUERY }

data class ProxyConfig(val host: String, val port: Int)

data class Environment(val name: String, val variables: Map<String, String>)

/** The resolved request, ready to be sent. Produced by [RequestBuilder]. */
data class PreparedRequest(
    val method: String,
    val url: String,
    /** Ordered; names keep the casing the user typed. */
    val headers: List<Pair<String, String>>,
    val body: String?,
) {
    fun header(name: String): String? = headers.lastOrNull { it.first.equals(name, ignoreCase = true) }?.second

    override fun toString(): String =
        "PreparedRequest(method=$method, url=${Redaction.redactUrl(url)}, headers=${Redaction.sanitizeHeaders(headers)}, body=${body?.let { "${it.length} chars" }})"
}

data class ApiResponse(
    val status: Int,
    val statusText: String,
    /** Response headers as received; a name can repeat (Set-Cookie). */
    val headers: List<Pair<String, String>>,
    val body: DecodedBody,
    val sizeBytes: Long,
    val responseTimeMs: Long,
    val attempts: Int,
    val truncated: Boolean,
    /** The URL of the final response, after redirects. */
    val finalUrl: String,
    val redirects: Int,
    /** Things the client changed or skipped (for example a header the JDK does not allow). */
    val warnings: List<String> = emptyList(),
)

/** A request that produced no response, explained as in the VS Code client. */
class RequestFailedException(
    val description: RequestErrorDescription,
    val code: String?,
    val responseTimeMs: Long,
    val attempts: Int,
    cause: Throwable?,
) : Exception(description.title + ": " + description.hint, cause)

class RequestCancelledException : Exception("Request was cancelled")

/** Thrown while building a request (bad URL, bad JSON, ...), before anything is sent. */
class RequestBuildException(message: String) : IllegalArgumentException(message)
