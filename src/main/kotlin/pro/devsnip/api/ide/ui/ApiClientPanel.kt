package pro.devsnip.api.ide.ui

import com.intellij.icons.AllIcons
import com.intellij.openapi.Disposable
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.util.Disposer
import com.intellij.ui.ColoredListCellRenderer
import com.intellij.ui.JBColor
import com.intellij.ui.OnePixelSplitter
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBPasswordField
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.components.JBTabbedPane
import com.intellij.ui.components.JBTextArea
import com.intellij.ui.components.JBTextField
import com.intellij.ui.JBIntSpinner
import com.intellij.ui.table.JBTable
import com.intellij.util.concurrency.AppExecutorUtil
import com.intellij.util.ui.AsyncProcessIcon
import com.intellij.util.ui.FormBuilder
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import pro.devsnip.api.core.ApiKeyLocation
import pro.devsnip.api.core.ApiRequest
import pro.devsnip.api.core.ApiResponse
import pro.devsnip.api.core.AuthConfig
import pro.devsnip.api.core.BodyType
import pro.devsnip.api.core.CancellationToken
import pro.devsnip.api.core.Curl
import pro.devsnip.api.core.ErrorAction
import pro.devsnip.api.core.HistoryItem
import pro.devsnip.api.core.HistorySnapshots
import pro.devsnip.api.core.Json
import pro.devsnip.api.core.OAuthGrant
import pro.devsnip.api.core.OAuthTokenRequest
import pro.devsnip.api.core.OAuthTokenResult
import pro.devsnip.api.core.ProxyConfig
import pro.devsnip.api.core.Redaction
import pro.devsnip.api.core.RequestBuildException
import pro.devsnip.api.core.RequestCancelledException
import pro.devsnip.api.core.RequestErrors
import pro.devsnip.api.core.RequestFailedException
import pro.devsnip.api.core.RequestType
import pro.devsnip.api.core.formatBytes
import pro.devsnip.api.ide.ApiClientService
import java.awt.BorderLayout
import java.awt.CardLayout
import java.awt.Color
import java.awt.FlowLayout
import java.awt.Font
import java.awt.datatransfer.StringSelection
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.text.SimpleDateFormat
import java.util.Date
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import javax.swing.Box
import javax.swing.BoxLayout
import javax.swing.DefaultComboBoxModel
import javax.swing.DefaultListModel
import javax.swing.JButton
import javax.swing.JComponent
import javax.swing.JList
import javax.swing.JPanel
import javax.swing.ListSelectionModel
import javax.swing.table.DefaultTableModel

/**
 * The REST Client tab of the DevSnip Pro tool window: build a request, send it on a background task,
 * and inspect the status, timing, headers and body, or a diagnosis of why it
 * failed. Every control here calls into [ApiClientService].
 */
class ApiClientPanel(private val project: Project) : JPanel(BorderLayout()), Disposable {
    private val service = ApiClientService.getInstance()
    private var running: CancellationToken? = null
    @Volatile
    private var disposed = false

    // ---------------------------------------------------------------- request line
    internal val methodBox = ComboBox(arrayOf("GET", "POST", "PUT", "PATCH", "DELETE", "HEAD", "OPTIONS")).apply { isEditable = true }
    internal val urlField = JBTextField().apply { emptyText.text = "https://api.example.com/users  or  {{baseUrl}}/users" }
    internal val sendButton = JButton("Send", AllIcons.Actions.Execute)
    private val spinner = AsyncProcessIcon("DevSnipApiSend").apply { isVisible = false }
    private val envBox = ComboBox<String>()
    private var updatingEnvBox = false

    // ------------------------------------------------------------------- request tabs
    internal val requestTabs = JBTabbedPane()
    private val params = KeyValueTable()
    internal val headers = KeyValueTable(keyTitle = "Header")

    private val requestTypeBox = ComboBox(arrayOf("REST", "GraphQL"))
    private val bodyTypeBox = ComboBox(BodyType.entries.toTypedArray())
    private val bodyArea = codeArea()
    private val graphqlQuery = codeArea()
    private val graphqlVariables = codeArea(rows = 4)
    private val graphqlOperation = JBTextField()
    private val bodyCards = JPanel(CardLayout())

    internal val authTypeBox = ComboBox(arrayOf(AUTH_NONE, AUTH_BEARER, AUTH_BASIC, AUTH_API_KEY, AUTH_OAUTH))
    private val authCards = JPanel(CardLayout())
    internal val bearerToken = JBPasswordField()
    private val basicUser = JBTextField()
    private val basicPassword = JBPasswordField()
    private val apiKeyName = JBTextField().apply { emptyText.text = "X-API-Key" }
    private val apiKeyValue = JBPasswordField()
    private val apiKeyLocation = ComboBox(arrayOf("Header", "Query parameter"))
    private val oauthGrant = ComboBox(OAuthGrant.entries.toTypedArray())
    private val oauthTokenUrl = JBTextField().apply { emptyText.text = "https://auth.example.com/oauth/token" }
    private val oauthClientId = JBTextField()
    private val oauthClientSecret = JBPasswordField()
    private val oauthScope = JBTextField()
    private val oauthUser = JBTextField()
    private val oauthPassword = JBPasswordField()
    private val oauthRefresh = JBPasswordField()
    private val oauthBasic = JBCheckBox("Send client credentials as HTTP Basic")
    private val oauthFetch = JButton("Fetch Token")
    private val oauthUseStored = JButton("Use Stored Token")
    private val oauthResult = codeArea(rows = 5).apply { isEditable = false }

    private val timeoutSpinner = JBIntSpinner(service.defaultTimeoutMs.toInt().coerceIn(1000, 300_000), 1000, 300_000, 1000)
    private val retriesSpinner = JBIntSpinner(0, 0, 5)
    private val retryDelaySpinner = JBIntSpinner(500, 0, 60_000, 100)
    private val retryCodesField = JBTextField(ApiRequest.DEFAULT_RETRY_STATUS_CODES.joinToString(", "))
    private val followRedirects = JBCheckBox("Follow redirects", true)
    private val maxRedirectsSpinner = JBIntSpinner(5, 0, 50)
    private val verifySsl = JBCheckBox("Verify SSL certificates", true)
    private val proxyHost = JBTextField().apply { emptyText.text = "Blank: use the IDE proxy settings" }
    private val proxyPort = JBIntSpinner(8080, 1, 65535)

    internal val historyModel = DefaultListModel<HistoryItem>()
    internal val historyList = JBList(historyModel)
    private val cookiesLabel = JBLabel()

    // ------------------------------------------------------------------- response
    internal val statusLabel = JBLabel(" ").apply { font = font.deriveFont(Font.BOLD) }
    internal val metricsLabel = JBLabel(" ")
    private val responseTabs = JBTabbedPane()
    internal val responseBody = codeArea().apply { isEditable = false; text = "Send a request to see the response." }
    internal val responseHeadersModel = object : DefaultTableModel(arrayOf("Header", "Value"), 0) {
        override fun isCellEditable(row: Int, column: Int) = false
    }
    private val diagnostics = codeArea().apply { isEditable = false }
    internal val fixButton = JButton().apply { isVisible = false }
    private var fixAction: (() -> Unit)? = null
    internal val hintLabel = JBLabel(" ").apply { foreground = UIUtil.getContextHelpForeground() }

    init {
        add(buildTop(), BorderLayout.NORTH)
        add(OnePixelSplitter(true, 0.5f).apply {
            firstComponent = buildRequestTabs()
            secondComponent = buildResponse()
        }, BorderLayout.CENTER)
        add(JPanel(BorderLayout()).apply { border = JBUI.Borders.empty(2, 8); add(hintLabel) }, BorderLayout.SOUTH)
        refreshEnvironments()
        refreshHistory()
    }

    // ============================================================== layout

    private fun buildTop(): JComponent {
        sendButton.addActionListener { if (running != null) cancel() else send() }
        urlField.addActionListener { if (running == null) send() }
        methodBox.preferredSize = JBUI.size(100, methodBox.preferredSize.height)

        val line = JPanel(BorderLayout(JBUI.scale(4), 0)).apply {
            add(methodBox, BorderLayout.WEST)
            add(urlField, BorderLayout.CENTER)
            add(JPanel(FlowLayout(FlowLayout.RIGHT, JBUI.scale(4), 0)).apply { add(spinner); add(sendButton) }, BorderLayout.EAST)
        }

        envBox.addActionListener {
            if (!updatingEnvBox) service.activeEnvironmentName = envBox.selectedIndex.takeIf { it > 0 }?.let { envBox.selectedItem as String }
        }
        val envButton = JButton("Environments…").apply { addActionListener { editEnvironments() } }
        val importButton = JButton("Import cURL…").apply { addActionListener { importCurl() } }
        val copyButton = JButton("Copy as cURL").apply { addActionListener { copyAsCurl() } }
        val tools = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(4), 0)).apply {
            add(JBLabel("Environment:"))
            add(envBox)
            add(envButton)
            add(Box.createHorizontalStrut(JBUI.scale(8)))
            add(importButton)
            add(copyButton)
        }
        return JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            border = JBUI.Borders.empty(6, 8, 4, 8)
            add(line)
            add(Box.createVerticalStrut(JBUI.scale(4)))
            add(tools)
        }
    }

    private fun buildRequestTabs(): JComponent {
        requestTabs.addTab("Params", params.component)
        requestTabs.addTab("Headers", headers.component)
        requestTabs.addTab("Body", buildBodyTab())
        requestTabs.addTab("Auth", buildAuthTab())
        requestTabs.addTab("Settings", buildSettingsTab())
        requestTabs.addTab("History", buildHistoryTab())
        requestTabs.addChangeListener { if (requestTabs.selectedIndex == TAB_HISTORY) refreshHistory() }
        return requestTabs
    }

    private fun buildBodyTab(): JComponent {
        val formatButton = JButton("Format JSON").apply {
            addActionListener {
                val target = if (requestTypeBox.selectedIndex == 1) graphqlVariables else bodyArea
                val parsed = Json.parseOrNull(target.text)
                if (parsed == null) hint("The body is not valid JSON, so it was not formatted.") else target.text = Json.prettyPrint(parsed)
            }
        }
        val header = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(4), 0)).apply {
            add(JBLabel("Type:")); add(requestTypeBox)
            add(JBLabel("Body:")); add(bodyTypeBox)
            add(formatButton)
        }
        bodyCards.add(JBScrollPane(bodyArea), "REST")
        bodyCards.add(
            FormBuilder.createFormBuilder()
                .addLabeledComponentFillVertically("Query:", JBScrollPane(graphqlQuery))
                .addLabeledComponent("Variables (JSON):", JBScrollPane(graphqlVariables), true)
                .addLabeledComponent("Operation name:", graphqlOperation)
                .panel,
            "GraphQL",
        )
        requestTypeBox.addActionListener {
            val graphql = requestTypeBox.selectedIndex == 1
            (bodyCards.layout as CardLayout).show(bodyCards, if (graphql) "GraphQL" else "REST")
            bodyTypeBox.isEnabled = !graphql
            if (graphql && methodBox.item == "GET") methodBox.item = "POST"
        }
        return JPanel(BorderLayout(0, JBUI.scale(4))).apply {
            border = JBUI.Borders.empty(4)
            add(header, BorderLayout.NORTH)
            add(bodyCards, BorderLayout.CENTER)
        }
    }

    private fun buildAuthTab(): JComponent {
        authCards.add(panelWith(JBLabel("No authentication is added to the request.")), AUTH_NONE)
        authCards.add(form { addLabeledComponent("Token:", bearerToken).addComponent(help("Sent as Authorization: Bearer &lt;token&gt;. Use {{variable}} to keep the token in an environment (stored in the password safe).")) }, AUTH_BEARER)
        authCards.add(form { addLabeledComponent("Username:", basicUser).addLabeledComponent("Password:", basicPassword) }, AUTH_BASIC)
        authCards.add(form { addLabeledComponent("Key name:", apiKeyName).addLabeledComponent("Value:", apiKeyValue).addLabeledComponent("Add to:", apiKeyLocation) }, AUTH_API_KEY)

        oauthFetch.addActionListener { fetchOAuthToken() }
        oauthUseStored.addActionListener { useStoredOAuthToken() }
        oauthGrant.addActionListener { updateOAuthFields() }
        authCards.add(
            JBScrollPane(form {
                addLabeledComponent("Grant type:", oauthGrant)
                    .addLabeledComponent("Token URL:", oauthTokenUrl)
                    .addLabeledComponent("Client ID:", oauthClientId)
                    .addLabeledComponent("Client secret:", oauthClientSecret)
                    .addLabeledComponent("Scope:", oauthScope)
                    .addLabeledComponent("Username:", oauthUser)
                    .addLabeledComponent("Password:", oauthPassword)
                    .addLabeledComponent("Refresh token:", oauthRefresh)
                    .addComponent(oauthBasic)
                    .addComponent(JPanel(FlowLayout(FlowLayout.LEFT, 0, 0)).apply { add(oauthFetch); add(Box.createHorizontalStrut(JBUI.scale(6))); add(oauthUseStored) })
                    .addComponent(help("The token is stored in the IDE password safe; only a masked preview is shown. Sending with OAuth 2.0 selected uses the stored token as a Bearer token."))
                    .addLabeledComponent("Token response:", JBScrollPane(oauthResult), true)
            }),
            AUTH_OAUTH,
        )
        updateOAuthFields()
        authTypeBox.addActionListener { (authCards.layout as CardLayout).show(authCards, authTypeBox.item) }
        return JPanel(BorderLayout(0, JBUI.scale(4))).apply {
            border = JBUI.Borders.empty(4)
            add(JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(4), 0)).apply { add(JBLabel("Type:")); add(authTypeBox) }, BorderLayout.NORTH)
            add(authCards, BorderLayout.CENTER)
        }
    }

    private fun updateOAuthFields() {
        val grant = oauthGrant.item
        oauthUser.isEnabled = grant == OAuthGrant.PASSWORD
        oauthPassword.isEnabled = grant == OAuthGrant.PASSWORD
        oauthRefresh.isEnabled = grant == OAuthGrant.REFRESH_TOKEN
    }

    private fun buildSettingsTab(): JComponent {
        timeoutSpinner.addChangeListener { service.defaultTimeoutMs = timeoutSpinner.number.toLong() }
        followRedirects.addActionListener { maxRedirectsSpinner.isEnabled = followRedirects.isSelected }
        val proxyRow = JPanel(BorderLayout(JBUI.scale(4), 0)).apply {
            add(proxyHost, BorderLayout.CENTER)
            add(JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(4), 0)).apply { add(JBLabel("Port:")); add(proxyPort) }, BorderLayout.EAST)
        }
        return JBScrollPane(form {
            addLabeledComponent("Timeout (ms):", timeoutSpinner)
                .addComponent(help("Saved as the default for every request."))
                .addLabeledComponent("Retries:", retriesSpinner)
                .addLabeledComponent("Retry delay (ms):", retryDelaySpinner)
                .addLabeledComponent("Retry on status:", retryCodesField)
                .addComponent(help("Retries use exponential backoff and also apply to network errors."))
                .addComponent(followRedirects)
                .addLabeledComponent("Max redirects:", maxRedirectsSpinner)
                .addComponent(verifySsl)
                .addComponent(help("Turn off only for local or test servers with self-signed certificates."))
                .addLabeledComponent("HTTP proxy:", proxyRow)
        })
    }

    private fun buildHistoryTab(): JComponent {
        historyList.selectionMode = ListSelectionModel.SINGLE_SELECTION
        historyList.emptyText.text = "Requests you send appear here."
        historyList.cellRenderer = object : ColoredListCellRenderer<HistoryItem>() {
            override fun customizeCellRenderer(list: JList<out HistoryItem>, value: HistoryItem, index: Int, selected: Boolean, hasFocus: Boolean) {
                append(value.method.padEnd(7), SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES)
                val status = value.status ?: 0
                append(if (status == 0) "ERR " else "$status ", SimpleTextAttributes(SimpleTextAttributes.STYLE_BOLD, statusColor(status)))
                append(value.url)
                val time = value.responseTimeMs?.let { "  $it ms" }.orEmpty()
                append("$time  ${DATE_FORMAT.format(Date(value.timestamp))}", SimpleTextAttributes.GRAYED_ATTRIBUTES)
            }
        }
        historyList.addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                if (e.clickCount == 2) openHistoryItem()
            }
        })
        val open = JButton("Open").apply { addActionListener { openHistoryItem() } }
        val clear = JButton("Clear History").apply {
            addActionListener {
                if (Messages.showYesNoDialog(project, "Remove all ${historyModel.size()} history entries?", "Clear History", null) == Messages.YES) {
                    service.clearHistory()
                    refreshHistory()
                }
            }
        }
        val exportJson = JButton("Export JSON…").apply { addActionListener { exportHistory(csv = false) } }
        val exportCsv = JButton("Export CSV…").apply { addActionListener { exportHistory(csv = true) } }
        val clearCookies = JButton("Clear Cookies").apply {
            addActionListener {
                service.engine.cookieJar.clear()
                refreshCookies()
                hint("Cookies cleared.")
            }
        }
        val buttons = JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(4), 0)).apply {
            add(open); add(clear); add(exportJson); add(exportCsv); add(clearCookies)
        }
        return JPanel(BorderLayout(0, JBUI.scale(4))).apply {
            border = JBUI.Borders.empty(4)
            add(buttons, BorderLayout.NORTH)
            add(JBScrollPane(historyList), BorderLayout.CENTER)
            add(cookiesLabel, BorderLayout.SOUTH)
        }
    }

    private fun buildResponse(): JComponent {
        fixButton.addActionListener { fixAction?.invoke() }
        val copyBody = JButton("Copy Body", AllIcons.Actions.Copy).apply {
            addActionListener {
                CopyPasteManager.getInstance().setContents(StringSelection(responseBody.text))
                hint("Response body copied.")
            }
        }
        val statusRow = JPanel(BorderLayout(JBUI.scale(8), 0)).apply {
            border = JBUI.Borders.empty(4, 8)
            add(JPanel(FlowLayout(FlowLayout.LEFT, JBUI.scale(10), 0)).apply { add(statusLabel); add(metricsLabel) }, BorderLayout.CENTER)
            add(JPanel(FlowLayout(FlowLayout.RIGHT, JBUI.scale(4), 0)).apply { add(fixButton); add(copyBody) }, BorderLayout.EAST)
        }
        val headersTable = JBTable(responseHeadersModel).apply { setShowGrid(false); emptyText.text = "No response headers yet." }
        responseTabs.addTab("Body", JBScrollPane(responseBody))
        responseTabs.addTab("Headers", JBScrollPane(headersTable))
        responseTabs.addTab("Diagnostics", JBScrollPane(diagnostics))
        return JPanel(BorderLayout()).apply {
            add(statusRow, BorderLayout.NORTH)
            add(responseTabs, BorderLayout.CENTER)
        }
    }

    // ============================================================== form <-> request

    /** Reads the form. Secrets stay in memory only; history keeps them out (see [HistorySnapshots]). */
    private fun readRequest(): ApiRequest {
        params.stopEditing()
        headers.stopEditing()
        val graphql = requestTypeBox.selectedIndex == 1
        val auth = when (authTypeBox.item) {
            AUTH_BEARER -> AuthConfig.Bearer(String(bearerToken.password))
            AUTH_BASIC -> AuthConfig.Basic(basicUser.text, String(basicPassword.password))
            AUTH_API_KEY -> AuthConfig.ApiKey(apiKeyName.text.trim(), String(apiKeyValue.password), if (apiKeyLocation.selectedIndex == 1) ApiKeyLocation.QUERY else ApiKeyLocation.HEADER)
            else -> AuthConfig.None
        }
        val retryCodes = retryCodesField.text.split(',', ' ').mapNotNull { it.trim().toIntOrNull() }.filter { it in 100..599 }
        val proxy = proxyHost.text.trim().takeIf { it.isNotEmpty() }?.let { ProxyConfig(it, proxyPort.number) }
        return ApiRequest(
            method = (methodBox.item ?: "GET").trim().uppercase(),
            url = urlField.text.trim(),
            data = bodyArea.text.takeIf { it.isNotEmpty() && !graphql },
            params = params.values,
            headers = headers.values,
            auth = auth,
            requestType = if (graphql) RequestType.GRAPHQL else RequestType.REST,
            graphqlQuery = graphqlQuery.text.takeIf { graphql },
            graphqlVariables = graphqlVariables.text.takeIf { graphql && it.isNotBlank() },
            graphqlOperationName = graphqlOperation.text.trim().takeIf { graphql && it.isNotEmpty() },
            bodyType = bodyTypeBox.item ?: BodyType.JSON,
            retries = retriesSpinner.number,
            retryDelayMs = retryDelaySpinner.number.toLong(),
            retryStatusCodes = retryCodes,
            followRedirects = followRedirects.isSelected,
            maxRedirects = maxRedirectsSpinner.number,
            verifySsl = verifySsl.isSelected,
            proxy = proxy,
        )
    }

    private fun fillForm(request: ApiRequest) {
        methodBox.item = request.method
        urlField.text = request.url
        params.values = request.params
        headers.values = request.headers
        val graphql = request.requestType == RequestType.GRAPHQL
        requestTypeBox.selectedIndex = if (graphql) 1 else 0
        bodyArea.text = if (graphql) "" else request.data.orEmpty()
        graphqlQuery.text = request.graphqlQuery.orEmpty()
        graphqlVariables.text = request.graphqlVariables.orEmpty()
        graphqlOperation.text = request.graphqlOperationName.orEmpty()
        bodyTypeBox.item = request.bodyType
        when (val auth = request.auth) {
            is AuthConfig.Bearer -> { authTypeBox.item = AUTH_BEARER; bearerToken.text = auth.token }
            is AuthConfig.Basic -> { authTypeBox.item = AUTH_BASIC; basicUser.text = auth.username; basicPassword.text = auth.password }
            is AuthConfig.ApiKey -> {
                authTypeBox.item = AUTH_API_KEY
                apiKeyName.text = auth.name
                apiKeyValue.text = auth.value
                apiKeyLocation.selectedIndex = if (auth.location == ApiKeyLocation.QUERY) 1 else 0
            }
            AuthConfig.None -> authTypeBox.item = AUTH_NONE
        }
        verifySsl.isSelected = request.verifySsl
        followRedirects.isSelected = request.followRedirects
        maxRedirectsSpinner.isEnabled = request.followRedirects
        request.proxy?.let { proxyHost.text = it.host; proxyPort.number = it.port }
        // A history entry's method may be one the box does not list.
        if (methodBox.selectedItem != request.method) methodBox.selectedItem = request.method
    }

    // ============================================================== actions

    internal fun send() {
        val formRequest = try {
            readRequest()
        } catch (e: Exception) {
            showFailure(RequestErrors.describe(e.message, null), e.message, null, null)
            return
        }
        val useStoredOAuth = authTypeBox.item == AUTH_OAUTH
        val token = CancellationToken()
        setRunning(token)
        showPending(formRequest)

        val title = "DevSnip: ${formRequest.method} ${Redaction.redactUrl(formRequest.url).take(80)}"
        ProgressManager.getInstance().run(object : Task.Backgroundable(project, title, true) {
            private var response: ApiResponse? = null
            private var error: Throwable? = null

            override fun run(indicator: ProgressIndicator) {
                indicator.isIndeterminate = true
                indicator.text = title
                try {
                    val request = if (useStoredOAuth) {
                        val stored = service.storedOAuthToken() ?: throw RequestBuildException("No OAuth token yet: fill in the OAuth 2.0 fields and click Fetch Token.")
                        formRequest.copy(auth = AuthConfig.Bearer(stored))
                    } else formRequest
                    response = awaitCancellable(indicator, token) { service.send(request, token) }
                } catch (e: ProcessCanceledException) {
                    token.cancel()
                    error = RequestCancelledException()
                } catch (e: Throwable) {
                    error = e
                }
            }

            override fun onFinished() {
                if (running === token) setRunning(null)
                if (disposed) return
                response?.let { showResponse(it) } ?: showError(error)
                refreshHistory()
            }
        })
    }

    private fun cancel() {
        running?.cancel()
        hint("Cancelling…")
    }

    private fun setRunning(token: CancellationToken?) {
        running = token
        val busy = token != null
        spinner.isVisible = busy
        if (busy) spinner.resume() else spinner.suspend()
        sendButton.text = if (busy) "Cancel" else "Send"
        sendButton.icon = if (busy) AllIcons.Actions.Suspend else AllIcons.Actions.Execute
    }

    private fun showPending(request: ApiRequest) {
        statusLabel.text = "Sending…"
        statusLabel.foreground = UIUtil.getLabelForeground()
        metricsLabel.text = "${request.method} ${Redaction.redactUrl(request.url)}"
        setFix(null)
        hint(" ")
    }

    private fun showResponse(response: ApiResponse) {
        statusLabel.text = "${response.status} ${response.statusText}".trim()
        statusLabel.foreground = statusColor(response.status)
        val extras = buildList {
            if (response.attempts > 1) add("${response.attempts} attempts")
            if (response.redirects > 0) add("${response.redirects} redirect${if (response.redirects > 1) "s" else ""}")
            if (response.truncated) add("truncated for display")
        }
        metricsLabel.text = (listOf("${response.responseTimeMs} ms", formatBytes(response.sizeBytes)) + extras).joinToString("  ·  ")
        responseBody.text = response.body.display.ifEmpty { "(empty body)" }
        responseBody.caretPosition = 0
        responseHeadersModel.rowCount = 0
        response.headers.forEach { (name, value) -> responseHeadersModel.addRow(arrayOf(name, if (name.equals("set-cookie", true)) "${value.substringBefore('=')}=… (stored for this host)" else value)) }
        diagnostics.text = buildString {
            appendLine("Final URL: ${Redaction.redactUrl(response.finalUrl)}")
            appendLine("Status: ${response.status} ${response.statusText}")
            appendLine("Time: ${response.responseTimeMs} ms (including retries and redirects)")
            appendLine("Size: ${formatBytes(response.sizeBytes)}")
            appendLine("Attempts: ${response.attempts}")
            appendLine("Redirects followed: ${response.redirects}")
            response.warnings.forEach { appendLine("Warning: $it") }
        }
        setFix(null)
        responseTabs.selectedIndex = 0
        refreshCookies()
        hint(response.warnings.firstOrNull() ?: " ")
    }

    private fun showError(error: Throwable?) {
        when (error) {
            is RequestCancelledException -> {
                statusLabel.text = "Cancelled"
                statusLabel.foreground = UIUtil.getContextHelpForeground()
                metricsLabel.text = " "
                responseBody.text = "The request was cancelled."
                setFix(null)
            }
            is RequestFailedException -> showFailure(error.description, RequestErrors.messageFor(error.cause ?: error), error.code, "${error.responseTimeMs} ms  ·  ${error.attempts} attempt${if (error.attempts > 1) "s" else ""}")
            is RequestBuildException -> showFailure(RequestErrors.describe(error.message, null), error.message, null, null)
            null -> Unit
            else -> showFailure(RequestErrors.describe(error.message, RequestErrors.codeFor(error)), RequestErrors.messageFor(error), RequestErrors.codeFor(error), null)
        }
    }

    private fun showFailure(description: pro.devsnip.api.core.RequestErrorDescription, detail: String?, code: String?, metrics: String?) {
        statusLabel.text = description.title
        statusLabel.foreground = JBColor.RED
        metricsLabel.text = metrics ?: " "
        val details = Redaction.redactUrl(detail.orEmpty())
        responseBody.text = buildString {
            appendLine(description.title)
            appendLine()
            appendLine(description.hint)
            if (details.isNotBlank() && details != description.title) {
                appendLine()
                append("Details: ").append(details)
                code?.let { append(" ($it)") }
                appendLine()
            }
        }
        responseBody.caretPosition = 0
        responseHeadersModel.rowCount = 0
        diagnostics.text = "No response was received.\n${metrics.orEmpty()}"
        responseTabs.selectedIndex = 0
        setFix(description.action)
    }

    private fun setFix(action: ErrorAction?) {
        val target = when (action) {
            ErrorAction.SETTINGS -> "Settings" to { requestTabs.selectedIndex = TAB_SETTINGS }
            ErrorAction.AUTH -> "Auth" to { requestTabs.selectedIndex = TAB_AUTH }
            ErrorAction.BODY -> "Body" to { requestTabs.selectedIndex = TAB_BODY; bodyArea.requestFocusInWindow(); Unit }
            ErrorAction.HEADERS -> "Headers" to { requestTabs.selectedIndex = TAB_HEADERS }
            ErrorAction.ENV -> "Environments" to { editEnvironments() }
            ErrorAction.URL -> "URL" to { urlField.requestFocusInWindow(); urlField.selectAll() }
            null -> null
        }
        fixButton.isVisible = target != null
        fixButton.text = target?.let { "Fix in ${it.first}" }.orEmpty()
        fixAction = target?.second
    }

    internal fun copyAsCurl() {
        val request = readRequest()
        runInBackground("Building cURL command", { service.toCurl(request) }) { result ->
            result.onSuccess {
                CopyPasteManager.getInstance().setContents(StringSelection(it))
                hint("cURL command copied. It includes any credentials in the request.")
            }.onFailure { showError(it) }
        }
    }

    private fun importCurl() {
        val text = Messages.showMultilineInputDialog(project, "Paste a cURL command:", "Import cURL", "", null, null) ?: return
        try {
            fillForm(Curl.parse(text))
            hint("Imported the cURL command into the form.")
        } catch (e: RequestBuildException) {
            Messages.showErrorDialog(project, e.message, "Import cURL")
        }
    }

    private fun editEnvironments() {
        runInBackground("Loading environments", { service.loadAllEnvironments() }) { loaded ->
            val environments = loaded.getOrElse { showError(it); return@runInBackground }
            val dialog = EnvironmentsDialog(project, environments)
            if (!dialog.showAndGet()) return@runInBackground
            val updated = dialog.result
            runInBackground("Saving environments", { service.saveEnvironments(updated) }) { saved ->
                saved.onSuccess { refreshEnvironments(); hint("Environments saved.") }.onFailure { showError(it) }
            }
        }
    }

    private fun refreshEnvironments() {
        updatingEnvBox = true
        try {
            val names = service.environmentNames()
            envBox.model = DefaultComboBoxModel((listOf(NO_ENVIRONMENT) + names).toTypedArray())
            envBox.selectedItem = service.activeEnvironmentName ?: NO_ENVIRONMENT
        } finally {
            updatingEnvBox = false
        }
    }

    private fun fetchOAuthToken() {
        val request = OAuthTokenRequest(
            grant = oauthGrant.item ?: OAuthGrant.CLIENT_CREDENTIALS,
            tokenUrl = oauthTokenUrl.text,
            clientId = oauthClientId.text,
            clientSecret = String(oauthClientSecret.password).ifEmpty { null },
            scope = oauthScope.text.ifBlank { null },
            username = oauthUser.text.ifBlank { null },
            password = String(oauthPassword.password).ifEmpty { null },
            refreshToken = String(oauthRefresh.password).ifEmpty { null },
            useBasicAuth = oauthBasic.isSelected,
            timeoutMs = service.defaultTimeoutMs,
            verifySsl = verifySsl.isSelected,
        )
        oauthFetch.isEnabled = false
        oauthResult.text = "Requesting a token…"
        runInBackground("Fetching OAuth 2.0 token", { service.fetchOAuthToken(request, CancellationToken()) }) { result ->
            oauthFetch.isEnabled = true
            result.onSuccess { showOAuthResult(it) }.onFailure { oauthResult.text = "Failed: ${RequestErrors.messageFor(it)}" }
        }
    }

    private fun showOAuthResult(result: OAuthTokenResult) {
        if (!result.ok) {
            oauthResult.text = "Failed: ${result.error}"
            return
        }
        oauthResult.text = buildString {
            appendLine("Token stored in the password safe (${result.tokenType}${result.expiresIn?.let { ", expires in $it s" }.orEmpty()}).")
            appendLine(result.safePreview.orEmpty())
        }
        hint("OAuth token fetched. Send with OAuth 2.0 selected, or click Use Stored Token to switch to Bearer.")
    }

    private fun useStoredOAuthToken() {
        runInBackground("Reading stored OAuth token", { service.storedOAuthToken() }) { result ->
            val token = result.getOrNull()
            if (token.isNullOrEmpty()) {
                hint("No OAuth token is stored yet. Click Fetch Token first.")
            } else {
                bearerToken.text = token
                authTypeBox.item = AUTH_BEARER
                hint("Auth switched to Bearer with the stored OAuth token (${Redaction.mask(token)}).")
            }
        }
    }

    internal fun openHistoryItem() {
        val item = historyList.selectedValue ?: return
        val snapshot = item.request
        if (snapshot == null) {
            fillForm(ApiRequest(method = item.method, url = item.url))
        } else {
            fillForm(HistorySnapshots.toRequest(snapshot))
        }
        requestTabs.selectedIndex = TAB_PARAMS
        hint("Opened from history. Secrets typed into the request were not stored and need to be entered again.")
    }

    private fun exportHistory(csv: Boolean) {
        val extension = if (csv) "csv" else "json"
        val file = Files.chooseSaveTarget(project, "Export Request History", "devsnip-api-history.$extension", "History export (without request bodies or credentials)") ?: return
        val content = service.exportHistory(csv)
        runInBackground("Exporting history", { file.writeText(content) }) { result ->
            result.onSuccess { hint("Exported ${historyModel.size()} entries to ${file.name}.") }
                .onFailure { Messages.showErrorDialog(project, "Could not write the file: ${it.message}", "Export History") }
        }
    }

    private fun refreshHistory() {
        val selected = historyList.selectedValue?.id
        historyModel.clear()
        service.history().forEach(historyModel::addElement)
        selected?.let { id -> (0 until historyModel.size()).firstOrNull { historyModel[it].id == id }?.let(historyList::setSelectedIndex) }
        refreshCookies()
    }

    private fun refreshCookies() {
        val summary = service.engine.cookieJar.summary()
        cookiesLabel.text = if (summary.isEmpty()) "No cookies stored (they are kept for this IDE session only)."
        else "Cookies (this session): " + summary.entries.joinToString("; ") { (host, names) -> "$host: ${names.joinToString(", ")}" }
    }

    // ============================================================== helpers

    /** Runs blocking work off the EDT and delivers the result back on the EDT. */
    private fun <T> runInBackground(title: String, work: () -> T, done: (Result<T>) -> Unit) {
        ProgressManager.getInstance().run(object : Task.Backgroundable(project, title, false) {
            private var result: Result<T>? = null
            override fun run(indicator: ProgressIndicator) {
                result = runCatching(work)
            }
            override fun onFinished() {
                if (!disposed) result?.let(done)
            }
        })
    }

    private fun <T> awaitCancellable(indicator: ProgressIndicator, token: CancellationToken, work: () -> T): T {
        val future = AppExecutorUtil.getAppExecutorService().submit<T>(work)
        while (true) {
            try {
                return future.get(100, TimeUnit.MILLISECONDS)
            } catch (_: TimeoutException) {
                if (indicator.isCanceled) token.cancel()
            } catch (e: ExecutionException) {
                throw e.cause ?: e
            }
        }
    }

    private fun hint(text: String) {
        hintLabel.text = text
    }

    override fun dispose() {
        disposed = true
        running?.cancel()
        Disposer.dispose(spinner)
    }

    companion object {
        private const val AUTH_NONE = "No Auth"
        private const val AUTH_BEARER = "Bearer Token"
        private const val AUTH_BASIC = "Basic Auth"
        private const val AUTH_API_KEY = "API Key"
        private const val AUTH_OAUTH = "OAuth 2.0"
        private const val NO_ENVIRONMENT = "No environment"
        private const val TAB_PARAMS = 0
        private const val TAB_HEADERS = 1
        private const val TAB_BODY = 2
        private const val TAB_AUTH = 3
        private const val TAB_SETTINGS = 4
        private const val TAB_HISTORY = 5
        private val DATE_FORMAT = SimpleDateFormat("MMM d HH:mm:ss")

        private val GREEN = JBColor(Color(0x2E7D32), Color(0x6AAB73))
        private val BLUE = JBColor(Color(0x1565C0), Color(0x56A8F5))
        private val ORANGE = JBColor(Color(0xC77700), Color(0xE0A04A))

        fun statusColor(status: Int): Color = when (status) {
            in 200..299 -> GREEN
            in 300..399 -> BLUE
            in 400..499 -> ORANGE
            else -> JBColor.RED
        }

        private fun codeArea(rows: Int = 10) = JBTextArea(rows, 40).apply {
            font = Font(Font.MONOSPACED, Font.PLAIN, UIUtil.getLabelFont().size)
            tabSize = 2
        }

        private fun help(text: String) = JBLabel("<html>$text</html>").apply { foreground = UIUtil.getContextHelpForeground() }

        private fun panelWith(component: JComponent) = JPanel(BorderLayout()).apply { border = JBUI.Borders.empty(8); add(component, BorderLayout.NORTH) }

        private fun form(build: FormBuilder.() -> FormBuilder): JComponent =
            JPanel(BorderLayout()).apply {
                border = JBUI.Borders.empty(4, 8)
                add(FormBuilder.createFormBuilder().build().panel, BorderLayout.NORTH)
            }
    }
}
