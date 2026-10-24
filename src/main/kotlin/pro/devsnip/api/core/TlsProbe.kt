package pro.devsnip.api.core

import java.net.InetSocketAddress
import java.net.Socket
import java.security.KeyStore
import java.security.cert.X509Certificate
import java.security.interfaces.ECPublicKey
import java.security.interfaces.RSAPublicKey
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import javax.naming.ldap.LdapName
import javax.net.ssl.SNIHostName
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLEngine
import javax.net.ssl.SSLSocket
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509ExtendedTrustManager

/** What Node's TLSSocket reports after a handshake (getPeerCertificate, getCipher, getProtocol, authorized). */
data class TlsObservation(
    val protocol: String?,
    val cipher: String?,
    val authorized: Boolean,
    val authorizationError: String?,
    val subjectCn: String?,
    val issuerO: String?,
    val issuerCn: String?,
    /** Node's format, for example "Oct 24 12:00:00 2026 GMT". */
    val validFrom: String?,
    val validTo: String?,
    /** Node's format: "DNS:example.com, IP Address:127.0.0.1". */
    val subjectAltNames: String?,
    val keyBits: Int?,
    val nistCurve: String?,
)

/**
 * The TLS handshake of the endpoint security scan (`probeTls` in the
 * extension's security-probe.ts). The connection never fails on a bad
 * certificate: the JDK's own verdict (chain and host name) is recorded as
 * `authorized`, as Node does with rejectUnauthorized: false.
 */
object TlsProbe {
    fun probe(host: String, port: Int, serverName: String = host, timeoutMs: Int = 15_000): TlsObservation {
        val trust = RecordingTrustManager(defaultTrustManager())
        val context = SSLContext.getInstance("TLS").apply { init(null, arrayOf(trust), null) }
        Socket().use { plain ->
            plain.connect(InetSocketAddress(host, port), timeoutMs)
            plain.soTimeout = timeoutMs
            val socket = context.socketFactory.createSocket(plain, serverName, port, true) as SSLSocket
            socket.use {
                socket.sslParameters = socket.sslParameters.apply {
                    endpointIdentificationAlgorithm = "HTTPS"
                    if (!serverName.matches(Regex("^[0-9.:\\[\\]]+$"))) serverNames = listOf(SNIHostName(serverName))
                }
                socket.startHandshake()
                val session = socket.session
                val leaf = session.peerCertificates.firstOrNull() as? X509Certificate
                return TlsObservation(
                    protocol = session.protocol,
                    cipher = session.cipherSuite,
                    authorized = trust.error == null,
                    authorizationError = trust.error,
                    subjectCn = leaf?.let { rdn(it.subjectX500Principal.name, "CN") },
                    issuerO = leaf?.let { rdn(it.issuerX500Principal.name, "O") },
                    issuerCn = leaf?.let { rdn(it.issuerX500Principal.name, "CN") },
                    validFrom = leaf?.notBefore?.let(::nodeDate),
                    validTo = leaf?.notAfter?.let(::nodeDate),
                    subjectAltNames = leaf?.let(::altNames),
                    keyBits = when (val key = leaf?.publicKey) {
                        is RSAPublicKey -> key.modulus.bitLength()
                        is ECPublicKey -> key.params.curve.field.fieldSize
                        else -> null
                    },
                    nistCurve = (leaf?.publicKey as? ECPublicKey)?.let { curveName(it.params.curve.field.fieldSize) },
                )
            }
        }
    }

    private fun defaultTrustManager(): X509ExtendedTrustManager {
        val factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
        factory.init(null as KeyStore?)
        return factory.trustManagers.filterIsInstance<X509ExtendedTrustManager>().first()
    }

    /** Accepts every certificate but remembers what the default trust manager thought of it. */
    private class RecordingTrustManager(private val delegate: X509ExtendedTrustManager) : X509ExtendedTrustManager() {
        @Volatile
        var error: String? = "The certificate was not checked."

        private fun record(check: () -> Unit) {
            error = try {
                check()
                null
            } catch (e: Exception) {
                RequestErrors.messageFor(e)
            }
        }

        override fun checkServerTrusted(chain: Array<out X509Certificate>, authType: String, socket: Socket?) = record { delegate.checkServerTrusted(chain, authType, socket) }
        override fun checkServerTrusted(chain: Array<out X509Certificate>, authType: String, engine: SSLEngine?) = record { delegate.checkServerTrusted(chain, authType, engine) }
        override fun checkServerTrusted(chain: Array<out X509Certificate>, authType: String) = record { delegate.checkServerTrusted(chain, authType) }
        override fun checkClientTrusted(chain: Array<out X509Certificate>, authType: String, socket: Socket?) = Unit
        override fun checkClientTrusted(chain: Array<out X509Certificate>, authType: String, engine: SSLEngine?) = Unit
        override fun checkClientTrusted(chain: Array<out X509Certificate>, authType: String) = Unit
        override fun getAcceptedIssuers(): Array<X509Certificate> = delegate.acceptedIssuers
    }

    private fun rdn(name: String, type: String): String? = try {
        LdapName(name).rdns.lastOrNull { it.type.equals(type, ignoreCase = true) }?.value?.toString()
    } catch (_: Exception) {
        null
    }

    private fun nodeDate(date: java.util.Date): String =
        SimpleDateFormat("MMM d HH:mm:ss yyyy 'GMT'", Locale.ENGLISH).apply { timeZone = TimeZone.getTimeZone("GMT") }.format(date)

    private fun altNames(certificate: X509Certificate): String? {
        val names = try {
            certificate.subjectAlternativeNames
        } catch (_: Exception) {
            null
        } ?: return null
        return names.mapNotNull { entry ->
            when (entry[0] as Int) {
                2 -> "DNS:${entry[1]}"
                7 -> "IP Address:${entry[1]}"
                6 -> "URI:${entry[1]}"
                1 -> "email:${entry[1]}"
                else -> null
            }
        }.joinToString(", ").ifEmpty { null }
    }

    private fun curveName(fieldSize: Int) = when (fieldSize) {
        256 -> "P-256"
        384 -> "P-384"
        521 -> "P-521"
        else -> null
    }
}
