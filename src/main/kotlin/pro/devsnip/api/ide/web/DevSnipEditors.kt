package pro.devsnip.api.ide.web

import com.google.gson.JsonObject
import com.intellij.openapi.Disposable
import com.intellij.openapi.fileEditor.FileEditor
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.FileEditorPolicy
import com.intellij.openapi.fileEditor.FileEditorProvider
import com.intellij.openapi.fileEditor.FileEditorState
import com.intellij.openapi.fileTypes.FileType
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.UserDataHolderBase
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.testFramework.LightVirtualFile
import pro.devsnip.api.ide.ui.ApiClientPanel
import java.beans.PropertyChangeListener
import javax.swing.Icon
import javax.swing.JComponent

/** Marks DevSnip Pro pages opened as editor tabs (VS Code opens tools as webview panels). */
object DevSnipFileType : FileType {
    override fun getName() = "DevSnip Pro"
    override fun getDescription() = "DevSnip Pro tool"
    override fun getDefaultExtension() = ""
    override fun getIcon(): Icon = DevSnipIcons.TOOL
    override fun isBinary() = true
    override fun isReadOnly() = true
}

/** A DevSnip Pro page: a toolkit tool, or the REST API Client. */
class DevSnipVirtualFile(val kind: Kind, val toolId: String, title: String) : LightVirtualFile(title, DevSnipFileType, "") {
    enum class Kind { TOOL, REST_CLIENT }

    /** Field values to start with (from "Run on selection" or an alias). */
    var initial: JsonObject = JsonObject()

    init {
        isWritable = false
    }

    override fun getPath() = "devsnip://${kind.name.lowercase()}/$toolId"
}

object DevSnipPages {
    /** Opens (or focuses) a tool's tab, as VS Code reveals an existing panel. */
    fun openTool(project: Project, toolId: String, initial: JsonObject? = null) {
        val manager = FileEditorManager.getInstance(project)
        val existing = manager.openFiles.filterIsInstance<DevSnipVirtualFile>().firstOrNull { it.kind == DevSnipVirtualFile.Kind.TOOL && it.toolId == toolId }
        if (existing != null) {
            manager.openFile(existing, true)
            if (initial != null && initial.size() > 0) {
                manager.getEditors(existing).filterIsInstance<ToolPageEditor>().firstOrNull()?.setValues(initial)
            }
            return
        }
        val file = DevSnipVirtualFile(DevSnipVirtualFile.Kind.TOOL, toolId, WebviewAssets.toolTitle(toolId))
        if (initial != null) file.initial = initial
        manager.openFile(file, true)
    }

    fun openRestClient(project: Project) {
        val manager = FileEditorManager.getInstance(project)
        val existing = manager.openFiles.filterIsInstance<DevSnipVirtualFile>().firstOrNull { it.kind == DevSnipVirtualFile.Kind.REST_CLIENT }
        manager.openFile(existing ?: DevSnipVirtualFile(DevSnipVirtualFile.Kind.REST_CLIENT, "rest-client", "REST API Client"), true)
    }
}

class DevSnipEditorProvider : FileEditorProvider, DumbAware {
    override fun accept(project: Project, file: VirtualFile) = file is DevSnipVirtualFile

    override fun createEditor(project: Project, file: VirtualFile): FileEditor {
        val page = file as DevSnipVirtualFile
        return when (page.kind) {
            DevSnipVirtualFile.Kind.TOOL -> ToolPageEditor(project, page)
            DevSnipVirtualFile.Kind.REST_CLIENT -> PanelEditor(page, "REST API Client") { parent -> ApiClientPanel(project).also { Disposer.register(parent, it) } }
        }
    }

    override fun getEditorTypeId() = "devsnip-pro-page"
    override fun getPolicy() = FileEditorPolicy.HIDE_DEFAULT_EDITOR
}

abstract class DevSnipEditorBase(private val file: VirtualFile, private val title: String) : UserDataHolderBase(), FileEditor {
    override fun getName() = title
    override fun getFile() = file
    override fun setState(state: FileEditorState) = Unit
    override fun isModified() = false
    override fun isValid() = true
    override fun addPropertyChangeListener(listener: PropertyChangeListener) = Unit
    override fun removePropertyChangeListener(listener: PropertyChangeListener) = Unit
}

/** A toolkit tool: the extension's own tool page in the browser. */
class ToolPageEditor(private val project: Project, private val file: DevSnipVirtualFile) : DevSnipEditorBase(file, WebviewAssets.toolTitle(file.toolId)) {
    private val browser: DevSnipBrowser = DevSnipBrowser(this, "panel") { message -> host.handle(message) }
    private val host: ToolHost = ToolHost(project, file.toolId) { browser.post(it) }

    init {
        val state = DevSnipUiState.getInstance()
        val hostInfo = JsonObject().apply {
            addProperty("page", "tool")
            addProperty("toolId", file.toolId)
            add("storage", state.toolStorage())
            project.basePath?.let { addProperty("workspaceName", java.io.File(it).name) }
            add("networkInterfaces", networkInterfaces())
            addProperty("platform", if (com.intellij.openapi.util.SystemInfo.isMac) "darwin" else if (com.intellij.openapi.util.SystemInfo.isWindows) "win32" else "linux")
        }
        browser.load(WebviewAssets.toolHtml(file.toolId, file.initial, state, hostInfo, browser.queryFunction))
    }

    fun setValues(values: JsonObject) = browser.post(JsonObject().apply {
        addProperty("type", "setValues")
        add("values", values)
        addProperty("run", true)
    })

    override fun getComponent(): JComponent = browser.component
    override fun getPreferredFocusedComponent(): JComponent = browser.component
    override fun dispose() = Unit

    private fun networkInterfaces(): JsonObject {
        val out = JsonObject()
        try {
            for (nic in java.net.NetworkInterface.getNetworkInterfaces().toList()) {
                val addresses = com.google.gson.JsonArray()
                for (address in nic.inetAddresses.toList()) {
                    addresses.add(JsonObject().apply {
                        addProperty("address", address.hostAddress.substringBefore('%'))
                        addProperty("family", if (address is java.net.Inet4Address) "IPv4" else "IPv6")
                        addProperty("internal", address.isLoopbackAddress)
                    })
                }
                out.add(nic.name, addresses)
            }
        } catch (_: Exception) {
            // No interfaces readable: the tool falls back to its placeholder.
        }
        return out
    }
}

/** A Swing panel (the REST API Client) in a tab. */
class PanelEditor(file: VirtualFile, title: String, create: (Disposable) -> JComponent) : DevSnipEditorBase(file, title) {
    private val component = create(this)
    override fun getComponent() = component
    override fun getPreferredFocusedComponent() = component
    override fun dispose() = Unit
}

object DevSnipIcons {
    val TOOL: Icon = com.intellij.openapi.util.IconLoader.getIcon("/icons/devsnipApi.svg", DevSnipIcons::class.java)
}
