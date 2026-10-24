package pro.devsnip.api.ide.ui

import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.ui.CollectionListModel
import com.intellij.ui.JBSplitter
import com.intellij.ui.ToolbarDecorator
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBList
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import pro.devsnip.api.core.Environment
import pro.devsnip.api.core.KeyValue
import java.awt.BorderLayout
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.ListSelectionModel

/**
 * Edits environments and their `{{variables}}`. Works on a copy; the caller
 * saves [result] when the dialog is closed with OK.
 */
class EnvironmentsDialog(project: Project?, initial: List<Environment>) : DialogWrapper(project) {
    private class Draft(var name: String, var variables: List<KeyValue>) {
        override fun toString() = name
    }

    private val listModel = CollectionListModel(initial.map { env -> Draft(env.name, env.variables.map { KeyValue(it.key, it.value) }) })
    private val list = JBList(listModel).apply { selectionMode = ListSelectionModel.SINGLE_SELECTION }
    private val variables = KeyValueTable(keyTitle = "Variable", withEnabledColumn = false, maskSecrets = true)
    private var editing: Draft? = null

    init {
        title = "DevSnip API Environments"
        list.addListSelectionListener {
            if (it.valueIsAdjusting) return@addListSelectionListener
            commitEditing()
            editing = list.selectedValue
            variables.values = editing?.variables.orEmpty()
            variables.table.isEnabled = editing != null
        }
        init()
        if (listModel.size > 0) list.selectedIndex = 0 else variables.table.isEnabled = false
    }

    private fun commitEditing() {
        editing?.variables = variables.values
    }

    override fun createCenterPanel(): JComponent {
        val envPanel = ToolbarDecorator.createDecorator(list)
            .setAddAction {
                val name = Messages.showInputDialog(list, "Environment name:", "New Environment", null)?.trim()
                if (!name.isNullOrEmpty()) {
                    if (listModel.items.any { it.name == name }) {
                        Messages.showErrorDialog(list, "An environment named \"$name\" already exists.", "New Environment")
                    } else {
                        listModel.add(Draft(name, listOf(KeyValue("baseUrl", ""))))
                        list.selectedIndex = listModel.size - 1
                    }
                }
            }
            .setRemoveAction {
                val index = list.selectedIndex
                if (index >= 0) {
                    editing = null
                    listModel.remove(index)
                    variables.values = emptyList()
                    if (listModel.size > 0) list.selectedIndex = (index - 1).coerceAtLeast(0)
                }
            }
            .setEditAction {
                val draft = list.selectedValue ?: return@setEditAction
                val name = Messages.showInputDialog(list, "Environment name:", "Rename Environment", null, draft.name, null)?.trim()
                if (!name.isNullOrEmpty() && name != draft.name) {
                    if (listModel.items.any { it.name == name }) {
                        Messages.showErrorDialog(list, "An environment named \"$name\" already exists.", "Rename Environment")
                    } else {
                        draft.name = name
                        list.repaint()
                    }
                }
            }
            .disableUpDownActions()
            .createPanel()

        val right = JPanel(BorderLayout(0, JBUI.scale(6))).apply {
            add(variables.component, BorderLayout.CENTER)
            add(
                JBLabel(
                    "<html>Use a variable as {{name}} in the URL, params, headers, body or auth.<br>" +
                        "Variables named like token, secret, password, key, auth, cookie or session are stored in the IDE password safe.</html>",
                ).apply { foreground = UIUtil.getContextHelpForeground() },
                BorderLayout.SOUTH,
            )
        }
        return JBSplitter(false, 0.3f).apply {
            firstComponent = envPanel
            secondComponent = right
            preferredSize = JBUI.size(720, 400)
        }
    }

    override fun doValidate(): ValidationInfo? {
        commitEditing()
        val bad = listModel.items.firstOrNull { draft -> draft.variables.any { !it.key.trim().matches(Regex("\\w+")) } }
        return bad?.let { ValidationInfo("Variable names in \"${it.name}\" may only use letters, digits and _ (they are used as {{name}}).", variables.table) }
    }

    val result: List<Environment>
        get() {
            commitEditing()
            return listModel.items.map { draft -> Environment(draft.name, draft.variables.associate { it.key.trim() to it.value }) }
        }
}
