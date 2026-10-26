package pro.devsnip.api.ide.engine

import com.google.gson.JsonObject
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.command.CommandProcessor
import com.intellij.openapi.command.undo.BasicUndoableAction
import com.intellij.openapi.command.undo.DocumentReferenceManager
import com.intellij.openapi.command.undo.UndoManager
import com.intellij.openapi.fileEditor.FileDocumentManagerListener
import com.intellij.openapi.fileEditor.FileEditor
import com.intellij.openapi.fileEditor.FileEditorLocation
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.FileEditorPolicy
import com.intellij.openapi.fileEditor.FileEditorProvider
import com.intellij.openapi.fileEditor.FileEditorState
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.util.UserDataHolderBase
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.JBUI
import pro.devsnip.api.ide.web.DevSnipBrowser
import pro.devsnip.api.ide.web.ScannerHost
import java.awt.BorderLayout
import java.beans.PropertyChangeListener
import java.beans.PropertyChangeSupport
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArraySet
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.SwingConstants

/**
 * The extension's custom editors (package.json `customEditors`): the Excel
 * viewer opens .xlsx / .xlsm files, and the CSV & TSV viewer is a "Table" tab
 * next to the text of .csv / .tsv / .tab files. The extension's provider
 * (spreadsheet/viewer.ts) owns the document; edits go on the IDE's undo
 * stack, Save writes the file, and an unsaved table is offered for saving
 * when its tab closes.
 */
class EngineCustomEditor(private val project: Project, private val file: VirtualFile, val viewType: String, title: String) : UserDataHolderBase(), FileEditor, EnginePanels.View {
    private val engine = DevSnipEngine.getInstance(project)
    private val panel = JPanel(BorderLayout())
    private val browser = DevSnipBrowser(this, "panel", native = false) { message ->
        engine.post(JsonObject().apply {
            addProperty("type", "__panelMessage")
            addProperty("panel", panelId)
            add("message", message)
        })
    }
    private val changes = PropertyChangeSupport(this)
    private val name = title
    override val panelId: String = "custom-" + java.util.UUID.randomUUID().toString().take(8)

    @Volatile
    private var dirty = false
    private var opened = false

    init {
        OPEN.add(this)
        panel.add(browser.component, BorderLayout.CENTER)
        engine.panels.register(this)
        engine.whenReady { ok ->
            ApplicationManager.getApplication().invokeLater({
                if (!ok) failed(engine.failure ?: "DevSnip Pro could not start.") else open()
            }, project.disposed)
        }
    }

    private fun open() {
        if (opened) return
        opened = true
        engine.post(JsonObject().apply {
            addProperty("type", "__openCustomEditor")
            addProperty("viewType", viewType)
            addProperty("path", ScannerHost.canonical(ScannerHost.normalize(Path.of(file.path))))
            addProperty("panel", panelId)
        })
    }

    override fun setHtml(html: String) {
        browser.load(EnginePages.write(html, viewType, "panel", browser.queryFunction, browser.instance))
    }

    override fun post(message: JsonObject) = browser.post(message)

    /** Diagnostics: evaluates an expression in the page. */
    fun probe(expression: String) = browser.probe(expression)

    override fun failed(error: String) {
        panel.removeAll()
        panel.add(JBLabel("<html><div style='text-align:center'>${error.replace("<", "&lt;")}<br><br>Open the file as text instead (right-click the tab).</div></html>", SwingConstants.CENTER).apply { border = JBUI.Borders.empty(24) })
        panel.revalidate()
        panel.repaint()
    }

    /** An edit in the table: on the IDE's undo stack (Undo / Redo call the extension's undo and redo). */
    override fun customEdit(edit: Int, label: String) {
        setDirty(true)
        val reference = DocumentReferenceManager.getInstance().create(file)
        CommandProcessor.getInstance().executeCommand(project, {
            UndoManager.getInstance(project).undoableActionPerformed(object : BasicUndoableAction(reference) {
                override fun undo() = engine.post(JsonObject().apply { addProperty("type", "__customUndo"); addProperty("edit", edit) })
                override fun redo() = engine.post(JsonObject().apply { addProperty("type", "__customUndo"); addProperty("edit", edit); addProperty("redo", true) })
            })
        }, "DevSnip Pro: $label", null)
    }

    override fun customDirty() = setDirty(true)

    private fun setDirty(value: Boolean) {
        if (dirty == value) return
        dirty = value
        changes.firePropertyChange(FileEditor.PROP_MODIFIED, !value, value)
    }

    /** Save (the page's button, Save All, or closing with unsaved edits); completes with whether it worked. */
    fun save(): java.util.concurrent.CompletableFuture<Boolean> = command("save")

    fun revert(): java.util.concurrent.CompletableFuture<Boolean> = command("revert")

    /** Asks the extension's provider; never waits on the UI thread (the provider may show dialogs). */
    private fun command(name: String): java.util.concurrent.CompletableFuture<Boolean> =
        engine.request("__customCommand") {
            addProperty("command", name)
            addProperty("panel", panelId)
        }.thenApply { result ->
            val ok = result.get("ok")?.asBoolean == true
            ApplicationManager.getApplication().invokeLater({
                if (ok) setDirty(false)
                else result.get("error")?.takeIf { it.isJsonPrimitive }?.asString?.let { Messages.showErrorDialog(project, it, "DevSnip Pro") }
            }, project.disposed)
            ok
        }

    override fun getComponent(): JComponent = panel
    override fun getPreferredFocusedComponent(): JComponent = browser.component
    override fun getName() = name
    override fun getFile() = file
    override fun setState(state: FileEditorState) = Unit
    override fun isModified() = dirty
    override fun isValid() = file.isValid
    override fun getCurrentLocation(): FileEditorLocation? = null
    override fun addPropertyChangeListener(listener: PropertyChangeListener) = changes.addPropertyChangeListener(listener)
    override fun removePropertyChangeListener(listener: PropertyChangeListener) = changes.removePropertyChangeListener(listener)

    override fun selectNotify() = engine.post(JsonObject().apply { addProperty("type", "__panelState"); addProperty("panel", panelId); addProperty("visible", true); addProperty("active", true) })
    override fun deselectNotify() = engine.post(JsonObject().apply { addProperty("type", "__panelState"); addProperty("panel", panelId); addProperty("visible", false); addProperty("active", false) })

    override fun dispose() {
        OPEN.remove(this)
        engine.panels.unregister(panelId)
        val closed = { engine.post(JsonObject().apply { addProperty("type", "__panelClosed"); addProperty("panel", panelId) }) }
        if (dirty && !project.isDisposed) {
            val choice = Messages.showYesNoDialog(project, "Save your changes to ${file.name}?", "DevSnip Pro", "Save", "Don't Save", Messages.getQuestionIcon())
            // The document stays open in the extension until the save has finished.
            if (choice == Messages.YES) {
                save().whenComplete { _, _ -> closed() }
                return
            }
        }
        closed()
    }

    companion object {
        val OPEN = CopyOnWriteArraySet<EngineCustomEditor>()

        /** The custom editor that is selected in a project, for the page's own Save, Revert, Undo and Redo. */
        fun selected(project: Project): EngineCustomEditor? =
            FileEditorManager.getInstance(project).selectedEditor as? EngineCustomEditor
    }
}

private fun spreadsheetFile(file: VirtualFile, extensions: Set<String>) =
    file.isInLocalFileSystem && !file.isDirectory && file.extension?.lowercase() in extensions && DevSnipEngine.isSupported()

/** Excel workbooks open in the viewer (the IDE has no editor for them). */
class ExcelViewerProvider : FileEditorProvider, DumbAware {
    override fun accept(project: Project, file: VirtualFile) = spreadsheetFile(file, setOf("xlsx", "xlsm"))
    override fun createEditor(project: Project, file: VirtualFile): FileEditor = EngineCustomEditor(project, file, "devsnip.spreadsheetViewer", "Spreadsheet")
    override fun getEditorTypeId() = "devsnip.spreadsheetViewer"
    override fun getPolicy() = FileEditorPolicy.HIDE_DEFAULT_EDITOR
}

/** CSV and TSV files get a "Table" tab after their text, as VS Code offers the viewer with "Open With". */
class CsvViewerProvider : FileEditorProvider, DumbAware {
    override fun accept(project: Project, file: VirtualFile) = spreadsheetFile(file, setOf("csv", "tsv", "tab"))
    override fun createEditor(project: Project, file: VirtualFile): FileEditor = EngineCustomEditor(project, file, "devsnip.csvViewer", "Table")
    override fun getEditorTypeId() = "devsnip.csvViewer"
    override fun getPolicy() = FileEditorPolicy.PLACE_AFTER_DEFAULT_EDITOR
}

/** Save All also saves edited tables. */
class CustomEditorSaver : FileDocumentManagerListener {
    override fun beforeAllDocumentsSaving() {
        EngineCustomEditor.OPEN.filter { it.isModified }.forEach { editor -> ApplicationManager.getApplication().invokeLater { editor.save() } }
    }
}
