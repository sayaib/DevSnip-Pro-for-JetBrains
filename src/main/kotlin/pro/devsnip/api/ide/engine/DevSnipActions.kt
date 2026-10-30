package pro.devsnip.api.ide.engine

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.intellij.ide.plugins.DynamicPluginListener
import com.intellij.ide.plugins.IdeaPluginDescriptor
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.actionSystem.Separator
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.startup.ProjectActivity
import com.intellij.openapi.vfs.VirtualFile
import pro.devsnip.api.ide.web.DevSnipIcons
import pro.devsnip.api.ide.web.EditorTracker
import pro.devsnip.api.ide.web.ScannerHost
import pro.devsnip.api.ide.web.WebviewAssets
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap

/**
 * The extension's commands in the IDE, from its package.json: every command
 * in Find Action and under Tools | DevSnip Pro (as in VS Code's command
 * palette), the editor's "DevSnip Pro" context menu and the Project view's
 * entries, each shown where package.json's `when` clause allows.
 */
object DevSnipActions {
    private const val PREFIX = WebviewAssets.COMMAND_PREFIX

    class Command(val id: String, val title: String)

    private val contributions: JsonObject by lazy {
        JsonParser.parseString(DevSnipActions::class.java.getResourceAsStream("/webview/contributions.json")!!.use { String(it.readBytes(), Charsets.UTF_8) }).asJsonObject
    }

    /** "🔌 DevSnip Pro: cURL Converter" → "DevSnip Pro: cURL Converter"; titles without the prefix get it. */
    fun cleanTitle(title: String, category: String?): String {
        val text = title.replace(Regex("^[^A-Za-z0-9(]+"), "").trim()
        return if (text.startsWith("DevSnip Pro")) text else "${category ?: "DevSnip Pro"}: $text"
    }

    /** Commands for Find Action: those VS Code's command palette shows (its `when` is not "false"), without development-only ones. */
    val commands: List<Command> by lazy {
        val palette = contributions.getAsJsonObject("menus").getAsJsonArray("commandPalette").map { it.asJsonObject }
        val hidden = palette.filter { WhenClause.neverHolds(it["when"]?.asString) }.map { it["command"].asString }.toSet()
        contributions.getAsJsonArray("commands").map { it.asJsonObject }
            .filter { it["command"].asString !in hidden }
            .map { Command(it["command"].asString, cleanTitle(it["title"].asString, it["category"]?.asString)) }
    }

    private val titles: Map<String, String> by lazy {
        contributions.getAsJsonArray("commands").map { it.asJsonObject }.associate { it["command"].asString to cleanTitle(it["title"].asString, it["category"]?.asString) }
    }

    fun title(command: String) = titles[command] ?: command.removePrefix(PREFIX)

    /** Menu entries: command, `when` clause. */
    fun menu(name: String): List<Pair<String, String?>> =
        contributions.getAsJsonObject("menus").getAsJsonArray(name)?.map { it.asJsonObject }?.filter { it.has("command") }?.map { it["command"].asString to it["when"]?.asString }.orEmpty()

    /** How long the last [registerOnce] held the UI thread (reported by DevSnipSelfTest). */
    @Volatile
    var registerMillis = 0L
        private set

    private val registered = mutableListOf<String>()
    private val added = mutableListOf<Pair<DefaultActionGroup, AnAction>>()
    private var done = false

    private fun actionId(command: String) = "DevSnipPro." + command.removePrefix(PREFIX)

    /** Reads what [registerOnce] needs, so parsing the plugin's JSON stays off the UI thread. */
    fun prepare() {
        commands.size
        titles.size
        contributions.size()
        WebviewAssets.entries.size
    }

    /** Registers the actions (on the EDT, once per plugin load). */
    fun registerOnce() {
        if (done) return
        done = true
        val started = System.nanoTime()
        val manager = ActionManager.getInstance()
        val actions = LinkedHashMap<String, AnAction>()
        for (command in commands) {
            val id = actionId(command.id)
            // Declared in plugin.xml (it has a keyboard shortcut): used as it is.
            val declared = manager.getAction(id) as? InsertDebugLogAction
            if (declared != null) {
                actions[command.id] = declared
                continue
            }
            if (manager.getAction(id) != null) manager.unregisterAction(id)
            val action = CommandAction(command.id, command.title)
            manager.registerAction(id, action)
            registered += id
            actions[command.id] = action
        }
        // Tools | DevSnip Pro: the getting-started entries, then the sidebar's sections in order.
        (manager.getAction("DevSnipPro.ToolsMenu") as? DefaultActionGroup)?.let { menu ->
            val top = listOf("searchTools", "advancedToolsHub", "milestoneTracker", "premiumStatus", "chooseTheme", "getStarted", "whatsNew").mapNotNull { actions[PREFIX + it] }
            top.forEach { add(menu, it) }
            add(menu, Separator.getInstance())
            val placed = top.toMutableSet()
            for ((section, entries) in WebviewAssets.entries.groupBy({ it.third }, { it.first })) {
                val group = DefaultActionGroup(section, true)
                entries.mapNotNull { actions[it] }.forEach { group.add(it); placed += it }
                if (group.childrenCount > 0) add(menu, group)
            }
            val rest = actions.values.filter { it !in placed }
            if (rest.isNotEmpty()) add(menu, DefaultActionGroup("More", true).apply { rest.forEach(::add) })
        }
        // The editor's DevSnip Pro submenu (package.json devsnip.editorContext) and the Project view entries.
        (manager.getAction("DevSnipPro.EditorContext") as? DefaultActionGroup)?.let { group ->
            for ((command, condition) in menu("devsnip.editorContext")) add(group, ContextAction(command, title(command), condition, Place.EDITOR))
        }
        (manager.getAction("DevSnipPro.ProjectViewContext") as? DefaultActionGroup)?.let { group ->
            for ((command, condition) in menu("explorer/context")) add(group, ContextAction(command, title(command), condition, Place.EXPLORER))
        }
        registerMillis = (System.nanoTime() - started) / 1_000_000
    }

    private fun add(group: DefaultActionGroup, action: AnAction) {
        group.add(action)
        added += group to action
    }

    /** Removes what [registerOnce] added, before the plugin is unloaded (updated or disabled without a restart). */
    fun unregisterAll() {
        val manager = ActionManager.getInstance()
        added.forEach { (group, action) -> group.remove(action) }
        added.clear()
        registered.forEach { if (manager.getAction(it) != null) manager.unregisterAction(it) }
        registered.clear()
        done = false
    }

    /** A command in Find Action and the Tools menu. */
    private class CommandAction(private val command: String, title: String) : DumbAwareAction(title, null, DevSnipIcons.TOOL) {
        override fun actionPerformed(e: AnActionEvent) {
            val project = e.project ?: return
            DevSnipCommands.run(project, command, editorArgs(e))
        }

        override fun update(e: AnActionEvent) {
            e.presentation.isEnabled = e.project != null
        }

        override fun getActionUpdateThread() = ActionUpdateThread.BGT
    }

    enum class Place { EDITOR, EXPLORER }

    /** An entry of a context menu, shown when its `when` clause holds. */
    private class ContextAction(private val command: String, title: String, private val condition: String?, private val place: Place) :
        DumbAwareAction(title.removePrefix("DevSnip Pro: "), null, null) {
        override fun actionPerformed(e: AnActionEvent) {
            val project = e.project ?: return
            DevSnipCommands.run(project, command, if (place == Place.EDITOR) editorArgs(e) else explorerArgs(e))
        }

        override fun update(e: AnActionEvent) {
            e.presentation.isEnabledAndVisible = e.project != null && WhenClause.holds(condition, context(e, place))
        }

        override fun getActionUpdateThread() = ActionUpdateThread.BGT
    }

    /** VS Code passes an editor command the document's Uri. */
    private fun editorArgs(e: AnActionEvent): JsonArray {
        val file = e.getData(CommonDataKeys.EDITOR)?.virtualFile ?: e.getData(CommonDataKeys.VIRTUAL_FILE)
        return JsonArray().apply { uriOf(file)?.let(::add) }
    }

    /** The explorer passes the clicked resource and the whole selection. */
    private fun explorerArgs(e: AnActionEvent): JsonArray {
        val files = e.getData(CommonDataKeys.VIRTUAL_FILE_ARRAY)?.toList().orEmpty()
        val clicked = e.getData(CommonDataKeys.VIRTUAL_FILE) ?: files.firstOrNull()
        return JsonArray().apply {
            uriOf(clicked)?.let(::add) ?: add(com.google.gson.JsonNull.INSTANCE)
            add(JsonArray().apply { files.mapNotNull(::uriOf).forEach(::add) })
        }
    }

    fun uriOf(file: VirtualFile?): JsonObject? {
        if (file == null || !file.isInLocalFileSystem) return null
        val path = ScannerHost.canonical(ScannerHost.normalize(Path.of(file.path)))
        return JsonObject().apply {
            addProperty("\$uri", "file://$path")
            addProperty("fsPath", path)
        }
    }

    private fun context(e: AnActionEvent, place: Place): Map<String, Any?> {
        val editor = e.getData(CommonDataKeys.EDITOR)
        val file = if (place == Place.EDITOR) editor?.virtualFile ?: e.getData(CommonDataKeys.VIRTUAL_FILE) else e.getData(CommonDataKeys.VIRTUAL_FILE)
        val name = file?.name ?: ""
        return mapOf(
            "editorHasSelection" to (editor?.selectionModel?.hasSelection() == true),
            "editorReadonly" to (editor?.document?.isWritable == false),
            "editorTextFocus" to (editor != null),
            "editorIsOpen" to (editor != null || (e.project?.let { EditorTracker.lastEditor(it) } != null)),
            "editorLangId" to (if (place == Place.EDITOR && file != null) VsCodeLanguages.idOf(name, file.fileType.name) else ""),
            "resourceFilename" to name,
            "resourceExtname" to (name.substringAfterLast('.', "").let { if (it.isEmpty()) "" else ".$it" }),
            "explorerResourceIsFolder" to (file?.isDirectory == true),
            "isDevelopment" to false,
        )
    }
}

/** package.json `when` clauses: `||`, `&&`, `!`, parentheses, `==`, `!=`, `=~ /regex/flags` and context keys. */
object WhenClause {
    private val patterns = ConcurrentHashMap<String, Regex>()

    fun holds(clause: String?, context: Map<String, Any?>): Boolean = clause.isNullOrBlank() || eval(clause, context)

    /** False whatever the context: "false" or "isDevelopment" (never set here) is one of its `&&` terms. */
    fun neverHolds(clause: String?): Boolean = !clause.isNullOrBlank() && split(unwrap(clause), "&&").any { unwrap(it) in NEVER }

    private fun eval(expression: String, context: Map<String, Any?>): Boolean {
        val text = unwrap(expression)
        split(text, "||").takeIf { it.size > 1 }?.let { parts -> return parts.any { eval(it, context) } }
        split(text, "&&").takeIf { it.size > 1 }?.let { parts -> return parts.all { eval(it, context) } }
        if (text.startsWith("!(")) return !eval(text.substring(1), context)
        return term(text, context)
    }

    /** Calls [visit] with each character's index and bracket depth, skipping regular expression literals. */
    private inline fun scan(text: String, visit: (Int, Int) -> Unit) {
        var depth = 0
        var inRegex = false
        var previous = ' '
        for (i in text.indices) {
            val c = text[i]
            when {
                inRegex -> if (c == '/' && text[i - 1] != '\\') inRegex = false
                c == '/' && previous == '~' -> inRegex = true
                else -> {
                    if (c == '(') depth++
                    if (c == ')') depth--
                    visit(i, depth)
                }
            }
            if (!c.isWhitespace()) previous = c
        }
    }

    /** Splits on [operator] outside parentheses and regular expressions. */
    private fun split(text: String, operator: String): List<String> {
        val parts = mutableListOf<String>()
        var start = 0
        scan(text) { i, depth ->
            if (depth == 0 && i >= start && text.startsWith(operator, i)) {
                parts += text.substring(start, i)
                start = i + operator.length
            }
        }
        parts += text.substring(start)
        return parts
    }

    /** Removes the parentheses around a whole expression. */
    private fun unwrap(expression: String): String {
        var text = expression.trim()
        while (text.startsWith("(") && text.endsWith(")")) {
            var closesEarly = false
            scan(text) { i, depth -> if (depth == 0 && i < text.length - 1) closesEarly = true }
            if (closesEarly) break
            text = text.substring(1, text.length - 1).trim()
        }
        return text
    }

    private fun term(term: String, context: Map<String, Any?>): Boolean {
        if (term == "true") return true
        if (term == "false") return false
        REGEX_TERM.find(term)?.let { m ->
            val value = context[m.groupValues[1]]?.toString() ?: ""
            val pattern = patterns.getOrPut("${m.groupValues[3]}/${m.groupValues[2]}") {
                Regex(m.groupValues[2].replace("\\/", "/"), if ('i' in m.groupValues[3]) setOf(RegexOption.IGNORE_CASE) else emptySet())
            }
            return pattern.containsMatchIn(value)
        }
        COMPARE_TERM.find(term)?.let { m ->
            val equal = (context[m.groupValues[1]]?.toString() ?: "") == m.groupValues[3].trim()
            return if (m.groupValues[2] == "==") equal else !equal
        }
        if (term.startsWith("!")) return !truthy(context[term.substring(1).trim()])
        return truthy(context[term])
    }

    private fun truthy(value: Any?) = when (value) {
        null -> false
        is Boolean -> value
        is String -> value.isNotEmpty()
        else -> true
    }

    private val NEVER = setOf("false", "isDevelopment")
    private val REGEX_TERM = Regex("^(\\w+)\\s*=~\\s*/(.*)/([a-z]*)$")
    private val COMPARE_TERM = Regex("^(\\w+)\\s*(==|!=)\\s*'?([^']*)'?$")
}

/** Registers the actions at startup and tracks the last editor (for tools that read it). */
class DevSnipStartup : ProjectActivity {
    override suspend fun execute(project: Project) {
        EditorTracker.install(project)
        DevSnipActions.prepare()
        ApplicationManager.getApplication().invokeLater { DevSnipActions.registerOnce() }
    }
}

/**
 * Keeps the generated actions in step with dynamic plugin loading: removed
 * before an update or uninstall without a restart, and added (with the editor
 * tracking) when the plugin is installed into a running IDE.
 */
class DevSnipPluginListener : DynamicPluginListener {
    override fun pluginLoaded(pluginDescriptor: IdeaPluginDescriptor) {
        if (pluginDescriptor.pluginId.idString != PLUGIN_ID) return
        ApplicationManager.getApplication().invokeLater {
            DevSnipActions.registerOnce()
            ProjectManager.getInstance().openProjects.forEach(EditorTracker::install)
        }
    }

    override fun beforePluginUnload(pluginDescriptor: IdeaPluginDescriptor, isUpdate: Boolean) {
        if (pluginDescriptor.pluginId.idString == PLUGIN_ID) DevSnipActions.unregisterAll()
    }

    companion object {
        const val PLUGIN_ID = "pro.devsnip.api"
    }
}

/** Insert Debug Log, with the extension's shortcut (Ctrl+Alt+Shift+L on macOS; assign one in Keymap elsewhere). */
class InsertDebugLogAction : DumbAwareAction() {
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val file = e.getData(CommonDataKeys.EDITOR)?.virtualFile
        DevSnipCommands.run(project, "${WebviewAssets.COMMAND_PREFIX}insertDebugLog", JsonArray().apply { DevSnipActions.uriOf(file)?.let(::add) })
    }

    override fun update(e: AnActionEvent) {
        val editor = e.getData(CommonDataKeys.EDITOR)
        val language = editor?.virtualFile?.let { VsCodeLanguages.idOf(it.name, it.fileType.name) }
        e.presentation.isEnabled = e.project != null && editor != null && editor.document.isWritable && language in DEBUG_LANGUAGES
    }

    override fun getActionUpdateThread() = ActionUpdateThread.BGT

    companion object {
        val DEBUG_LANGUAGES = setOf("javascript", "typescript", "javascriptreact", "typescriptreact", "python", "java", "kotlin", "csharp", "dart", "swift")
    }
}
