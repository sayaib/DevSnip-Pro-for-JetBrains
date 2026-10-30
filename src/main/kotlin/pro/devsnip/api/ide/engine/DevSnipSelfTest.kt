package pro.devsnip.api.ide.engine

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.guessProjectDir
import com.intellij.openapi.startup.ProjectActivity
import com.intellij.openapi.wm.ToolWindowManager
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

/**
 * A self-check of the extension host in a running IDE, for development and
 * support: started only when the IDE runs with -Ddevsnip.selfTest (see
 * build.gradle.kts, `DEVSNIP_SELFTEST=<project> ./gradlew runIde`). It opens
 * the main pages, checks that they render, and writes "DEVSNIP-SELFTEST"
 * lines to idea.log. It does nothing otherwise.
 */
class DevSnipSelfTest : ProjectActivity {
    override suspend fun execute(project: Project) {
        val mode = System.getProperty("devsnip.selfTest") ?: return
        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                run(project)
            } catch (e: Throwable) {
                log("FAILED with ${e.javaClass.simpleName}: ${e.message}")
                LOG.warn(e)
            }
            if (mode == "exit") ApplicationManager.getApplication().invokeLater { ApplicationManager.getApplication().exit(true, true, false) }
        }
    }

    private fun log(text: String) = LOG.warn("DEVSNIP-SELFTEST: $text")

    private fun <T> edt(block: () -> T): T {
        val future = CompletableFuture<T>()
        ApplicationManager.getApplication().invokeLater { future.complete(runCatching(block).getOrElse { throw it }) }
        return future.get(60, TimeUnit.SECONDS)
    }

    private fun run(project: Project) {
        Thread.sleep(3000)
        log("perf: engine before first use=${DevSnipEngine.getInstance(project).status} actions on UI thread=${DevSnipActions.registerMillis} ms")
        val engine = DevSnipEngine.getInstance(project)
        val ready = CompletableFuture<Boolean>()
        val started = System.nanoTime()
        engine.whenReady { ready.complete(it) }
        val ok = runCatching { ready.get(120, TimeUnit.SECONDS) }.getOrDefault(false)
        log("perf: extension host ready in ${(System.nanoTime() - started) / 1_000_000} ms")
        log("engine ready=$ok status=${engine.status} failure=${engine.failure} commands=${engine.commands.size} sidebar=${engine.providesSidebar}")
        if (!ok) return
        val extension = ExtensionFiles.root.toString().replace('\\', '/')
        log("sync channel: " + engine.probe("(function(){var x=new XMLHttpRequest();x.open('POST',window.__devsnipHost.syncUrl,false);x.send(JSON.stringify({method:'fs.stat',params:{path:${com.google.gson.JsonPrimitive(extension)}}}));return x.status+' '+x.responseText})()").get(30, TimeUnit.SECONDS))

        val commands = listOf(
            // premiumStatus is left out: it shows a modal dialog, as in VS Code.
            "advancedToolsHub", "milestoneTracker", "showSnippets", "readmeManager", "listAndRemoveConsoleLogs", "removeUnusedImports",
            "dependencyManager", "securityHub", "securityAudit", "cloudSecurityAudit", "dependencyAudit", "endpointSecurityScan",
            "databaseClient", "getStarted", "whatsNew", "curlConverter", "osvScanner", "openGUI",
        ).map { WEB_PREFIX + it }
        for (command in commands) {
            val opened = System.nanoTime()
            val error = runCatching { engine.execute(command).get(60, TimeUnit.SECONDS) }.getOrElse { "timeout: ${it.message}" }
            log("perf: ${command.removePrefix(WEB_PREFIX)} ran in ${(System.nanoTime() - opened) / 1_000_000} ms")
            Thread.sleep(2500)
            val page = edt { FileEditorManager.getInstance(project).selectedEditor }
            val probe = when (page) {
                is EnginePanelEditor -> page.probe("document.title + ' | ' + document.body.innerText.replace(/\\s+/g,' ').trim().slice(0,140) + ' | chars=' + document.body.innerText.length")
                is pro.devsnip.api.ide.web.DevSnipEditorBase -> CompletableFuture.completedFuture(JsonObject().apply { addProperty("result", "native page ${page.name}") })
                else -> CompletableFuture.completedFuture(JsonObject().apply { addProperty("result", "no page (${page?.javaClass?.simpleName})") })
            }
            val result = runCatching { probe.get(20, TimeUnit.SECONDS) }.getOrElse { JsonObject().apply { addProperty("error", "probe timeout") } }
            log("$command -> error=$error page=${page?.name} ${result}")
        }

        // A tool that needs the desktop host: it opens empty and runs only when asked (explicit run).
        engine.execute(WEB_PREFIX + "jwtDecoder").get(30, TimeUnit.SECONDS)
        Thread.sleep(2500)
        val tool = edt { FileEditorManager.getInstance(project).selectedEditor } as? pro.devsnip.api.ide.web.ToolPageEditor
        val run = tool?.probe(RUN_TOOL_SCRIPT)?.let { runCatching { it.get(20, TimeUnit.SECONDS) }.getOrElse { e -> JsonObject().apply { addProperty("error", e.toString()) } } }
        log("tool run -> $run")

        // The Tools sidebar from the extension's provider.
        edt { ToolWindowManager.getInstance(project).getToolWindow(pro.devsnip.api.ide.ui.ApiToolWindowFactory.TOOL_WINDOW_ID)?.activate(null) }
        Thread.sleep(4000)
        val sidebar = edt {
            val window = ToolWindowManager.getInstance(project).getToolWindow(pro.devsnip.api.ide.ui.ApiToolWindowFactory.TOOL_WINDOW_ID)
            (window?.contentManager?.contents?.firstOrNull()?.component as? DevSnipToolWindowPanel)?.view
        }
        log("sidebar -> " + (sidebar?.probe("document.body.innerText.replace(/\\s+/g,' ').trim().slice(0,200)")?.get(20, TimeUnit.SECONDS) ?: "no extension sidebar"))

        val root = project.guessProjectDir() ?: return
        // A CSV file in the viewer's Table tab.
        root.findChild("data.csv")?.let { csv ->
            edt {
                OpenFileDescriptor(project, csv).navigate(true)
                FileEditorManager.getInstance(project).setSelectedEditor(csv, "devsnip.csvViewer")
            }
            Thread.sleep(5000)
            val table = edt { FileEditorManager.getInstance(project).getEditors(csv).filterIsInstance<EngineCustomEditor>().firstOrNull() }
            log("csv viewer -> " + (table?.probe("document.body.innerText.replace(/\\s+/g,' ').trim().slice(0,200)")?.get(20, TimeUnit.SECONDS) ?: "no table editor"))
        }
        // Insert Debug Log on a variable in app.js.
        root.findFileByRelativePath("src/app.js")?.let { js ->
            val editor = edt {
                val e = EngineEditors.openEditor(project, OpenFileDescriptor(project, js), true)!!
                val offset = e.document.text.indexOf("total")
                e.caretModel.moveToOffset(offset + 2)
                e
            }
            val before = edt { editor.document.text }
            val error = engine.execute(WEB_PREFIX + "insertDebugLog", JsonArray()).get(30, TimeUnit.SECONDS)
            Thread.sleep(1000)
            val after = edt { editor.document.text }
            log("insertDebugLog -> error=$error changed=${before != after} added=${after.lines().filter { it !in before.lines() }}")
            edt { FileDocumentManager.getInstance().reloadFromDisk(editor.document) }
        }
        logMemory(engine)
        log("done")
    }

    private fun logMemory(engine: DevSnipEngine) {
        val heap = engine.probe("JSON.stringify({used:performance.memory.usedJSHeapSize,total:performance.memory.totalJSHeapSize})")
        log("perf: extension host JS heap ${runCatching { heap.get(20, TimeUnit.SECONDS) }.getOrNull()}")
        System.gc()
        val runtime = Runtime.getRuntime()
        log("perf: IDE heap used ${(runtime.totalMemory() - runtime.freeMemory()) / 1_048_576} MB")
    }

    companion object {
        private val LOG = Logger.getInstance(DevSnipSelfTest::class.java)
        private const val WEB_PREFIX = "sayaib.hue-console."

        /** Fills in a token, presses Run, and reports the page before and after. */
        private const val RUN_TOOL_SCRIPT = "new Promise(function(done){var r=document.getElementById('results');var before=r.querySelector('.ds-execution')?'bar':'empty';" +
            "var t=document.querySelector('textarea');t.value='eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJzdWIiOiIxIn0.c2ln';t.dispatchEvent(new Event('input',{bubbles:true}));" +
            "document.querySelector('.run-row button.btn').click();setTimeout(function(){var e=r.querySelector('.ds-execution');" +
            "done(before+' -> '+(e?e.getAttribute('data-state')+' | '+e.textContent:'no execution bar')+' | '+r.innerText.replace(/\\s+/g,' ').slice(0,100))},1000)})"
    }
}
