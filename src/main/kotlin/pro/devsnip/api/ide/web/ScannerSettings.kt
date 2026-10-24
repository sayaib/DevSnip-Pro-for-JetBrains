package pro.devsnip.api.ide.web

import com.google.gson.JsonObject
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
import javax.swing.JComponent
import javax.swing.JSpinner
import javax.swing.SpinnerNumberModel

/**
 * The OSV and SCA scanners' settings, the IDE's counterpart of the
 * extension's `devsnip.osvScanner.*` and `devsnip.sca.*` settings (same
 * names, values and defaults). The scanners read them as VS Code settings.
 */
@Service(Service.Level.APP)
@State(name = "DevSnipProScanners", storages = [Storage("devsnip-pro-scanners.xml")])
class ScannerSettings : PersistentStateComponent<ScannerSettings.Values> {
    class Values {
        var buildToolResolution: String = "ask"
        var downloadPoms: Boolean = true
        var buildToolTimeoutSeconds: Int = 300
        var healthChecks: String = "direct"
        var knownExploited: Boolean = true
        var nvdEnrichment: Boolean = true
        var bomUpgradeSearch: Boolean = true
    }

    private var values = Values()

    override fun getState(): Values = values

    override fun loadState(state: Values) {
        values = state
    }

    /** As `vscode.workspace.getConfiguration(section)` sees them, keyed by section. */
    fun asVsCodeSettings(): JsonObject = JsonObject().apply {
        add("devsnip.osvScanner", JsonObject().apply {
            addProperty("buildToolResolution", values.buildToolResolution.takeIf { it in BUILD_TOOL_MODES } ?: "ask")
            addProperty("downloadPoms", values.downloadPoms)
            addProperty("buildToolTimeoutSeconds", values.buildToolTimeoutSeconds.coerceIn(30, 3600))
        })
        add("devsnip.sca", JsonObject().apply {
            addProperty("healthChecks", values.healthChecks.takeIf { it in HEALTH_SCOPES } ?: "direct")
            addProperty("knownExploited", values.knownExploited)
            addProperty("nvdEnrichment", values.nvdEnrichment)
            addProperty("bomUpgradeSearch", values.bomUpgradeSearch)
        })
    }

    companion object {
        val BUILD_TOOL_MODES = listOf("ask", "always", "never")
        val HEALTH_SCOPES = listOf("direct", "all", "off")

        fun getInstance(): ScannerSettings = ApplicationManager.getApplication().getService(ScannerSettings::class.java)
    }
}

/** Settings | Tools | DevSnip Pro Scanners (the scanners' "Settings" buttons open it). */
class ScannerSettingsConfigurable : Configurable {
    private val buildTools = ComboBox(arrayOf("ask", "always", "never"))
    private val downloadPoms = JBCheckBox("Download POM files from Maven Central for the built-in Maven resolver (only coordinates are requested)")
    private val timeout = JSpinner(SpinnerNumberModel(300, 30, 3600, 30))
    private val health = ComboBox(arrayOf("direct", "all", "off"))
    private val knownExploited = JBCheckBox("Mark vulnerabilities in CISA's Known Exploited Vulnerabilities catalog (downloaded and matched locally)")
    private val nvd = JBCheckBox("Look up CVSS scores in the NVD for CVEs without an OSV severity (only CVE ids are sent)")
    private val bomSearch = JBCheckBox("Search a controlling BOM's releases for the lowest one that manages fixed versions")

    override fun getDisplayName() = "DevSnip Pro Scanners"

    override fun createComponent(): JComponent = FormBuilder.createFormBuilder()
        .addComponent(JBLabel("<html><b>OSV Vulnerability Scanner</b> (also used by the SCA Scanner)</html>"))
        .addLabeledComponent("Run Maven / Gradle to resolve dependencies:", buildTools)
        .addComponent(JBLabel("<html><small>ask: once per project, after you allow it; always: in trusted projects; never: the built-in resolver only. " +
            "Build tools run the build's own code, so they never run in an untrusted project.</small></html>"))
        .addComponent(downloadPoms)
        .addLabeledComponent("Build tool time limit (seconds):", timeout)
        .addSeparator()
        .addComponent(JBLabel("<html><b>SCA Scanner</b></html>"))
        .addLabeledComponent("Registry health checks:", health)
        .addComponent(knownExploited)
        .addComponent(nvd)
        .addComponent(bomSearch)
        .addComponentFillVertically(javax.swing.JPanel(), 0)
        .panel

    private fun current() = ScannerSettings.getInstance().state

    override fun isModified(): Boolean {
        val v = current()
        return buildTools.item != v.buildToolResolution || downloadPoms.isSelected != v.downloadPoms || timeout.value != v.buildToolTimeoutSeconds ||
            health.item != v.healthChecks || knownExploited.isSelected != v.knownExploited || nvd.isSelected != v.nvdEnrichment || bomSearch.isSelected != v.bomUpgradeSearch
    }

    override fun apply() {
        val v = current()
        v.buildToolResolution = buildTools.item
        v.downloadPoms = downloadPoms.isSelected
        v.buildToolTimeoutSeconds = (timeout.value as Int).coerceIn(30, 3600)
        v.healthChecks = health.item
        v.knownExploited = knownExploited.isSelected
        v.nvdEnrichment = nvd.isSelected
        v.bomUpgradeSearch = bomSearch.isSelected
        ScannerHost.broadcastSettings()
    }

    override fun reset() {
        val v = current()
        buildTools.item = v.buildToolResolution
        downloadPoms.isSelected = v.downloadPoms
        timeout.value = v.buildToolTimeoutSeconds.coerceIn(30, 3600)
        health.item = v.healthChecks
        knownExploited.isSelected = v.knownExploited
        nvd.isSelected = v.nvdEnrichment
        bomSearch.isSelected = v.bomUpgradeSearch
    }
}
