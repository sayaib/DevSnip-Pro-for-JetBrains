package pro.devsnip.api.core

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import com.sun.net.httpserver.HttpsConfigurator
import com.sun.net.httpserver.HttpsServer
import java.io.File
import java.net.InetSocketAddress
import java.security.KeyStore
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext

/** What the mock server received, for assertions. */
data class Received(val method: String, val path: String, val query: String?, val headers: Map<String, List<String>>, val body: String) {
    fun header(name: String): String? = headers.entries.firstOrNull { it.key.equals(name, true) }?.value?.firstOrNull()
}

/** A local HTTP(S) server on an ephemeral port; nothing leaves the machine. */
class MockServer private constructor(private val server: HttpServer, val scheme: String) : AutoCloseable {
    val requests = CopyOnWriteArrayList<Received>()
    val port: Int get() = server.address.port

    fun url(path: String, host: String = "127.0.0.1") = "$scheme://$host:$port$path"

    fun handle(path: String, handler: (HttpExchange, Received) -> Unit): MockServer {
        server.createContext(path) { exchange ->
            val received = Received(
                exchange.requestMethod,
                exchange.requestURI.path,
                exchange.requestURI.rawQuery,
                exchange.requestHeaders.toMap(),
                exchange.requestBody.readBytes().toString(Charsets.UTF_8),
            )
            requests += received
            try {
                handler(exchange, received)
            } finally {
                exchange.close()
            }
        }
        return this
    }

    override fun close() = server.stop(0)

    companion object {
        fun http(): MockServer = start(HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0), "http")

        /** HTTPS with a freshly generated self-signed certificate (not trusted by the JDK). */
        fun selfSignedHttps(workDir: File): MockServer {
            val keystore = File(workDir, "selfsigned.p12")
            if (!keystore.exists()) {
                val keytool = File(System.getProperty("java.home"), "bin/keytool").path
                val process = ProcessBuilder(
                    keytool, "-genkeypair", "-alias", "test", "-keyalg", "RSA", "-keysize", "2048", "-validity", "2",
                    "-dname", "CN=devsnip-test", "-storetype", "PKCS12", "-keystore", keystore.path,
                    "-storepass", "changeit", "-keypass", "changeit",
                ).redirectErrorStream(true).start()
                val output = process.inputStream.readBytes().toString(Charsets.UTF_8)
                check(process.waitFor() == 0) { "keytool failed: $output" }
            }
            val store = KeyStore.getInstance("PKCS12").apply { keystore.inputStream().use { load(it, "changeit".toCharArray()) } }
            val keyManagers = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()).apply { init(store, "changeit".toCharArray()) }.keyManagers
            val context = SSLContext.getInstance("TLS").apply { init(keyManagers, null, null) }
            val server = HttpsServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply { httpsConfigurator = HttpsConfigurator(context) }
            return start(server, "https")
        }

        private fun start(server: HttpServer, scheme: String): MockServer {
            server.executor = Executors.newCachedThreadPool()
            server.start()
            return MockServer(server, scheme)
        }
    }
}

fun HttpExchange.respond(status: Int, body: String = "", contentType: String? = "application/json", headers: Map<String, String> = emptyMap()) {
    contentType?.let { responseHeaders.add("Content-Type", it) }
    headers.forEach { (k, v) -> responseHeaders.add(k, v) }
    val bytes = body.toByteArray(Charsets.UTF_8)
    sendResponseHeaders(status, if (bytes.isEmpty()) -1 else bytes.size.toLong())
    if (bytes.isNotEmpty()) responseBody.use { it.write(bytes) }
}

fun HttpExchange.respondBytes(status: Int, bytes: ByteArray, headers: Map<String, String>) {
    headers.forEach { (k, v) -> responseHeaders.add(k, v) }
    sendResponseHeaders(status, bytes.size.toLong())
    responseBody.use { it.write(bytes) }
}
