package com.superiorsoftllc.agenthq.provider

import com.superiorsoftllc.agenthq.opencode.OpenCodeCommand
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Phase 2 evidence: both providers compose argv and parse session ids behind one seam, with no
 * change to `ProcessRunner` or `Envelope`. Pure functions only -- no CLI needs to be installed.
 */
class ProviderSpecTest {

    // --- selection -----------------------------------------------------------

    @Test
    fun factoryDefaultsToOpenCode() {
        assertEquals("opencode", ProviderFactory.fromFlag(null).id)
        assertEquals("opencode", ProviderFactory.fromFlag("").id)
        assertEquals("opencode", ProviderFactory.fromFlag("opencode").id)
    }

    @Test
    fun factoryRejectsUnknown() {
        assertTrue(runCatching { ProviderFactory.fromFlag("gemini") }.exceptionOrNull() is IllegalStateException)
    }

    // --- claude argv ---------------------------------------------------------




    // --- opencode argv -------------------------------------------------------

    @Test
    fun openCodeBaselineArgv() {
        val argv = OpenCodeCommand.command("opencode.exe", "opencode/space-bunny-free", null)
        assertEquals(
            listOf("opencode.exe", "run", "--format", "json", "-m", "opencode/space-bunny-free"),
            argv,
        )
    }

    @Test
    fun openCodeResumeAddsSession() {
        val argv = OpenCodeCommand.command("opencode.exe", "opencode/space-bunny-free", "ses_123")
        assertEquals(
            listOf(
                "opencode.exe", "run", "--format", "json",
                "-m", "opencode/space-bunny-free", "--session", "ses_123",
            ),
            argv,
        )
    }

    // --- session-id extraction ----------------------------------------------

    @Test
    fun sessionIdReadsBothKeyStyles() {
        assertEquals("camel", SessionId.fromLine("{\"type\":\"step_start\",\"sessionID\":\"camel\"}"))
        assertEquals("snake", SessionId.fromLine("{\"type\":\"system\",\"session_id\":\"snake\"}"))
    }

    @Test
    fun sessionIdToleratesNonSessionsAndGarbage() {
        assertNull(SessionId.fromLine("not json at all"))
        assertNull(SessionId.fromLine("{\"type\":\"text\",\"part\":{\"text\":\"hi\"}}"))
        assertNull(SessionId.fromLine("{\"session_id\":\"\"}"))
        assertNull(SessionId.fromLine("[]"))
    }

    // --- mcp config parsing ---------------------------------------------------

}
