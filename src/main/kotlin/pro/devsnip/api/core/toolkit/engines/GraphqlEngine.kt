package pro.devsnip.api.core.toolkit.engines

import pro.devsnip.api.core.toolkit.Js
import pro.devsnip.api.core.toolkit.ToolInputError

/** GraphQL formatting, operations and SDL → TypeScript. Port of the GraphQL part of `web-apispec.ts`. */
object GraphqlEngine {
    /** Not a data class: tokens are compared by identity, like the objects in the original. */
    class Token(val kind: String, val value: String)

    data class Variable(val name: String, val type: String, val defaultValue: String?)
    data class Operation(val type: String, val name: String, val variables: MutableList<Variable>)

    fun tokenize(src: String): List<Token> {
        val tokens = mutableListOf<Token>()
        var i = 0
        while (i < src.length) {
            val ch = src[i]
            if (ch.isJsWhitespace() || ch == ',' || ch == '﻿') {
                i++
                continue
            }
            if (ch == '#') {
                val end = src.indexOf('\n', i)
                val stop = if (end < 0) src.length else end
                tokens += Token("comment", src.substring(i, stop).trimEnd())
                i = stop
                continue
            }
            if (src.startsWith("\"\"\"", i)) {
                val end = src.indexOf("\"\"\"", i + 3)
                if (end < 0) throw ToolInputError("Unterminated block string (\"\"\").")
                tokens += Token("string", src.substring(i, end + 3))
                i = end + 3
                continue
            }
            if (ch == '"') {
                var j = i + 1
                while (j < src.length && src[j] != '"') {
                    if (src[j] == '\\') j++
                    if (src.getOrNull(j) == '\n') throw ToolInputError("Unterminated string.")
                    j++
                }
                if (j >= src.length) throw ToolInputError("Unterminated string.")
                tokens += Token("string", src.substring(i, j + 1))
                i = j + 1
                continue
            }
            if (src.startsWith("...", i)) {
                tokens += Token("spread", "...")
                i += 3
                continue
            }
            if (ch in "{}()[]:=@!|&") {
                tokens += Token("punct", ch.toString())
                i++
                continue
            }
            val rest = src.substring(i)
            if (ch == '$') {
                val m = Regex("^\\$[_A-Za-z][_0-9A-Za-z]*").find(rest) ?: throw ToolInputError("Invalid variable at position $i.")
                tokens += Token("var", m.value)
                i += m.value.length
                continue
            }
            val name = Regex("^[_A-Za-z][_0-9A-Za-z]*").find(rest)
            if (name != null) {
                tokens += Token("name", name.value)
                i += name.value.length
                continue
            }
            val number = Regex("^-?\\d+(\\.\\d+)?([eE][+-]?\\d+)?").find(rest)
            if (number != null) {
                tokens += Token("number", number.value)
                i += number.value.length
                continue
            }
            throw ToolInputError("Unexpected character \"$ch\" at position $i.")
        }
        var depth = 0
        for (t in tokens) {
            if (t.kind != "punct") continue
            if (t.value in listOf("{", "(", "[")) depth++
            if (t.value in listOf("}", ")", "]")) depth--
            if (depth < 0) throw ToolInputError("Unbalanced \"${t.value}\".")
        }
        if (depth != 0) throw ToolInputError("Unbalanced braces or brackets - a { ( or [ is never closed.")
        return tokens
    }

    fun format(src: String): String {
        val tokens = tokenize(src)
        val stack = mutableListOf<String>()
        val out = StringBuilder()
        var indent = 0
        fun inline() = "args" in stack
        fun trimEndOut() {
            val trimmed = out.toString().trimEnd()
            out.setLength(0)
            out.append(trimmed)
        }
        fun newline() {
            trimEndOut()
            out.append("\n").append("  ".repeat(indent))
        }
        fun atLineStart() = Regex("(^|\\n) *$").containsMatchIn(out)
        fun put(s: String, spaceBefore: Boolean) {
            if (spaceBefore && out.isNotEmpty() && !atLineStart() && !Regex("[\\s(\\[]$").containsMatchIn(out) && !out.endsWith("@") && !out.endsWith("...")) out.append(" ")
            out.append(s)
        }
        fun valueEnd(t: Token?) = t != null && ((t.kind == "name" && t.value != "on") || t.kind == "number" || t.kind == "string" || t.kind == "var" ||
            (t.kind == "punct" && t.value in listOf(")", "]", "!", "}")))
        for (k in tokens.indices) {
            val t = tokens[k]
            val prev = tokens.subList(0, k).lastOrNull { it.kind != "comment" }
            val next = tokens.getOrNull(k + 1)
            if (t.kind == "comment") {
                if (out.isNotEmpty() && !atLineStart()) out.append(" ")
                out.append(t.value)
                newline()
                continue
            }
            if (!inline()) {
                if (t.kind == "punct" && t.value == "{") {
                    put("{", true)
                    stack += "sel"
                    indent++
                    newline()
                    continue
                }
                if (t.kind == "punct" && t.value == "}") {
                    if (stack.isNotEmpty()) stack.removeAt(stack.size - 1)
                    indent = maxOf(0, indent - 1)
                    newline()
                    out.append("}")
                    if (next != null && !(next.kind == "punct" && next.value == "}")) {
                        newline()
                        if (indent == 0) out.append("\n")
                    }
                    continue
                }
                if (t.kind == "punct" && t.value == "(") {
                    put("(", false)
                    stack += "args"
                    continue
                }
                if (indent > 0 && (t.kind == "name" || t.kind == "spread" || t.kind == "string") && prev != null && valueEnd(prev) && !(prev.kind == "punct" && prev.value == "}")) newline()
                put(t.value, !(t.kind == "punct" && t.value in listOf(":", "!", "]", ")")) && !(prev?.kind == "punct" && prev.value == "@"))
                continue
            }
            val ctx = stack.lastOrNull()
            if (t.kind == "punct") {
                when (t.value) {
                    "(" -> {
                        put("(", false)
                        stack += "args"
                    }
                    ")" -> {
                        stack.removeAt(stack.size - 1)
                        val trimmed = out.toString().trimEnd().removeSuffix(",")
                        out.setLength(0)
                        out.append(trimmed).append(")")
                    }
                    "{" -> {
                        if (ctx == "list" && valueEnd(prev) && prev?.value != "[") {
                            trimEndOut()
                            out.append(",")
                        }
                        put("{", true)
                        stack += "obj"
                    }
                    "}" -> {
                        stack.removeAt(stack.size - 1)
                        trimEndOut()
                        out.append(" }")
                    }
                    "[" -> {
                        if (ctx == "list" && valueEnd(prev)) {
                            trimEndOut()
                            out.append(",")
                        }
                        put("[", true)
                        stack += "list"
                    }
                    "]" -> {
                        stack.removeAt(stack.size - 1)
                        trimEndOut()
                        out.append("]")
                    }
                    ":" -> {
                        trimEndOut()
                        out.append(":")
                    }
                    "!" -> {
                        trimEndOut()
                        out.append("!")
                    }
                    else -> put(t.value, true)
                }
                continue
            }
            if ((ctx == "args" || ctx == "obj") && (t.kind == "name" || t.kind == "var") && next?.value == ":" && prev != null && !(prev.kind == "punct" && (prev.value == "(" || prev.value == "{"))) {
                trimEndOut()
                out.append(",")
            } else if (ctx == "list" && prev != null && valueEnd(prev) && !(prev.kind == "punct" && prev.value == "[")) {
                trimEndOut()
                out.append(",")
            }
            put(t.value, !(prev?.kind == "punct" && prev.value == "@"))
        }
        return out.toString().trim().replace(Regex("\\n{3,}"), "\n\n") + "\n"
    }

    fun minify(src: String): String {
        val tokens = tokenize(src).filter { it.kind != "comment" }
        val out = StringBuilder()
        for (t in tokens) {
            val word = t.kind != "punct" && t.kind != "spread"
            if (word && out.isNotEmpty() && (out.last().isLetterOrDigit() && out.last().code < 128 || out.last() in "_\"$")) out.append(" ")
            out.append(t.value)
        }
        return out.toString()
    }

    fun operations(src: String): Pair<List<Operation>, List<String>> {
        val tokens = tokenize(src).filter { it.kind != "comment" }
        val operations = mutableListOf<Operation>()
        val fragments = mutableListOf<String>()
        var depth = 0
        var i = 0
        while (i < tokens.size) {
            val t = tokens[i]
            if (t.kind == "punct" && t.value == "{") {
                if (depth == 0 && (i == 0 || tokens[i - 1].value == "}")) operations += Operation("query", "", mutableListOf())
                depth++
                i++
                continue
            }
            if (t.kind == "punct" && t.value == "}") {
                depth--
                i++
                continue
            }
            if (depth != 0 || t.kind != "name") {
                i++
                continue
            }
            if (t.value == "fragment") {
                fragments += tokens.getOrNull(i + 1)?.value ?: "?"
                i++
                continue
            }
            if (t.value !in listOf("query", "mutation", "subscription")) {
                i++
                continue
            }
            val op = Operation(t.value, if (tokens.getOrNull(i + 1)?.kind == "name") tokens[i + 1].value else "", mutableListOf())
            var j = i + 1 + if (op.name.isNotEmpty()) 1 else 0
            if (tokens.getOrNull(j)?.value == "(") {
                j++
                while (j < tokens.size && tokens[j].value != ")") {
                    if (tokens[j].kind == "var") {
                        val name = tokens[j].value.substring(1)
                        j += 2 // $name :
                        val type = StringBuilder()
                        while (j < tokens.size && tokens[j].kind != "var" && tokens[j].value != ")" && tokens[j].value != "=" && tokens[j].value != "@") {
                            type.append(tokens[j].value)
                            j++
                        }
                        var defaultValue: String? = null
                        if (tokens.getOrNull(j)?.value == "=") {
                            j++
                            defaultValue = tokens.getOrNull(j)?.value
                            j++
                        }
                        op.variables += Variable(name, type.toString(), defaultValue)
                    } else {
                        j++
                    }
                }
            }
            operations += op
            i = j + 1
        }
        return operations to fragments
    }

    /** Placeholder value for a variable of a GraphQL type (null for a nullable object). */
    fun sampleFor(type: String): Any? {
        val nonNull = type.endsWith("!")
        val base = type.removeSuffix("!")
        if (base.startsWith("[")) return emptyList<Any?>()
        return when (base) {
            "Int", "Float" -> 0.0
            "String", "ID" -> ""
            "Boolean" -> false
            else -> if (nonNull) LinkedHashMap<String, Any?>() else null
        }
    }

    private val SCALARS = mapOf("ID" to "string", "String" to "string", "Int" to "number", "Float" to "number", "Boolean" to "boolean")
    private val DESCRIPTION_QUOTES = Regex("^\"\"\"|\"\"\"$|^\"|\"$")

    /** SDL → TypeScript types (types, inputs, interfaces, enums, unions, scalars). */
    fun sdlToTypeScript(sdl: String): String {
        val tokens = tokenize(sdl).filter { it.kind != "comment" }
        val out = mutableListOf<String>()
        var i = 0
        fun at(index: Int) = tokens.getOrNull(index)
        fun skipDirectives() {
            while (at(i)?.value == "@") {
                i += 2
                if (at(i)?.value == "(") {
                    var d = 0
                    do {
                        if (tokens[i].value == "(") d++
                        if (tokens[i].value == ")") d--
                        i++
                    } while (d > 0 && i < tokens.size)
                }
            }
        }
        fun readType(): String {
            if (at(i)?.value == "[") {
                i++
                val inner = readType()
                i++ // ]
                var t = "Array<$inner>"
                if (at(i)?.value == "!") i++ else t = "$t | null"
                return t
            }
            val name = tokens[i++].value
            var t = SCALARS[name] ?: name
            if (at(i)?.value == "!") i++ else t = "$t | null"
            return t
        }
        var description = ""
        while (i < tokens.size) {
            val t = tokens[i]
            if (t.kind == "string") {
                description = t.value.replace(DESCRIPTION_QUOTES, "").trim().split("\n")[0]
                i++
                continue
            }
            val doc = if (description.isNotEmpty()) "/** ${description.replace("*/", "")} */\n" else ""
            description = ""
            if (t.value == "extend") {
                i++
                continue
            }
            if (t.value == "schema" || t.value == "directive") {
                while (i < tokens.size && tokens[i].value != "{" && !(t.value == "directive" && tokens[i].value == "on")) i++
                if (at(i)?.value == "{") {
                    while (at(i) != null && tokens[i].value != "}") i++
                    i++
                } else {
                    i++
                    while (at(i) != null && (tokens[i].value == "|" || (tokens[i].kind == "name" && tokens[i].value.matches(Regex("^[A-Z_]+$"))))) i++
                }
                continue
            }
            if (t.value == "scalar") {
                val name = tokens[i + 1].value
                i += 2
                skipDirectives()
                out += "${doc}export type $name = unknown; // custom scalar - narrow this type"
                continue
            }
            if (t.value == "enum") {
                val name = tokens[i + 1].value
                i += 2
                skipDirectives()
                val values = mutableListOf<String>()
                if (at(i)?.value == "{") {
                    i++
                    while (at(i) != null && tokens[i].value != "}") {
                        if (tokens[i].kind == "string") {
                            i++
                            continue
                        }
                        values += tokens[i].value
                        i++
                        skipDirectives()
                    }
                    i++
                }
                out += "${doc}export type $name = ${values.joinToString(" | ") { Js.quote(it) }.ifEmpty { "never" }};"
                continue
            }
            if (t.value == "union") {
                val name = tokens[i + 1].value
                i += 2
                skipDirectives()
                if (at(i)?.value == "=") i++
                val members = mutableListOf<String>()
                val keywords = listOf("type", "input", "enum", "union", "scalar", "interface", "extend", "schema", "directive")
                while (at(i) != null && (tokens[i].value == "|" || (tokens[i].kind == "name" && tokens[i].value !in keywords))) {
                    if (tokens[i].value != "|") members += tokens[i].value
                    i++
                }
                out += "${doc}export type $name = ${members.joinToString(" | ").ifEmpty { "never" }};"
                continue
            }
            if (t.value in listOf("type", "input", "interface")) {
                val name = tokens[i + 1].value
                i += 2
                val parents = mutableListOf<String>()
                if (at(i)?.value == "implements") {
                    i++
                    while (at(i) != null && tokens[i].value != "{" && tokens[i].value != "@") {
                        if (tokens[i].kind == "name") parents += tokens[i].value
                        i++
                    }
                }
                skipDirectives()
                val fields = mutableListOf<String>()
                if (at(i)?.value == "{") {
                    i++
                    var fieldDoc = ""
                    while (at(i) != null && tokens[i].value != "}") {
                        if (tokens[i].kind == "string") {
                            fieldDoc = tokens[i].value.replace(DESCRIPTION_QUOTES, "").trim().split("\n")[0]
                            i++
                            continue
                        }
                        val field = tokens[i++].value
                        var args = ""
                        if (at(i)?.value == "(") {
                            var d = 0
                            val start = i
                            do {
                                if (tokens[i].value == "(") d++
                                if (tokens[i].value == ")") d--
                                i++
                            } while (d > 0 && i < tokens.size)
                            args = tokens.subList(start + 1, i - 1)
                                .filter { x -> x.kind == "name" && tokens.getOrNull(tokens.indexOfFirst { it === x } + 1)?.value == ":" }
                                .joinToString(", ") { it.value }
                        }
                        i++ // :
                        val type = readType()
                        if (at(i)?.value == "=") i += 2
                        skipDirectives()
                        val optional = t.value == "input" && type.endsWith("| null")
                        fields += "${if (fieldDoc.isNotEmpty()) "  /** ${fieldDoc.replace("*/", "")} */\n" else ""}  $field${if (optional) "?" else ""}: $type;${if (args.isNotEmpty()) " // args: $args" else ""}"
                        fieldDoc = ""
                    }
                    i++
                }
                val ext = if (parents.isNotEmpty()) " extends ${parents.joinToString(", ")}" else ""
                out += "${doc}export interface $name$ext {\n${if (t.value == "type") "  __typename?: ${Js.quote(name)};\n" else ""}${fields.joinToString("\n")}\n}"
                continue
            }
            i++
        }
        if (out.isEmpty()) throw ToolInputError("No type, input, enum, interface, union or scalar definitions found.")
        return out.joinToString("\n\n") + "\n"
    }
}
