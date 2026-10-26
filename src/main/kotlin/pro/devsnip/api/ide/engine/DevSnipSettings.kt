package pro.devsnip.api.ide.engine

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.JsonPrimitive
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.options.Configurable
import com.intellij.openapi.ui.ComboBox
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.util.ui.FormBuilder
import pro.devsnip.api.ide.web.ScannerSettings
import javax.swing.JComponent
import javax.swing.JSpinner
import javax.swing.SpinnerNumberModel

/**
 * The extension's settings (package.json `contributes.configuration`, the
 * `devsnip.*` keys), as the extension host reads them with
 * `workspace.getConfiguration`. Values the user never changed come from the
 * package.json defaults; the scanners' settings live in [ScannerSettings].
 *
 * `telemetry` is the IDE's counterpart of VS Code's own telemetry switch
 * (`env.isTelemetryEnabled`): DevSnip Pro's anonymous usage analytics only
 * run when it is on, and it is off until the user turns it on.
 */
@Service(Service.Level.APP)
@State(name = "DevSnipProSettings", storages = [Storage("devsnip-pro-settings.xml")])
class DevSnipSettings : PersistentStateComponent<DevSnipSettings.Values> {
    class Values {
        var valuesJson: String = "{}"
        var telemetry: Boolean = false
    }

    private var values = Values()

    override fun getState(): Values = values

    override fun loadState(state: Values) {
        values = state
    }

    private fun stored(): JsonObject = runCatching { JsonParser.parseString(values.valuesJson).asJsonObject }.getOrElse { JsonObject() }

    /** What the user set (not the defaults), checked against the schema. */
    fun userValues(): JsonObject {
        val out = JsonObject()
        for ((key, value) in stored().entrySet()) if (SCHEMA[key]?.accepts(value) == true) out.add(key, value)
        return out
    }

    fun value(key: String): JsonElement? = userValues()[key] ?: SCHEMA[key]?.default

    fun set(key: String, value: JsonElement?) {
        if (key.startsWith("devsnip.osvScanner.") || key.startsWith("devsnip.sca.")) {
            setScanner(key, value)
        } else {
            val all = stored()
            if (value == null || value.isJsonNull || value == SCHEMA[key]?.default) all.remove(key) else if (SCHEMA[key]?.accepts(value) == true) all.add(key, value)
            values.valuesJson = all.toString()
        }
        broadcast()
    }

    var telemetry: Boolean
        get() = values.telemetry
        set(value) {
            values.telemetry = value
            DevSnipEngine.all().forEach { it.post(JsonObject().apply { addProperty("type", "__telemetry"); addProperty("enabled", value) }) }
        }

    /** Every setting as the host sees it: the user's values plus the scanners'. */
    fun forHost(): JsonObject = userValues().apply {
        val scanners = ScannerSettings.getInstance().asVsCodeSettings()
        for ((section, group) in scanners.entrySet()) for ((name, value) in group.asJsonObject.entrySet()) add("$section.$name", value)
    }

    private fun setScanner(key: String, value: JsonElement?) {
        val v = ScannerSettings.getInstance().state
        val text = value?.takeIf { it.isJsonPrimitive }?.asJsonPrimitive
        when (key) {
            "devsnip.osvScanner.buildToolResolution" -> v.buildToolResolution = text?.asString?.takeIf { it in ScannerSettings.BUILD_TOOL_MODES } ?: "ask"
            "devsnip.osvScanner.downloadPoms" -> v.downloadPoms = text?.asBoolean ?: true
            "devsnip.osvScanner.buildToolTimeoutSeconds" -> v.buildToolTimeoutSeconds = (text?.asInt ?: 300).coerceIn(30, 3600)
            "devsnip.sca.healthChecks" -> v.healthChecks = text?.asString?.takeIf { it in ScannerSettings.HEALTH_SCOPES } ?: "direct"
            "devsnip.sca.knownExploited" -> v.knownExploited = text?.asBoolean ?: true
            "devsnip.sca.nvdEnrichment" -> v.nvdEnrichment = text?.asBoolean ?: true
            "devsnip.sca.bomUpgradeSearch" -> v.bomUpgradeSearch = text?.asBoolean ?: true
        }
        pro.devsnip.api.ide.web.ScannerHost.broadcastSettings()
    }

    /** Tells every extension host (configuration changed events fire there). */
    fun broadcast() {
        val settings = forHost()
        DevSnipEngine.all().forEach { it.post(JsonObject().apply { addProperty("type", "__settings"); add("settings", settings) }) }
    }

    /** One setting's schema from package.json. */
    class Setting(val key: String, val type: String, val default: JsonElement?, val options: List<String>, val optionDescriptions: List<String>, val minimum: Double?, val maximum: Double?, val description: String) {
        fun accepts(value: JsonElement): Boolean {
            if (!value.isJsonPrimitive) return false
            val p = value.asJsonPrimitive
            return when (type) {
                "boolean" -> p.isBoolean
                "number", "integer" -> p.isNumber && (minimum == null || p.asDouble >= minimum) && (maximum == null || p.asDouble <= maximum)
                "string" -> p.isString && (options.isEmpty() || p.asString in options)
                else -> false
            }
        }
    }

    companion object {
        /** package.json's settings, in its order. */
        val SCHEMA: Map<String, Setting> by lazy {
            val out = LinkedHashMap<String, Setting>()
            val text = DevSnipSettings::class.java.getResourceAsStream("/webview/contributions.json")?.use { String(it.readBytes(), Charsets.UTF_8) } ?: return@lazy out
            val configuration = JsonParser.parseString(text).asJsonObject["configuration"]
            val groups = if (configuration?.isJsonArray == true) configuration.asJsonArray.map { it.asJsonObject } else listOfNotNull(configuration?.asJsonObject)
            for (group in groups) for ((key, schema) in group.getAsJsonObject("properties").entrySet()) {
                val s = schema.asJsonObject
                out[key] = Setting(
                    key = key,
                    type = s["type"]?.asString ?: "string",
                    default = s["default"],
                    options = s.getAsJsonArray("enum")?.map { it.asString }.orEmpty(),
                    optionDescriptions = s.getAsJsonArray("enumDescriptions")?.map { it.asString }.orEmpty(),
                    minimum = s["minimum"]?.asDouble,
                    maximum = s["maximum"]?.asDouble,
                    description = plain((s["markdownDescription"] ?: s["description"])?.asString.orEmpty()),
                )
            }
            out
        }

        /** Markdown links and emphasis as plain text, VS Code's own setting names as the IDE's. */
        private fun plain(markdown: String) = markdown
            .replace(Regex("\\[([^\\]]+)]\\([^)]+\\)"), "$1")
            .replace(Regex("[*`]"), "")
            .replace("Also respects VS Code's telemetry setting (telemetry.telemetryLevel).", "Also needs \"Share anonymous usage statistics\" below.")

        fun getInstance(): DevSnipSettings = ApplicationManager.getApplication().getService(DevSnipSettings::class.java)
    }
}

/** Settings | Tools | DevSnip Pro: the extension's settings, built from package.json (the scanners have their own page). */
class DevSnipSettingsConfigurable : Configurable {
    private val editors = LinkedHashMap<String, () -> JsonElement>()
    private val resets = mutableListOf<() -> Unit>()
    private val telemetry = JBCheckBox("Share anonymous usage statistics with DevSnip Pro (off by default)")

    override fun getDisplayName() = "DevSnip Pro"

    override fun createComponent(): JComponent {
        val form = FormBuilder.createFormBuilder()
        val settings = DevSnipSettings.getInstance()
        for (setting in DevSnipSettings.SCHEMA.values) {
            if (setting.key.startsWith("devsnip.osvScanner.") || setting.key.startsWith("devsnip.sca.")) continue
            val label = setting.key.removePrefix("devsnip.")
            when {
                setting.type == "boolean" -> {
                    val box = JBCheckBox(label)
                    editors[setting.key] = { JsonPrimitive(box.isSelected) }
                    resets += { box.isSelected = settings.value(setting.key)?.asBoolean ?: false }
                    form.addComponent(box)
                }
                setting.options.isNotEmpty() -> {
                    val combo = ComboBox(setting.options.toTypedArray())
                    editors[setting.key] = { JsonPrimitive(combo.item) }
                    resets += { combo.item = settings.value(setting.key)?.asString ?: setting.options.first() }
                    form.addLabeledComponent("$label:", combo)
                }
                setting.type == "number" || setting.type == "integer" -> {
                    val min = (setting.minimum ?: 0.0).toInt()
                    val max = (setting.maximum ?: Int.MAX_VALUE.toDouble()).toInt()
                    val spinner = JSpinner(SpinnerNumberModel(setting.default?.asInt ?: min, min, max, 1))
                    editors[setting.key] = { JsonPrimitive(spinner.value as Int) }
                    resets += { spinner.value = (settings.value(setting.key)?.asInt ?: min).coerceIn(min, max) }
                    form.addLabeledComponent("$label:", spinner)
                }
                else -> continue
            }
            form.addComponent(JBLabel("<html><small>${escape(setting.description)}</small></html>").apply { setCopyable(true) })
        }
        form.addSeparator()
        form.addComponent(telemetry)
        form.addComponent(JBLabel("<html><small>Turns on DevSnip Pro's anonymous usage analytics (with analytics.enabled above). No code, file contents, paths, input or output are ever sent.</small></html>"))
        form.addComponent(JBLabel("<html><small>Scanner settings: Settings | Tools | DevSnip Pro | DevSnip Pro Scanners.</small></html>"))
        reset()
        return form.addComponentFillVertically(javax.swing.JPanel(), 0).panel
    }

    override fun isModified(): Boolean {
        val settings = DevSnipSettings.getInstance()
        return telemetry.isSelected != settings.telemetry || editors.any { (key, read) -> read() != settings.value(key) }
    }

    override fun apply() {
        val settings = DevSnipSettings.getInstance()
        for ((key, read) in editors) if (read() != settings.value(key)) settings.set(key, read())
        if (telemetry.isSelected != settings.telemetry) settings.telemetry = telemetry.isSelected
    }

    override fun reset() {
        resets.forEach { it() }
        telemetry.isSelected = DevSnipSettings.getInstance().telemetry
    }

    private fun escape(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
}
