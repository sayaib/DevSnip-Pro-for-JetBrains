package pro.devsnip.api.ide.ui

import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import javax.swing.JButton
import javax.swing.JPanel
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.ui.content.ContentFactory
import com.intellij.ui.jcef.JBCefApp
import pro.devsnip.api.ide.engine.DevSnipToolWindowPanel

/** The full DevSnip Pro interface, backed by the required embedded browser. */
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
        // A required dependency can still be unavailable because of runtime configuration.
        // Explain recovery instead of presenting a different, reduced product interface.
        val recovery = JPanel(BorderLayout(0, JBUI.scale(16))).apply {
            border = JBUI.Borders.empty(20)
            add(JBLabel("<html><h2>DevSnip Pro needs its embedded browser</h2>" +
                "The full interface could not start because JCEF is unavailable.<br><br>" +
                "Enable Web Browser (JCEF) in Plugins, then restart the IDE.<br>" +
                "If it is unavailable, update to an IDE version compatible with that plugin.<br>" +
                "If it is already enabled, check the IDE log for JCEF startup errors.</html>"), BorderLayout.NORTH)
            add(JButton("Open Plugins Settings").apply {
                addActionListener { ShowSettingsUtil.getInstance().showSettingsDialog(project, "Plugins") }
            }, BorderLayout.SOUTH)
        }
        toolWindow.contentManager.addContent(factory.createContent(recovery, "", false))
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
