package pro.devsnip.api.ide.engine

import com.google.gson.JsonNull
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.intellij.openapi.application.ApplicationManager
import io.netty.buffer.Unpooled
import io.netty.channel.ChannelFutureListener
import io.netty.channel.ChannelHandlerContext
import io.netty.handler.codec.http.DefaultFullHttpResponse
import io.netty.handler.codec.http.FullHttpRequest
import io.netty.handler.codec.http.HttpHeaderNames
import io.netty.handler.codec.http.HttpMethod
import io.netty.handler.codec.http.HttpRequest
import io.netty.handler.codec.http.HttpResponseStatus
import io.netty.handler.codec.http.HttpVersion
import io.netty.handler.codec.http.QueryStringDecoder
import org.jetbrains.ide.BuiltInServerManager
import org.jetbrains.ide.HttpRequestHandler
import java.nio.charset.StandardCharsets
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap

/**
 * The extension host's synchronous calls (Node's fs.readFileSync, fs.statSync, ...):
 * a synchronous request from the host page to the IDE's built-in local web
 * server, answered before the page continues. It works the same with the
 * in-process JCEF of older IDEs and the out-of-process JCEF of 2026.2 and
 * later (where prompt() no longer reaches the IDE).
 *
 * Only a host page knows its token (random, 256 bits), and every request must
 * carry it; nothing is answered otherwise.
 */
class EngineSyncServer : HttpRequestHandler() {
    override fun isSupported(request: FullHttpRequest): Boolean =
        request.uri().startsWith(PREFIX) && (request.method() == HttpMethod.POST || request.method() == HttpMethod.OPTIONS)

    // The page is a local file (origin "null"): the token, not the origin, decides access.
    override fun isAccessible(request: HttpRequest): Boolean = true

    override fun process(urlDecoder: QueryStringDecoder, request: FullHttpRequest, context: ChannelHandlerContext): Boolean {
        val channel = context.channel()
        if (request.method() == HttpMethod.OPTIONS) {
            respond(context, HttpResponseStatus.NO_CONTENT, "")
            return true
        }
        val token = urlDecoder.path().removePrefix(PREFIX)
        val engine = engines[token]
        if (engine == null || engine.project.isDisposed) {
            respond(context, HttpResponseStatus.FORBIDDEN, "{\"error\":\"Unknown extension host.\"}")
            return true
        }
        val body = request.content().toString(StandardCharsets.UTF_8)
        // File work runs off the server's I/O thread; the answer is written when it is ready.
        ApplicationManager.getApplication().executeOnPooledThread {
            val answer = JsonObject()
            try {
                val call = JsonParser.parseString(body).asJsonObject
                answer.add("result", engine.host.sync(call["method"].asString, call["params"] as? JsonObject ?: JsonObject()) ?: JsonNull.INSTANCE)
            } catch (e: Exception) {
                answer.addProperty("error", e.message ?: e.javaClass.simpleName)
                (e as? EngineHost.CodedException)?.code?.let { answer.addProperty("code", it) }
            }
            if (channel.isActive) respond(context, HttpResponseStatus.OK, answer.toString())
        }
        return true
    }

    private fun respond(context: ChannelHandlerContext, status: HttpResponseStatus, text: String) {
        val response = DefaultFullHttpResponse(HttpVersion.HTTP_1_1, status, Unpooled.copiedBuffer(text, StandardCharsets.UTF_8))
        response.headers().set(HttpHeaderNames.CONTENT_TYPE, "application/json; charset=utf-8")
        response.headers().set(HttpHeaderNames.CONTENT_LENGTH, response.content().readableBytes())
        response.headers().set(HttpHeaderNames.ACCESS_CONTROL_ALLOW_ORIGIN, "*")
        response.headers().set(HttpHeaderNames.ACCESS_CONTROL_ALLOW_METHODS, "POST")
        response.headers().set(HttpHeaderNames.ACCESS_CONTROL_ALLOW_HEADERS, "Content-Type")
        response.headers().set(HttpHeaderNames.CACHE_CONTROL, "no-store")
        context.channel().writeAndFlush(response).addListener(ChannelFutureListener.CLOSE)
    }

    companion object {
        private const val PREFIX = "/devsnip-sync/"
        private val engines = ConcurrentHashMap<String, DevSnipEngine>()
        private val random = SecureRandom()

        /** The URL a host page sends its synchronous calls to, or null when the built-in server is off. */
        fun register(engine: DevSnipEngine): String? {
            val port = runCatching { BuiltInServerManager.getInstance().waitForStart().port }.getOrNull()?.takeIf { it > 0 } ?: return null
            val token = ByteArray(32).also(random::nextBytes).joinToString("") { "%02x".format(it) }
            engines[token] = engine
            return "http://127.0.0.1:$port$PREFIX$token"
        }

        fun unregister(engine: DevSnipEngine) {
            engines.entries.removeIf { it.value === engine }
        }
    }
}
