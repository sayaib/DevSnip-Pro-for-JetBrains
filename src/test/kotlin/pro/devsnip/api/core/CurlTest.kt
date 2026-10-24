package pro.devsnip.api.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.condition.DisabledOnOs
import org.junit.jupiter.api.condition.OS

class CurlTest {
    @Test
    fun `builds a cURL command with headers, query parameters and a body`() {
        val prepared = RequestBuilder.build(
            ApiRequest(method = "POST", url = "https://api.example.com/users", params = listOf(KeyValue("page", "2")), headers = listOf(KeyValue("Accept", "application/json")), data = """{"name":"Ada"}"""),
            null,
        )
        val curl = Curl.generate(prepared, cookieHeader = "sid=1")
        assertEquals(
            "curl -X POST -H 'Accept: application/json' -H 'Content-Type: application/json' -H 'Cookie: sid=1' -d '{\"name\":\"Ada\"}' 'https://api.example.com/users?page=2'",
            curl,
        )
    }

    @Test
    fun `adds -k and the proxy when those settings are used`() {
        val prepared = RequestBuilder.build(ApiRequest(url = "https://localhost:8443/"), null)
        assertEquals("curl -X GET -k -x 'http://proxy.local:3128' 'https://localhost:8443/'", Curl.generate(prepared, verifySsl = false, proxy = ProxyConfig("proxy.local", 3128)))
    }

    @Test
    @DisabledOnOs(OS.WINDOWS)
    fun `quoting survives a real shell, so an exported cURL cannot inject a command`() {
        val nasty = listOf("it's", "\$(touch /tmp/pwned)", "`id`", "a;b|c&d", "\"double\"", "back\\slash", "new\nline", "'; rm -rf / #")
        for (value in nasty) {
            val process = ProcessBuilder("/bin/sh", "-c", "printf '%s' ${Curl.shellQuote(value)}").start()
            val output = process.inputStream.readBytes().toString(Charsets.UTF_8)
            assertEquals(0, process.waitFor())
            assertEquals(value, output, "shell saw a different word for $value")
        }
    }

    @Test
    fun `parses method, headers, body and url`() {
        val request = Curl.parse(
            """curl -X PUT 'https://api.example.com/items/1' -H 'Content-Type: application/json' -H "Authorization: Bearer abc" --data-raw '{"a": 1}'""",
        )
        assertEquals("PUT", request.method)
        assertEquals("https://api.example.com/items/1", request.url)
        assertEquals(listOf(KeyValue("Content-Type", "application/json"), KeyValue("Authorization", "Bearer abc")), request.headers)
        assertEquals("""{"a": 1}""", request.data)
        assertEquals(BodyType.JSON, request.bodyType)
    }

    @Test
    fun `infers POST from a data flag and parses basic auth, cookies, insecure and proxy`() {
        val request = Curl.parse("curl https://e.com/login -d 'a=1&b=2' -u ada:se:cret -b 'sid=9' -k -x proxy:3128 -A agent/1 \\\n  -L")
        assertEquals("POST", request.method)
        assertEquals("a=1&b=2", request.data)
        assertEquals(BodyType.TEXT, request.bodyType)
        assertEquals(AuthConfig.Basic("ada", "se:cret"), request.auth)
        assertTrue(KeyValue("Cookie", "sid=9") in request.headers)
        assertTrue(KeyValue("User-Agent", "agent/1") in request.headers)
        assertFalse(request.verifySsl)
        assertEquals(ProxyConfig("proxy", 3128), request.proxy)
    }

    @Test
    fun `parses browser-style commands with --url and escaped characters`() {
        val request = Curl.parse("""curl --url "https://e.com/a?x=\"1\"" --header 'X-Q: it'\''s'""")
        assertEquals("https://e.com/a?x=\"1\"", request.url)
        assertEquals(listOf(KeyValue("X-Q", "it's")), request.headers)
    }

    @Test
    fun `round-trips a generated command`() {
        val original = ApiRequest(
            method = "PATCH",
            url = "https://api.example.com/users/7?fields=a,b",
            headers = listOf(KeyValue("X-Note", "it's \$HOME and \"quotes\"")),
            data = """{"name":"O'Brien"}""",
        )
        val prepared = RequestBuilder.build(original, null)
        val parsed = Curl.parse(Curl.generate(prepared))
        assertEquals(prepared.method, parsed.method)
        assertEquals(prepared.url, parsed.url)
        assertEquals(prepared.body, parsed.data)
        assertEquals(prepared.headers.map { KeyValue(it.first, it.second) }, parsed.headers)
        assertEquals(prepared, RequestBuilder.build(parsed, null))
    }

    @Test
    fun `rejects input that is not a cURL command`() {
        assertEquals("Not a valid cURL command", assertThrows<RequestBuildException> { Curl.parse("wget https://e.com") }.message)
        assertEquals("Not a valid cURL command", assertThrows<RequestBuildException> { Curl.parse("   ") }.message)
        assertEquals("No URL found in cURL command", assertThrows<RequestBuildException> { Curl.parse("curl -X GET") }.message)
        assertEquals("Unterminated quote in cURL command", assertThrows<RequestBuildException> { Curl.parse("curl 'https://e.com") }.message)
    }
}
