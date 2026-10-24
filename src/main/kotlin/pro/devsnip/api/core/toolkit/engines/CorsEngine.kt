package pro.devsnip.api.core.toolkit.engines

import pro.devsnip.api.core.toolkit.Js

/**
 * CORS: config generation, a browser-accurate check of response headers and
 * an explainer for console errors. Port of the CORS part of
 * `src/toolkits/engines/web-security.ts`.
 */
object CorsEngine {
    private const val D = "$"

    data class CorsOptions(
        val origins: List<String>,
        val methods: List<String>,
        val headers: List<String>,
        val exposeHeaders: List<String>,
        val credentials: Boolean,
        val maxAge: Double,
    )

    data class Step(val ok: Boolean, val text: String)
    data class Verdict(val preflightNeeded: Boolean, val passed: Boolean, val steps: List<Step>)

    private fun js(s: String) = Js.quote(s)
    private fun js(list: List<String>) = Js.stringify(list)

    /** An origin pattern with a * wildcard for subdomains → RegExp source. */
    fun originRegex(pattern: String): String =
        "^" + pattern.replace(Regex("[.+?^${D}{}()|\\[\\]\\\\/]")) { "\\" + it.value }.replace("*", "[a-z0-9-]+") + "$"

    fun configs(o: CorsOptions): Map<String, String> {
        val any = "*" in o.origins
        val wild = o.origins.filter { it.contains("*") && it != "*" }
        val exact = o.origins.filter { !it.contains("*") }
        val originList = "[${exact.joinToString(", ") { js(it) }}]"
        val regexes = wild.map { "/${originRegex(it)}/" }
        val maxAge = Js.number(o.maxAge)
        val allowFn = if (any && !o.credentials) {
            "const isAllowedOrigin = (_origin: string) => true;"
        } else {
            "const ALLOWED_ORIGINS = $originList;\n" +
                (if (regexes.isNotEmpty()) "const ALLOWED_PATTERNS = [${regexes.joinToString(", ")}];\n" else "") +
                "const isAllowedOrigin = (origin: string) =>\n  ALLOWED_ORIGINS.includes(origin)" +
                (if (regexes.isNotEmpty()) " || ALLOWED_PATTERNS.some(p => p.test(origin))" else "") + ";"
        }
        val methods = o.methods.joinToString(",")
        val exposeLine = { indent: String, key: String -> if (o.exposeHeaders.isNotEmpty()) "$indent$key: ${js(o.exposeHeaders)},\n" else "" }

        val express = "import cors from \"cors\";\nimport express from \"express\";\n\n$allowFn\n\nconst app = express();\napp.use(cors({\n" +
            "  origin: (origin, callback) => {\n" +
            "    // Requests without an Origin header (curl, mobile apps, server-to-server) are not CORS requests.\n" +
            "    if (!origin || isAllowedOrigin(origin)) return callback(null, true);\n" +
            "    callback(new Error(`Origin $D{origin} is not allowed by CORS`));\n  },\n" +
            "  methods: ${js(o.methods)},\n  allowedHeaders: ${js(o.headers)},\n${exposeLine("  ", "exposedHeaders")}" +
            "  credentials: ${o.credentials},\n  maxAge: $maxAge,\n}));\n"

        val nest = "// main.ts\nimport { NestFactory } from \"@nestjs/core\";\nimport { AppModule } from \"./app.module\";\n\n$allowFn\n\n" +
            "async function bootstrap() {\n  const app = await NestFactory.create(AppModule);\n  app.enableCors({\n" +
            "    origin: (origin: string | undefined, callback: (err: Error | null, allow?: boolean) => void) => {\n" +
            "      if (!origin || isAllowedOrigin(origin)) return callback(null, true);\n" +
            "      callback(new Error(`Origin $D{origin} is not allowed by CORS`));\n    },\n" +
            "    methods: ${js(o.methods)},\n    allowedHeaders: ${js(o.headers)},\n${exposeLine("    ", "exposedHeaders")}" +
            "    credentials: ${o.credentials},\n    maxAge: $maxAge,\n  });\n  await app.listen(process.env.PORT ?? 3000);\n}\nvoid bootstrap();\n"

        val next = "// middleware.ts (project root) - CORS for /api routes in the Next.js App Router\nimport { NextRequest, NextResponse } from \"next/server\";\n\n$allowFn\n\n" +
            "const CORS_HEADERS: Record<string, string> = {\n" +
            "  \"Access-Control-Allow-Methods\": ${js(methods)},\n" +
            "  \"Access-Control-Allow-Headers\": ${js(o.headers.joinToString(","))},\n" +
            (if (o.exposeHeaders.isNotEmpty()) "  \"Access-Control-Expose-Headers\": ${js(o.exposeHeaders.joinToString(","))},\n" else "") +
            (if (o.credentials) "  \"Access-Control-Allow-Credentials\": \"true\",\n" else "") +
            "  \"Access-Control-Max-Age\": ${js(maxAge)},\n};\n\n" +
            "export function middleware(request: NextRequest) {\n  const origin = request.headers.get(\"origin\") ?? \"\";\n  const allowed = origin !== \"\" && isAllowedOrigin(origin);\n\n" +
            "  if (request.method === \"OPTIONS\") {\n    // Preflight: answer directly with 204.\n    return new NextResponse(null, {\n      status: 204,\n" +
            "      headers: allowed ? { ...CORS_HEADERS, \"Access-Control-Allow-Origin\": origin, Vary: \"Origin\" } : {},\n    });\n  }\n\n" +
            "  const response = NextResponse.next();\n  if (allowed) {\n    response.headers.set(\"Access-Control-Allow-Origin\", origin);\n    response.headers.set(\"Vary\", \"Origin\");\n" +
            "    for (const [key, value] of Object.entries(CORS_HEADERS)) response.headers.set(key, value);\n  }\n  return response;\n}\n\n" +
            "export const config = { matcher: \"/api/:path*\" };\n"

        val fastify = "import Fastify from \"fastify\";\nimport cors from \"@fastify/cors\";\n\n$allowFn\n\nconst app = Fastify();\nawait app.register(cors, {\n" +
            "  origin: (origin, cb) => cb(null, !origin || isAllowedOrigin(origin)),\n" +
            "  methods: ${js(o.methods)},\n  allowedHeaders: ${js(o.headers)},\n${exposeLine("  ", "exposedHeaders")}" +
            "  credentials: ${o.credentials},\n  maxAge: $maxAge,\n});\n"

        val nginxOrigins = if (any && !o.credentials) "" else (exact.map { "    ${js(it)} ${D}http_origin;" } + wild.map { "    \"~${originRegex(it)}\" ${D}http_origin;" }).joinToString("\n")
        val allowOrigin = if (nginxOrigins.isNotEmpty()) "${D}cors_origin" else "\"*\""
        val nginx = "# http { } block\n" +
            (if (nginxOrigins.isNotEmpty()) "map ${D}http_origin ${D}cors_origin {\n    default \"\";\n$nginxOrigins\n}\n\n" else "") +
            "# server { } block\nlocation /api/ {\n    add_header Access-Control-Allow-Origin $allowOrigin always;\n    add_header Vary Origin always;\n" +
            (if (o.credentials) "    add_header Access-Control-Allow-Credentials true always;\n" else "") +
            (if (o.exposeHeaders.isNotEmpty()) "    add_header Access-Control-Expose-Headers \"${o.exposeHeaders.joinToString(", ")}\" always;\n" else "") +
            "\n    if (${D}request_method = OPTIONS) {\n        add_header Access-Control-Allow-Origin $allowOrigin always;\n        add_header Vary Origin always;\n" +
            (if (o.credentials) "        add_header Access-Control-Allow-Credentials true always;\n" else "") +
            "        add_header Access-Control-Allow-Methods \"${o.methods.joinToString(", ")}\" always;\n" +
            "        add_header Access-Control-Allow-Headers \"${o.headers.joinToString(", ")}\" always;\n" +
            "        add_header Access-Control-Max-Age $maxAge always;\n        return 204;\n    }\n\n    proxy_pass http://127.0.0.1:3000;\n}\n"

        val fastapi = "from fastapi import FastAPI\nfrom fastapi.middleware.cors import CORSMiddleware\n\napp = FastAPI()\napp.add_middleware(\n    CORSMiddleware,\n" +
            "    allow_origins=${js(if (any && !o.credentials) listOf("*") else exact)},\n" +
            (if (wild.isNotEmpty()) "    allow_origin_regex=${js(wild.joinToString("|") { originRegex(it) })},\n" else "") +
            "    allow_methods=${js(o.methods)},\n    allow_headers=${js(o.headers)},\n" +
            (if (o.exposeHeaders.isNotEmpty()) "    expose_headers=${js(o.exposeHeaders)},\n" else "") +
            "    allow_credentials=${if (o.credentials) "True" else "False"},\n    max_age=$maxAge,\n)\n"

        return mapOf("express" to express, "nest" to nest, "next" to next, "fastify" to fastify, "nginx" to nginx, "fastapi" to fastapi)
    }

    private val SAFELISTED_HEADERS = setOf("accept", "accept-language", "content-language", "content-type", "range")
    private val SIMPLE_TYPES = listOf("application/x-www-form-urlencoded", "multipart/form-data", "text/plain")
    private val SIMPLE_METHODS = listOf("GET", "HEAD", "POST")

    /** Applies the browser's CORS algorithm to a set of response headers. */
    fun check(origin: String, methodIn: String, requestHeaders: List<String>, credentials: Boolean, responseHeaders: String, preflightStatus: Double?): Verdict {
        val headers = LinkedHashMap<String, MutableList<String>>()
        for (line in responseHeaders.split(Regex("\\r?\\n"))) {
            val colon = line.indexOf(':')
            if (colon <= 0 || Regex("^HTTP/", RegexOption.IGNORE_CASE).containsMatchIn(line)) continue
            headers.getOrPut(line.substring(0, colon).trim().lowercase()) { mutableListOf() } += line.substring(colon + 1).trim()
        }
        fun get(k: String) = headers[k]
        val steps = mutableListOf<Step>()
        val method = methodIn.uppercase()
        val custom = requestHeaders.map { it.split(":")[0].trim().lowercase() }.filter { it.isNotEmpty() }
        val contentType = requestHeaders.firstOrNull { Regex("^content-type\\s*:", RegexOption.IGNORE_CASE).containsMatchIn(it) }?.split(":")?.getOrNull(1)?.trim()?.lowercase()
        val nonSimpleHeaders = custom.filter { h -> h !in SAFELISTED_HEADERS || (h == "content-type" && !(contentType != null && contentType.isNotEmpty() && SIMPLE_TYPES.any { contentType.startsWith(it) })) }
        val preflightNeeded = method !in SIMPLE_METHODS || nonSimpleHeaders.isNotEmpty()
        steps += Step(
            true,
            if (preflightNeeded) "A preflight OPTIONS request is sent first (${if (method !in SIMPLE_METHODS) "method $method" else "headers ${nonSimpleHeaders.joinToString(", ")}"})."
            else "This is a simple request: no preflight; the browser checks the actual response.",
        )
        if (preflightNeeded && preflightStatus != null) {
            val ok = preflightStatus >= 200 && preflightStatus < 300
            val status = Js.number(preflightStatus)
            steps += Step(ok, if (ok) "Preflight status $status is OK." else "Preflight returned $status; it must be 2xx (redirects are not followed). Make OPTIONS bypass auth and return 204.")
        }
        val acao = get("access-control-allow-origin")
        when {
            acao == null -> steps += Step(false, "No Access-Control-Allow-Origin header. The server (or a proxy/error page) did not add CORS headers to this response.")
            acao.size > 1 || acao[0].contains(",") -> steps += Step(false, "Access-Control-Allow-Origin has multiple values (${acao.joinToString(" | ")}). Send exactly one origin - often both the app and a proxy add it.")
            acao[0] == "*" && credentials -> steps += Step(false, "Access-Control-Allow-Origin is * but the request uses credentials (cookies / withCredentials / credentials: \"include\"). Echo the exact origin instead.")
            acao[0] != "*" && acao[0] != origin -> steps += Step(false, "Access-Control-Allow-Origin is \"${acao[0]}\" but the page origin is \"$origin\". They must match exactly (scheme, host and port; no trailing slash).")
            else -> steps += Step(true, "Access-Control-Allow-Origin \"${acao[0]}\" matches.")
        }
        if (acao != null && acao[0] != "*" && !(get("vary") ?: emptyList()).any { it.contains("origin", ignoreCase = true) }) {
            steps += Step(true, "Tip: add Vary: Origin when the allowed origin depends on the request, or a CDN may serve one origin's response to another.")
        }
        if (credentials) {
            val acac = get("access-control-allow-credentials")?.getOrNull(0)
            steps += if (acac == "true") Step(true, "Access-Control-Allow-Credentials: true is present.") else Step(false, "Credentials mode requires Access-Control-Allow-Credentials: true (exactly lowercase \"true\").")
        }
        if (preflightNeeded) {
            val allowMethods = (get("access-control-allow-methods") ?: emptyList()).joinToString(",").split(",").map { it.trim().uppercase() }.filter { it.isNotEmpty() }
            val wildcard = "*" in allowMethods && !credentials
            if (method !in SIMPLE_METHODS) {
                steps += if (wildcard || method in allowMethods) Step(true, "Method $method is allowed.") else Step(false, "Method $method is not in Access-Control-Allow-Methods (${allowMethods.joinToString(", ").ifEmpty { "missing" }}).")
            }
            val allowHeaders = (get("access-control-allow-headers") ?: emptyList()).joinToString(",").split(",").map { it.trim().lowercase() }.filter { it.isNotEmpty() }
            val hWild = "*" in allowHeaders && !credentials
            val missing = nonSimpleHeaders.filter { h -> !(hWild && h != "authorization") && h !in allowHeaders }
            if (nonSimpleHeaders.isNotEmpty()) {
                steps += if (missing.isNotEmpty()) {
                    Step(false, "Request header${if (missing.size > 1) "s" else ""} ${missing.joinToString(", ")} not allowed by Access-Control-Allow-Headers (${allowHeaders.joinToString(", ").ifEmpty { "missing" }})${if ("authorization" in missing && hWild) " - * never covers Authorization" else ""}.")
                } else {
                    Step(true, "Headers ${nonSimpleHeaders.joinToString(", ")} are allowed.")
                }
            }
        }
        return Verdict(preflightNeeded, steps.all { it.ok }, steps)
    }

    private data class CorsError(val re: Regex, val title: String, val fix: String)

    private val CORS_ERRORS = listOf(
        CorsError(Regex("No 'Access-Control-Allow-Origin' header is present", RegexOption.IGNORE_CASE), "The response has no Access-Control-Allow-Origin header", "Enable CORS on the server for this origin. If CORS is configured, the failing response is probably an error (401/404/500) produced before the CORS middleware runs, or a proxy/CDN page: register CORS first and check the Network tab's actual status."),
        CorsError(Regex("must not be the wildcard '\\*' when the request's credentials mode is 'include'", RegexOption.IGNORE_CASE), "Wildcard origin with credentials", "With cookies or Authorization via credentials: \"include\" / withCredentials, return the exact request origin (and Vary: Origin) instead of *, plus Access-Control-Allow-Credentials: true."),
        CorsError(Regex("Access-Control-Allow-Credentials.*(must be 'true'|is '')", RegexOption.IGNORE_CASE), "Credentials not allowed", "Send Access-Control-Allow-Credentials: true, or stop sending credentials (remove withCredentials / credentials: \"include\")."),
        CorsError(Regex("Response to preflight request doesn't pass access control check: It does not have HTTP ok status", RegexOption.IGNORE_CASE), "Preflight OPTIONS failed", "The OPTIONS request got a non-2xx status. Usually auth middleware rejects it (OPTIONS has no Authorization header) or no route handles OPTIONS. Let OPTIONS through before auth and answer 204."),
        CorsError(Regex("Redirect is not allowed for a preflight request", RegexOption.IGNORE_CASE), "Preflight was redirected", "The OPTIONS request got a 301/302 (http→https, missing trailing slash, login redirect). Call the final URL directly."),
        CorsError(Regex("Request header field (\\S+) is not allowed by Access-Control-Allow-Headers", RegexOption.IGNORE_CASE), "A request header is not allowed", "Add that header to Access-Control-Allow-Headers (allowedHeaders in the cors package). Note: * never covers Authorization."),
        CorsError(Regex("Method (\\S+) is not allowed by Access-Control-Allow-Methods", RegexOption.IGNORE_CASE), "The HTTP method is not allowed", "Add the method to Access-Control-Allow-Methods (methods in the cors package)."),
        CorsError(Regex("contains multiple values", RegexOption.IGNORE_CASE), "Duplicate Access-Control-Allow-Origin", "Both the app and a proxy (nginx, API gateway, CDN) add the header. Configure CORS in exactly one place."),
        CorsError(Regex("that is not equal to the supplied origin", RegexOption.IGNORE_CASE), "Origin mismatch", "The allowed origin differs from the page origin. Match scheme, host and port exactly (http://localhost:3000 ≠ http://127.0.0.1:3000) and avoid trailing slashes."),
        CorsError(Regex("(Private Network Access|more-private address space|address space)", RegexOption.IGNORE_CASE), "Private Network Access block", "A public page is calling a private/local address. Serve both from the same network, use HTTPS, or answer the preflight with Access-Control-Allow-Private-Network: true."),
        CorsError(Regex("CORS request did not succeed|net::ERR_FAILED|TypeError: Failed to fetch", RegexOption.IGNORE_CASE), "Network-level failure", "The request never got a response (server down, DNS, TLS error, blocked mixed content, ad blocker). Check the Network tab; it may not be a CORS problem at all."),
        CorsError(Regex("Cross-Origin Read Blocking|CORB|ERR_BLOCKED_BY_ORB|OpaqueResponseBlocking", RegexOption.IGNORE_CASE), "Opaque response blocked (CORB/ORB)", "A no-cors request (or <img>/<script>) received JSON/HTML. Use a normal CORS fetch and correct Content-Type."),
        CorsError(Regex("mixed content", RegexOption.IGNORE_CASE), "Mixed content", "An https page cannot call an http API. Serve the API over https."),
    )

    fun explainError(text: String): List<Pair<String, String>> = CORS_ERRORS.filter { it.re.containsMatchIn(text) }.map { it.title to it.fix }
}
