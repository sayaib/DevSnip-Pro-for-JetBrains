package pro.devsnip.api.ide.engine

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonPrimitive
import com.intellij.codeInsight.template.TemplateManager
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.LogicalPosition
import com.intellij.openapi.editor.ScrollType
import com.intellij.openapi.editor.event.DocumentEvent
import com.intellij.openapi.editor.event.DocumentListener
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileDocumentManagerListener
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.fileEditor.TextEditor
import com.intellij.openapi.fileTypes.FileTypeManager
import com.intellij.openapi.fileTypes.PlainTextFileType
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.text.StringUtil
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.testFramework.LightVirtualFile
import com.intellij.util.Alarm
import pro.devsnip.api.ide.web.EditorTracker
import pro.devsnip.api.ide.web.ScannerHost.Companion.canonical
import pro.devsnip.api.ide.web.ScannerHost.Companion.normalize
import pro.devsnip.api.ide.web.ScannerHost.Companion.pathOf
import java.nio.file.Path

/**
 * Text editors and documents for the extension host, as VS Code's
 * TextEditor / TextDocument: the state sent with every command (the active
 * editor, its selections and text, unsaved documents), and edits, which are
 * applied as one undoable IDE command.
 */
object EngineEditors {
    /** Documents larger than this are described without their text (VS Code would load them; the extension never needs that much). */
    private const val MAX_TEXT = 8 * 1024 * 1024

    /** Untitled documents the host showed, by their editor id. */
    private val untitled = java.util.concurrent.ConcurrentHashMap<String, java.lang.ref.WeakReference<VirtualFile>>()

    /** The editor state for `window.activeTextEditor`, `visibleTextEditors` and `workspace.textDocuments` (EDT). */
    fun state(project: Project): JsonObject {
        val manager = FileEditorManager.getInstance(project)
        val selected = manager.selectedEditor
        val active = (selected as? TextEditor)?.editor ?: if (selected == null) EditorTracker.lastEditor(project) else null
        val visible = (active ?: EditorTracker.lastEditor(project))?.let { listOf(it) }.orEmpty()
        return JsonObject().apply {
            add("active", active?.let { info(it) } ?: com.google.gson.JsonNull.INSTANCE)
            add("visible", JsonArray().apply { visible.forEach { add(info(it)) } })
            add("documents", JsonArray().apply {
                var budget = MAX_TEXT
                for (document in FileDocumentManager.getInstance().unsavedDocuments) {
                    val file = FileDocumentManager.getInstance().getFile(document) ?: continue
                    if (!file.isInLocalFileSystem) continue
                    val text = if (document.textLength <= budget) document.text.also { budget -= it.length } else null
                    add(documentInfo(file, document, text))
                }
            })
        }
    }

    fun info(editor: Editor): JsonObject {
        val document = editor.document
        val file = FileDocumentManager.getInstance().getFile(document)
        val info = documentInfo(file, document, if (document.textLength <= MAX_TEXT) document.text else null)
        info.addProperty("id", editorId(file))
        info.add("selections", JsonArray().apply {
            for (caret in editor.caretModel.allCarets) {
                val start = position(document, caret.selectionStart)
                val end = position(document, caret.selectionEnd)
                val atStart = caret.offset == caret.selectionStart && caret.hasSelection()
                add(JsonObject().apply {
                    add("anchor", if (atStart) end else start)
                    add("active", if (atStart) start else end)
                })
            }
        })
        info.addProperty("tabSize", editor.settings.getTabSize(editor.project))
        info.addProperty("insertSpaces", !editor.settings.isUseTabCharacter(editor.project))
        return info
    }

    private fun documentInfo(file: VirtualFile?, document: Document, text: String?): JsonObject = JsonObject().apply {
        if (file != null && file.isInLocalFileSystem) addProperty("path", canonical(normalize(Path.of(file.path))))
        else addProperty("name", file?.name ?: "Untitled")
        addProperty("languageId", VsCodeLanguages.idOf(file?.name ?: "", file?.fileType?.name))
        addProperty("version", document.modificationStamp)
        addProperty("lineCount", document.lineCount.coerceAtLeast(1))
        addProperty("isDirty", FileDocumentManager.getInstance().isDocumentUnsaved(document))
        val separator = file?.detectedLineSeparator ?: "\n"
        addProperty("eol", if (separator == "\r\n") "crlf" else "lf")
        if (text != null) addProperty("text", text)
    }

    private fun editorId(file: VirtualFile?) = when {
        file == null -> "untitled:?"
        file.isInLocalFileSystem -> "e:" + canonical(normalize(Path.of(file.path)))
        else -> "u:" + file.name
    }

    private fun position(document: Document, offset: Int): JsonObject {
        val line = document.getLineNumber(offset)
        return JsonObject().apply {
            addProperty("line", line)
            addProperty("character", offset - document.getLineStartOffset(line))
        }
    }

    /** VS Code's line / character position as an offset, clamped as VS Code validates positions. */
    fun offset(document: Document, position: JsonObject): Int {
        if (document.lineCount == 0) return 0
        val line = position["line"].asInt
        if (line < 0) return 0
        if (line >= document.lineCount) return document.textLength
        val start = document.getLineStartOffset(line)
        val end = document.getLineEndOffset(line)
        return (start + position["character"].asInt.coerceAtLeast(0)).coerceAtMost(end)
    }

    /** The document an edit addresses: a project file by path, or an untitled document the host opened. */
    private fun documentFor(project: Project, p: JsonObject): Pair<VirtualFile, Document>? {
        val path = p["path"]?.takeIf { it.isJsonPrimitive }?.asString
        val file = if (path != null) LocalFileSystem.getInstance().refreshAndFindFileByNioFile(pathOf(path))
        else p["id"]?.takeIf { it.isJsonPrimitive }?.asString?.let { untitled[it]?.get() }
        file ?: return null
        val document = FileDocumentManager.getInstance().getDocument(file) ?: return null
        return file to document
    }

    private fun editorsOf(project: Project, file: VirtualFile): List<Editor> =
        FileEditorManager.getInstance(project).getAllEditors(file).filterIsInstance<TextEditor>().map { it.editor }

    /** TextEditor.edit: all edits in one undoable command. */
    fun edit(project: Project, p: JsonObject): JsonElement {
        val (file, document) = documentFor(project, p) ?: return JsonObject().apply { addProperty("ok", false) }
        // The extension edits documents open in the editor; a read-only one is refused as VS Code refuses it.
        if (!document.isWritable) return JsonObject().apply { addProperty("ok", false) }
        val edits = (p["edits"] as? JsonArray ?: JsonArray()).map { it.asJsonObject }
        val ranges = edits.map { e ->
            val range = e.getAsJsonObject("range")
            Triple(offset(document, range.getAsJsonObject("start")), offset(document, range.getAsJsonObject("end")), StringUtil.convertLineSeparators(e["text"].asString))
        }.sortedByDescending { it.first }
        WriteCommandAction.runWriteCommandAction(project, "DevSnip Pro", null, {
            for ((start, end, text) in ranges) document.replaceString(start, end, text)
        })
        if (p["eol"]?.isJsonPrimitive == true) {
            val separator = if (p["eol"].asString == "crlf") "\r\n" else "\n"
            if (file.detectedLineSeparator != separator) file.detectedLineSeparator = separator
        }
        return JsonObject().apply {
            addProperty("ok", true)
            addProperty("text", document.text)
            addProperty("version", document.modificationStamp)
        }
    }

    /** TextEditor.insertSnippet: a live template with VS Code's tab stops, placeholders and choices. */
    fun insertSnippet(project: Project, p: JsonObject): JsonElement {
        val (file, document) = documentFor(project, p) ?: return JsonObject().apply { addProperty("ok", false) }
        if (!document.isWritable) return JsonObject().apply { addProperty("ok", false) }
        val ranges = (p["ranges"] as? JsonArray ?: JsonArray()).map { it.asJsonObject }
        val first = ranges.firstOrNull() ?: return JsonObject().apply { addProperty("ok", false) }
        val editor = editorsOf(project, file).firstOrNull() ?: openEditor(project, OpenFileDescriptor(project, file), true)
            ?: return JsonObject().apply { addProperty("ok", false) }
        val start = offset(document, first.getAsJsonObject("start"))
        val end = offset(document, first.getAsJsonObject("end"))
        val template = VsCodeSnippets.toTemplate(project, p["snippet"].asString, file.name)
        WriteCommandAction.runWriteCommandAction(project, "DevSnip Pro: Insert Snippet", null, {
            document.deleteString(start, end)
            editor.caretModel.removeSecondaryCarets()
            editor.caretModel.moveToOffset(start)
        })
        TemplateManager.getInstance(project).startTemplate(editor, template)
        return JsonObject().apply {
            addProperty("ok", true)
            addProperty("text", document.text)
        }
    }

    fun select(project: Project, p: JsonObject) {
        val (file, document) = documentFor(project, p) ?: return
        val editor = editorsOf(project, file).firstOrNull() ?: return
        val selections = (p["selections"] as? JsonArray ?: return).map { it.asJsonObject }
        val first = selections.firstOrNull() ?: return
        val anchor = offset(document, first.getAsJsonObject("anchor"))
        val active = offset(document, first.getAsJsonObject("active"))
        editor.caretModel.removeSecondaryCarets()
        editor.caretModel.moveToOffset(active)
        editor.selectionModel.setSelection(minOf(anchor, active), maxOf(anchor, active))
        editor.scrollingModel.scrollToCaret(ScrollType.MAKE_VISIBLE)
    }

    fun reveal(project: Project, p: JsonObject) {
        val (file, document) = documentFor(project, p) ?: return
        val editor = editorsOf(project, file).firstOrNull() ?: return
        val start = offset(document, p.getAsJsonObject("range").getAsJsonObject("start"))
        val line = document.getLineNumber(start)
        editor.scrollingModel.scrollTo(LogicalPosition(line, 0), ScrollType.CENTER)
    }

    /** workspace.applyEdit: text edits per file and file operations, as one undoable command. */
    fun applyWorkspaceEdit(project: Project, p: JsonObject, writable: (Path) -> Boolean): JsonElement {
        val failed = JsonObject().apply { addProperty("ok", false) }
        val files = (p["files"] as? JsonArray ?: JsonArray()).map { it.asJsonObject }
        for (op in files) {
            val path = pathOf(op["path"].asString)
            if (!writable(path)) return failed
            when (op["kind"].asString) {
                "create" -> {
                    if (java.nio.file.Files.exists(path) && !(op["overwrite"]?.asBoolean ?: false)) {
                        if (op["ignoreIfExists"]?.asBoolean == true) continue
                        return failed
                    }
                    path.parent?.let(java.nio.file.Files::createDirectories)
                    val bytes = op["contents"]?.takeIf { it.isJsonPrimitive }?.asString?.let { java.util.Base64.getDecoder().decode(it) } ?: ByteArray(0)
                    java.nio.file.Files.write(path, bytes)
                    LocalFileSystem.getInstance().refreshAndFindFileByNioFile(path)
                }
                "delete" -> {
                    val vf = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(path) ?: continue
                    WriteCommandAction.runWriteCommandAction(project, "DevSnip Pro", null, { vf.delete(EngineEditors) })
                }
            }
        }
        val targets = (p["edits"] as? JsonArray ?: JsonArray()).map { it.asJsonObject }.map { entry ->
            val path = pathOf(entry["path"].asString)
            if (!writable(path)) return failed
            val file = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(path) ?: return failed
            val document = FileDocumentManager.getInstance().getDocument(file) ?: return failed
            if (!document.isWritable) return failed
            document to entry.getAsJsonArray("edits").map { it.asJsonObject }.map { e ->
                val range = e.getAsJsonObject("range")
                Triple(offset(document, range.getAsJsonObject("start")), offset(document, range.getAsJsonObject("end")), StringUtil.convertLineSeparators(e["text"].asString))
            }.sortedByDescending { it.first }
        }
        if (targets.isNotEmpty()) WriteCommandAction.runWriteCommandAction(project, "DevSnip Pro", null, {
            for ((document, edits) in targets) for ((start, end, text) in edits) document.replaceString(start, end, text)
        })
        return JsonObject().apply { addProperty("ok", true) }
    }

    /** Opens a file at the descriptor's place and gives its text editor (OpenFileDescriptor.navigate, as the IDE opens files). */
    fun openEditor(project: Project, descriptor: OpenFileDescriptor, focus: Boolean): Editor? {
        descriptor.navigate(focus)
        return editorsOf(project, descriptor.file).firstOrNull()
    }

    fun rememberUntitled(id: String, file: VirtualFile) {
        untitled[id] = java.lang.ref.WeakReference(file)
    }
}

/** Opening, showing, saving and watching documents for the extension host. */
class EngineDocuments(private val project: Project, private val engine: DevSnipEngine) : Disposable {
    @Volatile
    private var listening: Disposable? = null
    private val alarm = Alarm(Alarm.ThreadToUse.SWING_THREAD, this)
    private val changed = java.util.LinkedHashSet<Document>()

    fun open(path: Path): JsonElement? {
        val file = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(path) ?: return null
        val document = ApplicationManager.getApplication().runReadAction(com.intellij.openapi.util.Computable { FileDocumentManager.getInstance().getDocument(file) }) ?: return binaryInfo(file)
        return describe(file, document)
    }

    private fun binaryInfo(file: VirtualFile): JsonElement = JsonObject().apply {
        addProperty("path", canonical(normalize(Path.of(file.path))))
        addProperty("languageId", VsCodeLanguages.idOf(file.name, file.fileType.name))
        addProperty("version", file.modificationStamp)
        addProperty("lineCount", 1)
        addProperty("text", "")
    }

    private fun describe(file: VirtualFile, document: Document): JsonObject = JsonObject().apply {
        addProperty("path", canonical(normalize(Path.of(file.path))))
        addProperty("languageId", VsCodeLanguages.idOf(file.name, file.fileType.name))
        addProperty("version", document.modificationStamp)
        addProperty("lineCount", document.lineCount.coerceAtLeast(1))
        addProperty("isDirty", FileDocumentManager.getInstance().isDocumentUnsaved(document))
        if (document.textLength <= 8 * 1024 * 1024) addProperty("text", document.text)
    }

    /** window.showTextDocument: opens the file (at the selection) and describes the editor. */
    fun show(path: Path, selection: JsonObject?, preserveFocus: Boolean): JsonElement? {
        val file = LocalFileSystem.getInstance().refreshAndFindFileByNioFile(path) ?: throw EngineHost.CodedException("ENOENT: ${path.fileName} could not be found", "ENOENT")
        val document = FileDocumentManager.getInstance().getDocument(file)
        val start = selection?.getAsJsonObject("start")
        val descriptor = if (document != null && start != null) OpenFileDescriptor(project, file, EngineEditors.offset(document, start)) else OpenFileDescriptor(project, file)
        val editor = EngineEditors.openEditor(project, descriptor, !preserveFocus)
        if (editor != null && selection != null && document != null) {
            val from = EngineEditors.offset(document, selection.getAsJsonObject("start"))
            val to = EngineEditors.offset(document, selection.getAsJsonObject("end"))
            if (to > from) editor.selectionModel.setSelection(from, to)
        }
        return editor?.let { EngineEditors.info(it) } ?: document?.let { describe(file, it) }
    }

    /** An untitled document with content (workspace.openTextDocument({ content, language })) in a scratch tab. */
    fun showUntitled(name: String, text: String, languageId: String): JsonElement {
        val fileType = VsCodeLanguages.fileTypeFor(languageId) ?: PlainTextFileType.INSTANCE
        val file = LightVirtualFile("${name.ifEmpty { "Untitled" }}.${fileType.defaultExtension.ifEmpty { "txt" }}", fileType, text)
        val editor = EngineEditors.openEditor(project, OpenFileDescriptor(project, file), true)
        val info = editor?.let { EngineEditors.info(it) } ?: JsonObject()
        info.addProperty("languageId", languageId)
        info["id"]?.asString?.let { EngineEditors.rememberUntitled(it, file) }
        return info
    }

    fun save(path: Path) {
        val file = LocalFileSystem.getInstance().findFileByNioFile(path) ?: return
        FileDocumentManager.getInstance().getDocument(file)?.let(FileDocumentManager.getInstance()::saveDocument)
    }

    /** onDidChangeTextDocument / onDidSaveTextDocument: sent while the extension listens, changes batched. */
    @Synchronized
    fun listen() {
        if (listening != null) return
        val parent = Disposer.newDisposable("DevSnip Pro document events")
        Disposer.register(this, parent)
        listening = parent
        EditorFactory.getInstance().eventMulticaster.addDocumentListener(object : DocumentListener {
            override fun documentChanged(event: DocumentEvent) {
                val file = FileDocumentManager.getInstance().getFile(event.document) ?: return
                if (!file.isInLocalFileSystem || file.isDirectory) return
                synchronized(changed) { changed += event.document }
                alarm.cancelAllRequests()
                alarm.addRequest(::flush, 250)
            }
        }, parent)
        project.messageBus.connect(parent).subscribe(FileDocumentManagerListener.TOPIC, object : FileDocumentManagerListener {
            override fun beforeDocumentSaving(document: Document) {
                val file = FileDocumentManager.getInstance().getFile(document) ?: return
                if (!file.isInLocalFileSystem) return
                ApplicationManager.getApplication().invokeLater({
                    engine.post(JsonObject().apply {
                        addProperty("type", "__docSaved")
                        addProperty("path", canonical(normalize(Path.of(file.path))))
                        addProperty("languageId", VsCodeLanguages.idOf(file.name, file.fileType.name))
                        addProperty("version", document.modificationStamp)
                    })
                }, project.disposed)
            }
        })
    }

    private fun flush() {
        val documents = synchronized(changed) { changed.toList().also { changed.clear() } }
        for (document in documents) {
            val file = FileDocumentManager.getInstance().getFile(document) ?: continue
            engine.post(describe(file, document).apply { addProperty("type", "__docChanged") })
        }
    }

    override fun dispose() = Unit

    companion object {
        /** A file open in one of the project's editors (VS Code lets the extension read open documents). */
        fun isOpenInProject(project: Project, path: Path): Boolean =
            FileEditorManager.getInstance(project).openFiles.any { it.isInLocalFileSystem && runCatching { normalize(Path.of(it.path)) == path }.getOrDefault(false) }
    }
}

/** VS Code language ids (what `document.languageId` is) for the IDE's files, by name. */
object VsCodeLanguages {
    private val BY_EXTENSION = mapOf(
        "js" to "javascript", "mjs" to "javascript", "cjs" to "javascript", "jsx" to "javascriptreact",
        "ts" to "typescript", "mts" to "typescript", "cts" to "typescript", "tsx" to "typescriptreact",
        "py" to "python", "pyw" to "python", "pyi" to "python", "java" to "java", "kt" to "kotlin", "kts" to "kotlin",
        "cs" to "csharp", "csx" to "csharp", "dart" to "dart", "swift" to "swift", "html" to "html", "htm" to "html", "xhtml" to "html",
        "vue" to "vue", "svelte" to "svelte", "php" to "php", "json" to "json", "jsonc" to "jsonc", "json5" to "json5", "jsonl" to "jsonl",
        "yaml" to "yaml", "yml" to "yaml", "xml" to "xml", "xsd" to "xml", "svg" to "svg", "sql" to "sql", "md" to "markdown", "markdown" to "markdown",
        "go" to "go", "rs" to "rust", "rb" to "ruby", "c" to "c", "h" to "c", "cpp" to "cpp", "cc" to "cpp", "cxx" to "cpp", "hpp" to "cpp", "hh" to "cpp",
        "sh" to "shellscript", "bash" to "shellscript", "zsh" to "shellscript", "ps1" to "powershell", "psm1" to "powershell", "bat" to "bat", "cmd" to "bat",
        "toml" to "toml", "ini" to "ini", "cfg" to "ini", "properties" to "properties", "log" to "log", "txt" to "plaintext", "css" to "css", "scss" to "scss",
        "sass" to "sass", "less" to "less", "gradle" to "groovy", "groovy" to "groovy", "lua" to "lua", "r" to "r", "pl" to "perl", "pm" to "perl",
        "vb" to "vb", "coffee" to "coffeescript", "scala" to "scala", "graphql" to "graphql", "gql" to "graphql", "proto" to "proto3", "tf" to "terraform",
        "dockerfile" to "dockerfile", "csv" to "csv", "tsv" to "tsv", "env" to "dotenv", "fs" to "fsharp", "ex" to "elixir", "exs" to "elixir",
    )

    fun idOf(name: String, fileTypeName: String? = null): String {
        val lower = name.lowercase()
        return when {
            lower == "dockerfile" || lower.startsWith("dockerfile.") || lower.endsWith(".dockerfile") -> "dockerfile"
            lower == "makefile" || lower == "gnumakefile" -> "makefile"
            Regex("^(docker-)?compose(\\..+)?\\.ya?ml$").matches(lower) -> "dockercompose"
            lower.startsWith(".env") -> "dotenv"
            lower == "jenkinsfile" -> "groovy"
            lower == "gemfile" || lower == "rakefile" -> "ruby"
            else -> BY_EXTENSION[lower.substringAfterLast('.', "")] ?: when (fileTypeName?.lowercase()) {
                null, "", "plain_text", "text" -> "plaintext"
                else -> fileTypeName.lowercase()
            }
        }
    }

    /** An IDE file type for a VS Code language id (for untitled documents), by a typical file name. */
    fun fileTypeFor(languageId: String): com.intellij.openapi.fileTypes.FileType? {
        val sample = BY_EXTENSION.entries.firstOrNull { it.value == languageId }?.key ?: when (languageId) {
            "dockerfile" -> return FileTypeManager.getInstance().getFileTypeByFileName("Dockerfile")
            "makefile" -> return FileTypeManager.getInstance().getFileTypeByFileName("Makefile")
            else -> return null
        }
        return FileTypeManager.getInstance().getFileTypeByExtension(sample).takeIf { it != com.intellij.openapi.fileTypes.UnknownFileType.INSTANCE }
    }
}
