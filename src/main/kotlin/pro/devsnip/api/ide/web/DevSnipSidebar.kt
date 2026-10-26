package pro.devsnip.api.ide.web

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.intellij.ide.actions.SearchEverywhereAction
import pro.devsnip.api.ide.engine.DevSnipCommands
import com.intellij.ide.ui.LafManagerListener
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.editor.colors.EditorColorsListener
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.SimpleToolWindowPanel
import java.awt.BorderLayout
import javax.swing.JPanel

/**
 * The DevSnip Pro Tools sidebar: the VS Code extension's own sidebar page
 * (sections, search, filters, favourites, recents, theme picker) in the
 * DevSnip Pro tool window. Its messages are handled as the extension's
 * ToolsSidebarProvider handles them.
 */
class DevSnipSidebar(private val project: Project, parent: Disposable) : JPanel(BorderLayout()) {
    private val browser = DevSnipBrowser(parent, "sidebar", onMessage = ::onMessage)

    init {
        add(browser.component, BorderLayout.CENTER)
        reload()
    }

    private fun reload() {
        val host = JsonObject().apply { addProperty("page", "sidebar") }
        browser.load(WebviewAssets.sidebarHtml(DevSnipUiState.getInstance(), host, browser.queryFunction, browser.instance))
    }

    private fun onMessage(message: JsonObject) {
        val state = DevSnipUiState.getInstance()
        when (message["type"]?.asString) {
            "run" -> message["command"]?.asString?.let { DevSnipCommands.run(project, it) }
            "expanded" -> state.expanded = (message["groups"] as? JsonArray)?.mapNotNull { it.takeIf { e -> e.isJsonPrimitive }?.asString }.orEmpty()
            "favorites" -> state.favorites = (message["favorites"] as? JsonArray)?.mapNotNull { it.takeIf { e -> e.isJsonPrimitive }?.asString }?.filter { it in WebviewAssets.supportedCommands }.orEmpty()
            "setTheme", "previewTheme" -> message["id"]?.asString?.takeIf { it in WebviewAssets.themeIds() }?.let { id ->
                state.theme = id
                DevSnipBrowser.broadcast { it.applyDevSnipTheme(id) }
            }
            // Search every tool: the IDE's own search (Find Action lists every DevSnip Pro tool).
            "searchAll" -> {
                val action = ActionManager.getInstance().getAction("SearchEverywhere") as? SearchEverywhereAction ?: return
                ActionManager.getInstance().tryToExecute(action, null, this, "DevSnipSidebar", true)
            }
        }
    }

    /** After a tool runs from anywhere, Recently used and Most used update (recordUsage in the extension). */
    fun pushUsage() = browser.post(JsonObject().apply {
        addProperty("type", "usage")
        add("usage", WebviewAssets.usage(DevSnipUiState.getInstance()))
    })

    companion object {
        val OPEN = java.util.concurrent.CopyOnWriteArraySet<DevSnipSidebar>()
    }

    init {
        OPEN.add(this)
        com.intellij.openapi.util.Disposer.register(parent) { OPEN.remove(this) }
    }
}

/** Keeps every open page in step with the IDE theme and editor colour scheme. */
class DevSnipThemeListener : LafManagerListener, EditorColorsListener {
    override fun lookAndFeelChanged(source: com.intellij.ide.ui.LafManager) = refresh()
    override fun globalSchemeChange(scheme: com.intellij.openapi.editor.colors.EditorColorsScheme?) = refresh()

    private fun refresh() {
        ApplicationManager.getApplication().invokeLater { DevSnipBrowser.broadcast { it.refreshIdeTheme() } }
    }
}

/** Wraps the sidebar for the tool window (kept as a class so tests can build it). */
class DevSnipSidebarPanel(project: Project, parent: Disposable) : SimpleToolWindowPanel(true, true) {
    init {
        setContent(DevSnipSidebar(project, parent))
    }
}

class ShowSidebarAction : DumbAwareAction() {
    override fun actionPerformed(e: AnActionEvent) {
        e.project?.let(DevSnipCommands::showSidebar)
    }

    override fun getActionUpdateThread() = com.intellij.openapi.actionSystem.ActionUpdateThread.BGT
}
