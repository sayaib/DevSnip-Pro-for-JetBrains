package pro.devsnip.api.ide

import com.google.gson.JsonObject
import com.intellij.ide.plugins.DynamicPlugins
import com.intellij.ide.plugins.IdeaPluginDescriptorImpl
import com.intellij.ide.plugins.PluginManagerCore
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.extensions.PluginId
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import pro.devsnip.api.ide.engine.DevSnipActions
import pro.devsnip.api.ide.web.DevSnipUiState
import pro.devsnip.api.ide.web.EditorTracker
import pro.devsnip.api.ide.web.WebviewAssets
import java.nio.file.Files

/** How the plugin hooks into the IDE: page files per browser, the editor "Use editor" reads, and the generated actions. */
class IdeIntegrationTest : BasePlatformTestCase() {
    fun `test each browser gets its own page file with its own query function`() {
        val state = DevSnipUiState.getInstance()
        val host = JsonObject().apply { addProperty("page", "tool"); addProperty("toolId", "web.curl"); add("storage", JsonObject()) }
        val first = WebviewAssets.toolHtml("web.curl", JsonObject(), state, host, "window.queryA(request);", "b1")
        val second = WebviewAssets.toolHtml("web.curl", JsonObject(), state, host, "window.queryB(request);", "b2")
        assertFalse(first == second)
        assertEquals(WebviewAssets.directory, first.parent)
        assertTrue(Files.readString(first).contains("window.queryA(request);"))
        assertFalse(Files.readString(first).contains("window.queryB"))
        assertTrue(Files.readString(second).contains("window.queryB(request);"))
        // Instance names cannot leave the folder.
        val odd = WebviewAssets.sidebarHtml(state, JsonObject().apply { addProperty("page", "sidebar") }, "", "../../x")
        assertEquals(WebviewAssets.directory, odd.parent)
        listOf(first, second, odd).forEach(Files::deleteIfExists)
    }

    fun `test the page scripts are complete in the version folder`() {
        for (name in listOf("bridge.js", "toolkit-runtime.js", "scanner-runtime.js", "codicon.ttf")) {
            val expected = javaClass.getResourceAsStream("/webview/$name")!!.use { it.readBytes() }
            assertTrue(name, expected.contentEquals(Files.readAllBytes(WebviewAssets.directory.resolve(name))))
        }
        Files.list(WebviewAssets.directory).use { files -> assertTrue(files.noneMatch { it.fileName.toString().endsWith(".tmp") }) }
    }

    fun `test Use Editor and Insert find the project's text editor`() {
        myFixture.configureByText("sample.json", "{\"a\": 1}")
        EditorTracker.install(project)
        val editor = EditorTracker.lastEditor(project)
        assertNotNull(editor)
        assertEquals("{\"a\": 1}", editor!!.document.text)
    }

    fun `test the generated actions are removed before unloading and come back on load`() {
        val manager = ActionManager.getInstance()
        val id = "DevSnipPro.osvScanner"
        DevSnipActions.registerOnce()
        assertNotNull(manager.getAction(id))
        val menu = manager.getAction("DevSnipPro.ToolsMenu") as DefaultActionGroup
        val groups = menu.childActionsOrStubs.count { it is DefaultActionGroup }
        // The sidebar's 14 sections, plus More for the commands the sidebar does not list.
        assertTrue("sections: $groups", groups >= 14)

        DevSnipActions.unregisterAll()
        assertNull(manager.getAction(id))
        assertEquals(0, menu.childActionsOrStubs.count { it is DefaultActionGroup })

        DevSnipActions.registerOnce()
        DevSnipActions.registerOnce()
        val action = manager.getAction(id)
        assertNotNull(action)
        assertEquals("DevSnip Pro: OSV Vulnerability Scanner", action.templatePresentation.text)
        assertEquals("registering twice adds nothing", groups, menu.childActionsOrStubs.count { it is DefaultActionGroup })
        // Every command VS Code's command palette shows has an action (development-only ones excepted).
        assertEquals(DevSnipActions.commands.size, DevSnipActions.commands.count { manager.getAction("DevSnipPro." + it.id.removePrefix(WebviewAssets.COMMAND_PREFIX)) != null })
        assertNull(manager.getAction("DevSnipPro.resetFeatureUsage"))
        // The shortcut action from plugin.xml is the one Find Action shows.
        assertTrue(manager.getAction("DevSnipPro.insertDebugLog") is pro.devsnip.api.ide.engine.InsertDebugLogAction)
        // The editor and Project view menus are filled from package.json.
        assertTrue((manager.getAction("DevSnipPro.EditorContext") as DefaultActionGroup).childrenCount >= 15)
        assertTrue((manager.getAction("DevSnipPro.ProjectViewContext") as DefaultActionGroup).childrenCount >= 3)
    }

    fun `test the IDE can install, update and unload the plugin without a restart`() {
        val descriptor = PluginManagerCore.getPlugin(PluginId.getId("pro.devsnip.api")) as? IdeaPluginDescriptorImpl
        assertNotNull("the plugin is loaded in the test IDE", descriptor)
        assertNull(DynamicPlugins.checkCanUnloadWithoutRestart(descriptor!!))
    }
}
