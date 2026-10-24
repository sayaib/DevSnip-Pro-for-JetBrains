package pro.devsnip.api.ide

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonPrimitive
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.openapi.util.Disposer
import pro.devsnip.api.core.MockServer
import pro.devsnip.api.ide.web.DevSnipUiState
import pro.devsnip.api.ide.web.ScannerHost
import pro.devsnip.api.ide.web.ScannerSettings
import pro.devsnip.api.ide.web.WebviewAssets
import java.io.ByteArrayOutputStream
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.GZIPOutputStream

/**
 * The IDE side of the OSV and SCA scanners: what the page may read, write,
 * fetch and run. Network checks use a local mock server; nothing leaves the machine.
 */
class ScannerHostTest : BasePlatformTestCase() {
    private val posted = mutableListOf<JsonElement>()
    private lateinit var host: ScannerHost
    private lateinit var sandbox: Path

    override fun setUp() {
        super.setUp()
        host = ScannerHost(project, "osv") { posted += it }
        Disposer.register(testRootDisposable, host)
        sandbox = Files.createDirectories(ScannerHost.GLOBAL_STORAGE.resolve("test-${System.nanoTime()}"))
    }

    override fun tearDown() {
        try {
            sandbox.toFile().deleteRecursively()
        } finally {
            super.tearDown()
        }
    }

    // A plain function, not a lambda: JUnit 3 would take a test method's lambdas for tests.
    private fun isPath(raw: String): Boolean = try {
        ScannerHost.pathOf(raw)
        true
    } catch (_: IllegalArgumentException) {
        false
    }

    private var nextId = 1

    /** Sends one RPC as the page does and waits for its answer. */
    private fun call(method: String, params: JsonObject = JsonObject()): JsonObject {
        val id = nextId++
        host.handle(JsonObject().apply { addProperty("type", "__rpc"); addProperty("id", id); addProperty("method", method); add("params", params) })
        val deadline = System.currentTimeMillis() + 15_000
        while (System.currentTimeMillis() < deadline) {
            PlatformTestUtil.dispatchAllInvocationEventsInIdeEventQueue()
            posted.filterIsInstance<JsonObject>().firstOrNull { it["type"]?.asString == "__rpcResult" && it["id"]?.asInt == id }?.let { return it }
            Thread.sleep(10)
        }
        fail("No answer to $method")
        error("unreachable")
    }

    private fun params(vararg pairs: Pair<String, Any?>) = JsonObject().apply {
        for ((k, v) in pairs) when (v) {
            null -> add(k, com.google.gson.JsonNull.INSTANCE)
            is String -> addProperty(k, v)
            is Number -> addProperty(k, v)
            is Boolean -> addProperty(k, v)
            is JsonElement -> add(k, v)
            else -> error("unsupported")
        }
    }

    fun `test globs follow VS Code semantics for the extension's discovery globs`() {
        val deps = ScannerHost.globRegex("**/{package.json,requirements*.txt,*-requirements.txt,pom.xml,*.csproj,build.gradle.kts}")
        for (yes in listOf("package.json", "web/package.json", "a/b/requirements-dev.txt", "dev-requirements.txt", "api/pom.xml", "src/App.csproj", "app/build.gradle.kts")) assertTrue(yes, deps.matches(yes))
        for (no in listOf("package.json.bak", "web/package-lock.json", "requirements.md", "pom.xml/x")) assertFalse(no, deps.matches(no))
        val exclude = ScannerHost.globRegex("**/{node_modules,.git,.venv}/**")
        assertTrue(exclude.matches("node_modules/x"))
        assertTrue(exclude.matches("web/node_modules/left-pad/package.json"))
        assertFalse(exclude.matches("web/modules/package.json"))
        assertTrue(ScannerHost.globRegex("src/[a-c]?.ts").matches("src/b1.ts"))
    }

    fun `test paths from the page are normalised and must be absolute`() {
        assertEquals(Path.of("/tmp/a/c").toAbsolutePath(), ScannerHost.pathOf("/tmp/a/b/../c"))
        assertFalse(isPath("relative/path"))
        assertFalse(isPath(""))
    }

    fun `test files outside the project and the scanner's own folders cannot be read or written`() {
        val inside = sandbox.resolve("osv-cache.json")
        call("fs.write", params("path" to ScannerHost.canonical(inside), "text" to "{\"a\":1}")).let { assertNull(it["error"]) }
        assertEquals("{\"a\":1}", call("fs.read", params("path" to ScannerHost.canonical(inside)))["result"].asString)
        assertEquals(1, call("fs.stat", params("path" to ScannerHost.canonical(inside)))["result"].asJsonObject["type"].asInt)

        val secret = ScannerHost.HOME.resolve(".ssh").resolve("id_rsa")
        assertNotNull(call("fs.read", params("path" to ScannerHost.canonical(secret)))["error"])
        assertTrue(call("fs.stat", params("path" to ScannerHost.canonical(secret)))["result"].isJsonNull)
        assertNotNull(call("fs.write", params("path" to "/etc/devsnip-test", "text" to "x"))["error"])
        assertNotNull(call("fs.rm", params("path" to ScannerHost.canonical(ScannerHost.GLOBAL_STORAGE), "recursive" to true))["error"])
    }

    fun `test temporary folders are made in the system temp folder and are writable`() {
        val prefix = ScannerHost.canonical(ScannerHost.TEMP.resolve("devsnip-osv-"))
        val dir = call("fs.mkdtemp", params("prefix" to prefix))["result"].asString
        assertTrue(Files.isDirectory(ScannerHost.pathOf(dir)))
        assertNull(call("fs.write", params("path" to "$dir/tree.txt", "text" to "tree"))["error"])
        assertNull(call("fs.rm", params("path" to dir, "recursive" to true))["error"])
        assertFalse(Files.exists(ScannerHost.pathOf(dir)))
        assertNotNull(call("fs.mkdtemp", params("prefix" to ScannerHost.canonical(ScannerHost.HOME.resolve("x-"))))["error"])
    }

    fun `test file search skips excluded folders and reports build tool wrappers`() {
        Files.createDirectories(sandbox.resolve("web/node_modules/left-pad"))
        Files.writeString(sandbox.resolve("web/package.json"), "{}")
        Files.writeString(sandbox.resolve("web/node_modules/left-pad/package.json"), "{}")
        Files.createDirectories(sandbox.resolve("api"))
        Files.writeString(sandbox.resolve("api/pom.xml"), "<project/>")
        Files.writeString(sandbox.resolve("api/mvnw"), "#!/bin/sh")
        val result = call("ws.findFiles", params("base" to ScannerHost.canonical(sandbox), "include" to "**/{package.json,pom.xml}", "exclude" to "**/{node_modules}/**", "max" to 100))["result"].asJsonObject
        val paths = result.getAsJsonArray("paths").map { it.asString.removePrefix(ScannerHost.canonical(sandbox) + "/") }.sorted()
        assertEquals(listOf("api/pom.xml", "web/package.json"), paths)
        assertTrue(result.getAsJsonObject("facts").getAsJsonArray("exists").any { it.asString.endsWith("/api/mvnw") })
    }

    fun `test HTTP goes through the IDE with the page's method, headers and body`() {
        val gz = ByteArrayOutputStream().also { out -> GZIPOutputStream(out).use { it.write("{\"items\":[1]}".toByteArray()) } }.toByteArray()
        MockServer.http().handle("/v1/querybatch") { exchange, _ ->
            val body = "{\"results\":[]}".toByteArray()
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.write(body)
        }.handle("/gz") { exchange, _ ->
            exchange.responseHeaders.add("Content-Encoding", "gzip")
            exchange.sendResponseHeaders(200, gz.size.toLong())
            exchange.responseBody.write(gz)
        }.use { server ->
            val headers = JsonObject().apply { addProperty("User-Agent", "DevSnip-Pro/test"); addProperty("Content-Type", "application/json") }
            val answer = call("http.fetch", params("id" to 1, "url" to server.url("/v1/querybatch"), "method" to "POST", "headers" to headers, "body" to "{\"queries\":[]}"))["result"].asJsonObject
            assertEquals(200, answer["status"].asInt)
            assertEquals("{\"results\":[]}", answer["text"].asString)
            assertEquals("application/json", answer.getAsJsonObject("headers")["content-type"].asString)
            val received = server.requests.single()
            assertEquals("POST", received.method)
            assertEquals("{\"queries\":[]}", received.body)
            assertEquals("DevSnip-Pro/test", received.header("User-Agent"))
            assertEquals("{\"items\":[1]}", call("http.fetch", params("id" to 2, "url" to server.url("/gz")))["result"].asJsonObject["text"].asString)
        }
        assertNotNull(call("http.fetch", params("id" to 3, "url" to "http://example.com/"))["error"])
        assertNotNull(call("http.fetch", params("id" to 4, "url" to "file:///etc/passwd"))["error"])
    }

    fun `test an abort that overtakes its request means the request is never sent`() {
        MockServer.http().handle("/late") { exchange, _ -> exchange.sendResponseHeaders(200, -1) }.use { server ->
            assertNull(call("http.abort", params("id" to 40))["error"])
            val answer = call("http.fetch", params("id" to 40, "url" to server.url("/late")))
            assertEquals("The request was cancelled.", answer["error"]?.asString)
            assertTrue(server.requests.isEmpty())
            // Later requests are not affected.
            assertEquals(200, call("http.fetch", params("id" to 41, "url" to server.url("/late")))["result"].asJsonObject["status"].asInt)
        }
    }

    fun `test only https leaves the machine and the NVD key only goes to NVD`() {
        assertTrue(ScannerHost.allowedUrl(URI("https://api.osv.dev/v1/querybatch")))
        assertTrue(ScannerHost.allowedUrl(URI("http://127.0.0.1:8080/x")))
        assertFalse(ScannerHost.allowedUrl(URI("http://repo.example.com/maven2")))
        assertFalse(ScannerHost.allowedUrl(URI("ftp://example.com")))
        val marker = ScannerHost.SECRET_MARKER + ScannerHost.NVD_KEY
        val lookup = { _: String -> "test-key-not-real-0000" }
        assertEquals("test-key-not-real-0000", ScannerHost.resolveSecretHeader(URI("https://services.nvd.nist.gov/rest/json/cves/2.0"), marker, lookup))
        assertNull(ScannerHost.resolveSecretHeader(URI("https://evil.example.com/"), marker, lookup))
        assertEquals("plain", ScannerHost.resolveSecretHeader(URI("https://evil.example.com/"), "plain", lookup))
        assertNotNull(call("secrets.get", params("key" to "someone.else"))["error"])
    }

    fun `test only the extension's own Maven and Gradle runs are allowed`() {
        val temp = Files.createTempDirectory("devsnip-osv-")
        try {
            val output = ScannerHost.canonical(temp.resolve("tree.txt"))
            val maven = listOf("-B", "-q", "-Dstyle.color=never", "org.apache.maven.plugins:maven-dependency-plugin:3.6.1:tree", "-DoutputType=text", "-DoutputFile=$output", "-DappendOutput=true")
            assertTrue(ScannerHost.allowedArguments("mvnw", maven, listOf(temp)))
            assertFalse(ScannerHost.allowedArguments("mvnw", maven, emptyList()))
            assertFalse(ScannerHost.allowedArguments("mvnw", maven + "-Dexec.executable=sh", listOf(temp)))
            assertFalse(ScannerHost.allowedArguments("mvnw", maven.map { it.replace("tree", "exec") }, listOf(temp)))
            assertFalse(ScannerHost.allowedArguments("sh", maven, listOf(temp)))

            val buildTools = com.google.gson.JsonParser.parseString(javaClass.getResourceAsStream("/webview/build-tools.json")!!.use { String(it.readBytes()) }).asJsonObject.getAsJsonObject("gradle")
            val script = temp.resolve("devsnip-osv.init.gradle")
            val gradleArgs = buildTools.getAsJsonArray("args").map { it.asString.replace("__INIT__", ScannerHost.canonical(script)) }
            Files.writeString(script, buildTools["initScript"].asString)
            assertTrue(ScannerHost.allowedArguments("gradlew", gradleArgs, listOf(temp)))
            Files.writeString(script, "allprojects { exec { commandLine 'sh' } }")
            assertFalse("a changed init script is refused", ScannerHost.allowedArguments("gradlew", gradleArgs, listOf(temp)))
        } finally {
            temp.toFile().deleteRecursively()
        }
        // An untrusted (or unknown) command is refused before anything starts.
        val refused = call("process.spawn", params("id" to 7, "command" to "/bin/sh", "args" to JsonArray().apply { add("-c"); add("echo hi") }, "cwd" to ScannerHost.canonical(sandbox)))
        assertNotNull(refused["error"])
    }

    fun `test the local Maven repository follows settings xml`() {
        val home = Files.createTempDirectory("devsnip-home")
        try {
            assertEquals(home.resolve(".m2/repository"), ScannerHost.localRepository(home))
            Files.createDirectories(home.resolve(".m2"))
            Files.writeString(home.resolve(".m2/settings.xml"), "<settings><!-- <localRepository>/nope</localRepository> --><localRepository>\${user.home}/repo</localRepository></settings>")
            assertEquals(ScannerHost.normalize(home.resolve("repo")), ScannerHost.localRepository(home))
        } finally {
            home.toFile().deleteRecursively()
        }
    }

    fun `test confirmations allow one change shortly after the user said yes`() {
        val confirmation = ScannerHost.Confirmation()
        assertFalse(confirmation.consume { true })
        confirmation.arm("Run this command in api?\n\nnpm install lodash@4.17.21")
        assertFalse(confirmation.consume { it.contains("rm -rf /") })
        assertFalse("used up", confirmation.consume { true })
        confirmation.arm("Run this command in api?\n\nnpm install lodash@4.17.21")
        assertTrue(confirmation.consume { it.contains("npm install lodash@4.17.21") })
        // Without a confirmation, nothing runs in the terminal.
        assertNotNull(call("terminal.run", params("name" to "x", "cwd" to "", "command" to "echo hi"))["error"])
    }

    fun `test the page gets the settings VS Code would have and the IDE's facts`() {
        val info = host.hostInfo()
        assertEquals("osv", info["page"].asString)
        val osv = info.getAsJsonObject("settings").getAsJsonObject("devsnip.osvScanner")
        assertEquals("ask", osv["buildToolResolution"].asString)
        assertEquals(300, osv["buildToolTimeoutSeconds"].asInt)
        assertEquals("direct", info.getAsJsonObject("settings").getAsJsonObject("devsnip.sca")["healthChecks"].asString)
        assertTrue(info.has("facts"))
        ScannerSettings.getInstance().state.healthChecks = "bogus"
        try {
            assertEquals("direct", ScannerSettings.getInstance().asVsCodeSettings().getAsJsonObject("devsnip.sca")["healthChecks"].asString)
        } finally {
            ScannerSettings.getInstance().state.healthChecks = "direct"
        }
    }

    fun `test the scanner pages assemble with the runtime and the bridge`() {
        for (page in listOf("osv", "sca")) {
            // The preview copy (build/webview-preview) answers every request with null, so it opens in any Chromium.
            val answer = "var m=JSON.parse(request);console.log('[to IDE]',m.method||m.type);if(m.type==='__rpc')setTimeout(function(){window.__devsnipReceive({type:'__rpcResult',id:m.id,result:null})},0);"
            val path = WebviewAssets.scannerHtml(page, DevSnipUiState.getInstance(), host.hostInfo().apply { addProperty("page", page) }, answer)
            val html = Files.readString(path)
            assertFalse(page, html.contains("__DEVSNIP_"))
            assertTrue(page, html.indexOf("src=\"bridge.js\"") in 0 until html.indexOf("src=\"scanner-runtime.js\""))
            assertTrue(page, html.contains("src=\"$page-scanner.js\""))
            assertTrue(page, html.contains("connect-src 'none'"))
            for (name in listOf("scanner-runtime.js", "$page-scanner.js")) assertTrue(Files.exists(WebviewAssets.directory.resolve(name)))
            val preview = java.io.File("build/webview-preview").toPath()
            Files.createDirectories(preview)
            Files.list(WebviewAssets.directory).forEach { Files.copy(it, preview.resolve(it.fileName), java.nio.file.StandardCopyOption.REPLACE_EXISTING) }
        }
        assertTrue(Files.readString(WebviewAssets.directory.resolve("scanner-sca.html")).contains("src=\"sca-model.js\""))
        assertEquals(JsonPrimitive("osv"), JsonPrimitive(WebviewAssets.scannerByCommand.getValue(WebviewAssets.COMMAND_PREFIX + "osvScanner")))
        assertEquals("sca", WebviewAssets.scannerByCommand.getValue(WebviewAssets.COMMAND_PREFIX + "scaScanner"))
    }
}
