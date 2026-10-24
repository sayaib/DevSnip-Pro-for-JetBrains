package pro.devsnip.api.ide.web

import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.intellij.openapi.application.PathManager
import com.intellij.openapi.util.SystemInfo
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.CRC32

/**
 * DevSnip Pro's own webview pages (exported from the VS Code extension by
 * webview/build.mjs into resources/webview) assembled for the IDE's browser:
 * the page's data, the DevSnip theme, the IDE's colours, and the bridge that
 * stands in for VS Code's webview API.
 */
object WebviewAssets {
    const val COMMAND_PREFIX = "sayaib.hue-console."
    const val REST_CLIENT_COMMAND = COMMAND_PREFIX + "openGUI"
    private const val DATA_MARKER = "__DEVSNIP_DATA__"
    private val STATIC = listOf(
        "bridge.js", "tools-sidebar.js", "toolkit.js", "toolkit-runtime.js", "codicon.css", "codicon.ttf",
        "osv-scanner.js", "sca-scanner.js", "sca-model.js", "scanner-runtime.js",
    )

    /** Escapes like the extension's embedJson: safe inside an inline <script>. */
    private val gson = Gson()

    private fun resource(name: String): ByteArray =
        WebviewAssets::class.java.getResourceAsStream("/webview/$name")?.use { it.readBytes() } ?: error("Missing webview resource $name")

    private fun text(name: String) = String(resource(name), Charsets.UTF_8)
    private fun json(name: String): JsonObject = JsonParser.parseString(text(name)).asJsonObject

    val tools: JsonObject by lazy { json("tools.json") }
    private val themes: JsonObject by lazy { json("themes.json") }
    private val sidebarShell: String by lazy { text("sidebar.html") }
    private val toolShell: String by lazy { text("tool.html") }
    private val sidebarDefaults: JsonObject by lazy { json("sidebar-data.json") }

    /** The OSV and SCA scanner pages: page id → command, title, scripts. */
    val scanners: JsonObject by lazy { json("scanners.json") }

    /** Scanner command id → page id ("osv", "sca"). */
    val scannerByCommand: Map<String, String> by lazy { scanners.entrySet().associate { (page, s) -> s.asJsonObject["command"].asString to page } }

    fun scannerTitle(page: String): String = scanners.getAsJsonObject(page)?.get("title")?.asString ?: page

    /** Toolkit command id → tool id. */
    val toolByCommand: Map<String, String> by lazy { tools.entrySet().associate { (id, t) -> COMMAND_PREFIX + t.asJsonObject["command"].asString to id } }

    /** Sidebar commands this IDE can run; the others stay hidden until they are ported. */
    val supportedCommands: Set<String> by lazy { toolByCommand.keys + scannerByCommand.keys + REST_CLIENT_COMMAND }

    /** Every sidebar entry, in sidebar order, with its section: (command, label, section title). */
    val entries: List<Triple<String, String, String>> by lazy {
        sidebarDefaults.getAsJsonArray("groups").flatMap { g ->
            val group = g.asJsonObject
            group.getAsJsonArray("tools").map { t -> Triple(t.asJsonObject["command"].asString, t.asJsonObject["label"].asString, group["name"].asString) }
        }
    }

    fun toolTitle(toolId: String): String = tools.getAsJsonObject(toolId)?.get("title")?.asString ?: toolId

    /** The pages' scripts and font, written once per build to the IDE's system folder. */
    val directory: Path by lazy {
        val crc = CRC32()
        STATIC.forEach { crc.update(resource(it)) }
        val dir = Path.of(PathManager.getSystemPath(), "devsnip-pro", "webview", java.lang.Long.toHexString(crc.value))
        Files.createDirectories(dir)
        for (name in STATIC) {
            val target = dir.resolve(name)
            if (!Files.exists(target)) Files.write(target, resource(name))
        }
        dir
    }

    fun themeIds(): Set<String> = themes.getAsJsonObject("blocks").keySet()

    fun validTheme(id: String?): String = if (id != null && id in themeIds()) id else themes["system"].asString

    /** The `devsnip:theme` message that restyles open pages, as the extension's theme service sends it. */
    fun themeMessage(themeId: String, surface: String): JsonObject =
        themes.getAsJsonObject("messages").getAsJsonObject(validTheme(themeId)).getAsJsonObject(surface)

    /** Theme choices for the sidebar picker. All are available in the IDE (rewards are not ported yet). */
    private fun themeChoices(): JsonArray = themes.getAsJsonArray("choices").deepCopy().apply {
        forEach { choice ->
            val c = choice.asJsonObject
            c.remove("locked")
            c.remove("lock")
            if (c["id"].asString == "system") c.addProperty("description", "Follows your IDE color theme, light or dark.")
        }
    }

    fun sidebarData(state: DevSnipUiState): JsonObject {
        val data = sidebarDefaults.deepCopy()
        val groups = JsonArray()
        for (g in data.getAsJsonArray("groups")) {
            val group = g.asJsonObject
            val tools = JsonArray()
            group.getAsJsonArray("tools").filter { it.asJsonObject["command"].asString in supportedCommands }.forEach(tools::add)
            if (tools.size() > 0) {
                group.add("tools", tools)
                groups.add(group)
            }
        }
        data.add("groups", groups)
        data.add("expanded", JsonArray().apply { state.expanded.forEach(::add) })
        data.add("favorites", JsonArray().apply { state.favorites.filter { it in supportedCommands }.forEach(::add) })
        data.add("usage", usage(state))
        data.add("theme", JsonObject().apply {
            addProperty("current", validTheme(state.theme))
            add("choices", themeChoices())
            add("lockedPreview", null)
            addProperty("previewSeconds", 30)
        })
        data.add("onboarding", JsonObject().apply { add("guide", null); add("whatsNew", null) })
        return data
    }

    fun usage(state: DevSnipUiState): JsonObject {
        val out = JsonObject()
        for ((command, entry) in state.usage().entrySet()) if (command in supportedCommands) out.add(command, entry)
        return out
    }

    fun sidebarHtml(state: DevSnipUiState, host: JsonObject, query: String): Path {
        val html = sidebarShell
            .replace(DATA_MARKER, embed(sidebarData(state)))
            .replace("__DEVSNIP_SCRIPT__", "tools-sidebar.js")
            .replace("__DEVSNIP_CODICONS__", "codicon.css")
        // The rank card belongs to Milestones & rewards, which the IDE does not have yet.
        return write("sidebar.html", assemble(html, state.theme, "sidebar", host, query, extraHead = "<style>#rank{display:none !important}</style>", scripts = listOf("bridge.js")))
    }

    fun toolHtml(toolId: String, initial: JsonObject, state: DevSnipUiState, host: JsonObject, query: String): Path {
        val tool = tools.getAsJsonObject(toolId) ?: error("Unknown tool $toolId")
        val data = tool.getAsJsonObject("data").deepCopy()
        data.add("initial", initial)
        data.addProperty("mac", SystemInfo.isMac)
        val html = toolShell
            .replace(DATA_MARKER, embed(data))
            .replace("__DEVSNIP_SCRIPT__", "toolkit.js")
            .replace("__DEVSNIP_TITLE__", escapeHtml(tool["title"].asString))
        return write("tool-${toolId.replace(Regex("[^A-Za-z0-9.-]"), "_")}.html", assemble(html, state.theme, "panel", host, query, scripts = listOf("toolkit-runtime.js", "bridge.js")))
    }

    /** The extension's OSV or SCA scanner page; its own code runs in the page (scanner-runtime.js) and asks the IDE through ScannerHost. */
    fun scannerHtml(page: String, state: DevSnipUiState, host: JsonObject, query: String): Path {
        val scanner = scanners.getAsJsonObject(page) ?: error("Unknown scanner $page")
        var html = text("$page.html")
            .replace("__DEVSNIP_SCRIPT__", scanner["script"].asString)
            .replace("__DEVSNIP_CODICONS__", "codicon.css")
        scanner["model"]?.let { html = html.replace("__DEVSNIP_MODEL__", it.asString) }
        return write("scanner-$page.html", assemble(html, state.theme, "panel", host, query, scripts = listOf("bridge.js", "scanner-runtime.js")))
    }

    /**
     * Adds what VS Code adds around a webview: the theme (extension's
     * injectTheme block), the editor's colours (here: the IDE's) and the body
     * class for light or dark, plus the bridge. The CSP allows only this
     * page's own files and inline styles.
     */
    private fun assemble(html: String, themeId: String, surface: String, host: JsonObject, query: String, extraHead: String = "", scripts: List<String>): String {
        val theme = themes.getAsJsonObject("blocks").getAsJsonObject(validTheme(themeId))[surface].asString.replace("__DEVSNIP_NONCE__", "devsnip")
        val csp = "default-src 'none'; style-src 'unsafe-inline' file:; script-src 'unsafe-inline' file:; font-src file: data:; img-src file: data: https:; connect-src 'none';"
        val head = "<style id=\"devsnip-ide-theme\" data-surface=\"$surface\">${IdeThemeCss.css(surface)}</style>$extraHead" +
            "<script>window.__devsnipHost=${embed(host)};window.__devsnipQuery=function(request){$query};</script>" +
            scripts.joinToString("") { "<script src=\"$it\"></script>" }
        val bodyClass = IdeThemeCss.bodyClass()
        return html
            .replace(Regex("<meta http-equiv=\"Content-Security-Policy\" content=\"[^\"]*\">"), "<meta http-equiv=\"Content-Security-Policy\" content=\"$csp\">")
            .replaceFirst("<head>", "<head>$head")
            .replaceFirst("</head>", "$theme</head>")
            .replaceFirst("<body>", "<body class=\"$bodyClass\" data-vscode-theme-kind=\"$bodyClass\">")
    }

    private fun write(name: String, html: String): Path {
        val file = directory.resolve(name)
        Files.writeString(file, html)
        return file
    }

    /** JSON safe to embed in an inline <script> (as the extension's embedJson). */
    fun embed(value: JsonElement): String = gson.toJson(value).replace("\u2028", "\\u2028").replace("\u2029", "\\u2029")

    private fun escapeHtml(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
}
