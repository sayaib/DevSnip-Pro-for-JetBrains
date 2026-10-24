package pro.devsnip.api.ide.web

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.intellij.ide.actions.SearchEverywhereAction
import com.intellij.ide.ui.LafManagerListener
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.editor.colors.EditorColorsListener
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.startup.ProjectActivity
import com.intellij.openapi.ui.SimpleToolWindowPanel
import com.intellij.openapi.wm.ToolWindowManager
import java.awt.BorderLayout
import javax.swing.JPanel

/**
 * The DevSnip Pro Tools sidebar: the VS Code extension's own sidebar page
 * (sections, search, filters, favourites, recents, theme picker) in the
 * DevSnip Pro tool window. Its messages are handled as the extension's
 * ToolsSidebarProvider handles them.
 */
class DevSnipSidebar(private val project: Project, parent: Disposable) : JPanel(BorderLayout()) {
    private val browser = DevSnipBrowser(parent, "sidebar", ::onMessage)

    init {
        add(browser.component, BorderLayout.CENTER)
        reload()
    }

    private fun reload() {
        val host = JsonObject().apply { addProperty("page", "sidebar") }
        browser.load(WebviewAssets.sidebarHtml(DevSnipUiState.getInstance(), host, browser.queryFunction))
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

/** Runs a DevSnip Pro command, from the sidebar, a menu or Find Action. */
object DevSnipCommands {
    fun run(project: Project, command: String, initial: JsonObject? = null) {
        if (!com.intellij.ui.jcef.JBCefApp.isSupported() && command != WebviewAssets.REST_CLIENT_COMMAND) {
            // Without the embedded browser only the natively ported tools can open (in the tool window).
            val toolId = WebviewAssets.toolByCommand[command]
            val native = pro.devsnip.api.core.toolkit.BackendApiTools.all.firstOrNull { it.id == toolId }
            if (native != null) {
                pro.devsnip.api.ide.ui.ApiToolWindowFactory.open(project, pro.devsnip.api.ide.ui.ApiToolWindowFactory.TAB_NAMES[native.id] ?: native.title)
            } else {
                com.intellij.openapi.ui.Messages.showInfoMessage(project, "This DevSnip Pro tool needs the IDE's embedded browser (JCEF), which is not available in this IDE.", "DevSnip Pro")
                return
            }
            DevSnipUiState.getInstance().recordUsage(command)
            return
        }
        when {
            command == WebviewAssets.REST_CLIENT_COMMAND -> DevSnipPages.openRestClient(project)
            command in WebviewAssets.toolByCommand -> DevSnipPages.openTool(project, WebviewAssets.toolByCommand.getValue(command), initial)
            else -> return
        }
        DevSnipUiState.getInstance().recordUsage(command)
        DevSnipSidebar.OPEN.forEach { it.pushUsage() }
    }

    fun showSidebar(project: Project) {
        ToolWindowManager.getInstance(project).getToolWindow("DevSnip Pro")?.activate(null)
    }
}

/** Find Action / Search Everywhere and Tools menu entries for every tool, like the command palette in VS Code. */
class DevSnipActionsRegistrar : ProjectActivity {
    override suspend fun execute(project: Project) {
        EditorTracker.install(project)
        ApplicationManager.getApplication().invokeLater { registerOnce() }
    }

    companion object {
        @Volatile
        private var registered = false

        fun registerOnce() {
            if (registered) return
            registered = true
            val manager = ActionManager.getInstance()
            val menu = manager.getAction("DevSnipPro.ToolsMenu") as? DefaultActionGroup ?: return
            var section: String? = null
            var sectionGroup: DefaultActionGroup? = null
            for ((command, label, sectionTitle) in WebviewAssets.entries) {
                if (command !in WebviewAssets.supportedCommands) continue
                val id = "DevSnipPro." + command.removePrefix(WebviewAssets.COMMAND_PREFIX)
                val action = manager.getAction(id) ?: object : DumbAwareAction("DevSnip Pro: $label", null, DevSnipIcons.TOOL) {
                    override fun actionPerformed(e: AnActionEvent) {
                        e.project?.let { DevSnipCommands.run(it, command) }
                    }

                    override fun update(e: AnActionEvent) {
                        e.presentation.isEnabled = e.project != null
                    }

                    override fun getActionUpdateThread() = ActionUpdateThread.BGT
                }.also { manager.registerAction(id, it) }
                if (sectionTitle != section) {
                    section = sectionTitle
                    sectionGroup = DefaultActionGroup(sectionTitle, true)
                    menu.add(sectionGroup)
                }
                sectionGroup?.add(action)
            }
        }
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

    override fun getActionUpdateThread() = ActionUpdateThread.BGT
}
