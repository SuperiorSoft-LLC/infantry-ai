package com.superiorsoftllc.infantry.plugins.official.opencode.harness

import com.superiorsoftllc.infantry.harness.api.HarnessCommandAction
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * HARNESS H8b-3: tests moved from the daemon with the core opencode code. Moved here: OpenCodeCommandsTest (GAP-4), OpenCodeModelResolutionTest
 * (BUG-G2), OpenCodeModelsTest, OpenCodeDefaultModelLiveTest (BUG-DEFMODEL; the live half runs the real `opencode models` and is skipped where
 * the binary is absent). OpenCodeFoldParityTest has no counterpart: the provider/harness fold no longer exists.
 */
class OpenCodeMovedTest {
    // ---- OpenCodeCommandsTest
    @Test fun `opencode offers its model and compact commands`() {
        assertEquals(listOf("model", "compact"), OpenCodeCommand.COMMANDS.map { it.name })
    }

    @Test fun `opencode has no effort command because its run has no effort flag`() {
        assertTrue(OpenCodeCommand.COMMANDS.none { it.action == HarnessCommandAction.SET_EFFORT })
        assertEquals("/compact", OpenCodeCommand.COMMANDS.single { it.name == "compact" }.native)
    }

    // ---- OpenCodeModelResolutionTest
    private val pickle: (String) -> String? = { if (it == "INFANTRY_OPENCODE_MODEL") "opencode/big-pickle" else null }
    private val unset: (String) -> String? = { null }

    private fun argvModel(requested: String?, env: (String) -> String?): String {
        val argv = OpenCodeCommand.command("opencode.exe", OpenCodeCommand.effectiveModel(requested, env), null)
        return argv[argv.indexOf("-m") + 1]
    }

    @Test fun `an explicit model wins`() = assertEquals("vendor/explicit", argvModel("vendor/explicit", pickle))

    @Test fun `no model uses INFANTRY_OPENCODE_MODEL`() = assertEquals("opencode/big-pickle", argvModel(null, pickle))

    @Test fun `the default placeholder is never passed through`() {
        assertEquals("opencode/big-pickle", argvModel("default", pickle))
        assertEquals("opencode/big-pickle", argvModel(" Default ", pickle))
        assertEquals(OpenCodeCommand.DEFAULT_MODEL, argvModel("default", unset))
        assertEquals(OpenCodeCommand.DEFAULT_MODEL, argvModel(null) { if (it == "INFANTRY_OPENCODE_MODEL") "default" else null })
    }

    @Test fun `nothing configured falls back to the free model`() = assertEquals(OpenCodeCommand.DEFAULT_MODEL, argvModel(null, unset))

    // ---- OpenCodeModelsTest
    @Test fun `the CLI listing is grouped by upstream provider`() {
        val groups = OpenCodeCommand.models(listOf("opencode/big-pickle", "anthropic/claude-x", "opencode/beam-free", "", "junk"), emptyList())
        assertEquals(listOf("anthropic", "opencode"), groups.map { it.group }.sorted())
        assertEquals(listOf("opencode/big-pickle", "opencode/beam-free"), groups.first { it.group == "opencode" }.models)
    }

    @Test fun `no models from the CLI is one honest empty entry`() {
        val groups = OpenCodeCommand.models(emptyList(), emptyList())
        assertEquals(1, groups.size)
        assertTrue(groups[0].models.isEmpty() && groups[0].note != null)
    }

    // ---- OpenCodeDefaultModelLiveTest
    private val knownDead = listOf("space-bunny", "minimax-m3.1-flash-free")

    @Test fun `the default model is not a known dead id`() {
        for (dead in knownDead) assertFalse(OpenCodeCommand.DEFAULT_MODEL.contains(dead), "DEFAULT_MODEL ${OpenCodeCommand.DEFAULT_MODEL} is retired ($dead)")
    }

    @Test fun `the default model is in the live opencode catalog`() {
        val lines = try {
            val p = ProcessBuilder("opencode", "models").redirectErrorStream(true).start()
            val out = p.inputStream.bufferedReader().readLines()
            if (!p.waitFor(30, java.util.concurrent.TimeUnit.SECONDS) || p.exitValue() != 0) return
            out
        } catch (e: Exception) {
            return // opencode binary absent: nothing to check
        }
        val live = lines.map { it.trim() }.filter(OpenCodeCommand::isModelId)
        if (live.isEmpty()) return
        assertTrue(OpenCodeCommand.DEFAULT_MODEL in live, "DEFAULT_MODEL ${OpenCodeCommand.DEFAULT_MODEL} is not in `opencode models` (${live.size} ids)")
    }

    // ---- ProviderSelectionTest (AGENT-UX-7) model-list parsing, ContextLimitTranslationTest opencode case, config merge with nothing to merge
    @Test fun `opencode models lists one provider slash model per line and drops blank and help lines`() {
        val output = "\nopencode/big-pickle\n\nopencode models [provider]\n list all available models\nollama/qwen2.5:7b\n"
        val ids = OpenCodeCommand.models(output.lines().map { it.trim() }.filter(OpenCodeCommand::isModelId), emptyList()).flatMap { it.models }
        assertEquals(listOf("opencode/big-pickle", "ollama/qwen2.5:7b"), ids)
        assertTrue(OpenCodeCommand.models("   \n\n".lines().map { it.trim() }.filter(OpenCodeCommand::isModelId), emptyList()).single().models.isEmpty(), "no ids = unknown")
    }

    @Test fun `opencode reports context limit unsupported and says why`() {
        val cap = OpenCodeCommand.CONTEXT_LIMIT
        assertEquals(com.superiorsoftllc.infantry.harness.api.ContextLimitSupport.UNSUPPORTED, cap.support)
        assertTrue(cap.how.isNotBlank())
    }

    @Test fun `a config merge with nothing to merge leaves the config alone`() {
        assertEquals("""{"a":1}""", OpenCodeConfig.merge("""{"a":1}""", com.superiorsoftllc.infantry.harness.api.ConfigParts()))
    }
}
