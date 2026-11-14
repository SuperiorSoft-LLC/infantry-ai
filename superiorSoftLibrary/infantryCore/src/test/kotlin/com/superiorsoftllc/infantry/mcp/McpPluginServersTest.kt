// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.mcp

import com.superiorsoftllc.infantry.harness.api.McpServerSpec
import com.superiorsoftllc.infantry.harness.api.McpSource
import com.superiorsoftllc.infantry.harness.api.McpTransport
import com.superiorsoftllc.infantry.protocol.McpGrantInput
import com.superiorsoftllc.infantry.protocol.McpServerInput
import com.superiorsoftllc.infantry.provider.ListingCache
import java.util.concurrent.Executor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** MCP-PLUGIN-1: a plugin-declared MCP server is listed (source plugin), walled until granted, and a bad schema is refused. */
class McpPluginServersTest {
    private val infantry = McpServerSpec("infantry", McpTransport.Stdio(listOf("infantry", "mcp", "serve")), McpSource.INFANTRY)
    private val browser = McpServerInput("playwright", command = listOf("npx", "@playwright/mcp"), env = mapOf("MODE" to "x"), envSecrets = mapOf("PW_KEY" to "pw-key"), provides = listOf("browser"), localOnly = true)
    private val worker = McpSessionContext("cli-a", projectId = "p1", role = "backend", agentId = "agent-1", ticketId = "t-1")

    private fun service(plugins: McpPluginServers, secrets: Map<String, String> = mapOf("pw-key" to "s3cret")): McpService {
        val storage = MemoryMcpStorage()
        val owned = McpOwnedServers(storage) { 10 }
        val secret: (String, String?) -> String? = { n, _ -> secrets[n] }
        val registry = McpRegistry(
            owned = owned, harnessIds = { emptyList() }, harnessMcp = { null }, projectDir = { it ?: "home" }, secret = secret,
            cache = ListingCache(ttlMs = { 60_000 }, waitMs = { 1_000 }, executor = Executor { it.run() }),
            infantry = { infantry }, pluginSpecs = { plugins.specs(secret) },
        )
        return McpService(registry, owned, McpGrants(storage, max = { 10 }, purposeChars = { 100 }), { null }, { true }, secret, infantryFor = { infantry })
    }

    @Test
    fun `a declared server is in the registry as a plugin server, but in no plan without a grant`() {
        val plugins = McpPluginServers().also { it.declare("Infantry_Browser", listOf(browser)) }
        val svc = service(plugins)
        val e = svc.registry.find("playwright", "p1")!!
        assertEquals(McpSource.PLUGIN, e.source)
        assertEquals(setOf("browser"), e.provides)
        assertTrue(e.localOnly)
        assertEquals(listOf("infantry"), svc.plan(worker).plan.delivered.map { it.name })
        assertTrue(svc.plan(worker).reasons.getValue("playwright").startsWith("walled"))
        val wire = e.view().toString()
        assertTrue("s3cret" !in wire, "no secret value on the wire view")
    }

    @Test
    fun `a grant with a purpose delivers it, a localOnly one not to a remote runner`() {
        val svc = service(McpPluginServers().also { it.declare("Infantry_Browser", listOf(browser)) })
        svc.grants.grant(McpGrantInput("playwright", "ticket", "t-1", purpose = "store setup needs a browser", approvedBy = "owner"))
        assertEquals(listOf("infantry", "playwright"), svc.plan(worker).plan.delivered.map { it.name })
        assertEquals(listOf("infantry"), svc.plan(worker.copy(remote = true)).plan.delivered.map { it.name })
    }

    @Test
    fun `leaving drops the servers from the registry`() {
        val plugins = McpPluginServers().also { it.declare("Infantry_Browser", listOf(browser)) }
        val svc = service(plugins)
        plugins.drop("Infantry_Browser")
        assertEquals(null, svc.registry.find("playwright", "p1"))
    }

    @Test
    fun `a bad schema is refused with a clear error`() {
        val p = McpPluginServers()
        fun refused(vararg s: McpServerInput) = assertFailsWith<IllegalArgumentException> { p.declare("Acme", s.toList()) }.message!!
        assertTrue(refused(browser, browser).contains("declared twice"))
        assertTrue(refused(browser.copy(provides = emptyList())).contains("no capability tag"))
        assertTrue(refused(browser.copy(provides = listOf("Not A Tag"))).contains("capability tag"))
        assertTrue(refused(browser.copy(env = mapOf("API_KEY" to "plain"))).contains("looks like a credential"))
        assertTrue(refused(browser.copy(url = "https://x/mcp")).contains("exactly one"))
        assertTrue(refused(browser.copy(name = "bad name")).startsWith("daemon.mcpServers of Acme"))
        assertTrue(refused(browser.copy(name = "infantry")).contains("daemon's own"))
        p.declare("Infantry_Browser", listOf(browser))
        assertTrue(assertFailsWith<IllegalArgumentException> { p.declare("Acme", listOf(browser)) }.message!!.contains("already declared by Infantry_Browser"))
    }

    @Test
    fun `a server whose secret is not set is not offered`() {
        val svc = service(McpPluginServers().also { it.declare("Infantry_Browser", listOf(browser)) }, secrets = emptyMap())
        assertEquals(null, svc.registry.find("playwright", "p1"))
    }
}
