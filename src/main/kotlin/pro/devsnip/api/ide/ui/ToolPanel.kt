package pro.devsnip.api.ide.ui

import com.intellij.icons.AllIcons
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.guessProjectDir
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPasswordField
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTabbedPane
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.components.JBTextField
import com.intellij.ui.table.JBTable
import com.intellij.util.Alarm
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import pro.devsnip.api.core.toolkit.FieldKind
import pro.devsnip.api.core.toolkit.FieldSpec
import pro.devsnip.api.core.toolkit.GeneratedFile
import pro.devsnip.api.core.toolkit.Js
import pro.devsnip.api.core.toolkit.MessageKind
import pro.devsnip.api.core.toolkit.Output
import pro.devsnip.api.core.toolkit.Tone
import pro.devsnip.api.core.toolkit.ToolDefinition
import pro.devsnip.api.core.toolkit.ToolInputError
import pro.devsnip.api.core.toolkit.ToolResult
import java.awt.BorderLayout
import java.awt.Component
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Font
import java.awt.datatransfer.StringSelection
import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicInteger
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.ScrollPaneConstants
import javax.swing.event.DocumentEvent
import javax.swing.event.DocumentListener
import javax.swing.table.DefaultTableModel

/**
 * Renders any toolkit tool from its metadata, like the VS Code workbench:
 * the form (with examples, conditional fields and "use editor" inputs), a run
 * button or live re-run, and the results (stats, messages, code, tables and
 * generated files) with Copy, Insert at Caret and Save to Project.
 *
 * Tools are pure functions; they run on a pooled thread and the latest result wins.
 */
class ToolPanel(private val project: Project, private val tool: ToolDefinition) : JPanel(BorderLayout()), Disposable {
    private val descriptor = tool.descriptor
    private val inputs = LinkedHashMap<String, Input>()
    private val fieldRows = LinkedHashMap<String, JComponent>()
    private val results = JPanel().apply { layout = BoxLayout(this, BoxLayout.Y_AXIS); isOpaque = false }
    private val runButton = JButton(descriptor.runLabel ?: "Run", AllIcons.Actions.Execute)
    private val liveAlarm = Alarm(Alarm.ThreadToUse.SWING_THREAD, this)
    private val generation = AtomicInteger()
    private var filling = false

    /** One form control and how to read and write its value. */
    private class Input(val component: JComponent, val get: () -> Any?, val set: (Any?) -> Unit)

    init {
        val content = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            border = JBUI.Borders.empty(8, 10)
        }
        content.add(header())
        descriptor.fields.forEach { content.add(fieldRow(it)) }
        content.add(left(JPanel(FlowLayout(FlowLayout.LEFT, 0, JBUI.scale(6))).apply {
            isOpaque = false
            runButton.addActionListener { run() }
            add(runButton)
            if (descriptor.live) add(JBLabel("  Updates as you type").apply { foreground = UIUtil.getContextHelpForeground() })
        }))
        content.add(results)
        content.add(Box.createVerticalGlue())
        add(JBScrollPane(content).apply {
            horizontalScrollBarPolicy = ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER
            border = JBUI.Borders.empty()
            verticalScrollBar.unitIncrement = JBUI.scale(16)
        }, BorderLayout.CENTER)
        updateVisibility()
        if (descriptor.live) run()
    }

    // ============================================================== form

    private fun header(): JComponent {
        val panel = JPanel().apply { layout = BoxLayout(this, BoxLayout.Y_AXIS); isOpaque = false }
        panel.add(left(JBLabel(descriptor.title).apply { font = font.deriveFont(Font.BOLD, font.size + 2f) }))
        panel.add(left(wrapped(descriptor.summary)))
        descriptor.guide?.let { panel.add(left(wrapped(it).apply { foreground = UIUtil.getContextHelpForeground() })) }
        if (descriptor.examples.isNotEmpty()) {
            val examples = ComboBox(arrayOf("Examples…") + descriptor.examples.map { it.label })
            examples.addActionListener {
                val index = examples.selectedIndex - 1
                if (index >= 0) {
                    fill(descriptor.examples[index].values)
                    examples.selectedIndex = 0
                    run()
                }
            }
            panel.add(left(JPanel(FlowLayout(FlowLayout.LEFT, 0, JBUI.scale(4))).apply { isOpaque = false; add(examples) }))
        }
        return panel
    }

    private fun fieldRow(field: FieldSpec): JComponent {
        val input = createInput(field)
        inputs[field.id] = input
        val row = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            isOpaque = false
            border = JBUI.Borders.emptyTop(6)
        }
        if (field.group != null) row.add(left(JBLabel(field.group).apply { font = font.deriveFont(Font.BOLD) }))
        if (field.kind != FieldKind.TOGGLE) {
            val labelRow = JPanel(BorderLayout()).apply { isOpaque = false }
            labelRow.add(JBLabel(field.label + if (field.required) " *" else ""), BorderLayout.WEST)
            if (field.fromEditor) {
                labelRow.add(JButton("Use Editor").apply {
                    toolTipText = "Fill from the selection in the active editor, or the whole file"
                    putClientProperty("JButton.buttonType", "borderless")
                    addActionListener { useEditor(field) }
                }, BorderLayout.EAST)
            }
            row.add(left(labelRow))
        }
        row.add(left(input.component))
        field.help?.let { row.add(left(wrapped(it).apply { foreground = UIUtil.getContextHelpForeground() })) }
        fieldRows[field.id] = row
        return left(row)
    }

    private fun createInput(field: FieldSpec): Input {
        val default = field.default
        val onChange = { changed() }
        return when (field.kind) {
            FieldKind.SELECT -> {
                val box = ComboBox(field.options.map { it.label }.toTypedArray())
                field.options.indexOfFirst { it.value == default }.takeIf { it >= 0 }?.let { box.selectedIndex = it }
                box.addActionListener { onChange() }
                Input(box, { field.options.getOrNull(box.selectedIndex)?.value }, { v -> field.options.indexOfFirst { it.value == v }.takeIf { it >= 0 }?.let { box.selectedIndex = it } })
            }
            FieldKind.TOGGLE -> {
                val box = JBCheckBox(field.label, default == true)
                box.isOpaque = false
                box.addActionListener { onChange() }
                Input(box, { box.isSelected }, { v -> box.isSelected = v == true || v == "true" })
            }
            FieldKind.TEXTAREA, FieldKind.CODE -> {
                val area = JBTextArea(field.rows ?: 6, 40).apply {
                    lineWrap = field.kind == FieldKind.TEXTAREA
                    if (field.kind == FieldKind.CODE) font = Font(Font.MONOSPACED, Font.PLAIN, UIUtil.getLabelFont().size)
                    text = default?.let { Js.string(it) }.orEmpty()
                    caretPosition = 0
                    emptyText.text = field.placeholder.orEmpty()
                    document.addDocumentListener(listener(onChange))
                }
                val scroll = JBScrollPane(area).apply { preferredSize = Dimension(JBUI.scale(400), area.preferredSize.height + JBUI.scale(8)) }
                Input(scroll, { area.text }, { v -> area.text = v?.let { Js.string(it) }.orEmpty(); area.caretPosition = 0 })
            }
            FieldKind.SECRET -> {
                val box = JBPasswordField().apply { document.addDocumentListener(listener(onChange)) }
                Input(box, { String(box.password) }, { v -> box.text = v?.let { Js.string(it) }.orEmpty() })
            }
            FieldKind.NUMBER, FieldKind.TEXT -> {
                val box = JBTextField(default?.let { Js.string(it) }.orEmpty()).apply {
                    emptyText.text = field.placeholder.orEmpty()
                    document.addDocumentListener(listener(onChange))
                    addActionListener { run() }
                }
                Input(box, { box.text }, { v -> box.text = v?.let { Js.string(it) }.orEmpty() })
            }
        }
    }

    private fun listener(onChange: () -> Unit) = object : DocumentListener {
        override fun insertUpdate(e: DocumentEvent) = onChange()
        override fun removeUpdate(e: DocumentEvent) = onChange()
        override fun changedUpdate(e: DocumentEvent) = onChange()
    }

    private fun changed() {
        if (filling) return
        updateVisibility()
        if (descriptor.live) {
            liveAlarm.cancelAllRequests()
            liveAlarm.addRequest({ run() }, 350)
        }
    }

    /** Shows only the fields whose `showIf` condition holds, as the VS Code form does. */
    private fun updateVisibility() {
        val current = currentValues()
        for (field in descriptor.fields) fieldRows[field.id]?.isVisible = ToolDefinition.isVisible(field, current)
        revalidate()
        repaint()
    }

    private fun currentValues(): Map<String, Any?> = inputs.mapValues { (_, input) -> input.get() }

    private fun fill(values: Map<String, Any>) {
        filling = true
        try {
            // Start from the defaults so an example does not inherit unrelated edits.
            descriptor.fields.forEach { field -> inputs[field.id]?.set?.invoke(field.default) }
            values.forEach { (key, value) -> inputs[key]?.set?.invoke(value) }
        } finally {
            filling = false
        }
        updateVisibility()
    }

    private fun useEditor(field: FieldSpec) {
        val editor = FileEditorManager.getInstance(project).selectedTextEditor
        if (editor == null) {
            Messages.showInfoMessage(project, "Open a file in the editor first (or select part of it).", descriptor.title)
            return
        }
        val text = editor.selectionModel.selectedText?.takeIf { it.isNotBlank() } ?: editor.document.text
        inputs[field.id]?.set?.invoke(text)
        changed()
        if (!descriptor.live) run()
    }

    // ============================================================== running

    fun run() {
        val values = tool.formValues(currentValues())
        val ticket = generation.incrementAndGet()
        runButton.isEnabled = false
        ApplicationManager.getApplication().executeOnPooledThread {
            val outcome: Result<ToolResult> = try {
                Result.success(tool.run(values))
            } catch (e: ToolInputError) {
                Result.failure(e)
            } catch (e: Exception) {
                LOG.warn("Tool ${descriptor.id} failed", e)
                Result.failure(e)
            }
            ApplicationManager.getApplication().invokeLater {
                if (ticket != generation.get() || project.isDisposed) return@invokeLater
                runButton.isEnabled = true
                outcome.onSuccess(::show).onFailure { showError(it) }
            }
        }
    }

    private fun showError(error: Throwable) {
        results.removeAll()
        val text = if (error is ToolInputError) error.message.orEmpty() else "Something went wrong: ${error.message ?: error.javaClass.simpleName}"
        results.add(messageRow(MessageKind.ERROR, text))
        results.revalidate()
        results.repaint()
    }

    private fun show(result: ToolResult) {
        results.removeAll()
        result.stats?.takeIf { it.isNotEmpty() }?.let { stats ->
            results.add(left(JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(12), JBUI.scale(6))).apply {
                isOpaque = false
                stats.forEach { s ->
                    add(JBLabel("<html><span style='color:gray'>${escape(s.label)}</span>&nbsp;<b>${escape(s.value)}</b></html>").apply {
                        when (s.tone) {
                            Tone.GOOD -> foreground = GOOD
                            Tone.WARN -> foreground = WARN
                            Tone.BAD -> foreground = JBColor.RED
                            else -> Unit
                        }
                    })
                }
            }))
        }
        result.messages?.forEach { results.add(messageRow(it.kind, it.text)) }
        result.outputs?.forEach { results.add(outputBlock(it)) }
        results.revalidate()
        results.repaint()
    }

    private fun messageRow(kind: MessageKind, text: String): JComponent {
        val icon = when (kind) {
            MessageKind.ERROR -> AllIcons.General.Error
            MessageKind.WARNING -> AllIcons.General.Warning
            MessageKind.SUCCESS -> AllIcons.General.InspectionsOK
            MessageKind.INFO -> AllIcons.General.Information
        }
        return left(JPanel(BorderLayout(JBUI.scale(6), 0)).apply {
            isOpaque = false
            border = JBUI.Borders.empty(2, 0)
            add(JBLabel(icon).apply { verticalAlignment = JBLabel.TOP }, BorderLayout.WEST)
            add(wrapped(text), BorderLayout.CENTER)
        })
    }

    private fun outputBlock(output: Output): JComponent {
        val block = JPanel(BorderLayout(0, JBUI.scale(4))).apply {
            isOpaque = false
            border = JBUI.Borders.emptyTop(10)
        }
        val title = JPanel(BorderLayout()).apply { isOpaque = false }
        title.add(JBLabel(output.title).apply { font = font.deriveFont(Font.BOLD) }, BorderLayout.WEST)
        val buttons = JPanel(FlowLayout(FlowLayout.RIGHT, JBUI.scale(4), 0)).apply { isOpaque = false }
        title.add(buttons, BorderLayout.EAST)
        block.add(title, BorderLayout.NORTH)
        when (output) {
            is Output.Code -> {
                buttons.add(smallButton("Copy", AllIcons.Actions.Copy) { copy(output.content) })
                buttons.add(smallButton("Insert at Caret", AllIcons.Actions.MenuPaste) { insertAtCaret(output.content) })
                buttons.add(smallButton("Save…", AllIcons.Actions.MenuSaveall) { saveAs(output.fileName ?: "output.${extension(output.language)}", output.content) })
                block.add(codeView(output.content), BorderLayout.CENTER)
            }
            is Output.Text -> {
                buttons.add(smallButton("Copy", AllIcons.Actions.Copy) { copy(output.content) })
                block.add(wrapped(output.content), BorderLayout.CENTER)
            }
            is Output.Table -> {
                buttons.add(smallButton("Copy as TSV", AllIcons.Actions.Copy) {
                    copy((listOf(output.columns) + output.rows.map { row -> row.map { Js.string(it) } }).joinToString("\n") { it.joinToString("\t") })
                })
                val model = object : DefaultTableModel(output.columns.toTypedArray(), 0) {
                    override fun isCellEditable(row: Int, column: Int) = false
                }
                output.rows.forEach { row -> model.addRow(row.map { if (it is Number) Js.number(it.toDouble()) else it }.toTypedArray()) }
                val table = JBTable(model).apply { setShowGrid(false) }
                val height = table.rowHeight * minOf(output.rows.size, 12) + table.tableHeader.preferredSize.height + JBUI.scale(4)
                block.add(JBScrollPane(table).apply { preferredSize = Dimension(JBUI.scale(400), height) }, BorderLayout.CENTER)
            }
            is Output.Files -> {
                buttons.add(smallButton("Save All to Project", AllIcons.Actions.MenuSaveall) { saveFiles(output.files) })
                val tabs = JBTabbedPane()
                output.files.forEach { file ->
                    val panel = JPanel(BorderLayout()).apply { isOpaque = false }
                    panel.add(JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(4), 0)).apply {
                        isOpaque = false
                        add(JBLabel(file.path + if (file.mode == "append") "  (appended to the existing file)" else "").apply { foreground = UIUtil.getContextHelpForeground() })
                        add(smallButton("Copy", AllIcons.Actions.Copy) { copy(file.content) })
                    }, BorderLayout.NORTH)
                    panel.add(codeView(file.content), BorderLayout.CENTER)
                    tabs.addTab(file.path.substringAfterLast('/'), panel)
                }
                block.add(tabs, BorderLayout.CENTER)
            }
        }
        return left(block)
    }

    private fun codeView(content: String): JComponent {
        val area = JBTextArea(content).apply {
            isEditable = false
            font = Font(Font.MONOSPACED, Font.PLAIN, UIUtil.getLabelFont().size)
            caretPosition = 0
        }
        val lines = content.count { it == '\n' } + 1
        return JBScrollPane(area).apply {
            preferredSize = Dimension(JBUI.scale(400), (area.getFontMetrics(area.font).height * minOf(lines, 24)) + JBUI.scale(16))
        }
    }

    // ============================================================== actions

    private fun copy(text: String) {
        CopyPasteManager.getInstance().setContents(StringSelection(text))
    }

    private fun insertAtCaret(text: String) {
        val editor = FileEditorManager.getInstance(project).selectedTextEditor
        if (editor == null || !editor.document.isWritable) {
            Messages.showInfoMessage(project, "Open a writable file in the editor and place the caret where the code should go.", descriptor.title)
            return
        }
        WriteCommandAction.runWriteCommandAction(project, "Insert ${descriptor.title} Output", null, {
            val selection = editor.selectionModel
            if (selection.hasSelection()) {
                editor.document.replaceString(selection.selectionStart, selection.selectionEnd, text)
            } else {
                editor.document.insertString(editor.caretModel.offset, text)
            }
        })
    }

    private fun saveAs(name: String, content: String) {
        val target = Files.chooseSaveTarget(project, "Save ${descriptor.title} Output", name) ?: return
        try {
            target.writeText(content)
            VfsUtil.findFileByIoFile(target, true)?.let { FileEditorManager.getInstance(project).openTextEditor(OpenFileDescriptor(project, it), true) }
        } catch (e: Exception) {
            Messages.showErrorDialog(project, "Could not write the file: ${e.message}", descriptor.title)
        }
    }

    /** Writes generated files into the project, asking before overwriting anything. */
    private fun saveFiles(files: List<GeneratedFile>) {
        val base = project.guessProjectDir()
        if (base == null) {
            Messages.showErrorDialog(project, "This project has no base directory to save into.", descriptor.title)
            return
        }
        val existing = files.filter { it.mode != "append" && base.findFileByRelativePath(it.path) != null }.map { it.path }
        if (existing.isNotEmpty()) {
            val answer = Messages.showYesNoDialog(project, "These files already exist and will be overwritten:\n\n${existing.joinToString("\n")}\n\nContinue?", descriptor.title, null)
            if (answer != Messages.YES) return
        }
        try {
            val written = WriteCommandAction.writeCommandAction(project).withName("Save ${descriptor.title} Files").compute<List<com.intellij.openapi.vfs.VirtualFile>, Exception> {
                files.map { file ->
                    val dir = VfsUtil.createDirectoryIfMissing(base, file.path.substringBeforeLast('/', ""))
                        ?: error("Could not create ${file.path.substringBeforeLast('/')}")
                    val name = file.path.substringAfterLast('/')
                    val target = dir.findChild(name) ?: dir.createChildData(this, name)
                    val previous = if (file.mode == "append" && target.length > 0) String(target.contentsToByteArray(), StandardCharsets.UTF_8) else ""
                    target.setBinaryContent((previous + file.content).toByteArray(StandardCharsets.UTF_8))
                    target
                }
            }
            written.firstOrNull()?.let { FileEditorManager.getInstance(project).openTextEditor(OpenFileDescriptor(project, it), true) }
            Messages.showInfoMessage(project, "Saved ${written.size} file${if (written.size == 1) "" else "s"} under ${base.presentableUrl}.", descriptor.title)
        } catch (e: Exception) {
            Messages.showErrorDialog(project, "Could not save the files: ${e.message}", descriptor.title)
        }
    }

    override fun dispose() {
        generation.incrementAndGet()
    }

    // For tests: set a field as the user would, read what the results show.
    internal fun setField(id: String, value: Any?) {
        inputs.getValue(id).set(value)
        changed()
    }

    internal fun isFieldVisible(id: String) = fieldRows.getValue(id).isVisible

    internal val isRunning get() = !runButton.isEnabled

    internal fun resultText(): String {
        val out = StringBuilder()
        fun walk(c: Component) {
            when (c) {
                is JBTextArea -> out.append(c.text).append('\n')
                is JBLabel -> out.append(c.text).append('\n')
                is JBTable -> for (r in 0 until c.rowCount) out.append((0 until c.columnCount).joinToString(" | ") { c.getValueAt(r, it).toString() }).append('\n')
                is javax.swing.JTabbedPane -> for (i in 0 until c.tabCount) out.append("[tab] ").append(c.getTitleAt(i)).append('\n')
            }
            if (c is java.awt.Container) c.components.forEach(::walk)
        }
        walk(results)
        return out.toString()
    }

    companion object {
        private val LOG = Logger.getInstance(ToolPanel::class.java)
        private val GOOD = JBColor(java.awt.Color(0x2E7D32), java.awt.Color(0x6AAB73))
        private val WARN = JBColor(java.awt.Color(0xC77700), java.awt.Color(0xE0A04A))

        private fun left(c: JComponent): JComponent = c.apply { alignmentX = Component.LEFT_ALIGNMENT }

        private fun escape(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

        /** A read-only label that wraps to the panel width. */
        private fun wrapped(text: String) = JBTextArea(text).apply {
            isEditable = false
            lineWrap = true
            wrapStyleWord = true
            isOpaque = false
            border = JBUI.Borders.empty(2, 0)
            font = UIUtil.getLabelFont()
        }

        private fun smallButton(text: String, icon: javax.swing.Icon, action: () -> Unit) = JButton(text, icon).apply {
            putClientProperty("JButton.buttonType", "borderless")
            addActionListener { action() }
        }

        private fun extension(language: String) = when (language) {
            "javascript" -> "js"; "typescript" -> "ts"; "python" -> "py"; "kotlin" -> "kt"; "csharp" -> "cs"; "shell" -> "sh"
            "graphql" -> "graphql"; "json" -> "json"; "yaml" -> "yaml"; "java" -> "java"; "go" -> "go"; "dart" -> "dart"
            "swift" -> "swift"; "php" -> "php"; "http" -> "http"; "nginx" -> "conf"
            else -> "txt"
        }
    }
}
