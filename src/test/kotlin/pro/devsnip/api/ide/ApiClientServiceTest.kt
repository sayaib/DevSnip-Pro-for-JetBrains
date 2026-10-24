package pro.devsnip.api.ide

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import pro.devsnip.api.core.ApiRequest
import pro.devsnip.api.core.AuthConfig
import pro.devsnip.api.core.CancellationToken
import pro.devsnip.api.core.Environment
import pro.devsnip.api.core.KeyValue
import pro.devsnip.api.core.MockServer
import pro.devsnip.api.core.OAuthGrant
import pro.devsnip.api.core.OAuthTokenRequest
import pro.devsnip.api.core.RequestFailedException
import pro.devsnip.api.core.respond

class InMemorySecretStore : SecretStore {
    val values = mutableMapOf<String, String>()
    override fun get(key: String) = values[key]
    override fun set(key: String, value: String?) {
        if (value.isNullOrEmpty()) values.remove(key) else values[key] = value
    }
}

/** The service without the IDE: password safe replaced by memory, real HTTP against a local server. */
class ApiClientServiceTest {
    private lateinit var server: MockServer
    private val secrets = InMemorySecretStore()
    private val service = ApiClientService().also { it.secrets = secrets }

    @BeforeEach
    fun start() {
        server = MockServer.http()
            .handle("/echo") { ex, r -> ex.respond(200, """{"auth":"${r.header("Authorization").orEmpty()}"}""") }
            .handle("/token") { ex, _ -> ex.respond(200, """{"access_token":"oauth-access-token-0123456789","token_type":"Bearer"}""") }
    }

    @AfterEach
    fun stop() = server.close()

    @Test
    fun `secret environment variables go to the secret store, never to persisted state`() {
        service.saveEnvironments(listOf(Environment("dev", mapOf("baseUrl" to server.url(""), "apiToken" to "tok-123-secret", "password" to "pw-secret"))))
        val persisted = service.state.environmentsJson
        assertFalse(persisted.contains("tok-123-secret"))
        assertFalse(persisted.contains("pw-secret"))
        assertTrue(persisted.contains(server.url("")))
        assertEquals("tok-123-secret", secrets.values["env/dev/apiToken"])

        val loaded = service.loadEnvironment("dev")!!
        assertEquals(mapOf("baseUrl" to server.url(""), "apiToken" to "tok-123-secret", "password" to "pw-secret"), loaded.variables)

        // Removing a variable or environment removes its secret.
        service.saveEnvironments(listOf(Environment("dev", mapOf("baseUrl" to server.url("")))))
        assertNull(secrets.values["env/dev/apiToken"])
        service.saveEnvironments(emptyList())
        assertTrue(secrets.values.isEmpty())
    }

    @Test
    fun `state survives a save and load cycle`() {
        service.saveEnvironments(listOf(Environment("dev", mapOf("baseUrl" to "https://e.com", "secretKey" to "s"))))
        service.activeEnvironmentName = "dev"
        service.defaultTimeoutMs = 12_000
        val reloaded = ApiClientService().also { it.secrets = secrets }
        reloaded.loadState(service.state)
        assertEquals(listOf("dev"), reloaded.environmentNames())
        assertEquals("dev", reloaded.activeEnvironmentName)
        assertEquals(12_000, reloaded.defaultTimeoutMs)
        assertEquals("s", reloaded.loadEnvironment("dev")!!.variables["secretKey"])
    }

    @Test
    fun `sends with the active environment and records history without secrets`() {
        service.saveEnvironments(listOf(Environment("local", mapOf("base" to server.url(""), "token" to "env-secret-token"))))
        service.activeEnvironmentName = "local"
        val response = service.send(ApiRequest(url = "{{base}}/echo?api_key=literal-key", auth = AuthConfig.Bearer("{{token}}"), headers = listOf(KeyValue("X-Secret-Token", "typed-secret"))), CancellationToken())
        assertEquals(200, response.status)
        assertTrue(response.body.rawText.contains("Bearer env-secret-token"))

        val item = service.history().single()
        assertEquals(200, item.status)
        assertEquals(server.url("/echo?api_key=[redacted]"), item.url)
        assertEquals("{{token}}", item.request!!.auth["token"])
        val stored = service.state.historyJson
        for (secret in listOf("env-secret-token", "literal-key", "typed-secret")) assertFalse(stored.contains(secret), secret)
        assertFalse(service.exportHistory(csv = true).contains("literal-key"))
    }

    @Test
    fun `failed requests are recorded with status 0`() {
        val port = java.net.ServerSocket(0).use { it.localPort }
        assertThrows<RequestFailedException> { service.send(ApiRequest(url = "http://127.0.0.1:$port/"), CancellationToken()) }
        assertEquals(0, service.history().single().status)
        service.clearHistory()
        assertTrue(service.history().isEmpty())
    }

    @Test
    fun `cURL export resolves the environment`() {
        service.saveEnvironments(listOf(Environment("local", mapOf("base" to server.url("")))))
        service.activeEnvironmentName = "local"
        assertEquals("curl -X GET '${server.url("/echo")}'", service.toCurl(ApiRequest(url = "{{base}}/echo")))
    }

    @Test
    fun `OAuth tokens are kept in the secret store`() {
        val result = service.fetchOAuthToken(OAuthTokenRequest(OAuthGrant.CLIENT_CREDENTIALS, server.url("/token"), "client"), CancellationToken())
        assertTrue(result.ok)
        assertEquals("oauth-access-token-0123456789", service.storedOAuthToken())
        assertFalse(service.state.historyJson.contains("oauth-access-token"))
    }
}
