package pro.devsnip.api.core

import java.io.IOException
import java.io.InputStream
import java.net.ProxySelector
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.net.http.HttpTimeoutException
import java.time.Duration
import java.util.concurrent.CancellationException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/** A request exactly as a service built it (what the extension's services hand to axios). */
data class RawRequest(
    val method: String,
    val url: String,
    val headers: List<Pair<String, String>> = emptyList(),
    val body: String? = null,
    /** 0 means no limit beyond [RawHttp.MAX_TIMEOUT_MS]. */
    val timeoutMs: Long = 0,
    val maxRedirects: Int = 5,
    val verifySsl: Boolean = true,
    /** 0 means [RawHttp.MAX_BODY_BYTES]. */
    val maxBytes: Long = 0,
) {
    override fun toString() = "RawRequest(method=$method, url=${Redaction.redactUrl(url)}, headers=${Redaction.sanitizeHeaders(headers)}, body=${body?.let { "${it.length} chars" }})"
}

class RawResponse(
    val status: Int,
    val statusText: String,
    /** As received; a name can repeat (Set-Cookie). Names are lower case, as Node reports them. */
    val headers: List<Pair<String, String>>,
    /** The decompressed body as UTF-8 text; null for a streamed response. */
    val body: String?,
)

/** A failure with the Node-style error code axios would have reported (ECONNREFUSED, ETIMEDOUT, ...). */
class RawHttpException(message: String, val code: String?, cause: Throwable? = null) : IOException(message, cause)

/**
 * Sends requests for the REST API Client's feature services (AI providers,
 * vector databases, the endpoint security scan) as axios does for them in
 * the VS Code extension: the request as built, no cookie jar, no retries,
 * redirects followed up to [RawRequest.maxRedirects], every status returned.
 *
 * Calls block; inside the IDE they run on a pooled thread.
 */
class RawHttp(private val proxySelector: () -> ProxySelector? = { ProxySelector.getDefault() }) {
    private val clients = ConcurrentHashMap<Pair<Boolean, ProxySelector?>, HttpClient>()

    /** Sends [request]; with [onChunk] the body is streamed to it as it arrives (and [RawResponse.body] is null). */
    fun send(
        request: RawRequest,
        cancellation: CancellationToken = CancellationToken(),
        onHeaders: ((RawResponse) -> Unit)? = null,
        onChunk: ((String) -> Unit)? = null,
    ): RawResponse {
        val uri = try {
            URI(request.url).also { require(it.scheme?.lowercase() in setOf("http", "https") && !it.host.isNullOrEmpty()) }
        } catch (e: Exception) {
            throw RawHttpException("Invalid URL", "ERR_INVALID_URL", e)
        }
        val timeoutMs = request.timeoutMs.takeIf { it > 0 }?.coerceAtMost(MAX_TIMEOUT_MS) ?: MAX_TIMEOUT_MS
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs)
        val maxBytes = request.maxBytes.takeIf { it > 0 }?.coerceAtMost(MAX_BODY_BYTES) ?: MAX_BODY_BYTES
        val client = clientFor(request.verifySsl)

        var current = uri
        var method = request.method.uppercase()
        var body = request.body
        var headers = request.headers
        var redirects = 0
        try {
            while (true) {
                cancellation.throwIfCancelled()
                val remaining = TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime())
                if (remaining <= 0) throw HttpTimeoutException("timeout of ${timeoutMs}ms exceeded")
                val builder = HttpRequest.newBuilder(current).timeout(Duration.ofMillis(remaining))
                if (current.scheme.equals("http", ignoreCase = true)) builder.version(HttpClient.Version.HTTP_1_1)
                if (headers.none { it.first.equals("User-Agent", true) }) builder.header("User-Agent", USER_AGENT)
                if (headers.none { it.first.equals("Accept", true) }) builder.header("Accept", "application/json, text/plain, */*")
                for ((name, value) in headers) if (name.lowercase() !in RESTRICTED_HEADERS) builder.header(name, value)
                builder.method(method, if (body != null) HttpRequest.BodyPublishers.ofString(body) else HttpRequest.BodyPublishers.noBody())

                val future = client.sendAsync(builder.build(), HttpResponse.BodyHandlers.ofInputStream())
                cancellation.onCancel { future.cancel(true) }
                val response = await(future, deadline)
                val location = response.headers().firstValue("location").orElse(null)
                if (response.statusCode() in REDIRECT_CODES && !location.isNullOrBlank() && request.maxRedirects > 0) {
                    response.body().close()
                    if (redirects >= request.maxRedirects) throw RawHttpException("Maximum number of redirects exceeded", "ERR_FR_TOO_MANY_REDIRECTS")
                    redirects++
                    val next = current.resolve(location.replace(" ", "%20"))
                    val toGet = response.statusCode() == 303 || (response.statusCode() in setOf(301, 302) && method == "POST")
                    if (toGet) {
                        method = "GET"
                        body = null
                        headers = headers.filterNot { it.first.lowercase() in setOf("content-type", "content-length") }
                    }
                    if (!next.host.equals(current.host, ignoreCase = true)) {
                        // Never forward credentials to another host.
                        headers = headers.filterNot { it.first.lowercase() in setOf("authorization", "cookie", "proxy-authorization") }
                    }
                    current = next
                    continue
                }
                return read(response, maxBytes, cancellation, onHeaders, onChunk)
            }
        } catch (e: RawHttpException) {
            throw e
        } catch (e: RequestCancelledException) {
            throw e
        } catch (e: Exception) {
            if (cancellation.isCancelled) throw RequestCancelledException()
            val code = RequestErrors.codeFor(e)
            val message = if (code == "ETIMEDOUT") "timeout of ${timeoutMs}ms exceeded" else RequestErrors.messageFor(e)
            throw RawHttpException(message, code, e)
        }
    }

    private fun read(
        response: HttpResponse<InputStream>,
        maxBytes: Long,
        cancellation: CancellationToken,
        onHeaders: ((RawResponse) -> Unit)?,
        onChunk: ((String) -> Unit)?,
    ): RawResponse {
        val headers = response.headers().map().filterKeys { !it.startsWith(":") }.flatMap { (name, values) -> values.map { name.lowercase() to it } }
        val status = response.statusCode()
        val encoding = response.headers().firstValue("content-encoding").orElse(null)
        val head = RawResponse(status, HttpEngine.reasonPhrase(status), headers, null)
        val stream = decoding(response.body(), encoding)
        cancellation.onCancel { runCatching { stream.close() } }
        stream.use { input ->
            if (onChunk != null) {
                onHeaders?.invoke(head)
                val decoder = Charsets.UTF_8.newDecoder()
                val bytes = ByteArray(8192)
                val chars = java.nio.CharBuffer.allocate(16384)
                var leftover = java.nio.ByteBuffer.allocate(0)
                while (true) {
                    val n = input.read(bytes)
                    if (n < 0) break
                    val buffer = java.nio.ByteBuffer.allocate(leftover.remaining() + n).put(leftover).put(bytes, 0, n)
                    buffer.flip()
                    chars.clear()
                    decoder.decode(buffer, chars, false)
                    leftover = buffer.slice()
                    chars.flip()
                    if (chars.hasRemaining()) onChunk(chars.toString())
                }
                return head
            }
            val out = java.io.ByteArrayOutputStream()
            val bytes = ByteArray(8192)
            while (true) {
                val n = input.read(bytes)
                if (n < 0) break
                out.write(bytes, 0, n)
                if (out.size() > maxBytes) throw RawHttpException("maxContentLength size of $maxBytes exceeded", "ERR_BAD_RESPONSE")
            }
            return RawResponse(status, head.statusText, headers, out.toString(Charsets.UTF_8))
        }
    }

    private fun decoding(input: InputStream, encoding: String?): InputStream = when (encoding?.trim()?.lowercase()) {
        "gzip", "x-gzip" -> java.util.zip.GZIPInputStream(input)
        "deflate" -> java.util.zip.InflaterInputStream(input)
        else -> input
    }

    private fun <T> await(future: java.util.concurrent.CompletableFuture<T>, deadline: Long): T {
        try {
            val wait = TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime()).coerceAtLeast(1) + 250
            return future.get(wait, TimeUnit.MILLISECONDS)
        } catch (_: CancellationException) {
            throw RequestCancelledException()
        } catch (e: TimeoutException) {
            future.cancel(true)
            throw HttpTimeoutException("request timed out").also { it.initCause(e) }
        } catch (e: InterruptedException) {
            future.cancel(true)
            Thread.currentThread().interrupt()
            throw RequestCancelledException()
        } catch (e: ExecutionException) {
            throw (e.cause as? Exception) ?: e
        }
    }

    private fun clientFor(verifySsl: Boolean): HttpClient {
        val selector = proxySelector()
        return clients.computeIfAbsent(verifySsl to selector) { (verify, proxy) ->
            HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).apply {
                if (proxy != null) proxy(proxy)
                if (!verify) sslContext(HttpEngine.insecureSslContext())
            }.build()
        }
    }

    companion object {
        const val MAX_TIMEOUT_MS = 600_000L
        const val MAX_BODY_BYTES = 50L * 1024 * 1024
        const val USER_AGENT = "DevSnip-Pro"
        private val REDIRECT_CODES = setOf(301, 302, 303, 307, 308)
        private val RESTRICTED_HEADERS = setOf("connection", "content-length", "expect", "host", "upgrade")
    }
}
