package pro.devsnip.api.ide.engine

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonNull
import com.google.gson.JsonObject
import com.google.gson.JsonPrimitive
import com.intellij.diff.DiffContentFactory
import com.intellij.diff.DiffManager
import com.intellij.diff.requests.SimpleDiffRequest
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.ide.BrowserUtil
import com.intellij.ide.actions.RevealFileAction
import com.intellij.ide.util.PropertiesComponent
import com.intellij.notification.Notification
import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationType
import com.intellij.notification.Notifications
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileChooser.FileChooser
import com.intellij.openapi.fileChooser.FileChooserDescriptor
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.guessProjectDir
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.util.SystemInfo
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.openapi.wm.WindowManager
import com.intellij.util.EnvironmentUtil
import pro.devsnip.api.ide.PasswordSafeSecretStore
import pro.devsnip.api.ide.web.ProjectTrust
import pro.devsnip.api.ide.web.RestClientHost
import pro.devsnip.api.ide.web.RestClientUi
import pro.devsnip.api.ide.web.ScannerHost
import pro.devsnip.api.ide.web.ScannerHost.Companion.canonical
import pro.devsnip.api.ide.web.ScannerHost.Companion.normalize
import pro.devsnip.api.ide.web.ScannerHost.Companion.pathOf
import pro.devsnip.api.ide.web.ScannerLogs
import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.BasicFileAttributes
import java.util.Base64
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * The IDE side of DevSnip Pro's extension host: what the extension's code
 * asks VS Code, Node's fs and child_process, or axios for arrives here
 * (webview/shims/vscode-host.js, host-fs.js, host-child_process.js, axios.js).
 *
 *  - files: the project, the extension's own folder and storage, temporary
 *    folders it made, files the user picked, and the build tool caches and
 *    configuration files the extension reads; nothing else is written;
 *  - processes: package managers, build tools and Node only, in a
 *    trusted project, in the project's folders (the extension validates every
 *    command line before it asks);
 *  - HTTP and TLS: through the REST client's Kotlin engine (IDE proxy settings);
 *  - editors, documents, dialogs, notifications, quick picks, terminals,
 *    progress, the clipboard, the password safe and the extension's settings.
 */
class EngineHost(private val project: Project, private val engine: DevSnipEngine) : Disposable {
    /** An error with a Node error code (ENOENT, EACCES), which the extension checks. */
    class CodedException(message: String, val code: String) : Exception(message)

    private val workspaceRoot: Path? get() = project.guessProjectDir()?.takeIf { it.isInLocalFileSystem }?.let { normalize(Path.of(it.path)) }
    private val picked = CopyOnWriteArraySet<Path>()
    private val saveTargets = CopyOnWriteArraySet<Path>()
    private val tempDirs = CopyOnWriteArraySet<Path>()
    private val processes = ConcurrentHashMap<Int, Process>()
    private val progress = ConcurrentHashMap<Int, ProgressTask>()
    private val nextProgress = AtomicInteger(1)
    private val watchers = EngineWatchers(project, engine)
    private val terminals = EngineTerminals(project, engine)
    private val documents = EngineDocuments(project, engine)
    private val databases = DbBridge()
    private val restHost = RestClientHost(
        service = pro.devsnip.api.ide.ApiClientService.getInstance(),
        ui = NoUi,
        post = { message -> engine.post(message) },
        background = { task -> ApplicationManager.getApplication().executeOnPooledThread(task) },
    )

    @Volatile
    private var disposed = false

    init {
        com.intellij.openapi.util.Disposer.register(engine, documents)
    }

    // ------------------------------------------------------------ dispatch

    fun handle(message: JsonObject) {
        val id = message["id"] ?: return
        val method = message.str("method")
        val params = message["params"] as? JsonObject ?: JsonObject()
        if (method in REST_METHODS) {
            restHost.handle(message)
            return
        }
        val reply = { result: JsonElement?, error: Throwable? ->
            if (!disposed) engine.post(JsonObject().apply {
                addProperty("type", "__rpcResult")
                add("id", id)
                if (error != null) {
                    addProperty("error", error.message ?: error.javaClass.simpleName)
                    add("errorData", (error as? DbBridge.DbException)?.data ?: JsonObject().apply { (error as? CodedException)?.code?.let { addProperty("code", it) } })
                } else {
                    add("result", result ?: JsonNull.INSTANCE)
                }
            })
        }
        if (method in UI_METHODS) {
            ApplicationManager.getApplication().invokeLater({
                try {
                    when (val answer = ui(method, params)) {
                        is Later -> answer.future.whenComplete { value, error -> reply(value, error) }
                        else -> reply(answer as JsonElement?, null)
                    }
                } catch (e: Throwable) {
                    reply(null, e)
                }
            }, project.disposed)
        } else {
            ApplicationManager.getApplication().executeOnPooledThread {
                try {
                    reply(io(method, params), null)
                } catch (e: Throwable) {
                    reply(null, e)
                }
            }
        }
    }

    /** The synchronous calls (fs.readFileSync and friends), answered on a pooled thread while the page waits. */
    fun sync(method: String, params: JsonObject): JsonElement? = when (method) {
        "fs.stat", "fs.readBytes", "fs.writeBytes", "fs.readdir", "fs.mkdir", "fs.rename", "fs.delete" -> io(method, params)
        else -> throw CodedException("Unknown request $method", "EINVAL")
    }

    /** A UI answer that comes later (a notification button, a quick pick). */
    private class Later(val future: CompletableFuture<JsonElement?>)

    private fun io(method: String, p: JsonObject): JsonElement? = when (method) {
        "globalState.update" -> { EngineState.getInstance().update(p.str("key"), p["value"]?.takeIf { !it.isJsonNull }, engine); null }
        "workspaceState.update" -> { updateWorkspaceState(p.str("key"), p["value"] ?: JsonNull.INSTANCE); null }
        "config.update" -> { DevSnipSettings.getInstance().set(p.str("key"), p["value"]?.takeIf { !it.isJsonNull }); null }
        "fs.stat" -> stat(pathOf(p.str("path")))
        "fs.readBytes" -> readBytes(pathOf(p.str("path")), p["offset"]?.asLong, p["length"]?.asInt)
        "fs.writeBytes" -> writeBytes(pathOf(p.str("path")), Base64.getDecoder().decode(p.str("base64")), p["append"]?.asBoolean == true)
        "fs.mkdir" -> pathOf(p.str("path")).let { require(writable(it)) { denied(it) }; Files.createDirectories(it); refresh(it); null }
        "fs.delete" -> delete(pathOf(p.str("path")), p["recursive"]?.asBoolean == true)
        "fs.readdir" -> readdir(pathOf(p.str("path")))
        "fs.rename" -> move(pathOf(p.str("from")), pathOf(p.str("to")), p["overwrite"]?.asBoolean == true, copy = false)
        "fs.copy" -> move(pathOf(p.str("from")), pathOf(p.str("to")), p["overwrite"]?.asBoolean == true, copy = true)
        "fs.mkdtemp" -> JsonPrimitive(makeTemp(p.str("prefix")))
        "fs.watch" -> { watchers.watch(p["id"].asInt, p["base"]?.takeIf { it.isJsonPrimitive }?.let { pathOf(it.asString) } ?: workspaceRoot, p.str("pattern")); null }
        "fs.unwatch" -> { watchers.unwatch(p["id"].asInt); null }
        "ws.findFiles" -> findFiles(p)
        "events.documents" -> { documents.listen(); null }
        // Loading a document reads the file: off the UI thread, in a read action.
        "doc.open" -> documents.open(pathOf(p.str("path")).also { if (!readable(it)) throw CodedException(denied(it), "EACCES") })
        "secrets.get" -> PasswordSafeSecretStore().get(secretKey(p.str("key")))?.let(::JsonPrimitive)
        "secrets.store" -> { PasswordSafeSecretStore().set(secretKey(p.str("key")), p["value"]?.takeIf { it.isJsonPrimitive }?.asString); null }
        "log.append" -> { ScannerLogs.append(p.str("channel"), (p["lines"] as? JsonArray ?: JsonArray()).mapNotNull { it as? JsonObject }.map { it.str("level") to it.str("text") }); null }
        "log.clear" -> { ScannerLogs.clear(p.str("channel")); null }
        "progress.report" -> { progress[p["id"]?.asInt]?.let { task -> p["message"]?.takeIf { it.isJsonPrimitive }?.let { task.text = it.asString }; p["fraction"]?.takeIf { it.isJsonPrimitive }?.let { task.fraction = it.asDouble } }; null }
        "progress.end" -> { progress.remove(p["id"]?.asInt)?.done?.countDown(); null }
        "process.spawn" -> spawn(p)
        "process.kill" -> { processes.remove(p["id"]?.asInt)?.let(::killTree); null }
        "process.input" -> { processes[p["id"]?.asInt]?.let { process -> if (p["end"]?.asBoolean == true) process.outputStream.close() else { process.outputStream.write(p.str("text").toByteArray()); process.outputStream.flush() } }; null }
        "db.connect", "db.query", "db.call", "db.cancel", "db.close", "mongo.connect", "mongo.op", "mongo.close", "redis.connect", "redis.exec", "redis.close" -> databases.handle(method, p)
        "ide.native" -> { ApplicationManager.getApplication().invokeLater({ DevSnipNative.run(project, p.str("id"), p["args"] as? JsonArray ?: JsonArray()) }, project.disposed); null }
        else -> throw CodedException("Unknown request $method", "EINVAL")
    }

    private fun ui(method: String, p: JsonObject): Any? = when (method) {
        "ui.message" -> message(p)
        "ui.quickPick" -> Later(EngineQuickPick.show(project, engine, p))
        "ui.quickPickHide" -> { EngineQuickPick.hide(p["id"]?.asInt ?: -1); null }
        "ui.inputBox" -> inputBox(p)
        "ui.openDialog" -> openDialog(p)
        "ui.saveDialog" -> saveDialog(p)
        "doc.show" -> documents.show(pathOf(p.str("path")).also { require(readable(it)) { denied(it) } }, p["selection"] as? JsonObject, p["preserveFocus"]?.asBoolean == true)
        "doc.showUntitled" -> documents.showUntitled(p.str("name"), p.str("text"), p.str("languageId"))
        "doc.save" -> { documents.save(pathOf(p.str("path"))); null }
        "doc.saveAll" -> { FileDocumentManager.getInstance().saveAllDocuments(); null }
        "editor.edit" -> EngineEditors.edit(project, p)
        "editor.insertSnippet" -> EngineEditors.insertSnippet(project, p)
        "editor.select" -> { EngineEditors.select(project, p); null }
        "editor.reveal" -> { EngineEditors.reveal(project, p); null }
        "workspace.applyEdit" -> EngineEditors.applyWorkspaceEdit(project, p) { path -> writable(path) }
        "ide.command" -> { DevSnipNative.ideCommand(project, p.str("id"), p["args"] as? JsonArray ?: JsonArray()); null }
        "ide.copy" -> { CopyPasteManager.getInstance().setContents(java.awt.datatransfer.StringSelection(p.str("text"))); null }
        "ide.paste" -> CopyPasteManager.getInstance().getContents<String>(java.awt.datatransfer.DataFlavor.stringFlavor)?.let(::JsonPrimitive)
        "ide.openExternal" -> { openExternal(p.str("url")); null }
        "ide.openSettings" -> { openSettings(p.str("query")); null }
        "ide.notify" -> { notify(p.str("kind"), p.str("text")); null }
        "ide.diff" -> { diff(p); null }
        "ide.reveal" -> { reveal(pathOf(p.str("path"))); null }
        "ide.revealInOs" -> { pathOf(p.str("path")).takeIf(::readable)?.let { RevealFileAction.openFile(it.toFile()) }; null }
        "ide.showToolWindow" -> { ToolWindowManager.getInstance(project).getToolWindow(pro.devsnip.api.ide.ui.ApiToolWindowFactory.TOOL_WINDOW_ID)?.activate(null); null }
        "log.show" -> { ScannerLogs.show(project, p.str("channel")); null }
        "progress.start" -> JsonPrimitive(startProgress(p.str("title"), p["cancellable"]?.asBoolean == true))
        "statusBar.set" -> { statusBar(p); null }
        "terminal.create" -> { terminals.create(p, ::terminalDirectory); null }
        "terminal.send" -> { terminals.send(p["id"].asInt, p.str("text"), p["newline"]?.asBoolean != false); null }
        "terminal.show" -> { terminals.show(p["id"].asInt); null }
        "terminal.dispose" -> { terminals.dispose(p["id"].asInt); null }
        else -> throw CodedException("Unknown request $method", "EINVAL")
    }

    // --------------------------------------------------------------- files

    private fun under(path: Path, dir: Path?) = dir != null && path.startsWith(dir)

    private fun pickedRoots() = picked.map { if (Files.isDirectory(it)) it else it.parent ?: it }

    /** Build tool caches and configuration the extension reads (Maven and Gradle repositories, Maven settings). */
    private fun readOnlyRoots(): List<Path> {
        val home = ScannerHost.HOME
        val gradleHome = EnvironmentUtil.getValue("GRADLE_USER_HOME")?.takeIf { it.isNotBlank() }?.let { normalize(Path.of(it)) } ?: home.resolve(".gradle")
        return listOf(
            ScannerHost.localRepository(home), gradleHome.resolve("caches"), home.resolve(".m2").resolve("settings.xml"),
        )
    }

    private fun ownRoots(): List<Path> = listOf(ExtensionFiles.root, DevSnipEngine.GLOBAL_STORAGE, workspaceStorage())

    fun readable(path: Path): Boolean = under(path, workspaceRoot) || ownRoots().any { under(path, it) } || tempDirs.any { under(path, it) } ||
        saveTargets.contains(path) || pickedRoots().any { under(path, it) } || readOnlyRoots().any { under(path, it) } || EngineDocuments.isOpenInProject(project, path)

    fun writable(path: Path): Boolean = under(path, workspaceRoot) || ownRoots().any { under(path, it) } || tempDirs.any { under(path, it) } ||
        saveTargets.contains(path) || picked.contains(path)

    private fun workspaceStorage(): Path = normalize(Path.of(com.intellij.openapi.application.PathManager.getSystemPath(), "devsnip-pro", "workspace-storage", project.locationHash.replace(Regex("[^A-Za-z0-9_-]"), "_")))

    private fun denied(path: Path) = "EACCES: DevSnip Pro does not read or write ${path.fileName ?: path} outside the project and its own folders."

    /** Existence, size and kind are answered for any path (the extension looks for executables on PATH); contents only for readable ones. */
    private fun stat(path: Path): JsonElement? {
        if (!Files.exists(path)) return null
        val attributes = runCatching { Files.readAttributes(path, BasicFileAttributes::class.java) }.getOrNull() ?: return null
        return JsonObject().apply {
            addProperty("type", if (attributes.isDirectory) 2 else 1)
            addProperty("size", attributes.size())
            addProperty("mtime", attributes.lastModifiedTime().toMillis())
            addProperty("ctime", attributes.creationTime().toMillis())
            addProperty("executable", attributes.isRegularFile && (SystemInfo.isWindows || Files.isExecutable(path)))
            if (!Files.isWritable(path)) addProperty("readonly", true)
        }
    }

    private fun readBytes(path: Path, offset: Long?, length: Int?): JsonElement? {
        if (!readable(path)) throw CodedException(denied(path), "EACCES")
        // An open, unsaved document is read as the editor shows it, as VS Code's file system does not, but the extension expects the latest text.
        if (!Files.isRegularFile(path)) return if (Files.isDirectory(path)) throw CodedException("EISDIR: ${path.fileName} is a folder", "EISDIR") else null
        val size = Files.size(path)
        if (offset != null && length != null) {
            Files.newByteChannel(path).use { channel ->
                channel.position(offset)
                val buffer = java.nio.ByteBuffer.allocate(length.coerceIn(0, 1 shl 20))
                channel.read(buffer)
                return JsonPrimitive(Base64.getEncoder().encodeToString(buffer.array().copyOf(buffer.position())))
            }
        }
        if (size > MAX_FILE_BYTES) throw CodedException("EFBIG: ${path.fileName} is larger than ${MAX_FILE_BYTES / 1024 / 1024} MB.", "EFBIG")
        return JsonPrimitive(Base64.getEncoder().encodeToString(Files.readAllBytes(path)))
    }

    private fun writeBytes(path: Path, bytes: ByteArray, append: Boolean): JsonElement? {
        if (!writable(path)) throw CodedException(denied(path), "EACCES")
        path.parent?.let(Files::createDirectories)
        if (append) Files.write(path, bytes, java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND) else Files.write(path, bytes)
        refresh(path)
        return null
    }

    private fun delete(path: Path, recursive: Boolean): JsonElement? {
        if (!writable(path) || path == workspaceRoot || ownRoots().contains(path)) throw CodedException(denied(path), "EACCES")
        if (!Files.exists(path)) throw CodedException("ENOENT: ${path.fileName} does not exist", "ENOENT")
        if (Files.isDirectory(path)) {
            if (!recursive && Files.list(path).use { it.findAny().isPresent }) throw CodedException("ENOTEMPTY: ${path.fileName} is not empty", "ENOTEMPTY")
            path.toFile().deleteRecursively()
        } else {
            Files.delete(path)
        }
        tempDirs.remove(path)
        refresh(path)
        return null
    }

    private fun readdir(path: Path): JsonElement {
        if (!readable(path)) throw CodedException(denied(path), "EACCES")
        if (!Files.isDirectory(path)) throw CodedException("ENOENT: no such directory ${path.fileName}", if (Files.exists(path)) "ENOTDIR" else "ENOENT")
        return JsonArray().apply {
            Files.list(path).use { entries ->
                entries.sorted().forEach { entry ->
                    add(JsonObject().apply {
                        addProperty("name", entry.fileName.toString())
                        addProperty("type", if (Files.isDirectory(entry)) 2 else 1)
                    })
                }
            }
        }
    }

    private fun move(from: Path, to: Path, overwrite: Boolean, copy: Boolean): JsonElement? {
        if (!(if (copy) readable(from) else writable(from)) || !writable(to)) throw CodedException(denied(if (writable(to)) from else to), "EACCES")
        if (!overwrite && Files.exists(to)) throw CodedException("EEXIST: ${to.fileName} exists", "EEXIST")
        to.parent?.let(Files::createDirectories)
        if (copy) Files.copy(from, to, StandardCopyOption.REPLACE_EXISTING)
        else runCatching { Files.move(from, to, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE) }.getOrElse { Files.move(from, to, StandardCopyOption.REPLACE_EXISTING) }
        refresh(from)
        refresh(to)
        return null
    }

    private fun makeTemp(prefix: String): String {
        val target = pathOf(prefix)
        val name = target.fileName.toString().replace(Regex("[^A-Za-z0-9._-]"), "")
        val dir = normalize(Files.createTempDirectory(ScannerHost.TEMP, name))
        tempDirs.add(dir)
        return canonical(dir)
    }

    /** VS Code's findFiles: a glob over the project (or a folder), skipping the excluded paths. */
    private fun findFiles(p: JsonObject): JsonElement {
        val base = p["base"]?.takeIf { it.isJsonPrimitive }?.let { pathOf(it.asString) } ?: workspaceRoot ?: return JsonArray()
        if (!readable(base)) throw CodedException(denied(base), "EACCES")
        val include = ScannerHost.globRegex(p.str("include"))
        val exclude = p["exclude"]?.takeIf { it.isJsonPrimitive && it.asString.isNotEmpty() }?.asString?.let(ScannerHost::globRegex)
        val max = p["max"]?.takeIf { it.isJsonPrimitive }?.asInt ?: 100_000
        val defaults = p["useDefaultExcludes"]?.asBoolean == true
        val paths = JsonArray()
        if (!Files.isDirectory(base)) return paths
        Files.walkFileTree(base, object : SimpleFileVisitor<Path>() {
            override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
                if (disposed || paths.size() >= max) return FileVisitResult.TERMINATE
                val rel = base.relativize(dir).toString().replace('\\', '/')
                if (rel.isEmpty()) return FileVisitResult.CONTINUE
                if (defaults && dir.fileName.toString() in DEFAULT_EXCLUDED_DIRS) return FileVisitResult.SKIP_SUBTREE
                return if (exclude?.matches("$rel/x") == true) FileVisitResult.SKIP_SUBTREE else FileVisitResult.CONTINUE
            }

            override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                if (!attrs.isRegularFile) return FileVisitResult.CONTINUE
                val rel = base.relativize(file).toString().replace('\\', '/')
                if (include.matches(rel) && exclude?.matches(rel) != true) paths.add(canonical(file))
                return if (paths.size() >= max) FileVisitResult.TERMINATE else FileVisitResult.CONTINUE
            }

            override fun visitFileFailed(file: Path, exc: java.io.IOException) = FileVisitResult.CONTINUE
        })
        return paths
    }

    private fun refresh(path: Path) {
        LocalFileSystem.getInstance().findFileByNioFile(path)?.let { VfsUtil.markDirtyAndRefresh(true, false, false, it) }
            ?: path.parent?.let { parent -> LocalFileSystem.getInstance().findFileByNioFile(parent)?.let { VfsUtil.markDirtyAndRefresh(true, false, true, it) } }
    }

    // ----------------------------------------------------------- processes

    /** Folders commands may run in: the project's. */
    private fun terminalDirectory(raw: String?): Path {
        val root = workspaceRoot
        val dir = raw?.takeIf { it.isNotEmpty() }?.let(::pathOf) ?: root ?: ScannerHost.HOME
        return if (root == null || under(dir, root)) dir else root
    }

    private fun spawn(p: JsonObject): JsonElement? {
        val id = p["id"].asInt
        var command = p.str("command")
        var args = (p["args"] as? JsonArray ?: JsonArray()).map { it.asString }
        // planSpawn's cmd.exe line for .cmd shims is undone, so the IDE quotes the program and its arguments itself.
        if (SystemInfo.isWindows && File(command).name.lowercase() in setOf("cmd.exe", "cmd") && args.size == 5 && args[3] == "/c") {
            val parts = splitCmdLine(args[4].removeSurrounding("\""))
            command = parts.firstOrNull() ?: throw CodedException("Nothing to run.", "EINVAL")
            args = parts.drop(1)
        }
        val name = File(command).name.lowercase().removeSuffix(".exe").removeSuffix(".cmd").removeSuffix(".bat").removeSuffix(".ps1")
        if (name !in ALLOWED_PROGRAMS) throw CodedException("DevSnip Pro only runs package managers, build tools and Node ($name is not one).", "EACCES")
        val root = workspaceRoot
        val cwd = p.str("cwd").takeIf { it.isNotEmpty() }?.let(::pathOf) ?: root ?: ScannerHost.HOME
        if (name !in CHECK_PROGRAMS) {
            if (!ProjectTrust.isTrusted(project)) throw CodedException("Trust this project to let DevSnip Pro run $name in it.", "EACCES")
            if (root == null || !under(cwd, root)) throw CodedException("Commands run in the project's folders only.", "EACCES")
        }
        if (!Files.isDirectory(cwd)) throw CodedException("ENOENT: ${cwd.fileName} is not a folder", "ENOENT")
        val line = GeneralCommandLine(listOf(command) + args)
            .withWorkDirectory(cwd.toFile())
            .withParentEnvironmentType(GeneralCommandLine.ParentEnvironmentType.CONSOLE)
            .withCharset(StandardCharsets.UTF_8)
        (p["env"] as? JsonObject)?.entrySet()?.forEach { (key, value) ->
            // The extension passes its own environment with additions; only the additions and PATH matter here.
            if (value.isJsonPrimitive && (key.uppercase() == "PATH" || key !in DevSnipEngine.childEnvironment())) line.withEnvironment(key, value.asString)
        }
        val process = try {
            line.createProcess()
        } catch (e: Exception) {
            throw CodedException("spawn $command ENOENT: ${e.message}", "ENOENT")
        }
        processes[id] = process
        fun event(kind: String, fill: JsonObject.() -> Unit) {
            if (!disposed) engine.post(JsonObject().apply { addProperty("type", "__process"); addProperty("id", id); addProperty("event", kind); fill() })
        }
        fun pump(stream: java.io.InputStream, kind: String) = ApplicationManager.getApplication().executeOnPooledThread {
            val buffer = CharArray(8192)
            stream.reader(StandardCharsets.UTF_8).use { reader ->
                while (true) {
                    val n = runCatching { reader.read(buffer) }.getOrDefault(-1)
                    if (n < 0) break
                    val text = String(buffer, 0, n)
                    event(kind) { addProperty("data", text) }
                }
            }
        }
        val out = pump(process.inputStream, "stdout")
        val err = pump(process.errorStream, "stderr")
        ApplicationManager.getApplication().executeOnPooledThread {
            val code = runCatching { process.waitFor() }.getOrNull()
            runCatching { out.get(10, TimeUnit.SECONDS) }
            runCatching { err.get(10, TimeUnit.SECONDS) }
            val killed = processes.remove(id) == null
            event("exit") { if (code != null && !killed && code < 128) addProperty("code", code) else add("code", JsonNull.INSTANCE) }
        }
        return null
    }

    private fun killTree(process: Process) {
        process.toHandle().descendants().forEach { it.destroyForcibly() }
        process.destroyForcibly()
    }

    // ------------------------------------------------------------------- UI

    private fun message(p: JsonObject): Any? {
        val items = (p["items"] as? JsonArray ?: JsonArray()).map { it.asString }
        val text = p.str("message")
        val detail = p["detail"]?.takeIf { it.isJsonPrimitive }?.asString
        val kind = p.str("kind")
        if (p["modal"]?.asBoolean == true) {
            // VS Code's modal adds Cancel; Escape and Cancel answer undefined.
            val buttons = (items + "Cancel").toTypedArray()
            val icon = when (kind) { "warning" -> Messages.getWarningIcon(); "error" -> Messages.getErrorIcon(); else -> Messages.getInformationIcon() }
            val choice = Messages.showDialog(project, if (detail != null) "$text\n\n$detail" else text, "DevSnip Pro", buttons, 0, icon)
            return if (choice in items.indices) JsonPrimitive(choice) else null
        }
        val pending = CompletableFuture<JsonElement?>()
        val type = when (kind) { "warning" -> NotificationType.WARNING; "error" -> NotificationType.ERROR; else -> NotificationType.INFORMATION }
        val notification = Notification(NOTIFICATIONS, "DevSnip Pro", escape(text), type)
        items.forEachIndexed { index, item -> notification.addAction(NotificationAction.createSimpleExpiring(item) { pending.complete(JsonPrimitive(index)) }) }
        // A notification closed without a choice answers undefined, as VS Code does.
        notification.whenExpired { pending.complete(null) }
        Notifications.Bus.notify(notification, project)
        return if (items.isEmpty()) null else Later(pending)
    }

    private fun inputBox(p: JsonObject): JsonElement? {
        val prompt = listOfNotNull(p["problem"]?.takeIf { it.isJsonPrimitive }?.asString, p["prompt"]?.takeIf { it.isJsonPrimitive }?.asString, p["placeHolder"]?.takeIf { it.isJsonPrimitive }?.asString?.let { "e.g. $it" }).joinToString("\n\n")
        val title = p.str("title").ifEmpty { "DevSnip Pro" }
        val value = if (p["password"]?.asBoolean == true) Messages.showPasswordDialog(project, prompt, title, null)
        else Messages.showInputDialog(project, prompt, title, null, p.str("value"), null)
        return value?.let(::JsonPrimitive)
    }

    private fun openDialog(p: JsonObject): JsonElement {
        val files = p["canSelectFiles"]?.asBoolean != false
        val folders = p["canSelectFolders"]?.asBoolean == true
        val many = p["canSelectMany"]?.asBoolean == true
        val extensions = (p["extensions"] as? JsonArray ?: JsonArray()).map { it.asString.lowercase() }.filter { it.isNotEmpty() && it != "*" }
        val descriptor = FileChooserDescriptor(files, folders, false, false, false, many).withTitle(p.str("title").ifEmpty { "Open" })
        if (extensions.isNotEmpty() && !folders) descriptor.withFileFilter { file -> file.isDirectory || file.extension?.lowercase() in extensions }
        val start = p["defaultPath"]?.takeIf { it.isJsonPrimitive }?.asString?.let { LocalFileSystem.getInstance().findFileByPath(it) } ?: project.guessProjectDir()
        val chosen = FileChooser.chooseFiles(descriptor, project, start).filter { it.isInLocalFileSystem }.map { normalize(Path.of(it.path)) }
        picked.addAll(chosen)
        return JsonArray().apply { chosen.forEach { add(canonical(it)) } }
    }

    private fun saveDialog(p: JsonObject): JsonElement? {
        val suggested = p["defaultPath"]?.takeIf { it.isJsonPrimitive }?.asString?.let { pathOf(it).fileName?.toString() } ?: "untitled"
        val target = pro.devsnip.api.ide.ui.Files.chooseSaveTarget(project, p.str("title").ifEmpty { "Save" }, suggested) ?: return null
        val path = normalize(target.toPath())
        saveTargets.add(path)
        return JsonPrimitive(canonical(path))
    }

    private fun openExternal(url: String) {
        when {
            url.startsWith("https://") || url.startsWith("http://") || url.startsWith("mailto:") -> BrowserUtil.browse(url)
            url.startsWith("file://") -> {
                val path = normalize(Path.of(java.net.URI(url)))
                if (readable(path)) BrowserUtil.browse(path.toUri().toString())
            }
            url.startsWith("command:") -> DevSnipNative.ideCommand(project, url.removePrefix("command:").substringBefore('?'), JsonArray())
        }
    }

    private fun openSettings(query: String) {
        if (query.startsWith("devsnip.osvScanner") || query.startsWith("devsnip.sca")) ShowSettingsUtil.getInstance().showSettingsDialog(project, pro.devsnip.api.ide.web.ScannerSettingsConfigurable::class.java)
        else ShowSettingsUtil.getInstance().showSettingsDialog(project, DevSnipSettingsConfigurable::class.java)
    }

    private fun notify(kind: String, text: String) {
        val type = when (kind) { "warning" -> NotificationType.WARNING; "error" -> NotificationType.ERROR; else -> NotificationType.INFORMATION }
        Notifications.Bus.notify(Notification(NOTIFICATIONS, "DevSnip Pro", escape(text), type), project)
    }

    private fun diff(p: JsonObject) {
        val factory = DiffContentFactory.getInstance()
        fun content(pathKey: String, textKey: String, near: VirtualFile?) = p[pathKey]?.takeIf { it.isJsonPrimitive }?.asString?.let { raw ->
            val path = pathOf(raw)
            if (!readable(path)) throw CodedException(denied(path), "EACCES")
            LocalFileSystem.getInstance().refreshAndFindFileByNioFile(path)?.let { factory.create(project, it) }
        } ?: factory.create(project, p.str(textKey), near?.fileType)
        val leftFile = p["leftPath"]?.takeIf { it.isJsonPrimitive }?.asString?.let { LocalFileSystem.getInstance().findFileByNioFile(pathOf(it)) }
        DiffManager.getInstance().showDiff(project, SimpleDiffRequest(p.str("title").ifEmpty { "DevSnip Pro" }, content("leftPath", "leftText", leftFile), content("rightPath", "rightText", leftFile), "Current", "Proposed"))
    }

    private fun reveal(path: Path) {
        if (!readable(path)) return
        val file = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(path) ?: return
        com.intellij.ide.projectView.ProjectView.getInstance(project).select(null, file, true)
    }

    private fun statusBar(p: JsonObject) {
        val text = p["text"]?.takeIf { it.isJsonPrimitive }?.asString
        WindowManager.getInstance().getStatusBar(project)?.info = text ?: ""
    }

    private inner class ProgressTask(title: String, cancellable: Boolean, val id: Int) : Task.Backgroundable(project, title, cancellable) {
        val done = CountDownLatch(1)

        @Volatile
        var text: String = ""

        @Volatile
        var fraction: Double? = null

        override fun run(indicator: ProgressIndicator) {
            indicator.isIndeterminate = true
            while (!done.await(150, TimeUnit.MILLISECONDS) && !disposed) {
                indicator.text2 = text
                fraction?.let { indicator.isIndeterminate = false; indicator.fraction = it }
                if (indicator.isCanceled) {
                    engine.post(JsonObject().apply { addProperty("type", "__progressCancel"); addProperty("id", id) })
                    done.await(30, TimeUnit.SECONDS)
                    break
                }
            }
        }
    }

    private fun startProgress(title: String, cancellable: Boolean): Int {
        val id = nextProgress.getAndIncrement()
        val task = ProgressTask(title.ifEmpty { "DevSnip Pro" }, cancellable, id)
        progress[id] = task
        task.queue()
        return id
    }

    // ---------------------------------------------------------------- state

    fun workspaceState(): JsonObject = runCatching {
        PropertiesComponent.getInstance(project).getValue(STATE_KEY)?.let { com.google.gson.JsonParser.parseString(it).asJsonObject }
    }.getOrNull() ?: JsonObject()

    @Synchronized
    private fun updateWorkspaceState(key: String, value: JsonElement) {
        val state = workspaceState()
        if (value.isJsonNull) state.remove(key) else state.add(key, value)
        PropertiesComponent.getInstance(project).setValue(STATE_KEY, state.toString())
    }

    /** The extension's secrets go to the IDE password safe under its own names (devsnip.*). */
    private fun secretKey(key: String): String {
        if (!key.startsWith("devsnip") || key.length > 200) throw CodedException("Unknown secret $key.", "EINVAL")
        return key
    }

    override fun dispose() {
        disposed = true
        processes.values.forEach(::killTree)
        processes.clear()
        progress.values.forEach { it.done.countDown() }
        restHost.dispose()
        watchers.dispose()
        terminals.disposeAll()
        ApplicationManager.getApplication().executeOnPooledThread { databases.closeAll() }
        tempDirs.forEach { runCatching { it.toFile().deleteRecursively() } }
    }

    /** The REST engine's dialogs are never used by the extension host (it has its own); storage is not used either. */
    private object NoUi : RestClientUi {
        override fun chooseSaveFile(title: String, defaultName: String, extensions: List<String>): File? = null
        override fun chooseOpenFile(title: String, extensions: List<String>): File? = null
        override fun notify(kind: String, text: String) = Unit
        override fun setStorage(key: String, value: JsonElement?) = Unit
    }

    companion object {
        const val NOTIFICATIONS = "DevSnip Pro"
        private const val STATE_KEY = "devsnip.engine.workspaceState"
        const val MAX_FILE_BYTES = 256L * 1024 * 1024

        private val REST_METHODS = setOf("http.raw", "http.cancel", "tls.probe")
        private val UI_METHODS = setOf(
            "ui.message", "ui.quickPick", "ui.quickPickHide", "ui.inputBox", "ui.openDialog", "ui.saveDialog",
            "doc.show", "doc.showUntitled", "doc.save", "doc.saveAll",
            "editor.edit", "editor.insertSnippet", "editor.select", "editor.reveal", "workspace.applyEdit",
            "ide.command", "ide.copy", "ide.paste", "ide.openExternal", "ide.openSettings", "ide.notify", "ide.diff", "ide.reveal", "ide.revealInOs", "ide.showToolWindow",
            "log.show", "progress.start", "statusBar.set", "terminal.create", "terminal.send", "terminal.show", "terminal.dispose",
        )
        private val DEFAULT_EXCLUDED_DIRS = setOf("node_modules", ".git", ".idea", ".gradle", ".svn", ".hg")

        /** What the extension starts: package managers, build tools, runtimes it checks. */
        val ALLOWED_PROGRAMS = setOf(
            "npm", "npx", "pnpm", "yarn", "bun", "node", "pip", "pip3", "python", "python3", "py", "uv", "poetry", "pipenv",
            "mvn", "mvnw", "gradle", "gradlew", "dotnet", "flutter", "dart", "composer", "which", "where",
        )

        /** Lookups that change nothing (which / where) also run in untrusted projects. */
        private val CHECK_PROGRAMS = setOf("which", "where")

        /** Undoes the cmd.exe quoting of the extension's planSpawn: "program" "arg" ... */
        fun splitCmdLine(line: String): List<String> {
            val out = mutableListOf<String>()
            val current = StringBuilder()
            var quoted = false
            var started = false
            var i = 0
            while (i < line.length) {
                val c = line[i]
                when {
                    quoted && c == '"' && line.getOrNull(i + 1) == '"' -> { current.append('"'); i++ }
                    quoted && c == '"' -> quoted = false
                    quoted -> current.append(c)
                    c == '"' -> { quoted = true; started = true }
                    c == ' ' -> if (started) { out += current.toString(); current.clear(); started = false }
                    else -> { current.append(c); started = true }
                }
                i++
            }
            if (started) out += current.toString()
            return out
        }

        private fun escape(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\n", "<br>")
        private fun JsonObject.str(key: String): String = get(key)?.takeIf { it.isJsonPrimitive }?.asString ?: ""
    }
}
