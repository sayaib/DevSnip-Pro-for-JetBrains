package pro.devsnip.api.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.ByteArrayOutputStream
import java.net.ConnectException
import java.net.UnknownHostException
import java.net.http.HttpConnectTimeoutException
import java.util.zip.GZIPOutputStream
import javax.net.ssl.SSLHandshakeException

class ResponseDecoderTest {
    private fun decode(text: String, type: String) = ResponseDecoder.decode(text.toByteArray(), type).first

    @Test
    fun `parses JSON by content type or by shape, and pretty prints it`() {
        val body = decode("""{"a":1,"b":[true,null],"c":"<x>"}""", "application/json; charset=utf-8")
        assertTrue(body is DecodedBody.JsonBody)
        assertEquals("{\n  \"a\": 1,\n  \"b\": [\n    true,\n    null\n  ],\n  \"c\": \"<x>\"\n}", body.display)
        assertTrue(decode("[1,2]", "text/plain") is DecodedBody.JsonBody)
        assertTrue(decode("42", "") is DecodedBody.JsonBody)
    }

    @Test
    fun `keeps number formatting when pretty printing`() {
        assertEquals("{\n  \"n\": 1,\n  \"f\": 1.50,\n  \"big\": 12345678901234567890\n}", decode("""{"n":1,"f":1.50,"big":12345678901234567890}""", "application/json").display)
    }

    @Test
    fun `invalid JSON under a JSON content type is shown as text`() {
        val body = decode("{not json", "application/json")
        assertEquals(DecodedBody.Text("{not json"), body)
        assertEquals(DecodedBody.Text("hello"), decode("hello", "application/json"))
    }

    @Test
    fun `binary content is described instead of shown as broken text`() {
        val png = byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(), 0, 1, 2)
        val body = ResponseDecoder.decode(png, "image/png").first
        assertEquals(DecodedBody.Binary("image/png", 7), body)
        assertTrue(body.display.startsWith("[Binary response: image/png, 7 B."))
        // Undeclared bytes that are not valid UTF-8 are binary too.
        assertTrue(ResponseDecoder.decode(byteArrayOf(0xC3.toByte(), 0x28), "").first is DecodedBody.Binary)
    }

    @Test
    fun `honours the declared charset`() {
        val latin1 = "café".toByteArray(Charsets.ISO_8859_1)
        assertEquals(DecodedBody.Text("café"), ResponseDecoder.decode(latin1, "text/plain; charset=ISO-8859-1").first)
    }

    @Test
    fun `truncates bodies over the display limit`() {
        val big = "x".repeat(ResponseDecoder.MAX_DISPLAY_BYTES + 10).toByteArray()
        val (body, truncated) = ResponseDecoder.decode(big, "text/plain")
        assertTrue(truncated)
        assertTrue(body.display.endsWith("... [response truncated]"))
        assertEquals(ResponseDecoder.MAX_DISPLAY_BYTES + "\n... [response truncated]".length, body.display.length)
    }

    @Test
    fun `decompresses gzip and leaves unknown encodings alone`() {
        val out = ByteArrayOutputStream()
        GZIPOutputStream(out).use { it.write("""{"ok":true}""".toByteArray()) }
        assertEquals("""{"ok":true}""", ResponseDecoder.decompress(out.toByteArray(), "gzip").toString(Charsets.UTF_8))
        assertEquals("abc", ResponseDecoder.decompress("abc".toByteArray(), "br").toString(Charsets.UTF_8))
        // A body that claims gzip but is not is returned unchanged rather than failing.
        assertEquals("plain", ResponseDecoder.decompress("plain".toByteArray(), "gzip").toString(Charsets.UTF_8))
    }

    @Test
    fun `formats sizes`() {
        assertEquals("0 B", formatBytes(0))
        assertEquals("512 B", formatBytes(512))
        assertEquals("1 KB", formatBytes(1024))
        assertEquals("1.50 KB", formatBytes(1536))
        assertEquals("2 MB", formatBytes(2L * 1024 * 1024))
    }
}

class RequestErrorsTest {
    @Test
    fun `transport errors are explained with a fix and where to make it`() {
        assertEquals(RequestErrorDescription("Could not find the server", "The host name did not resolve. Check it for typos, check your network or VPN, and check the environment's base URL.", ErrorAction.URL), RequestErrors.describe("x", "ENOTFOUND"))
        assertEquals("Connection refused", RequestErrors.describe(null, "ECONNREFUSED").title)
        assertEquals("The request timed out after 1.5 s", RequestErrors.describe("request timed out", "ETIMEDOUT", 1500).title)
        assertEquals("The request timed out after 30 s", RequestErrors.describe("", "ETIMEDOUT", 30_000).title)
        assertEquals("The request timed out after 500 ms", RequestErrors.describe("timeout", null, 500).title)
        assertEquals(ErrorAction.SETTINGS, RequestErrors.describe("PKIX path building failed", "CERT_INVALID").action)
        assertEquals("TLS handshake failed", RequestErrors.describe("Unsupported or unrecognized SSL message", "EPROTO").title)
        assertEquals("Too many redirects", RequestErrors.describe("", RequestErrors.TOO_MANY_REDIRECTS).title)
        assertEquals("The connection was closed", RequestErrors.describe("Connection reset", "ECONNRESET").title)
        assertEquals("Network unreachable", RequestErrors.describe(null, "EHOSTUNREACH").title)
        assertEquals("The request failed", RequestErrors.describe("something odd", null).title)
    }

    @Test
    fun `request-building errors point at the field to fix`() {
        assertEquals(ErrorAction.URL, RequestErrors.describe("Invalid URL format", null).action)
        assertEquals("The body is not valid JSON", RequestErrors.describe("Invalid JSON in request body", null).title)
        assertEquals("GraphQL variables are not valid JSON", RequestErrors.describe("Invalid GraphQL variables JSON", null).title)
        val undefined = RequestErrors.describe("Invalid URL format: {{baseUrl}} is used but no environment is selected", null)
        assertEquals("Undefined variable", undefined.title)
        assertEquals("{{baseUrl}} is used but no environment is selected. Define it in an environment, or select the environment that has it.", undefined.hint)
    }

    @Test
    fun `maps JDK exceptions to the codes the VS Code client used`() {
        assertEquals("ENOTFOUND", RequestErrors.codeFor(java.io.IOException("wrapped", UnknownHostException("nope.invalid"))))
        assertEquals("ECONNREFUSED", RequestErrors.codeFor(ConnectException()))
        assertEquals("ETIMEDOUT", RequestErrors.codeFor(HttpConnectTimeoutException("HTTP connect timed out")))
        assertEquals("CERT_INVALID", RequestErrors.codeFor(SSLHandshakeException("PKIX path building failed")))
        assertEquals("EPROTO", RequestErrors.codeFor(SSLHandshakeException("Remote host terminated the handshake")))
        val hostMismatch = SSLHandshakeException("No subject alternative names present").apply { initCause(java.security.cert.CertificateException("No subject alternative names present")) }
        assertEquals("CERT_INVALID", RequestErrors.codeFor(hostMismatch))
        assertEquals(null, RequestErrors.codeFor(IllegalStateException("x")))
        assertEquals("deepest", RequestErrors.messageFor(RuntimeException("outer", RuntimeException("deepest"))))
        assertFalse(RequestErrors.messageFor(RuntimeException()).isEmpty())
    }
}
