package pro.devsnip.api.ide.ui

import com.intellij.openapi.actionSystem.ActionGroup
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowFactory
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.ui.content.ContentFactory
import pro.devsnip.api.core.toolkit.BackendApiTools

/**
 * The DevSnip Pro tool window: the "Backend & API" section of the VS Code
 * sidebar, one tab per tool, starting with the REST API Client.
 */
class ApiToolWindowFactory : ToolWindowFactory, DumbAware {
    override fun createToolWindowContent(project: Project, toolWindow: ToolWindow) {
        val factory = ContentFactory.getInstance()
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

/** Tools | DevSnip Pro: Backend & API: opens any of the tools. */
class BackendApiActionGroup : ActionGroup(), DumbAware {
    private val children: Array<AnAction> by lazy {
        val entries = listOf(ApiToolWindowFactory.REST_CLIENT_TAB to "REST API Client") +
            BackendApiTools.all.map { (ApiToolWindowFactory.TAB_NAMES[it.id] ?: it.title) to it.title }
        entries.map { (tab, title) ->
            object : DumbAwareAction(title) {
                override fun actionPerformed(e: AnActionEvent) {
                    e.project?.let { ApiToolWindowFactory.open(it, tab) }
                }

                override fun update(e: AnActionEvent) {
                    e.presentation.isEnabled = e.project != null
                }

                override fun getActionUpdateThread() = ActionUpdateThread.BGT
            }
        }.toTypedArray()
    }

    override fun getChildren(e: AnActionEvent?): Array<AnAction> = children

    override fun getActionUpdateThread() = ActionUpdateThread.BGT
}
