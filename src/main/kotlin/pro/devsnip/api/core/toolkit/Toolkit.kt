package pro.devsnip.api.core.toolkit

/**
 * The toolkit contract, ported from `src/toolkits/types.ts` in the VS Code
 * extension: a tool is metadata, input fields and a `run` function returning
 * stats, messages and outputs. Tools are pure (no IDE access), so each one is
 * unit-tested against the VS Code implementation's results.
 */

/** A problem with what the user entered; shown as a message, never as a crash. */
class ToolInputError(message: String) : IllegalArgumentException(message)

enum class FieldKind { TEXT, TEXTAREA, CODE, NUMBER, SELECT, TOGGLE, SECRET }

data class FieldOption(val value: String, val label: String)

/** The field is only shown (and only passed to run) when [field] has one of [equals]. */
data class ShowIf(val field: String, val equals: List<Any>)

data class FieldSpec(
    val id: String,
    val label: String,
    val kind: FieldKind,
    val default: Any? = null,
    val placeholder: String? = null,
    val help: String? = null,
    val options: List<FieldOption> = emptyList(),
    val rows: Int? = null,
    val min: Double? = null,
    val max: Double? = null,
    val step: Double? = null,
    val language: String? = null,
    val required: Boolean = false,
    val showIf: ShowIf? = null,
    val fromEditor: Boolean = false,
    val group: String? = null,
    val width: String? = null,
)

data class ToolExample(val label: String, val values: Map<String, Any>)

data class ToolDescriptor(
    val id: String,
    val command: String,
    val title: String,
    val summary: String,
    val guide: String? = null,
    val keywords: List<String> = emptyList(),
    val icon: String? = null,
    val live: Boolean = false,
    val network: Boolean = false,
    val runLabel: String? = null,
    val fields: List<FieldSpec> = emptyList(),
    val examples: List<ToolExample> = emptyList(),
)

data class GeneratedFile(val path: String, val content: String, val language: String? = null, val mode: String? = null)

sealed class Output {
    abstract val title: String

    data class Code(override val title: String, val language: String, val content: String, val fileName: String? = null) : Output()
    data class Text(override val title: String, val content: String) : Output()
    data class Table(override val title: String, val columns: List<String>, val rows: List<List<Any>>) : Output()
    data class Files(override val title: String, val files: List<GeneratedFile>) : Output()
}

enum class Tone { GOOD, WARN, BAD, NEUTRAL }

data class Stat(val label: String, val value: String, val tone: Tone? = null)

enum class MessageKind { INFO, SUCCESS, WARNING, ERROR }

data class Message(val kind: MessageKind, val text: String)

data class ToolResult(
    val stats: List<Stat>? = null,
    val messages: List<Message>? = null,
    val outputs: List<Output>? = null,
)

/** Form values as the UI sends them: strings, booleans and numbers. */
typealias Values = Map<String, Any?>

class ToolDefinition(val descriptor: ToolDescriptor, private val runner: (Values) -> ToolResult) {
    val id get() = descriptor.id
    val title get() = descriptor.title

    fun run(values: Values): ToolResult = runner(values)

    /**
     * What the form passes to [run]: defaults, overridden by [entered], keeping
     * only fields whose `showIf` holds (as the VS Code workbench does).
     */
    fun formValues(entered: Map<String, Any?>): Map<String, Any?> {
        val all = LinkedHashMap<String, Any?>()
        descriptor.fields.forEach { field -> field.default?.let { all[field.id] = it } }
        entered.forEach { (key, value) -> if (value != null) all[key] = value }
        val visible = LinkedHashMap<String, Any?>()
        for (field in descriptor.fields) {
            if (!isVisible(field, all)) continue
            all[field.id]?.let { visible[field.id] = it }
        }
        return visible
    }

    companion object {
        fun isVisible(field: FieldSpec, values: Map<String, Any?>): Boolean {
            val condition = field.showIf ?: return true
            val current = values[condition.field]
            return condition.equals.any { it == current || (it is String && current is String && it == current) }
        }
    }
}

// ---------------------------------------------------------------------------
// Value helpers (types.ts): read form values without turning a deliberate 0 into the default.
// ---------------------------------------------------------------------------

fun str(values: Values, key: String, fallback: String = ""): String {
    val value = values[key] ?: return fallback
    return Js.string(value)
}

fun num(values: Values, key: String, fallback: Double, min: Double? = null, max: Double? = null, integer: Boolean = false, label: String? = null): Double {
    val raw = values[key]
    if (raw == null || Js.string(raw).trim().isEmpty()) return fallback
    val value = if (raw is Number) raw.toDouble() else Js.toNumber(Js.string(raw).replace(Regex("[_,\\s]"), ""))
    val name = label ?: key
    if (!value.isFinite()) throw ToolInputError("$name must be a number.")
    if (integer && value != Math.floor(value)) throw ToolInputError("$name must be a whole number.")
    if (min != null && value < min) throw ToolInputError("$name must be at least ${Js.number(min)}.")
    if (max != null && value > max) throw ToolInputError("$name must be at most ${Js.number(max)}.")
    return value
}

fun bool(values: Values, key: String, fallback: Boolean = false): Boolean = when (val value = values[key]) {
    is Boolean -> value
    "true" -> true
    "false" -> false
    else -> fallback
}

fun required(values: Values, key: String, label: String): String {
    val value = str(values, key).trim()
    if (value.isEmpty()) throw ToolInputError("$label is required.")
    return value
}

/** `fmtBytes` from types.ts. */
fun fmtBytes(bytes: Long): String {
    val units = listOf("B", "KB", "MB", "GB", "TB")
    var value = bytes.toDouble()
    var unit = 0
    while (value >= 1024 && unit < units.size - 1) {
        value /= 1024
        unit++
    }
    return "${Js.toFixed(value, if (value >= 100 || unit == 0) 0 else 1)} ${units[unit]}"
}

/** `list` from sections/helpers.ts: split on newlines and commas. */
fun list(value: String): List<String> = value.split(Regex("[\\n,]+")).map { it.trim() }.filter { it.isNotEmpty() }

fun code(title: String, language: String, content: String, fileName: String? = null) = Output.Code(title, language, content, fileName)
fun text(title: String, content: String) = Output.Text(title, content)
fun table(title: String, columns: List<String>, rows: List<List<Any>>) = Output.Table(title, columns, rows)

/** `severityMessages` from sections/helpers.ts. */
fun severityMessages(items: List<Issue>, okText: String): List<Message> {
    if (items.isEmpty()) return listOf(Message(MessageKind.SUCCESS, okText))
    val order = mapOf("error" to 0, "warning" to 1, "info" to 2)
    return items.withIndex()
        .sortedWith(compareBy({ order.getValue(it.value.severity) }, { it.value.line ?: 0 }, { it.index }))
        .map { (_, issue) ->
            val kind = when (issue.severity) { "error" -> MessageKind.ERROR; "warning" -> MessageKind.WARNING; else -> MessageKind.INFO }
            Message(kind, "${issue.line?.let { "Line $it: " } ?: ""}${issue.message}")
        }
}

data class Issue(val severity: String, val message: String, val line: Int? = null)
