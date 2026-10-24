package pro.devsnip.api.ide.web

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.intellij.ide.BrowserUtil
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.util.Disposer
import com.intellij.ui.jcef.JBCefBrowser
import com.intellij.ui.jcef.JBCefBrowserBase
import com.intellij.ui.jcef.JBCefJSQuery
import org.cef.browser.CefBrowser
import org.cef.browser.CefFrame
import org.cef.handler.CefLoadHandlerAdapter
import org.cef.handler.CefRequestHandlerAdapter
import org.cef.network.CefRequest
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArraySet
import javax.swing.JComponent

/**
 * One DevSnip Pro page in the IDE's embedded browser, with the messaging of a
 * VS Code webview: the page posts JSON to [onMessage]; [post] delivers JSON
 * to the page as a `message` event (queued until the page has loaded).
 */
class DevSnipBrowser(parent: Disposable, private val surface: String, private val onMessage: (JsonObject) -> Unit) : Disposable {
    private val browser: JBCefBrowser = JBCefBrowser.createBuilder().build()
    private val query: JBCefJSQuery = JBCefJSQuery.create(browser as JBCefBrowserBase)
    private val queue = mutableListOf<String>()
    private var loaded = false
    private var disposed = false
    private var page: Path? = null

    /** Names this browser's page file (see [WebviewAssets.sidebarHtml]). */
    val instance: String = "b" + NEXT_INSTANCE.incrementAndGet()

    val component: JComponent get() = browser.component

    /** JavaScript that sends `request` (a JSON string) to the IDE; the bridge calls it. */
    val queryFunction: String get() = query.inject("request")

    init {
        Disposer.register(parent, this)
        Disposer.register(this, browser)
        Disposer.register(this, query)
        query.addHandler { request ->
            val message = try {
                JsonParser.parseString(request).asJsonObject
            } catch (e: Exception) {
                LOG.warn("DevSnip Pro: ignored a malformed page message")
                null
            }
            if (message != null) ApplicationManager.getApplication().invokeLater { if (!disposed) onMessage(message) }
            null
        }
        browser.jbCefClient.addLoadHandler(object : CefLoadHandlerAdapter() {
            override fun onLoadEnd(cefBrowser: CefBrowser, frame: CefFrame, httpStatusCode: Int) {
                if (!frame.isMain) return
                ApplicationManager.getApplication().invokeLater {
                    loaded = true
                    val pending = queue.toList()
                    queue.clear()
                    pending.forEach(::execute)
                }
            }
        }, browser.cefBrowser)
        // Links in a page (docs, "learn more") open in the system browser, never inside the IDE.
        browser.jbCefClient.addRequestHandler(object : CefRequestHandlerAdapter() {
            override fun onBeforeBrowse(cefBrowser: CefBrowser, frame: CefFrame, request: CefRequest, userGesture: Boolean, isRedirect: Boolean): Boolean {
                val url = request.url ?: return false
                if (url.startsWith("http://") || url.startsWith("https://")) {
                    BrowserUtil.browse(url)
                    return true
                }
                return false
            }
        }, browser.cefBrowser)
        OPEN.add(this)
    }

    fun load(page: Path) {
        loaded = false
        this.page?.takeIf { it != page }?.let(::deletePage)
        this.page = page
        browser.loadURL(page.toUri().toString())
    }

    private fun deletePage(path: Path) {
        runCatching { java.nio.file.Files.deleteIfExists(path) }
    }

    /** Delivers a message to the page, like `webview.postMessage`. */
    fun post(message: JsonElement) {
        val script = "window.__devsnipReceive && window.__devsnipReceive(${WebviewAssets.embed(message)});"
        if (loaded) execute(script) else queue += script
    }

    private fun execute(script: String) {
        if (!disposed) browser.cefBrowser.executeJavaScript(script, browser.cefBrowser.url, 0)
    }

    /** Re-applies the IDE's colours (System Default follows the IDE theme, as it follows VS Code's). */
    fun refreshIdeTheme() {
        val css = WebviewAssets.embed(com.google.gson.JsonPrimitive(IdeThemeCss.css(surface)))
        val kind = WebviewAssets.embed(com.google.gson.JsonPrimitive(IdeThemeCss.bodyClass()))
        execute(
            "(function(){var s=document.getElementById('devsnip-ide-theme');if(s)s.textContent=$css;var b=document.body;if(!b)return;" +
                "['vscode-light','vscode-dark','vscode-high-contrast','vscode-high-contrast-light'].forEach(function(c){b.classList.remove(c)});b.classList.add($kind);b.setAttribute('data-vscode-theme-kind',$kind);})();",
        )
    }

    /** Applies a DevSnip theme to the page without reloading it. */
    fun applyDevSnipTheme(themeId: String) {
        post(WebviewAssets.themeMessage(themeId, surface))
    }

    override fun dispose() {
        disposed = true
        OPEN.remove(this)
        queue.clear()
        page?.let(::deletePage)
    }

    companion object {
        private val LOG = Logger.getInstance(DevSnipBrowser::class.java)
        private val NEXT_INSTANCE = java.util.concurrent.atomic.AtomicLong()

        /** Every open DevSnip Pro page, so theme changes reach all of them. */
        val OPEN: MutableSet<DevSnipBrowser> = CopyOnWriteArraySet()

        fun broadcast(action: (DevSnipBrowser) -> Unit) = OPEN.forEach(action)
    }
}
