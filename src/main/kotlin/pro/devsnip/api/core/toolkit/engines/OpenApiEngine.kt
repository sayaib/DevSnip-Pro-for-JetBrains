package pro.devsnip.api.core.toolkit.engines

import pro.devsnip.api.core.toolkit.Issue
import pro.devsnip.api.core.toolkit.Js
import pro.devsnip.api.core.toolkit.ToolInputError
import pro.devsnip.api.core.toolkit.Yaml

/**
 * OpenAPI 3.x and Swagger 2.0: list and lint endpoints, generate TypeScript
 * types and a typed fetch client, example requests, and OpenAPI from samples.
 * Port of the OpenAPI part of `src/toolkits/engines/web-apispec.ts`.
 */
@Suppress("UNCHECKED_CAST")
object OpenApiEngine {
    private const val D = "$"
    private val METHODS = listOf("get", "put", "post", "delete", "options", "head", "patch", "trace")

    /** `undefined` in JavaScript: what a missing key or path reads as. */
    private object Undefined

    private fun obj(v: Any?): Map<String, Any?>? = v as? Map<String, Any?>
    private fun isObj(v: Any?) = v is Map<*, *>
    private fun Map<String, Any?>.js(key: String): Any? = if (containsKey(key)) get(key) else Undefined

    /** `String(value)`, where a missing value is "undefined". */
    private fun jsStr(value: Any?): String = if (value === Undefined) "undefined" else Js.string(value)

    /** `a ?? b`. */
    private fun Any?.orElse(other: Any?): Any? = if (this == null || this === Undefined) other else this

    private fun truthy(v: Any?) = v !== Undefined && Js.truthy(v)

    fun parseSpec(text: String): Map<String, Any?> {
        val t = text.trim()
        if (t.isEmpty()) throw ToolInputError("Paste an OpenAPI / Swagger document (JSON or YAML).")
        val doc = try {
            if (t.startsWith("{")) Js.parseJson(t) else Yaml.parse(t)
        } catch (e: Js.JsonSyntaxError) {
            throw ToolInputError("The document does not parse: ${e.message?.split("\n")?.first()}")
        } catch (e: Yaml.YamlError) {
            throw ToolInputError("The document does not parse: ${e.message?.split("\n")?.first()}")
        }
        val map = obj(doc) ?: throw ToolInputError("The document must be an object.")
        if (!truthy(map.js("openapi")) && !truthy(map.js("swagger"))) throw ToolInputError("No \"openapi\" or \"swagger\" version field - is this an OpenAPI document?")
        return map
    }

    fun specSchemas(doc: Map<String, Any?>): Map<String, Any?> {
        val components = obj(doc["components"])
        return obj(components?.get("schemas")) ?: obj(doc["definitions"]) ?: emptyMap()
    }

    private fun resolveRef(doc: Map<String, Any?>, ref: String): Any? {
        if (!ref.startsWith("#/")) return Undefined
        var node: Any? = doc
        for (part in ref.substring(2).split("/").map { it.replace("~1", "/").replace("~0", "~") }) {
            val map = obj(node) ?: return Undefined
            node = map.js(part)
        }
        return node
    }

    private fun deref(doc: Map<String, Any?>, v: Any?): Map<String, Any?> {
        val map = obj(v) ?: return emptyMap()
        val ref = map["\$ref"]
        if (ref is String) return obj(resolveRef(doc, ref)) ?: emptyMap()
        return map
    }

    data class Param(val name: String, val `in`: String, val required: Boolean, val schema: Map<String, Any?>)
    data class Body(val contentType: String, val schema: Map<String, Any?>, val required: Boolean)
    data class Response(val status: String, val schema: Map<String, Any?>?, val description: String)
    data class Operation(
        val method: String,
        val path: String,
        val operationId: String,
        val summary: String,
        val tags: List<String>,
        val params: List<Param>,
        val body: Body?,
        val responses: List<Response>,
        val secured: Boolean,
        val deprecated: Boolean,
    )

    fun operationName(method: String, path: String): String {
        val words = path.split("/").filter { it.isNotEmpty() }.map { seg -> if (seg.startsWith("{")) "By ${seg.substring(1, seg.length - 1)}" else seg }
        val camel = words.joinToString(" ").replace(Regex("[^A-Za-z0-9]+(.)?")) { m -> m.groupValues[1].uppercase() }
        return method + camel.take(1).uppercase() + camel.drop(1)
    }

    fun operations(doc: Map<String, Any?>): List<Operation> {
        val out = mutableListOf<Operation>()
        val paths = obj(doc["paths"]) ?: emptyMap()
        val swagger2 = truthy(doc.js("swagger"))
        val globalSecurity = (doc["security"] as? List<*>)?.isNotEmpty() == true
        for ((path, rawItem) in Js.entries(paths)) {
            val item = deref(doc, rawItem)
            val shared = item["parameters"] as? List<*> ?: emptyList<Any?>()
            for (method in METHODS) {
                val op = obj(item[method]) ?: continue
                val params = mutableListOf<Param>()
                var body: Body? = null
                for (rawParam in shared + (op["parameters"] as? List<*> ?: emptyList<Any?>())) {
                    val p = deref(doc, rawParam)
                    if (swagger2 && p["in"] == "body") {
                        val schema = if (truthy(deref(doc, p["schema"]).js("\$ref"))) deref(doc, p["schema"]) else obj(p["schema"]) ?: emptyMap()
                        body = Body("application/json", schema, truthy(p.js("required")))
                        continue
                    }
                    if (swagger2 && p["in"] == "formData") continue
                    val schema = obj(p["schema"]) ?: if (swagger2) {
                        LinkedHashMap<String, Any?>().apply {
                            for (key in listOf("type", "items", "enum", "format")) if (p.containsKey(key)) put(key, p[key])
                        }
                    } else {
                        emptyMap()
                    }
                    val entry = Param(jsStr(p.js("name")), jsStr(p.js("in")), truthy(p.js("required")) || p["in"] == "path", schema)
                    val existing = params.indexOfFirst { it.name == entry.name && it.`in` == entry.`in` }
                    if (existing >= 0) params[existing] = entry else params += entry
                }
                obj(op["requestBody"])?.let {
                    val rb = deref(doc, it)
                    val content = obj(rb["content"]) ?: emptyMap()
                    val type = Js.keys(content).firstOrNull { k -> k.contains("json") } ?: Js.keys(content).firstOrNull()
                    if (type != null) body = Body(type, obj(obj(content[type])?.get("schema")) ?: emptyMap(), truthy(rb.js("required")))
                }
                val responses = mutableListOf<Response>()
                for ((status, rawRes) in Js.entries(obj(op["responses"]) ?: emptyMap<String, Any?>())) {
                    val r = deref(doc, rawRes)
                    var schema: Map<String, Any?>? = null
                    if (swagger2) obj(r["schema"])?.let { schema = it }
                    obj(r["content"])?.let { content ->
                        val type = Js.keys(content).firstOrNull { it.contains("json") } ?: Js.keys(content).firstOrNull()
                        val s = if (type != null) obj(content[type])?.get("schema") else null
                        obj(s)?.let { schema = it }
                    }
                    responses += Response(status, schema, jsStr(r.js("description").orElse("")))
                }
                out += Operation(
                    method = method.uppercase(),
                    path = path,
                    operationId = op["operationId"] as? String ?: "",
                    summary = jsStr(op.js("summary").orElse(op.js("description")).orElse("")).split("\n")[0],
                    tags = (op["tags"] as? List<*>)?.map { Js.string(it) } ?: emptyList(),
                    params = params,
                    body = body,
                    responses = responses,
                    secured = (op["security"] as? List<*>)?.let { it.isNotEmpty() } ?: globalSecurity,
                    deprecated = op["deprecated"] == true,
                )
            }
        }
        return out
    }

    fun lint(doc: Map<String, Any?>, ops: List<Operation>): List<Issue> {
        val issues = mutableListOf<Issue>()
        fun add(severity: String, message: String) {
            issues += Issue(severity, message)
        }
        val info = obj(doc["info"]) ?: emptyMap()
        if (!truthy(info.js("title"))) add("error", "info.title is required.")
        if (!truthy(info.js("version"))) add("error", "info.version is required.")
        if (truthy(doc.js("swagger"))) add("info", "This is Swagger 2.0. Most tooling now targets OpenAPI 3.x; convert with swagger2openapi or the Swagger Editor.")
        if (truthy(doc.js("openapi")) && (doc["servers"] as? List<*>).isNullOrEmpty()) add("warning", "No servers: clients and docs default to \"/\".")
        val ids = LinkedHashMap<String, Int>()
        for (op in ops) {
            val where = "${op.method} ${op.path}"
            if (op.operationId.isEmpty()) add("warning", "$where: no operationId (code generators invent names like ${operationName(op.method.lowercase(), op.path)}).")
            else ids[op.operationId] = (ids[op.operationId] ?: 0) + 1
            val declared = op.params.filter { it.`in` == "path" }.map { it.name }.toCollection(LinkedHashSet())
            for (m in Regex("\\{([^}]+)\\}").findAll(op.path)) if (m.groupValues[1] !in declared) add("error", "$where: path parameter {${m.groupValues[1]}} is not declared.")
            for (name in declared) if (!op.path.contains("{$name}")) add("error", "$where: declares path parameter \"$name\" that is not in the path.")
            if (op.responses.isEmpty()) add("error", "$where: no responses.")
            else if (op.responses.none { Regex("^2|default").containsMatchIn(it.status) }) add("warning", "$where: no success (2xx) response.")
            if (op.method in listOf("GET", "HEAD", "DELETE") && op.body != null) add("warning", "$where: a request body on ${op.method} is ignored by many clients and proxies.")
            if (op.method == "POST" && op.body == null && op.params.isEmpty()) add("info", "$where: POST without a body or parameters.")
        }
        for ((id, n) in ids) if (n > 1) add("error", "operationId \"$id\" is used $n times; it must be unique.")
        val text = Js.stringify(doc)
        val refs = Regex("\"\\\$ref\":\"([^\"]+)\"").findAll(text).map { it.groupValues[1] }.toCollection(LinkedHashSet())
        for (m in refs) if (m.startsWith("#/") && resolveRef(doc, m) === Undefined) add("error", "Unresolved \$ref $m.")
        val components = obj(doc["components"])
        val schemes = obj(components?.get("securitySchemes"))?.let { Js.keys(it) } ?: obj(doc["securityDefinitions"])?.let { Js.keys(it) } ?: emptyList()
        if (schemes.isEmpty()) add("info", "No security schemes defined; document how clients authenticate.")
        return issues
    }

    // ---- JSON Schema → TypeScript ------------------------------------------

    private fun ident(k: String) = if (k.matches(Regex("^[A-Za-z_$][\\w$]*$"))) k else Js.quote(k)

    fun typeName(s: String): String {
        val n = s.replace(Regex("[^A-Za-z0-9]+(.)?")) { m -> m.groupValues[1].uppercase() }.let { it.take(1).uppercase() + it.drop(1) }
        return if (n.isNotEmpty() && n[0].isDigit()) "T$n" else n.ifEmpty { "Unnamed" }
    }

    fun schemaToTs(schemaIn: Any?, indent: String = ""): String {
        val schema = obj(schemaIn) ?: return "unknown"
        (schema["\$ref"] as? String)?.let { return typeName(it.split("/").last()) }
        val nullable = schema["nullable"] == true
        fun wrap(t: String) = if (nullable) "$t | null" else t
        (schema["enum"] as? List<*>)?.let { values -> return wrap(values.joinToString(" | ") { Js.stringify(it) }) }
        if (schema.containsKey("const")) return Js.stringify(schema["const"])
        for (key in listOf("oneOf", "anyOf")) (schema[key] as? List<*>)?.let { return wrap(it.joinToString(" | ") { s -> schemaToTs(s, indent) }) }
        (schema["allOf"] as? List<*>)?.let { return wrap(it.joinToString(" & ") { s -> schemaToTs(s, indent) }) }
        val type = schema["type"]
        if (type is List<*>) return type.joinToString(" | ") { t -> schemaToTs(LinkedHashMap(schema).apply { put("type", t) }, indent) }
        when (type) {
            "string" -> return wrap(if (schema["format"] == "binary") "Blob" else "string")
            "integer", "number" -> return wrap("number")
            "boolean" -> return wrap("boolean")
            "null" -> return "null"
            "array" -> {
                val inner = schemaToTs(schema.js("items"), indent)
                return wrap(if (Regex("[|&]").containsMatchIn(inner)) "Array<$inner>" else "$inner[]")
            }
        }
        val additional = schema["additionalProperties"]
        if (type == "object" || isObj(schema["properties"]) || Js.truthy(additional)) {
            val props = obj(schema["properties"]) ?: emptyMap()
            val required = (schema["required"] as? List<*>)?.map { Js.string(it) }?.toSet() ?: emptySet()
            val lines = Js.entries(props).map { (k, v) ->
                val description = obj(v)?.get("description") as? String
                val doc = if (description != null) "$indent  /** ${description.replace("*/", "*\\/").split("\n")[0]} */\n" else ""
                "$doc$indent  ${ident(k)}${if (k in required) "" else "?"}: ${schemaToTs(v, "$indent  ")};"
            }.toMutableList()
            if (Js.truthy(additional) && props.isEmpty()) return wrap("Record<string, ${if (additional == true) "unknown" else schemaToTs(additional, indent)}>")
            if (Js.truthy(additional)) lines += "$indent  [key: string]: unknown;"
            return wrap(if (lines.isNotEmpty()) "{\n${lines.joinToString("\n")}\n$indent}" else "Record<string, unknown>")
        }
        return "unknown"
    }

    fun specTypes(doc: Map<String, Any?>): String {
        val blocks = Js.entries(specSchemas(doc)).map { (name, s) ->
            val ts = schemaToTs(s)
            val description = obj(s)?.get("description") as? String
            val desc = if (description != null) "/** ${description.split("\n")[0].replace("*/", "")} */\n" else ""
            if (ts.startsWith("{")) "${desc}export interface ${typeName(name)} $ts" else "${desc}export type ${typeName(name)} = $ts;"
        }
        if (blocks.isEmpty()) throw ToolInputError("The document has no components.schemas (or definitions) to convert.")
        return blocks.joinToString("\n\n") + "\n"
    }

    private fun serverUrl(doc: Map<String, Any?>, fallback: String): String {
        val servers = doc["servers"] as? List<*>
        val first = servers?.firstOrNull()
        if (servers != null && first is Map<*, *>) return jsStr((first as Map<String, Any?>).js("url"))
        if (truthy(doc.js("host"))) return "https://${Js.string(doc["host"])}${doc.js("basePath").orElse("").let { jsStr(it) }}"
        return fallback
    }

    fun specClient(doc: Map<String, Any?>, ops: List<Operation>): String {
        val servers = serverUrl(doc, "")
        val fns = ops.map { op ->
            val name = if (op.operationId.isNotEmpty()) op.operationId.replace(Regex("[^A-Za-z0-9_$]"), "_") else operationName(op.method.lowercase(), op.path)
            val pathParams = op.params.filter { it.`in` == "path" }
            val query = op.params.filter { it.`in` == "query" }
            val args = pathParams.map { "${it.name.replace(Regex("[^A-Za-z0-9_$]"), "_")}: ${schemaToTs(it.schema)}" }.toMutableList()
            if (query.isNotEmpty()) args += "query${if (query.all { !it.required }) "?" else ""}: { ${query.joinToString("; ") { q -> "${ident(q.name)}${if (q.required) "" else "?"}: ${schemaToTs(q.schema)}" }} }"
            op.body?.let { args += "body${if (it.required) "" else "?"}: ${schemaToTs(it.schema)}" }
            args += "init?: RequestInit"
            val ok = op.responses.firstOrNull { it.status.startsWith("2") } ?: op.responses.firstOrNull { it.status == "default" }
            val ret = when {
                ok?.schema != null -> schemaToTs(ok.schema, "  ")
                ok != null && ok.status == "204" -> "void"
                else -> "unknown"
            }
            val urlPath = op.path.replace(Regex("\\{([^}]+)\\}")) { m -> "$D{encodeURIComponent(String(${m.groupValues[1].replace(Regex("[^A-Za-z0-9_$]"), "_")}))}" }
            val comment = "/** ${op.method} ${op.path}${if (op.summary.isNotEmpty()) " - ${op.summary.replace("*/", "")}" else ""}${if (op.deprecated) " (deprecated)" else ""} */"
            "$comment\nexport function $name(${args.joinToString(", ")}): Promise<$ret> {\n  return request<$ret>(${Js.quote(op.method)}, `$urlPath`${if (query.isNotEmpty()) ", query" else ", undefined"}${if (op.body != null) ", body" else ", undefined"}, init);\n}"
        }
        val types = try {
            specTypes(doc) + "\n"
        } catch (_: ToolInputError) {
            ""
        }
        return types +
            "// ---- Client -------------------------------------------------------------\n\n" +
            "export const BASE_URL = ${Js.quote(servers)};\n\n" +
            "export class ApiError extends Error {\n  constructor(public status: number, public body: unknown) {\n    super(`HTTP $D{status}`);\n  }\n}\n\n" +
            "async function request<T>(method: string, path: string, query?: Record<string, unknown>, body?: unknown, init?: RequestInit): Promise<T> {\n" +
            "  const url = new URL(BASE_URL + path, typeof window === \"undefined\" ? \"http://localhost\" : window.location.origin);\n" +
            "  for (const [key, value] of Object.entries(query ?? {})) {\n" +
            "    if (value === undefined || value === null) continue;\n" +
            "    for (const v of Array.isArray(value) ? value : [value]) url.searchParams.append(key, String(v));\n  }\n" +
            "  const response = await fetch(url, {\n    ...init,\n    method,\n" +
            "    headers: { Accept: \"application/json\", ...(body !== undefined ? { \"Content-Type\": \"application/json\" } : {}), ...init?.headers },\n" +
            "    body: body !== undefined ? JSON.stringify(body) : undefined,\n  });\n" +
            "  const text = await response.text();\n" +
            "  const data = text ? (response.headers.get(\"content-type\")?.includes(\"json\") ? JSON.parse(text) : text) : undefined;\n" +
            "  if (!response.ok) throw new ApiError(response.status, data);\n  return data as T;\n}\n\n" +
            fns.joinToString("\n\n") + "\n"
    }

    fun exampleFromSchema(doc: Map<String, Any?>, schemaIn: Any?, depth: Int = 0): Any? {
        if (depth > 6) return null
        val schema = obj(schemaIn) ?: return null
        (schema["\$ref"] as? String)?.let { return exampleFromSchema(doc, resolveRef(doc, it), depth + 1) }
        if (schema.containsKey("example")) return schema["example"]
        (schema["examples"] as? List<*>)?.let { if (it.isNotEmpty()) return it[0] }
        if (schema.containsKey("default")) return schema["default"]
        (schema["enum"] as? List<*>)?.let { return it.firstOrNull() }
        for (key in listOf("oneOf", "anyOf")) (schema[key] as? List<*>)?.let { return exampleFromSchema(doc, it.firstOrNull(), depth + 1) }
        (schema["allOf"] as? List<*>)?.let { parts ->
            val merged = LinkedHashMap<String, Any?>()
            parts.map { exampleFromSchema(doc, it, depth + 1) }.filterIsInstance<Map<String, Any?>>().forEach { merged.putAll(it) }
            return merged
        }
        val type = (schema["type"] as? List<*>)?.firstOrNull { it != "null" } ?: schema["type"]
        when (type) {
            "string" -> return mapOf(
                "date-time" to "2026-01-15T09:30:00Z", "date" to "2026-01-15", "email" to "user@example.com", "uuid" to "3fa85f64-5717-4562-b3fc-2c963f66afa6",
                "uri" to "https://example.com", "url" to "https://example.com", "binary" to "<file>",
            )[jsStr(schema.js("format"))] ?: "string"
            "integer", "number" -> return 0.0
            "boolean" -> return true
            "array" -> return listOf(exampleFromSchema(doc, schema["items"], depth + 1))
        }
        obj(schema["properties"])?.let { props -> return LinkedHashMap<String, Any?>().apply { for ((k, v) in Js.entries(props)) put(k, exampleFromSchema(doc, v, depth + 1)) } }
        return LinkedHashMap<String, Any?>()
    }

    fun curlExamples(doc: Map<String, Any?>, ops: List<Operation>): String {
        val servers = serverUrl(doc, "https://api.example.com")
        fun shq(s: String) = "'" + s.replace("'", "'\\''") + "'"
        return ops.joinToString("\n\n") { op ->
            val q = op.params.filter { it.`in` == "query" && it.required }.map { "${it.name}=${Js.encodeURIComponent(Js.string(exampleFromSchema(doc, it.schema) ?: ""))}" }
            val headerParams = op.params.filter { it.`in` == "header" }
            val lines = mutableListOf(
                "# ${op.method} ${op.path}${if (op.summary.isNotEmpty()) " - ${op.summary}" else ""}",
                "curl -X ${op.method} ${shq("$servers${op.path}${if (q.isNotEmpty()) "?${q.joinToString("&")}" else ""}")}",
            )
            lines += "  -H 'Accept: application/json'"
            if (op.secured) lines += "  -H \"Authorization: Bearer ${D}TOKEN\""
            for (h in headerParams) lines += "  -H ${shq("${h.name}: ${Js.string(exampleFromSchema(doc, h.schema) ?: "")}")}"
            op.body?.let { lines += listOf("  -H ${shq("Content-Type: ${it.contentType}")}", "  --data-raw ${shq(Js.stringify(exampleFromSchema(doc, it.schema), "  "))}") }
            lines.joinToString(" \\\n")
        } + "\n"
    }

    fun fromSample(method: String, path: String, requestJson: String, responseJson: String, status: String, title: String): String {
        fun parse(t: String, what: String): Any? = try {
            Js.parseJson(t)
        } catch (e: Js.JsonSyntaxError) {
            throw ToolInputError("$what is not valid JSON: ${e.message}")
        }
        fun clean(s: Map<String, Any?>) = LinkedHashMap(s).apply { remove("\$schema"); remove("title") }
        val pathParams = Regex("\\{([^}]+)\\}|:(\\w+)").findAll(path).map { it.groupValues[1].ifEmpty { it.groupValues[2] } }.toList()
        val normPath = path.replace(Regex(":(\\w+)"), "{$1}")
        val op = LinkedHashMap<String, Any?>()
        op["operationId"] = operationName(method.lowercase(), normPath)
        op["summary"] = "${method.uppercase()} $normPath"
        if (pathParams.isNotEmpty()) op["parameters"] = pathParams.map { mapOf("name" to it, "in" to "path", "required" to true, "schema" to mapOf("type" to "string")) }
        if (requestJson.trim().isNotEmpty()) {
            val schema = clean(Shapes.toJsonSchema(Shapes.recordShape(parse(requestJson, "The request")), "Request"))
            op["requestBody"] = mapOf("required" to true, "content" to mapOf("application/json" to mapOf("schema" to schema, "example" to parse(requestJson, "The request"))))
        }
        val code = status.ifEmpty { "200" }
        val response = LinkedHashMap<String, Any?>()
        response["description"] = "Success"
        if (responseJson.trim().isNotEmpty()) {
            val parsed = parse(responseJson, "The response")
            var schema: Map<String, Any?> = clean(Shapes.toJsonSchema(Shapes.recordShape(parsed), "Response"))
            if (parsed is List<*>) schema = linkedMapOf("type" to "array", "items" to schema)
            response["content"] = mapOf("application/json" to linkedMapOf("schema" to schema, "example" to parsed))
        }
        op["responses"] = mapOf(code to response)
        val doc = linkedMapOf(
            "openapi" to "3.1.0",
            "info" to linkedMapOf("title" to title.ifEmpty { "API" }, "version" to "1.0.0"),
            "servers" to listOf(mapOf("url" to "https://api.example.com")),
            "paths" to mapOf(normPath to mapOf(method.lowercase() to op)),
        )
        return Yaml.stringify(doc)
    }
}

/** Type inference from example JSON. Port of `recordShape` and `toJsonSchema` (data-types.ts). */
object Shapes {
    sealed class Shape {
        class Str(val formats: LinkedHashSet<String>) : Shape()
        object Integer : Shape()
        object Num : Shape()
        object Bool : Shape()
        object Null : Shape()
        object Unknown : Shape()
        class Arr(val item: Shape) : Shape()
        class Obj(val fields: LinkedHashMap<String, Pair<Shape, Int>>, val samples: Int) : Shape()
        class Union(val options: List<Shape>) : Shape()
    }

    private fun stringFormat(value: String): String = when {
        Regex("^\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}(:\\d{2}(\\.\\d+)?)?(Z|[+-]\\d{2}:?\\d{2})?$").matches(value) -> "date-time"
        Regex("^\\d{4}-\\d{2}-\\d{2}$").matches(value) -> "date"
        Regex("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$").matches(value) -> "email"
        Regex("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$", RegexOption.IGNORE_CASE).matches(value) -> "uuid"
        Regex("^https?://\\S+$").matches(value) -> "uri"
        else -> "text"
    }

    fun shapeOf(value: Any?): Shape = when (value) {
        null -> Shape.Null
        is String -> Shape.Str(linkedSetOf(stringFormat(value)))
        is Number -> if (value.toDouble() == Math.floor(value.toDouble()) && value.toDouble().isFinite()) Shape.Integer else Shape.Num
        is Boolean -> Shape.Bool
        is List<*> -> Shape.Arr(if (value.isNotEmpty()) value.map(::shapeOf).reduce(::merge) else Shape.Unknown)
        is Map<*, *> -> {
            val fields = LinkedHashMap<String, Pair<Shape, Int>>()
            @Suppress("UNCHECKED_CAST")
            for ((key, v) in Js.entries(value as Map<String, Any?>)) fields[key] = shapeOf(v) to 1
            Shape.Obj(fields, 1)
        }
        else -> Shape.Unknown
    }

    private fun kind(s: Shape) = s.javaClass.simpleName

    private fun compatible(a: Shape, b: Shape): Boolean =
        kind(a) == kind(b) || (a is Shape.Integer && b is Shape.Num) || (a is Shape.Num && b is Shape.Integer)

    fun merge(a: Shape, b: Shape): Shape {
        if (a is Shape.Unknown) return b
        if (b is Shape.Unknown) return a
        if (a is Shape.Union || b is Shape.Union) {
            val options = (if (a is Shape.Union) a.options else listOf(a)).toMutableList()
            for (option in if (b is Shape.Union) b.options else listOf(b)) {
                val index = options.indexOfFirst { compatible(it, option) }
                if (index >= 0) options[index] = merge(options[index], option) else options += option
            }
            return if (options.size == 1) options[0] else Shape.Union(options)
        }
        if ((a is Shape.Integer && b is Shape.Num) || (a is Shape.Num && b is Shape.Integer)) return Shape.Num
        if (kind(a) != kind(b)) return Shape.Union(listOf(a, b))
        return when (a) {
            is Shape.Str -> Shape.Str(LinkedHashSet(a.formats).apply { addAll((b as Shape.Str).formats) })
            is Shape.Arr -> Shape.Arr(merge(a.item, (b as Shape.Arr).item))
            is Shape.Obj -> {
                val other = b as Shape.Obj
                val fields = LinkedHashMap(a.fields)
                for ((key, f) in other.fields) {
                    val existing = fields[key]
                    fields[key] = if (existing != null) merge(existing.first, f.first) to existing.second + f.second else f
                }
                Shape.Obj(fields, a.samples + other.samples)
            }
            else -> a
        }
    }

    /** The shape of one record: for an array input, all elements merged. */
    fun recordShape(value: Any?): Shape {
        if (value is List<*>) {
            if (value.isEmpty()) throw ToolInputError("The array is empty; add at least one example element.")
            return value.map(::shapeOf).reduce(::merge)
        }
        return shapeOf(value)
    }

    fun toJsonSchema(root: Shape, title: String): Map<String, Any?> {
        fun schema(s: Shape): Map<String, Any?> = when (s) {
            is Shape.Str -> {
                val f = s.formats.filter { it != "text" }
                if (f.size == 1 && s.formats.size == 1) linkedMapOf("type" to "string", "format" to f[0]) else linkedMapOf("type" to "string")
            }
            Shape.Integer -> linkedMapOf("type" to "integer")
            Shape.Num -> linkedMapOf("type" to "number")
            Shape.Bool -> linkedMapOf("type" to "boolean")
            Shape.Null -> linkedMapOf("type" to "null")
            Shape.Unknown -> linkedMapOf()
            is Shape.Arr -> linkedMapOf("type" to "array", "items" to schema(s.item))
            is Shape.Obj -> {
                val properties = LinkedHashMap<String, Any?>()
                val required = mutableListOf<String>()
                for ((key, f) in s.fields) {
                    properties[key] = schema(f.first)
                    if (f.second == s.samples) required += key
                }
                linkedMapOf<String, Any?>("type" to "object", "properties" to properties).apply {
                    if (required.isNotEmpty()) put("required", required)
                    put("additionalProperties", false)
                }
            }
            is Shape.Union -> {
                val types = s.options.map(::schema)
                val simple = types.all { it.size == 1 && it["type"] is String }
                if (simple) linkedMapOf("type" to types.map { it["type"] }) else linkedMapOf("anyOf" to types)
            }
        }
        return linkedMapOf<String, Any?>("\$schema" to "https://json-schema.org/draft/2020-12/schema", "title" to title).apply { putAll(schema(root)) }
    }
}
