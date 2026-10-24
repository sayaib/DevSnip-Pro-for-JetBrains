package pro.devsnip.api.ide.web

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.RoamingType
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage

/**
 * What the VS Code extension keeps in its global state for the sidebar and
 * toolkit, kept by the IDE: expanded sections, favourites, recently and most
 * used tools, the DevSnip theme, and the tools' own storage (saved prompt
 * templates and the like).
 */
@Service(Service.Level.APP)
@State(name = "DevSnipProUi", storages = [Storage(value = "devsnip-pro-ui.xml", roamingType = RoamingType.DISABLED)])
class DevSnipUiState : PersistentStateComponent<DevSnipUiState.Data> {
    class Data {
        var expanded: MutableList<String> = mutableListOf()
        var favorites: MutableList<String> = mutableListOf()
        var usageJson: String = "{}"
        var theme: String = "system"
        var toolStorageJson: String = "{}"
    }

    private var data = Data()

    override fun getState(): Data = data

    override fun loadState(state: Data) {
        data = state
    }

    var expanded: List<String>
        get() = data.expanded.toList()
        set(value) {
            data.expanded = value.toMutableList()
        }

    var favorites: List<String>
        get() = data.favorites.toList()
        set(value) {
            data.favorites = value.distinct().take(100).toMutableList()
        }

    var theme: String
        get() = data.theme
        set(value) {
            data.theme = value
        }

    /** command → { count, last }, as the sidebar's ToolUsage. */
    fun usage(): JsonObject = parse(data.usageJson)

    fun recordUsage(command: String): JsonObject {
        val usage = usage()
        val entry = usage.getAsJsonObject(command) ?: JsonObject().apply { addProperty("count", 0); addProperty("last", 0) }
        entry.addProperty("count", entry.get("count").asInt + 1)
        entry.addProperty("last", System.currentTimeMillis())
        usage.add(command, entry)
        data.usageJson = usage.toString()
        return usage
    }

    fun toolStorage(): JsonObject = parse(data.toolStorageJson)

    fun setToolStorage(key: String, value: com.google.gson.JsonElement) {
        val storage = toolStorage()
        storage.add(key, value)
        data.toolStorageJson = storage.toString()
    }

    private fun parse(json: String): JsonObject = try {
        JsonParser.parseString(json).takeIf { it.isJsonObject }?.asJsonObject ?: JsonObject()
    } catch (_: Exception) {
        JsonObject()
    }

    companion object {
        fun getInstance(): DevSnipUiState = ApplicationManager.getApplication().getService(DevSnipUiState::class.java)
    }
}
