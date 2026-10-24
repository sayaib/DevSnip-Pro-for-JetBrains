package pro.devsnip.api.ide

import com.google.gson.reflect.TypeToken
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.RoamingType
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.diagnostic.debug
import pro.devsnip.api.core.ApiRequest
import pro.devsnip.api.core.ApiResponse
import pro.devsnip.api.core.CancellationToken
import pro.devsnip.api.core.Curl
import pro.devsnip.api.core.Environment
import pro.devsnip.api.core.HistoryItem
import pro.devsnip.api.core.HistoryLog
import pro.devsnip.api.core.HistorySnapshots
import pro.devsnip.api.core.HttpEngine
import pro.devsnip.api.core.OAuthClient
import pro.devsnip.api.core.OAuthTokenRequest
import pro.devsnip.api.core.OAuthTokenResult
import pro.devsnip.api.core.Redaction
import pro.devsnip.api.core.RequestBuilder
import pro.devsnip.api.core.RequestFailedException
import java.net.ProxySelector
import java.net.URI
import java.util.UUID

/** Persisted, non-secret state. Secret values are in the password safe, never here. */
class ApiClientState {
    var defaultTimeoutMs: Long = 30_000
    var historyJson: String = ""
    var environmentsJson: String = ""
    var activeEnvironment: String? = null
}

/** An environment as stored: values of secret-named variables are blank here and kept in [SecretStore]. */
data class StoredEnvironment(val name: String, val variables: Map<String, String>, val secretKeys: List<String>)

/**
 * The REST API client's backend inside the IDE: settings, environments,
 * history and request execution. Mirrors `ApiTester` in the VS Code
 * extension, with secrets moved from plain global state to the password safe.
 *
 * Methods that touch the network or the password safe block, and are called
 * from background tasks only.
 */
@Service(Service.Level.APP)
@State(name = "DevSnipApiClient", storages = [Storage(value = "devsnip-api-client.xml", roamingType = RoamingType.DISABLED)])
class ApiClientService : PersistentStateComponent<ApiClientState> {
    private var state = ApiClientState()
    private val lock = Any()
    private var history = HistoryLog()
    private var environments: List<StoredEnvironment> = emptyList()

    internal var secrets: SecretStore = PasswordSafeSecretStore()
    val engine = HttpEngine(defaultProxySelector = { ProxySelector.getDefault() })
    private val oauth = OAuthClient(engine)

    override fun getState(): ApiClientState = synchronized(lock) {
        state.historyJson = history.toStorageJson()
        state.environmentsJson = pro.devsnip.api.core.Json.toJson(environments)
        state
    }

    override fun loadState(loaded: ApiClientState) = synchronized(lock) {
        state = loaded
        history = HistoryLog.fromStorageJson(loaded.historyJson)
        environments = parseEnvironments(loaded.environmentsJson)
    }

    var defaultTimeoutMs: Long
        get() = synchronized(lock) { state.defaultTimeoutMs.takeIf { it in 1..ApiRequest.MAX_TIMEOUT_MS } ?: 30_000 }
        set(value) = synchronized(lock) { state.defaultTimeoutMs = value.coerceIn(1, ApiRequest.MAX_TIMEOUT_MS) }

    // ------------------------------------------------------------ environments

    fun environmentNames(): List<String> = synchronized(lock) { environments.map { it.name } }

    var activeEnvironmentName: String?
        get() = synchronized(lock) { state.activeEnvironment?.takeIf { name -> environments.any { it.name == name } } }
        set(value) = synchronized(lock) { state.activeEnvironment = value }

    /** The environment with its secret values filled in. Blocks on the password safe. */
    fun loadEnvironment(name: String?): Environment? {
        val stored = synchronized(lock) { environments.firstOrNull { it.name == name } } ?: return null
        val variables = LinkedHashMap(stored.variables)
        stored.secretKeys.forEach { key -> variables[key] = secrets.get(secretKey(stored.name, key)).orEmpty() }
        return Environment(stored.name, variables)
    }

    fun loadAllEnvironments(): List<Environment> = environmentNames().mapNotNull { loadEnvironment(it) }

    /**
     * Replaces all environments. Variables whose names look secret (token,
     * password, key, ...) go to the password safe. Blocks.
     */
    fun saveEnvironments(updated: List<Environment>) {
        val previous = synchronized(lock) { environments }
        val stored = updated.filter { it.name.isNotBlank() }.distinctBy { it.name.trim() }.map { env ->
            val name = env.name.trim()
            val clean = env.variables.mapKeys { it.key.trim() }.filterKeys { it.isNotEmpty() }
            val secretKeys = clean.keys.filter { Redaction.isSensitiveName(it) }
            secretKeys.forEach { key -> secrets.set(secretKey(name, key), clean[key]) }
            StoredEnvironment(name, clean.mapValues { (k, v) -> if (k in secretKeys) "" else v }, secretKeys)
        }
        // Remove secrets that no longer belong to any environment.
        for (old in previous) {
            val now = stored.firstOrNull { it.name == old.name }
            old.secretKeys.filter { now == null || it !in now.secretKeys }.forEach { secrets.set(secretKey(old.name, it), null) }
        }
        synchronized(lock) {
            environments = stored
            if (stored.none { it.name == state.activeEnvironment }) state.activeEnvironment = null
        }
    }

    private fun secretKey(environment: String, variable: String) = "env/$environment/$variable"

    // ----------------------------------------------------------------- requests

    /** Sends a request against the active environment and records it in history. Blocks. */
    fun send(request: ApiRequest, cancellation: CancellationToken): ApiResponse {
        val environment = loadEnvironment(activeEnvironmentName)
        val timeout = defaultTimeoutMs
        val resolvedUrl = runCatching { RequestBuilder.build(request, environment).url }.getOrDefault(request.url)
        LOG.debug { "Sending ${request.method} ${Redaction.redactUrl(resolvedUrl)}" }
        try {
            val response = engine.execute(request, environment, timeout, cancellation)
            record(request, resolvedUrl, response.status, response.responseTimeMs, response.sizeBytes, response.attempts)
            LOG.debug { "${request.method} ${Redaction.redactUrl(resolvedUrl)} -> ${response.status} in ${response.responseTimeMs} ms" }
            return response
        } catch (e: RequestFailedException) {
            record(request, resolvedUrl, 0, e.responseTimeMs, null, e.attempts)
            // Only the error kind is logged: no headers, bodies or credentials.
            LOG.debug { "${request.method} ${Redaction.redactUrl(resolvedUrl)} failed: ${e.code ?: e.description.title}" }
            throw e
        }
    }

    private fun record(request: ApiRequest, url: String, status: Int, time: Long, size: Long?, attempts: Int) {
        synchronized(lock) {
            history.add(
                HistoryItem(
                    id = UUID.randomUUID().toString(),
                    url = url,
                    method = request.method.uppercase(),
                    timestamp = System.currentTimeMillis(),
                    status = status,
                    responseTimeMs = time,
                    sizeBytes = size,
                    attempts = attempts,
                    request = HistorySnapshots.snapshot(request),
                ),
            )
        }
    }

    /** The cURL equivalent of a request, including stored cookies. Blocks (environment secrets). */
    fun toCurl(request: ApiRequest): String {
        val prepared = RequestBuilder.build(request, loadEnvironment(activeEnvironmentName))
        val host = runCatching { URI(prepared.url).host }.getOrNull()
        return Curl.generate(prepared, host?.let { engine.cookieJar.headerFor(it) }, request.verifySsl, request.proxy)
    }

    fun history(): List<HistoryItem> = synchronized(lock) { history.all() }

    fun clearHistory() = synchronized(lock) { history.clear() }

    fun exportHistory(csv: Boolean): String = synchronized(lock) { if (csv) history.exportCsv() else history.exportJson() }

    // -------------------------------------------------------------------- OAuth

    /** Fetches a token; on success the token is stored in the password safe. Blocks. */
    fun fetchOAuthToken(request: OAuthTokenRequest, cancellation: CancellationToken): OAuthTokenResult {
        val result = oauth.requestToken(request, loadEnvironment(activeEnvironmentName), cancellation)
        if (result.ok && result.accessToken != null) secrets.set(OAUTH_TOKEN_KEY, result.accessToken)
        return result
    }

    fun storedOAuthToken(): String? = secrets.get(OAUTH_TOKEN_KEY)

    private fun parseEnvironments(json: String?): List<StoredEnvironment> {
        if (json.isNullOrBlank()) return emptyList()
        return try {
            val type = object : TypeToken<List<StoredEnvironment>>() {}.type
            val parsed: List<StoredEnvironment?>? = com.google.gson.Gson().fromJson(json, type)
            parsed.orEmpty().filterNotNull().filter { !it.name.isNullOrBlank() }.map {
                @Suppress("USELESS_ELVIS")
                StoredEnvironment(it.name, it.variables ?: emptyMap(), it.secretKeys ?: emptyList())
            }
        } catch (e: Exception) {
            LOG.warn("Stored environments could not be read; starting with none.")
            emptyList()
        }
    }

    companion object {
        private val LOG = Logger.getInstance(ApiClientService::class.java)
        private const val OAUTH_TOKEN_KEY = "oauth/accessToken"

        fun getInstance(): ApiClientService = ApplicationManager.getApplication().getService(ApiClientService::class.java)
    }
}
