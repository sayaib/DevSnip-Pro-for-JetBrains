package pro.devsnip.api.ide.engine

import com.intellij.codeInsight.template.Template
import com.intellij.codeInsight.template.TemplateManager
import com.intellij.codeInsight.template.impl.ConstantNode
import com.intellij.openapi.project.Project
import java.time.LocalDateTime
import java.time.format.TextStyle
import java.util.Locale

/**
 * VS Code snippet bodies (the extension's snippets folder and the user's
 * custom snippet files) as IDE live templates: tab stops ($1, ${1:default}),
 * choices (${1|a,b|}), mirrors (the same number twice), the final cursor ($0)
 * and VS Code's variables (TM_FILENAME, CURRENT_YEAR, ...).
 */
object VsCodeSnippets {
    sealed class Part {
        data class Text(val text: String) : Part()
        data class Stop(val index: Int, val default: String, val choices: List<String>) : Part()
    }

    /** Parses a snippet body into text and tab stops (nested placeholders become their text). */
    fun parse(body: String, fileName: String = "", now: LocalDateTime = LocalDateTime.now()): List<Part> {
        val parts = mutableListOf<Part>()
        val text = StringBuilder()
        var i = 0

        fun flush() {
            if (text.isNotEmpty()) parts += Part.Text(text.toString())
            text.clear()
        }

        /** Reads a placeholder's default text up to its matching '}' and returns it with the index after it. */
        fun readNested(start: Int): Pair<String, Int> {
            val out = StringBuilder()
            var j = start
            while (j < body.length) {
                val c = body[j]
                when {
                    c == '\\' && j + 1 < body.length -> { out.append(body[j + 1]); j += 2 }
                    c == '}' -> return out.toString() to j + 1
                    c == '$' && j + 1 < body.length && body[j + 1] == '{' -> {
                        val inner = Regex("^\\$\\{(\\d+|[A-Z_]+)(?::)?").find(body.substring(j))
                        if (inner != null) {
                            val name = inner.groupValues[1]
                            val (nested, next) = readNested(j + inner.value.length)
                            out.append(if (name.all(Char::isDigit)) nested else variable(name, fileName, now) ?: nested)
                            j = next
                        } else {
                            out.append(c); j++
                        }
                    }
                    c == '$' && j + 1 < body.length && body[j + 1].isDigit() -> {
                        j++
                        while (j < body.length && body[j].isDigit()) j++
                    }
                    else -> { out.append(c); j++ }
                }
            }
            return out.toString() to j
        }

        while (i < body.length) {
            val c = body[i]
            if (c == '\\' && i + 1 < body.length && body[i + 1] in "\$}\\") {
                text.append(body[i + 1]); i += 2; continue
            }
            if (c != '$') { text.append(c); i++; continue }
            val rest = body.substring(i)
            val simple = Regex("^\\$(\\d+)").find(rest)
            if (simple != null) {
                flush()
                parts += Part.Stop(simple.groupValues[1].toInt(), "", emptyList())
                i += simple.value.length
                continue
            }
            val choice = Regex("^\\$\\{(\\d+)\\|((?:[^|\\\\]|\\\\.)*)\\|}").find(rest)
            if (choice != null) {
                flush()
                val options = choice.groupValues[2].split(Regex("(?<!\\\\),")).map { it.replace("\\,", ",").replace("\\|", "|") }
                parts += Part.Stop(choice.groupValues[1].toInt(), options.firstOrNull().orEmpty(), options)
                i += choice.value.length
                continue
            }
            val placeholder = Regex("^\\$\\{(\\d+)(:?)").find(rest)
            if (placeholder != null) {
                flush()
                val (default, next) = if (placeholder.groupValues[2] == ":") readNested(i + placeholder.value.length) else ("" to (rest.indexOf('}').takeIf { it >= 0 }?.let { i + it + 1 } ?: body.length))
                parts += Part.Stop(placeholder.groupValues[1].toInt(), default, emptyList())
                i = next
                continue
            }
            val named = Regex("^\\$\\{([A-Z_]+)(:?)").find(rest) ?: Regex("^\\$([A-Z_]+)").find(rest)
            if (named != null) {
                val name = named.groupValues[1]
                if (named.value.startsWith("\${")) {
                    val (fallback, next) = if (named.groupValues.getOrNull(2) == ":") readNested(i + named.value.length) else ("" to (rest.indexOf('}').takeIf { it >= 0 }?.let { i + it + 1 } ?: body.length))
                    text.append(variable(name, fileName, now) ?: fallback)
                    i = next
                } else {
                    text.append(variable(name, fileName, now) ?: "")
                    i += named.value.length
                }
                continue
            }
            text.append(c)
            i++
        }
        flush()
        return parts
    }

    /** VS Code's snippet variables the IDE can fill in. */
    fun variable(name: String, fileName: String, now: LocalDateTime): String? = when (name) {
        "TM_FILENAME" -> fileName
        "TM_FILENAME_BASE" -> fileName.substringBeforeLast('.')
        "CURRENT_YEAR" -> now.year.toString()
        "CURRENT_YEAR_SHORT" -> (now.year % 100).toString().padStart(2, '0')
        "CURRENT_MONTH" -> now.monthValue.toString().padStart(2, '0')
        "CURRENT_MONTH_NAME" -> now.month.getDisplayName(TextStyle.FULL, Locale.ENGLISH)
        "CURRENT_MONTH_NAME_SHORT" -> now.month.getDisplayName(TextStyle.SHORT, Locale.ENGLISH)
        "CURRENT_DATE" -> now.dayOfMonth.toString().padStart(2, '0')
        "CURRENT_DAY_NAME" -> now.dayOfWeek.getDisplayName(TextStyle.FULL, Locale.ENGLISH)
        "CURRENT_DAY_NAME_SHORT" -> now.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.ENGLISH)
        "CURRENT_HOUR" -> now.hour.toString().padStart(2, '0')
        "CURRENT_MINUTE" -> now.minute.toString().padStart(2, '0')
        "CURRENT_SECOND" -> now.second.toString().padStart(2, '0')
        "CURRENT_SECONDS_UNIX" -> (System.currentTimeMillis() / 1000).toString()
        "UUID" -> java.util.UUID.randomUUID().toString()
        "RANDOM" -> (100000..999999).random().toString()
        "RANDOM_HEX" -> Integer.toHexString((0x100000..0xFFFFFF).random())
        "LINE_COMMENT" -> "//"
        "BLOCK_COMMENT_START" -> "/*"
        "BLOCK_COMMENT_END" -> "*/"
        "TM_SELECTED_TEXT", "TM_CURRENT_LINE", "TM_CURRENT_WORD", "CLIPBOARD", "SELECTION" -> ""
        else -> null
    }

    /** The snippet's text with every tab stop at its default (what a plain insert shows). */
    fun plainText(body: String, fileName: String = ""): String = parse(body, fileName).joinToString("") {
        when (it) {
            is Part.Text -> it.text
            is Part.Stop -> it.default
        }
    }

    /** A live template for the body: tab stops in VS Code's order (1, 2, ... then 0 as the end). */
    fun toTemplate(project: Project, body: String, fileName: String): Template {
        val template = TemplateManager.getInstance(project).createTemplate("", "")
        template.isToReformat = false
        val parts = parse(body, fileName)
        val defined = mutableSetOf<Int>()
        // A tab stop used more than once takes the first occurrence that has a default (VS Code's rule for mirrors).
        val defaults = parts.filterIsInstance<Part.Stop>().groupBy { it.index }.mapValues { (_, stops) -> stops.firstOrNull { it.default.isNotEmpty() || it.choices.isNotEmpty() } ?: stops.first() }
        var endAdded = false
        for (part in parts) {
            when (part) {
                is Part.Text -> template.addTextSegment(part.text)
                is Part.Stop -> when {
                    part.index == 0 -> if (!endAdded) { template.addEndVariable(); endAdded = true }
                    part.index in defined -> template.addVariableSegment("S${part.index}")
                    else -> {
                        val stop = defaults.getValue(part.index)
                        val expression = if (stop.choices.size > 1) ChoiceExpression(stop.choices) else ConstantNode(stop.default)
                        // addVariable also places the variable here.
                        template.addVariable("S${part.index}", expression, ConstantNode(stop.default), true)
                        defined += part.index
                    }
                }
            }
        }
        // The cursor visits tab stops in VS Code's numeric order, wherever they are in the body.
        (template as? com.intellij.codeInsight.template.impl.TemplateImpl)?.variables?.sortBy { it.name.removePrefix("S").toIntOrNull() ?: Int.MAX_VALUE }
        return template
    }

    /** A placeholder with VS Code's choices: the first is filled in, the rest are offered. */
    private class ChoiceExpression(private val choices: List<String>) : com.intellij.codeInsight.template.Expression() {
        override fun calculateResult(context: com.intellij.codeInsight.template.ExpressionContext) = com.intellij.codeInsight.template.TextResult(choices.first())
        override fun calculateLookupItems(context: com.intellij.codeInsight.template.ExpressionContext) =
            choices.map { com.intellij.codeInsight.lookup.LookupElementBuilder.create(it) }.toTypedArray<com.intellij.codeInsight.lookup.LookupElement>()
    }
}
