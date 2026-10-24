package pro.devsnip.api.core.toolkit

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Every "Backend & API" tool must give exactly the result the VS Code
 * extension gives. The reference results were recorded by running the
 * extension's own TypeScript (scripts/export-backend-api.js) over 256 inputs:
 * defaults, every mode and example, many edge cases and the error paths.
 */
class BackendApiParityTest {
    /** Error messages that quote the JSON/YAML parser's own wording, which differs between V8 and the JVM. */
    private val parserMessagePrefixes = listOf("Not valid JSON:", "The document does not parse:", "The request is not valid JSON:", "The response is not valid JSON:")

    @Suppress("UNCHECKED_CAST")
    private val cases: List<Map<String, Any?>> by lazy {
        val text = javaClass.getResourceAsStream("/toolkit/golden-backend-api.json")!!.use { it.readBytes().toString(Charsets.UTF_8) }
        Js.parseJson(text) as List<Map<String, Any?>>
    }

    @Test
    fun `all eight Backend and API tools are present`() {
        assertEquals(
            listOf("cURL Converter", "API Response Inspector", "URL & Query String Tool", "CORS Builder & Debugger", "OpenAPI / Swagger Toolkit", "GraphQL Formatter & Types", "API Resource Scaffolder"),
            BackendApiTools.all.map { it.title },
        )
    }

    @Test
    @Suppress("UNCHECKED_CAST")
    fun `every tool matches the VS Code extension on all reference cases`() {
        assertTrue(cases.size >= 250, "expected the full reference set, got ${cases.size}")
        val failures = mutableListOf<String>()
        for (case in cases) {
            val tool = BackendApiTools.byId(case["tool"] as String)
            val values = case["values"] as Map<String, Any?>
            val label = "${case["tool"]} / ${case["label"]}"
            val expectedError = case["error"] as? Map<String, Any?>
            val actual = try {
                tool.run(values)
            } catch (e: ToolInputError) {
                if (expectedError == null) {
                    failures += "$label: unexpected error ${e.message}"
                } else {
                    val expected = expectedError["message"] as String
                    val prefix = parserMessagePrefixes.firstOrNull { expected.startsWith(it) }
                    val same = if (prefix != null) e.message!!.startsWith(prefix) else e.message == expected
                    if (!same) failures += "$label: error\n  expected: $expected\n  actual:   ${e.message}"
                }
                continue
            } catch (e: Exception) {
                failures += "$label: crashed with $e"
                continue
            }
            if (expectedError != null) {
                failures += "$label: expected error \"${expectedError["message"]}\" but got a result"
                continue
            }
            val expected = case["result"]
            val got = toJson(actual)
            if (got != expected) failures += "$label:\n${describe(expected, got)}"
        }
        assertTrue(failures.isEmpty(), "${failures.size} of ${cases.size} cases differ from VS Code:\n\n" + failures.take(15).joinToString("\n\n"))
    }

    /** The result in the shape `JSON.stringify` gives in the extension (undefined fields left out). */
    private fun toJson(r: ToolResult): Map<String, Any?> = linkedMapOf<String, Any?>().apply {
        r.stats?.let { stats -> put("stats", stats.map { s -> linkedMapOf<String, Any?>("label" to s.label, "value" to s.value).apply { s.tone?.let { put("tone", it.name.lowercase()) } } }) }
        r.messages?.let { messages -> put("messages", messages.map { mapOf("kind" to it.kind.name.lowercase(), "text" to it.text) }) }
        r.outputs?.let { outputs -> put("outputs", outputs.map(::output)) }
    }

    private fun output(o: Output): Map<String, Any?> = when (o) {
        is Output.Code -> linkedMapOf<String, Any?>("kind" to "code", "title" to o.title, "language" to o.language, "content" to o.content).apply { o.fileName?.let { put("fileName", it) } }
        is Output.Text -> mapOf("kind" to "text", "title" to o.title, "content" to o.content)
        is Output.Table -> mapOf("kind" to "table", "title" to o.title, "columns" to o.columns, "rows" to o.rows.map { row -> row.map { if (it is Number) it.toDouble() else it } })
        is Output.Files -> mapOf(
            "kind" to "files", "title" to o.title,
            "files" to o.files.map { f -> linkedMapOf<String, Any?>("path" to f.path, "content" to f.content).apply { f.language?.let { put("language", it) }; f.mode?.let { put("mode", it) } } },
        )
    }

    /** Points at the first difference so a failure is quick to fix. */
    @Suppress("UNCHECKED_CAST")
    private fun describe(expected: Any?, actual: Any?, path: String = "result"): String {
        if (expected is Map<*, *> && actual is Map<*, *>) {
            for (key in (expected.keys + actual.keys).toSet()) {
                if (expected[key] != actual[key]) return describe(expected[key], actual[key], "$path.$key")
            }
        }
        if (expected is List<*> && actual is List<*>) {
            for (i in 0 until maxOf(expected.size, actual.size)) {
                if (expected.getOrNull(i) != actual.getOrNull(i)) return describe(expected.getOrNull(i), actual.getOrNull(i), "$path[$i]")
            }
        }
        if (expected is String && actual is String) {
            val el = expected.lines()
            val al = actual.lines()
            val line = (0 until maxOf(el.size, al.size)).first { el.getOrNull(it) != al.getOrNull(it) }
            return "  $path line ${line + 1}\n    expected: ${el.getOrNull(line)}\n    actual:   ${al.getOrNull(line)}"
        }
        return "  $path\n    expected: ${expected.toString().take(300)}\n    actual:   ${actual.toString().take(300)}"
    }
}
