package pro.devsnip.api.ide

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import pro.devsnip.api.core.MockServer
import pro.devsnip.api.core.respond
import pro.devsnip.api.ide.web.RestClientHost
import pro.devsnip.api.ide.web.RestClientUi
import pro.devsnip.api.ide.web.RestClientWire
import java.io.File
import java.util.Base64
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * The REST API Client page's message protocol (api-test.ts in the VS Code
 * extension) answered by the Kotlin engine: real HTTP against local servers,
 * the password safe replaced by memory, dialogs by fixed answers.
 */
class RestClientHostTest {
    @TempDir
    lateinit var dir: File

    private lateinit var server: MockServer
    private val secrets = InMemorySecretStore()
    private val service = ApiClientService().also { it.secrets = secrets }
    private val posted = CopyOnWriteArrayList<JsonObject>()
    private val release = CountDownLatch(1)

    private val ui = object : RestClientUi {
        var saveTarget: File? = null
        var openTarget: File? = null
        val notices = mutableListOf<String>()
        val storage = mutableMapOf<String, JsonElement?>()
        override fun chooseSaveFile(title: String, defaultName: String, extensions: List<String>) = saveTarget
        override fun chooseOpenFile(title: String, extensions: List<String>) = openTarget
        override fun notify(kind: String, text: String) {
            notices += "$kind: $text"
        }
        override fun setStorage(key: String, value: JsonElement?) {
            storage[key] = value
        }
    }

    private val host = RestClientHost(service, ui, { posted += it }, { task -> Thread(task).start() })

    @BeforeEach
    fun start() {
        server = MockServer.http()
            .handle("/echo") { ex, r ->
                ex.responseHeaders.add("Set-Cookie", "sid=abc123; Path=/")
                ex.respond(200, """{"method":"${r.method}","auth":"${r.header("Authorization").orEmpty()}","body":${com.google.gson.Gson().toJson(r.body)}}""")
            }
            .handle("/text") { ex, _ -> ex.respond(200, "plain words", "text/plain") }
            .handle("/fail") { ex, _ -> ex.respond(503, """{"error":"down"}""") }
            .handle("/slow") { ex, _ ->
                release.await(10, TimeUnit.SECONDS)
                ex.respond(200, "{}")
            }
            .handle("/token") { ex, _ -> ex.respond(200, """{"access_token":"oauth-access-token-0123456789","token_type":"Bearer","expires_in":3600}""") }
            .handle("/stream") { ex, _ ->
                ex.responseHeaders.add("Content-Type", "text/event-stream")
                ex.sendResponseHeaders(200, 0)
                ex.responseBody.use { out ->
                    for (word in listOf("one", "two", "three")) {
                        out.write("data: $word\n\n".toByteArray())
                        out.flush()
                        Thread.sleep(20)
                    }
                }
            }
    }

    @AfterEach
    fun stop() {
        release.countDown()
        host.dispose()
        server.close()
    }

    // ----------------------------------------------------------------- helpers

    private fun send(json: String) = host.handle(JsonParser.parseString(json).asJsonObject)

    private fun request(url: String, extra: String = "") =
        """{"command":"testAPI","method":"POST","url":"$url","data":"{\"a\":1}","params":{},"headers":{"X-Test":"1"},"authType":"","bodyType":"json","requestType":"rest","retries":0,"followRedirects":true,"maxRedirects":5,"rejectUnauthorized":true$extra}"""

    private fun await(command: String, timeoutMs: Long = 10_000): JsonObject {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            posted.firstOrNull { it.get("command")?.asString == command }?.let { posted.remove(it); return it }
            Thread.sleep(10)
        }
        error("No \"$command\" message; got ${posted.map { it.get("command") ?: it.get("type") }}")
    }

    private var rpcId = 0

    private fun rpc(method: String, params: String): JsonObject {
        val id = ++rpcId
        send("""{"type":"__rpc","id":$id,"method":"$method","params":$params}""")
        val deadline = System.currentTimeMillis() + 15_000
        while (System.currentTimeMillis() < deadline) {
            posted.firstOrNull { it.get("type")?.asString == "__rpcResult" && it.get("id").asInt == id }?.let { posted.remove(it); return it }
            Thread.sleep(10)
        }
        error("No reply to $method")
    }

    private fun environments(json: String) {
        send("""{"command":"saveEnvironment","environment":$json}""")
        await("environmentSaved")
    }

    // ------------------------------------------------------------------- tests

    @Test
    fun `testAPI sends through the engine and answers like makeRequest`() {
        send(request(server.url("/echo")))
        await("requestStarted")
        val response = await("apiResponse")
        assertEquals(200, response["status"].asInt)
        assertEquals("OK", response["statusText"].asString)
        val data = response.getAsJsonObject("data")
        assertEquals("POST", data["method"].asString)
        assertEquals("{\"a\":1}", data["body"].asString)
        // Node-style headers: lower-case names, set-cookie as an array.
        val headers = response.getAsJsonObject("headers")
        assertEquals("application/json", headers["content-type"].asString)
        assertTrue(headers["set-cookie"].isJsonArray)
        assertTrue(response["size"].asString.endsWith(" B"))
        assertEquals(1, response["attempts"].asInt)
        val history = response.getAsJsonArray("history")
        assertEquals(1, history.size())
        val item = history[0].asJsonObject
        assertEquals("POST", item["method"].asString)
        assertEquals(200, item["status"].asInt)
        assertEquals("1", item.getAsJsonObject("request").getAsJsonObject("headers")["X-Test"].asString)
        assertEquals("json", item.getAsJsonObject("request")["bodyType"].asString)
        assertEquals(1, server.requests.size)
    }

    @Test
    fun `text and error statuses are responses, not failures`() {
        send(request(server.url("/text")).replace("\"POST\"", "\"GET\""))
        assertEquals("plain words", await("apiResponse")["data"].asString)
        send(request(server.url("/fail")))
        val failed = await("apiResponse")
        assertEquals(503, failed["status"].asInt)
        assertEquals("down", failed.getAsJsonObject("data")["error"].asString)
    }

    @Test
    fun `build and network errors are explained as in VS Code`() {
        send(request("not a url"))
        val invalid = await("apiError")
        assertEquals("Invalid URL", invalid["title"].asString)
        assertEquals("url", invalid["action"].asString)

        send(request("{{baseUrl}}/users"))
        val undefined = await("apiError")
        assertEquals("Undefined variable", undefined["title"].asString)
        assertEquals("env", undefined["action"].asString)

        val port = java.net.ServerSocket(0).use { it.localPort }
        send(request("http://127.0.0.1:$port/"))
        val refused = await("apiError", 20_000)
        assertEquals("ECONNREFUSED", refused["code"].asString)
        assertEquals("Connection refused", refused["title"].asString)
        assertEquals(0, refused["status"].asInt)
        assertTrue(refused.getAsJsonArray("history").size() >= 1)
    }

    @Test
    fun `cancelRequest stops the request in flight`() {
        send(request(server.url("/slow")))
        await("requestStarted")
        Thread.sleep(200)
        send("""{"command":"cancelRequest"}""")
        await("requestCancelled")
        release.countDown()
        Thread.sleep(300)
        assertTrue(posted.none { it.get("command")?.asString in setOf("apiResponse", "apiError") })
    }

    @Test
    fun `environments resolve variables, keep secrets out of state and follow the page's indexes`() {
        environments("""{"name":"dev","variables":{"baseUrl":"${server.url("")}","apiToken":"tok-secret-1"}}""")
        environments("""{"name":"prod","variables":{"baseUrl":"https://example.invalid"}}""")
        send("""{"command":"setActiveEnvironment","index":0}""")
        val activated = await("environmentActivated")
        assertEquals(0, activated["activeIndex"].asInt)
        assertEquals("tok-secret-1", activated.getAsJsonArray("environments")[0].asJsonObject.getAsJsonObject("variables")["apiToken"].asString)
        assertFalse(service.state.environmentsJson.contains("tok-secret-1"))
        assertEquals("tok-secret-1", secrets.values["env/dev/apiToken"])

        send(request("{{baseUrl}}/echo", ""","authType":"Bearer","authToken":"{{apiToken}}""""))
        val response = await("apiResponse")
        assertEquals("Bearer tok-secret-1", response.getAsJsonObject("data")["auth"].asString)
        val snapshot = response.getAsJsonArray("history")[0].asJsonObject.getAsJsonObject("request")
        assertEquals("{{baseUrl}}/echo", snapshot["url"].asString)
        assertEquals("Bearer", snapshot["authType"].asString)

        // Renaming keeps the position and the active flag.
        send("""{"command":"saveEnvironment","previousName":"dev","environment":{"name":"local","variables":{"baseUrl":"x"}}}""")
        val renamed = await("environmentSaved")
        assertEquals("local", renamed.getAsJsonArray("environments")[0].asJsonObject["name"].asString)
        assertEquals(0, renamed["activeIndex"].asInt)
        assertNull(secrets.values["env/dev/apiToken"])

        send("""{"command":"saveEnvironment","environment":{"name":"  ","variables":{}}}""")
        assertEquals("An environment needs a name.", await("environmentError")["error"].asString)

        send("""{"command":"deleteEnvironment","name":"local"}""")
        val deleted = await("environmentDeleted")
        assertEquals(1, deleted.getAsJsonArray("environments").size())
        assertEquals(0, deleted["activeIndex"].asInt)

        send("""{"command":"setActiveEnvironment","index":-1}""")
        assertEquals(-1, await("environmentActivated")["activeIndex"].asInt)
    }

    @Test
    fun `cookies, history and cURL`() {
        send(request(server.url("/echo")))
        await("apiResponse")
        send("""{"command":"getCookies"}""")
        val cookies = await("showCookies").getAsJsonObject("cookies")
        assertEquals("sid=abc123", cookies.getAsJsonArray("127.0.0.1")[0].asString)
        send("""{"command":"clearCookies"}""")
        await("cookiesCleared")
        send("""{"command":"getCookies"}""")
        assertEquals(0, await("showCookies").getAsJsonObject("cookies").size())

        send(request(server.url("/echo")).replace("testAPI", "generateCurl"))
        val curl = await("curlGenerated")["curl"].asString
        assertTrue(curl.startsWith("curl -X POST"), curl)
        assertTrue(curl.contains("X-Test: 1"))

        send("""{"command":"parseCurl","curl":"curl -k -L -u me:pw -H 'Accept: text/plain' -d 'x=1' https://api.example.com/a"}""")
        val parsed = await("curlParsed").getAsJsonObject("request")
        assertEquals("POST", parsed["method"].asString)
        assertEquals("https://api.example.com/a", parsed["url"].asString)
        assertEquals("text/plain", parsed.getAsJsonObject("headers")["Accept"].asString)
        assertEquals("x=1", parsed["data"].asString)
        assertEquals("Basic", parsed["authType"].asString)
        assertEquals("me", parsed["username"].asString)
        assertFalse(parsed["rejectUnauthorized"].asBoolean)
        send("""{"command":"parseCurl","curl":"wget x"}""")
        assertEquals("Not a valid cURL command", await("curlParsed")["error"].asString)

        send("""{"command":"getHistory"}""")
        assertEquals(1, await("historyLoaded").getAsJsonArray("history").size())
        ui.saveTarget = File(dir, "out/history.csv")
        send("""{"command":"exportHistory","format":"csv"}""")
        assertTrue(await("historyExported")["success"].asBoolean)
        assertTrue(ui.saveTarget!!.readText().startsWith("id,method,url,status"))
        send("""{"command":"clearHistory"}""")
        assertEquals(0, await("historyCleared").getAsJsonArray("history").size())
        assertTrue(service.history().isEmpty())
    }

    @Test
    fun `saveResponse writes the body where the user chose`() {
        send("""{"command":"saveResponse","body":""}""")
        assertFalse(await("responseSaved")["success"].asBoolean)
        ui.saveTarget = File(dir, "response.json")
        send("""{"command":"saveResponse","body":"{\"ok\":true}"}""")
        val saved = await("responseSaved")
        assertTrue(saved["success"].asBoolean)
        assertEquals("{\"ok\":true}", ui.saveTarget!!.readText())
        assertTrue(ui.notices.any { it.contains("Response saved") })
        // Cancelled dialog: nothing is posted.
        ui.saveTarget = null
        send("""{"command":"saveResponse","body":"x"}""")
        Thread.sleep(100)
        assertTrue(posted.none { it.get("command")?.asString == "responseSaved" })
    }

    @Test
    fun `OAuth tokens stay in the password safe until applied`() {
        send("""{"command":"feature:oauthToken","featureId":"oauth2-helper","grant":"client_credentials","tokenUrl":"${server.url("/token")}","clientId":"client","clientSecret":"s3cret"}""")
        val fetched = await("featureResult")
        assertEquals("oauth2-helper", fetched["featureId"].asString)
        val result = fetched.getAsJsonObject("result")
        assertTrue(result["ok"].asBoolean)
        assertTrue(result["tokenAvailable"].asBoolean)
        assertEquals(3600, result["expiresIn"].asInt)
        assertFalse(fetched.toString().contains("oauth-access-token-0123456789"), "the raw token must not reach the page here")
        assertEquals("oauth-access-token-0123456789", secrets.values["oauth/accessToken"])

        send("""{"command":"feature:applyOauthToken","featureId":"oauth2-helper"}""")
        val applied = await("featureResult").getAsJsonObject("result")
        assertEquals("Bearer", applied["authType"].asString)
        assertEquals("oauth-access-token-0123456789", applied["authToken"].asString)

        send("""{"command":"feature:oauthToken","featureId":"oauth2-helper","grant":"implicit"}""")
        assertEquals("\"implicit\" is not a supported grant type.", await("featureError")["error"].asString)
    }

    @Test
    fun `the feature system's sender shares cookies, and background runs skip history`() {
        val background = rpc("http.send", """{"background":true,"request":{"method":"GET","url":"${server.url("/echo")}","headers":{}}}""")
        assertEquals(200, background.getAsJsonObject("result")["status"].asInt)
        assertTrue(service.history().isEmpty())
        val chained = rpc("http.send", """{"background":false,"request":{"method":"GET","url":"${server.url("/echo")}","headers":{}}}""")
        assertEquals(1, chained.getAsJsonObject("result").getAsJsonArray("history").size())
        assertEquals("sid=abc123", server.requests.last().header("Cookie"))
        val bad = rpc("http.send", """{"request":{"method":"GET","url":"nope"}}""")
        assertEquals("Invalid URL format", bad["error"].asString)
    }

    @Test
    fun `raw HTTP for the services returns every status, streams, and reports Node error codes`() {
        val ok = rpc("http.raw", """{"id":"a1","method":"POST","url":"${server.url("/echo")}","headers":{"Content-Type":"application/json"},"body":"{\"q\":1}","timeoutMs":5000}""").getAsJsonObject("result")
        assertEquals(200, ok["status"].asInt)
        assertEquals("{\"q\":1}", JsonParser.parseString(ok["body"].asString).asJsonObject["body"].asString)
        assertTrue(ok.getAsJsonObject("headers")["set-cookie"].isJsonArray)
        // No cookie jar for the services (axios has none either).
        rpc("http.raw", """{"id":"a2","method":"GET","url":"${server.url("/echo")}"}""")
        assertNull(server.requests.last().header("Cookie"))

        assertEquals(503, rpc("http.raw", """{"id":"a3","method":"GET","url":"${server.url("/fail")}"}""").getAsJsonObject("result")["status"].asInt)

        val head = rpc("http.raw", """{"id":"s1","method":"GET","url":"${server.url("/stream")}","stream":true}""").getAsJsonObject("result")
        assertEquals(200, head["status"].asInt)
        assertFalse(head.has("body"))
        val deadline = System.currentTimeMillis() + 10_000
        while (posted.none { it.get("type")?.asString == "__stream" && it.get("event").asString == "end" } && System.currentTimeMillis() < deadline) Thread.sleep(10)
        val events = posted.filter { it.get("type")?.asString == "__stream" && it.get("id").asString == "s1" }
        assertEquals("end", events.last()["event"].asString)
        assertEquals("data: one\n\ndata: two\n\ndata: three\n\n", events.filter { it["event"].asString == "data" }.joinToString("") { it["value"].asString })

        val port = java.net.ServerSocket(0).use { it.localPort }
        val refused = rpc("http.raw", """{"id":"a4","method":"GET","url":"http://127.0.0.1:$port/"}""")
        assertEquals("ECONNREFUSED", refused.getAsJsonObject("errorData")["code"].asString)
    }

    @Test
    fun `TLS for the security scan reports an untrusted certificate instead of failing`() {
        val https = MockServer.selfSignedHttps(dir).handle("/") { ex, _ -> ex.respond(200, "{}") }
        try {
            val strict = rpc("http.raw", """{"id":"t1","method":"GET","url":"${https.url("/")}"}""")
            assertEquals("CERT_INVALID", strict.getAsJsonObject("errorData")["code"].asString)
            val lax = rpc("http.raw", """{"id":"t2","method":"GET","url":"${https.url("/")}","rejectUnauthorized":false}""")
            assertEquals(200, lax.getAsJsonObject("result")["status"].asInt)

            val probe = rpc("tls.probe", """{"host":"127.0.0.1","port":${https.port},"servername":"127.0.0.1","timeoutMs":5000}""").getAsJsonObject("result")
            assertFalse(probe["authorized"].asBoolean)
            assertNotNull(probe["authorizationError"])
            assertTrue(probe["protocol"].asString.startsWith("TLSv1."))
            val certificate = probe.getAsJsonObject("certificate")
            assertEquals("devsnip-test", certificate.getAsJsonObject("subject")["CN"].asString)
            assertEquals(2048, certificate["bits"].asInt)
            assertTrue(java.time.Instant.now().toEpochMilli() < parseNodeDate(certificate["valid_to"].asString))
        } finally {
            https.close()
        }
    }

    private fun parseNodeDate(text: String): Long =
        java.text.SimpleDateFormat("MMM d HH:mm:ss yyyy 'GMT'", java.util.Locale.ENGLISH).apply { timeZone = java.util.TimeZone.getTimeZone("GMT") }.parse(text).time

    @Test
    fun `files are only read and written where the user chose, storage keys are the page's`() {
        val target = File(dir, "collection.json")
        val content = Base64.getEncoder().encodeToString("{\"requests\":[]}".toByteArray())
        val refused = rpc("fs.write", """{"path":"${target.path}","base64":"$content"}""")
        assertEquals("EACCES", refused.getAsJsonObject("errorData")["code"].asString)
        assertFalse(target.exists())

        ui.saveTarget = target
        assertEquals(target.absolutePath, rpc("dialog.save", """{"defaultName":"devsnip-collection.json","filters":{"JSON Files":["json"]}}""")["result"].asString)
        assertTrue(rpc("fs.write", """{"path":"${target.path}","base64":"$content"}""").has("result"))
        assertEquals("{\"requests\":[]}", target.readText())

        assertEquals("EACCES", rpc("fs.read", """{"path":"${target.path}"}""").getAsJsonObject("errorData")["code"].asString)
        ui.openTarget = target
        rpc("dialog.open", """{"filters":{}}""")
        assertEquals(content, rpc("fs.read", """{"path":"${target.path}"}""")["result"].asString)

        rpc("storage.set", """{"key":"devsnip.apiClient.collections","value":[{"id":"r1"}]}""")
        assertTrue((ui.storage["devsnip.apiClient.collections"] as JsonArray).size() == 1)
        rpc("storage.set", """{"key":"devsnip.apiClient.collections","value":null}""")
        assertNull(ui.storage["devsnip.apiClient.collections"])
        assertTrue(rpc("storage.set", """{"key":"other","value":1}""").has("error"))
    }

    @Test
    fun `points are not part of the IDE`() {
        send("""{"command":"getPoints"}""")
        assertEquals(0, await("showPoints")["points"].asInt)
    }

    @Test
    fun `wire formats match the extension's`() {
        assertEquals("0 B", RestClientWire.formatBytes(0))
        assertEquals("512 B", RestClientWire.formatBytes(512))
        assertEquals("1.5 KB", RestClientWire.formatBytes(1536))
        assertEquals("2 MB", RestClientWire.formatBytes(2 * 1024 * 1024))
        assertEquals("1.23 KB", RestClientWire.formatBytes(1259))
        val binary = RestClientWire.data(pro.devsnip.api.core.DecodedBody.Binary("image/png", 2048))
        assertEquals("[Binary response: image/png, 2.0 KB. It is not shown as text; use cURL export or save it from your app to inspect the bytes.]", binary.asString)

        val request = RestClientWire.request(JsonParser.parseString(
            """{"method":"PUT","url":"u","headers":{"A":"1"},"authType":"ApiKey","apiKeyName":"k","apiKeyValue":"v","apiKeyLocation":"query","bodyType":"form-urlencoded","timeout":1500,"retries":9,"retryStatusCodes":[500,"x"],"rejectUnauthorized":false,"proxy":{"host":"proxy.local","port":3128},"requestType":"graphql","graphqlQuery":"{ a }"}""",
        ).asJsonObject)
        assertEquals("PUT", request.method)
        assertEquals(pro.devsnip.api.core.AuthConfig.ApiKey("k", "v", pro.devsnip.api.core.ApiKeyLocation.QUERY), request.auth)
        assertEquals(pro.devsnip.api.core.BodyType.FORM_URLENCODED, request.bodyType)
        assertEquals(1500L, request.timeoutMs)
        assertEquals(5, request.retries)
        assertEquals(listOf(500), request.retryStatusCodes)
        assertFalse(request.verifySsl)
        assertEquals(pro.devsnip.api.core.ProxyConfig("proxy.local", 3128), request.proxy)
        assertEquals(pro.devsnip.api.core.RequestType.GRAPHQL, request.requestType)
    }
}
