package pro.devsnip.api.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.util.Base64

class RequestBuilderTest {
    private val env = Environment("dev", mapOf("baseUrl" to "https://api.example.com", "token" to "t0k3n", "id" to "42"))

    private fun build(request: ApiRequest, environment: Environment? = null) = RequestBuilder.build(request, environment)

    @Test
    fun `rejects unsupported protocols and malformed URLs`() {
        for (url in listOf("ftp://example.com", "not a url", "", "file:///etc/passwd", "javascript:alert(1)", "http://")) {
            val error = assertThrows<RequestBuildException>(url) { build(ApiRequest(url = url)) }
            assertEquals("Invalid URL format", error.message)
        }
    }

    @Test
    fun `accepts a URL with spaces by encoding them`() {
        assertEquals("https://example.com/a%20b", build(ApiRequest(url = "https://example.com/a b")).url)
    }

    @Test
    fun `names an undefined base-URL variable instead of a generic format error`() {
        val noEnv = assertThrows<RequestBuildException> { build(ApiRequest(url = "{{baseUrl}}/users")) }
        assertEquals("Invalid URL format: {{baseUrl}} is used but no environment is selected", noEnv.message)
        val withEnv = assertThrows<RequestBuildException> { build(ApiRequest(url = "{{host}}/{{path}}"), env) }
        assertEquals("Invalid URL format: {{host}}, {{path}} are not defined in the active environment \"dev\"", withEnv.message)
        assertEquals(ErrorAction.ENV, RequestErrors.describe(withEnv.message, null).action)
    }

    @Test
    fun `resolves environment variables into url, params, headers and body`() {
        val prepared = build(
            ApiRequest(
                method = "post",
                url = "{{baseUrl}}/users/{{id}}",
                params = listOf(KeyValue("q", "{{id}}")),
                headers = listOf(KeyValue("X-Trace", "{{id}}")),
                data = """{"id": "{{id}}"}""",
            ),
            env,
        )
        assertEquals("POST", prepared.method)
        assertEquals("https://api.example.com/users/42?q=42", prepared.url)
        assertEquals("42", prepared.header("x-trace"))
        assertEquals("""{"id": "42"}""", prepared.body)
    }

    @Test
    fun `an unknown variable is left in place rather than blanked out`() {
        val prepared = build(ApiRequest(url = "https://example.com/{{missing}}", headers = listOf(KeyValue("X", "{{missing}}"))), env)
        assertEquals("https://example.com/%7B%7Bmissing%7D%7D", prepared.url)
        assertEquals("{{missing}}", prepared.header("X"))
    }

    @Test
    fun `query params replace an existing key, keep others, and are form-encoded`() {
        val prepared = build(
            ApiRequest(
                url = "https://example.com/search?page=1&sort=asc#top",
                params = listOf(KeyValue("page", "2"), KeyValue("q", "a b&c"), KeyValue("off", "x", enabled = false), KeyValue("", "ignored")),
            ),
        )
        assertEquals("https://example.com/search?sort=asc&page=2&q=a+b%26c#top", prepared.url)
    }

    @Test
    fun `disabled headers are not sent and header names are case-insensitive`() {
        val prepared = build(
            ApiRequest(
                method = "POST",
                url = "https://example.com",
                headers = listOf(KeyValue("content-type", "application/vnd.api+json"), KeyValue("X-Off", "1", enabled = false)),
                data = "{}",
            ),
        )
        assertNull(prepared.header("X-Off"))
        // The user's lower-case content-type is kept, not duplicated by the default.
        assertEquals(listOf("content-type" to "application/vnd.api+json"), prepared.headers)
    }

    @Test
    fun `applies each supported authentication style`() {
        val url = "https://example.com/x"
        assertEquals("Bearer {{nope}}", build(ApiRequest(url = url, auth = AuthConfig.Bearer("{{nope}}"))).header("Authorization"))
        assertEquals("Bearer t0k3n", build(ApiRequest(url = url, auth = AuthConfig.Bearer("{{token}}")), env).header("Authorization"))

        val basic = build(ApiRequest(url = url, auth = AuthConfig.Basic("ada", "p:ss")))
        assertEquals("Basic " + Base64.getEncoder().encodeToString("ada:p:ss".toByteArray()), basic.header("Authorization"))
        val stripeStyle = build(ApiRequest(url = url, auth = AuthConfig.Basic("sk_test_key", "")))
        assertEquals("Basic " + Base64.getEncoder().encodeToString("sk_test_key:".toByteArray()), stripeStyle.header("Authorization"))

        val headerKey = build(ApiRequest(url = url, auth = AuthConfig.ApiKey("X-API-Key", "secret", ApiKeyLocation.HEADER)))
        assertEquals("secret", headerKey.header("X-API-Key"))
        val queryKey = build(ApiRequest(url = url, auth = AuthConfig.ApiKey("api_key", "s e", ApiKeyLocation.QUERY)))
        assertEquals("https://example.com/x?api_key=s+e", queryKey.url)
        assertNull(queryKey.header("api_key"))

        // Empty credentials add nothing.
        assertNull(build(ApiRequest(url = url, auth = AuthConfig.Bearer(""))).header("Authorization"))
        assertNull(build(ApiRequest(url = url, auth = AuthConfig.Basic("", "x"))).header("Authorization"))
    }

    @Test
    fun `auth overrides a typed Authorization header`() {
        val prepared = build(ApiRequest(url = "https://e.com", headers = listOf(KeyValue("authorization", "old")), auth = AuthConfig.Bearer("new")))
        assertEquals(listOf("Authorization" to "Bearer new"), prepared.headers)
    }

    @Test
    fun `JSON bodies get a content type, invalid JSON is sent as text unless the header says JSON`() {
        val json = build(ApiRequest(method = "POST", url = "https://e.com", data = """{"a":1}"""))
        assertEquals("application/json", json.header("Content-Type"))
        assertEquals("""{"a":1}""", json.body)

        val text = build(ApiRequest(method = "POST", url = "https://e.com", data = "hello"))
        assertEquals("text/plain", text.header("Content-Type"))
        assertEquals("hello", text.body)

        val error = assertThrows<RequestBuildException> {
            build(ApiRequest(method = "POST", url = "https://e.com", data = "{bad", headers = listOf(KeyValue("Content-Type", "application/json"))))
        }
        assertEquals("Invalid JSON in request body", error.message)
        assertEquals(ErrorAction.BODY, RequestErrors.describe(error.message, null).action)
    }

    @Test
    fun `bodies are only sent with methods that carry one`() {
        assertNull(build(ApiRequest(method = "GET", url = "https://e.com", data = "{}")).body)
        assertNull(build(ApiRequest(method = "HEAD", url = "https://e.com", data = "{}")).body)
        for (method in listOf("POST", "PUT", "PATCH", "DELETE", "OPTIONS")) {
            assertEquals("{}", build(ApiRequest(method = method, url = "https://e.com", data = "{}")).body, method)
        }
    }

    @Test
    fun `encodes a form-urlencoded body from a JSON object, or sends pre-encoded text as is`() {
        val fromJson = build(ApiRequest(method = "POST", url = "https://e.com", bodyType = BodyType.FORM_URLENCODED, data = """{"name":"A B","n":1,"ok":true}"""))
        assertEquals("name=A+B&n=1&ok=true", fromJson.body)
        assertEquals("application/x-www-form-urlencoded", fromJson.header("Content-Type"))

        val raw = build(ApiRequest(method = "POST", url = "https://e.com", bodyType = BodyType.FORM_URLENCODED, data = "a=1&b=2"))
        assertEquals("a=1&b=2", raw.body)
    }

    @Test
    fun `wraps GraphQL queries into a JSON payload`() {
        val prepared = build(
            ApiRequest(
                method = "POST",
                url = "https://e.com/graphql",
                requestType = RequestType.GRAPHQL,
                graphqlQuery = "query User(\$id: ID!) { user(id: \$id) { name } }",
                graphqlVariables = """{"id": "{{id}}"}""",
                graphqlOperationName = "User",
                headers = listOf(KeyValue("Content-Type", "text/plain")),
            ),
            env,
        )
        assertEquals("application/json", prepared.header("Content-Type"))
        val body = Json.parseOrNull(prepared.body!!)!!.asJsonObject
        assertEquals("User", body["operationName"].asString)
        assertEquals("42", body["variables"].asJsonObject["id"].asString)
        assertTrue(body["query"].asString.startsWith("query User"))
    }

    @Test
    fun `reports invalid GraphQL variables clearly`() {
        for (variables in listOf("{bad", "[1,2]", "hello")) {
            val error = assertThrows<RequestBuildException>(variables) {
                build(ApiRequest(method = "POST", url = "https://e.com", requestType = RequestType.GRAPHQL, graphqlQuery = "{ a }", graphqlVariables = variables))
            }
            assertEquals("Invalid GraphQL variables JSON", error.message)
        }
    }

    @Test
    fun `rejects a method with illegal characters`() {
        assertThrows<RequestBuildException> { build(ApiRequest(method = "GET /x", url = "https://e.com")) }
        assertEquals("PROPFIND", build(ApiRequest(method = "propfind", url = "https://e.com")).method)
    }

    @Test
    fun `toString never prints credentials`() {
        val prepared = build(ApiRequest(url = "https://e.com/?token=abc", auth = AuthConfig.Bearer("supersecretvalue123")))
        val text = prepared.toString() + AuthConfig.Bearer("supersecretvalue123") + AuthConfig.Basic("u", "pw-secret") + AuthConfig.ApiKey("k", "keyvalue-secret-xyz")
        for (secret in listOf("supersecretvalue123", "pw-secret", "keyvalue-secret-xyz", "token=abc")) {
            assertTrue(secret !in text, "leaked $secret in $text")
        }
    }
}
