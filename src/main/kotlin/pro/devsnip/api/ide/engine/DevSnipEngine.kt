package pro.devsnip.api.ide.engine

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonNull
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationInfo
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.PathManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.project.guessProjectDir
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.SystemInfo
import com.intellij.ui.jcef.JBCefApp
import com.intellij.ui.jcef.JBCefBrowser
import com.intellij.ui.jcef.JBCefBrowserBase
import com.intellij.ui.jcef.JBCefJSQuery
import com.intellij.util.EnvironmentUtil
import org.cef.browser.CefBrowser
import org.cef.browser.CefFrame
import org.cef.callback.CefJSDialogCallback
import org.cef.handler.CefJSDialogHandler
import org.cef.handler.CefJSDialogHandlerAdapter
import org.cef.handler.CefLoadHandler
import org.cef.handler.CefLoadHandlerAdapter
import org.cef.handler.CefRequestHandlerAdapter
import org.cef.misc.BoolRef
import org.cef.network.CefRequest
import pro.devsnip.api.ide.web.ProjectTrust
import pro.devsnip.api.ide.web.ScannerHost
import pro.devsnip.api.ide.web.WebviewAssets
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * DevSnip Pro's extension host for one project: the VS Code extension's own
 * code (webview/src/host-runtime.js) running in a hidden page of the IDE's
 * embedded browser, as VS Code runs it in its extension host. The IDE answers
 * what it asks of VS Code ([EngineHost]), opens its webview panels as editor
 * tabs and its sidebar in the tool window ([EnginePanels]), and runs its
 * commands from Find Action, menus and the keyboard ([execute]).
 *
 * It starts on first use. Messages to the page wait until it has loaded;
 * commands wait until the extension has activated.
 */
@Service(Service.Level.PROJECT)
class DevSnipEngine(val project: Project) : Disposable {
    enum class Status { IDLE, STARTING, READY, FAILED }

    @Volatile
    var status: Status = Status.IDLE
        private set

    @Volatile
    var failure: String? = null
        private set

    val host = EngineHost(project, this)
    val panels = EnginePanels(project, this)

    private var browser: JBCefBrowser? = null
    private var query: JBCefJSQuery? = null
    private var pageFile: Path? = null
    private val lock = Any()
    private val outbox = mutableListOf<String>()
    private var loaded = false
    private val readyCallbacks = mutableListOf<(Boolean) -> Unit>()
    private val pending = ConcurrentHashMap<Int, CompletableFuture<JsonObject>>()
    private val nextId = AtomicInteger(1)

    /** Commands the extension registered (the sidebar, menus and Find Action may run them here). */
    @Volatile
    var commands: Set<String> = emptySet()
        private set

    /** Whether the extension provides the Tools sidebar (it does once activated). */
    @Volatile
    var providesSidebar = false
        private set

    init {
        ALL.add(this)
    }

    /** Starts the host if it is not running (on the EDT or any thread). */
    fun start() {
        if (!isSupported()) return
        synchronized(lock) {
            if (status == Status.STARTING || status == Status.READY) return
            status = Status.STARTING
            failure = null
        }
        // The local server's address is looked up off the UI thread (it may still be starting).
        ApplicationManager.getApplication().executeOnPooledThread {
            val syncUrl = EngineSyncServer.register(this)
            ApplicationManager.getApplication().invokeLater({ create(syncUrl) }, project.disposed)
        }
    }

    /** Runs [callback] once the extension has activated (true) or failed to (false). */
    fun whenReady(callback: (Boolean) -> Unit) {
        val now = synchronized(lock) {
            when (status) {
                Status.READY -> true
                Status.FAILED -> false
                else -> {
                    readyCallbacks += callback
                    null
                }
            }
        }
        if (now != null) callback(now) else start()
    }

    private fun create(syncUrl: String?) {
        if (project.isDisposed || browser != null) return
        try {
            // Queries and handlers are added before the native browser exists (newer JCEF, out of process
            // from 2026.2, ignores a query created afterwards); createImmediately() then starts it without UI.
            val b = JBCefBrowser.createBuilder()
                .setOffScreenRendering(JBCefApp.isOffScreenRenderingModeEnabled())
                .setCreateImmediately(false)
                .build()
            val q = JBCefJSQuery.create(b as JBCefBrowserBase)
            Disposer.register(this, b)
            Disposer.register(this, q)
            browser = b
            query = q
            q.addHandler { request ->
                val message = runCatching { JsonParser.parseString(request).asJsonObject }.getOrNull()
                when {
                    message == null -> LOG.warn("DevSnip Pro: ignored a malformed host message")
                    message.str("type") == "__probe" -> probes.remove(message["id"]?.asInt)?.complete(message)
                    else -> onMessage(message)
                }
                null
            }
            b.jbCefClient.addJSDialogHandler(syncChannel, b.cefBrowser)
            b.jbCefClient.addLoadHandler(object : CefLoadHandlerAdapter() {
                override fun onLoadEnd(cefBrowser: CefBrowser, frame: CefFrame, httpStatusCode: Int) {
                    if (!frame.isMain) return
                    val queued = synchronized(lock) {
                        loaded = true
                        outbox.toList().also { outbox.clear() }
                    }
                    queued.forEach(::execute)
                }

                override fun onLoadError(cefBrowser: CefBrowser, frame: CefFrame, errorCode: CefLoadHandler.ErrorCode, errorText: String, failedUrl: String) {
                    if (frame.isMain) fail("The extension host page could not load ($errorText).")
                }
            }, b.cefBrowser)
            b.jbCefClient.addRequestHandler(object : CefRequestHandlerAdapter() {
                // Nothing in the host page navigates; a link would leave it, so it is refused.
                override fun onBeforeBrowse(cefBrowser: CefBrowser, frame: CefFrame, request: CefRequest, userGesture: Boolean, isRedirect: Boolean): Boolean =
                    frame.isMain && pageFile != null && request.url != pageFile!!.toUri().toString()

                override fun onRenderProcessTerminated(cefBrowser: CefBrowser, status: org.cef.handler.CefRequestHandler.TerminationStatus) {
                    fail("The extension host stopped ($status).")
                }
            }, b.cefBrowser)
            val info = hostInfo().apply { syncUrl?.let { addProperty("syncUrl", it) } }
            val page = EnginePage.write(info, q.inject("request"), "engine-${INSTANCE.incrementAndGet()}", syncUrl)
            pageFile = page
            b.createImmediately()
            b.loadURL(page.toUri().toString())
            // A host that never reports back is treated as failed, so commands do not wait forever.
            ApplicationManager.getApplication().executeOnPooledThread {
                Thread.sleep(TimeUnit.SECONDS.toMillis(START_TIMEOUT_SECONDS))
                if (status == Status.STARTING) fail("The extension host did not start within $START_TIMEOUT_SECONDS seconds.")
            }
        } catch (e: Throwable) {
            LOG.warn("DevSnip Pro: the extension host could not be created", e)
            fail(e.message ?: e.javaClass.simpleName)
        }
    }

    /** window.__devsnipHost for the extension: what VS Code knows about the machine, the project and the extension. */
    private fun hostInfo(): JsonObject = JsonObject().apply {
        val root = project.guessProjectDir()?.takeIf { it.isInLocalFileSystem }?.let { ScannerHost.normalize(Path.of(it.path)) }
        addProperty("page", "engine")
        addProperty("platform", PLATFORM)
        addProperty("home", ScannerHost.canonical(ScannerHost.HOME))
        addProperty("tmpdir", ScannerHost.canonical(ScannerHost.TEMP))
        addProperty("hostname", runCatching { java.net.InetAddress.getLocalHost().hostName }.getOrDefault("localhost"))
        addProperty("username", System.getProperty("user.name") ?: "")
        addProperty("arch", if (System.getProperty("os.arch") in setOf("aarch64", "arm64")) "arm64" else "x64")
        addProperty("cpus", Runtime.getRuntime().availableProcessors())
        addProperty("osVersion", SystemInfo.OS_VERSION)
        addProperty("shell", EnvironmentUtil.getValue("SHELL") ?: "")
        addProperty("extensionPath", ScannerHost.canonical(ExtensionFiles.root))
        addProperty("globalStorage", ScannerHost.canonical(GLOBAL_STORAGE))
        addProperty("workspaceStorage", ScannerHost.canonical(workspaceStorage()))
        addProperty("logPath", ScannerHost.canonical(Path.of(PathManager.getLogPath(), "devsnip-pro")))
        addProperty("version", EXTENSION_VERSION)
        addProperty("ideName", ApplicationInfo.getInstance().versionName)
        addProperty("ideVersion", ApplicationInfo.getInstance().fullVersion)
        addProperty("trusted", ProjectTrust.isTrusted(project))
        addProperty("themeKind", if (com.intellij.ui.JBColor.isBright()) "light" else "dark")
        addProperty("machineId", EngineState.getInstance().machineId)
        addProperty("sessionId", SESSION_ID)
        addProperty("telemetry", DevSnipSettings.getInstance().telemetry)
        add("workspace", root?.let { JsonObject().apply { addProperty("name", project.name); addProperty("path", ScannerHost.canonical(it)) } } ?: JsonNull.INSTANCE)
        add("settings", DevSnipSettings.getInstance().forHost())
        add("globalState", EngineState.getInstance().snapshot())
        add("workspaceState", host.workspaceState())
        add("nativeCommands", JsonArray().apply { DevSnipNative.commands.forEach(::add) })
        add("env", JsonObject().apply { childEnvironment().forEach { (k, v) -> addProperty(k, v) } })
    }

    private fun workspaceStorage(): Path {
        val key = (project.locationHash).replace(Regex("[^A-Za-z0-9_-]"), "_")
        return Path.of(PathManager.getSystemPath(), "devsnip-pro", "workspace-storage", key)
    }

    // ------------------------------------------------------------ messages

    /** Delivers a message to the extension (queued until the page has loaded). */
    fun post(message: JsonObject) {
        val script = "window.__devsnipReceive && window.__devsnipReceive(${WebviewAssets.embed(message)});"
        val now = synchronized(lock) {
            if (browser == null || !loaded) {
                outbox += script
                false
            } else {
                true
            }
        }
        if (now) execute(script)
    }

    private fun execute(script: String) {
        val b = browser ?: return
        if (!b.isDisposed) b.cefBrowser.executeJavaScript(script, b.cefBrowser.url, 0)
    }

    private fun onMessage(message: JsonObject) {
        when (message.str("type")) {
            // Requests run where they belong (EngineHost moves UI work to the EDT and I/O off it).
            "__rpc" -> host.handle(message)
            "__execResult", "__ledgerResult", "__customResult" -> pending.remove(message["id"]?.asInt)?.complete(message)
            else -> ApplicationManager.getApplication().invokeLater({ dispatch(message) }, project.disposed)
        }
    }

    private fun dispatch(message: JsonObject) {
        when (message.str("type")) {
            "__ready" -> ready(message)
            "__viewProvider" -> providesSidebar = true
            "__usage" -> DevSnipNative.usageRecorded(project, message.str("command"))
            "__theme" -> DevSnipNative.themeApplied(message.str("id"))
            "__points" -> DevSnipNative.pointsChanged(message["balance"]?.asInt ?: 0)
            else -> panels.handle(message)
        }
    }

    private fun ready(message: JsonObject) {
        commands = (message["commands"] as? JsonArray)?.mapNotNull { it.takeIf { e -> e.isJsonPrimitive }?.asString }?.toSet().orEmpty()
        val failed = (message["failed"] as? JsonArray)?.map { it.asString }.orEmpty()
        if (failed.isNotEmpty()) LOG.warn("DevSnip Pro: parts of the extension did not load: $failed ${message.str("error")}")
        message["theme"]?.takeIf { it.isJsonPrimitive }?.let { DevSnipNative.themeApplied(it.asString) }
        message["points"]?.takeIf { it.isJsonPrimitive }?.let { DevSnipNative.pointsChanged(it.asInt) }
        val callbacks = synchronized(lock) {
            status = Status.READY
            readyCallbacks.toList().also { readyCallbacks.clear() }
        }
        callbacks.forEach { runCatching { it(true) }.onFailure { e -> LOG.warn(e) } }
        panels.engineReady()
    }

    private fun fail(reason: String) {
        val callbacks = synchronized(lock) {
            if (status == Status.FAILED) return
            status = Status.FAILED
            failure = reason
            readyCallbacks.toList().also { readyCallbacks.clear() }
        }
        LOG.warn("DevSnip Pro: $reason")
        pending.values.forEach { it.complete(JsonObject().apply { addProperty("error", reason) }) }
        pending.clear()
        ApplicationManager.getApplication().invokeLater({
            callbacks.forEach { runCatching { it(false) } }
            panels.engineStopped()
            disposeBrowser()
        }, project.disposed)
    }

    private fun disposeBrowser() {
        EngineSyncServer.unregister(this)
        val b = browser ?: return
        browser = null
        query?.let(Disposer::dispose)
        query = null
        synchronized(lock) {
            loaded = false
            outbox.clear()
        }
        Disposer.dispose(b)
        pageFile?.let { runCatching { Files.deleteIfExists(it) } }
        pageFile = null
    }

    /** Restarts a failed host (the next command does this on its own). */
    fun restartIfFailed() {
        if (status == Status.FAILED) synchronized(lock) { status = Status.IDLE }
    }

    // ------------------------------------------------------------ commands

    /**
     * Runs a command in the extension, as VS Code's executeCommand: with the
     * current editor state, after activation. The future completes with the
     * error message, or null when the command finished.
     */
    fun execute(command: String, args: JsonArray = JsonArray(), editors: JsonObject? = null): CompletableFuture<String?> {
        restartIfFailed()
        val result = CompletableFuture<String?>()
        whenReady { ok ->
            if (!ok) {
                result.complete(failure ?: "DevSnip Pro could not start.")
                return@whenReady
            }
            val id = nextId.getAndIncrement()
            val reply = CompletableFuture<JsonObject>()
            pending[id] = reply
            reply.thenAccept { answer -> result.complete(answer["error"]?.takeIf { it.isJsonPrimitive }?.asString) }
            ApplicationManager.getApplication().invokeLater({
                post(JsonObject().apply {
                    addProperty("type", "__exec")
                    addProperty("id", id)
                    addProperty("command", command)
                    add("args", args)
                    add("editors", editors ?: EngineEditors.state(project))
                })
            }, project.disposed)
        }
        return result
    }

    /** A request whose answer comes back as a message with the same id (ledger, custom editor save). */
    fun request(type: String, fill: JsonObject.() -> Unit): CompletableFuture<JsonObject> {
        val id = nextId.getAndIncrement()
        val reply = CompletableFuture<JsonObject>()
        pending[id] = reply
        whenReady { ok ->
            if (!ok) {
                pending.remove(id)
                reply.complete(JsonObject().apply { addProperty("error", failure ?: "DevSnip Pro could not start.") })
            } else {
                post(JsonObject().apply { addProperty("type", type); addProperty("id", id); fill() })
            }
        }
        return reply
    }

    private val probes = ConcurrentHashMap<Int, CompletableFuture<JsonObject>>()

    /** Evaluates an expression in the extension host (diagnostics): completes with { result } or { error }. */
    fun probe(expression: String): CompletableFuture<JsonObject> {
        val id = nextId.getAndIncrement()
        val future = CompletableFuture<JsonObject>()
        probes[id] = future
        val script = pro.devsnip.api.ide.web.DevSnipBrowser.PROBE_SCRIPT.replace("__ID__", id.toString()).replace("__EXPR__", expression)
        synchronized(lock) { if (browser == null || !loaded) outbox += script else null }?.let { return future }
        execute(script)
        return future
    }

    // ---------------------------------------------------------- sync calls

    /** The extension's synchronous Node calls arrive as prompt() and are answered before the page continues. */
    private val syncChannel: CefJSDialogHandler = object : CefJSDialogHandlerAdapter() {
        override fun onJSDialog(
            browser: CefBrowser?, originUrl: String?, dialogType: CefJSDialogHandler.JSDialogType?, messageText: String?,
            defaultPromptText: String?, callback: CefJSDialogCallback, suppressMessage: BoolRef?,
        ): Boolean {
            if (dialogType != CefJSDialogHandler.JSDialogType.JSDIALOGTYPE_PROMPT || messageText != SYNC_MARKER) {
                // The host page shows no dialogs of its own.
                callback.Continue(false, "")
                return true
            }
            ApplicationManager.getApplication().executeOnPooledThread {
                val answer = JsonObject()
                try {
                    val request = JsonParser.parseString(defaultPromptText ?: "{}").asJsonObject
                    answer.add("result", host.sync(request.str("method"), request["params"] as? JsonObject ?: JsonObject()) ?: JsonNull.INSTANCE)
                } catch (e: Exception) {
                    answer.addProperty("error", e.message ?: e.javaClass.simpleName)
                    (e as? EngineHost.CodedException)?.code?.let { answer.addProperty("code", it) }
                }
                callback.Continue(true, answer.toString())
            }
            return true
        }
    }

    override fun dispose() {
        ALL.remove(this)
        EngineSyncServer.unregister(this)
        synchronized(lock) {
            status = Status.FAILED
            failure = "The project was closed."
        }
        pending.values.forEach { it.complete(JsonObject().apply { addProperty("error", "The project was closed.") }) }
        pending.clear()
        host.dispose()
        pageFile?.let { runCatching { Files.deleteIfExists(it) } }
    }

    companion object {
        private val LOG = Logger.getInstance(DevSnipEngine::class.java)
        private val INSTANCE = AtomicInteger()
        private val ALL = CopyOnWriteArraySet<DevSnipEngine>()
        private const val SYNC_MARKER = "__devsnip_sync__"
        private const val START_TIMEOUT_SECONDS = 60L
        private val SESSION_ID = java.util.UUID.randomUUID().toString()

        val PLATFORM: String = if (SystemInfo.isWindows) "win32" else if (SystemInfo.isMac) "darwin" else "linux"
        val GLOBAL_STORAGE: Path get() = ScannerHost.GLOBAL_STORAGE

        /** The VS Code extension version this host was built from (its own code reads it as its version). */
        val EXTENSION_VERSION: String by lazy {
            runCatching {
                val text = DevSnipEngine::class.java.getResourceAsStream("/webview/contributions.json")!!.use { String(it.readBytes(), Charsets.UTF_8) }
                JsonParser.parseString(text).asJsonObject["version"].asString
            }.getOrDefault("0.0.0")
        }

        /** Environment variables the extension reads (paths and tool locations; never secrets). */
        private val ENV_KEYS = setOf(
            "PATH", "Path", "PATHEXT", "HOME", "USERPROFILE", "APPDATA", "LOCALAPPDATA", "ProgramFiles", "ProgramFiles(x86)", "ChocolateyInstall",
            "ComSpec", "SystemRoot", "GRADLE_USER_HOME", "MAVEN_HOME", "M2_HOME", "JAVA_HOME", "VIRTUAL_ENV", "CONDA_PREFIX", "SHELL", "TMPDIR",
            "LANG", "XDG_CONFIG_HOME", "XDG_DATA_HOME", "NVM_DIR", "VOLTA_HOME", "PNPM_HOME", "BUN_INSTALL", "FLUTTER_ROOT", "DOTNET_ROOT", "GOPATH", "CARGO_HOME",
        )

        fun childEnvironment(): Map<String, String> = (EnvironmentUtil.getEnvironmentMap()).filterKeys { it in ENV_KEYS }

        fun isSupported(): Boolean = runCatching { JBCefApp.isSupported() }.getOrDefault(false)

        fun getInstance(project: Project): DevSnipEngine = project.service()

        /** Running hosts (globalState and settings changes reach all of them). */
        fun all(): List<DevSnipEngine> = ALL.filter { !it.project.isDisposed }

        fun forOpenProjects(action: (DevSnipEngine) -> Unit) = ProjectManager.getInstance().openProjects.filter { !it.isDisposed }.forEach { action(getInstance(it)) }

        private fun JsonObject.str(key: String): String = get(key)?.takeIf { it.isJsonPrimitive }?.asString ?: ""
    }
}

/** The host page: engine.html with the host's data, the IDE query function and the extension's code. */
object EnginePage {
    fun write(host: JsonObject, query: String, name: String, syncUrl: String? = null): Path {
        // The page may connect only to its own synchronous-call address on the IDE's local server.
        val connect = syncUrl?.let { java.net.URI(it).let { u -> "${u.scheme}://${u.host}:${u.port}" } } ?: "'none'"
        val csp = "default-src 'none'; script-src 'unsafe-inline' 'unsafe-eval' 'wasm-unsafe-eval' file:; connect-src $connect; img-src 'none'; style-src 'none';"
        val html = text("engine.html")
            .replace("__DEVSNIP_CSP__", csp)
            .replaceFirst("<head>", "<head><script>window.__devsnipHost=${WebviewAssets.embed(host)};window.__devsnipQuery=function(request){$query};</script>")
            .replaceFirst("</body>", "<script src=\"host-runtime.js\"></script></body>")
        val file = WebviewAssets.directory.resolve("$name.html")
        Files.writeString(file, html)
        return file
    }

    private fun text(name: String): String =
        EnginePage::class.java.getResourceAsStream("/webview/$name")?.use { String(it.readBytes(), Charsets.UTF_8) } ?: error("Missing webview resource $name")
}
