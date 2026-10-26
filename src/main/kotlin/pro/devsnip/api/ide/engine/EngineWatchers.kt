package pro.devsnip.api.ide.engine

import com.google.gson.JsonObject
import com.intellij.openapi.Disposable
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.openapi.vfs.newvfs.BulkFileListener
import com.intellij.openapi.vfs.newvfs.events.VFileContentChangeEvent
import com.intellij.openapi.vfs.newvfs.events.VFileCreateEvent
import com.intellij.openapi.vfs.newvfs.events.VFileDeleteEvent
import com.intellij.openapi.vfs.newvfs.events.VFileEvent
import pro.devsnip.api.ide.web.ScannerHost
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap

/** VS Code's createFileSystemWatcher (the CSV & Excel viewer watches its file for changes made elsewhere), on the IDE's file events. */
class EngineWatchers(private val project: Project, private val engine: DevSnipEngine) {
    private data class Watch(val base: Path, val pattern: Regex)

    private val watches = ConcurrentHashMap<Int, Watch>()
    private var connection: Disposable? = null

    @Synchronized
    fun watch(id: Int, base: Path?, pattern: String) {
        base ?: return
        watches[id] = Watch(base, ScannerHost.globRegex(pattern))
        if (connection != null) return
        val parent = Disposer.newDisposable("DevSnip Pro file watchers")
        Disposer.register(engine, parent)
        connection = parent
        project.messageBus.connect(parent).subscribe(VirtualFileManager.VFS_CHANGES, object : BulkFileListener {
            override fun after(events: List<VFileEvent>) {
                if (watches.isEmpty()) return
                for (event in events) {
                    val kind = when (event) {
                        is VFileCreateEvent -> "create"
                        is VFileDeleteEvent -> "delete"
                        is VFileContentChangeEvent -> "change"
                        else -> continue
                    }
                    val path = runCatching { ScannerHost.normalize(Path.of(event.path)) }.getOrNull() ?: continue
                    for ((watchId, watch) in watches) {
                        if (!path.startsWith(watch.base)) continue
                        val rel = watch.base.relativize(path).toString().replace('\\', '/')
                        if (!watch.pattern.matches(rel)) continue
                        engine.post(JsonObject().apply {
                            addProperty("type", "__fsEvent")
                            addProperty("id", watchId)
                            addProperty("kind", kind)
                            addProperty("path", ScannerHost.canonical(path))
                        })
                    }
                }
            }
        })
    }

    fun unwatch(id: Int) {
        watches.remove(id)
    }

    @Synchronized
    fun dispose() {
        watches.clear()
        connection?.let(Disposer::dispose)
        connection = null
    }
}
