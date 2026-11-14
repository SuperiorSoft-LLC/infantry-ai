// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.mcp

import com.superiorsoftllc.infantry.harness.api.HarnessMcp
import com.superiorsoftllc.infantry.harness.api.McpListing
import com.superiorsoftllc.infantry.harness.api.McpServerSpec
import com.superiorsoftllc.infantry.harness.api.McpServerState
import com.superiorsoftllc.infantry.harness.api.McpServerStatus
import com.superiorsoftllc.infantry.harness.api.McpSource
import com.superiorsoftllc.infantry.harness.api.McpTransport
import com.superiorsoftllc.infantry.protocol.McpGrantInput
import com.superiorsoftllc.infantry.protocol.McpServerInput
import com.superiorsoftllc.infantry.provider.ListingCache
import java.util.concurrent.Executor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** MCP-REGISTRY-1: the walled selection (no grant = nothing), grant scopes, localOnly on a remote runner, shadowing, secrets. */
class McpSelectionTest {
    private val direct = Executor { it.run() }
    private val infantry = McpServerSpec("infantry", McpTransport.Stdio(listOf("infantry", "mcp", "serve")), McpSource.INFANTRY)

    private class FakeCli(var rows: List<McpListing>) : HarnessMcp {
        var lists = 0
        override suspend fun list(project: String): List<McpListing> { lists++; return rows }
    }

    private fun cliServer(name: String, localOnly: Boolean = false) = McpListing(
        McpServerSpec(name, McpTransport.Stdio(listOf("node", "$name.js")), McpSource.HARNESS, scope = "user", localOnly = localOnly),
        McpServerStatus(name, McpServerState.CONNECTED, toolCount = 3),
    )

    private class World(val storage: McpStorage = MemoryMcpStorage(), val secrets: MutableMap<String, String> = mutableMapOf())

    private fun service(world: World, cli: FakeCli, loadsUserMcp: Boolean = true): McpService {
        val owned = McpOwnedServers(world.storage) { 10 }
        val secret: (String, String?) -> String? = { n, _ -> world.secrets[n] }
        val registry = McpRegistry(
            owned = owned, harnessIds = { listOf("cli-a") }, harnessMcp = { if (it == "cli-a") cli else null },
            projectDir = { it ?: "home" }, secret = secret,
            cache = ListingCache(ttlMs = { 60_000 }, waitMs = { 1_000 }, executor = direct),
            infantry = { infantry },
        )
        return McpService(
            registry, owned, McpGrants(world.storage, max = { 10 }, purposeChars = { 100 }), harnessMcp = { if (it == "cli-a") cli else null },
            loadsUserMcp = { loadsUserMcp }, secret = secret, infantryFor = { infantry },
        )
    }

    private val worker = McpSessionContext("cli-a", projectId = "p1", role = "backend", agentId = "agent-1", ticketId = "t-1")

    @Test
    fun `no grant means nothing but the daemon's own server, and the CLI's own servers are denied`() {
        val world = World()
        val svc = service(world, FakeCli(listOf(cliServer("playwright"), cliServer("github"))))
        svc.owned.put(McpServerInput("docs", url = "https://docs.example/mcp"), null)
        val r = svc.plan(worker)
        assertEquals(listOf("infantry"), r.plan.delivered.map { it.name })
        assertEquals(setOf("playwright", "github"), r.plan.deny)
        assertTrue(r.reasons.getValue("docs").startsWith("walled"), r.reasons.toString())
        assertTrue(r.reasons.getValue("playwright").startsWith("walled"), r.reasons.toString())
    }

    @Test
    fun `a ticket grant applies only to that ticket`() {
        val world = World()
        val svc = service(world, FakeCli(listOf(cliServer("playwright"))))
        svc.grants.grant(McpGrantInput("playwright", "ticket", "t-1", purpose = "store setup needs a browser", approvedBy = "owner"))
        val mine = svc.plan(worker)
        assertFalse("playwright" in mine.plan.deny, "granted to this ticket: the CLI loads it itself, nothing denies it")
        assertTrue(mine.reasons.getValue("playwright").contains("granted to ticket t-1"), mine.reasons.toString())
        val other = svc.plan(worker.copy(ticketId = "t-2"))
        assertEquals(setOf("playwright"), other.plan.deny)
        val none = svc.plan(worker.copy(ticketId = null))
        assertEquals(setOf("playwright"), none.plan.deny)
    }

    @Test
    fun `a role grant delivers an Infantry-owned server to that role only, within the grant's project`() {
        val world = World()
        val svc = service(world, FakeCli(emptyList()))
        svc.owned.put(McpServerInput("docs", url = "https://docs.example/mcp", provides = listOf("docs")), null)
        svc.grants.grant(McpGrantInput("docs", "role", "backend", projectId = "p1", purpose = "API reference", approvedBy = "owner"))
        assertEquals(listOf("infantry", "docs"), svc.plan(worker).plan.delivered.map { it.name })
        assertEquals(listOf("infantry"), svc.plan(worker.copy(role = "frontend")).plan.delivered.map { it.name })
        assertEquals(listOf("infantry"), svc.plan(worker.copy(projectId = "p2")).plan.delivered.map { it.name })
    }

    @Test
    fun `an agent grant applies to that agent`() {
        val world = World()
        val svc = service(world, FakeCli(emptyList()))
        svc.owned.put(McpServerInput("docs", url = "https://docs.example/mcp"), null)
        svc.grants.grant(McpGrantInput("docs", "agent", "agent-1", purpose = "API reference", approvedBy = "owner"))
        assertEquals(listOf("infantry", "docs"), svc.plan(worker).plan.delivered.map { it.name })
        assertEquals(listOf("infantry"), svc.plan(worker.copy(agentId = "agent-2")).plan.delivered.map { it.name })
    }

    @Test
    fun `localOnly servers are filtered for a remote runner even when granted`() {
        val world = World()
        val svc = service(world, FakeCli(listOf(cliServer("localtool", localOnly = true))))
        svc.owned.put(McpServerInput("pcfiles", command = listOf("C:\\tools\\files.cmd"), localOnly = true), null)
        svc.grants.grant(McpGrantInput("pcfiles", "role", "backend", purpose = "files", approvedBy = "owner"))
        svc.grants.grant(McpGrantInput("localtool", "role", "backend", purpose = "tool", approvedBy = "owner"))
        val local = svc.plan(worker)
        assertEquals(listOf("infantry", "pcfiles"), local.plan.delivered.map { it.name })
        assertTrue(local.plan.deny.isEmpty())
        val remote = svc.plan(worker.copy(remote = true))
        assertEquals(listOf("infantry"), remote.plan.delivered.map { it.name })
        assertEquals(setOf("localtool"), remote.plan.deny)
        assertTrue(remote.reasons.getValue("pcfiles").startsWith("local only"), remote.reasons.toString())
    }

    @Test
    fun `a disabled server and one whose secret is not set are never delivered`() {
        val world = World()
        val svc = service(world, FakeCli(emptyList()))
        svc.owned.put(McpServerInput("off", url = "https://off.example/mcp", enabled = false), null)
        svc.owned.put(McpServerInput("gh", url = "https://gh.example/mcp", headerSecrets = mapOf("Authorization" to "gh-token")), null)
        listOf("off", "gh").forEach { svc.grants.grant(McpGrantInput(it, "role", "backend", purpose = "x", approvedBy = "owner")) }
        val before = svc.plan(worker)
        assertEquals(listOf("infantry"), before.plan.delivered.map { it.name })
        assertEquals("disabled", before.reasons["off"])
        assertTrue(before.reasons.getValue("gh").contains("secret 'gh-token'"), before.reasons.toString())
        world.secrets["gh-token"] = "Bearer s3cr3t"
        val after = svc.plan(worker).plan.delivered.first { it.name == "gh" }
        assertEquals("Bearer s3cr3t", (after.transport as McpTransport.Http).headers["Authorization"])
    }

    @Test
    fun `an Infantry server shadows the CLI's of the same name, and a CLI that does not load user servers gets granted specs delivered`() {
        val world = World()
        val svc = service(world, FakeCli(listOf(cliServer("docs"), cliServer("lint"))), loadsUserMcp = false)
        svc.owned.put(McpServerInput("docs", url = "https://docs.example/mcp"), null)
        svc.grants.grant(McpGrantInput("docs", "role", "backend", purpose = "x", approvedBy = "owner"))
        svc.grants.grant(McpGrantInput("lint", "role", "backend", purpose = "x", approvedBy = "owner"))
        val catalog = svc.registry.catalog("p1", "cli-a")
        val shadowed = catalog.entries.single { it.name == "docs" && it.source == McpSource.HARNESS }
        assertEquals(McpSource.INFANTRY_USER, shadowed.shadowedBy)
        assertEquals("infantry_user", shadowed.view().shadowedBy)
        val r = svc.plan(worker)
        assertEquals(listOf("infantry", "docs", "lint"), r.plan.delivered.map { it.name })
        assertEquals(McpSource.INFANTRY_USER, r.plan.delivered.single { it.name == "docs" }.source)
        assertFalse("docs" in r.plan.deny, "denying the shadowed CLI server's name would deny the delivered winner too")
    }

    @Test
    fun `the wire view never carries a secret value`() {
        val world = World(secrets = mutableMapOf("k" to "VALUE-123"))
        val svc = service(world, FakeCli(emptyList()))
        svc.owned.put(McpServerInput("s", command = listOf("node", "s.js"), env = mapOf("MODE" to "fast"), envSecrets = mapOf("API_TOKEN" to "k")), null)
        val view = svc.registry.find("s", null)!!.view()
        assertEquals(listOf("API_TOKEN", "MODE"), view.envKeys)
        val wire = svc.handle(com.superiorsoftllc.infantry.protocol.Envelope.MCP_LIST, "{}").data
        assertFalse("VALUE-123" in wire, wire)
        assertFalse("fast" in wire, "plain env values stay off the wire too: $wire")
    }

    @Test
    fun `the CLI list is cached and the plan frame explains a session`() {
        val world = World()
        val cli = FakeCli(listOf(cliServer("playwright")))
        val svc = service(world, cli)
        repeat(3) { svc.registry.catalog("p1") }
        assertEquals(1, cli.lists, "one CLI listing serves every request within limits.mcp.listTtlMs")
        val answer = svc.handle(com.superiorsoftllc.infantry.protocol.Envelope.MCP_PLAN, """{"target":"cli-a","projectId":"p1","role":"backend"}""")
        assertTrue(answer.data.contains("\"deny\":[\"playwright\"]"), answer.data)
        assertNull(svc.registry.find("nope", "p1"))
    }
}
