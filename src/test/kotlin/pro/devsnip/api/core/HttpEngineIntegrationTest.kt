package pro.devsnip.api.core

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.ServerSocket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.GZIPOutputStream

/**
 * Exercises the engine end to end against a local mock server. No request
 * leaves the machine, so this is safe to run anywhere.
 */
class HttpEngineIntegrationTest {
    private lateinit var server: MockServer
    // No proxy: the tests talk to 127.0.0.1 directly.
    private val engine = HttpEngine(defaultProxySelector = { null })

    @BeforeEach
    fun start() {
        server = MockServer.http()
    }

    @AfterEach
    fun stop() = server.close()

    private fun send(request: ApiRequest, environment: Environment? = null, timeout: Long = 5_000, token: CancellationToken = CancellationToken()) =
        engine.execute(request, environment, timeout, token)

    @Test
    fun `sends a GET and reports status, headers, JSON body, timing and size`() {
        server.handle("/users") { ex, _ -> ex.respond(200, """{"users":[{"id":1}]}""", headers = mapOf("X-Request-Id" to "abc")) }
        val response = send(ApiRequest(url = server.url("/users"), params = listOf(KeyValue("page", "2"))))

        assertEquals(200, response.status)
        assertEquals("OK", response.statusText)
        assertTrue(response.body is DecodedBody.JsonBody)
        assertEquals(1, (response.body as DecodedBody.JsonBody).element.asJsonObject["users"].asJsonArray[0].asJsonObject["id"].asInt)
        assertTrue(response.headers.any { it.first.equals("X-Request-Id", true) && it.second == "abc" })
        assertEquals(20L, response.sizeBytes)
        assertTrue(response.responseTimeMs >= 0)
        assertEquals(1, response.attempts)

        val received = server.requests.single()
        assertEquals("page=2", received.query)
        assertEquals("DevSnip-Pro API Tester", received.header("User-Agent"))
    }

    @Test
    fun `sends JSON bodies, auth and resolved variables to the server`() {
        server.handle("/items") { ex, _ -> ex.respond(201, """{"created":true}""") }
        val env = Environment("local", mapOf("base" to server.url(""), "token" to "env-token-value"))
        val response = send(
            ApiRequest(method = "POST", url = "{{base}}/items", data = """{"name":"{{token}}"}""", auth = AuthConfig.Bearer("{{token}}"), headers = listOf(KeyValue("X-Client", "devsnip"))),
            env,
        )
        assertEquals(201, response.status)
        val received = server.requests.single()
        assertEquals("POST", received.method)
        assertEquals("""{"name":"env-token-value"}""", received.body)
        assertEquals("application/json", received.header("Content-Type"))
        assertEquals("Bearer env-token-value", received.header("Authorization"))
        assertEquals("devsnip", received.header("X-Client"))
    }

    @Test
    fun `an error status is a response, not a failure`() {
        server.handle("/missing") { ex, _ -> ex.respond(404, """{"error":"not found"}""") }
        val response = send(ApiRequest(url = server.url("/missing")))
        assertEquals(404, response.status)
        assertEquals("Not Found", response.statusText)
    }

    @Test
    fun `retries retryable statuses with backoff, then succeeds`() {
        val calls = AtomicInteger()
        server.handle("/flaky") { ex, _ -> if (calls.incrementAndGet() < 3) ex.respond(503, "busy", "text/plain") else ex.respond(200, "{}") }
        val response = send(ApiRequest(url = server.url("/flaky"), retries = 3, retryDelayMs = 10))
        assertEquals(200, response.status)
        assertEquals(3, response.attempts)
        assertEquals(3, calls.get())
    }

    @Test
    fun `stops retrying after the limit and returns the last response`() {
        server.handle("/down") { ex, _ -> ex.respond(429, "slow down", "text/plain") }
        val response = send(ApiRequest(url = server.url("/down"), retries = 2, retryDelayMs = 1))
        assertEquals(429, response.status)
        assertEquals(3, response.attempts)
        // Only the listed codes are retried.
        val custom = send(ApiRequest(url = server.url("/down"), retries = 2, retryDelayMs = 1, retryStatusCodes = listOf(500)))
        assertEquals(1, custom.attempts)
    }

    @Test
    fun `follows redirects, turning a 303 into a GET`() {
        server.handle("/start") { ex, _ -> ex.respond(303, contentType = null, headers = mapOf("Location" to "/end")) }
        server.handle("/end") { ex, r -> ex.respond(200, """{"method":"${r.method}"}""") }
        val response = send(ApiRequest(method = "POST", url = server.url("/start"), data = "{}"))
        assertEquals(200, response.status)
        assertEquals(1, response.redirects)
        assertEquals(server.url("/end"), response.finalUrl)
        assertEquals("GET", server.requests.last().method)
        assertEquals("", server.requests.last().body)
    }

    @Test
    fun `keeps method and body on a 307`() {
        server.handle("/old") { ex, _ -> ex.respond(307, contentType = null, headers = mapOf("Location" to server.url("/new"))) }
        server.handle("/new") { ex, _ -> ex.respond(200, "{}") }
        send(ApiRequest(method = "PUT", url = server.url("/old"), data = """{"v":1}"""))
        assertEquals("PUT", server.requests.last().method)
        assertEquals("""{"v":1}""", server.requests.last().body)
    }

    @Test
    fun `returns the redirect itself when following is off`() {
        server.handle("/r") { ex, _ -> ex.respond(302, contentType = null, headers = mapOf("Location" to "/elsewhere")) }
        val response = send(ApiRequest(url = server.url("/r"), followRedirects = false))
        assertEquals(302, response.status)
        assertEquals(1, server.requests.size)
    }

    @Test
    fun `reports too many redirects without retrying`() {
        server.handle("/loop") { ex, _ -> ex.respond(302, contentType = null, headers = mapOf("Location" to "/loop")) }
        val error = assertThrows<RequestFailedException> { send(ApiRequest(url = server.url("/loop"), maxRedirects = 3, retries = 2)) }
        assertEquals("Too many redirects", error.description.title)
        assertEquals(1, error.attempts)
        assertEquals(4, server.requests.size)
    }

    @Test
    fun `does not forward credentials when a redirect changes host`() {
        // 127.0.0.1 and localhost are different hosts to the client.
        server.handle("/hop") { ex, _ -> ex.respond(302, contentType = null, headers = mapOf("Location" to server.url("/landing", host = "localhost"))) }
        server.handle("/landing") { ex, _ -> ex.respond(200, "{}") }
        send(ApiRequest(url = server.url("/hop"), auth = AuthConfig.Bearer("secret-token"), headers = listOf(KeyValue("X-Keep", "1"))))
        val (first, second) = server.requests
        assertEquals("Bearer secret-token", first.header("Authorization"))
        assertNull(second.header("Authorization"))
        assertEquals("1", second.header("X-Keep"))
    }

    @Test
    fun `stores cookies per host and sends them back`() {
        server.handle("/login") { ex, _ -> ex.respond(200, "{}", headers = mapOf("Set-Cookie" to "sid=abc123; Path=/; HttpOnly")) }
        server.handle("/me") { ex, _ -> ex.respond(200, "{}") }
        send(ApiRequest(url = server.url("/login")))
        send(ApiRequest(url = server.url("/me")))
        assertEquals("sid=abc123", server.requests.last().header("Cookie"))
        assertEquals(mapOf("127.0.0.1" to listOf("sid")), engine.cookieJar.summary())

        engine.cookieJar.clear()
        send(ApiRequest(url = server.url("/me")))
        assertNull(server.requests.last().header("Cookie"))
    }

    @Test
    fun `decodes gzip responses and measures the decoded size`() {
        val gz = ByteArrayOutputStream().also { out -> GZIPOutputStream(out).use { it.write("""{"zipped":true}""".toByteArray()) } }.toByteArray()
        server.handle("/gz") { ex, _ -> ex.respondBytes(200, gz, mapOf("Content-Type" to "application/json", "Content-Encoding" to "gzip")) }
        val response = send(ApiRequest(url = server.url("/gz")))
        assertEquals(true, (response.body as DecodedBody.JsonBody).element.asJsonObject["zipped"].asBoolean)
        assertEquals(15L, response.sizeBytes)
    }

    @Test
    fun `refuses bodies over the size limit instead of loading them, without retrying`() {
        val small = HttpEngine(defaultProxySelector = { null }, maxBodyBytes = 1024)
        server.handle("/declared") { ex, _ -> ex.respond(200, "x".repeat(4096), contentType = "text/plain") }
        // Chunked: no Content-Length, so the limit is enforced while reading.
        server.handle("/chunked") { ex, _ ->
            ex.responseHeaders.add("Content-Type", "text/plain")
            ex.sendResponseHeaders(200, 0)
            ex.responseBody.use { out -> repeat(8) { out.write("y".repeat(512).toByteArray()); out.flush() } }
        }
        val bomb = ByteArrayOutputStream().also { out -> GZIPOutputStream(out).use { it.write(ByteArray(64 * 1024)) } }.toByteArray()
        server.handle("/bomb") { ex, _ -> ex.respondBytes(200, bomb, mapOf("Content-Type" to "text/plain", "Content-Encoding" to "gzip")) }
        for (path in listOf("/declared", "/chunked", "/bomb")) {
            val error = assertThrows<RequestFailedException>(path) { small.execute(ApiRequest(url = server.url(path), retries = 2, retryDelayMs = 0), null, 5_000) }
            assertEquals("The response is too large", error.description.title, path)
            assertEquals(RequestErrors.RESPONSE_TOO_LARGE, error.code, path)
            assertEquals(1, error.attempts, path)
        }
        assertEquals(1, server.requests.count { it.path == "/declared" })
        // Within the limit, the same body is returned as usual.
        assertEquals(4096L, HttpEngine(defaultProxySelector = { null }, maxBodyBytes = 4096).execute(ApiRequest(url = server.url("/declared")), null, 5_000).sizeBytes)
    }

    @Test
    fun `skips headers the JDK manages and says so`() {
        server.handle("/h") { ex, _ -> ex.respond(200, "{}") }
        val response = send(ApiRequest(url = server.url("/h"), headers = listOf(KeyValue("Host", "spoofed"), KeyValue("Connection", "close"))))
        assertEquals(200, response.status)
        assertEquals(2, response.warnings.size)
        assertTrue(server.requests.single().header("Host")!!.startsWith("127.0.0.1"))
    }

    @Test
    fun `times out with a clear error`() {
        val release = CountDownLatch(1)
        server.handle("/slow") { ex, _ -> release.await(5, TimeUnit.SECONDS); ex.respond(200, "{}") }
        try {
            val error = assertThrows<RequestFailedException> { send(ApiRequest(url = server.url("/slow"), timeoutMs = 300)) }
            assertEquals("ETIMEDOUT", error.code)
            assertEquals("The request timed out after 300 ms", error.description.title)
            assertEquals(ErrorAction.SETTINGS, error.description.action)
        } finally {
            release.countDown()
        }
    }

    @Test
    fun `explains a refused connection`() {
        val freePort = ServerSocket(0).use { it.localPort }
        val error = assertThrows<RequestFailedException> { send(ApiRequest(url = "http://127.0.0.1:$freePort/")) }
        assertEquals("ECONNREFUSED", error.code)
        assertEquals("Connection refused", error.description.title)
    }

    @Test
    fun `explains an unknown host`() {
        // .invalid is reserved and never resolves (RFC 2606).
        val error = assertThrows<RequestFailedException> { send(ApiRequest(url = "http://devsnip-test.invalid/")) }
        assertEquals("Could not find the server", error.description.title)
    }

    @Test
    fun `build errors surface before anything is sent`() {
        assertThrows<RequestBuildException> { send(ApiRequest(url = "{{base}}/x")) }
        assertEquals(0, server.requests.size)
    }

    @Test
    fun `cancels a request in flight`() {
        val release = CountDownLatch(1)
        server.handle("/hang") { ex, _ -> release.await(5, TimeUnit.SECONDS); ex.respond(200, "{}") }
        val token = CancellationToken()
        val executor = Executors.newSingleThreadScheduledExecutor()
        try {
            executor.schedule({ token.cancel() }, 200, TimeUnit.MILLISECONDS)
            val started = System.nanoTime()
            assertThrows<RequestCancelledException> { send(ApiRequest(url = server.url("/hang")), timeout = 10_000, token = token) }
            assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started) < 3_000, "cancel should return promptly")
        } finally {
            release.countDown()
            executor.shutdownNow()
        }
    }

    @Test
    fun `cancelling during retry backoff stops the retries`() {
        server.handle("/busy") { ex, _ -> ex.respond(503, "busy", "text/plain") }
        val token = CancellationToken()
        val executor = Executors.newSingleThreadScheduledExecutor()
        try {
            executor.schedule({ token.cancel() }, 200, TimeUnit.MILLISECONDS)
            assertThrows<RequestCancelledException> { send(ApiRequest(url = server.url("/busy"), retries = 5, retryDelayMs = 5_000), token = token) }
            assertEquals(1, server.requests.size)
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun `GraphQL requests are posted as JSON`() {
        server.handle("/graphql") { ex, _ -> ex.respond(200, """{"data":{"ok":true}}""") }
        send(ApiRequest(method = "POST", url = server.url("/graphql"), requestType = RequestType.GRAPHQL, graphqlQuery = "{ ok }"))
        assertEquals("""{"query":"{ ok }"}""", server.requests.single().body)
    }

    @Test
    fun `HEAD and DELETE work`() {
        server.handle("/r") { ex, r -> ex.respond(if (r.method == "DELETE") 204 else 200, "") }
        assertEquals(200, send(ApiRequest(method = "HEAD", url = server.url("/r"))).status)
        val deleted = send(ApiRequest(method = "DELETE", url = server.url("/r")))
        assertEquals(204, deleted.status)
        assertEquals(DecodedBody.Empty, deleted.body)
    }
}

class HttpsVerificationTest {
    @Test
    fun `self-signed certificates fail by default and pass with verification off`(@TempDir dir: File) {
        MockServer.selfSignedHttps(dir).use { server ->
            server.handle("/secure") { ex, _ -> ex.respond(200, """{"tls":true}""") }
            val engine = HttpEngine(defaultProxySelector = { null })

            val error = assertThrows<RequestFailedException> { engine.execute(ApiRequest(url = server.url("/secure")), null, 5_000) }
            val chain = generateSequence<Throwable>(error) { it.cause }.joinToString(" <- ") { "${it.javaClass.name}: ${it.message}" }
            assertEquals("SSL certificate problem", error.description.title, chain)
            assertEquals(ErrorAction.SETTINGS, error.description.action)

            // Host name "localhost" does not match the certificate's CN either; verification off skips both checks.
            val response = engine.execute(ApiRequest(url = server.url("/secure", host = "localhost"), verifySsl = false), null, 5_000)
            assertEquals(200, response.status)
        }
    }
}
