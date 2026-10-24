package pro.devsnip.api.core

import com.google.gson.JsonObject
import com.google.gson.JsonPrimitive
import java.net.URLEncoder
import java.util.Base64

enum class OAuthGrant(val wireName: String, val label: String) {
    CLIENT_CREDENTIALS("client_credentials", "Client credentials"),
    PASSWORD("password", "Password"),
    REFRESH_TOKEN("refresh_token", "Refresh token");

    override fun toString() = label
}

data class OAuthTokenRequest(
    val grant: OAuthGrant,
    val tokenUrl: String,
    val clientId: String,
    val clientSecret: String? = null,
    val scope: String? = null,
    val username: String? = null,
    val password: String? = null,
    val refreshToken: String? = null,
    /** Send the client credentials as HTTP Basic instead of in the form body. */
    val useBasicAuth: Boolean = false,
    val timeoutMs: Long = 30_000,
    val verifySsl: Boolean = true,
) {
    override fun toString() = "OAuthTokenRequest(grant=$grant, tokenUrl=${Redaction.redactUrl(tokenUrl)}, clientId=$clientId, scope=$scope, useBasicAuth=$useBasicAuth)"
}

class OAuthTokenResult(
    val ok: Boolean,
    val error: String? = null,
    val accessToken: String? = null,
    val tokenType: String? = null,
    val expiresIn: Long? = null,
    val refreshToken: String? = null,
    val scope: String? = null,
    /** The token response with the tokens masked; safe to display. */
    val safePreview: String? = null,
) {
    override fun toString() = "OAuthTokenResult(ok=$ok, error=$error, tokenType=$tokenType, expiresIn=$expiresIn, accessToken=${Redaction.mask(accessToken)})"
}

/** Port of `requestOAuthToken()` from the VS Code extension's `dev-operations.ts`. */
class OAuthClient(private val engine: HttpEngine) {
    fun requestToken(request: OAuthTokenRequest, environment: Environment? = null, cancellation: CancellationToken = CancellationToken()): OAuthTokenResult {
        fun resolve(text: String?) = text?.let { RequestBuilder.resolveVariables(it.trim(), environment) }?.ifEmpty { null }

        val tokenUrl = resolve(request.tokenUrl) ?: return OAuthTokenResult(false, "Enter the token endpoint URL.")
        val clientId = resolve(request.clientId)
        val clientSecret = resolve(request.clientSecret)
        if (clientId == null && request.grant != OAuthGrant.REFRESH_TOKEN) return OAuthTokenResult(false, "Enter the client id.")

        val form = linkedMapOf("grant_type" to request.grant.wireName)
        resolve(request.scope)?.let { form["scope"] = it }
        when (request.grant) {
            OAuthGrant.PASSWORD -> {
                val username = resolve(request.username)
                val password = request.password?.let { RequestBuilder.resolveVariables(it, environment) }
                if (username == null || password.isNullOrEmpty()) return OAuthTokenResult(false, "The password grant needs a username and a password.")
                form["username"] = username
                form["password"] = password
            }
            OAuthGrant.REFRESH_TOKEN -> {
                form["refresh_token"] = resolve(request.refreshToken) ?: return OAuthTokenResult(false, "Enter the refresh token.")
            }
            OAuthGrant.CLIENT_CREDENTIALS -> Unit
        }

        val headers = mutableListOf(KeyValue("Content-Type", "application/x-www-form-urlencoded"), KeyValue("Accept", "application/json"))
        if (request.useBasicAuth && clientId != null) {
            val credentials = Base64.getEncoder().encodeToString("$clientId:${clientSecret.orEmpty()}".toByteArray(Charsets.UTF_8))
            headers += KeyValue("Authorization", "Basic $credentials")
        } else {
            clientId?.let { form["client_id"] = it }
            clientSecret?.let { form["client_secret"] = it }
        }
        val body = form.entries.joinToString("&") { (k, v) -> "${URLEncoder.encode(k, Charsets.UTF_8)}=${URLEncoder.encode(v, Charsets.UTF_8)}" }

        val response = try {
            engine.execute(
                ApiRequest(method = "POST", url = tokenUrl, data = body, headers = headers, bodyType = BodyType.TEXT, timeoutMs = request.timeoutMs, verifySsl = request.verifySsl),
                environment = null,
                cancellation = cancellation,
            )
        } catch (e: RequestBuildException) {
            return OAuthTokenResult(false, RequestErrors.describe(e.message, null).let { "${it.title}: ${it.hint}" })
        } catch (e: RequestFailedException) {
            return OAuthTokenResult(false, "${e.description.title}: ${e.description.hint}")
        }

        val json = (response.body as? DecodedBody.JsonBody)?.element?.takeIf { it.isJsonObject }?.asJsonObject
        if (response.status >= 400) {
            val detail = json?.let { string(it, "error_description") ?: string(it, "error") ?: Json.toCompact(it).take(300) }
                ?: response.body.rawText.take(300)
            return OAuthTokenResult(false, "HTTP ${response.status}: $detail")
        }
        val accessToken = json?.let { string(it, "access_token") } ?: return OAuthTokenResult(false, "The response did not contain an access_token.")

        val preview = json.deepCopy().apply {
            add("access_token", JsonPrimitive(Redaction.mask(accessToken)))
            string(json, "refresh_token")?.let { add("refresh_token", JsonPrimitive(Redaction.mask(it))) }
            string(json, "id_token")?.let { add("id_token", JsonPrimitive(Redaction.mask(it))) }
        }
        return OAuthTokenResult(
            ok = true,
            accessToken = accessToken,
            tokenType = string(json, "token_type") ?: "Bearer",
            expiresIn = json.get("expires_in")?.takeIf { it.isJsonPrimitive }?.asString?.toLongOrNull(),
            refreshToken = string(json, "refresh_token"),
            scope = string(json, "scope"),
            safePreview = Json.prettyPrint(preview),
        )
    }

    private fun string(json: JsonObject, name: String): String? =
        json.get(name)?.takeIf { it.isJsonPrimitive }?.asString?.takeIf { it.isNotEmpty() }
}
