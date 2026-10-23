package com.superiorsoftllc.agenthq.provider.providerconfig

import java.io.File
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** CP-1(b): the Claude reader, driven ONLY by a fixture settings file and an injected environment. */
class ClaudeConfigReaderTest {
    private val fx = ConfigFixture()

    @AfterTest
    fun cleanup() = fx.close()

    private fun read(settings: String?, env: Map<String, String> = emptyMap()): ClaudeCatalog {
        settings?.let { fx.write(fx.claudeSettings, it) }
        return ClaudeConfigReader(fx.claudeLocations(env)).read()
    }

    @Test
    fun `no settings file still offers the cli aliases`() {
        val c = read(null)
        assertEquals(ClaudeConfigReader.CLI_ALIASES, c.models)
        assertNull(c.defaultModel)
        assertFalse(c.restricted)
    }

    @Test
    fun `the configured model and per-model settings appear beside the aliases`() {
        val c = read("""{"model":"claude-opus-9","modelSettings":{"claude-sonnet-5-5":{"effortLevel":"low"}},"theme":"dark"}""")
        assertEquals("claude-opus-9", c.defaultModel)
        assertEquals(listOf("opus", "sonnet", "haiku", "claude-opus-9", "claude-sonnet-5-5"), c.models)
    }

    @Test
    fun `model pins in the settings env block and the process environment are offered`() {
        val c = read(
            """{"env":{"ANTHROPIC_DEFAULT_SONNET_MODEL":"my-sonnet-pin"}}""",
            env = mapOf("ANTHROPIC_DEFAULT_HAIKU_MODEL" to "my-haiku-pin", "UNRELATED" to "x"),
        )
        assertTrue("my-sonnet-pin" in c.models && "my-haiku-pin" in c.models, "${c.models}")
        assertFalse("x" in c.models)
    }

    @Test
    fun `availableModels restricts the catalogue to exactly that list`() {
        val c = read("""{"model":"claude-a","availableModels":["claude-a","claude-b"]}""")
        assertTrue(c.restricted)
        assertEquals(listOf("claude-a", "claude-b"), c.models)
        assertFalse("opus" in c.models)
    }

    @Test
    fun `a later settings file wins over an earlier one`() {
        val local = File(fx.root, "claude/settings.local.json")
        fx.write(fx.claudeSettings, """{"model":"from-shared"}""")
        fx.write(local, """{"model":"from-local"}""")
        val c = ClaudeConfigReader(ClaudeConfigLocations(listOf(fx.claudeSettings, local))).read()
        assertEquals("from-local", c.defaultModel)
    }

    @Test
    fun `a malformed settings file is a visible note and the aliases remain`() {
        val c = read("""{"model": """)
        assertTrue(c.notes.any { it.startsWith("settings.json is unreadable") }, "${c.notes}")
        assertEquals(ClaudeConfigReader.CLI_ALIASES, c.models)
    }

    @Test
    fun `an edit is picked up on the next read`() {
        val reader = ClaudeConfigReader(fx.claudeLocations())
        fx.write(fx.claudeSettings, """{"model":"first"}""")
        assertEquals("first", reader.read().defaultModel)
        fx.write(fx.claudeSettings, """{"model":"second"}""")
        assertEquals("second", reader.read().defaultModel)
    }

    @Test
    fun `locations honour CLAUDE_CONFIG_DIR else the injected home`() {
        val home = File(fx.root, "home")
        assertEquals(File(home, ".claude/settings.json"), ClaudeConfigLocations.fromEnvironment(emptyMap(), home).settingsFiles[0])
        val dir = File(fx.root, "cfg")
        assertEquals(File(dir, "settings.local.json"), ClaudeConfigLocations.fromEnvironment(mapOf("CLAUDE_CONFIG_DIR" to dir.path), home).settingsFiles[1])
    }
}
