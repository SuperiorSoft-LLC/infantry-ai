// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.mcp

import com.superiorsoftllc.infantry.harness.api.McpServerSpec
import com.superiorsoftllc.infantry.harness.api.McpSource
import com.superiorsoftllc.infantry.harness.api.McpTransport
import com.superiorsoftllc.infantry.protocol.McpGrantInput
import com.superiorsoftllc.infantry.protocol.McpServerInput
import com.superiorsoftllc.infantry.provider.ListingCache
import java.io.File
import java.util.concurrent.Executor
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** BROWSER-1: Infantry_Browser's real module.json declares a valid `browser` server, walled until a grant puts it in a session's plan. */
class BrowserPluginDeclarationTest {
    private val json = Json { ignoreUnknownKeys = true }

    private fun manifest(): JsonObject {
        var dir: File? = File("").absoluteFile
        while (dir != null && !File(dir, "plugins/official/Infantry_Browser/module.json").exists()) dir = dir.parentFile
        return json.parseToJsonElement(File(dir!!, "plugins/official/Infantry_Browser/module.json").readText()).jsonObject
    }

    private fun declared(): List<McpServerInput> =
        manifest().getValue("daemon").jsonObject.getValue("mcpServers").jsonArray
            .map { json.decodeFromJsonElement(McpServerInput.serializer(), it) }

    private val infantry = McpServerSpec("infantry", McpTransport.Stdio(listOf("infantry", "mcp", "serve")), McpSource.INFANTRY)
    private val worker = McpSessionContext("cli-a", projectId = "p1", role = "backend", agentId = "agent-1", ticketId = "t-1")

    private fun service(plugins: McpPluginServers): McpService {
        val storage = MemoryMcpStorage()
        val secret: (String, String?) -> String? = { _, _ -> null }
        val registry = McpRegistry(
            owned = McpOwnedServers(storage) { 10 }, harnessIds = { emptyList() }, harnessMcp = { null }, projectDir = { it ?: "home" }, secret = secret,
            cache = ListingCache(ttlMs = { 60_000 }, waitMs = { 1_000 }, executor = Executor { it.run() }),
            infantry = { infantry }, pluginSpecs = { plugins.specs(secret) },
        )
        return McpService(registry, McpOwnedServers(storage) { 10 }, McpGrants(storage, max = { 10 }, purposeChars = { 100 }), { null }, { true }, secret, infantryFor = { infantry })
    }

    @Test
    fun `the manifest declares a valid browser server with capability browser, isolated and pinned`() {
        val servers = declared()
        assertEquals(listOf("browser"), servers.map { it.name })
        val s = servers.single()
        assertEquals(listOf("browser"), s.provides)
        assertFalse(s.localOnly, "runner-ready")
        assertTrue("--isolated" in s.command, "never the owner's Chrome profile")
        assertTrue("--headless" !in s.command, "headed by default")
        assertTrue(s.command.any { it.startsWith("@playwright/mcp@") }, "pinned version")
        McpPluginServers.validate("Infantry_Browser", servers)
    }

    @Test
    fun `not in any plan without a grant, a ticket grant puts it in that session's plan, plugin source`() {
        val plugins = McpPluginServers().also { it.declare("Infantry_Browser", declared()) }
        val svc = service(plugins)
        assertEquals(McpSource.PLUGIN, svc.registry.find("browser", "p1")!!.source)
        assertEquals(listOf("infantry"), svc.plan(worker).plan.delivered.map { it.name })
        svc.grants.grant(McpGrantInput("browser", "ticket", "t-1", purpose = "store setup needs a browser", approvedBy = "owner"))
        assertEquals(listOf("infantry", "browser"), svc.plan(worker).plan.delivered.map { it.name })
        assertEquals(listOf("infantry"), svc.plan(worker.copy(ticketId = "t-2")).plan.delivered.map { it.name })
    }
}
