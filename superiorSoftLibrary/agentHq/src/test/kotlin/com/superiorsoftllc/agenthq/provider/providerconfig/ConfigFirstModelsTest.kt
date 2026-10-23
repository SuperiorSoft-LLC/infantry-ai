package com.superiorsoftllc.agenthq.provider.providerconfig

import com.superiorsoftllc.agenthq.provider.ClaudeCodeProvider
import com.superiorsoftllc.agenthq.provider.ConfigFirstModels
import com.superiorsoftllc.agenthq.provider.OpenCodeProvider
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** CP-1(c): the providers serve what the readers found; the CLI is a fallback, never the first stop. */
class ConfigFirstModelsTest {
    private val fx = ConfigFixture()

    @AfterTest
    fun cleanup() = fx.close()

    private fun noCli(): List<String> = error("the opencode CLI must not be spawned when the config names models")

    private fun opencode(cli: () -> List<String> = ::noCli) =
        OpenCodeProvider(ConfigFirstModels(OpenCodeConfigReader(fx.opencodeLocations()), cli))

    @Test
    fun `opencode models come from the config files and the cli is never spawned`() {
        fx.seedOpencode()
        val groups = opencode().models()
        assertEquals(listOf("homebrew", "opencode-go"), groups.map { it.group }.sorted())
        assertEquals(listOf("opencode-go/glm-go-2", "opencode-go/kimi-go-1"), groups.first { it.group == "opencode-go" }.models)
    }

    @Test
    fun `knownModels is the same list the picker shows`() {
        fx.seedOpencode()
        val p = opencode()
        assertEquals(p.models().flatMap { it.models }.sorted(), p.knownModels()!!.sorted())
    }

    @Test
    fun `an opencode model added to the config is offered on the next call`() {
        fx.seedOpencode()
        val p = opencode()
        assertTrue("homebrew/brew-9" !in p.knownModels()!!)
        fx.write(fx.opencodeConfig, """{"provider":{"homebrew":{"models":{"brew-9":{}}}}}""")
        assertTrue("homebrew/brew-9" in p.knownModels()!!)
    }

    @Test
    fun `with no config at all the cli answers and is remembered for the ttl only`() {
        var calls = 0
        var now = 1_000L
        val source = ConfigFirstModels(
            OpenCodeConfigReader(fx.opencodeLocations()),
            cli = { calls++; listOf("opencode/from-cli") },
            cliTtlMs = 60_000,
            clock = { now },
        )
        assertEquals(listOf("opencode/from-cli"), source())
        assertEquals(listOf("opencode/from-cli"), source())
        assertEquals(1, calls, "second call inside the ttl reuses the cli answer")
        now += 60_001
        source()
        assertEquals(2, calls, "after the ttl the cli is asked again")
    }

    @Test
    fun `a broken opencode config shows a note entry beside the models`() {
        fx.seedOpencode()
        val p = opencode()
        fx.write(fx.opencodeConfig, """{ "provider": """)
        val groups = p.models()
        assertTrue(groups.any { it.models.isNotEmpty() }, "signed-in provider still lists")
        val note = groups.single { it.group == "config" }
        assertTrue(note.models.isEmpty() && note.note!!.contains("opencode.jsonc is unreadable"), "${note}")
    }

    @Test
    fun `knownModels is null when nothing can list, so profile validation accepts any model`() {
        val p = OpenCodeProvider(ConfigFirstModels(OpenCodeConfigReader(fx.opencodeLocations()), cli = { error("no cli") }))
        assertNull(p.knownModels())
    }

    @Test
    fun `claude models come from the settings file`() {
        fx.write(fx.claudeSettings, ConfigFixture.CLAUDE_SETTINGS)
        val groups = ClaudeCodeProvider(ClaudeConfigReader(fx.claudeLocations())::read).models()
        assertEquals(1, groups.size)
        assertEquals(listOf("opus", "sonnet", "haiku", "claude-fixture-9", "claude-fixture-8"), groups[0].models)
    }

    @Test
    fun `claude availableModels restriction is stated in the note`() {
        fx.write(fx.claudeSettings, """{"availableModels":["only-this"]}""")
        val g = ClaudeCodeProvider(ClaudeConfigReader(fx.claudeLocations())::read).models().single()
        assertEquals(listOf("only-this"), g.models)
        assertTrue(g.note!!.contains("availableModels"))
    }
}
