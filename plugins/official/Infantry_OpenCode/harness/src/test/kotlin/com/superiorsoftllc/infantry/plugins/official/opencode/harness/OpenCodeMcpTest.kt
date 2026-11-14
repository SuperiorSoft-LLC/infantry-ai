// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.plugins.official.opencode.harness

import com.superiorsoftllc.infantry.harness.api.McpPlan
import com.superiorsoftllc.infantry.harness.api.McpServerSpec
import com.superiorsoftllc.infantry.harness.api.McpServerState
import com.superiorsoftllc.infantry.harness.api.McpSource
import com.superiorsoftllc.infantry.harness.api.McpTransport
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** MCP-OPENCODE-1: list fixture, no grant = only infantry enabled, a grant enables exactly that server. */
class OpenCodeMcpTest {
    private val list = "┌  MCP Servers\n│\n●  ✓ github connected\n│      npx -y @modelcontextprotocol/server-github\n│\n●  ⚠ sentry needs authentication\n│      https://mcp.sentry.dev/mcp\n│\n●  ✗ broken failed\n│      node server.js\n│\n└  3 servers\n"

    @Test
    fun parsesTheListFixture() {
        val l = OpenCodeMcp.parseList(list)
        assertEquals(listOf("github", "sentry", "broken"), l.map { it.spec.name })
        assertEquals(listOf(McpServerState.CONNECTED, McpServerState.NEEDS_AUTH, McpServerState.FAILED), l.map { it.status.state })
        assertEquals(McpTransport.Http("https://mcp.sentry.dev/mcp"), l[1].spec.transport)
        assertEquals(listOf("npx", "-y", "@modelcontextprotocol/server-github"), (l[0].spec.transport as McpTransport.Stdio).command)
    }

    @Test
    fun isReadOnly() {
        val m = OpenCodeMcp { _, _ -> 0 to list }
        assertFalse(m.canAdd || m.canRemove || m.canEdit)
        assertEquals("read-only: manage in opencode", OpenCodeMcp.READ_ONLY)
    }

    private fun enabled(config: String, name: String) =
        ((Json.parseToJsonElement(config) as JsonObject)["mcp"] as JsonObject)[name]?.let { ((it as JsonObject)["enabled"] as? JsonPrimitive)?.content }

    private val users = listOf("github", "sentry", "broken")
    private val infantry = McpServerSpec("infantry", McpTransport.Stdio(listOf("infantry", "mcp")), McpSource.INFANTRY)

    @Test
    fun noGrantDisablesEveryUserServer() {
        val plan = McpPlan(add = listOf(infantry))
        val off = OpenCodeMcp.toDisable(users, plan)
        assertEquals(users.toSet(), off)
        val cfg = OpenCodeConfig.mergePlan("{}", plan, off)
        users.forEach { assertEquals("false", enabled(cfg, it)) }
        assertEquals(null, enabled(cfg, "infantry"))
    }

    @Test
    fun grantEnablesExactlyThatServer() {
        val plan = McpPlan(add = listOf(infantry, McpServerSpec("github", McpTransport.Stdio(listOf("npx")), McpSource.HARNESS)))
        val off = OpenCodeMcp.toDisable(users, plan)
        assertEquals(setOf("sentry", "broken"), off)
        val cfg = OpenCodeConfig.mergePlan("{}", plan, off)
        assertEquals(null, enabled(cfg, "github"))
        assertEquals("false", enabled(cfg, "sentry"))
        assertTrue(enabled(cfg, "broken") == "false")
    }

    @Test
    fun parsesGetMcpStatuses() {
        val s = OpenCodeMcp.statusesOf("""{"infantry":{"status":"connected"},"x":{"status":"needs_auth"},"y":{"status":"failed","error":"boom"}}""")
        assertEquals(listOf(McpServerState.CONNECTED, McpServerState.NEEDS_AUTH, McpServerState.FAILED), s.map { it.state })
    }
}
