package pro.devsnip.api.ide

import com.intellij.testFramework.fixtures.BasePlatformTestCase
import pro.devsnip.api.ide.engine.DevSnipActions
import pro.devsnip.api.ide.engine.DevSnipNative
import pro.devsnip.api.ide.engine.DevSnipSettings
import pro.devsnip.api.ide.engine.EngineHost
import pro.devsnip.api.ide.engine.EnginePages
import pro.devsnip.api.ide.engine.ExtensionFiles
import pro.devsnip.api.ide.engine.VsCodeLanguages
import pro.devsnip.api.ide.engine.VsCodeSnippets
import pro.devsnip.api.ide.engine.WhenClause
import java.nio.file.Files
import java.time.LocalDateTime

/** The IDE side of the extension host: menus, snippets, languages, settings, files and pages. */
class EngineTest : BasePlatformTestCase() {
    fun `test when clauses from package json`() {
        val js = mapOf("editorLangId" to "javascript", "editorHasSelection" to false, "resourceFilename" to "package.json", "explorerResourceIsFolder" to false)
        assertTrue(WhenClause.holds("editorLangId =~ /^(javascript|typescript)$/", js))
        assertFalse(WhenClause.holds("editorHasSelection", js))
        assertTrue(WhenClause.holds("!editorHasSelection && editorLangId == javascript", js))
        assertTrue(WhenClause.holds("resourceFilename =~ /^(package-lock\\.json|package\\.json)$/ && !explorerResourceIsFolder", js))
        assertFalse(WhenClause.holds("editorLangId == xml", js))
        assertTrue(WhenClause.holds("resourceExtname =~ /^\\.(csv|tsv)$/i", mapOf("resourceExtname" to ".CSV")))
        assertTrue(WhenClause.holds(null, js))
        assertFalse(WhenClause.holds("false", js))
    }

    fun `test command titles lose their emoji and keep the DevSnip Pro prefix`() {
        assertEquals("DevSnip Pro: cURL Converter", DevSnipActions.cleanTitle("🔌 DevSnip Pro: cURL Converter", null))
        assertEquals("DevSnip Pro: Open in CSV & Excel Viewer", DevSnipActions.cleanTitle("Open in CSV & Excel Viewer", "DevSnip Pro"))
        val ids = DevSnipActions.commands.map { it.id }
        assertTrue(ids.size > 120)
        assertFalse("development-only commands are not offered", ids.any { it.endsWith("resetFeatureUsage") })
        assertFalse("hidden aliases stay out of Find Action", ids.any { it.endsWith("webDevHub") })
        assertTrue(ids.any { it.endsWith("milestoneTracker") } && ids.any { it.endsWith("databaseClient") } && ids.any { it.endsWith("insertDebugLog") })
    }

    fun `test snippet bodies become tab stops, choices and variables`() {
        val now = LocalDateTime.of(2026, 3, 4, 5, 6, 7)
        val parts = VsCodeSnippets.parse("const \${1:name} = \${2|let,var|};\$0 // \$TM_FILENAME \${CURRENT_YEAR} \\$5", "app.ts", now)
        val stops = parts.filterIsInstance<VsCodeSnippets.Part.Stop>()
        assertEquals(listOf(1, 2, 0), stops.map { it.index })
        assertEquals("name", stops[0].default)
        assertEquals(listOf("let", "var"), stops[1].choices)
        assertEquals("const name = let; // app.ts 2026 \$5", VsCodeSnippets.plainText("const \${1:name} = \${2|let,var|};\$0 // \$TM_FILENAME \${CURRENT_YEAR} \\$5", "app.ts").replace(now.year.toString(), now.year.toString()).let {
            it.replace(Regex("20\\d\\d"), "2026")
        })
        // Nested placeholders take their text.
        assertEquals("a(b)", VsCodeSnippets.plainText("\${1:a(\${2:b})}"))
    }

    fun `test snippets insert as a live template`() {
        myFixture.configureByText("a.js", "<caret>")
        val template = VsCodeSnippets.toTemplate(project, "function \${1:name}(\${2:args}) {\n\t\$0\n}", "a.js")
        assertEquals(2, (template as com.intellij.codeInsight.template.impl.TemplateImpl).variableCount)
        com.intellij.codeInsight.template.TemplateManager.getInstance(project).startTemplate(myFixture.editor, template)
        assertTrue(myFixture.editor.document.text.startsWith("function name(args)"))
    }

    fun `test language ids match VS Code's`() {
        assertEquals("typescriptreact", VsCodeLanguages.idOf("App.tsx"))
        assertEquals("dockerfile", VsCodeLanguages.idOf("Dockerfile"))
        assertEquals("dockercompose", VsCodeLanguages.idOf("docker-compose.yml"))
        assertEquals("dotenv", VsCodeLanguages.idOf(".env.local"))
        assertEquals("csharp", VsCodeLanguages.idOf("Program.cs"))
        assertEquals("plaintext", VsCodeLanguages.idOf("notes.unknownext", "PLAIN_TEXT"))
    }

    fun `test settings come from package json with its defaults and limits`() {
        val settings = DevSnipSettings.getInstance()
        assertEquals(30000, settings.value("devsnip.apiTimeout")?.asInt)
        settings.set("devsnip.apiTimeout", com.google.gson.JsonPrimitive(5000))
        assertEquals(5000, settings.value("devsnip.apiTimeout")?.asInt)
        settings.set("devsnip.apiTimeout", com.google.gson.JsonPrimitive(1))
        assertEquals("out-of-range values are refused", 5000, settings.value("devsnip.apiTimeout")?.asInt)
        settings.set("devsnip.rewards.notifications", com.google.gson.JsonPrimitive("loud"))
        assertEquals("all", settings.value("devsnip.rewards.notifications")?.asString)
        settings.set("devsnip.apiTimeout", null)
        assertEquals("devsnip.osvScanner.buildToolResolution", settings.forHost().keySet().first { it.startsWith("devsnip.osvScanner.build") })
        assertFalse("telemetry is off until the user turns it on", settings.telemetry)
    }

    fun `test the extension folder keeps the user's snippets across updates`() {
        val dir = Files.createTempDirectory("devsnip-ext")
        try {
            ExtensionFiles.unpack(dir)
            assertTrue(Files.isRegularFile(dir.resolve("package.json")))
            assertTrue(Files.isRegularFile(dir.resolve("media/db-client.js")))
            val mine = dir.resolve("custom/custom_javascript.json")
            Files.writeString(mine, "{\"log\":{\"prefix\":\"lg\",\"body\":[\"console.log($1)\"]}}")
            Files.writeString(dir.resolve("media/db-client.js"), "changed")
            ExtensionFiles.unpack(dir)
            assertTrue(Files.readString(mine).contains("\"lg\""))
            assertFalse("the plugin's own files are refreshed", Files.readString(dir.resolve("media/db-client.js")) == "changed")
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    fun `test extension pages get the IDE's CSP, colours and the webview bridge`() {
        val html = "<!DOCTYPE html><html><head><meta http-equiv=\"Content-Security-Policy\" content=\"script-src 'nonce-x'\"><title>T</title></head><body class=\"page\"><script nonce=\"x\">acquireVsCodeApi()</script></body></html>"
        val file = EnginePages.write(html, "devsnip.test", "panel", "window.q(request);", "t1")
        try {
            val page = Files.readString(file)
            assertFalse(page.contains("'nonce-x'"))
            assertTrue(page.contains("connect-src 'none'"))
            assertTrue(page.contains("bridge.js"))
            assertTrue(page.contains("window.q(request);"))
            assertTrue(Regex("<body class=\"page vscode-(light|dark)\" data-vscode-theme-kind").containsMatchIn(page))
            assertTrue(page.indexOf("bridge.js") < page.indexOf("acquireVsCodeApi()"))
        } finally {
            Files.deleteIfExists(file)
        }
    }

    fun `test cmd shims are run as the program itself`() {
        assertEquals(listOf("C:\\nodejs\\npm.cmd", "install", "a b"), EngineHost.splitCmdLine("\"C:\\nodejs\\npm.cmd\" \"install\" \"a b\""))
        assertTrue("npm" in EngineHost.ALLOWED_PROGRAMS && "gradlew" in EngineHost.ALLOWED_PROGRAMS)
        assertFalse("opencode" in EngineHost.ALLOWED_PROGRAMS)
        assertFalse("rm" in EngineHost.ALLOWED_PROGRAMS || "bash" in EngineHost.ALLOWED_PROGRAMS)
    }

    fun `test native commands include every toolkit alias, the REST client and the scanners`() {
        assertTrue(DevSnipNative.isNative("sayaib.hue-console.urlEncoder"))
        assertTrue(DevSnipNative.isNative("sayaib.hue-console.openGUI"))
        assertTrue(DevSnipNative.isNative("sayaib.hue-console.osvScanFile"))
        assertFalse(DevSnipNative.isNative("sayaib.hue-console.databaseClient"))
    }

    fun `test the terminal console is found on this IDE`() {
        val handler = com.intellij.execution.process.NopProcessHandler()
        val console = pro.devsnip.api.ide.engine.TerminalConsoles.create(project, handler)
        try {
            assertTrue(console.javaClass.name, console.javaClass.name.endsWith("TerminalExecutionConsole"))
        } finally {
            com.intellij.openapi.util.Disposer.dispose(console)
        }
    }
}
