package pro.devsnip.api.ide.ui

import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.ui.content.ContentFactory
import com.intellij.ui.jcef.JBCefApp
import pro.devsnip.api.ide.engine.DevSnipToolWindowPanel
import pro.devsnip.api.core.toolkit.BackendApiTools

/**
 * The DevSnip Pro tool window: the extension's Tools sidebar (in the IDE's
 * embedded browser). Without JCEF it falls back to native tabs for the REST
 * API Client and the natively ported Backend & API tools.
 */
class ApiToolWindowFactory : ToolWindowFactory, DumbAware {
    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val factory = ContentFactory.getInstance()
        if (JBCefApp.isSupported()) {
            // The DevSnip Pro sidebar from the extension itself, as in VS Code; tools open as editor tabs.
            val content = factory.createContent(null, "", false)
            val panel = DevSnipToolWindowPanel(project, content)
            content.component = panel
            toolWindow.contentManager.addContent(content)
            // The extension refreshes the sidebar's points and level when it becomes visible.
            project.messageBus.connect(content).subscribe(com.intellij.openapi.wm.ex.ToolWindowManagerListener.TOPIC, object : com.intellij.openapi.wm.ex.ToolWindowManagerListener {
                override fun stateChanged(toolWindowManager: com.intellij.openapi.wm.ToolWindowManager) {
                    val window = toolWindowManager.getToolWindow(TOOL_WINDOW_ID) ?: return
                    panel.view?.setVisible(window.isVisible)
                }
            })
            return
        }
        // Fallback for IDEs without the embedded browser: the natively ported tools as tabs.
        val client = ApiClientPanel(project)
        factory.createContent(client, REST_CLIENT_TAB, false).also {
            Disposer.register(it, client)
            toolWindow.contentManager.addContent(it)
        }
        for (tool in BackendApiTools.all) {
            val panel = ToolPanel(project, tool)
            factory.createContent(panel, TAB_NAMES[tool.id] ?: tool.title, false).also {
                it.description = tool.title
                Disposer.register(it, panel)
                toolWindow.contentManager.addContent(it)
            }
        }
    }

    companion object {
        const val TOOL_WINDOW_ID = "DevSnip Pro"
        const val REST_CLIENT_TAB = "REST Client"

        /** Short tab names; the full title is the tab tooltip. */
        val TAB_NAMES = mapOf(
            "web.curl" to "cURL",
            "web.api-response" to "Response",
            "web.url" to "URL",
            "web.cors" to "CORS",
            "web.openapi" to "OpenAPI",
            "web.graphql" to "GraphQL",
            "web.api-scaffold" to "Scaffold",
        )

        fun open(project: Project, tab: String) {
            val window = ToolWindowManager.getInstance(project).getToolWindow(TOOL_WINDOW_ID) ?: return
            window.activate {
                val manager = window.contentManager
                manager.findContent(tab)?.let { manager.setSelectedContent(it, true) }
            }
        }
    }
}
