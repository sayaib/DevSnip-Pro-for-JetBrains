package pro.devsnip.api.core

import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketException
import java.net.UnknownHostException
import java.net.http.HttpConnectTimeoutException
import java.net.http.HttpTimeoutException
import java.nio.channels.UnresolvedAddressException
import java.security.cert.CertificateException
import javax.net.ssl.SSLException

/** A failed request explained in terms the user can act on. */
data class RequestErrorDescription(
    val title: String,
    val hint: String,
    /** The request-builder area that most likely needs changing. */
    val action: ErrorAction? = null,
)

enum class ErrorAction { SETTINGS, AUTH, BODY, HEADERS, ENV, URL }

/** Port of `describeRequestError()` from the VS Code extension, plus a mapping from JDK exceptions to its error codes. */
object RequestErrors {
    const val TOO_MANY_REDIRECTS = "ERR_FR_TOO_MANY_REDIRECTS"
    const val RESPONSE_TOO_LARGE = "ERR_RESPONSE_TOO_LARGE"

    fun describe(message: String?, code: String?, timeoutMs: Long? = null): RequestErrorDescription {
        val text = message.orEmpty()
        val errorCode = code.orEmpty()
        fun has(pattern: Regex) = pattern.containsMatchIn(errorCode) || pattern.containsMatchIn(text)

        if (Regex("not defined in the active environment|no environment is selected", RegexOption.IGNORE_CASE).containsMatchIn(text)) {
            return RequestErrorDescription(
                "Undefined variable",
                text.replace(Regex("^Invalid URL format:\\s*"), "") + ". Define it in an environment, or select the environment that has it.",
                ErrorAction.ENV,
            )
        }
        if (Regex("^Invalid URL format", RegexOption.IGNORE_CASE).containsMatchIn(text)) {
            return RequestErrorDescription("Invalid URL", "Use a full http:// or https:// address, for example https://api.example.com/users.", ErrorAction.URL)
        }
        if (Regex("Invalid JSON in request body", RegexOption.IGNORE_CASE).containsMatchIn(text)) {
            return RequestErrorDescription("The body is not valid JSON", "Fix the JSON in the Body tab, or switch the body type to Text to send it as typed.", ErrorAction.BODY)
        }
        if (Regex("Invalid GraphQL variables", RegexOption.IGNORE_CASE).containsMatchIn(text)) {
            return RequestErrorDescription("GraphQL variables are not valid JSON", "The Variables field must be a JSON object, for example { \"id\": 1 }.")
        }
        if (Regex("Invalid HTTP method", RegexOption.IGNORE_CASE).containsMatchIn(text)) {
            return RequestErrorDescription("Invalid HTTP method", "$text. Use a method such as GET, POST, PUT, PATCH or DELETE.", ErrorAction.URL)
        }
        if (has(Regex("ERR_RESPONSE_TOO_LARGE|Response body larger than"))) {
            return RequestErrorDescription(
                "The response is too large",
                "Response bodies over ${formatBytes(ResponseDecoder.MAX_BODY_BYTES)} are not loaded into the IDE. Use Copy as cURL to download it, or request a smaller page.",
            )
        }
        if (has(Regex("ENOTFOUND|EAI_AGAIN"))) {
            return RequestErrorDescription("Could not find the server", "The host name did not resolve. Check it for typos, check your network or VPN, and check the environment's base URL.", ErrorAction.URL)
        }
        if (has(Regex("ECONNREFUSED"))) {
            return RequestErrorDescription("Connection refused", "Nothing accepted the connection at that address. Is the server running, and is the port right?", ErrorAction.URL)
        }
        if (has(Regex("ECONNABORTED|ETIMEDOUT|ESOCKETTIMEDOUT")) || Regex("timeout|timed out", RegexOption.IGNORE_CASE).containsMatchIn(text)) {
            val limit = timeoutMs?.let { if (it >= 1000) " after ${formatSeconds(it)} s" else " after $it ms" }.orEmpty()
            return RequestErrorDescription("The request timed out$limit", "The server did not answer in time. Raise the timeout in Settings, or check that the server is responding.", ErrorAction.SETTINGS)
        }
        if (has(Regex("CERT|SELF_SIGNED|UNABLE_TO_VERIFY|UNABLE_TO_GET_ISSUER|ERR_TLS|PKIX|certification path"))) {
            return RequestErrorDescription("SSL certificate problem", "The server's certificate could not be verified. For a local or test server with a self-signed certificate, turn off certificate verification in Settings.", ErrorAction.SETTINGS)
        }
        if (has(Regex("EPROTO|wrong version number|ERR_SSL|Unsupported or unrecognized SSL message|plaintext connection"))) {
            return RequestErrorDescription("TLS handshake failed", "This often means https:// was used for a server that only speaks http://. Try http://, or check the port.", ErrorAction.URL)
        }
        if (has(Regex("ERR_FR_TOO_MANY_REDIRECTS|Maximum number of redirects"))) {
            return RequestErrorDescription("Too many redirects", "The server kept redirecting. Raise the limit or turn off following redirects in Settings to see the first response.", ErrorAction.SETTINGS)
        }
        if (has(Regex("ECONNRESET|socket hang up|EPIPE|Connection reset|header parser received no bytes|EOF reached"))) {
            return RequestErrorDescription("The connection was closed", "The server or a proxy dropped the connection before answering. Check http versus https, the proxy settings, and the server logs.", ErrorAction.SETTINGS)
        }
        if (has(Regex("EHOSTUNREACH|ENETUNREACH|ENETDOWN"))) {
            return RequestErrorDescription("Network unreachable", "Your machine could not reach that network. Check your connection, VPN or firewall.")
        }
        if (has(Regex("ERR_BAD_OPTION|proxy", RegexOption.IGNORE_CASE))) {
            return RequestErrorDescription("Proxy problem", "The request could not go through the configured proxy. Check the proxy host and port in Settings.", ErrorAction.SETTINGS)
        }
        return RequestErrorDescription("The request failed", "No response was received. The details below come from the network layer.")
    }

    /** The Node-style error code the VS Code client would have seen for a JDK exception. */
    fun codeFor(error: Throwable): String? {
        // The JDK wraps the real cause (an unresolved host arrives as a ConnectException
        // caused by UnresolvedAddressException), so the whole chain is checked, most specific first.
        val chain = generateSequence(error) { it.cause.takeIf { cause -> cause !== it } }.take(20).toList()
        val certificate = Regex("PKIX|certificate|certification|subject alternative name|hostname", RegexOption.IGNORE_CASE)
        return when {
            chain.any { it is UnknownHostException || it is UnresolvedAddressException } -> "ENOTFOUND"
            chain.any { it is HttpConnectTimeoutException || it is HttpTimeoutException } -> "ETIMEDOUT"
            chain.any { it is NoRouteToHostException } -> "EHOSTUNREACH"
            // Untrusted issuer, expiry, or a host name the certificate does not cover.
            chain.any { it is CertificateException || (it is SSLException && certificate.containsMatchIn(it.message.orEmpty())) } -> "CERT_INVALID"
            chain.any { it is SSLException } -> "EPROTO"
            chain.any { it is ConnectException && it.message.orEmpty().contains("timed out", true) } -> "ETIMEDOUT"
            chain.any { it is ConnectException } -> "ECONNREFUSED"
            chain.any { (it is SocketException && it.message.orEmpty().contains("reset", true)) || it.message.orEmpty().contains("header parser received no bytes") || it.message.orEmpty().contains("EOF reached") } -> "ECONNRESET"
            else -> null
        }
    }

    /** The most useful message in an exception chain (the JDK often wraps the real one). */
    fun messageFor(error: Throwable): String {
        var current: Throwable? = error
        var best: String? = null
        while (current != null) {
            current.message?.takeIf { it.isNotBlank() }?.let { best = it }
            current = current.cause
        }
        return best ?: error.javaClass.simpleName
    }

    private fun formatSeconds(ms: Long): String {
        val tenths = Math.round(ms / 100.0)
        return if (tenths % 10 == 0L) (tenths / 10).toString() else (tenths / 10.0).toString()
    }
}
