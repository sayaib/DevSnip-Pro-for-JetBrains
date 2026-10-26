package pro.devsnip.api.ide.engine

import com.intellij.openapi.project.Project
import pro.devsnip.api.ide.web.RestClientHost
import java.util.concurrent.CompletableFuture

/**
 * The REST API Client's points ledger: premium features spend points from
 * the extension's milestone tracker (in the project's extension host), and
 * failed runs are refunded, as in VS Code. Without the embedded browser there
 * is no extension host and no ledger (nothing is charged).
 */
object EnginePoints {
    fun ledger(project: Project): RestClientHost.PointsLedger? {
        if (!DevSnipEngine.isSupported()) return null
        val engine = DevSnipEngine.getInstance(project)
        // The balance shown on the page comes from the host; start it so the page gets it.
        engine.start()
        return object : RestClientHost.PointsLedger {
            override fun balance(): Int = DevSnipNative.points

            override fun spend(amount: Int, reason: String): CompletableFuture<Boolean> =
                engine.request("__ledger") {
                    addProperty("op", "spend")
                    addProperty("amount", amount)
                    addProperty("reason", reason)
                }.thenApply { answer ->
                    answer["error"]?.takeIf { it.isJsonPrimitive }?.let { throw IllegalStateException(it.asString) }
                    answer["result"]?.takeIf { it.isJsonPrimitive }?.asBoolean == true
                }

            override fun refund(amount: Int, reason: String): CompletableFuture<Unit> =
                engine.request("__ledger") {
                    addProperty("op", "refund")
                    addProperty("amount", amount)
                    addProperty("reason", reason)
                }.thenApply { Unit }
        }
    }
}
