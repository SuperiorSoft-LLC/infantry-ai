package com.superiorsoftllc.agenthq.cli.mcp

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** RUNTIME-LOAD-2 (F1): a plugin tool is waited for as long as its own declared timeout, a quarter longer. */
class McpPluginToolTimeoutTest {
    @Test fun `a tool without a declared timeout waits the global default plus a quarter`() {
        assertEquals(75_000L, McpPluginTools.answerWaitMs(null))
    }

    @Test fun `a long tool waits its own timeout plus a quarter, not the global one`() {
        assertEquals(1_125_000L, McpPluginTools.answerWaitMs(900_000L))
        assertTrue(McpPluginTools.answerWaitMs(900_000L) > McpPluginTools.answerWaitMs(null))
    }
}
