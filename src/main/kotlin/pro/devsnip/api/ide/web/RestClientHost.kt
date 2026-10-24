package pro.devsnip.api.ide.web

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonNull
import com.google.gson.JsonObject
import com.google.gson.JsonPrimitive
import com.intellij.openapi.diagnostic.Logger
import pro.devsnip.api.core.CancellationToken
import pro.devsnip.api.core.Curl
import pro.devsnip.api.core.OAuthGrant
import pro.devsnip.api.core.OAuthTokenRequest
import pro.devsnip.api.core.RawHttp
import pro.devsnip.api.core.RawHttpException
import pro.devsnip.api.core.RawRequest
import pro.devsnip.api.core.RequestCancelledException
import pro.devsnip.api.core.RequestErrors
import pro.devsnip.api.core.RequestFailedException
import pro.devsnip.api.core.TlsProbe
import pro.devsnip.api.ide.ApiClientService
import pro.devsnip.api.ide.web.RestClientWire.num
import pro.devsnip.api.ide.web.RestClientWire.str
import java.io.File
import java.net.SocketTimeoutException
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap

/** What the REST client page needs from the IDE besides the engine: dialogs, notifications, storage. Called on the UI thread. */
interface RestClientUi {
    /** A save dialog; null when cancelled. [extensions] are without the dot. */
    fun chooseSaveFile(title: String, defaultName: String, extensions: List<String>): File?

    fun chooseOpenFile(title: String, extensions: List<String>): File?

    fun notify(kind: String, text: String)

    /** The page's global state (collections, prompt versions, ...); null removes the key. */
    fun setStorage(key: String, value: JsonElement?)
}

/**
 * The IDE side of the REST API Client page: the message loop of `apiTest()`
 * in the VS Code extension's api-test.ts, on top of [ApiClientService] (the
 * Kotlin engine, environments with secrets in the password safe, history,
 * cookies), plus the requests the page's in-page feature system makes
 * (`__rpc`: the client's sender, raw HTTP for the services, TLS probes,
 * files, storage).
 *
 * [handle] is called on the UI thread; network and password-safe work runs
 * through [background], never on it. [post] may be called from any thread.
 * Nothing here logs request contents or secrets.
 */
class RestClientHost(
    private val service: ApiClientService,
    private val ui: RestClientUi,
    private val post: (JsonObject) -> Unit,
    private val background: (() -> Unit) -> Unit,
    private val rawHttp: RawHttp = RawHttp(),
) {
    /** The Send button's request: a new Send (or a chained step) replaces it, as the client's cancel token does. */
    @Volatile
    private var current: CancellationToken? = null
    private val running = ConcurrentHashMap<String, CancellationToken>()
    private val writable = ConcurrentHashMap.newKeySet<String>()
    private val readable = ConcurrentHashMap.newKeySet<String>()

    @Volatile
    private var disposed = false

    fun handle(message: JsonObject) {
        if (disposed) return
        if (message.str("type") == "__rpc") {
            rpc(message)
            return
        }
        when (val command = message.str("command")) {
            "testAPI" -> testApi(message)
            "cancelRequest" -> {
                current?.cancel()
                current = null
                send("requestCancelled")
            }
            "generateCurl" -> background {
                try {
                    send("curlGenerated") { addProperty("curl", service.toCurl(RestClientWire.request(message))) }
                } catch (e: Exception) {
                    send("curlGenerated") { addProperty("error", e.message ?: "Failed to generate cURL command") }
                }
            }
            "parseCurl" -> try {
                send("curlParsed") { add("request", RestClientWire.parsedCurl(Curl.parse(message.str("curl")))) }
            } catch (e: Exception) {
                send("curlParsed") { addProperty("error", e.message ?: "Failed to parse cURL command") }
            }
            "exportHistory" -> exportHistory(if (message.str("format") == "csv") "csv" else "json")
            "saveResponse" -> saveResponse(message.str("body"))
            "getCookies" -> send("showCookies") {
                add("cookies", JsonObject().apply { service.engine.cookieJar.entries().forEach { (host, values) -> add(host, JsonArray().apply { values.forEach(::add) }) } })
            }
            "clearHistory" -> {
                service.clearHistory()
                send("historyCleared") { add("history", JsonArray()) }
            }
            "clearCookies" -> {
                service.engine.cookieJar.clear()
                send("cookiesCleared")
            }
            "getHistory" -> send("historyLoaded") { add("history", RestClientWire.history(service.history())) }
            "getEnvironments" -> background { sendEnvironments("showEnvironments") }
            "saveEnvironment" -> background {
                try {
                    val previous = message["previousName"]?.takeIf { it.isJsonPrimitive }?.asString
                    service.saveEnvironment(RestClientWire.environment(message["environment"]), previous)
                } catch (e: Exception) {
                    send("environmentError") { addProperty("error", e.message ?: "The environment could not be saved.") }
                    return@background
                }
                sendEnvironments("environmentSaved")
            }
            "deleteEnvironment" -> background {
                service.deleteEnvironment(message.str("name"))
                sendEnvironments("environmentDeleted")
            }
            "setActiveEnvironment" -> background {
                service.activeEnvironmentIndex = message.num("index")?.toInt() ?: -1
                sendEnvironments("environmentActivated")
            }
            // Milestones & rewards are not ported: there is no balance, and no feature needs one.
            "getPoints" -> send("showPoints") { addProperty("points", 0) }
            "feature:oauthToken" -> oauthToken(message)
            "feature:applyOauthToken" -> applyOauthToken(message)
            else -> if (command.isNotEmpty()) LOG.debug("REST client: ignored message \"$command\"")
        }
    }

    /** Cancels everything in flight (the tab was closed), as the panel's onDidDispose does. */
    fun dispose() {
        disposed = true
        current?.cancel()
        running.values.forEach { it.cancel() }
        running.clear()
    }

    // ---------------------------------------------------------------- requests

    private fun testApi(message: JsonObject) {
        send("requestStarted")
        val request = RestClientWire.request(message)
        val token = replaceCurrent()
        background {
            try {
                val response = service.send(request, token)
                send("apiResponse") {
                    RestClientWire.response(response).entrySet().forEach { (k, v) -> add(k, v) }
                    add("history", RestClientWire.history(service.history()))
                }
            } catch (_: RequestCancelledException) {
                // Reported by cancelRequest, or superseded by a newer request.
            } catch (e: RequestFailedException) {
                if (token.isCancelled) return@background
                send("apiError") {
                    addProperty("error", e.cause?.let(RequestErrors::messageFor) ?: e.description.title)
                    e.code?.let { addProperty("code", it) }
                    addProperty("title", e.description.title)
                    addProperty("hint", e.description.hint)
                    e.description.action?.let { addProperty("action", it.name.lowercase()) }
                    addProperty("status", 0)
                    addProperty("responseTime", e.responseTimeMs)
                    addProperty("attempts", e.attempts)
                    add("history", RestClientWire.history(service.history()))
                }
            } catch (e: Exception) {
                // Building the request failed (bad URL, undefined variable, invalid JSON body, ...).
                val text = e.message ?: "Request failed"
                val described = RequestErrors.describe(text, null)
                send("apiError") {
                    addProperty("error", text)
                    addProperty("title", described.title)
                    addProperty("hint", described.hint)
                    described.action?.let { addProperty("action", it.name.lowercase()) }
                    addProperty("status", 0)
                    add("history", RestClientWire.history(service.history()))
                }
            } finally {
                synchronized(this) { if (current === token) current = null }
            }
        }
    }

    private fun replaceCurrent(): CancellationToken = synchronized(this) {
        current?.cancel()
        CancellationToken().also { current = it }
    }

    /** The client's own sender for the feature system (chains, batch and load runs). Throws like makeRequest(). */
    private fun sendForFeature(params: JsonObject): JsonElement {
        val request = RestClientWire.request(params.getAsJsonObject("request") ?: JsonObject())
        val backgroundRun = params["background"]?.takeIf { it.isJsonPrimitive }?.asBoolean == true
        // A background run never cancels the user's request; a chained step replaces it, as in VS Code.
        val token = if (backgroundRun) CancellationToken() else replaceCurrent()
        val id = "send-${System.nanoTime()}"
        running[id] = token
        try {
            val response = service.send(request, token, recordHistory = !backgroundRun)
            return RestClientWire.response(response).apply { add("history", RestClientWire.history(service.history())) }
        } catch (e: RequestFailedException) {
            throw RpcError(e.cause?.let(RequestErrors::messageFor) ?: e.description.title, e.code)
        } catch (e: RequestCancelledException) {
            throw RpcError("Request was cancelled", "ERR_CANCELED")
        } finally {
            running.remove(id)
        }
    }

    private fun sendRaw(params: JsonObject, reply: (JsonElement?, RpcError?) -> Unit) {
        val id = params.str("id")
        val headers = (params["headers"] as? JsonObject)?.entrySet()?.filter { it.value.isJsonPrimitive }?.map { it.key to it.value.asString }.orEmpty()
        val request = RawRequest(
            method = params.str("method").ifEmpty { "GET" },
            url = params.str("url"),
            headers = headers,
            body = params["body"]?.takeIf { it.isJsonPrimitive }?.asString,
            timeoutMs = params.num("timeoutMs")?.toLong() ?: 0,
            maxRedirects = params.num("maxRedirects")?.toInt()?.coerceIn(0, 20) ?: 5,
            verifySsl = params["rejectUnauthorized"]?.takeIf { it.isJsonPrimitive }?.asBoolean ?: true,
            maxBytes = params.num("maxBytes")?.toLong() ?: 0,
        )
        val stream = params["stream"]?.takeIf { it.isJsonPrimitive }?.asBoolean == true
        val token = CancellationToken()
        running[id] = token
        var headersSent = false
        try {
            if (stream) {
                rawHttp.send(
                    request,
                    token,
                    onHeaders = { head ->
                        headersSent = true
                        reply(rawJson(head.status, head.statusText, head.headers, null), null)
                    },
                    onChunk = { chunk -> streamEvent(id, "data", chunk) },
                )
                streamEvent(id, "end", null)
            } else {
                val response = rawHttp.send(request, token)
                reply(rawJson(response.status, response.statusText, response.headers, response.body), null)
            }
        } catch (e: Exception) {
            val error = when (e) {
                is RequestCancelledException -> RpcError("canceled", "ERR_CANCELED")
                is RawHttpException -> RpcError(e.message ?: "The request failed.", e.code)
                else -> RpcError(RequestErrors.messageFor(e), RequestErrors.codeFor(e))
            }
            if (headersSent) streamEvent(id, "error", error.message) else reply(null, error)
        } finally {
            running.remove(id)
        }
    }

    private fun rawJson(status: Int, statusText: String, headers: List<Pair<String, String>>, body: String?) = JsonObject().apply {
        addProperty("status", status)
        addProperty("statusText", statusText)
        add("headers", RestClientWire.headers(headers))
        body?.let { addProperty("body", it) }
    }

    private fun streamEvent(id: String, event: String, value: String?) = post(JsonObject().apply {
        addProperty("type", "__stream")
        addProperty("id", id)
        addProperty("event", event)
        value?.let { addProperty("value", it) }
    })

    private fun tlsProbe(params: JsonObject): JsonElement {
        val host = params.str("host").ifEmpty { throw RpcError("The URL could not be parsed.", null) }
        val observation = try {
            TlsProbe.probe(host, params.num("port")?.toInt() ?: 443, params.str("servername").ifEmpty { host }, params.num("timeoutMs")?.toInt() ?: 15_000)
        } catch (e: SocketTimeoutException) {
            throw RpcError("The TLS handshake timed out.", "ETIMEDOUT")
        } catch (e: Exception) {
            throw RpcError(RequestErrors.messageFor(e), RequestErrors.codeFor(e))
        }
        return JsonObject().apply {
            addProperty("authorized", observation.authorized)
            observation.authorizationError?.let { addProperty("authorizationError", it) }
            observation.protocol?.let { addProperty("protocol", it) }
            observation.cipher?.let { addProperty("cipher", it) }
            add("certificate", JsonObject().apply {
                add("subject", JsonObject().apply { observation.subjectCn?.let { addProperty("CN", it) } })
                add("issuer", JsonObject().apply {
                    observation.issuerO?.let { addProperty("O", it) }
                    observation.issuerCn?.let { addProperty("CN", it) }
                })
                observation.validFrom?.let { addProperty("valid_from", it) }
                observation.validTo?.let { addProperty("valid_to", it) }
                observation.subjectAltNames?.let { addProperty("subjectaltname", it) }
                observation.keyBits?.let { addProperty("bits", it) }
                observation.nistCurve?.let { addProperty("nistCurve", it) }
            })
        }
    }

    // ------------------------------------------------------------------- OAuth

    /** feature:oauthToken: the token goes to the password safe; the page gets a masked preview. */
    private fun oauthToken(message: JsonObject) {
        val featureId = message.str("featureId").ifEmpty { "oauthToken" }
        val grant = OAuthGrant.entries.firstOrNull { it.wireName == message.str("grant").ifEmpty { "client_credentials" } }
        if (grant == null) {
            featureError(featureId, message, "\"${message.str("grant")}\" is not a supported grant type.")
            return
        }
        val request = OAuthTokenRequest(
            grant = grant,
            tokenUrl = message.str("tokenUrl"),
            clientId = message.str("clientId"),
            clientSecret = message.str("clientSecret").ifEmpty { null },
            scope = message.str("scope").ifEmpty { null },
            username = message.str("username").ifEmpty { null },
            password = message.str("password").ifEmpty { null },
            refreshToken = message.str("refreshToken").ifEmpty { null },
            useBasicAuth = message["useBasicAuth"]?.takeIf { it.isJsonPrimitive }?.asBoolean == true,
        )
        background {
            try {
                val result = service.fetchOAuthToken(request, CancellationToken())
                featureResult(featureId, message, JsonObject().apply {
                    addProperty("ok", result.ok)
                    result.error?.let { addProperty("error", it) }
                    result.tokenType?.let { addProperty("tokenType", it) }
                    result.expiresIn?.let { addProperty("expiresIn", it) }
                    result.scope?.let { addProperty("scope", it) }
                    result.safePreview?.let { addProperty("preview", it) }
                    addProperty("tokenAvailable", result.ok && result.accessToken != null)
                })
            } catch (e: Exception) {
                featureError(featureId, message, e.message ?: "The token request failed.")
            }
        }
    }

    /** feature:applyOauthToken: moves the stored token into the request's Authorization (the Auth tab). */
    private fun applyOauthToken(message: JsonObject) {
        val featureId = message.str("featureId").ifEmpty { "applyOauthToken" }
        background {
            val token = try {
                service.storedOAuthToken()
            } catch (e: Exception) {
                null
            }
            if (token.isNullOrEmpty()) {
                featureError(featureId, message, "Fetch a token first.")
                return@background
            }
            featureResult(featureId, message, JsonObject().apply {
                addProperty("authType", "Bearer")
                addProperty("authToken", token)
            })
        }
    }

    private fun featureResult(featureId: String, message: JsonObject, result: JsonObject) = send("featureResult") {
        addProperty("featureId", featureId)
        addProperty("requestId", message.str("requestId"))
        add("result", result)
        addProperty("pointsCharged", 0)
        addProperty("remainingPoints", 0)
    }

    private fun featureError(featureId: String, message: JsonObject, error: String) = send("featureError") {
        addProperty("featureId", featureId)
        addProperty("requestId", message.str("requestId"))
        addProperty("error", error)
        addProperty("remainingPoints", 0)
    }

    // ------------------------------------------------------------------- files

    private fun exportHistory(format: String) {
        val data = try {
            service.exportHistory(format == "csv")
        } catch (e: Exception) {
            send("historyExported") { addProperty("success", false); addProperty("error", e.message ?: "Export failed") }
            return
        }
        val target = ui.chooseSaveFile("Export History", "devsnip-api-history.$format", listOf(format)) ?: return
        background {
            try {
                target.parentFile?.mkdirs()
                target.writeText(data, Charsets.UTF_8)
                send("historyExported") { addProperty("success", true) }
                ui.notify("info", "API history exported to ${target.path}")
            } catch (e: Exception) {
                send("historyExported") { addProperty("success", false); addProperty("error", e.message ?: "Export failed") }
            }
        }
    }

    private fun saveResponse(body: String) {
        if (body.isEmpty()) {
            send("responseSaved") { addProperty("success", false); addProperty("error", "There is no response body to save.") }
            return
        }
        val target = ui.chooseSaveFile("Save Response", "response.json", listOf("json", "txt")) ?: return
        background {
            try {
                target.parentFile?.mkdirs()
                target.writeText(body, Charsets.UTF_8)
                send("responseSaved") { addProperty("success", true); addProperty("path", target.path) }
                ui.notify("info", "Response saved to ${target.path}")
            } catch (e: Exception) {
                send("responseSaved") { addProperty("success", false); addProperty("error", e.message ?: "Could not save the response.") }
            }
        }
    }

    private fun extensions(filters: JsonObject?): List<String> =
        filters?.entrySet()?.flatMap { (_, v) -> (v as? JsonArray)?.mapNotNull { it.takeIf { e -> e.isJsonPrimitive }?.asString } ?: emptyList() }?.filter { it != "*" }.orEmpty()

    // --------------------------------------------------------------------- rpc

    private class RpcError(message: String, val code: String?) : Exception(message)

    private fun rpc(message: JsonObject) {
        val id = message["id"] ?: JsonNull.INSTANCE
        val params = message.getAsJsonObject("params") ?: JsonObject()
        fun reply(result: JsonElement?, error: RpcError?) = post(JsonObject().apply {
            addProperty("type", "__rpcResult")
            add("id", id)
            if (error != null) {
                addProperty("error", error.message ?: "Failed")
                add("errorData", JsonObject().apply { error.code?.let { addProperty("code", it) } })
            } else {
                add("result", result ?: JsonNull.INSTANCE)
            }
        })
        fun guarded(block: () -> JsonElement?) = try {
            reply(block(), null)
        } catch (e: RpcError) {
            reply(null, e)
        } catch (e: Exception) {
            reply(null, RpcError(e.message ?: e.javaClass.simpleName, null))
        }

        when (val method = message.str("method")) {
            // Dialogs and storage belong to the UI thread (handle runs on it).
            "dialog.save" -> guarded {
                val file = ui.chooseSaveFile(params.str("title").ifEmpty { "Save" }, params.str("defaultName").ifEmpty { "devsnip.json" }, extensions(params.getAsJsonObject("filters")))
                file?.let { writable += it.absolutePath; JsonPrimitive(it.absolutePath) } ?: JsonNull.INSTANCE
            }
            "dialog.open" -> guarded {
                val file = ui.chooseOpenFile(params.str("title").ifEmpty { "Open" }, extensions(params.getAsJsonObject("filters")))
                file?.let { readable += it.absolutePath; JsonPrimitive(it.absolutePath) } ?: JsonNull.INSTANCE
            }
            "notify" -> guarded {
                ui.notify(params.str("kind").ifEmpty { "info" }, params.str("text"))
                JsonNull.INSTANCE
            }
            "storage.set" -> guarded {
                val key = params.str("key")
                require(key.startsWith("devsnip.")) { "Unknown storage key." }
                ui.setStorage(key, params["value"]?.takeIf { !it.isJsonNull })
                JsonNull.INSTANCE
            }
            "http.cancel" -> guarded {
                running[params.str("id")]?.cancel()
                JsonNull.INSTANCE
            }
            "http.raw" -> background { sendRaw(params) { result, error -> reply(result, error) } }
            else -> background {
                guarded {
                    when (method) {
                        "http.send" -> sendForFeature(params)
                        "tls.probe" -> tlsProbe(params)
                        // Only files the user picked in this page's own dialogs.
                        "fs.write" -> {
                            val path = File(params.str("path")).absolutePath
                            if (path !in writable) throw RpcError("That file was not chosen in a save dialog.", "EACCES")
                            File(path).apply { parentFile?.mkdirs() }.writeBytes(Base64.getDecoder().decode(params.str("base64")))
                            JsonNull.INSTANCE
                        }
                        "fs.read" -> {
                            val file = File(params.str("path")).absoluteFile
                            if (file.path !in readable) throw RpcError("That file was not chosen in an open dialog.", "EACCES")
                            if (file.length() > MAX_READ_BYTES) throw RpcError("${file.name} is larger than 20 MB.", "EFBIG")
                            JsonPrimitive(Base64.getEncoder().encodeToString(file.readBytes()))
                        }
                        else -> throw RpcError("Unknown request $method", null)
                    }
                }
            }
        }
    }

    // ------------------------------------------------------------------ helpers

    private fun sendEnvironments(command: String) {
        val environments = service.loadAllEnvironments()
        send(command) {
            add("environments", RestClientWire.environments(environments))
            addProperty("activeIndex", environments.indexOfFirst { it.name == service.activeEnvironmentName })
        }
    }

    private fun send(command: String, fill: JsonObject.() -> Unit = {}) {
        if (disposed) return
        post(JsonObject().apply {
            addProperty("command", command)
            fill()
        })
    }

    companion object {
        private val LOG = Logger.getInstance(RestClientHost::class.java)
        private const val MAX_READ_BYTES = 20L * 1024 * 1024
    }
}
