package pro.devsnip.api.core

import com.google.gson.JsonElement
import java.io.ByteArrayInputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.util.zip.GZIPInputStream
import java.util.zip.InflaterInputStream

/** What a response body is, for display. */
sealed class DecodedBody {
    /** Text shown in the response viewer. */
    abstract val display: String

    object Empty : DecodedBody() {
        override val display = ""
    }

    data class JsonBody(val element: JsonElement, val raw: String) : DecodedBody() {
        override val display: String get() = Json.prettyPrint(element)
    }

    data class Text(val text: String) : DecodedBody() {
        override val display get() = text
    }

    data class Binary(val contentType: String, val size: Long) : DecodedBody() {
        override val display: String
            get() = "[Binary response: ${contentType.ifEmpty { "unknown type" }}, ${formatBytes(size)}. It is not shown as text; use Copy as cURL to fetch the bytes.]"
    }

    /** The raw text of the body (minified as received for JSON), used by assertions and copy. */
    val rawText: String
        get() = when (this) {
            Empty -> ""
            is JsonBody -> raw
            is Text -> text
            is Binary -> display
        }
}

/**
 * A response body larger than the client loads. The VS Code client runs in its
 * own extension host process; here the body would fill the IDE's own memory.
 */
class ResponseTooLargeException(val limit: Long) : IOException("Response body larger than ${formatBytes(limit)}")

/** Port of `ApiTester.decodeBody` and `formatBytes` from the VS Code extension. */
object ResponseDecoder {
    const val MAX_DISPLAY_BYTES = 2 * 1024 * 1024

    /** The most a response body may hold, received or decompressed (the IDE's heap is shared with everything else). */
    const val MAX_BODY_BYTES = 100L * 1024 * 1024

    private val DECLARED_TEXT = Regex("^text/|json|xml|javascript|ecmascript|x-www-form-urlencoded|graphql|yaml|csv|svg|html")
    private val DECLARED_BINARY = Regex("^(image|audio|video|font)/|application/(octet-stream|pdf|zip|gzip|x-tar|x-7z|vnd\\.|msword|protobuf|x-protobuf|wasm)")
    private val LOOKS_JSON = Regex("^[\\[{\"]|^(true|false|null|-?\\d)")

    /**
     * Turns response bytes into what the panel shows: text is decoded and parsed
     * as JSON when it is JSON; binary content is described rather than shown as
     * broken text. Bodies over [MAX_DISPLAY_BYTES] are cut and shown as text.
     */
    fun decode(raw: ByteArray, contentType: String): Pair<DecodedBody, Boolean> {
        if (raw.isEmpty()) return DecodedBody.Empty to false
        val type = contentType.lowercase()
        val mime = type.substringBefore(';').trim()
        val declaredText = DECLARED_TEXT.containsMatchIn(type)
        val declaredBinary = !declaredText && DECLARED_BINARY.containsMatchIn(type)
        val charset = charsetOf(type)
        val strict = decodeStrict(raw, charset)
        val looksBinary = declaredBinary || (!declaredText && (raw.contains(0.toByte()) || strict == null))
        if (looksBinary) return DecodedBody.Binary(mime, raw.size.toLong()) to false

        val text = strict ?: String(raw, charset)
        if (raw.size > MAX_DISPLAY_BYTES) {
            return DecodedBody.Text(text.take(MAX_DISPLAY_BYTES) + "\n... [response truncated]") to true
        }
        val trimmed = text.trim()
        if (trimmed.isNotEmpty() && (type.contains("json") || LOOKS_JSON.containsMatchIn(trimmed))) {
            Json.parseOrNull(trimmed)?.let { return DecodedBody.JsonBody(it, text) to false }
        }
        return DecodedBody.Text(text) to false
    }

    /** Undoes `Content-Encoding: gzip/deflate`; the JDK client does not do it. More than [limit] bytes out fails. */
    fun decompress(raw: ByteArray, contentEncoding: String?, limit: Long = MAX_BODY_BYTES): ByteArray {
        val encoding = contentEncoding?.trim()?.lowercase().orEmpty()
        if (raw.isEmpty()) return raw
        return try {
            when (encoding) {
                "gzip", "x-gzip" -> GZIPInputStream(ByteArrayInputStream(raw)).use { readLimited(it, limit) }
                "deflate" -> InflaterInputStream(ByteArrayInputStream(raw)).use { readLimited(it, limit) }
                else -> raw
            }
        } catch (e: ResponseTooLargeException) {
            throw e
        } catch (_: IOException) {
            raw
        }
    }

    private fun readLimited(input: java.io.InputStream, limit: Long): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(64 * 1024)
        var total = 0L
        while (true) {
            val n = input.read(buffer)
            if (n < 0) return out.toByteArray()
            total += n
            if (total > limit) throw ResponseTooLargeException(limit)
            out.write(buffer, 0, n)
        }
    }

    private fun charsetOf(type: String): Charset {
        val name = Regex("charset=\"?([\\w.:-]+)").find(type)?.groupValues?.get(1) ?: return Charsets.UTF_8
        return try {
            Charset.forName(name)
        } catch (_: Exception) {
            Charsets.UTF_8
        }
    }

    private fun decodeStrict(raw: ByteArray, charset: Charset): String? = try {
        charset.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(raw))
            .toString()
    } catch (_: CharacterCodingException) {
        null
    }
}

fun formatBytes(bytes: Long): String = when {
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> "%.2f KB".format(java.util.Locale.ROOT, bytes / 1024.0).replace(".00 ", " ")
    else -> "%.2f MB".format(java.util.Locale.ROOT, bytes / (1024.0 * 1024)).replace(".00 ", " ")
}
