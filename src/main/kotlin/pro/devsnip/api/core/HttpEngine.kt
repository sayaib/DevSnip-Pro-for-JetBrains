package pro.devsnip.api.core

import java.io.IOException
import java.net.InetSocketAddress
import java.net.ProxySelector
import java.net.Socket
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.net.http.HttpTimeoutException
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.time.Duration
import java.util.concurrent.CancellationException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLEngine
import javax.net.ssl.X509ExtendedTrustManager

/** Cooperative cancellation shared by the UI's Cancel button and the IDE progress indicator. */
class CancellationToken {
    @Volatile
    var isCancelled = false
        private set
    private val listeners = CopyOnWriteArrayList<() -> Unit>()
    private val latch = CountDownLatch(1)

    fun cancel() {
        if (isCancelled) return
        isCancelled = true
        latch.countDown()
        listeners.forEach { runCatching(it) }
    }

    fun onCancel(listener: () -> Unit) {
        listeners += listener
        if (isCancelled) runCatching(listener)
    }

    /** Sleeps up to [ms]; returns false if cancelled meanwhile. */
    fun sleep(ms: Long): Boolean = !latch.await(ms, TimeUnit.MILLISECONDS)

    fun throwIfCancelled() {
        if (isCancelled) throw RequestCancelledException()
    }
}

/**
 * Cookies received per host, sent back on later requests to the same host (as
 * the VS Code client does). Values are `name=value` pairs; a newer cookie
 * replaces an older one of the same name.
 */
class CookieJar {
    private val cookies = ConcurrentHashMap<String, LinkedHashMap<String, String>>()

    fun headerFor(host: String): String? = cookies[host.lowercase()]?.let { map ->
        synchronized(map) { map.values.joinToString("; ").ifEmpty { null } }
    }

    fun store(host: String, setCookieHeaders: List<String>) {
        if (setCookieHeaders.isEmpty()) return
        val map = cookies.computeIfAbsent(host.lowercase()) { LinkedHashMap() }
        synchronized(map) {
            for (header in setCookieHeaders) {
                val pair = header.substringBefore(';').trim()
                val name = pair.substringBefore('=').trim()
                if (name.isEmpty() || !pair.contains('=')) continue
                map[name] = pair
            }
        }
    }

    /** Host to `name=value` pairs, for the REST client page's cookie viewer (as the VS Code client shows them). Never logged. */
    fun entries(): Map<String, List<String>> = cookies.mapValues { (_, map) -> synchronized(map) { map.values.toList() } }.filterValues { it.isNotEmpty() }

    /** Host to cookie names (values are not exposed, they are often session secrets). */
    fun summary(): Map<String, List<String>> = cookies.mapValues { (_, map) -> synchronized(map) { map.keys.toList() } }.filterValues { it.isNotEmpty() }

    fun clear() = cookies.clear()
}

/**
 * Sends [ApiRequest]s. Port of `ApiTester.makeRequest()` from the VS Code
 * extension (axios) onto `java.net.http`: same defaults, retry policy,
 * redirect limits, cookie handling, timing and size limits.
 *
 * Calls block the calling thread; inside the IDE they run on a background
 * task (see `ApiClientPanel`), never on the EDT.
 */
class HttpEngine(
    val cookieJar: CookieJar = CookieJar(),
    /** Used when a request has no explicit proxy. Inside the IDE this honours the IDE proxy settings. */
    private val defaultProxySelector: () -> ProxySelector? = { ProxySelector.getDefault() },
    private val userAgent: String = "DevSnip-Pro API Tester",
) {
    private val clients = ConcurrentHashMap<ClientKey, HttpClient>()

    private data class ClientKey(val verifySsl: Boolean, val proxy: ProxyConfig?, val selector: ProxySelector?)

    fun execute(
        request: ApiRequest,
        environment: Environment?,
        defaultTimeoutMs: Long = 30_000,
        cancellation: CancellationToken = CancellationToken(),
    ): ApiResponse {
        val prepared = RequestBuilder.build(request, environment)
        val timeoutMs = (request.timeoutMs?.takeIf { it > 0 } ?: defaultTimeoutMs).coerceIn(1, ApiRequest.MAX_TIMEOUT_MS)
        val maxRetries = request.retries.coerceIn(0, 5)
        val retryCodes = request.retryStatusCodes.ifEmpty { ApiRequest.DEFAULT_RETRY_STATUS_CODES }
        val baseDelay = request.retryDelayMs.coerceAtLeast(0)
        val maxRedirects = if (request.followRedirects) request.maxRedirects.coerceIn(0, 50) else 0
        val client = clientFor(request)

        val start = System.nanoTime()
        var attempt = 0
        while (true) {
            cancellation.throwIfCancelled()
            try {
                val result = sendFollowingRedirects(client, prepared, maxRedirects, timeoutMs, cancellation)
                if (attempt < maxRetries && result.response.statusCode() in retryCodes) {
                    attempt++
                    backoff(baseDelay, attempt, cancellation)
                    continue
                }
                return toApiResponse(result, elapsedMs(start), attempt + 1)
            } catch (e: RequestCancelledException) {
                throw e
            } catch (e: Exception) {
                if (cancellation.isCancelled) throw RequestCancelledException()
                if (e is IllegalArgumentException && e !is RequestBuildException) {
                    // The JDK rejected the request itself (for example a header value with a line break).
                    throw RequestFailedException(RequestErrorDescription("The request could not be built", e.message ?: "Invalid request.", ErrorAction.HEADERS), null, elapsedMs(start), attempt + 1, e)
                }
                // Too many redirects is deterministic: retrying would only repeat it.
                if (attempt < maxRetries && e !is TooManyRedirectsException) {
                    attempt++
                    backoff(baseDelay, attempt, cancellation)
                    continue
                }
                val code = if (e is TooManyRedirectsException) RequestErrors.TOO_MANY_REDIRECTS else RequestErrors.codeFor(e)
                val message = RequestErrors.messageFor(e)
                throw RequestFailedException(RequestErrors.describe(message, code, timeoutMs), code, elapsedMs(start), attempt + 1, e)
            }
        }
    }

    private fun backoff(baseDelay: Long, attempt: Int, cancellation: CancellationToken) {
        val delay = baseDelay * (1L shl (attempt - 1).coerceAtMost(10))
        if (!cancellation.sleep(delay)) throw RequestCancelledException()
    }

    private class Hop(val response: HttpResponse<ByteArray>, val url: String, val redirects: Int, val warnings: List<String>)

    private class TooManyRedirectsException(max: Int) : IOException("Maximum number of redirects exceeded ($max)")

    private fun sendFollowingRedirects(
        client: HttpClient,
        prepared: PreparedRequest,
        maxRedirects: Int,
        timeoutMs: Long,
        cancellation: CancellationToken,
    ): Hop {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs)
        var current = prepared
        var redirects = 0
        val warnings = mutableListOf<String>()
        val originalHost = URI(prepared.url).host
        while (true) {
            val uri = URI(current.url)
            val remaining = TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime())
            if (remaining <= 0) throw HttpTimeoutException("request timed out")

            val builder = HttpRequest.newBuilder(uri).timeout(Duration.ofMillis(remaining))
            if (uri.scheme.equals("http", ignoreCase = true)) builder.version(HttpClient.Version.HTTP_1_1)
            val headers = buildList {
                if (current.header("User-Agent") == null) add("User-Agent" to userAgent)
                addAll(current.headers)
                if (current.header("Cookie") == null) cookieJar.headerFor(uri.host)?.let { add("Cookie" to it) }
            }
            for ((name, value) in headers) {
                if (name.lowercase() in RESTRICTED_HEADERS) {
                    warnings += "The \"$name\" header is managed by the HTTP client and was not sent as typed."
                    continue
                }
                builder.header(name, value)
            }
            val body = current.body
            builder.method(current.method, if (body != null) HttpRequest.BodyPublishers.ofString(body) else HttpRequest.BodyPublishers.noBody())

            val response = await(client.sendAsync(builder.build(), HttpResponse.BodyHandlers.ofByteArray()), deadline, cancellation)
            cookieJar.store(uri.host, response.headers().allValues("set-cookie"))

            val location = response.headers().firstValue("location").orElse(null)
            if (response.statusCode() !in REDIRECT_CODES || location.isNullOrBlank() || maxRedirects == 0) {
                return Hop(response, current.url, redirects, warnings.distinct())
            }
            if (redirects >= maxRedirects) throw TooManyRedirectsException(maxRedirects)
            redirects++

            val next = uri.resolve(RequestBuilder.parseHttpUrl(location)?.toString() ?: location.replace(" ", "%20"))
            if (next.scheme?.lowercase() !in setOf("http", "https")) return Hop(response, current.url, redirects - 1, warnings.distinct())
            val switchToGet = (response.statusCode() == 303 && current.method != "HEAD") ||
                (response.statusCode() in setOf(301, 302) && current.method == "POST")
            var nextHeaders = current.headers
            if (switchToGet) nextHeaders = nextHeaders.filterNot { it.first.lowercase() in setOf("content-type", "content-length") }
            if (!next.host.equals(originalHost, ignoreCase = true)) {
                // Never forward credentials to another host.
                nextHeaders = nextHeaders.filterNot { it.first.lowercase() in setOf("authorization", "cookie", "proxy-authorization") }
            }
            current = PreparedRequest(
                method = if (switchToGet) "GET" else current.method,
                url = next.toASCIIString(),
                headers = nextHeaders,
                body = if (switchToGet) null else current.body,
            )
        }
    }

    private fun <T> await(future: java.util.concurrent.CompletableFuture<T>, deadline: Long, cancellation: CancellationToken): T {
        cancellation.onCancel { future.cancel(true) }
        try {
            // A little slack so the JDK's own timeout (which names the cause) usually fires first.
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
            if (cancellation.isCancelled) throw RequestCancelledException()
            throw (e.cause as? Exception) ?: e
        }
    }

    private fun toApiResponse(hop: Hop, elapsed: Long, attempts: Int): ApiResponse {
        val response = hop.response
        val headers = response.headers().map()
            .filterKeys { !it.startsWith(":") }
            .flatMap { (name, values) -> values.map { name to it } }
        val raw = ResponseDecoder.decompress(response.body() ?: ByteArray(0), response.headers().firstValue("content-encoding").orElse(null))
        val contentType = response.headers().firstValue("content-type").orElse("")
        val (body, truncated) = ResponseDecoder.decode(raw, contentType)
        return ApiResponse(
            status = response.statusCode(),
            statusText = reasonPhrase(response.statusCode()),
            headers = headers,
            body = body,
            sizeBytes = raw.size.toLong(),
            responseTimeMs = elapsed,
            attempts = attempts,
            truncated = truncated,
            finalUrl = hop.url,
            redirects = hop.redirects,
            warnings = hop.warnings,
        )
    }

    /** Builds a client for a request's TLS and proxy settings; clients are reused. */
    private fun clientFor(request: ApiRequest): HttpClient {
        val selector = if (request.proxy == null) defaultProxySelector() else null
        return clients.computeIfAbsent(ClientKey(request.verifySsl, request.proxy, selector)) { key ->
            val builder = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER)
            when {
                key.proxy != null -> builder.proxy(ProxySelector.of(InetSocketAddress(key.proxy.host, key.proxy.port)))
                key.selector != null -> builder.proxy(key.selector)
            }
            if (!key.verifySsl) builder.sslContext(insecureSslContext())
            builder.build()
        }
    }

    private fun elapsedMs(start: Long) = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start)

    companion object {
        private val REDIRECT_CODES = setOf(301, 302, 303, 307, 308)

        /** Headers `java.net.http` refuses to let callers set. */
        private val RESTRICTED_HEADERS = setOf("connection", "content-length", "expect", "host", "upgrade")

        /**
         * Trusts any certificate and skips host name checks. Only used when the
         * user turns verification off for a request (the `-k` of cURL).
         */
        internal fun insecureSslContext(): SSLContext {
            val trustAll = object : X509ExtendedTrustManager() {
                override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
                override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
                override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?, socket: Socket?) = Unit
                override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?, socket: Socket?) = Unit
                override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?, engine: SSLEngine?) = Unit
                override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?, engine: SSLEngine?) = Unit
                override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
            }
            return SSLContext.getInstance("TLS").apply { init(null, arrayOf(trustAll), SecureRandom()) }
        }

        fun reasonPhrase(status: Int): String = when (status) {
            100 -> "Continue"; 101 -> "Switching Protocols"
            200 -> "OK"; 201 -> "Created"; 202 -> "Accepted"; 203 -> "Non-Authoritative Information"; 204 -> "No Content"
            205 -> "Reset Content"; 206 -> "Partial Content"
            300 -> "Multiple Choices"; 301 -> "Moved Permanently"; 302 -> "Found"; 303 -> "See Other"; 304 -> "Not Modified"
            307 -> "Temporary Redirect"; 308 -> "Permanent Redirect"
            400 -> "Bad Request"; 401 -> "Unauthorized"; 402 -> "Payment Required"; 403 -> "Forbidden"; 404 -> "Not Found"
            405 -> "Method Not Allowed"; 406 -> "Not Acceptable"; 407 -> "Proxy Authentication Required"; 408 -> "Request Timeout"
            409 -> "Conflict"; 410 -> "Gone"; 411 -> "Length Required"; 412 -> "Precondition Failed"; 413 -> "Payload Too Large"
            414 -> "URI Too Long"; 415 -> "Unsupported Media Type"; 416 -> "Range Not Satisfiable"; 417 -> "Expectation Failed"
            418 -> "I'm a Teapot"; 422 -> "Unprocessable Entity"; 425 -> "Too Early"; 426 -> "Upgrade Required"
            428 -> "Precondition Required"; 429 -> "Too Many Requests"; 431 -> "Request Header Fields Too Large"
            451 -> "Unavailable For Legal Reasons"
            500 -> "Internal Server Error"; 501 -> "Not Implemented"; 502 -> "Bad Gateway"; 503 -> "Service Unavailable"
            504 -> "Gateway Timeout"; 505 -> "HTTP Version Not Supported"; 511 -> "Network Authentication Required"
            else -> ""
        }
    }
}
