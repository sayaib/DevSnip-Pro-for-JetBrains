package pro.devsnip.api.ide.web

import com.google.gson.JsonElement
import com.google.gson.JsonNull
import com.google.gson.JsonObject
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileChooser.FileChooser
import com.intellij.openapi.fileChooser.FileChooserDescriptor
import com.intellij.openapi.fileEditor.FileEditor
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.FileEditorPolicy
import com.intellij.openapi.fileEditor.FileEditorProvider
import com.intellij.openapi.fileEditor.FileEditorState
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.fileTypes.FileType
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.SystemInfo
import com.intellij.openapi.util.UserDataHolderBase
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.testFramework.LightVirtualFile
import com.intellij.ui.jcef.JBCefApp
import java.beans.PropertyChangeListener
import java.io.File
import javax.swing.Icon
import javax.swing.JComponent
import pro.devsnip.api.ide.ApiClientService
import pro.devsnip.api.ide.ui.ApiClientPanel

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
    enum class Kind { TOOL, REST_CLIENT, SCANNER }

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
            OpenFileDescriptor(project, existing).navigate(true)
            if (initial != null && initial.size() > 0) {
                manager.getEditors(existing).filterIsInstance<ToolPageEditor>().firstOrNull()?.setValues(initial)
            }
            return
        }
        val file = DevSnipVirtualFile(DevSnipVirtualFile.Kind.TOOL, toolId, WebviewAssets.toolTitle(toolId))
        if (initial != null) file.initial = initial
        OpenFileDescriptor(project, file).navigate(true)
    }

    /** Opens (or focuses) the OSV ("osv") or SCA ("sca") scanner. */
    fun openScanner(project: Project, page: String) {
        val manager = FileEditorManager.getInstance(project)
        val existing = manager.openFiles.filterIsInstance<DevSnipVirtualFile>().firstOrNull { it.kind == DevSnipVirtualFile.Kind.SCANNER && it.toolId == page }
        OpenFileDescriptor(project, existing ?: DevSnipVirtualFile(DevSnipVirtualFile.Kind.SCANNER, page, WebviewAssets.scannerTitle(page))).navigate(true)
    }

    fun openRestClient(project: Project) {
        val manager = FileEditorManager.getInstance(project)
        val existing = manager.openFiles.filterIsInstance<DevSnipVirtualFile>().firstOrNull { it.kind == DevSnipVirtualFile.Kind.REST_CLIENT }
        OpenFileDescriptor(project, existing ?: DevSnipVirtualFile(DevSnipVirtualFile.Kind.REST_CLIENT, "rest-client", "REST API Client")).navigate(true)
    }
}

class DevSnipEditorProvider : FileEditorProvider, DumbAware {
    override fun accept(project: Project, file: VirtualFile) = file is DevSnipVirtualFile

    override fun createEditor(project: Project, file: VirtualFile): FileEditor {
        val page = file as DevSnipVirtualFile
        return when (page.kind) {
            DevSnipVirtualFile.Kind.TOOL -> ToolPageEditor(project, page)
            DevSnipVirtualFile.Kind.REST_CLIENT ->
                if (JBCefApp.isSupported()) RestClientPageEditor(project, page)
                // Without JCEF (some remote setups) the native client stands in.
                else PanelEditor(page, "REST API Client") { parent -> ApiClientPanel(project).also { Disposer.register(parent, it) } }
            DevSnipVirtualFile.Kind.SCANNER -> ScannerPageEditor(project, page)
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
        browser.load(WebviewAssets.toolHtml(file.toolId, file.initial, state, hostInfo, browser.queryFunction, browser.instance))
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

/** The OSV or SCA scanner: the extension's own page and panel code, with the IDE doing its I/O. */
class ScannerPageEditor(project: Project, file: DevSnipVirtualFile) : DevSnipEditorBase(file, WebviewAssets.scannerTitle(file.toolId)) {
    private val browser: DevSnipBrowser = DevSnipBrowser(this, "panel") { message -> host.handle(message) }
    private val host: ScannerHost = ScannerHost(project, file.toolId) { browser.post(it) }

    init {
        Disposer.register(this, host)
        browser.load(WebviewAssets.scannerHtml(file.toolId, DevSnipUiState.getInstance(), host.hostInfo(), browser.queryFunction, browser.instance))
    }

    // The extension tells a hidden panel's result with a notification instead.
    override fun selectNotify() = host.setVisible(true)
    override fun deselectNotify() = host.setVisible(false)

    override fun getComponent(): JComponent = browser.component
    override fun getPreferredFocusedComponent(): JComponent = browser.component
    override fun dispose() = Unit
}

/** The REST API Client: the extension's own page, its messages answered by [RestClientHost] on the Kotlin engine. */
class RestClientPageEditor(project: Project, file: DevSnipVirtualFile) : DevSnipEditorBase(file, "REST API Client") {
    private val browser: DevSnipBrowser = DevSnipBrowser(this, "panel") { message -> host.handle(message) }
    private val host: RestClientHost = RestClientHost(
        service = ApiClientService.getInstance(),
        ui = IdeRestClientUi(project),
        // Replies come from pooled threads; the browser is driven from the UI thread.
        post = { message -> ApplicationManager.getApplication().invokeLater { browser.post(message) } },
        background = { task -> ApplicationManager.getApplication().executeOnPooledThread(task) },
    )

    init {
        val state = DevSnipUiState.getInstance()
        val hostInfo = JsonObject().apply {
            addProperty("page", "rest-client")
            add("storage", state.toolStorage())
            addProperty("platform", if (SystemInfo.isMac) "darwin" else if (SystemInfo.isWindows) "win32" else "linux")
        }
        browser.load(WebviewAssets.restClientHtml(state, hostInfo, browser.queryFunction, browser.instance))
    }

    override fun getComponent(): JComponent = browser.component
    override fun getPreferredFocusedComponent(): JComponent = browser.component
    override fun dispose() = host.dispose()
}

/** Dialogs, notifications and storage for the REST client page, the IDE way. */
private class IdeRestClientUi(private val project: Project) : RestClientUi {
    override fun chooseSaveFile(title: String, defaultName: String, extensions: List<String>): File? =
        pro.devsnip.api.ide.ui.Files.chooseSaveTarget(project, title, defaultName)

    override fun chooseOpenFile(title: String, extensions: List<String>): File? {
        val descriptor = FileChooserDescriptor(true, false, false, false, false, false).withTitle(title)
        val chosen = FileChooser.chooseFile(descriptor, project, null) ?: return null
        return File(chosen.path)
    }

    override fun notify(kind: String, text: String) {
        val type = when (kind) {
            "error" -> NotificationType.ERROR
            "warning" -> NotificationType.WARNING
            else -> NotificationType.INFORMATION
        }
        ApplicationManager.getApplication().invokeLater {
            if (!project.isDisposed) NotificationGroupManager.getInstance().getNotificationGroup("DevSnip Pro").createNotification(text, type).notify(project)
        }
    }

    override fun setStorage(key: String, value: JsonElement?) {
        DevSnipUiState.getInstance().setToolStorage(key, value ?: JsonNull.INSTANCE)
    }
}

/** A Swing panel (the REST API Client) without JCEF, in a tab. */
class PanelEditor(file: VirtualFile, title: String, create: (Disposable) -> JComponent) : DevSnipEditorBase(file, title) {
    private val component = create(this)
    override fun getComponent() = component
    override fun getPreferredFocusedComponent() = component
    override fun dispose() = Unit
}

object DevSnipIcons {
    val TOOL: Icon = com.intellij.openapi.util.IconLoader.getIcon("/icons/devsnipApi.svg", DevSnipIcons::class.java)
}
