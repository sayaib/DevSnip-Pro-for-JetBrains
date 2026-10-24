package pro.devsnip.api.core

import com.google.gson.GsonBuilder
import com.google.gson.JsonElement
import com.google.gson.JsonParseException
import com.google.gson.JsonParser
import com.google.gson.Strictness
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import java.io.IOException
import java.io.StringReader

/** Strict JSON helpers: `JsonParser.parseString` is lenient and accepts bare words. */
object Json {
    private val pretty = GsonBuilder().setPrettyPrinting().disableHtmlEscaping().serializeNulls().create()
    private val compact = GsonBuilder().disableHtmlEscaping().serializeNulls().create()

    /** Parses one strict JSON value, or returns null when [text] is not exactly that. */
    fun parseOrNull(text: String): JsonElement? {
        if (text.isBlank()) return null
        return try {
            val reader = JsonReader(StringReader(text))
            reader.setStrictness(Strictness.STRICT)
            val element = JsonParser.parseReader(reader)
            if (reader.peek() != JsonToken.END_DOCUMENT) null else element
        } catch (_: JsonParseException) {
            null
        } catch (_: IOException) {
            null
        } catch (_: IllegalStateException) {
            null
        }
    }

    fun isValid(text: String) = parseOrNull(text) != null

    fun prettyPrint(element: JsonElement): String = pretty.toJson(element)

    fun toCompact(element: JsonElement): String = compact.toJson(element)

    fun toJson(value: Any?): String = compact.toJson(value)

    fun toPrettyJson(value: Any?): String = pretty.toJson(value)
}
