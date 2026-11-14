// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.cli

import com.superiorsoftllc.infantry.protocol.Envelope
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/** MCP-CLI-1: the `infantry mcp` verbs build the registry frames, and a worker session cannot change grants (no self-grant). */
class McpCliTest {
    @Test
    fun `usage accepts the verbs and refuses a grant without a purpose`() {
        assertNull(McpCli.usageError(listOf("serve"), null, null))
        assertNull(McpCli.usageError(listOf("list"), null, null))
        assertNull(McpCli.usageError(listOf("grant", "docs", "role", "qa"), null, "read docs"))
        assertNotNull(McpCli.usageError(listOf("grant", "docs", "role", "qa"), null, null))
        assertNotNull(McpCli.usageError(listOf("add", "infantry"), null, null))
        assertNotNull(McpCli.usageError(listOf("bogus"), null, null))
    }

    @Test
    fun `a worker session cannot grant itself or change servers, the PM and a person can`() {
        val worker = { k: String -> mapOf("INFANTRY_SESSION_ID" to "s1", "INFANTRY_ROLE" to "backend")[k] }
        val pm = { k: String -> mapOf("INFANTRY_SESSION_ID" to "s2", "INFANTRY_ROLE" to "pm")[k] }
        val person = { _: String -> null }
        assertNotNull(McpCli.changeRefusal("grant", worker))
        assertNotNull(McpCli.changeRefusal("add", worker))
        assertNull(McpCli.changeRefusal("list", worker))
        assertNull(McpCli.changeRefusal("grant", pm))
        assertNull(McpCli.changeRefusal("grant", person))
    }

    @Test
    fun `verbs map to the mcp frames`() {
        val g = McpCli.frame("grant", listOf("grant", "docs", "role", "qa"), "p1", null, "read docs", "owner")
        assertEquals(Envelope.MCP_ASSIGN, g.type)
        assertEquals("qa", g.req.grant!!.target)
        assertEquals("owner", g.req.grant!!.approvedBy)
        assertEquals(Envelope.MCP_REVOKE, McpCli.frame("revoke", listOf("revoke", "g1"), null, null, null, "owner").type)
        val d = McpCli.frame("disable", listOf("disable", "docs"), null, null, null, "owner")
        assertEquals(false, d.req.enabled)
        assertEquals(Envelope.MCP_SET_ENABLED, d.type)
    }
}
