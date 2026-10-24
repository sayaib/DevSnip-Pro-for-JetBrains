package pro.devsnip.api.ide.ui

import com.intellij.ui.ToolbarDecorator
import com.intellij.ui.table.JBTable
import pro.devsnip.api.core.KeyValue
import pro.devsnip.api.core.Redaction
import java.awt.Component
import javax.swing.JComponent
import javax.swing.JTable
import javax.swing.table.AbstractTableModel
import javax.swing.table.DefaultTableCellRenderer

/** An editable table of enabled/key/value rows, used for params, headers and environment variables. */
class KeyValueTable(
    private val keyTitle: String = "Key",
    private val withEnabledColumn: Boolean = true,
    /** Shows values of secret-named rows as dots until they are edited. */
    private val maskSecrets: Boolean = false,
) {
    private val rows = mutableListOf<MutableRow>()

    private class MutableRow(var enabled: Boolean, var key: String, var value: String)

    private val model = object : AbstractTableModel() {
        private val offset get() = if (withEnabledColumn) 0 else 1
        override fun getRowCount() = rows.size
        override fun getColumnCount() = if (withEnabledColumn) 3 else 2
        override fun getColumnName(column: Int) = when (column + offset) {
            0 -> ""
            1 -> keyTitle
            else -> "Value"
        }
        override fun getColumnClass(column: Int): Class<*> = if (column + offset == 0) java.lang.Boolean::class.java else String::class.java
        override fun isCellEditable(row: Int, column: Int) = true
        override fun getValueAt(row: Int, column: Int): Any = when (column + offset) {
            0 -> rows[row].enabled
            1 -> rows[row].key
            else -> rows[row].value
        }
        override fun setValueAt(value: Any?, row: Int, column: Int) {
            when (column + offset) {
                0 -> rows[row].enabled = value as? Boolean ?: true
                1 -> rows[row].key = value?.toString().orEmpty()
                else -> rows[row].value = value?.toString().orEmpty()
            }
            fireTableCellUpdated(row, column)
        }
    }

    val table = JBTable(model).apply {
        setShowGrid(false)
        emptyText.text = "No rows. Click + to add one."
        if (withEnabledColumn) {
            columnModel.getColumn(0).apply { maxWidth = 30; minWidth = 30 }
        }
        putClientProperty("terminateEditOnFocusLost", true)
        if (maskSecrets) {
            val valueColumn = if (withEnabledColumn) 2 else 1
            columnModel.getColumn(valueColumn).cellRenderer = object : DefaultTableCellRenderer() {
                override fun getTableCellRendererComponent(table: JTable, value: Any?, selected: Boolean, focused: Boolean, row: Int, column: Int): Component {
                    val key = rows.getOrNull(row)?.key.orEmpty()
                    val shown = if (Redaction.isSensitiveName(key) && value.toString().isNotEmpty()) "•".repeat(8) else value
                    return super.getTableCellRendererComponent(table, shown, selected, focused, row, column)
                }
            }
        }
    }

    val component: JComponent = ToolbarDecorator.createDecorator(table)
        .setAddAction {
            stopEditing()
            rows += MutableRow(true, "", "")
            model.fireTableRowsInserted(rows.size - 1, rows.size - 1)
            val column = if (withEnabledColumn) 1 else 0
            table.editCellAt(rows.size - 1, column)
            table.changeSelection(rows.size - 1, column, false, false)
        }
        .setRemoveAction {
            stopEditing()
            table.selectedRows.sortedDescending().forEach { rows.removeAt(it) }
            model.fireTableDataChanged()
        }
        .disableUpDownActions()
        .createPanel()

    fun stopEditing() {
        if (table.isEditing) table.cellEditor?.stopCellEditing()
    }

    var values: List<KeyValue>
        get() {
            stopEditing()
            return rows.filter { it.key.isNotBlank() || it.value.isNotBlank() }.map { KeyValue(it.key, it.value, it.enabled) }
        }
        set(value) {
            if (table.isEditing) table.cellEditor?.cancelCellEditing()
            rows.clear()
            value.forEach { rows += MutableRow(it.enabled, it.key, it.value) }
            model.fireTableDataChanged()
        }
}
