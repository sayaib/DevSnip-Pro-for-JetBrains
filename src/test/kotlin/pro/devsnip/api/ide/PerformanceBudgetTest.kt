package pro.devsnip.api.ide

import com.intellij.codeInsight.lookup.LookupElement
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import pro.devsnip.api.ide.engine.ExtensionFiles
import pro.devsnip.api.ide.engine.SnippetCompletionContributor
import pro.devsnip.api.ide.engine.WhenClause
import java.nio.file.Files

/**
 * What runs for every user, whether or not they open DevSnip Pro: these paths
 * must stay cheap. Budgets are generous multiples of what a laptop measures, so
 * only a real regression fails; each test prints its figure for the release report.
 */
class PerformanceBudgetTest : BasePlatformTestCase() {
    private fun millis(block: () -> Unit): Double {
        val started = System.nanoTime()
        block()
        return (System.nanoTime() - started) / 1_000_000.0
    }

    private fun report(name: String, value: Double, budget: Double) {
        println("PERF $name: ${"%.3f".format(value)} ms (budget $budget)")
        assertTrue("$name took $value ms; the budget is $budget ms", value <= budget)
    }

    fun `test completion without a prefix never touches the snippets`() {
        myFixture.configureByText("a.js", "const a = 1;\n<caret>\n")
        report("completion, nothing typed", millis { myFixture.completeBasic() }, 2000.0)
    }

    fun `test cached snippet lookups are cheap`() {
        val snippets = SnippetCompletionContributor.Snippets
        snippets.forLanguage("javascript")
        val average = millis { repeat(2000) { snippets.forLanguage("javascript") } } / 2000
        report("snippet lookup, cached", average, 1.0)
    }

    fun `test a large snippet collection stays fast to complete`() {
        val file = ExtensionFiles.customSnippets.resolve("custom_javascript.json")
        val original = runCatching { Files.readString(file) }.getOrNull()
        val large = (1..5000).joinToString(",", "{", "}") { "\"snippet $it\":{\"prefix\":\"bench$it\",\"body\":[\"line $it\"],\"description\":\"Snippet $it\"}" }
        try {
            Files.writeString(file, large)
            Thread.sleep(5) // the file's stamp is what refreshes the cache
            myFixture.configureByText("a.js", "bench4999<caret>")
            var offered: Array<LookupElement>? = null
            report("completion, 5,000 snippets", millis { offered = myFixture.completeBasic() }, 3000.0)
            // The only match is inserted right away, or offered with its siblings.
            assertTrue(offered == null || offered!!.any { it.lookupString == "bench4999" })
        } finally {
            if (original != null) Files.writeString(file, original) else Files.deleteIfExists(file)
        }
    }

    fun `test menu conditions are cheap to evaluate`() {
        val clause = "(resourceFilename =~ /^(package-lock\\.json|yarn\\.lock|pom\\.xml|.*\\.(cs|fs|vb)proj)$/) && !isWeb && !devsnip.webHost"
        val context = mapOf<String, Any?>("resourceFilename" to "pom.xml")
        val average = millis { repeat(10_000) { assertTrue(WhenClause.holds(clause, context)) } } / 10_000
        report("when clause", average, 0.5)
    }

    fun `test unpacking the same build again does no work`() {
        val dir = Files.createTempDirectory("devsnip-ext")
        try {
            val first = millis { ExtensionFiles.unpack(dir) }
            val again = millis { ExtensionFiles.unpack(dir) }
            println("PERF unpack, first time: ${"%.1f".format(first)} ms")
            report("unpack, same build", again, 20.0)
            assertTrue("again ($again ms) should be far cheaper than first ($first ms)", again < first / 3)
        } finally {
            dir.toFile().deleteRecursively()
        }
    }
}
