package pro.devsnip.api.ide

import com.google.gson.JsonObject
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import pro.devsnip.api.ide.web.DevSnipUiState
import pro.devsnip.api.ide.web.IdeThemeCss
import pro.devsnip.api.ide.web.ToolHost
import pro.devsnip.api.ide.web.WebviewAssets
import java.io.File
import java.nio.file.Files

/**
 * The extension's pages as the IDE assembles them. The pages are also copied
 * to build/webview-preview with a logging bridge, so they can be opened in
 * any Chromium (the engine behind JCEF) to check them by eye.
 */
class WebviewAssetsTest : BasePlatformTestCase() {
    private val logQuery = "console.log('[to IDE]', request);"
    private val preview = File("build/webview-preview")

    private fun copyPreview(page: java.nio.file.Path) {
        preview.mkdirs()
        Files.list(WebviewAssets.directory).forEach { Files.copy(it, preview.toPath().resolve(it.fileName), java.nio.file.StandardCopyOption.REPLACE_EXISTING) }
        Files.copy(page, preview.toPath().resolve(page.fileName), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
    }

    fun `test the sidebar page is complete and shows only commands the IDE can run`() {
        val state = DevSnipUiState.getInstance()
        val page = WebviewAssets.sidebarHtml(state, JsonObject().apply { addProperty("page", "sidebar") }, logQuery)
        val html = Files.readString(page)
        for (marker in listOf("__DEVSNIP_DATA__", "__DEVSNIP_SCRIPT__", "__DEVSNIP_CODICONS__", "__DEVSNIP_NONCE__")) assertFalse(marker, html.contains(marker))
        assertTrue(html.contains("<script src=\"tools-sidebar.js\"></script>") || html.contains("src=\"tools-sidebar.js\""))
        assertTrue(html.contains("id=\"devsnip-theme\""))
        assertTrue(html.contains("class=\"${IdeThemeCss.bodyClass()}\""))
        val data = WebviewAssets.sidebarData(state)
        val commands = data.getAsJsonArray("groups").flatMap { g -> g.asJsonObject.getAsJsonArray("tools").map { it.asJsonObject["command"].asString } }
        assertEquals("every supported command appears once", WebviewAssets.supportedCommands, commands.toSet())
        assertEquals(103, commands.size) // 100 toolkit tools, the OSV and SCA scanners and the REST API Client
        assertTrue(WebviewAssets.COMMAND_PREFIX + "osvScanner" in commands)
        assertTrue(WebviewAssets.COMMAND_PREFIX + "scaScanner" in commands)
        // 13 of the 14 sections: Spreadsheets & CSV only has host features that are not ported yet.
        assertEquals(13, data.getAsJsonArray("groups").size())
        for (name in listOf("tools-sidebar.js", "bridge.js", "codicon.css", "codicon.ttf")) assertTrue(Files.exists(WebviewAssets.directory.resolve(name)))
        copyPreview(page)
    }

    fun `test every tool page assembles`() {
        val state = DevSnipUiState.getInstance()
        for (toolId in WebviewAssets.tools.keySet()) {
            val host = JsonObject().apply { addProperty("page", "tool"); addProperty("toolId", toolId); add("storage", JsonObject()) }
            val page = WebviewAssets.toolHtml(toolId, JsonObject(), state, host, logQuery)
            val html = Files.readString(page)
            assertFalse(toolId, html.contains("__DEVSNIP_"))
            assertTrue(toolId, html.contains("src=\"toolkit-runtime.js\""))
            if (toolId == "web.curl" || toolId == "dev.jwt" || toolId == "ai.prompt") copyPreview(page)
        }
        assertEquals(100, WebviewAssets.tools.size())
    }

    fun `test the REST API Client page is the extension's own, with its runtime and bridge`() {
        val state = DevSnipUiState.getInstance()
        val host = JsonObject().apply { addProperty("page", "rest-client"); add("storage", JsonObject()) }
        val page = WebviewAssets.restClientHtml(state, host, logQuery)
        val html = Files.readString(page)
        assertFalse(html.contains("__DEVSNIP_"))
        assertTrue(html.contains("<title>REST API Client</title>"))
        assertTrue(html.contains("const vscode = acquireVsCodeApi();"))
        // The feature system and the bridge load before the page's own script.
        val runtime = html.indexOf("src=\"api-client-runtime.js\"")
        val bridge = html.indexOf("src=\"bridge.js\"")
        assertTrue(runtime in 0 until bridge)
        assertTrue(bridge < html.indexOf("acquireVsCodeApi();"))
        // The IDE's CSP (inline scripts, local files, WebSockets for the WebSocket tool); no nonce-only CSP is left.
        assertTrue(html.contains("connect-src ws: wss:;"))
        assertFalse(html.contains("'nonce-"))
        // Points belong to Milestones & rewards, not ported: hidden, every feature open.
        assertTrue(html.contains("#pointsBadge,.cost-tag"))
        assertTrue(html.contains("id=\"devsnip-theme\""))
        assertTrue(Files.exists(WebviewAssets.directory.resolve("api-client-runtime.js")))
        copyPreview(page)
    }

    fun `test the IDE theme provides every VS Code variable the pages use`() {
        // Variables the pages read (DevSnip themes also define many, which needs no IDE value).
        val used = Regex("var\\((--vscode-[A-Za-z0-9-]+)").findAll(
            listOf("sidebar.html", "tool.html", "rest-client.html", "tools-sidebar.js", "toolkit.js", "themes.json", "osv.html", "sca.html", "osv-scanner.js", "sca-scanner.js").joinToString("\n") { name ->
                javaClass.getResourceAsStream("/webview/$name")!!.use { String(it.readBytes()) }
            },
        ).map { it.groupValues[1] }.toSet()
        val provided = Regex("(--vscode-[A-Za-z0-9-]+):").findAll(IdeThemeCss.css("panel") + IdeThemeCss.css("sidebar")).map { it.groupValues[1] }.toSet()
        val missing = used - provided
        assertTrue("Not provided by the IDE theme: $missing", missing.isEmpty())
    }

    fun `test workspace paths stay inside the workspace`() {
        assertEquals("src/a.ts", ToolHost.safeRelativePath("./src/a.ts"))
        assertEquals("src/a.ts", ToolHost.safeRelativePath("src\\a.ts"))
        assertNull(ToolHost.safeRelativePath("../etc/passwd"))
        assertNull(ToolHost.safeRelativePath("/etc/passwd"))
        assertNull(ToolHost.safeRelativePath("C:/Windows"))
        assertNull(ToolHost.safeRelativePath("a//b"))
    }
}
