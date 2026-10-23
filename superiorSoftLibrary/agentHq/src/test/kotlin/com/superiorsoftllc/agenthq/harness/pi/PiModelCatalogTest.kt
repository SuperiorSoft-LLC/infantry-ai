package com.superiorsoftllc.agenthq.harness.pi

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * ROLE-FIRST S4: Pi's `models` source. The parser is pinned against a RECORDED `pi --list-models` table
 * (`/pi/list-models.txt`, captured live 2026-10-18: 179 models in 6 providers), not a hand-written guess.
 */
class PiModelCatalogTest {

    private val recorded: String = javaClass.getResourceAsStream("/pi/list-models.txt")!!.bufferedReader().readText()

    @Test
    fun theRecordedTableParsesToSixProvidersAnd179ProviderQualifiedIds() {
        val parsed = PiModelCatalog.parseListModels(recorded)
        assertEquals(listOf("anthropic", "claude-bridge", "commandcode", "deepseek", "google", "opencode-go"), parsed.keys.toList())
        assertEquals(179, parsed.values.sumOf { it.size })
        assertEquals(mapOf("anthropic" to 17, "claude-bridge" to 14, "commandcode" to 93, "deepseek" to 2, "google" to 23, "opencode-go" to 30), parsed.mapValues { it.value.size })
        // The id is `provider/model` -- what Pi's --model documents, and unambiguous: the same name is under two providers.
        assertEquals("anthropic/claude-fable-5", parsed.getValue("anthropic").first())
        assertTrue("claude-bridge/claude-fable-5" in parsed.getValue("claude-bridge"))
        assertEquals("opencode-go/step-5-preview-free", parsed.getValue("opencode-go").last())
        // A routed model name keeps its own slash; the provider is the text before the FIRST one.
        assertTrue("commandcode/deepseek/deepseek-v4-pro" in parsed.getValue("commandcode"))
    }

    @Test
    fun theParserToleratesBannersExtraColumnsAndJunkButNeedsTheHeader() {
        val noisy = """
            warning: something on stdout
            provider  model   context  max-out  thinking  images  price  extra
            acme      fast-1  1M       64K      yes       no      0.1    x
            acme      fast-1  1M       64K      yes       no
            acme      lite
            (bad)     row     1M
            other     m.2:beta

        """.trimIndent()
        assertEquals(mapOf("acme" to listOf("acme/fast-1", "acme/lite"), "other" to listOf("other/m.2:beta")), PiModelCatalog.parseListModels(noisy))
        assertTrue(PiModelCatalog.parseListModels("acme fast-1 1M\n").isEmpty(), "no header = not the table")
        assertTrue(PiModelCatalog.parseListModels("").isEmpty())
    }

    @Test
    fun rowsCarryThePiHarnessIdAndTheVendorAsGroup() {
        val rows = PiModelCatalog(listModels = { recorded }, storeFile = { null }).models()
        assertTrue(rows.all { it.provider == "pi" }, "$rows")
        assertEquals(6, rows.size)
        assertEquals("deepseek", rows.first { it.group == "deepseek" }.label)
        assertTrue(rows.all { it.note == null })
    }

    @Test
    fun aFailingCommandFallsBackToModelsStoreWithANoteThatLeaksNoContent() {
        val dir = Files.createTempDirectory("pi-store").toFile()
        try {
            val store = File(dir, "models-store.json").apply {
                writeText("""{"deepseek":{"models":[{"id":"deepseek-chat","baseUrl":"https://SECRET.example"},{"id":"deepseek-reasoner"}],"etag":"x"},"broken":{"models":"nope"},"google":{"models":[{"name":"no id"}]}}""")
            }
            val rows = PiModelCatalog(listModels = { error("timed out after 20000ms") }, storeFile = { store }).models()
            assertEquals(listOf("deepseek/deepseek-chat", "deepseek/deepseek-reasoner"), rows.single { it.group == "deepseek" }.models)
            val note = assertNotNull(rows.single { it.group == PiModelCatalog.NOTES_GROUP }.note)
            assertTrue("timed out" in note && "models-store.json" in note, note)
            assertFalse(rows.any { r -> (r.note ?: "").contains("SECRET") || r.models.any { "SECRET" in it } }, "file content leaked: $rows")
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun bothSourcesFailingIsOneNotedEmptyRow() {
        val rows = PiModelCatalog(listModels = { error("pi not found") }, storeFile = { File("does-not-exist-${System.nanoTime()}.json") }).models()
        val only = rows.single()
        assertEquals("pi", only.provider)
        assertTrue(only.models.isEmpty())
        assertTrue(only.note!!.startsWith("could not list models: pi --list-models failed: pi not found"), only.note)
    }

    @Test
    fun anUnreadableStoreIsAFixedReasonNotItsText() {
        val dir = Files.createTempDirectory("pi-store").toFile()
        try {
            val store = File(dir, "models-store.json").apply { writeText("{ not json SECRET") }
            val note = PiModelCatalog(listModels = { error("boom") }, storeFile = { store }).models().single().note!!
            assertTrue("unreadable" in note && "SECRET" !in note, note)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun theListingIsCachedForItsTtlThenReRead() {
        var calls = 0
        var now = 0L
        val catalog = PiModelCatalog(listModels = { calls++; recorded }, storeFile = { null }, ttlMs = 1_000, clock = { now })
        catalog.models(); catalog.models()
        assertEquals(1, calls)
        now = 1_001
        catalog.models()
        assertEquals(2, calls)
    }

    // --- the bounded child process (real processes; skipped where `node` is absent) ---

    private fun node(): String? = try {
        val p = ProcessBuilder("node", "--version").redirectErrorStream(true).start()
        p.inputStream.readBytes()
        if (p.waitFor() == 0) "node" else null
    } catch (e: Exception) {
        null
    }

    @Test
    fun theBoundedRunnerReturnsStdout() {
        val node = node() ?: return
        assertEquals("provider model\n", PiModelCatalog.runBounded(listOf(node, "-e", "process.stdout.write('provider model\\n')"), timeoutMs = 10_000))
    }

    @Test
    fun theBoundedRunnerKillsAChildThatOutlivesItsTimeout() {
        val node = node() ?: return
        val started = System.currentTimeMillis()
        val e = assertFailsWith<IllegalStateException> {
            PiModelCatalog.runBounded(listOf(node, "-e", "setTimeout(()=>{}, 60000)"), timeoutMs = 500)
        }
        assertTrue("timed out" in e.message!!, e.message)
        assertTrue(System.currentTimeMillis() - started < 10_000, "the child was not killed promptly")
    }

    @Test
    fun theBoundedRunnerRefusesOversizedOutputAndNonZeroExit() {
        val node = node() ?: return
        val big = assertFailsWith<IllegalStateException> {
            PiModelCatalog.runBounded(listOf(node, "-e", "process.stdout.write('x'.repeat(100000))"), timeoutMs = 10_000, maxBytes = 1_000)
        }
        assertTrue("exceeded" in big.message!!, big.message)
        val exit = assertFailsWith<IllegalStateException> {
            PiModelCatalog.runBounded(listOf(node, "-e", "process.exit(3)"), timeoutMs = 10_000)
        }
        assertEquals("exited with code 3", exit.message)
    }

    // --- INSTALL-PIDCP-1: the probe runs Pi's extensions, which write under the child's home directory ---

    /**
     * The probe child must see the daemon's `user.home` as its home, not the inherited HOME/USERPROFILE: Pi loads
     * every installed extension even for `--list-models`, and one of them (pi-dcp) writes `<home>/.pi-dcp`. The child
     * only prints `os.homedir()`, so nothing is written anywhere.
     */
    @Test
    fun theBoundedRunnerGivesTheChildTheDaemonsUserHome() {
        val node = node() ?: return
        val tempHome = Files.createTempDirectory("pidcp-home").toFile().canonicalPath
        val saved = System.getProperty("user.home")
        try {
            System.setProperty("user.home", tempHome)
            val printed = PiModelCatalog.runBounded(listOf(node, "-e", "process.stdout.write(require('os').homedir())"), timeoutMs = 10_000)
            assertEquals(tempHome, File(printed).canonicalPath, "the probe child resolved a home outside the daemon's user.home")
        } finally {
            System.setProperty("user.home", saved)
            File(tempHome).delete()
        }
    }

    @Test
    fun homeOverridesOnlyReplaceInheritedValuesThatDiffer() {
        val home = "C:\\tmp\\h"
        // Windows: USERPROFILE replaced; HOME replaced only when it was inherited; never added.
        assertEquals(mapOf("USERPROFILE" to home), PiModelCatalog.homeOverrides(mapOf("USERPROFILE" to "C:\\Users\\real"), home, windows = true))
        assertEquals(
            mapOf("USERPROFILE" to home, "HOME" to home),
            PiModelCatalog.homeOverrides(mapOf("USERPROFILE" to "C:\\Users\\real", "HOME" to "C:\\Users\\real"), home, windows = true),
        )
        // Production: user.home equals the inherited home, so nothing changes.
        assertEquals(emptyMap(), PiModelCatalog.homeOverrides(mapOf("USERPROFILE" to home, "HOME" to home), home, windows = true))
        // Non-Windows: HOME is set even when absent; USERPROFILE is not touched.
        assertEquals(mapOf("HOME" to "/tmp/h"), PiModelCatalog.homeOverrides(emptyMap(), "/tmp/h", windows = false))
        assertEquals(emptyMap(), PiModelCatalog.homeOverrides(mapOf("HOME" to "/tmp/h"), "/tmp/h", windows = false))
        // No usable user.home: leave the environment alone.
        assertEquals(emptyMap(), PiModelCatalog.homeOverrides(mapOf("USERPROFILE" to "x"), null, windows = true))
        assertEquals(emptyMap(), PiModelCatalog.homeOverrides(mapOf("USERPROFILE" to "x"), " ", windows = true))
    }
}
