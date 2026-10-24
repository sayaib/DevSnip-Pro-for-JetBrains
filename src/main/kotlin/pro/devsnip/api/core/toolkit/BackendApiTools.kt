package pro.devsnip.api.core.toolkit

import pro.devsnip.api.core.toolkit.engines.ApiResponseEngine
import pro.devsnip.api.core.toolkit.engines.CorsEngine
import pro.devsnip.api.core.toolkit.engines.CurlEngine
import pro.devsnip.api.core.toolkit.engines.GraphqlEngine
import pro.devsnip.api.core.toolkit.engines.OpenApiEngine
import pro.devsnip.api.core.toolkit.engines.ScaffoldEngine
import pro.devsnip.api.core.toolkit.engines.UrlEngine

/**
 * The "Backend & API" section of the DevSnip Pro sidebar (after the REST API
 * Client). Each `run` is a port of the matching tool in the VS Code
 * extension's `src/toolkits/sections/web.ts`; metadata comes from
 * `toolkit/backend-api.json`, exported from the same source.
 */
object BackendApiTools {
    private const val D = "$"

    val all: List<ToolDefinition> by lazy {
        val runners: Map<String, (Values) -> ToolResult> = mapOf(
            "web.curl" to ::curl,
            "web.api-response" to ::apiResponse,
            "web.url" to ::url,
            "web.cors" to ::cors,
            "web.openapi" to ::openApi,
            "web.graphql" to ::graphql,
            "web.api-scaffold" to ScaffoldEngine::run,
        )
        ToolCatalog.load("/toolkit/backend-api.json").map { ToolDefinition(it, runners.getValue(it.id)) }
    }

    fun byId(id: String) = all.first { it.id == id }

    private fun info(text: String) = Message(MessageKind.INFO, text)
    private fun warning(text: String) = Message(MessageKind.WARNING, text)
    private fun error(text: String) = Message(MessageKind.ERROR, text)

    // ------------------------------------------------------------- cURL Converter

    private fun curl(values: Values): ToolResult {
        val r = CurlEngine.parseCurl(required(values, "curl", "cURL command"))
        val target = str(values, "target", "fetch")
        val targets = if (bool(values, "all")) CurlEngine.TARGETS else CurlEngine.TARGETS.filter { it.id == target }
        val bodyLabel = when (r.bodyKind) {
            "none" -> "none"
            "multipart" -> "multipart (${r.form.size} parts)"
            else -> "${r.bodyKind} \u00B7 ${fmtBytes((r.body ?: "").toByteArray(Charsets.UTF_8).size.toLong())}"
        }
        val u = JsUrl.parse(r.url)
        val requestRows = mutableListOf<List<Any>>(listOf("URL", r.url))
        r.headers.forEach { (k, v) ->
            requestRows += listOf("Header: $k", if (k.matches(Regex("^(authorization|cookie|x-api-key)$", RegexOption.IGNORE_CASE))) "${v.take(12)}\u2026" else v)
        }
        r.basicAuth?.let { requestRows += listOf("Basic auth user", it.user) }
        return ToolResult(
            stats = listOf(Stat("Method", r.method), Stat("Host", u.host), Stat("Headers", r.headers.size.toString()), Stat("Body", bodyLabel)),
            messages = CurlEngine.warnings(r).map(::warning),
            outputs = targets.map { code(it.label, it.language, CurlEngine.generate(r, it.id), it.file) } +
                table("Request", listOf("Part", "Value"), requestRows) +
                code("Normalised cURL", "shell", CurlEngine.toCurl(r)),
        )
    }

    // ----------------------------------------------------- API Response Inspector

    private fun apiResponse(values: Values): ToolResult {
        val r = ApiResponseEngine.inspect(str(values, "raw"))
        val stats = mutableListOf<Stat>()
        if (r.status != null && r.status != 0) {
            val tone = if (r.status < 300) Tone.GOOD else if (r.status < 400) Tone.NEUTRAL else Tone.BAD
            stats += Stat("Status", "${r.status}${if (!r.statusText.isNullOrEmpty()) " ${r.statusText}" else ""}", tone)
        }
        stats += Stat("Body", "${if (r.isJson) "JSON" else "text"} \u00B7 ${fmtBytes(r.bytes)}")
        stats += Stat("Headers", r.headers.size.toString())
        val outputs = mutableListOf<Output>(code("Body", if (r.isJson) "json" else "text", if (r.isJson) Js.stringify(r.body, "  ") else r.bodyText))
        if (r.headers.isNotEmpty()) outputs += table("Headers", listOf("Header", "Value"), r.headers.map { listOf(it.first, it.second) })
        if (r.fields.isNotEmpty()) outputs += table("Fields", listOf("Path", "Type", "Example"), r.fields.map { listOf(it.path, it.type, it.example) })
        return ToolResult(stats, r.insights.map(::info), outputs)
    }

    // --------------------------------------------------- URL & Query String Tool

    private fun url(values: Values): ToolResult {
        when (str(values, "mode", "parse")) {
            "build" -> {
                val url = UrlEngine.buildUrl(required(values, "base", "Base URL"), str(values, "params"))
                val params = JsUrl.parse(url).searchParams.map { (k, v) -> listOf(k, v, "${Js.encodeURIComponent(k)}=${Js.encodeURIComponent(v)}") }
                return ToolResult(stats = listOf(Stat("Length", "${url.length} chars")), outputs = listOf(code("URL", "text", url), table("Parameters", listOf("Key", "Value", "Encoded"), params)))
            }
            "qs-json" -> {
                val obj = UrlEngine.queryToObject(required(values, "query", "Query string"))
                val flat = Js.quote(str(values, "query").replace(Regex("^.*?\\?"), ""))
                return ToolResult(
                    outputs = listOf(
                        code("JSON", "json", Js.stringify(obj, "  ")),
                        code("URLSearchParams (flat)", "javascript", "const params = new URLSearchParams($flat);\nconsole.log(Object.fromEntries(params)); // repeated keys keep only the last value\nconsole.log(params.getAll(\"fields[]\"));\n"),
                    ),
                )
            }
            "json-qs" -> {
                val parsed = try {
                    Js.parseJson(required(values, "json", "JSON"))
                } catch (e: Js.JsonSyntaxError) {
                    throw ToolInputError("Not valid JSON: ${e.message}")
                }
                val format = str(values, "arrayFormat", "brackets")
                val qs = UrlEngine.objectToQuery(parsed, format)
                return ToolResult(
                    outputs = listOf(
                        code("Query string", "text", qs),
                        code("qs library", "javascript", "import qs from \"qs\";\n\nconst query = qs.stringify(${Js.stringify(parsed)}, { arrayFormat: ${Js.quote(format)}, encodeValuesOnly: true });\n"),
                    ),
                )
            }
        }
        val r = UrlEngine.parseUrlReport(required(values, "url", "URL"))
        val outputs = mutableListOf<Output>(table("Parts", listOf("Part", "Value"), r.parts))
        if (r.params.isNotEmpty()) {
            outputs += table("Query parameters", listOf("Key", "Decoded value", "Raw"), r.params)
            val query = r.params.joinToString("&") { "${Js.encodeURIComponent(it[0])}=${Js.encodeURIComponent(it[1])}" }
            outputs += code("As JSON", "json", Js.stringify(UrlEngine.queryToObject(query), "  "))
        }
        return ToolResult(
            stats = listOf(Stat("Parameters", r.params.size.toString()), Stat("Warnings", r.warnings.size.toString(), if (r.warnings.isNotEmpty()) Tone.WARN else Tone.GOOD)),
            messages = r.warnings.map(::warning),
            outputs = outputs,
        )
    }

    // ---------------------------------------------------- CORS Builder & Debugger

    private fun cors(values: Values): ToolResult {
        when (str(values, "mode", "build")) {
            "explain" -> {
                val found = CorsEngine.explainError(required(values, "error", "Console error"))
                if (found.isEmpty()) {
                    return ToolResult(messages = listOf(info("No known CORS pattern found. Open DevTools \u2192 Network, select the failing request (and its OPTIONS preflight) and check the status and Access-Control-* response headers - then use \"Check response headers\".")))
                }
                return ToolResult(messages = found.map { warning(it.first) }, outputs = listOf(table("Cause and fix", listOf("Problem", "Fix"), found.map { listOf(it.first, it.second) })))
            }
            "check" -> {
                val v = CorsEngine.check(
                    origin = required(values, "origin", "Page origin"),
                    methodIn = str(values, "method", "GET"),
                    requestHeaders = str(values, "requestHeaders").split(",").map { it.trim() }.filter { it.isNotEmpty() },
                    credentials = bool(values, "credentials"),
                    responseHeaders = str(values, "response"),
                    preflightStatus = num(values, "preflightStatus", 204.0, min = 100.0, max = 599.0, integer = true),
                )
                return ToolResult(
                    stats = listOf(Stat("Result", if (v.passed) "allowed" else "BLOCKED", if (v.passed) Tone.GOOD else Tone.BAD), Stat("Preflight", if (v.preflightNeeded) "yes" else "no")),
                    messages = v.steps.map { Message(if (it.ok) MessageKind.SUCCESS else MessageKind.ERROR, it.text) },
                    outputs = emptyList(),
                )
            }
        }
        val origins = list(str(values, "origins"))
        if (origins.isEmpty()) throw ToolInputError("Add at least one allowed origin.")
        val credentials = bool(values, "credentials", true)
        val messages = mutableListOf<Message>()
        if ("*" in origins && credentials) messages += error("* cannot be combined with credentials. List the exact origins instead.")
        for (o in origins) if (o != "*" && !o.matches(Regex("^https?://[^/]+$"))) messages += warning("\"$o\" is not an origin (scheme://host[:port], no path or trailing slash).")
        if (origins.any { it.startsWith("http://") && !Regex("localhost|127\\.0\\.0\\.1").containsMatchIn(it) }) messages += warning("Production origins should be https.")
        val c = CorsEngine.configs(
            CorsEngine.CorsOptions(
                origins = origins,
                methods = list(str(values, "methods")).map { it.uppercase() },
                headers = list(str(values, "headers")),
                exposeHeaders = list(str(values, "expose")),
                credentials = credentials,
                maxAge = num(values, "maxAge", 600.0, min = 0.0, max = 86400.0, integer = true),
            ),
        )
        messages += info("Return the CORS headers on error responses too (401, 404, 500) - otherwise the browser reports a CORS error instead of the real one.")
        return ToolResult(
            messages = messages,
            outputs = listOf(
                code("Express (cors)", "typescript", c.getValue("express")),
                code("NestJS", "typescript", c.getValue("nest")),
                code("Next.js middleware", "typescript", c.getValue("next"), "middleware.ts"),
                code("Fastify", "typescript", c.getValue("fastify")),
                code("nginx", "nginx", c.getValue("nginx")),
                code("FastAPI", "python", c.getValue("fastapi")),
            ),
        )
    }

    // -------------------------------------------------- OpenAPI / Swagger Toolkit

    private fun openApi(values: Values): ToolResult {
        val mode = str(values, "mode", "endpoints")
        if (mode == "from-sample") {
            val yaml = OpenApiEngine.fromSample(
                str(values, "method", "POST"), required(values, "path", "Path"), str(values, "requestJson"), str(values, "responseJson"), str(values, "status", "200").trim(), "API",
            )
            return ToolResult(
                messages = listOf(info("Inferred from one example: review required fields, formats and descriptions before publishing.")),
                outputs = listOf(code("openapi.yaml", "yaml", yaml, "openapi.yaml")),
            )
        }
        val doc = OpenApiEngine.parseSpec(required(values, "spec", "OpenAPI document"))
        val ops = OpenApiEngine.operations(doc)
        @Suppress("UNCHECKED_CAST")
        val info = doc["info"] as? Map<String, Any?> ?: emptyMap()
        fun jsOr(key: String, map: Map<String, Any?>, fallback: String) = if (map[key] == null) fallback else Js.string(map[key])
        val spec = if (Js.truthy(doc["openapi"])) "OpenAPI ${Js.string(doc["openapi"])}" else "Swagger ${if (doc.containsKey("swagger")) Js.string(doc["swagger"]) else "undefined"}"
        val stats = listOf(Stat("Spec", spec), Stat("API", "${jsOr("title", info, "?")} ${jsOr("version", info, "")}".trim()), Stat("Operations", ops.size.toString()))
        when (mode) {
            "types" -> return ToolResult(stats = stats, outputs = listOf(code("Types", "typescript", OpenApiEngine.specTypes(doc), "api-types.ts")))
            "client" -> return ToolResult(stats = stats, outputs = listOf(code("Client", "typescript", OpenApiEngine.specClient(doc, ops), "api-client.ts")))
            "curl" -> return ToolResult(stats = stats, outputs = listOf(code("cURL", "shell", OpenApiEngine.curlExamples(doc, ops))))
        }
        val issues = OpenApiEngine.lint(doc, ops)
        val tone = if (issues.any { it.severity == "error" }) Tone.BAD else if (issues.isNotEmpty()) Tone.WARN else Tone.GOOD
        return ToolResult(
            stats = stats + Stat("Problems", issues.count { it.severity != "info" }.toString(), tone),
            messages = severityMessages(issues, "No problems found."),
            outputs = listOf(
                table(
                    "Endpoints",
                    listOf("Method", "Path", "operationId", "Summary", "Auth", "Responses"),
                    ops.map { o ->
                        listOf(o.method, o.path, o.operationId.ifEmpty { "-" }, "${o.summary}${if (o.deprecated) " (deprecated)" else ""}", if (o.secured) "yes" else "no", o.responses.joinToString(", ") { it.status })
                    },
                ),
            ),
        )
    }

    // -------------------------------------------------- GraphQL Formatter & Types

    private fun graphql(values: Values): ToolResult {
        if (str(values, "mode", "query") == "sdl") {
            val types = GraphqlEngine.sdlToTypeScript(required(values, "sdl", "Schema"))
            return ToolResult(
                messages = listOf(info("Nullable GraphQL fields become T | null; input fields that are nullable are optional. For full typed operations use GraphQL Code Generator.")),
                outputs = listOf(code("TypeScript", "typescript", types, "graphql-types.ts")),
            )
        }
        val query = required(values, "query", "Query")
        val formatted = GraphqlEngine.format(query)
        val (ops, fragments) = GraphqlEngine.operations(query)
        val op = ops.firstOrNull()
        val variables = LinkedHashMap<String, Any?>()
        op?.variables?.forEach { v ->
            variables[v.name] = v.defaultValue?.let { dv -> Js.toNumber(dv).takeIf { it.isFinite() } ?: dv.replace(Regex("^\"|\"$"), "") } ?: GraphqlEngine.sampleFor(v.type)
        }
        val minified = GraphqlEngine.minify(query)
        val body = LinkedHashMap<String, Any?>()
        body["query"] = minified
        if (op != null && op.variables.isNotEmpty()) body["variables"] = variables
        if (op != null && op.name.isNotEmpty() && ops.size > 1) body["operationName"] = op.name
        val endpoint = str(values, "endpoint", "https://api.example.com/graphql")
        val messages = mutableListOf<Message>()
        if (ops.size > 1 && ops.any { it.name.isEmpty() }) messages += error("A document with several operations must name each one, and the request must send operationName.")
        if (op != null && op.name.isEmpty()) messages += info("Name your operations (query GetUser { \u2026 }): server logs, APQ and Apollo DevTools use the name.")
        val usedFragments = Regex("\\.\\.\\.\\s*([_A-Za-z]\\w*)").findAll(query).map { it.groupValues[1] }.filter { it != "on" }.toCollection(LinkedHashSet())
        for (name in usedFragments) if (name !in fragments) messages += error("Fragment $name is used but not defined in this document.")
        for (name in fragments) if (name !in usedFragments) messages += warning("Fragment $name is defined but never used (servers reject unused fragments).")
        val outputs = mutableListOf<Output>(code("Formatted", "graphql", formatted))
        if (op != null && op.variables.isNotEmpty()) outputs += table("Variables", listOf("Name", "Type", "Default"), op.variables.map { listOf("$D${it.name}", it.type, it.defaultValue ?: "") })
        outputs += code("Request body (JSON)", "json", Js.stringify(body, "  "))
        outputs += code("cURL", "shell", "curl $endpoint \\\n  -H 'Content-Type: application/json' \\\n  -H \"Authorization: Bearer ${D}TOKEN\" \\\n  --data-raw '${Js.stringify(body).replace("'", "'\\''")}'")
        val templated = formatted.trim().replace("`", "\\`").replace("$D{", "\\$D{").split("\n").joinToString("\n    ")
        outputs += code(
            "fetch", "javascript",
            "const response = await fetch(${Js.quote(endpoint)}, {\n  method: \"POST\",\n  headers: { \"Content-Type\": \"application/json\" },\n  body: JSON.stringify({\n" +
                "    query: `$templated`,\n    variables: ${Js.stringify(variables)},\n  }),\n});\nconst { data, errors } = await response.json();\n" +
                "// GraphQL returns 200 even for errors: always check the errors array.\nif (errors?.length) throw new Error(errors.map(e => e.message).join(\"; \"));\nconsole.log(data);\n",
        )
        outputs += code("Minified", "graphql", minified)
        return ToolResult(
            stats = listOf(
                Stat("Operations", ops.joinToString(", ") { "${it.type} ${it.name.ifEmpty { "(anonymous)" }}" }.ifEmpty { "none" }),
                Stat("Variables", (op?.variables?.size ?: 0).toString()),
                Stat("Size", "${query.length} \u2192 ${minified.length} chars"),
            ),
            messages = messages,
            outputs = outputs,
        )
    }
}
