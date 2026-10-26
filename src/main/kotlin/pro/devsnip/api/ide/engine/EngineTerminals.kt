package pro.devsnip.api.ide.engine

import com.google.gson.JsonObject
import com.intellij.execution.RunContentExecutor
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.configurations.PtyCommandLine
import com.intellij.execution.process.KillableProcessHandler
import com.intellij.execution.process.ProcessEvent
import com.intellij.execution.process.ProcessListener
import com.intellij.notification.Notification
import com.intellij.notification.NotificationType
import com.intellij.notification.Notifications
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.SystemInfo
import com.intellij.util.EnvironmentUtil
import java.nio.charset.StandardCharsets
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap

/**
 * VS Code's integrated terminal (createTerminal + sendText) for the extension
 * host: the user's shell, interactive, in a terminal console in the Run tool
 * window (a real terminal emulator, so interactive and full-screen programs work). The
 * platform's TerminalExecutionConsole is the same on every supported IDE.
 */
class EngineTerminals(private val project: Project, private val engine: DevSnipEngine) {
    private class Terminal(val id: Int, val name: String, val cwd: Path) {
        var handler: KillableProcessHandler? = null
        val waiting = mutableListOf<String>()
        var failed = false
    }

    private val terminals = ConcurrentHashMap<Int, Terminal>()

    /** Starts the shell (on the EDT); text sent before it is ready waits for it. */
    fun create(p: JsonObject, directory: (String?) -> Path) {
        val id = p["id"].asInt
        if (terminals.containsKey(id)) return
        val terminal = Terminal(id, p["name"]?.takeIf { it.isJsonPrimitive }?.asString ?: "DevSnip Pro", directory(p["cwd"]?.takeIf { it.isJsonPrimitive }?.asString))
        terminals[id] = terminal
        val shellPath = p["shellPath"]?.takeIf { it.isJsonPrimitive }?.asString
        val shellArgs = p["shellArgs"]?.takeIf { it.isJsonArray }?.asJsonArray?.map { it.asString }
        val env = p["env"]?.takeIf { it.isJsonObject }?.asJsonObject
        ApplicationManager.getApplication().executeOnPooledThread {
            val handler = try {
                val line = PtyCommandLine(shell(shellPath, shellArgs))
                    .withInitialColumns(160)
                    .withInitialRows(40)
                line.withWorkDirectory(terminal.cwd.toFile())
                line.withParentEnvironmentType(GeneralCommandLine.ParentEnvironmentType.CONSOLE)
                line.withEnvironment("TERM", "xterm-256color")
                env?.entrySet()?.forEach { (k, v) -> if (v.isJsonPrimitive) line.withEnvironment(k, v.asString) }
                line.withCharset(StandardCharsets.UTF_8)
                KillableProcessHandler(line)
            } catch (e: Exception) {
                terminal.failed = true
                ApplicationManager.getApplication().invokeLater({ couldNotStart(terminal, e.message ?: e.javaClass.simpleName) }, project.disposed)
                return@executeOnPooledThread
            }
            handler.addProcessListener(object : ProcessListener {
                override fun processTerminated(event: ProcessEvent) {
                    terminals.remove(id)
                    engine.post(JsonObject().apply { addProperty("type", "__terminalClosed"); addProperty("id", id); addProperty("code", event.exitCode) })
                }
            })
            ApplicationManager.getApplication().invokeLater({
                val console = TerminalConsoles.create(project, handler)
                RunContentExecutor(project, handler).withConsole(console).withTitle(terminal.name).withActivateToolWindow(true).run()
                synchronized(terminal) {
                    terminal.handler = handler
                    terminal.waiting.forEach { write(handler, it) }
                    terminal.waiting.clear()
                }
            }, project.disposed)
        }
    }

    fun send(id: Int, text: String, newline: Boolean) {
        val terminal = terminals[id] ?: return
        val input = if (newline) "$text\r" else text
        synchronized(terminal) {
            if (terminal.failed) {
                CopyPasteManager.getInstance().setContents(java.awt.datatransfer.StringSelection(text))
                return
            }
            val handler = terminal.handler
            if (handler == null) terminal.waiting += input else write(handler, input)
        }
    }

    private fun write(handler: KillableProcessHandler, text: String) {
        runCatching {
            handler.processInput?.let { stream ->
                stream.write(text.toByteArray(StandardCharsets.UTF_8))
                stream.flush()
            }
        }
    }

    fun show(id: Int) {
        com.intellij.openapi.wm.ToolWindowManager.getInstance(project).getToolWindow(com.intellij.openapi.wm.ToolWindowId.RUN)?.activate(null)
    }

    fun dispose(id: Int) {
        terminals.remove(id)?.handler?.destroyProcess()
    }

    fun disposeAll() {
        terminals.values.forEach { it.handler?.destroyProcess() }
        terminals.clear()
    }

    /** If the shell cannot start, what was sent is copied so it can be run by hand. */
    private fun couldNotStart(terminal: Terminal, reason: String) {
        val pending = synchronized(terminal) { terminal.waiting.joinToString("").trim() }
        if (pending.isNotEmpty()) CopyPasteManager.getInstance().setContents(java.awt.datatransfer.StringSelection(pending))
        val copied = if (pending.isNotEmpty()) " The command was copied; run it in a terminal in ${terminal.cwd.fileName}." else ""
        Notifications.Bus.notify(Notification(EngineHost.NOTIFICATIONS, "DevSnip Pro", "The terminal could not start ($reason).$copied", NotificationType.WARNING), project)
    }

    /** The user's shell, interactive (a login shell on macOS and Linux, as VS Code's terminal). */
    private fun shell(shellPath: String?, shellArgs: List<String>?): List<String> {
        if (!shellPath.isNullOrBlank()) return listOf(shellPath) + shellArgs.orEmpty()
        if (SystemInfo.isWindows) return listOf("powershell.exe", "-NoLogo")
        val shell = EnvironmentUtil.getValue("SHELL")?.takeIf { it.isNotBlank() } ?: if (SystemInfo.isMac) "/bin/zsh" else "/bin/bash"
        val name = Path.of(shell).fileName.toString()
        return if (name in setOf("bash", "zsh", "fish", "sh", "ksh")) listOf(shell, "-l") else listOf(shell)
    }
}

/**
 * The platform's terminal console. Newer IDEs (2026.3) build it with
 * TerminalExecutionConsoleBuilder and deprecate the constructors; older ones
 * (2024.x) only have the constructors. The one that exists is found at run
 * time, the builder first, as ProjectTrust does for the trust API.
 */
object TerminalConsoles {
    fun create(project: Project, handler: com.intellij.execution.process.ProcessHandler): com.intellij.execution.ui.ConsoleView {
        val loader = TerminalConsoles::class.java.classLoader
        runCatching {
            val builder = Class.forName("com.intellij.terminal.TerminalExecutionConsoleBuilder", true, loader).getConstructor(Project::class.java).newInstance(project)
            return builder.javaClass.getMethod("build").invoke(builder) as com.intellij.execution.ui.ConsoleView
        }
        val type = Class.forName("com.intellij.terminal.TerminalExecutionConsole", true, loader)
        return type.getConstructor(Project::class.java, com.intellij.execution.process.ProcessHandler::class.java).newInstance(project, handler) as com.intellij.execution.ui.ConsoleView
    }
}
