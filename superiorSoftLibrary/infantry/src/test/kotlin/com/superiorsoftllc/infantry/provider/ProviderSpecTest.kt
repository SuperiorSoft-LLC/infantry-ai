package com.superiorsoftllc.infantry.provider

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
    fun factoryHasNoOpenCodeDefaultAnyMore() {
        // H8b-3: with no harness module loaded the default is the zero-harness provider, never opencode.
        assertEquals("none", ProviderFactory.fromFlag(null).id)
        assertTrue(runCatching { ProviderFactory.fromFlag("opencode") }.exceptionOrNull() is IllegalStateException)
    }

    @Test
    fun factoryRejectsUnknown() {
        assertTrue(runCatching { ProviderFactory.fromFlag("gemini") }.exceptionOrNull() is IllegalStateException)
    }

    // --- claude argv ---------------------------------------------------------




    // --- opencode argv: H8b-3 moved to the Infantry_OpenCode plugin (OpenCodeMovedTest) ---

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
