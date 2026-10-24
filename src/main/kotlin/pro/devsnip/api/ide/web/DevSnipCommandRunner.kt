package pro.devsnip.api.ide.web

import com.intellij.execution.ExecutionException
import com.intellij.execution.RunContentExecutor
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.filters.TextConsoleBuilderFactory
import com.intellij.execution.process.KillableColoredProcessHandler
import com.intellij.execution.process.ProcessTerminatedListener
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.SystemInfo
import com.intellij.util.EnvironmentUtil
import java.nio.file.Path

/**
 * The scanners' "Run in Terminal" (VS Code's createTerminal + sendText): the
 * confirmed command runs in the user's shell, in the project folder, with its
 * output, exit code and Stop button in the Run tool window. The platform's
 * execution API is the same on every supported IDE, unlike the Terminal
 * plugin's, which newer IDEs deprecate.
 */
object DevSnipCommandRunner {
    /** [onShown] gets the tab's console, which lives until the user closes the tab. */
    fun run(project: Project, workingDirectory: Path, title: String, command: String, onShown: (Disposable) -> Unit = {}, onFailure: (ExecutionException) -> Unit) {
        ApplicationManager.getApplication().executeOnPooledThread {
            val handler = try {
                KillableColoredProcessHandler(shell(command).withWorkDirectory(workingDirectory.toFile()))
            } catch (e: ExecutionException) {
                ApplicationManager.getApplication().invokeLater({ onFailure(e) }, project.disposed)
                return@executeOnPooledThread
            }
            ProcessTerminatedListener.attach(handler, project)
            ApplicationManager.getApplication().invokeLater({
                val console = TextConsoleBuilderFactory.getInstance().createBuilder(project).console
                RunContentExecutor(project, handler).withConsole(console).withTitle(title.ifEmpty { "DevSnip Pro" }).withActivateToolWindow(true).run()
                onShown(console)
            }, project.disposed)
        }
    }

    /** The command line as the user's terminal would run it: cmd.exe on Windows, the login shell (or sh) elsewhere. */
    fun shell(command: String): GeneralCommandLine {
        val line = if (SystemInfo.isWindows) GeneralCommandLine("cmd.exe", "/c", command)
        else GeneralCommandLine(EnvironmentUtil.getValue("SHELL")?.takeIf { it.isNotBlank() } ?: "/bin/sh", "-c", command)
        return line.withParentEnvironmentType(GeneralCommandLine.ParentEnvironmentType.CONSOLE)
    }
}
