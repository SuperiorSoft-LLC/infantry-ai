package com.superiorsoftllc.agenthq.provider.providerconfig

import com.superiorsoftllc.agenthq.provider.ClaudeCodeProvider
import com.superiorsoftllc.agenthq.provider.ConfigFirstModels
import com.superiorsoftllc.agenthq.provider.OpenCodeProvider
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * CP-1 FIX (QA finding): a malformed config file can hold an API key, and a JSON parser's message QUOTES the
 * input it choked on. Whatever a reader tells the user -- notes, `models` entries, log output -- may name the
 * file and a FIXED reason, never anything derived from the file's content.
 */
class ConfigNoLeakTest {
    private val fx = ConfigFixture()

    @AfterTest
    fun cleanup() = fx.close()

    /** Unquoted value => invalid JSON, and kotlinx's error message would quote this text. */
    private val poison = """{"opencode-go": {"type": "api", "key": sk-LEAKTEST-123}"""

    /** Every word of the poisoned file that could be a fragment of it. */
    private val fragments = listOf("LEAKTEST", "sk-", "123", "opencode-go", "type", "\"key\"")

    private fun assertClean(text: String, where: String) {
        fragments.forEach { assertFalse(it in text, "$where leaked '$it' from the config file: $text") }
    }

    @Test
    fun `a malformed auth json yields a fixed-reason note and no file text`() {
        fx.write(fx.opencodeModels, ConfigFixture.MODELS_JSON)
        fx.write(fx.opencodeConfig, ConfigFixture.CONFIG_JSONC)
        fx.write(fx.opencodeAuth, poison)
        val catalog = OpenCodeConfigReader(fx.opencodeLocations()).read()

        assertEquals(listOf("auth.json is unreadable: not valid JSON"), catalog.notes)
        assertClean(catalog.notes.joinToString(), "notes")
        assertClean(catalog.providers.joinToString { it.id + it.name + it.models }, "providers")
    }

    @Test
    fun `every config file kind reports a fixed reason only`() {
        fx.write(fx.opencodeModels, poison)
        fx.write(fx.opencodeAuth, poison)
        fx.write(fx.opencodeConfig, poison)
        fx.write(fx.claudeSettings, poison)
        val notes = OpenCodeConfigReader(fx.opencodeLocations()).read().notes +
            ClaudeConfigReader(fx.claudeLocations()).read().notes
        assertEquals(4, notes.size, "$notes")
        assertTrue(notes.all { it.endsWith("is unreadable: not valid JSON") }, "$notes")
        assertClean(notes.joinToString(), "notes")
    }

    @Test
    fun `a file that is valid json but not an object states a fixed reason`() {
        fx.write(fx.opencodeAuth, """["sk-LEAKTEST-123"]""")
        val notes = OpenCodeConfigReader(fx.opencodeLocations()).read().notes
        assertTrue(notes.any { it == "auth.json is unreadable: top level is not a JSON object" }, "$notes")
        assertClean(notes.joinToString(), "notes")
    }

    @Test
    fun `provider entries and captured log output carry no file text either`() {
        fx.write(fx.opencodeModels, ConfigFixture.MODELS_JSON)
        fx.write(fx.opencodeAuth, poison)
        fx.write(fx.opencodeConfig, ConfigFixture.CONFIG_JSONC)
        fx.write(fx.claudeSettings, poison)

        val outBytes = ByteArrayOutputStream()
        val errBytes = ByteArrayOutputStream()
        val oldOut = System.out
        val oldErr = System.err
        System.setOut(PrintStream(outBytes, true))
        System.setErr(PrintStream(errBytes, true))
        val entries = try {
            val oc = OpenCodeProvider(ConfigFirstModels(OpenCodeConfigReader(fx.opencodeLocations()), cli = { error("no cli") }))
            val cl = ClaudeCodeProvider(ClaudeConfigReader(fx.claudeLocations())::read)
            oc.knownModels()
            oc.models() + cl.models()
        } finally {
            System.setOut(oldOut)
            System.setErr(oldErr)
        }
        val shown = entries.joinToString { (it.note ?: "") + it.label }
        assertTrue("auth.json is unreadable: not valid JSON" in shown, shown)
        assertClean(shown, "models entries")
        assertClean(outBytes.toString() + errBytes.toString(), "captured stdout/stderr")
    }
}
