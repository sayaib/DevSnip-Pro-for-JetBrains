package pro.devsnip.api.ide

import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.util.Disposer
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import pro.devsnip.api.core.KeyValue
import pro.devsnip.api.core.MockServer
import pro.devsnip.api.core.respond
import pro.devsnip.api.ide.ui.ApiClientPanel
import java.awt.datatransfer.DataFlavor
import java.net.ServerSocket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Drives the real tool-window panel inside a headless IDE: the buttons go
 * through the background task, the service and the network to a local server.
 */
class ApiClientPanelTest : BasePlatformTestCase() {
    private lateinit var server: MockServer
    private lateinit var panel: ApiClientPanel
    private val release = CountDownLatch(1)

    override fun setUp() {
        super.setUp()
        server = MockServer.http()
            .handle("/users") { ex, _ -> ex.respond(200, """{"users":[{"id":1,"name":"Ada"}]}""", headers = mapOf("X-Served-By" to "mock")) }
            .handle("/hang") { ex, _ -> release.await(10, TimeUnit.SECONDS); ex.respond(200, "{}") }
        ApiClientService.getInstance().clearHistory()
        panel = ApiClientPanel(project)
        Disposer.register(testRootDisposable, panel)
    }

    override fun tearDown() {
        try {
            release.countDown()
            server.close()
        } finally {
            super.tearDown()
        }
    }

    private fun waitFor(what: String, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 10_000
        while (!condition()) {
            if (System.currentTimeMillis() > deadline) fail("Timed out waiting for $what (status: ${panel.statusLabel.text}, hint: ${panel.hintLabel.text})")
            PlatformTestUtil.dispatchAllEventsInIdeEventQueue()
            Thread.sleep(20)
        }
    }

    fun `test Send shows status, formatted body, headers and records history`() {
        panel.urlField.text = server.url("/users")
        panel.headers.values = listOf(KeyValue("X-Test", "panel"))
        panel.sendButton.doClick()

        waitFor("the response") { panel.statusLabel.text == "200 OK" }
        assertTrue(panel.responseBody.text.contains("\"name\": \"Ada\""))
        assertTrue(panel.metricsLabel.text.contains(" ms"))
        assertTrue((0 until panel.responseHeadersModel.rowCount).any { panel.responseHeadersModel.getValueAt(it, 1) == "mock" })
        assertEquals("Send", panel.sendButton.text)
        assertEquals("panel", server.requests.single().header("X-Test"))
        assertEquals(1, panel.historyModel.size())
        assertEquals(200, panel.historyModel[0].status)
    }

    fun `test a failed request is diagnosed with a fix button`() {
        val port = ServerSocket(0).use { it.localPort }
        panel.urlField.text = "http://127.0.0.1:$port/"
        panel.sendButton.doClick()

        waitFor("the error") { panel.statusLabel.text == "Connection refused" }
        assertTrue(panel.responseBody.text.contains("Is the server running"))
        assertTrue(panel.fixButton.isVisible)
        assertEquals("Fix in URL", panel.fixButton.text)
    }

    fun `test an undefined variable points at environments`() {
        panel.urlField.text = "{{baseUrl}}/users"
        panel.sendButton.doClick()
        waitFor("the error") { panel.statusLabel.text == "Undefined variable" }
        assertEquals("Fix in Environments", panel.fixButton.text)
        assertTrue(server.requests.isEmpty())
    }

    fun `test Cancel stops a request in flight`() {
        panel.urlField.text = server.url("/hang")
        panel.sendButton.doClick()
        waitFor("the request to start") { panel.sendButton.text == "Cancel" && server.requests.isNotEmpty() }
        panel.sendButton.doClick()
        waitFor("cancellation") { panel.statusLabel.text == "Cancelled" }
        assertEquals("Send", panel.sendButton.text)
    }

    fun `test Copy as cURL puts the resolved command on the clipboard`() {
        panel.methodBox.item = "DELETE"
        panel.urlField.text = server.url("/users/1")
        panel.copyAsCurl()
        waitFor("the copy") { panel.hintLabel.text.startsWith("cURL command copied") }
        val copied = CopyPasteManager.getInstance().getContents<String>(DataFlavor.stringFlavor)
        assertEquals("curl -X DELETE '${server.url("/users/1")}'", copied)
    }

    fun `test opening a history entry restores the request without secrets`() {
        panel.urlField.text = server.url("/users")
        panel.authTypeBox.item = "Bearer Token"
        panel.bearerToken.text = "typed-secret-token"
        panel.sendButton.doClick()
        waitFor("the response") { panel.statusLabel.text == "200 OK" }
        assertEquals("Bearer typed-secret-token", server.requests.single().header("Authorization"))

        panel.urlField.text = ""
        panel.bearerToken.text = ""
        panel.historyList.selectedIndex = 0
        panel.openHistoryItem()
        assertEquals(server.url("/users"), panel.urlField.text)
        assertEquals("Bearer Token", panel.authTypeBox.item)
        assertEquals("", String(panel.bearerToken.password))
    }
}
