package pro.devsnip.api.ide.engine

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileEditor.FileEditor
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.fileEditor.ex.FileEditorManagerEx
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.testFramework.LightVirtualFile
import pro.devsnip.api.ide.web.DevSnipBrowser
import pro.devsnip.api.ide.web.DevSnipEditorBase
import pro.devsnip.api.ide.web.DevSnipFileType
import pro.devsnip.api.ide.web.DevSnipUiState
import pro.devsnip.api.ide.web.WebviewAssets
import java.util.concurrent.ConcurrentHashMap
import javax.swing.JComponent

/**
 * Where the extension host's webviews show: a webview panel is an editor tab,
 * the Tools sidebar is the DevSnip Pro tool window, and a custom editor (the
 * CSV & Excel viewer) is a file editor. Each is a page of the embedded
 * browser with the extension's HTML; what the extension posts reaches it, and
 * what the page posts goes back to the extension.
 */
class EnginePanels(private val project: Project, private val engine: DevSnipEngine) {
    /** One shown webview. */
    interface View {
        val panelId: String
        fun setHtml(html: String)
        fun post(message: JsonObject)
        fun reveal(preserveFocus: Boolean) {}
        fun setTitle(title: String) {}
        /** The extension disposed it. */
        fun closeByExtension() {}
        fun customEdit(edit: Int, label: String) {}
        fun customDirty() {}
        fun failed(error: String) {}
    }

    private val views = ConcurrentHashMap<String, View>()
    private val earlyHtml = ConcurrentHashMap<String, String>()
    private val earlyPosts = ConcurrentHashMap<String, MutableList<JsonObject>>()

    fun register(view: View) {
        views[view.panelId] = view
        earlyHtml.remove(view.panelId)?.let(view::setHtml)
        earlyPosts.remove(view.panelId)?.forEach(view::post)
    }

    fun unregister(panelId: String) {
        views.remove(panelId)
        earlyHtml.remove(panelId)
        earlyPosts.remove(panelId)
    }

    fun view(panelId: String): View? = views[panelId]

    /** A message from the extension about a webview (on the EDT). */
    fun handle(message: JsonObject) {
        val panel = message.str("panel")
        when (message.str("type")) {
            "__panelCreate" -> open(panel, message.str("viewType"), message.str("title"), message["preserveFocus"]?.asBoolean == true)
            "__panelHtml" -> views[panel]?.setHtml(message.str("html")) ?: earlyHtml.put(panel, message.str("html"))
            "__panelPost" -> (message["message"] as? JsonObject)?.let { m -> views[panel]?.post(m) ?: earlyPosts.getOrPut(panel) { mutableListOf() }.add(m) }
            "__panelReveal" -> views[panel]?.reveal(message["preserveFocus"]?.asBoolean == true)
            "__panelTitle" -> views[panel]?.setTitle(message.str("title"))
            "__panelDispose" -> views.remove(panel)?.closeByExtension()
            "__customEdit" -> (message["panels"] as? JsonArray)?.forEach { id -> views[id.asString]?.customEdit(message["edit"].asInt, message.str("label")) }
            "__customDirty" -> (message["panels"] as? JsonArray)?.forEach { id -> views[id.asString]?.customDirty() }
            "__customEditorFailed" -> views[panel]?.failed(message.str("error"))
        }
    }

    private fun open(panelId: String, viewType: String, title: String, preserveFocus: Boolean) {
        LOG.debug("DevSnip Pro: panel $panelId ($viewType) \"$title\"")
        val file = EnginePanelFile(project, panelId, viewType, title.ifEmpty { "DevSnip Pro" })
        OpenFileDescriptor(project, file).navigate(!preserveFocus)
    }

    fun engineReady() = views.values.filterIsInstance<EngineSidebarView>().forEach { it.resolve() }

    /** The host stopped: its panels are gone (their tabs close); the sidebar shows its fallback. */
    fun engineStopped() {
        for (view in views.values.toList()) {
            when (view) {
                is EnginePanelEditor -> view.closeByExtension()
                else -> view.failed(engine.failure ?: "DevSnip Pro stopped.")
            }
        }
    }

    private fun JsonObject.str(key: String): String = get(key)?.takeIf { it.isJsonPrimitive }?.asString ?: ""

    companion object {
        private val LOG = com.intellij.openapi.diagnostic.Logger.getInstance(EnginePanels::class.java)
    }
}

/** An extension webview panel's tab. */
class EnginePanelFile(val project: Project, val panelId: String, val viewType: String, var title: String) : LightVirtualFile(title, DevSnipFileType, "") {
    init {
        isWritable = false
    }

    override fun getPath() = "devsnip://panel/$viewType/$panelId"
    override fun getPresentableName() = title
}

/** An extension webview page in the IDE's browser: the extension's HTML with the IDE's colours and the webview bridge. */
object EnginePages {
    fun write(html: String, viewType: String, surface: String, query: String, instance: String): java.nio.file.Path {
        val host = JsonObject().apply {
            addProperty("page", "panel")
            addProperty("toolId", viewType)
            addProperty("platform", DevSnipEngine.PLATFORM)
        }
        val csp = "default-src 'none'; style-src 'unsafe-inline' file: data:; script-src 'unsafe-inline' file:; font-src file: data:; img-src file: data: https: blob:; media-src file: data:; connect-src 'none';"
        val head = "<meta http-equiv=\"Content-Security-Policy\" content=\"$csp\">" +
            "<style id=\"devsnip-ide-theme\" data-surface=\"$surface\">${pro.devsnip.api.ide.web.IdeThemeCss.css(surface)}</style>" +
            "<script>window.__devsnipHost=${WebviewAssets.embed(host)};window.__devsnipQuery=function(request){$query};</script>" +
            "<script src=\"${WebviewAssets.directory.resolve("bridge.js").toUri()}\"></script>"
        val kind = pro.devsnip.api.ide.web.IdeThemeCss.bodyClass()
        var page = html.replace(Regex("<meta\\s+http-equiv=\"Content-Security-Policy\"[^>]*>", RegexOption.IGNORE_CASE), "")
        val headTag = Regex("<head[^>]*>", RegexOption.IGNORE_CASE).find(page)
        page = if (headTag != null) page.substring(0, headTag.range.last + 1) + head + page.substring(headTag.range.last + 1) else "<head>$head</head>$page"
        Regex("<body([^>]*)>", RegexOption.IGNORE_CASE).find(page)?.let { match ->
            val attributes = match.groupValues[1]
            val classMatch = Regex("class=\"([^\"]*)\"").find(attributes)
            val withClass = if (classMatch != null) attributes.replaceRange(classMatch.range, "class=\"${classMatch.groupValues[1]} $kind\"") else "$attributes class=\"$kind\""
            page = page.replaceRange(match.range, "<body$withClass data-vscode-theme-kind=\"$kind\">")
        }
        val file = WebviewAssets.directory.resolve("panel-$instance.html")
        java.nio.file.Files.writeString(file, page)
        return file
    }
}

/** A tab showing one of the extension's webview panels. */
class EnginePanelEditor(private val project: Project, private val file: EnginePanelFile) : DevSnipEditorBase(file, file.title), EnginePanels.View {
    private val engine = DevSnipEngine.getInstance(project)
    private val browser = DevSnipBrowser(this, "panel", native = false) { message -> toExtension(message) }
    private var closedByExtension = false
    override val panelId: String get() = file.panelId

    init {
        engine.panels.register(this)
    }

    private fun toExtension(message: JsonObject) {
        engine.post(JsonObject().apply {
            addProperty("type", "__panelMessage")
            addProperty("panel", panelId)
            add("message", message)
        })
    }

    override fun setHtml(html: String) {
        browser.load(EnginePages.write(html, file.viewType, "panel", browser.queryFunction, browser.instance))
    }

    override fun post(message: JsonObject) = browser.post(message)

    /** Diagnostics: evaluates an expression in the page. */
    fun probe(expression: String) = browser.probe(expression)

    override fun reveal(preserveFocus: Boolean) {
        OpenFileDescriptor(project, file).navigate(!preserveFocus)
    }

    override fun setTitle(title: String) {
        file.title = title
        (FileEditorManager.getInstance(project) as? FileEditorManagerEx)?.updateFilePresentation(file)
    }

    override fun closeByExtension() {
        closedByExtension = true
        ApplicationManager.getApplication().invokeLater({ if (!project.isDisposed) FileEditorManager.getInstance(project).closeFile(file) }, project.disposed)
    }

    private fun state(visible: Boolean) = engine.post(JsonObject().apply {
        addProperty("type", "__panelState")
        addProperty("panel", panelId)
        addProperty("visible", visible)
        addProperty("active", visible)
    })

    override fun selectNotify() = state(true)
    override fun deselectNotify() = state(false)

    override fun getComponent(): JComponent = browser.component
    override fun getPreferredFocusedComponent(): JComponent = browser.component

    override fun dispose() {
        engine.panels.unregister(panelId)
        if (!closedByExtension) engine.post(JsonObject().apply { addProperty("type", "__panelClosed"); addProperty("panel", panelId) })
    }
}

/** Opens extension panel tabs (and nothing else). */
class EnginePanelEditorProvider : com.intellij.openapi.fileEditor.FileEditorProvider, com.intellij.openapi.project.DumbAware {
    override fun accept(project: Project, file: VirtualFile) = file is EnginePanelFile && file.project == project
    override fun createEditor(project: Project, file: VirtualFile): FileEditor = EnginePanelEditor(project, file as EnginePanelFile)
    override fun getEditorTypeId() = "devsnip-pro-panel"
    override fun getPolicy() = com.intellij.openapi.fileEditor.FileEditorPolicy.HIDE_DEFAULT_EDITOR
}

/**
 * The Tools sidebar from the extension's own provider (ToolsSidebarProvider):
 * rank card, points, onboarding, theme locks and all. If the extension host
 * cannot start, [onFallback] shows the sidebar the IDE renders itself.
 */
class EngineSidebarView(private val project: Project, parent: Disposable, private val onFallback: () -> Unit) : EnginePanels.View, Disposable {
    private val engine = DevSnipEngine.getInstance(project)
    private val browser = DevSnipBrowser(this, "sidebar", native = false) { message ->
        engine.post(JsonObject().apply {
            addProperty("type", "__panelMessage")
            addProperty("panel", panelId)
            add("message", message)
        })
    }
    override val panelId: String = "view-" + java.util.UUID.randomUUID().toString().take(8)
    val component: JComponent get() = browser.component
    private var resolved = false

    init {
        Disposer.register(parent, this)
        engine.panels.register(this)
        engine.whenReady { ok -> ApplicationManager.getApplication().invokeLater({ if (ok) resolve() else onFallback() }, project.disposed) }
    }

    fun resolve() {
        if (resolved || engine.status != DevSnipEngine.Status.READY) return
        if (!engine.providesSidebar) {
            onFallback()
            return
        }
        resolved = true
        engine.post(JsonObject().apply {
            addProperty("type", "__resolveView")
            addProperty("viewId", TOOLS_VIEW_ID)
            addProperty("panel", panelId)
        })
    }

    override fun setHtml(html: String) {
        browser.load(EnginePages.write(html, TOOLS_VIEW_ID, "sidebar", browser.queryFunction, browser.instance))
    }

    override fun post(message: JsonObject) = browser.post(message)

    /** Diagnostics: evaluates an expression in the page. */
    fun probe(expression: String) = browser.probe(expression)

    fun setVisible(visible: Boolean) = engine.post(JsonObject().apply {
        addProperty("type", "__panelState")
        addProperty("panel", panelId)
        addProperty("visible", visible)
        addProperty("active", visible)
    })

    override fun failed(error: String) {
        resolved = false
        onFallback()
    }

    override fun dispose() {
        engine.panels.unregister(panelId)
        engine.post(JsonObject().apply { addProperty("type", "__panelClosed"); addProperty("panel", panelId) })
    }

    companion object {
        /** package.json's view id for the Tools sidebar (TOOLS_VIEW_ID in the extension). */
        const val TOOLS_VIEW_ID = "myView"
    }
}

/** The DevSnip Pro tool window: the extension's sidebar, or the IDE's own if the extension host cannot run. */
class DevSnipToolWindowPanel(private val project: Project, private val parent: Disposable) : com.intellij.openapi.ui.SimpleToolWindowPanel(true, true) {
    private var fallback = false
    val view: EngineSidebarView? = if (DevSnipEngine.isSupported()) EngineSidebarView(project, parent) { showFallback() } else null

    init {
        if (view != null) setContent(view.component) else showFallback()
    }

    private fun showFallback() {
        if (fallback) return
        fallback = true
        setContent(pro.devsnip.api.ide.web.DevSnipSidebar(project, parent))
        revalidate()
        repaint()
    }
}

/** Keeps the extension's "Recently used" and theme in step with what the IDE keeps for its own pages. */
internal fun DevSnipUiState.mirrorUsage(command: String) {
    recordUsage(command)
}
