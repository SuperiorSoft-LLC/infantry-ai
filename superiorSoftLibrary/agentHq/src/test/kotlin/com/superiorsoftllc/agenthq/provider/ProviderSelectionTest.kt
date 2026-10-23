package com.superiorsoftllc.agenthq.provider

import com.superiorsoftllc.agenthq.claude.ClaudeCommand
import com.superiorsoftllc.agenthq.opencode.OpenCodeCommand
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Contract 1.3.0 (story 9.0): which providers a daemon may spawn besides its default
 * (`AGENTHQ_PROVIDERS`), and how a session's model reaches a CLI command line. Pure functions only --
 * no CLI needs to be installed and the environment is never read for a value under test.
 */
class ProviderSelectionTest {

    private val opencode = OpenCodeProvider()

    // --- AGENTHQ_PROVIDERS ------------------------------------------------------------------------

    @Test
    fun unsetOrBlankMeansTheDefaultOnly() {
        assertEquals(emptyList(), ProviderFactory.additionalFromFlag(null, opencode))
        assertEquals(emptyList(), ProviderFactory.additionalFromFlag("", opencode))
        assertEquals(emptyList(), ProviderFactory.additionalFromFlag(" , ,", opencode))
    }

    @Test
    fun entriesKeepTheirOrderAndTheDefaultIsNotListedTwice() {
        val ids = ProviderFactory.additionalFromFlag("claude, OpenCode ,stub", opencode).map { it.id }
        assertEquals(listOf("claude", "stub"), ids, "the default is always available, so it is dropped")
    }

    @Test
    fun aliasesCollapseToOneEntry() {
        val ids = ProviderFactory.additionalFromFlag("cc,claude,claude-code,scripted,stub", opencode).map { it.id }
        assertEquals(listOf("claude", "stub"), ids)
    }

    @Test
    fun anUnknownEntryFailsLoudlyAndNamesTheVariable() {
        val failure = assertFailsWith<IllegalStateException> {
            ProviderFactory.additionalFromFlag("claude,gemini", opencode)
        }
        assertTrue(failure.message!!.contains(ProviderFactory.PROVIDERS_ENV), failure.message)
        assertTrue(failure.message!!.contains("gemini"), failure.message)
    }

    // --- the model on the command line -------------------------------------------------------------

    @Test
    fun aSessionModelWinsOverTheConfiguredOne() {
        assertEquals("anthropic/claude-sonnet-4-5", OpenCodeCommand.effectiveModel("anthropic/claude-sonnet-4-5"))
        assertEquals("sonnet", ClaudeCommand.effectiveModel("sonnet"))
    }

    @Test
    fun noSessionModelFallsBackToTheConfiguredDefault() {
        // Whatever the environment says, "none" and "blank" mean the configured default -- the
        // pre-1.3.0 behaviour, so a session that never chose a model runs exactly as it always did.
        assertEquals(OpenCodeCommand.model(), OpenCodeCommand.effectiveModel(null))
        assertEquals(OpenCodeCommand.model(), OpenCodeCommand.effectiveModel("  "))
        assertEquals(ClaudeCommand.model(), ClaudeCommand.effectiveModel(null))
        assertEquals(ClaudeCommand.model(), ClaudeCommand.effectiveModel(""))
    }

    @Test
    fun theSessionModelLandsBehindTheModelFlag() {
        assertEquals(
            listOf("opencode.exe", "run", "--format", "json", "-m", "ollama/qwen2.5-coder:7b"),
            OpenCodeCommand.command("opencode.exe", OpenCodeCommand.effectiveModel("ollama/qwen2.5-coder:7b"), null),
        )
        val claude = ClaudeCommand.command(
            executable = "claude.exe",
            model = ClaudeCommand.effectiveModel("haiku"),
            permissionMode = null,
            mcpConfigs = emptyList(),
            strictMcp = false,
            resumeSessionId = null,
        )
        assertEquals(listOf("--model", "haiku"), claude.subList(claude.indexOf("--model"), claude.indexOf("--model") + 2))
    }

    @Test
    fun eachProviderReportsItsConfiguredDefaultModel() {
        assertEquals(OpenCodeCommand.model(), OpenCodeProvider().defaultModel(), "opencode always names a model")
        assertEquals(ClaudeCommand.model(), ClaudeCodeProvider().defaultModel(), "claude: AGENTHQ_CLAUDE_MODEL or none")
        assertNull(StubProvider().defaultModel(), "the stub has no model")
    }

    @Test
    fun theStubEchoesASessionModelOnItsLabelAndOnlyThen() {
        val stub = StubProvider()
        val chosen = stub.spec(RunRequest(prompt = "hi", model = "m-1")).command
        assertEquals(listOf("--model", "m-1"), chosen.takeLast(2))
        val none = stub.spec(RunRequest(prompt = "hi")).command
        assertTrue("--model" !in none, "no model chosen, no model echoed: $none")
    }

    // --- AGENT-UX-7: the model list a provider reports for save-time validation -------------------

    @Test
    fun opencodeParsesItsPlainTextModelList() {
        // `opencode models` prints one `provider/model` id per line: no JSON, no headers, one entry
        // may carry a `:tag` (ollama). This is the real shape, verified against the installed CLI.
        val output = """
            opencode/space-bunny-free
            opencode/claude-sonnet-4
            opencode-go/deepseek-v4.1-flash
            ollama/qwen2.5:7b
        """.trimIndent()
        assertEquals(
            listOf(
                "opencode/space-bunny-free",
                "opencode/claude-sonnet-4",
                "opencode-go/deepseek-v4.1-flash",
                "ollama/qwen2.5:7b",
            ),
            opencode.parseModels(output),
        )
    }

    @Test
    fun opencodeDropsBlankLinesAndCommandHelpText() {
        // A rejected invocation makes yargs print its help; none of that is a model id.
        val output = "\nopencode/big-pickle\n\nopencode models [provider]\n list all available models\n"
        assertEquals(listOf("opencode/big-pickle"), opencode.parseModels(output))
    }

    @Test
    fun anUnparseableModelListMeansUnknownSoAnythingIsAccepted() {
        assertEquals(null, opencode.parseModels("   \n\n"), "no ids = cannot validate, accept any model")
    }
}
