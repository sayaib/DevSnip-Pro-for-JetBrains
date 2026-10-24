package pro.devsnip.api.ide.web

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonNull
import com.google.gson.JsonObject
import com.google.gson.JsonPrimitive
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.fileChooser.FileChooser
import com.intellij.openapi.fileChooser.FileChooserDescriptor
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.FileEditorManagerEvent
import com.intellij.openapi.fileEditor.FileEditorManagerListener
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.fileEditor.TextEditor
import com.intellij.openapi.fileTypes.FileTypeManager
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.guessProjectDir
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.testFramework.LightVirtualFile
import pro.devsnip.api.ide.ui.Files as FileDialogs
import java.io.File
import java.lang.ref.WeakReference
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.charset.StandardCharsets
import java.nio.file.FileSystems
import java.util.Base64
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The IDE side of a toolkit tool page: what `src/toolkits/runner.ts` does in
 * the VS Code extension host, with the same rules and messages. The tool
 * itself runs in the page.
 */
class ToolHost(private val project: Project, private val toolId: String, private val post: (JsonElement) -> Unit) {
    private fun notice(kind: String, text: String) = post(JsonObject().apply {
        addProperty("type", "notice")
        addProperty("kind", kind)
        addProperty("text", text)
    })

    fun handle(message: JsonObject) {
        try {
            when (message.str("type")) {
                "__rpc" -> rpc(message)
                "__ran" -> Unit
                "readEditor" -> readEditor(message.str("field"))
                "copy" -> {
                    CopyPasteManager.getInstance().setContents(java.awt.datatransfer.StringSelection(message.str("text")))
                    notice("success", "Copied to the clipboard.")
                }
                "insert" -> insertAtCursor(message.str("text"), message.str("language"))
                "open" -> openInEditor(message.str("text"), message.str("language"))
                "download" -> saveAs(message.str("text"), message.str("language"), message.str("fileName"))?.let { notice("success", "Saved ${it.name}.") }
                "save", "writeAll" -> {
                    val files = (message["files"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
                        .filter { it["path"]?.isJsonPrimitive == true && it["content"]?.isJsonPrimitive == true }
                    if (files.isEmpty()) return
                    val (kind, text) = writeFiles(files)
                    notice(kind, text)
                }
            }
        } catch (e: Exception) {
            notice("error", "That did not work: ${e.message ?: e.javaClass.simpleName}")
        }
    }

    // ------------------------------------------------------------- editor

    private fun readEditor(field: String) {
        val editor = EditorTracker.lastEditor(project)
        if (editor == null) {
            notice("warning", "Open a file in an editor first.")
            return
        }
        val selection = editor.selectionModel.selectedText
        val text = if (selection.isNullOrEmpty()) editor.document.text else selection
        if (text.length > MAX_READ_BYTES) {
            notice("warning", "That file is larger than 5 MB; select the part you need first.")
            return
        }
        val name = FileEditorManager.getInstance(project).selectedFiles.firstOrNull { it.fileType != DevSnipFileType }?.name
            ?: com.intellij.openapi.fileEditor.FileDocumentManager.getInstance().getFile(editor.document)?.name ?: "editor"
        post(JsonObject().apply {
            addProperty("type", "editorText")
            addProperty("field", field)
            addProperty("text", text)
            addProperty("source", "${if (selection.isNullOrEmpty()) "all of" else "selection in"} $name")
        })
    }

    private fun insertAtCursor(text: String, language: String) {
        val editor = EditorTracker.lastEditor(project)
        if (editor == null || !editor.document.isWritable) {
            openInEditor(text, language)
            return
        }
        WriteCommandAction.runWriteCommandAction(project, "Insert DevSnip Pro Output", null, {
            val carets = editor.caretModel.allCarets.sortedByDescending { it.offset }
            for (caret in carets) {
                if (caret.hasSelection()) editor.document.replaceString(caret.selectionStart, caret.selectionEnd, text)
                else editor.document.insertString(caret.offset, text)
            }
        })
        com.intellij.openapi.wm.IdeFocusManager.getInstance(project).requestFocus(editor.contentComponent, true)
    }

    private fun openInEditor(text: String, language: String) {
        val ext = EXTENSIONS[language] ?: "txt"
        val file = LightVirtualFile("${WebviewAssets.toolTitle(toolId)}.$ext", FileTypeManager.getInstance().getFileTypeByExtension(ext), text)
        FileEditorManager.getInstance(project).openFile(file, true)
    }

    // -------------------------------------------------------------- files

    /** "Save as…": the user picks the location, so any path they choose is allowed. */
    private fun saveAs(text: String, language: String, suggested: String): File? {
        val ext = EXTENSIONS[language] ?: "txt"
        val base = suggested.split(Regex("[\\\\/]")).last().replace(Regex("[^\\w.@ -]+"), " ").replace(Regex("\\s+"), "-").replace(Regex("^-+|-+$"), "").take(80).ifEmpty { "output" }
        val name = if (Regex("\\.[A-Za-z0-9]{1,12}$").containsMatchIn(base) || base == "Dockerfile") base else "$base.$ext"
        val target = FileDialogs.chooseSaveTarget(project, "Save As", name) ?: return null
        target.parentFile?.mkdirs()
        target.writeText(text, StandardCharsets.UTF_8)
        LocalFileSystem.getInstance().refreshAndFindFileByIoFile(target)
        return target
    }

    private fun writeFiles(files: List<JsonObject>): Pair<String, String> {
        val root = project.guessProjectDir() ?: return "error" to "Open a folder first: files are written into the workspace."
        data class Target(val path: String, val content: String, val append: Boolean, val exists: Boolean)
        val targets = mutableListOf<Target>()
        for (file in files) {
            val raw = file.str("path")
            val safe = safeRelativePath(raw) ?: return "error" to "Refused to write \"$raw\": only paths inside the workspace are allowed."
            targets += Target(safe, file.str("content"), file.str("mode") == "append", root.findFileByRelativePath(safe) != null)
        }
        val overwrite = targets.filter { it.exists && !it.append }
        if (overwrite.isNotEmpty()) {
            val list = overwrite.take(8).joinToString("\n") { it.path }
            val title = if (overwrite.size == 1) "This file already exists" else "${overwrite.size} files already exist"
            val answer = Messages.showOkCancelDialog(project, "$title:\n$list${if (overwrite.size > 8) "\n…" else ""}\n\nOverwrite?", "DevSnip Pro", "Overwrite", "Cancel", Messages.getWarningIcon())
            if (answer != Messages.OK) return "warning" to "Nothing was written."
        }
        val written = WriteCommandAction.writeCommandAction(project).withName("Write DevSnip Pro Files").compute<List<VirtualFile>, Exception> {
            targets.map { t ->
                val dir = VfsUtil.createDirectoryIfMissing(root, t.path.substringBeforeLast('/', "")) ?: error("Could not create the folder for ${t.path}")
                val name = t.path.substringAfterLast('/')
                val vf = dir.findChild(name) ?: dir.createChildData(this, name)
                var content = t.content
                if (t.append && t.exists) {
                    val existing = String(vf.contentsToByteArray(), StandardCharsets.UTF_8)
                    content = existing + (if (existing.isNotEmpty() && !existing.endsWith("\n")) "\n" else "") + content
                }
                vf.setBinaryContent(content.toByteArray(StandardCharsets.UTF_8))
                vf
            }
        }
        FileEditorManager.getInstance(project).openTextEditor(OpenFileDescriptor(project, written.first()), true)
        val appended = targets.count { it.append && it.exists }
        val text = if (targets.size == 1) "${if (appended > 0) "Appended to" else "Wrote"} ${targets[0].path}."
        else "Wrote ${targets.size} files: ${targets.take(6).joinToString(", ") { it.path }}${if (targets.size > 6) "…" else ""}."
        return "success" to text
    }

    // ----------------------------------------------------- tool context

    private fun rpc(message: JsonObject) {
        val id = message["id"]
        val params = message.getAsJsonObject("params") ?: JsonObject()
        fun reply(result: JsonElement?, error: String? = null) = post(JsonObject().apply {
            addProperty("type", "__rpcResult")
            add("id", id)
            if (error != null) addProperty("error", error) else add("result", result ?: JsonNull.INSTANCE)
        })
        val method = message.str("method")
        if (method == "files.pick" || method == "files.save") {
            // Dialogs run on the EDT; everything else reads the disk or network off it.
            try {
                reply(if (method == "files.pick") pickFile(params) else saveFromTool(params))
            } catch (e: Exception) {
                reply(null, e.message ?: e.javaClass.simpleName)
            }
            return
        }
        ApplicationManager.getApplication().executeOnPooledThread {
            val outcome = runCatching {
                when (method) {
                    "storage.set" -> {
                        ApplicationManager.getApplication().invokeAndWait { DevSnipUiState.getInstance().setToolStorage(params.str("key"), params["value"] ?: JsonNull.INSTANCE) }
                        JsonNull.INSTANCE
                    }
                    "network.isPortFree" -> JsonPrimitive(isPortFree(params["port"].asInt))
                    "workspace.exists" -> JsonPrimitive(workspaceFile(params.str("path"))?.exists() == true)
                    "workspace.readFile" -> workspaceFile(params.str("path"))?.takeIf { it.isFile && it.length() <= MAX_READ_BYTES }?.let { JsonPrimitive(it.readText(StandardCharsets.UTF_8)) } ?: JsonNull.INSTANCE
                    "workspace.findFiles" -> findFiles(params.str("glob"), params["limit"]?.takeIf { it.isJsonPrimitive }?.asInt ?: 100)
                    else -> error("Unknown request $method")
                }
            }
            ApplicationManager.getApplication().invokeLater { outcome.onSuccess { reply(it) }.onFailure { reply(null, it.message ?: it.javaClass.simpleName) } }
        }
    }

    private fun root(): File? = project.guessProjectDir()?.let { File(it.path) }

    private fun workspaceFile(relative: String): File? {
        val safe = safeRelativePath(relative) ?: throw IllegalArgumentException("Invalid workspace path: $relative")
        return root()?.resolve(safe)
    }

    /** Workspace search, excluding dependency and build folders (the extension's SEARCH_EXCLUDE). */
    private fun findFiles(glob: String, limit: Int): JsonArray {
        val base = root() ?: return JsonArray()
        val matcher = FileSystems.getDefault().getPathMatcher("glob:$glob")
        val out = JsonArray()
        val skip = setOf("node_modules", ".git", "dist", "build", "out", ".next", "target", ".venv", "venv", "__pycache__", ".gradle", ".idea", "Pods", ".dart_tool")
        base.walkTopDown().onEnter { it == base || it.name !in skip }.filter { it.isFile }.forEach { file ->
            if (out.size() >= limit) return out
            val relative = file.relativeTo(base).invariantSeparatorsPath
            if (matcher.matches(java.nio.file.Path.of(relative)) || (!glob.contains('/') && matcher.matches(java.nio.file.Path.of(file.name)))) out.add(relative)
        }
        return out
    }

    private fun pickFile(params: JsonObject): JsonElement {
        val descriptor = FileChooserDescriptor(true, false, false, false, false, false).withTitle(params.str("title").ifEmpty { "Choose a File" })
        val chosen = FileChooser.chooseFile(descriptor, project, project.guessProjectDir()) ?: return JsonNull.INSTANCE
        val maxBytes = params["maxBytes"]?.takeIf { it.isJsonPrimitive }?.asLong ?: Long.MAX_VALUE
        if (chosen.length > maxBytes) {
            throw IllegalArgumentException("${chosen.name} is ${chosen.length / 1024 / 1024} MB; files up to ${maxBytes / 1024 / 1024} MB can be converted.")
        }
        return JsonObject().apply {
            addProperty("name", chosen.name)
            addProperty("path", chosen.path)
            addProperty("base64", Base64.getEncoder().encodeToString(chosen.contentsToByteArray()))
        }
    }

    private fun saveFromTool(params: JsonObject): JsonElement {
        val options = params.getAsJsonObject("options") ?: JsonObject()
        val name = options.str("suggestedName").replace(Regex("[\\\\/:*?\"<>|]+"), "-").ifEmpty { "output.txt" }
        val target = FileDialogs.chooseSaveTarget(project, "Save", name) ?: return JsonNull.INSTANCE
        target.writeText(params.str("content"), StandardCharsets.UTF_8)
        LocalFileSystem.getInstance().refreshAndFindFileByIoFile(target)
        return JsonPrimitive(target.path)
    }

    companion object {
        const val MAX_READ_BYTES = 5 * 1024 * 1024

        val EXTENSIONS = mapOf(
            "typescript" to "ts", "javascript" to "js", "tsx" to "tsx", "jsx" to "jsx", "json" to "json", "jsonl" to "jsonl", "yaml" to "yaml",
            "python" to "py", "shell" to "sh", "sql" to "sql", "markdown" to "md", "go" to "go", "java" to "java", "kotlin" to "kt", "swift" to "swift",
            "dart" to "dart", "xml" to "xml", "html" to "html", "css" to "css", "graphql" to "graphql", "dockerfile" to "Dockerfile", "nginx" to "conf",
            "terraform" to "tf", "dotenv" to "env", "properties" to "properties", "csv" to "csv", "tsv" to "tsv", "diff" to "diff", "http" to "http",
            "php" to "php", "ruby" to "rb", "groovy" to "gradle", "prisma" to "prisma", "ignore" to "gitignore", "log" to "log", "text" to "txt",
        )

        /** runner.ts safeRelativePath: plain workspace-relative paths only, no absolute paths, no "..". */
        fun safeRelativePath(input: String): String? {
            val normalized = input.replace('\\', '/').replace(Regex("^\\./+"), "").trim()
            if (normalized.isEmpty() || normalized.startsWith("/") || Regex("^[A-Za-z]:").containsMatchIn(normalized)) return null
            val parts = normalized.split("/")
            if (parts.any { it == ".." || it.isEmpty() }) return null
            return parts.joinToString("/")
        }

        /** runner.ts isPortFree: in use when something accepts a connection (IPv4 or IPv6 loopback). */
        fun isPortFree(port: Int): Boolean {
            fun listening(host: String) = try {
                Socket().use { it.connect(InetSocketAddress(host, port), 400); true }
            } catch (_: Exception) {
                false
            }
            return !listening("127.0.0.1") && !listening("::1")
        }

        private fun JsonObject.str(key: String): String = get(key)?.takeIf { it.isJsonPrimitive }?.asString ?: ""
        private fun JsonArray?.orEmpty(): JsonArray = this ?: JsonArray()
    }
}

/**
 * The text editor last used in a project, for "Use editor" and "Insert" (the
 * tool tab itself takes focus). A project service, so it goes with the
 * project, and only weakly holds the editor.
 */
@Service(Service.Level.PROJECT)
class EditorTracker(private val project: Project) : Disposable {
    @Volatile
    private var last: WeakReference<Editor>? = null
    private val installed = AtomicBoolean()

    private fun start() {
        if (!installed.compareAndSet(false, true)) return
        project.messageBus.connect(this).subscribe(FileEditorManagerListener.FILE_EDITOR_MANAGER, object : FileEditorManagerListener {
            override fun selectionChanged(event: FileEditorManagerEvent) {
                (event.newEditor as? TextEditor)?.let { last = WeakReference(it.editor) }
            }
        })
        ApplicationManager.getApplication().invokeLater({
            if (last == null) (FileEditorManager.getInstance(project).selectedEditor as? TextEditor)?.let { last = WeakReference(it.editor) }
        }, project.disposed)
    }

    private fun editor(): Editor? {
        last?.get()?.takeIf { !it.isDisposed }?.let { return it }
        return FileEditorManager.getInstance(project).allEditors.filterIsInstance<TextEditor>().firstOrNull()?.editor
    }

    override fun dispose() {
        last = null
    }

    companion object {
        fun install(project: Project) {
            if (!project.isDisposed) project.service<EditorTracker>().start()
        }

        fun lastEditor(project: Project): Editor? = project.service<EditorTracker>().also { it.start() }.editor()
    }
}
