package pro.devsnip.api.ide.engine

import com.google.gson.JsonElement
import com.google.gson.JsonNull
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.RoamingType
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import pro.devsnip.api.ide.web.DevSnipUiState
import java.util.UUID

/**
 * The extension's `context.globalState` (points and milestones, the sidebar's
 * favourites and recents, onboarding, the theme, database connections, ...),
 * kept by the IDE for every project's extension host, as VS Code keeps it for
 * every window. A change made by one host reaches the others.
 *
 * The first time, the sidebar state and theme an earlier version of the
 * plugin kept (DevSnipUiState) are carried over, so nothing chosen is lost.
 */
@Service(Service.Level.APP)
@State(name = "DevSnipProGlobalState", storages = [Storage(value = "devsnip-pro-state.xml", roamingType = RoamingType.DISABLED)])
class EngineState : PersistentStateComponent<EngineState.Data> {
    class Data {
        var globalStateJson: String = ""
        var machineId: String = ""
    }

    private var data = Data()
    private var cache: JsonObject? = null

    override fun getState(): Data = synchronized(this) {
        cache?.let { data.globalStateJson = it.toString() }
        data
    }

    override fun loadState(state: Data) = synchronized(this) {
        data = state
        cache = null
    }

    private fun values(): JsonObject {
        cache?.let { return it }
        val parsed = runCatching { JsonParser.parseString(data.globalStateJson).takeIf { it.isJsonObject }?.asJsonObject }.getOrNull()
        val values = parsed ?: migrate()
        cache = values
        return values
    }

    /** Carries over what DevSnipUiState kept before the extension host existed. */
    private fun migrate(): JsonObject = JsonObject().apply {
        val ui = runCatching { DevSnipUiState.getInstance() }.getOrNull() ?: return@apply
        if (ui.theme.isNotEmpty() && ui.theme != "system") addProperty(THEME_KEY, ui.theme)
        if (ui.expanded.isNotEmpty()) add("devsnip.sidebar.expanded", com.google.gson.JsonArray().apply { ui.expanded.forEach(::add) })
        if (ui.favorites.isNotEmpty()) add("devsnip.sidebar.favorites", com.google.gson.JsonArray().apply { ui.favorites.forEach(::add) })
        val usage = ui.usage()
        if (usage.size() > 0) add("devsnip.sidebar.usage", usage)
    }

    /** A copy of everything, for a host that is starting. */
    fun snapshot(): JsonObject = synchronized(this) { values().deepCopy() }

    fun get(key: String): JsonElement? = synchronized(this) { values()[key]?.deepCopy() }

    /** Stores one value (null removes it) and tells the other hosts. */
    fun update(key: String, value: JsonElement?, origin: DevSnipEngine?) {
        synchronized(this) {
            val values = values()
            if (value == null || value.isJsonNull) values.remove(key) else values.add(key, value.deepCopy())
            data.globalStateJson = values.toString()
        }
        if (key == THEME_KEY) value?.takeIf { it.isJsonPrimitive }?.asString?.let { DevSnipUiState.getInstance().theme = it }
        DevSnipEngine.all().filter { it !== origin }.forEach { engine ->
            engine.post(JsonObject().apply {
                addProperty("type", "__globalState")
                addProperty("key", key)
                add("value", value ?: JsonNull.INSTANCE)
            })
        }
    }

    /** An anonymous id for this installation (VS Code's env.machineId), made once. */
    val machineId: String
        get() = synchronized(this) {
            if (data.machineId.isEmpty()) data.machineId = UUID.randomUUID().toString().replace("-", "")
            data.machineId
        }

    companion object {
        const val THEME_KEY = "devsnip.appearance.theme"

        fun getInstance(): EngineState = ApplicationManager.getApplication().getService(EngineState::class.java)
    }
}
