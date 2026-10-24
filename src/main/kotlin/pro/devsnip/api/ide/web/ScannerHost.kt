package pro.devsnip.api.ide.web

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonNull
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.JsonPrimitive
import com.intellij.diff.DiffContentFactory
import com.intellij.diff.DiffManager
import com.intellij.diff.requests.SimpleDiffRequest
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.ide.BrowserUtil
import com.intellij.ide.util.PropertiesComponent
import com.intellij.notification.Notification
import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationType
import com.intellij.notification.Notifications
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.application.ApplicationInfo
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.PathManager
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.fileChooser.FileChooser
import com.intellij.openapi.fileChooser.FileChooserDescriptor
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.fileTypes.PlainTextFileType
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.guessProjectDir
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.util.SystemInfo
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.testFramework.LightVirtualFile
import com.intellij.util.EnvironmentUtil
import pro.devsnip.api.ide.PasswordSafeSecretStore
import java.io.ByteArrayInputStream
import java.net.InetAddress
import java.net.ProxySelector
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import java.time.Duration
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.GZIPInputStream
import java.util.zip.InflaterInputStream

/**
 * The IDE side of the OSV and SCA scanner pages. The scanners' own code (the
 * VS Code extension's panels and engine) runs in the page; what it would ask
 * VS Code, Node's fs and child_process, or fetch for arrives here as RPCs
 * (webview/shims/vscode-browser.js and friends):
 *
 *  - files: the project's files, files the user picked, and the scanner's own
 *    storage, temporary folders and local Maven repository; nothing else;
 *  - HTTP: https only, through the IDE's proxy settings, off the EDT, cancellable;
 *  - build tools: only the extension's read-only Maven `dependency:tree` and
 *    Gradle init-script runs, from the project's wrapper or the tool on PATH,
 *    in a trusted project;
 *  - dialogs, editors, diffs, commands in the Run tool window, the clipboard, the password safe.
 *
 * Running a command and changing a project file need the user's
 * confirmation in a modal dialog first (the extension asks for it).
 */
class ScannerHost(private val project: Project, val page: String, private val post: (JsonElement) -> Unit) : Disposable {
    private val workspaceRoot: Path? = project.guessProjectDir()?.takeIf { it.isInLocalFileSystem }?.let { normalize(Path.of(it.path)) }
    private val picked = CopyOnWriteArraySet<Path>()
    private val tempDirs = CopyOnWriteArraySet<Path>()
    private val saveTargets = CopyOnWriteArraySet<Path>()
    private val processes = ConcurrentHashMap<Int, Process>()
    private val requests = ConcurrentHashMap<Int, CompletableFuture<*>>()
    private val aborted: MutableSet<Int> = ConcurrentHashMap.newKeySet()
    private val progress = ConcurrentHashMap<Int, ProgressTask>()
    private val nextProgress = AtomicInteger(1)
    private val confirmation = Confirmation()

    @Volatile
    private var disposed = false

    init {
        OPEN.add(this)
    }

    // ------------------------------------------------------------ bootstrap

    /** window.__devsnipHost for the page: what VS Code would know about the workspace, the machine and the settings. */
    fun hostInfo(): JsonObject = JsonObject().apply {
        addProperty("page", page)
        addProperty("platform", if (SystemInfo.isWindows) "win32" else if (SystemInfo.isMac) "darwin" else "linux")
        addProperty("home", canonical(HOME))
        addProperty("tmpdir", canonical(TEMP))
        addProperty("pathEnv", pathEnv())
        addProperty("globalStorage", canonical(GLOBAL_STORAGE))
        addProperty("extensionPath", "/devsnip-pro")
        addProperty("version", pluginVersion)
        addProperty("ideVersion", ApplicationInfo.getInstance().fullApplicationName)
        addProperty("ideName", ApplicationInfo.getInstance().versionName)
        addProperty("trusted", isTrusted())
        addProperty("visible", true)
        add("settings", ScannerSettings.getInstance().asVsCodeSettings())
        add("workspace", workspaceRoot?.let { root -> JsonObject().apply { addProperty("name", project.name); addProperty("path", canonical(root)) } } ?: JsonNull.INSTANCE)
        add("workspaceState", workspaceState())
        add("session", ScannerSessions.get(project, page))
        add("facts", JsonObject().apply {
            add("exists", JsonArray().apply { pathExecutables().forEach { add(canonical(it)) } })
            add("texts", JsonObject().apply {
                val settings = HOME.resolve(".m2").resolve("settings.xml")
                runCatching { if (Files.isRegularFile(settings)) addProperty(canonical(settings), Files.readString(settings)) }
            })
        })
    }

    fun setVisible(visible: Boolean) = post(JsonObject().apply { addProperty("type", "__visibility"); addProperty("visible", visible) })

    // ------------------------------------------------------------ messages

    fun handle(message: JsonObject) {
        if (message.str("type") != "__rpc") return
        val id = message["id"] ?: return
        val method = message.str("method")
        val params = message["params"] as? JsonObject ?: JsonObject()
        val reply = { result: JsonElement?, error: String? ->
            val answer = JsonObject().apply {
                addProperty("type", "__rpcResult")
                add("id", id)
                if (error != null) addProperty("error", error) else add("result", result ?: JsonNull.INSTANCE)
            }
            ApplicationManager.getApplication().invokeLater { if (!disposed) post(answer) }
        }
        try {
            when (method) {
                // Dialogs and editors: on the EDT, where this is called.
                in UI_METHODS -> try {
                    reply(ui(method, params), null)
                } catch (pending: PendingAnswer) {
                    pending.answer.thenAccept { reply(it?.let(::JsonPrimitive), null) }
                }
                // Off the EDT: the NVD key comes from the password safe, which can block.
                "http.fetch" -> ApplicationManager.getApplication().executeOnPooledThread {
                    try {
                        fetch(params, reply)
                    } catch (e: Exception) {
                        reply(null, e.message ?: e.javaClass.simpleName)
                    }
                }
                else -> ApplicationManager.getApplication().executeOnPooledThread {
                    runCatching { io(method, params) }.onSuccess { reply(it, null) }.onFailure { reply(null, it.message ?: it.javaClass.simpleName) }
                }
            }
        } catch (e: Exception) {
            reply(null, e.message ?: e.javaClass.simpleName)
        }
    }

    private fun io(method: String, p: JsonObject): JsonElement? = when (method) {
        "ws.findFiles" -> findFiles(p)
        "fs.stat" -> stat(pathOf(p.str("path")))
        "fs.read" -> read(pathOf(p.str("path")))
        "fs.write" -> write(pathOf(p.str("path")), p.str("text"))
        "fs.mkdir" -> pathOf(p.str("path")).let { require(writable(it)) { "Not allowed to create $it." }; Files.createDirectories(it); null }
        "fs.rm" -> remove(pathOf(p.str("path")))
        "fs.mkdtemp" -> JsonPrimitive(makeTemp(p.str("prefix")))
        "process.spawn" -> spawn(p)
        "process.kill" -> { processes.remove(p["id"]?.asInt)?.let(::killTree); null }
        "http.abort" -> {
            val id = p["id"]?.asInt
            // An abort can overtake its request: remember it, and the request is never sent.
            requests.remove(id)?.cancel(true) ?: id?.let(aborted::add)
            null
        }
        "secrets.get" -> secretKey(p.str("key")).let { key -> if (PasswordSafeSecretStore().get(key).isNullOrEmpty()) null else JsonPrimitive(SECRET_MARKER + key) }
        "secrets.store" -> { PasswordSafeSecretStore().set(secretKey(p.str("key")), p["value"]?.takeIf { it.isJsonPrimitive }?.asString); null }
        "state.update" -> { updateWorkspaceState(p.str("key"), p["value"] ?: JsonNull.INSTANCE); null }
        "session.save" -> { ScannerSessions.save(project, page, p.str("key"), p["value"] ?: JsonNull.INSTANCE); null }
        "log.append" -> { ScannerLogs.append(p.str("channel"), (p["lines"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }.map { it.str("level") to it.str("text") }); null }
        "progress.report" -> { progress[p["id"]?.asInt]?.text = p.str("message"); null }
        "progress.end" -> { progress.remove(p["id"]?.asInt)?.done?.countDown(); null }
        else -> error("Unknown request $method")
    }

    private fun ui(method: String, p: JsonObject): JsonElement? = when (method) {
        "ui.message" -> message(p)
        "ui.openDialog" -> openDialog(p)
        "ui.saveDialog" -> saveDialog(p)
        "ui.inputBox" -> inputBox(p)
        "doc.open" -> openDocument(pathOf(p.str("path")))
        "doc.show" -> { showDocument(pathOf(p.str("path")), p["line"]?.takeIf { it.isJsonPrimitive }?.asInt); null }
        "doc.replace" -> JsonPrimitive(replaceDocument(pathOf(p.str("path")), p.str("text")))
        "doc.save" -> { findFile(pathOf(p.str("path")))?.let { vf -> FileDocumentManager.getInstance().getDocument(vf)?.let(FileDocumentManager.getInstance()::saveDocument) }; null }
        "ide.diff" -> { diff(pathOf(p.str("path")), p.str("text"), p.str("title")); null }
        "ide.copy" -> { CopyPasteManager.getInstance().setContents(java.awt.datatransfer.StringSelection(p.str("text"))); null }
        "ide.openExternal" -> { openExternal(p.str("url")); null }
        "ide.openSettings" -> { ShowSettingsUtil.getInstance().showSettingsDialog(project, ScannerSettingsConfigurable::class.java); null }
        "ide.openFolder" -> { ActionManager.getInstance().getAction("OpenFile")?.let { ActionManager.getInstance().tryToExecute(it, null, null, "DevSnipScanner", true) }; null }
        "terminal.run" -> { runInTerminal(p.str("name"), p.str("cwd"), p.str("command")); null }
        "progress.start" -> JsonPrimitive(startProgress(p.str("title")))
        "log.show" -> { ScannerLogs.show(project, p.str("channel")); null }
        "panel.reveal" -> { DevSnipPages.openScanner(project, page); null }
        else -> error("Unknown request $method")
    }

    // ---------------------------------------------------------------- files

    private fun under(path: Path, dir: Path?) = dir != null && path.startsWith(dir)

    /** Folders the user picked, and the folders of files they picked. */
    private fun pickedRoots() = picked.map { if (Files.isDirectory(it)) it else it.parent ?: it }

    fun readable(path: Path): Boolean = under(path, workspaceRoot) || under(path, GLOBAL_STORAGE) || tempDirs.any { under(path, it) } ||
        under(path, localRepository(HOME)) || path == HOME.resolve(".m2").resolve("settings.xml") || saveTargets.contains(path) ||
        pickedRoots().any { under(path, it) } ||
        // Context files above a picked file (parent POMs, gradle.properties, …), as discovery reads them.
        (path.fileName?.toString() in CONTEXT_FILES && picked.any { p -> path.parent != null && p.startsWith(path.parent) })

    fun writable(path: Path): Boolean = under(path, GLOBAL_STORAGE) || tempDirs.any { under(path, it) } || saveTargets.contains(path)

    private fun stat(path: Path): JsonElement? {
        if (!readable(path) || !Files.exists(path)) return null
        val attributes = Files.readAttributes(path, BasicFileAttributes::class.java)
        return JsonObject().apply {
            addProperty("type", if (attributes.isDirectory) 2 else 1)
            addProperty("size", attributes.size())
            addProperty("mtime", attributes.lastModifiedTime().toMillis())
        }
    }

    private fun read(path: Path): JsonElement? {
        require(readable(path)) { "DevSnip Pro only reads the project's files and its own storage." }
        if (!Files.isRegularFile(path)) return null
        require(Files.size(path) <= MAX_FILE_BYTES) { "Larger than ${MAX_FILE_BYTES / 1024 / 1024} MB." }
        return JsonPrimitive(decodeUtf8(Files.readAllBytes(path)))
    }

    private fun write(path: Path, text: String): JsonElement? {
        require(writable(path)) { "DevSnip Pro only writes its own storage and files you choose." }
        path.parent?.let(Files::createDirectories)
        Files.writeString(path, text)
        return null
    }

    private fun remove(path: Path): JsonElement? {
        val inStorage = under(path, GLOBAL_STORAGE) && path != GLOBAL_STORAGE
        require(inStorage || tempDirs.any { under(path, it) }) { "Not allowed to delete $path." }
        if (Files.exists(path)) path.toFile().deleteRecursively()
        tempDirs.remove(path)
        return null
    }

    private fun makeTemp(prefix: String): String {
        val target = pathOf(prefix)
        require(target.parent == TEMP) { "Temporary folders are made in the system temporary folder only." }
        val name = target.fileName.toString().replace(Regex("[^A-Za-z0-9._-]"), "")
        val dir = normalize(Files.createTempDirectory(TEMP, name))
        tempDirs.add(dir)
        return canonical(dir)
    }

    private fun findFiles(p: JsonObject): JsonElement {
        val base = p["base"]?.takeIf { it.isJsonPrimitive }?.let { pathOf(it.asString) } ?: workspaceRoot ?: return JsonObject().apply { add("paths", JsonArray()) }
        require(readable(base)) { "Not allowed to search $base." }
        val include = globRegex(p.str("include"))
        val exclude = p["exclude"]?.takeIf { it.isJsonPrimitive }?.asString?.let(::globRegex)
        val max = p["max"]?.takeIf { it.isJsonPrimitive }?.asInt ?: 100_000
        val paths = JsonArray()
        val wrappers = mutableListOf<Path>()
        if (Files.isDirectory(base)) {
            Files.walkFileTree(base, object : SimpleFileVisitor<Path>() {
                override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
                    if (disposed) return FileVisitResult.TERMINATE
                    val rel = relative(base, dir)
                    return if (rel.isNotEmpty() && exclude?.matches("$rel/x") == true) FileVisitResult.SKIP_SUBTREE else FileVisitResult.CONTINUE
                }

                override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                    if (!attrs.isRegularFile) return FileVisitResult.CONTINUE
                    if (file.fileName.toString() in WRAPPERS) wrappers.add(file)
                    val rel = relative(base, file)
                    if (paths.size() < max && include.matches(rel) && exclude?.matches(rel) != true) paths.add(canonical(file))
                    return FileVisitResult.CONTINUE
                }

                override fun visitFileFailed(file: Path, exc: java.io.IOException) = FileVisitResult.CONTINUE
            })
        }
        return JsonObject().apply {
            add("paths", paths)
            add("facts", facts(wrappers + wrappersAbove(base)))
        }
    }

    /** Build tool wrappers in a folder and the folders above it (the extension looks for them up to the workspace folder). */
    private fun wrappersAbove(start: Path): List<Path> = generateSequence(start) { it.parent }.flatMap { dir -> WRAPPERS.map(dir::resolve) }.filter(Files::isRegularFile).toList()

    private fun facts(existing: List<Path>) = JsonObject().apply {
        add("exists", JsonArray().apply { (existing + pathExecutables()).distinct().forEach { add(canonical(it)) } })
    }

    // ---------------------------------------------------------- build tools

    private fun spawn(p: JsonObject): JsonElement? {
        val id = p["id"].asInt
        val command = pathOf(p.str("command"))
        val args = (p["args"] as? JsonArray).orEmpty().map { it.asString }
        val cwd = pathOf(p.str("cwd"))
        require(isTrusted()) { "Build tools only run in a trusted project." }
        require(Files.isDirectory(cwd) && readable(cwd)) { "Build tools run in the project's folders only." }
        val name = command.fileName.toString()
        val wrapper = name in WRAPPERS && Files.isRegularFile(command) && readable(command)
        require(wrapper || command in pathExecutables()) { "Only the project's Maven or Gradle wrapper, or mvn / gradle on PATH, may run." }
        require(allowedArguments(name, args, tempDirs.toList())) { "Only the scanner's own dependency listing may run." }
        val process = GeneralCommandLine(listOf(command.toString()) + args)
            .withWorkDirectory(cwd.toFile())
            .withParentEnvironmentType(GeneralCommandLine.ParentEnvironmentType.CONSOLE)
            .withCharset(StandardCharsets.UTF_8)
            .createProcess()
        processes[id] = process
        fun event(kind: String, fill: JsonObject.() -> Unit) = ApplicationManager.getApplication().invokeLater {
            if (!disposed) post(JsonObject().apply { addProperty("type", "__process"); addProperty("id", id); addProperty("event", kind); fill() })
        }
        val stdout = ApplicationManager.getApplication().executeOnPooledThread<String> { process.inputStream.readBytes().toString(StandardCharsets.UTF_8) }
        val stderr = ApplicationManager.getApplication().executeOnPooledThread<String> { process.errorStream.readBytes().toString(StandardCharsets.UTF_8).takeLast(20_000) }
        ApplicationManager.getApplication().executeOnPooledThread {
            val code = runCatching { process.waitFor() }.getOrNull()
            val out = runCatching { stdout.get(10, TimeUnit.SECONDS) }.getOrDefault("")
            val err = runCatching { stderr.get(10, TimeUnit.SECONDS) }.getOrDefault("")
            processes.remove(id)
            if (out.isNotEmpty()) event("stdout") { addProperty("data", out) }
            if (err.isNotEmpty()) event("stderr") { addProperty("data", err) }
            // Killed processes report no exit code, as in Node.
            event("close") { if (code != null && code < 128) addProperty("code", code) else add("code", JsonNull.INSTANCE) }
        }
        return null
    }

    private fun killTree(process: Process) {
        process.toHandle().descendants().forEach { it.destroyForcibly() }
        process.destroyForcibly()
    }

    // ----------------------------------------------------------------- HTTP

    private fun fetch(p: JsonObject, reply: (JsonElement?, String?) -> Unit) {
        val id = p["id"]?.asInt ?: 0
        val uri = URI(p.str("url"))
        require(allowedUrl(uri)) { "DevSnip Pro only connects over HTTPS." }
        val method = p.str("method").ifEmpty { "GET" }
        require(method in setOf("GET", "POST", "HEAD")) { "Unsupported method $method." }
        val builder = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(120))
        val headers = p["headers"] as? JsonObject ?: JsonObject()
        for ((name, value) in headers.entrySet()) {
            if (name.lowercase() in RESTRICTED_HEADERS || !value.isJsonPrimitive) continue
            val text = resolveSecretHeader(uri, value.asString) ?: continue
            builder.header(name, text)
        }
        val body = p["body"]?.takeIf { it.isJsonPrimitive }?.asString
        builder.method(method, if (body == null) HttpRequest.BodyPublishers.noBody() else HttpRequest.BodyPublishers.ofString(body))
        if (aborted.remove(id) || disposed) {
            reply(null, "The request was cancelled.")
            return
        }
        val future = HTTP.sendAsync(builder.build(), HttpResponse.BodyHandlers.ofByteArray())
        requests[id] = future
        if (aborted.remove(id) || disposed) future.cancel(true)
        future.whenComplete { response, error ->
            requests.remove(id)
            if (error != null) {
                val cause = (error as? java.util.concurrent.CompletionException)?.cause ?: error
                reply(null, cause.message ?: cause.javaClass.simpleName)
                return@whenComplete
            }
            runCatching {
                JsonObject().apply {
                    addProperty("status", response.statusCode())
                    add("headers", JsonObject().apply { response.headers().map().forEach { (k, v) -> if (v.isNotEmpty()) addProperty(k.lowercase(), v.first()) } })
                    addProperty("text", decodeUtf8(decodeBody(response.body(), response.headers().firstValue("content-encoding").orElse(""))))
                }
            }.onSuccess { reply(it, null) }.onFailure { reply(null, it.message ?: it.javaClass.simpleName) }
        }
    }

    // ------------------------------------------------------------------ UI

    private fun message(p: JsonObject): JsonElement? {
        val items = (p["items"] as? JsonArray).orEmpty().map { it.asString }
        val text = p.str("message")
        val detail = p["detail"]?.takeIf { it.isJsonPrimitive }?.asString
        val type = p.str("kind")
        if (p["modal"]?.asBoolean == true) {
            val buttons = (items + "Cancel").toTypedArray()
            val icon = when (type) { "warning" -> Messages.getWarningIcon(); "error" -> Messages.getErrorIcon(); else -> Messages.getInformationIcon() }
            val choice = Messages.showDialog(project, if (detail != null) "$text\n\n$detail" else text, "DevSnip Pro", buttons, 0, icon)
            val chosen = items.getOrNull(choice) ?: return null
            confirmation.arm(text)
            return JsonPrimitive(chosen)
        }
        // Not modal: a notification; its actions answer the request when clicked.
        val pending = CompletableFuture<String?>()
        val notification = Notification(NOTIFICATIONS, "DevSnip Pro", text, when (type) { "warning" -> NotificationType.WARNING; "error" -> NotificationType.ERROR; else -> NotificationType.INFORMATION })
        for (item in items) notification.addAction(NotificationAction.createSimpleExpiring(item) { pending.complete(item) })
        Notifications.Bus.notify(notification, project)
        return if (items.isEmpty()) null else throw PendingAnswer(pending)
    }

    private fun openDialog(p: JsonObject): JsonElement {
        val files = p["canSelectFiles"]?.asBoolean != false
        val folders = p["canSelectFolders"]?.asBoolean == true
        val many = p["canSelectMany"]?.asBoolean == true
        val descriptor = FileChooserDescriptor(files, folders, false, false, false, many).withTitle(p.str("title").ifEmpty { "Choose Files" })
        val chosen = FileChooser.chooseFiles(descriptor, project, project.guessProjectDir()).filter { it.isInLocalFileSystem }.map { normalize(Path.of(it.path)) }
        picked.addAll(chosen)
        return JsonObject().apply {
            add("paths", JsonArray().apply { chosen.forEach { add(canonical(it)) } })
            add("facts", facts(chosen.flatMap { wrappersAbove(if (Files.isDirectory(it)) it else it.parent) }))
        }
    }

    private fun saveDialog(p: JsonObject): JsonElement? {
        val suggested = p["defaultPath"]?.takeIf { it.isJsonPrimitive }?.asString?.let { pathOf(it).fileName?.toString() } ?: "report"
        val target = pro.devsnip.api.ide.ui.Files.chooseSaveTarget(project, p.str("title").ifEmpty { "Save" }, suggested) ?: return null
        val path = normalize(target.toPath())
        saveTargets.add(path)
        return JsonPrimitive(canonical(path))
    }

    private fun inputBox(p: JsonObject): JsonElement? {
        val prompt = listOfNotNull(p["problem"]?.takeIf { it.isJsonPrimitive }?.asString, p.str("prompt")).joinToString("\n\n")
        val title = p.str("title").ifEmpty { "DevSnip Pro" }
        val value = if (p["password"]?.asBoolean == true) Messages.showPasswordDialog(project, prompt, title, null)
        else Messages.showInputDialog(project, prompt, title, null, p.str("value"), null)
        return value?.let(::JsonPrimitive)
    }

    private fun findFile(path: Path): VirtualFile? {
        require(readable(path)) { "${path.fileName} is not in the project." }
        return LocalFileSystem.getInstance().refreshAndFindFileByNioFile(path)
    }

    private fun openDocument(path: Path): JsonElement? {
        val file = findFile(path) ?: return null
        val document = ApplicationManager.getApplication().runReadAction(com.intellij.openapi.util.Computable { FileDocumentManager.getInstance().getDocument(file) }) ?: return null
        return JsonObject().apply {
            addProperty("lineCount", document.lineCount)
            if (document.textLength <= ToolHost.MAX_READ_BYTES) addProperty("text", document.text)
        }
    }

    private fun showDocument(path: Path, line: Int?) {
        val file = findFile(path) ?: error("${path.fileName} could not be found.")
        OpenFileDescriptor(project, file, (line ?: 0).coerceAtLeast(0), 0).navigate(true)
    }

    /** Writes a proposed manifest change, only right after the user confirmed it in the modal dialog. */
    private fun replaceDocument(path: Path, text: String): Boolean {
        require(under(path, workspaceRoot)) { "Only files in the project can be changed." }
        require(confirmation.consume { true }) { "The change was not confirmed." }
        val file = findFile(path) ?: return false
        val document = FileDocumentManager.getInstance().getDocument(file) ?: return false
        if (!document.isWritable) return false
        WriteCommandAction.runWriteCommandAction(project, "DevSnip Pro: Apply SCA Fix", null, { document.setText(text) })
        return true
    }

    private fun diff(path: Path, proposed: String, title: String) {
        val file = findFile(path) ?: error("${path.fileName} could not be found.")
        val factory = DiffContentFactory.getInstance()
        DiffManager.getInstance().showDiff(project, SimpleDiffRequest(title, factory.create(project, file), factory.create(project, proposed, file.fileType), "Current", "Proposed change"))
    }

    private fun openExternal(url: String) {
        when {
            url.startsWith("https://") || url.startsWith("http://") -> BrowserUtil.browse(url)
            url.startsWith("file://") -> {
                val path = normalize(Path.of(URI(url)))
                require(saveTargets.contains(path)) { "Only reports you saved can be opened." }
                BrowserUtil.browse(path.toUri().toString())
            }
        }
    }

    /** The extension's "Run in Terminal": only a command the user just confirmed, in a project folder, shown in the Run tool window. */
    private fun runInTerminal(name: String, cwd: String, command: String) {
        require(confirmation.consume { it.contains(command) }) { "The command was not confirmed." }
        val dir = if (cwd.isEmpty()) workspaceRoot else pathOf(cwd)
        require(dir != null && under(dir, workspaceRoot) && Files.isDirectory(dir)) { "Commands run in the project's folders only." }
        // If the shell cannot start, the command is copied so it can be run by hand.
        DevSnipCommandRunner.run(project, dir, name, command) { error ->
            CopyPasteManager.getInstance().setContents(java.awt.datatransfer.StringSelection(command))
            Notifications.Bus.notify(Notification(NOTIFICATIONS, "DevSnip Pro", "The command could not start (${error.message}), so it was copied. Run it in ${dir.fileName}.", NotificationType.WARNING), project)
        }
    }

    private inner class ProgressTask(title: String) : Task.Backgroundable(project, title, false) {
        val done = CountDownLatch(1)

        @Volatile
        var text: String = ""

        override fun run(indicator: ProgressIndicator) {
            indicator.isIndeterminate = true
            while (!done.await(200, TimeUnit.MILLISECONDS) && !disposed) indicator.text = text
        }
    }

    private fun startProgress(title: String): Int {
        val id = nextProgress.getAndIncrement()
        val task = ProgressTask(title.ifEmpty { "DevSnip Pro" })
        progress[id] = task
        task.queue()
        return id
    }

    // ------------------------------------------------------- project state

    /** Workspace trust (the extension's vscode.workspace.isTrusted). */
    private fun isTrusted(): Boolean = ProjectTrust.isTrusted(project)

    private fun workspaceState(): JsonObject = runCatching {
        PropertiesComponent.getInstance(project).getValue(STATE_KEY)?.let { JsonParser.parseString(it).asJsonObject }
    }.getOrNull() ?: JsonObject()

    private fun updateWorkspaceState(key: String, value: JsonElement) {
        val state = workspaceState()
        if (value.isJsonNull) state.remove(key) else state.add(key, value)
        PropertiesComponent.getInstance(project).setValue(STATE_KEY, state.toString())
    }

    override fun dispose() {
        disposed = true
        OPEN.remove(this)
        processes.values.forEach(::killTree)
        processes.clear()
        requests.values.forEach { it.cancel(true) }
        progress.values.forEach { it.done.countDown() }
        tempDirs.forEach { runCatching { it.toFile().deleteRecursively() } }
    }

    /** A request whose answer comes later (a notification action). */
    private class PendingAnswer(val answer: CompletableFuture<String?>) : RuntimeException(null, null, false, false)

    /** One modal "yes" allows one following change (a terminal command or a file edit) within a minute. */
    class Confirmation {
        private var text: String? = null
        private var at = 0L

        @Synchronized
        fun arm(message: String) {
            text = message
            at = System.currentTimeMillis()
        }

        @Synchronized
        fun consume(matches: (String) -> Boolean): Boolean {
            val armed = text ?: return false
            text = null
            return System.currentTimeMillis() - at < 60_000 && matches(armed)
        }
    }

    companion object {
        const val NOTIFICATIONS = "DevSnip Pro"
        const val SECRET_MARKER = "devsnip-secret:"
        const val NVD_KEY = "devsnip.sca.nvdApiKey"
        private const val STATE_KEY = "devsnip.scanners.workspaceState"
        const val MAX_FILE_BYTES = 64L * 1024 * 1024

        private val UI_METHODS = setOf(
            "ui.message", "ui.openDialog", "ui.saveDialog", "ui.inputBox", "doc.open", "doc.show", "doc.replace", "doc.save", "ide.diff",
            "ide.copy", "ide.openExternal", "ide.openSettings", "ide.openFolder", "terminal.run", "progress.start", "log.show", "panel.reveal",
        )
        val WRAPPERS = setOf("mvnw", "mvnw.cmd", "gradlew", "gradlew.bat")
        private val CONTEXT_FILES = setOf("pom.xml", "gradle.properties", "settings.gradle", "settings.gradle.kts", "Directory.Packages.props")
        private val RESTRICTED_HEADERS = setOf("host", "connection", "content-length", "expect", "upgrade", "cookie", "accept-encoding")

        val OPEN: MutableSet<ScannerHost> = CopyOnWriteArraySet()

        val HOME: Path = normalize(Path.of(System.getProperty("user.home")))
        val TEMP: Path = normalize(Path.of(System.getProperty("java.io.tmpdir")))
        val GLOBAL_STORAGE: Path by lazy { normalize(Path.of(PathManager.getSystemPath(), "devsnip-pro", "global-storage")) }

        private val HTTP: HttpClient by lazy {
            HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).connectTimeout(Duration.ofSeconds(20))
                .apply { ProxySelector.getDefault()?.let(::proxy) }.build()
        }

        private val buildTools: JsonObject by lazy {
            JsonParser.parseString(ScannerHost::class.java.getResourceAsStream("/webview/build-tools.json")!!.use { String(it.readBytes(), StandardCharsets.UTF_8) }).asJsonObject
        }

        fun broadcastSettings() {
            val settings = ScannerSettings.getInstance().asVsCodeSettings()
            OPEN.forEach { host -> host.post(JsonObject().apply { addProperty("type", "__settings"); add("settings", settings) }) }
        }

        /** A path the page sent: forward or back slashes, Windows drives as C:/ or /C:/. */
        fun pathOf(raw: String): Path {
            require(raw.isNotEmpty() && !raw.contains('\u0000')) { "Invalid path." }
            val text = raw.replace('\\', '/').let { if (Regex("^/[A-Za-z]:").containsMatchIn(it)) it.substring(1) else it }
            val path = Path.of(text)
            require(path.isAbsolute) { "Invalid path $raw." }
            return normalize(path)
        }

        fun normalize(path: Path): Path = path.toAbsolutePath().normalize()

        /** The page's spelling of a path: forward slashes. */
        fun canonical(path: Path): String = path.toString().replace('\\', '/')

        private fun relative(base: Path, path: Path) = base.relativize(path).toString().replace('\\', '/')

        /**
         * VS Code's glob syntax as a regular expression over `/`-separated
         * relative paths: `**` spans folders (including none), `*` and `?` stay
         * within one, `{a,b}` are alternatives, `[…]` character classes.
         */
        fun globRegex(glob: String): Regex {
            val out = StringBuilder()
            var i = 0
            var braces = 0
            while (i < glob.length) {
                val c = glob[i]
                when {
                    c == '*' && glob.getOrNull(i + 1) == '*' -> {
                        if (glob.getOrNull(i + 2) == '/') { out.append("(?:[^/]*/)*"); i += 2 } else { out.append(".*"); i++ }
                    }
                    c == '*' -> out.append("[^/]*")
                    c == '?' -> out.append("[^/]")
                    c == '{' -> { braces++; out.append("(?:") }
                    c == '}' && braces > 0 -> { braces--; out.append(")") }
                    c == ',' && braces > 0 -> out.append("|")
                    c == '[' && glob.indexOf(']', i + 1) > i -> {
                        val end = glob.indexOf(']', i + 1)
                        out.append('[').append(glob.substring(i + 1, end).replace("\\", "\\\\").replaceFirst(Regex("^!"), "^")).append(']')
                        i = end
                    }
                    else -> out.append(Regex.escape(c.toString()))
                }
                i++
            }
            return Regex(out.toString())
        }

        /** extension maven/host.ts localRepository: `<localRepository>` in ~/.m2/settings.xml, or ~/.m2/repository. */
        fun localRepository(home: Path): Path {
            runCatching {
                val settings = Files.readString(home.resolve(".m2").resolve("settings.xml")).replace(Regex("<!--[\\s\\S]*?-->"), "")
                Regex("<localRepository>\\s*([^<]+?)\\s*</localRepository>").find(settings)?.groupValues?.get(1)?.let { configured ->
                    val expanded = configured.replace("\${user.home}", home.toString()).replace(Regex("^~(?=[/\\\\])"), Regex.escapeReplacement(home.toString()))
                    return normalize(Path.of(expanded))
                }
            }
            return home.resolve(".m2").resolve("repository")
        }

        /** mvn / gradle on PATH (the shell's PATH, as a terminal sees it). */
        fun pathExecutables(path: String? = EnvironmentUtil.getValue("PATH") ?: System.getenv("PATH")): List<Path> {
            val names = if (SystemInfo.isWindows) listOf("mvn.cmd", "mvn.bat", "mvn.exe", "gradle.bat", "gradle.cmd", "gradle.exe") else listOf("mvn", "gradle")
            return path.orEmpty().split(java.io.File.pathSeparator).filter { it.isNotBlank() }.flatMap { entry ->
                names.mapNotNull { name -> runCatching { normalize(Path.of(entry, name)) }.getOrNull()?.takeIf(Files::isRegularFile) }
            }.distinct()
        }

        private fun pathEnv(): String = (EnvironmentUtil.getValue("PATH") ?: System.getenv("PATH") ?: "")
            .split(java.io.File.pathSeparator).filter { it.isNotBlank() }.joinToString(if (SystemInfo.isWindows) ";" else ":") { it.replace('\\', '/') }

        /**
         * The arguments of the extension's own build tool runs and nothing else:
         * Maven's dependency:tree into one of our temporary folders, or Gradle
         * with the scanner's init script (unchanged) from one of them.
         */
        fun allowedArguments(executable: String, args: List<String>, temp: List<Path>): Boolean {
            fun inTemp(raw: String) = runCatching { pathOf(raw) }.getOrNull()?.let { p -> temp.any { p.startsWith(it) && p != it } } == true
            fun matches(template: List<String>, placeholder: String, check: (String) -> Boolean): Boolean {
                if (template.size != args.size) return false
                return template.indices.all { i ->
                    val t = template[i]
                    val at = t.indexOf(placeholder)
                    if (at < 0) t == args[i]
                    else {
                        val prefix = t.substring(0, at)
                        val suffix = t.substring(at + placeholder.length)
                        val a = args[i]
                        a.length >= prefix.length + suffix.length && a.startsWith(prefix) && a.endsWith(suffix) && check(a.substring(prefix.length, a.length - suffix.length))
                    }
                }
            }
            val lower = executable.lowercase()
            return when {
                lower.startsWith("mvn") -> matches(buildTools.getAsJsonObject("maven").getAsJsonArray("args").map { it.asString }, "__OUTPUT__", ::inTemp)
                lower.startsWith("gradle") -> {
                    val gradle = buildTools.getAsJsonObject("gradle")
                    val script = gradle["initScript"].asString
                    val check = { raw: String -> inTemp(raw) && runCatching { Files.readString(pathOf(raw)) == script }.getOrDefault(false) }
                    listOf("args", "argsWithoutConfigurationCache").any { key -> matches(gradle.getAsJsonArray(key).map { it.asString }, "__INIT__", check) }
                }
                else -> false
            }
        }

        /** https anywhere (registries, OSV, NVD, KEV, a POM's own repositories); plain http only to this machine. */
        fun allowedUrl(uri: URI): Boolean = when (uri.scheme?.lowercase()) {
            "https" -> !uri.host.isNullOrEmpty()
            "http" -> runCatching { InetAddress.getByName(uri.host).isLoopbackAddress }.getOrDefault(false) && uri.host in setOf("127.0.0.1", "localhost", "[::1]", "::1")
            else -> false
        }

        /** The NVD key reaches only NVD: the page holds a marker, swapped here for the stored key. */
        fun resolveSecretHeader(uri: URI, value: String, lookup: (String) -> String? = { PasswordSafeSecretStore().get(it) }): String? {
            if (!value.startsWith(SECRET_MARKER)) return value
            val key = value.removePrefix(SECRET_MARKER)
            if (key != NVD_KEY || uri.host?.lowercase() != "services.nvd.nist.gov") return null
            return lookup(key)?.takeIf { it.isNotEmpty() }
        }

        private fun secretKey(key: String): String {
            require(key == NVD_KEY) { "Unknown secret $key." }
            return key
        }

        fun decodeBody(bytes: ByteArray, encoding: String): ByteArray = when (encoding.trim().lowercase()) {
            "gzip", "x-gzip" -> GZIPInputStream(ByteArrayInputStream(bytes)).use { it.readBytes() }
            "deflate" -> InflaterInputStream(ByteArrayInputStream(bytes)).use { it.readBytes() }
            else -> bytes
        }

        /** UTF-8 with malformed bytes replaced (as Node's Buffer.toString("utf8")). */
        fun decodeUtf8(bytes: ByteArray): String = StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPLACE).onUnmappableCharacter(CodingErrorAction.REPLACE)
            .decode(java.nio.ByteBuffer.wrap(bytes)).toString()

        /** For the scanners' User-Agent: the version in this plugin's own descriptor (no plugin manager API needed). */
        private val pluginVersion: String by lazy {
            runCatching {
                val xml = ScannerHost::class.java.getResourceAsStream("/META-INF/plugin.xml")!!.use { String(it.readBytes(), StandardCharsets.UTF_8) }
                Regex("<version>\\s*([^<\\s]+)\\s*</version>").find(xml)?.groupValues?.get(1)
            }.getOrNull() ?: "dev"
        }

        private fun JsonObject.str(key: String): String = get(key)?.takeIf { it.isJsonPrimitive }?.asString ?: ""
        private fun JsonArray?.orEmpty(): JsonArray = this ?: JsonArray()
    }
}

/** What each scanner last found, per project, so reopening its tab shows it (the extension keeps it for the window). */
object ScannerSessions {
    private val sessions = java.util.WeakHashMap<Project, MutableMap<String, JsonObject>>()

    @Synchronized
    fun get(project: Project, page: String): JsonObject = sessions[project]?.get(page)?.deepCopy() ?: JsonObject()

    @Synchronized
    fun save(project: Project, page: String, key: String, value: JsonElement) {
        if (key !in setOf("result", "projects")) return
        sessions.getOrPut(project) { mutableMapOf() }.getOrPut(page) { JsonObject() }.add(key, value)
    }
}

/** The scanners' log channels ("DevSnip Pro: OSV Scanner"), shown in an editor tab like VS Code's Output panel. */
object ScannerLogs {
    private const val MAX_CHARS = 2_000_000
    private val logs = ConcurrentHashMap<String, StringBuilder>()
    private val time = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS")

    fun append(channel: String, lines: List<Pair<String, String>>) {
        val log = logs.getOrPut(channel) { StringBuilder() }
        synchronized(log) {
            val now = LocalDateTime.now().format(time)
            for ((level, text) in lines) log.append(now).append(" [").append(level.ifEmpty { "info" }).append("] ").append(text).append('\n')
            if (log.length > MAX_CHARS) log.delete(0, log.length - MAX_CHARS)
        }
    }

    fun text(channel: String): String = logs[channel]?.let { synchronized(it) { it.toString() } } ?: ""

    fun show(project: Project, channel: String) {
        val manager = FileEditorManager.getInstance(project)
        val name = "${channel.ifEmpty { "DevSnip Pro" }}.log"
        manager.openFiles.filter { it is LightVirtualFile && it.name == name }.forEach(manager::closeFile)
        val file = LightVirtualFile(name, PlainTextFileType.INSTANCE, text(channel).ifEmpty { "Nothing logged yet. Run a scan first.\n" })
        file.isWritable = false
        OpenFileDescriptor(project, file).navigate(true)
    }
}
