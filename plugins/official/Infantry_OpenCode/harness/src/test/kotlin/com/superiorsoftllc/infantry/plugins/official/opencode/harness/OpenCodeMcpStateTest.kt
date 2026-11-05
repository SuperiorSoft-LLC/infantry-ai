// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.plugins.official.opencode.harness

import com.superiorsoftllc.infantry.harness.api.McpState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** HARNESS-GAP G12: opencode's `GET /mcp` answer maps to the session's MCP state. */
class OpenCodeMcpStateTest {
    @Test
    fun mapsTheInfantryServerStatus() {
        assertEquals(McpState.CONNECTED, OpenCodeServeSession.mcpStateOf("""{"infantry":{"status":"connected"}}"""))
        assertEquals(McpState.DISCONNECTED, OpenCodeServeSession.mcpStateOf("""{"infantry":{"status":"failed","error":"x"}}"""))
        assertEquals(McpState.DISCONNECTED, OpenCodeServeSession.mcpStateOf("""{"infantry":{"status":"disabled"}}"""))
        assertNull(OpenCodeServeSession.mcpStateOf("""{"other":{"status":"connected"}}"""))
    }
}
