// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.mcp

import com.superiorsoftllc.infantry.harness.api.McpServerSpec
import com.superiorsoftllc.infantry.harness.api.McpSource
import com.superiorsoftllc.infantry.harness.api.McpTransport
import com.superiorsoftllc.infantry.protocol.McpGrantInput
import com.superiorsoftllc.infantry.protocol.McpServerInput
import com.superiorsoftllc.infantry.protocol.TicketMcpRequire
import com.superiorsoftllc.infantry.provider.ListingCache
import java.util.concurrent.Executor
import kotlin.test.Test
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** MCP-FAIL-1: a ticket's required MCP server is checked before the start: missing, not granted, then met. */
class McpPreflightTest {
    private val infantry = McpServerSpec("infantry", McpTransport.Stdio(listOf("infantry", "mcp", "serve")), McpSource.INFANTRY)
    private val ctx = McpSessionContext("", projectId = "p1", role = "backend", agentId = null, ticketId = "t-1")
    private val need = listOf(TicketMcpRequire(server = "docs", purpose = "API reference"))

    private fun service(): McpService {
        val storage = MemoryMcpStorage()
        val owned = McpOwnedServers(storage) { 10 }
        val registry = McpRegistry(
            owned = owned, harnessIds = { emptyList() }, harnessMcp = { null }, projectDir = { it ?: "home" }, secret = { _, _ -> null },
            cache = ListingCache(ttlMs = { 60_000 }, waitMs = { 1_000 }, executor = Executor { it.run() }), infantry = { infantry },
        )
        return McpService(registry, owned, McpGrants(storage, max = { 10 }, purposeChars = { 100 }), { null }, { true }, { _, _ -> null }, infantryFor = { infantry })
    }

    @Test
    fun `start is refused naming the missing server, then the missing grant, then passes`() {
        val svc = service()
        val notInstalled = svc.preflight(need, ctx)
        assertTrue(notInstalled!!.contains("server 'docs' is not installed") && notInstalled.contains("MCP Tools"), notInstalled)
        svc.owned.put(McpServerInput("docs", url = "https://docs.example/mcp"), null)
        val notGranted = svc.preflight(need, ctx)
        assertTrue(notGranted!!.contains("not granted"), notGranted)
        svc.grants.grant(McpGrantInput("docs", "ticket", "t-1", purpose = "API reference", approvedBy = "owner"))
        assertNull(svc.preflight(need, ctx))
    }
}
