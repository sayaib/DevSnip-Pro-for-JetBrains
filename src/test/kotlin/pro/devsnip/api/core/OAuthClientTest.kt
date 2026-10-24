package pro.devsnip.api.core

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.net.URLDecoder
import java.util.Base64

class OAuthClientTest {
    private lateinit var server: MockServer
    private val oauth = OAuthClient(HttpEngine(defaultProxySelector = { null }))
    private val accessToken = "eyJhbGciOiJIUzI1NiJ9.access-token-value-1234567890"

    @BeforeEach
    fun start() {
        server = MockServer.http().handle("/token") { ex, r ->
            val form = r.body.split('&').associate { URLDecoder.decode(it.substringBefore('='), Charsets.UTF_8) to URLDecoder.decode(it.substringAfter('='), Charsets.UTF_8) }
            when {
                form["client_id"] == "bad" -> ex.respond(401, """{"error":"invalid_client","error_description":"Client authentication failed"}""")
                form["client_id"] == "empty" -> ex.respond(200, """{"token_type":"Bearer"}""")
                else -> ex.respond(200, """{"access_token":"$accessToken","token_type":"Bearer","expires_in":3600,"refresh_token":"refresh-token-value-abcdef","scope":"read"}""")
            }
        }
    }

    @AfterEach
    fun stop() = server.close()

    private fun form(body: String) = body.split('&').associate { URLDecoder.decode(it.substringBefore('='), Charsets.UTF_8) to URLDecoder.decode(it.substringAfter('='), Charsets.UTF_8) }

    @Test
    fun `client credentials grant posts a form and returns the token with a masked preview`() {
        val result = oauth.requestToken(OAuthTokenRequest(OAuthGrant.CLIENT_CREDENTIALS, server.url("/token"), "my-client", clientSecret = "s3cret", scope = "read write"))
        assertTrue(result.ok, result.error)
        assertEquals(accessToken, result.accessToken)
        assertEquals("Bearer", result.tokenType)
        assertEquals(3600L, result.expiresIn)
        assertEquals("refresh-token-value-abcdef", result.refreshToken)
        assertFalse(result.safePreview!!.contains(accessToken))
        assertFalse(result.safePreview!!.contains("refresh-token-value-abcdef"))
        assertTrue(result.safePreview!!.contains("eyJhbG...7890"))
        assertFalse(result.toString().contains(accessToken))

        val received = server.requests.single()
        assertEquals("application/x-www-form-urlencoded", received.header("Content-Type"))
        assertEquals(mapOf("grant_type" to "client_credentials", "scope" to "read write", "client_id" to "my-client", "client_secret" to "s3cret"), form(received.body))
        assertNull(received.header("Authorization"))
    }

    @Test
    fun `basic auth mode sends the client credentials in the header`() {
        val result = oauth.requestToken(OAuthTokenRequest(OAuthGrant.CLIENT_CREDENTIALS, server.url("/token"), "my-client", clientSecret = "s3cret", useBasicAuth = true))
        assertTrue(result.ok)
        val received = server.requests.single()
        assertEquals("Basic " + Base64.getEncoder().encodeToString("my-client:s3cret".toByteArray()), received.header("Authorization"))
        assertEquals(mapOf("grant_type" to "client_credentials"), form(received.body))
    }

    @Test
    fun `password and refresh grants send their fields, resolving variables`() {
        val env = Environment("dev", mapOf("tokenUrl" to server.url("/token"), "pw" to "p@ss word"))
        assertTrue(oauth.requestToken(OAuthTokenRequest(OAuthGrant.PASSWORD, "{{tokenUrl}}", "c", username = "ada", password = "{{pw}}"), env).ok)
        assertEquals("p@ss word", form(server.requests.last().body)["password"])
        assertTrue(oauth.requestToken(OAuthTokenRequest(OAuthGrant.REFRESH_TOKEN, server.url("/token"), "", refreshToken = "r-1")).ok)
        assertEquals(mapOf("grant_type" to "refresh_token", "refresh_token" to "r-1"), form(server.requests.last().body))
    }

    @Test
    fun `explains token endpoint errors`() {
        assertEquals("HTTP 401: Client authentication failed", oauth.requestToken(OAuthTokenRequest(OAuthGrant.CLIENT_CREDENTIALS, server.url("/token"), "bad")).error)
        assertEquals("The response did not contain an access_token.", oauth.requestToken(OAuthTokenRequest(OAuthGrant.CLIENT_CREDENTIALS, server.url("/token"), "empty")).error)
    }

    @Test
    fun `validates input before sending anything`() {
        assertEquals("Enter the token endpoint URL.", oauth.requestToken(OAuthTokenRequest(OAuthGrant.CLIENT_CREDENTIALS, " ", "c")).error)
        assertEquals("Enter the client id.", oauth.requestToken(OAuthTokenRequest(OAuthGrant.CLIENT_CREDENTIALS, server.url("/token"), "")).error)
        assertEquals("The password grant needs a username and a password.", oauth.requestToken(OAuthTokenRequest(OAuthGrant.PASSWORD, server.url("/token"), "c", username = "ada")).error)
        assertEquals("Enter the refresh token.", oauth.requestToken(OAuthTokenRequest(OAuthGrant.REFRESH_TOKEN, server.url("/token"), "c")).error)
        assertTrue(oauth.requestToken(OAuthTokenRequest(OAuthGrant.CLIENT_CREDENTIALS, "not a url", "c")).error!!.startsWith("Invalid URL"))
        assertEquals(0, server.requests.size)
    }
}
