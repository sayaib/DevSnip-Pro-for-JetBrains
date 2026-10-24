package pro.devsnip.api.ide.web

import com.intellij.openapi.project.Project
import java.lang.reflect.Method
import java.lang.reflect.Modifier

/**
 * Workspace trust (the extension's vscode.workspace.isTrusted). Newer IDEs
 * have the public `TrustedProjects.isProjectTrusted(Project)`; older ones
 * (2024.x) only the experimental `Project.isTrusted()`, which the newer ones
 * deprecate. Neither exists on every supported IDE, so the check is found at
 * run time: the public API first, the older one only where it is missing.
 * If neither is found the project counts as untrusted.
 */
object ProjectTrust {
    private val CANDIDATES = listOf(
        "com.intellij.ide.trustedProjects.TrustedProjects" to "isProjectTrusted",
        "com.intellij.ide.impl.TrustedProjects" to "isTrusted",
    )

    private val check: Method? by lazy {
        CANDIDATES.firstNotNullOfOrNull { (owner, name) ->
            runCatching { Class.forName(owner, true, ProjectTrust::class.java.classLoader).getMethod(name, Project::class.java) }
                .getOrNull()
                ?.takeIf { Modifier.isStatic(it.modifiers) && it.returnType == java.lang.Boolean.TYPE }
        }
    }

    fun isTrusted(project: Project): Boolean = runCatching { check?.invoke(null, project) as? Boolean }.getOrNull() ?: false
}
