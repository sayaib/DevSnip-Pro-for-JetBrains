package pro.devsnip.api.ide.web

import com.intellij.openapi.project.Project
import org.jetbrains.plugins.terminal.TerminalToolWindowManager

/**
 * The IDE's terminal, for the scanners' "Run in Terminal" (VS Code's
 * createTerminal + sendText). Only called when the Terminal plugin is
 * enabled (an optional dependency, see devsnip-terminal.xml).
 */
object DevSnipTerminal {
    fun run(project: Project, workingDirectory: String, tabName: String, command: String) {
        TerminalToolWindowManager.getInstance(project).createShellWidget(workingDirectory, tabName.ifEmpty { "DevSnip Pro" }, true, true).sendCommandToExecute(command)
    }
}
