package pro.devsnip.api.ide.engine

import com.google.gson.JsonParser
import com.intellij.openapi.application.PathManager
import com.intellij.openapi.diagnostic.Logger
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/**
 * The VS Code extension's own folder (context.extensionPath) for the
 * extension host: its media, package.json, built-in snippets and walkthrough
 * pages, unpacked from the plugin into the IDE's configuration folder. The
 * user's own snippets (custom/custom_*.json) live there too, so they are kept
 * across plugin updates; only the plugin's files are refreshed.
 */
object ExtensionFiles {
    private val LOG = Logger.getInstance(ExtensionFiles::class.java)

    /** Files the user owns once they exist (their snippets): never overwritten. */
    private fun userOwned(name: String) = name.startsWith("custom/")

    val root: Path by lazy { unpack(Path.of(PathManager.getConfigPath(), "devsnip-pro", "extension")) }

    /** Identifies the plugin's JAR; while it is the same one, what was unpacked is current. */
    private fun buildStamp(): String? = runCatching {
        Path.of(PathManager.getJarPathForClass(ExtensionFiles::class.java)!!).let { "${Files.size(it)}:${Files.getLastModifiedTime(it).toMillis()}" }
    }.getOrNull()

    fun unpack(target: Path): Path {
        val stamp = buildStamp()
        val stampFile = target.resolve(".unpacked")
        if (stamp != null && Files.isRegularFile(target.resolve("package.json")) && runCatching { Files.readString(stampFile) }.getOrNull() == stamp) return target
        Files.createDirectories(target)
        val list = resource("files.json") ?: error("The plugin's extension files are missing.")
        val files = JsonParser.parseString(String(list, Charsets.UTF_8)).asJsonArray.map { it.asString }
        var failed = false
        for (name in files) {
            val dest = target.resolve(name).normalize()
            if (!dest.startsWith(target)) continue
            try {
                if (userOwned(name) && Files.exists(dest)) continue
                val bytes = resource(name) ?: continue
                if (Files.isRegularFile(dest) && Files.size(dest) == bytes.size.toLong() && Files.readAllBytes(dest).contentEquals(bytes)) continue
                Files.createDirectories(dest.parent)
                val temp = Files.createTempFile(dest.parent, dest.fileName.toString(), ".tmp")
                Files.write(temp, bytes)
                Files.move(temp, dest, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            } catch (e: Exception) {
                LOG.warn("DevSnip Pro: could not unpack $name", e)
                failed = true
            }
        }
        if (stamp != null && !failed) runCatching { Files.writeString(stampFile, stamp) }
        return target
    }

    private fun resource(name: String): ByteArray? =
        ExtensionFiles::class.java.getResourceAsStream("/webview/extension/$name")?.use { it.readBytes() }

    /** The folder with the user's own snippets (custom_<language>.json, VS Code's format). */
    val customSnippets: Path get() = root.resolve("custom")
}
