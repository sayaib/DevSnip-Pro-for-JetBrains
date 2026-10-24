package pro.devsnip.api.ide.ui

import com.intellij.openapi.fileChooser.FileChooserFactory
import com.intellij.openapi.fileChooser.FileSaverDescriptor
import com.intellij.openapi.project.Project
import java.io.File

/** File dialogs shared by the tool window panels. */
object Files {
    /** Asks where to save; returns the chosen file, or null when cancelled. */
    fun chooseSaveTarget(project: Project, title: String, suggestedName: String, description: String = ""): File? {
        val extension = suggestedName.substringAfterLast('.', "")
        val descriptor = saverDescriptor(title, description, extension)
        return FileChooserFactory.getInstance().createSaveFileDialog(descriptor, project).save(suggestedName)?.file
    }

    /**
     * 2024.1 only has `FileSaverDescriptor(String, String, String...)`, which newer
     * platforms deprecate in favour of `(String, String, String)`. Pick whichever
     * the running IDE has, so the plugin uses no deprecated API on either.
     */
    fun saverDescriptor(title: String, description: String, extension: String): FileSaverDescriptor {
        val type = FileSaverDescriptor::class.java
        val string = String::class.java
        val extensions = if (extension.isEmpty()) emptyArray() else arrayOf(extension)
        return runCatching { if (extension.isEmpty()) error("no extension") else type.getConstructor(string, string, string).newInstance(title, description, extension) }
            .getOrElse { type.getConstructor(string, string, Array<String>::class.java).newInstance(title, description, extensions) }
    }
}
