package pro.devsnip.api.ide.engine

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.intellij.notification.Notification
import com.intellij.notification.NotificationType
import com.intellij.notification.Notifications
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.IdeActions
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.command.undo.UndoManager
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.wm.ToolWindowId
import com.intellij.openapi.wm.ToolWindowManager
import pro.devsnip.api.ide.web.DevSnipBrowser
import pro.devsnip.api.ide.web.DevSnipPages
import pro.devsnip.api.ide.web.DevSnipUiState
import pro.devsnip.api.ide.web.EditorTracker
import pro.devsnip.api.ide.web.ScannerHost
import pro.devsnip.api.ide.web.WebviewAssets
import java.nio.file.Path

/**
 * What the IDE runs itself: the toolkit tools (aliases included), the REST
 * API Client and the OSV / SCA scanners open in their own pages; and VS Code
 * commands the extension calls (save, undo, open with, ...) done the IDE way.
 */
object DevSnipNative {
    private const val PREFIX = WebviewAssets.COMMAND_PREFIX
    private val LOG = com.intellij.openapi.diagnostic.Logger.getInstance(DevSnipNative::class.java)

    /** Toolkit command → its tool and preset values (aliases such as urlEncoder open a tool in a mode). */
    private data class ToolCommand(val tool: String, val values: JsonObject?)

    private val toolCommands: Map<String, ToolCommand> by lazy {
        val text = DevSnipNative::class.java.getResourceAsStream("/webview/tool-commands.json")!!.use { String(it.readBytes(), Charsets.UTF_8) }
        JsonParser.parseString(text).asJsonArray.associate { e ->
            val o = e.asJsonObject
            o["command"].asString to ToolCommand(o["tool"].asString, o["values"]?.takeIf { it.isJsonObject }?.asJsonObject)
        }
    }

    private val scannerCommands = mapOf(
        "${PREFIX}osvScanner" to ("osv" to false), "${PREFIX}osvScanWorkspace" to ("osv" to true), "${PREFIX}osvScanFile" to ("osv" to true),
        "${PREFIX}scaScanner" to ("sca" to false), "${PREFIX}scaScanWorkspace" to ("sca" to true),
    )

    /** Every command the IDE's pages run. */
    val commands: List<String> by lazy { toolCommands.keys.toList() + WebviewAssets.REST_CLIENT_COMMAND + scannerCommands.keys }

    fun isNative(command: String) = command in toolCommands || command == WebviewAssets.REST_CLIENT_COMMAND || command in scannerCommands

    /** Opens a native page (EDT). `args` as VS Code passed them: a file Uri from a context menu, the explorer's selection. */
    fun run(project: Project, command: String, args: JsonArray) {
        if (project.isDisposed) return
        LOG.debug("DevSnip Pro: opening $command")
        when {
            command == WebviewAssets.REST_CLIENT_COMMAND -> DevSnipPages.openRestClient(project)
            command in toolCommands -> {
                val entry = toolCommands.getValue(command)
                val initial = JsonObject()
                entry.values?.entrySet()?.forEach { (k, v) -> initial.add(k, v) }
                // Run from the editor's context menu (VS Code passes the file's Uri): the selection, or the whole file, is the tool's input.
                if (args.firstOrNull()?.let(::uriPath) != null) fromEditor(project, entry.tool)?.entrySet()?.forEach { (k, v) -> initial.add(k, v) }
                DevSnipPages.openTool(project, entry.tool, initial.takeIf { it.size() > 0 })
            }
            command in scannerCommands -> {
                val (page, run) = scannerCommands.getValue(command)
                val files = if (command.endsWith("osvScanFile")) scanTargets(project, args) else emptyList()
                DevSnipPages.openScanner(project, page, run = run, files = files)
            }
        }
    }

    /** The files osvScanFile scans: the explorer selection, the clicked file, or the active editor's file. */
    private fun scanTargets(project: Project, args: JsonArray): List<Path> {
        val selection = args.getOrNull(1)?.takeIf { it.isJsonArray }?.asJsonArray?.mapNotNull(::uriPath).orEmpty()
        if (selection.isNotEmpty()) return selection
        args.getOrNull(0)?.let(::uriPath)?.let { return listOf(it) }
        val file = FileEditorManager.getInstance(project).selectedTextEditor?.virtualFile ?: return emptyList()
        return if (file.isInLocalFileSystem) listOf(ScannerHost.normalize(Path.of(file.path))) else emptyList()
    }

    private fun JsonArray.getOrNull(index: Int): JsonElement? = if (index < size()) get(index) else null

    private fun uriPath(element: JsonElement): Path? {
        val o = element.takeIf { it.isJsonObject }?.asJsonObject ?: return null
        val raw = o["fsPath"]?.takeIf { it.isJsonPrimitive }?.asString ?: return null
        return runCatching { ScannerHost.pathOf(raw) }.getOrNull()
    }

    /** runner.ts initialFromInvocation: the field marked fromEditor gets the selection (or the file), and its mode is switched on. */
    fun fromEditor(project: Project, toolId: String): JsonObject? {
        val editor = FileEditorManager.getInstance(project).selectedTextEditor ?: EditorTracker.lastEditor(project) ?: return null
        val fields = WebviewAssets.tools.getAsJsonObject(toolId)?.getAsJsonObject("data")?.getAsJsonObject("tool")?.getAsJsonArray("fields") ?: return null
        val field = fields.map { it.asJsonObject }.firstOrNull { it["fromEditor"]?.asBoolean == true } ?: return null
        val text = editor.selectionModel.selectedText?.takeIf { it.isNotEmpty() } ?: editor.document.text
        if (text.length > 2 * 1024 * 1024) return null
        return JsonObject().apply {
            addProperty(field["id"].asString, text)
            field.getAsJsonObject("showIf")?.let { showIf -> showIf.getAsJsonArray("equals")?.firstOrNull()?.let { add(showIf["field"].asString, it) } }
        }
    }

    /** VS Code commands the extension runs that the IDE does (EDT). */
    fun ideCommand(project: Project, id: String, args: JsonArray) {
        when (id) {
            "workbench.action.files.save" -> EngineCustomEditor.selected(project)?.save() ?: FileDocumentManager.getInstance().saveAllDocuments()
            "workbench.action.files.revert" -> EngineCustomEditor.selected(project)?.revert()
            "undo", "redo" -> {
                val editor = FileEditorManager.getInstance(project).selectedEditor
                val undo = UndoManager.getInstance(project)
                if (id == "undo" && undo.isUndoAvailable(editor)) undo.undo(editor) else if (id == "redo" && undo.isRedoAvailable(editor)) undo.redo(editor)
            }
            "vscode.openWith" -> {
                val path = args.getOrNull(0)?.let(::uriPath) ?: return
                val file = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(path) ?: return
                val viewType = args.getOrNull(1)?.takeIf { it.isJsonPrimitive }?.asString ?: "default"
                val manager = FileEditorManager.getInstance(project)
                OpenFileDescriptor(project, file).navigate(true)
                manager.setSelectedEditor(file, if (viewType == "default") "text-editor" else viewType)
            }
            "workbench.action.files.openFolder" -> action(project, "OpenFile")
            "workbench.view.explorer" -> ToolWindowManager.getInstance(project).getToolWindow(ToolWindowId.PROJECT_VIEW)?.activate(null)
            "editor.action.formatDocument" -> action(project, IdeActions.ACTION_EDITOR_REFORMAT)
            "workbench.action.closeActiveEditor" -> FileEditorManager.getInstance(project).selectedEditor?.file?.let { FileEditorManager.getInstance(project).closeFile(it) }
            else -> when {
                isNative(id) -> run(project, id, args)
                id.startsWith(PREFIX) -> DevSnipCommands.run(project, id, args)
            }
        }
    }

    private fun action(project: Project, id: String) {
        val action = ActionManager.getInstance().getAction(id) ?: return
        val component = FileEditorManager.getInstance(project).selectedEditor?.component
        ActionManager.getInstance().tryToExecute(action, null, component, "DevSnipPro", true)
    }

    /** The extension counted a command run: the IDE's own sidebar fallback keeps the same "Recently used". */
    fun usageRecorded(project: Project, command: String) {
        if (command.startsWith(PREFIX)) DevSnipUiState.getInstance().recordUsage(command)
    }

    /** The theme the extension applies (a choice or a locked-theme preview): the IDE's own pages follow it. */
    @Volatile
    var liveTheme: String? = null
        private set

    fun themeApplied(id: String) {
        if (id.isEmpty() || liveTheme == id) return
        liveTheme = id
        DevSnipBrowser.OPEN.filter { it.native }.forEach { it.applyDevSnipTheme(id) }
    }

    /** The points balance changed: the REST API Client's prices and balance follow it. */
    @Volatile
    var points: Int = 0
        private set

    fun pointsChanged(balance: Int) {
        points = balance
        DevSnipBrowser.OPEN.filter { it.native }.forEach { browser ->
            browser.post(JsonObject().apply { addProperty("type", "__points"); addProperty("balance", balance) })
        }
    }

    fun notifyError(project: Project, text: String) {
        Notifications.Bus.notify(Notification(EngineHost.NOTIFICATIONS, "DevSnip Pro", text.replace("<", "&lt;"), NotificationType.ERROR), project)
    }
}

/** Runs a DevSnip Pro command: in the extension host when it can run (points, quests and recents count it), else natively. */
object DevSnipCommands {
    fun run(project: Project, command: String, args: JsonArray = JsonArray()) {
        if (project.isDisposed) return
        if (DevSnipEngine.isSupported()) {
            val engine = DevSnipEngine.getInstance(project)
            engine.execute(command, args).thenAccept { error ->
                if (error == null) return@thenAccept
                ApplicationManager.getApplication().invokeLater({
                    // Without a running extension host, the IDE's own pages still open (they just earn no points).
                    if (engine.status == DevSnipEngine.Status.FAILED && DevSnipNative.isNative(command)) DevSnipNative.run(project, command, args)
                    else DevSnipNative.notifyError(project, "DevSnip Pro: $error")
                }, project.disposed)
            }
            return
        }
        runWithoutBrowser(project, command)
    }

    /** IDEs without the embedded browser: the natively ported Backend & API tools and the REST client, in the tool window. */
    private fun runWithoutBrowser(project: Project, command: String) {
        if (command == WebviewAssets.REST_CLIENT_COMMAND) {
            DevSnipPages.openRestClient(project)
            return
        }
        val toolId = WebviewAssets.toolByCommand[command]
        val native = pro.devsnip.api.core.toolkit.BackendApiTools.all.firstOrNull { it.id == toolId }
        if (native != null) {
            pro.devsnip.api.ide.ui.ApiToolWindowFactory.open(project, pro.devsnip.api.ide.ui.ApiToolWindowFactory.TAB_NAMES[native.id] ?: native.title)
            DevSnipUiState.getInstance().recordUsage(command)
        } else {
            com.intellij.openapi.ui.Messages.showInfoMessage(project, "This DevSnip Pro tool needs the IDE's embedded browser (JCEF), which is not available in this IDE.", "DevSnip Pro")
        }
    }

    fun showSidebar(project: Project) {
        ToolWindowManager.getInstance(project).getToolWindow(pro.devsnip.api.ide.ui.ApiToolWindowFactory.TOOL_WINDOW_ID)?.activate(null)
    }

    fun openPath(project: Project, path: Path) {
        LocalFileSystem.getInstance().refreshAndFindFileByNioFile(path)?.let { OpenFileDescriptor(project, it).navigate(true) }
    }
}
