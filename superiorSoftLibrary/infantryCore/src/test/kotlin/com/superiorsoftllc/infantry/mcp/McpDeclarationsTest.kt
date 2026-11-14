// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.mcp

import com.superiorsoftllc.infantry.harness.api.McpServerSpec
import com.superiorsoftllc.infantry.harness.api.McpServerState
import com.superiorsoftllc.infantry.harness.api.McpServerStatus
import com.superiorsoftllc.infantry.harness.api.McpSource
import com.superiorsoftllc.infantry.harness.api.McpTransport
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** MCP-SELECT-1: role / ticket / agent `mcp` blocks become registry grants; the selection stays walled and the gate holds. */
class McpDeclarationsTest {
    private val infantry = McpServerSpec("infantry", McpTransport.Stdio(listOf("infantry", "mcp", "serve")), McpSource.INFANTRY)
    private val pw = McpServerSpec("pw", McpTransport.Stdio(listOf("node", "pw.js")), McpSource.INFANTRY_USER)
    private val grants = McpGrants(MemoryMcpStorage(), max = { 20 }, purposeChars = { 100 })
    private val decl = McpDeclarations(grants)
    private val owner = McpDeclarer("owner")
    private val entries = listOf(McpEntry("pw", McpSource.INFANTRY_USER, "infantry", pw, McpServerStatus("pw", McpServerState.CONNECTED)))

    private fun ctx(role: String?, ticket: String?, agent: String? = null) =
        McpSessionContext("cli-a", projectId = "p", role = role, agentId = agent, ticketId = ticket)

    private fun delivered(c: McpSessionContext) =
        McpSelection.select(entries, grants.list(), c, infantry, loadsUserMcp = true).plan.delivered.map { it.name }

    @Test
    fun `no field means walled`() {
        assertEquals(listOf("infantry"), delivered(ctx("backend", "t-1")))
    }

    @Test
    fun `a ticket require reaches only that ticket's session`() {
        decl.declare(McpGrantScope.TICKET, "t-1", null, McpBlock(listOf("pw"), "browser check"), owner)
        assertEquals(listOf("infantry", "pw"), delivered(ctx("backend", "t-1")))
        assertEquals(listOf("infantry"), delivered(ctx("backend", "t-2")))
    }

    @Test
    fun `a role block reaches every session of that role`() {
        decl.declare(McpGrantScope.ROLE, "qa", null, McpBlock(listOf("pw"), "UI checks"), owner)
        assertEquals(listOf("infantry", "pw"), delivered(ctx("qa", "t-1")))
        assertEquals(listOf("infantry", "pw"), delivered(ctx("qa", "t-9")))
        assertEquals(listOf("infantry"), delivered(ctx("backend", "t-1")))
    }

    @Test
    fun `an agent override reaches that agent only, and a smaller block revokes`() {
        decl.declare(McpGrantScope.AGENT, "a-1", null, McpBlock(listOf("pw"), "demo"), owner)
        assertEquals(listOf("infantry", "pw"), delivered(ctx("x", null, "a-1")))
        assertEquals(listOf("infantry"), delivered(ctx("x", null, "a-2")))
        decl.declare(McpGrantScope.AGENT, "a-1", null, McpBlock(), owner)
        assertEquals(listOf("infantry"), delivered(ctx("x", null, "a-1")))
    }

    @Test
    fun `a grant records purpose and approver, and a purpose is required`() {
        val g = decl.declare(McpGrantScope.TICKET, "t-1", null, McpBlock(listOf("pw"), "browser check"), McpDeclarer("pm")).single()
        assertEquals("pm", g.approvedBy)
        assertEquals("browser check", g.purpose)
        assertFailsWith<IllegalArgumentException> { decl.declare(McpGrantScope.TICKET, "t-2", null, McpBlock(listOf("pw"), " "), owner) }
    }

    @Test
    fun `a worker cannot grant to its own role, agent or ticket`() {
        val worker = McpDeclarer("agent-4c", ctx("backend", "t-1", "a-1"))
        for ((scope, target) in listOf(McpGrantScope.ROLE to "backend", McpGrantScope.TICKET to "t-1", McpGrantScope.AGENT to "a-1")) {
            val e = assertFailsWith<IllegalArgumentException> { decl.declare(scope, target, null, McpBlock(listOf("pw"), "x"), worker) }
            assertTrue(e.message!!.contains("cannot grant"), e.message)
        }
        assertTrue(grants.list().isEmpty())
    }
}
