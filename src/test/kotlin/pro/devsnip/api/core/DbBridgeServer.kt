package pro.devsnip.api.core

import com.google.gson.JsonNull
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import pro.devsnip.api.ide.engine.DbBridge
import pro.devsnip.api.ide.web.RestClientWire
import java.io.PrintStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/**
 * The IDE's database and HTTP side for webview/test/db-servers.mjs: reads
 * one JSON request per line on stdin ({id, method, params}) and answers on
 * stdout as EngineHost does (`__rpcResult`, and `__stream` events for
 * streamed HTTP). It lets the Node test run the extension's own database
 * adapters, through the driver stand-ins, against real servers.
 */
object DbBridgeServer {
    private val out = PrintStream(System.out, true, "UTF-8")
    private val bridge = DbBridge()
    private val http = RawHttp()
    private val running = ConcurrentHashMap<String, CancellationToken>()

    @Synchronized
    private fun send(message: JsonObject) = out.println(message.toString())

    @JvmStatic
    fun main(args: Array<String>) {
        val pool = Executors.newCachedThreadPool()
        System.setOut(System.err)
        generateSequence(::readLine).forEach { line ->
            val request = runCatching { JsonParser.parseString(line).asJsonObject }.getOrNull() ?: return@forEach
            pool.execute { answer(request) }
        }
        bridge.closeAll()
        pool.shutdownNow()
    }

    private fun answer(request: JsonObject) {
        val id = request["id"]
        val method = request["method"].asString
        val params = request["params"]?.asJsonObject ?: JsonObject()
        fun reply(result: com.google.gson.JsonElement?, error: Throwable?) = send(JsonObject().apply {
            addProperty("type", "__rpcResult")
            add("id", id)
            if (error != null) {
                addProperty("error", error.message ?: error.javaClass.simpleName)
                add("errorData", (error as? DbBridge.DbException)?.data ?: JsonObject().apply { (error as? RawHttpException)?.code?.let { addProperty("code", it) } })
            } else {
                add("result", result ?: JsonNull.INSTANCE)
            }
        })
        try {
            when (method) {
                "http.raw" -> raw(params, ::reply)
                "http.cancel" -> { running[params["id"].asString]?.cancel(); reply(null, null) }
                else -> reply(bridge.handle(method, params), null)
            }
        } catch (e: Throwable) {
            reply(null, e)
        }
    }

    /** RestClientHost.sendRaw, without the IDE. */
    private fun raw(p: JsonObject, reply: (com.google.gson.JsonElement?, Throwable?) -> Unit) {
        val id = p["id"].asString
        val request = RawRequest(
            method = p["method"]?.asString ?: "GET",
            url = p["url"].asString,
            headers = p["headers"]?.asJsonObject?.entrySet()?.map { it.key to it.value.asString }.orEmpty(),
            body = p["body"]?.takeIf { it.isJsonPrimitive }?.asString,
            timeoutMs = p["timeoutMs"]?.asLong ?: 0,
        )
        val token = CancellationToken()
        running[id] = token
        fun head(response: RawResponse) = JsonObject().apply {
            addProperty("status", response.status)
            addProperty("statusText", response.statusText)
            add("headers", RestClientWire.headers(response.headers))
            response.body?.let { addProperty("body", it) }
        }
        fun event(name: String, value: String?) = send(JsonObject().apply {
            addProperty("type", "__stream")
            addProperty("id", id)
            addProperty("event", name)
            value?.let { addProperty("value", it) }
        })
        try {
            if (p["stream"]?.asBoolean == true) {
                http.send(request, token, onHeaders = { reply(head(it), null) }, onChunk = { event("data", it) })
                event("end", null)
            } else {
                reply(head(http.send(request, token)), null)
            }
        } finally {
            running.remove(id)
        }
    }
}
