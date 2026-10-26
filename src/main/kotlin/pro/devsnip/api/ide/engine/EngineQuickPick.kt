package pro.devsnip.api.ide.engine

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonPrimitive
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.popup.JBPopup
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.ui.popup.JBPopupListener
import com.intellij.openapi.ui.popup.LightweightWindowEvent
import com.intellij.ui.CheckBoxList
import com.intellij.ui.ColoredListCellRenderer
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import javax.swing.JComponent
import javax.swing.JList

/**
 * VS Code's quick pick (showQuickPick, createQuickPick) as the IDE's list
 * popup: label, description and detail, separators, type-to-filter, and the
 * live "active item" events the theme picker uses to preview a theme.
 * A pick of many items is a checkbox list dialog.
 */
object EngineQuickPick {
    private data class Item(val index: Int, val label: String, val description: String?, val detail: String?, val picked: Boolean, val separator: Boolean)

    private val open = ConcurrentHashMap<Int, JBPopup>()

    fun show(project: Project, engine: DevSnipEngine, p: JsonObject): CompletableFuture<JsonElement?> {
        val items = (p["items"] as? JsonArray ?: JsonArray()).mapIndexed { index, raw ->
            val o = raw.asJsonObject
            Item(index, o.str("label"), o.opt("description"), o.opt("detail"), o["picked"]?.asBoolean == true, o["separator"]?.asBoolean == true)
        }
        val answer = CompletableFuture<JsonElement?>()
        val title = p.opt("title") ?: p.opt("placeHolder") ?: "DevSnip Pro"
        if (p["canPickMany"]?.asBoolean == true) {
            answer.complete(pickMany(project, title, items.filter { !it.separator }))
            return answer
        }
        val live = p["live"]?.asBoolean == true
        val id = p["id"]?.asInt ?: -1
        fun event(name: String, indices: List<Int> = emptyList()) = engine.post(JsonObject().apply {
            addProperty("type", "__quickPick")
            addProperty("id", id)
            addProperty("event", name)
            add("indices", JsonArray().apply { indices.forEach(::add) })
        })
        val choices = items.filter { !it.separator }
        // A separator labels the item after it.
        val captions = HashMap<Int, String>()
        items.forEachIndexed { i, item -> if (item.separator) items.drop(i + 1).firstOrNull { !it.separator }?.let { captions[it.index] = item.label } }
        var accepted = false
        val builder = JBPopupFactory.getInstance().createPopupChooserBuilder(choices)
            .setTitle(title)
            .setRenderer(object : ColoredListCellRenderer<Item>() {
                override fun customizeCellRenderer(list: JList<out Item>, value: Item, index: Int, selected: Boolean, hasFocus: Boolean) {
                    append(value.label.replace(Regex("\\$\\([a-z0-9-]+\\)\\s*"), ""))
                    value.description?.let { append("  $it", SimpleTextAttributes.GRAYED_ATTRIBUTES) }
                    value.detail?.let { append("  $it", SimpleTextAttributes.GRAY_SMALL_ATTRIBUTES) }
                    captions[value.index]?.let { toolTipText = it }
                    border = JBUI.Borders.empty(2, 6)
                }
            })
            .setNamerForFiltering { listOfNotNull(it.label, it.description, it.detail).joinToString(" ") }
            .setFilterAlwaysVisible(choices.size > 8)
            .setVisibleRowCount(minOf(15, choices.size.coerceAtLeast(1)))
            .setItemChosenCallback { item ->
                accepted = true
                if (live) event("accept", listOf(item.index)) else answer.complete(JsonPrimitive(item.index))
            }
        if (live) builder.setItemSelectedCallback { item -> if (item != null) event("active", listOf(item.index)) }
        val popup = builder.createPopup()
        popup.addListener(object : JBPopupListener {
            override fun onClosed(e: LightweightWindowEvent) {
                open.remove(id)
                if (live) event("hide") else if (!accepted) answer.complete(null)
            }
        })
        if (live) {
            open[id] = popup
            answer.complete(null)
        }
        p["activeIndex"]?.takeIf { it.isJsonPrimitive && it.asInt >= 0 }?.asInt?.let { active ->
            choices.indexOfFirst { it.index == active }.takeIf { it >= 0 }?.let { row -> (popup.content as? JComponent)?.let { selectRow(it, row) } }
        }
        popup.showCenteredInCurrentWindow(project)
        return answer
    }

    private fun selectRow(component: JComponent, row: Int) {
        val list = findList(component) ?: return
        list.selectedIndex = row
        list.ensureIndexIsVisible(row)
    }

    private fun findList(component: java.awt.Component): JList<*>? {
        if (component is JList<*>) return component
        if (component is java.awt.Container) for (child in component.components) findList(child)?.let { return it }
        return null
    }

    fun hide(id: Int) {
        open.remove(id)?.cancel()
    }

    private fun pickMany(project: Project, title: String, items: List<Item>): JsonElement? {
        val list = CheckBoxList<Item>()
        for (item in items) list.addItem(item, listOfNotNull(item.label, item.description).joinToString("  "), item.picked)
        val dialog = object : DialogWrapper(project, true) {
            init {
                setTitle(title)
                init()
            }

            override fun createCenterPanel(): JComponent = JBScrollPane(list).apply { preferredSize = JBUI.size(460, 320) }
        }
        if (!dialog.showAndGet()) return null
        return JsonArray().apply { items.filter { list.isItemSelected(it) }.forEach { add(it.index) } }
    }

    private fun JsonObject.str(key: String): String = get(key)?.takeIf { it.isJsonPrimitive }?.asString ?: ""
    private fun JsonObject.opt(key: String): String? = get(key)?.takeIf { it.isJsonPrimitive }?.asString?.takeIf { it.isNotEmpty() }
}
