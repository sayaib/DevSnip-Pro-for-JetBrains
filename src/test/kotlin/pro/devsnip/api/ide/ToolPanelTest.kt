package pro.devsnip.api.ide

import com.intellij.openapi.util.Disposer
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import pro.devsnip.api.core.toolkit.BackendApiTools
import pro.devsnip.api.ide.ui.ApiToolWindowFactory
import pro.devsnip.api.ide.ui.ToolPanel

/** The generic tool panel in a headless IDE: forms, conditional fields, running and results. */
class ToolPanelTest : BasePlatformTestCase() {
    private fun panel(id: String) = ToolPanel(project, BackendApiTools.byId(id)).also { Disposer.register(testRootDisposable, it) }

    private fun waitForResult(panel: ToolPanel, what: String, condition: (String) -> Boolean): String {
        val deadline = System.currentTimeMillis() + 10_000
        while (true) {
            PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
            val text = panel.resultText()
            if (!panel.isRunning && condition(text)) return text
            if (System.currentTimeMillis() > deadline) fail("Timed out waiting for $what. Results:\n$text")
            Thread.sleep(20)
        }
    }

    fun `test every Backend and API tool opens and runs its defaults`() {
        assertEquals(7, BackendApiTools.all.size)
        for (tool in BackendApiTools.all) {
            val panel = panel(tool.id)
            panel.run()
            val text = waitForResult(panel, tool.title) { it.isNotBlank() }
            assertFalse("${tool.title} failed on its defaults:\n$text", text.contains("Something went wrong"))
            assertTrue(ApiToolWindowFactory.TAB_NAMES.containsKey(tool.id))
        }
    }

    fun `test the cURL converter converts the command typed in the form`() {
        val panel = panel("web.curl")
        panel.setField("curl", "curl -X PUT https://api.example.com/items/7 -H 'X-Test: 1' --json '{\"a\":1}'")
        panel.setField("target", "python")
        panel.run()
        val text = waitForResult(panel, "Python output") { it.contains("requests.request") }
        assertTrue(text, text.contains("\"PUT\""))
        assertTrue(text, text.contains("\"https://api.example.com/items/7\""))
    }

    fun `test a mode switch shows the matching fields`() {
        val panel = panel("web.url")
        assertTrue(panel.isFieldVisible("url"))
        assertFalse(panel.isFieldVisible("base"))
        panel.setField("mode", "build")
        assertFalse(panel.isFieldVisible("url"))
        assertTrue(panel.isFieldVisible("base"))
    }

    fun `test invalid input is reported as a message`() {
        val panel = panel("web.graphql")
        panel.setField("query", "{ a { b }")
        panel.run()
        val text = waitForResult(panel, "the error") { it.contains("never closed") }
        assertFalse(text.contains("Something went wrong"))
    }

    fun `test the scaffolder lists the generated files`() {
        val panel = panel("web.api-scaffold")
        panel.setField("framework", "spring")
        panel.run()
        val text = waitForResult(panel, "files") { it.contains("[tab] ProductController.java") }
        assertTrue(text, text.contains("[tab] ApiExceptionHandler.java"))
    }
}
