package pro.devsnip.api.ide.engine

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.intellij.codeInsight.completion.CompletionContributor
import com.intellij.codeInsight.completion.CompletionParameters
import com.intellij.codeInsight.completion.CompletionProvider
import com.intellij.codeInsight.completion.CompletionResultSet
import com.intellij.codeInsight.completion.CompletionType
import com.intellij.codeInsight.completion.InsertionContext
import com.intellij.codeInsight.lookup.LookupElementBuilder
import com.intellij.codeInsight.template.TemplateManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.patterns.PlatformPatterns
import com.intellij.util.ProcessingContext
import pro.devsnip.api.ide.web.DevSnipIcons
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap

/**
 * DevSnip Pro's snippets in code completion, as VS Code offers contributed
 * snippets: the extension's framework snippets (snippets folder, type "ds-")
 * and the user's own (custom_<language>.json in the custom folder, made with Create
 * Snippet or Saved Snippets), for the file's language. Choosing one inserts
 * it as a live template with VS Code's tab stops.
 */
class SnippetCompletionContributor : CompletionContributor() {
    init {
        extend(CompletionType.BASIC, PlatformPatterns.psiElement(), object : CompletionProvider<CompletionParameters>() {
            override fun addCompletions(parameters: CompletionParameters, context: ProcessingContext, result: CompletionResultSet) {
                val file = parameters.originalFile.virtualFile ?: return
                val language = VsCodeLanguages.idOf(file.name, file.fileType.name)
                val snippets = Snippets.forLanguage(language)
                if (snippets.isEmpty()) return
                val document = parameters.editor.document
                val caret = parameters.offset
                val text = document.charsSequence
                var start = caret
                while (start > 0 && (text[start - 1].isLetterOrDigit() || text[start - 1] in "_-.:@!#$")) start--
                val typed = text.subSequence(start, caret).toString()
                if (typed.isEmpty()) return
                val matching = result.withPrefixMatcher(typed)
                for (snippet in snippets) {
                    for (prefix in snippet.prefixes) {
                        matching.addElement(
                            LookupElementBuilder.create(snippet, prefix)
                                .withIcon(DevSnipIcons.TOOL)
                                .withTypeText(snippet.source, true)
                                .withTailText(snippet.description?.let { "  $it" } ?: "", true)
                                .withInsertHandler { ctx, _ -> insert(ctx, snippet) },
                        )
                    }
                }
            }
        })
    }

    private fun insert(context: InsertionContext, snippet: Snippet) {
        val project = context.project
        context.document.deleteString(context.startOffset, context.tailOffset)
        context.commitDocument()
        context.editor.caretModel.moveToOffset(context.startOffset)
        val name = context.file.virtualFile?.name ?: ""
        TemplateManager.getInstance(project).startTemplate(context.editor, VsCodeSnippets.toTemplate(project, snippet.body, name))
    }

    data class Snippet(val name: String, val prefixes: List<String>, val body: String, val description: String?, val source: String)

    /** The snippet files package.json contributes per language, read from the extension folder (re-read when they change). */
    object Snippets {
        private val LOG = Logger.getInstance(Snippets::class.java)
        private data class Cached(val stamp: Long, val snippets: List<Snippet>)

        private val cache = ConcurrentHashMap<Path, Cached>()

        private val byLanguage: Map<String, List<String>> by lazy {
            val text = Snippets::class.java.getResourceAsStream("/webview/contributions.json")?.use { String(it.readBytes(), Charsets.UTF_8) } ?: return@lazy emptyMap()
            JsonParser.parseString(text).asJsonObject.getAsJsonArray("snippets").map { it.asJsonObject }
                .groupBy({ it["language"].asString }, { it["path"].asString.removePrefix("./") })
        }

        fun forLanguage(language: String): List<Snippet> {
            val files = byLanguage[language].orEmpty()
            if (files.isEmpty()) return emptyList()
            val root = runCatching { ExtensionFiles.root }.getOrNull() ?: return emptyList()
            return files.flatMap { read(root.resolve(it)) }
        }

        private fun read(file: Path): List<Snippet> {
            val stamp = runCatching { Files.getLastModifiedTime(file).toMillis() + Files.size(file) }.getOrDefault(-1L)
            if (stamp < 0) return emptyList()
            cache[file]?.takeIf { it.stamp == stamp }?.let { return it.snippets }
            val source = if (file.parent?.fileName?.toString() == "custom") "My snippet" else "DevSnip Pro"
            val snippets = try {
                val json = JsonParser.parseString(Files.readString(file)).takeIf { it.isJsonObject }?.asJsonObject ?: JsonObject()
                json.entrySet().mapNotNull { (name, value) ->
                    val o = value.takeIf { it.isJsonObject }?.asJsonObject ?: return@mapNotNull null
                    val prefixes = o["prefix"]?.let { p -> if (p.isJsonArray) p.asJsonArray.map { it.asString } else listOf(p.asString) }.orEmpty().filter { it.isNotBlank() }
                    val body = o["body"]?.let { b -> if (b.isJsonArray) b.asJsonArray.joinToString("\n") { it.asString } else b.asString } ?: return@mapNotNull null
                    if (prefixes.isEmpty()) null else Snippet(name, prefixes, body, o["description"]?.takeIf { it.isJsonPrimitive }?.asString, source)
                }
            } catch (e: Exception) {
                LOG.info("DevSnip Pro: could not read snippets in ${file.fileName}: ${e.message}")
                emptyList()
            }
            cache[file] = Cached(stamp, snippets)
            return snippets
        }
    }
}
