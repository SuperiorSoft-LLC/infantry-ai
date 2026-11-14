// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.plugins.official.claude.harness

import com.superiorsoftllc.infantry.harness.api.McpPlan
import com.superiorsoftllc.infantry.harness.api.McpServerSpec
import com.superiorsoftllc.infantry.harness.api.McpServerState
import com.superiorsoftllc.infantry.harness.api.McpSource
import com.superiorsoftllc.infantry.harness.api.McpTransport
import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.startCoroutine
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ClaudeMcpTest {
    private val list = """
        Checking MCP server health...

        github: npx -y @modelcontextprotocol/server-github - ✓ Connected
        sentry: https://mcp.sentry.dev/mcp (HTTP) - ⚠ Needs authentication
        broken: node server.js --port 1 - ✗ Failed to connect
    """.trimIndent()

    private val getStdio = """
        github:
          Scope: User config (available in all your projects)
          Status: ✓ Connected
          Type: stdio
          Command: npx
          Args: -y @modelcontextprotocol/server-github
          Environment:
            GITHUB_TOKEN=ghp_secret123

        To remove this server, run: claude mcp remove "github" -s user
    """.trimIndent()

    private val getHttp = """
        sentry:
          Scope: Local config (private to you in this project)
          Status: ⚠ Needs authentication
          Type: http
          URL: https://mcp.sentry.dev/mcp
          Headers:
            Authorization: Bearer abc
    """.trimIndent()

    @Test
    fun `parses the list fixture`() {
        val l = ClaudeMcp.parseList(list)
        assertEquals(listOf("github", "sentry", "broken"), l.map { it.spec.name })
        assertEquals(listOf(McpServerState.CONNECTED, McpServerState.NEEDS_AUTH, McpServerState.FAILED), l.map { it.status.state })
        assertEquals(listOf("npx", "-y", "@modelcontextprotocol/server-github"), (l[0].spec.transport as McpTransport.Stdio).command)
        assertEquals("https://mcp.sentry.dev/mcp", (l[1].spec.transport as McpTransport.Http).url)
        assertTrue(l.all { it.spec.source == McpSource.HARNESS })
    }

    @Test
    fun `get masks env and header values, delivery keeps them`() {
        val (masked, status) = ClaudeMcp.parseGet("github", getStdio, mask = true)!!
        assertEquals("user", masked.scope)
        assertEquals(McpServerState.CONNECTED, status.state)
        assertEquals(mapOf("GITHUB_TOKEN" to "***"), (masked.transport as McpTransport.Stdio).env)
        assertFalse(masked.toString().contains("ghp_secret123"))
        val (real, _) = ClaudeMcp.parseGet("github", getStdio, mask = false)!!
        assertEquals("ghp_secret123", (real.transport as McpTransport.Stdio).env["GITHUB_TOKEN"])
        val (http, hs) = ClaudeMcp.parseGet("sentry", getHttp, mask = true)!!
        assertEquals("local", http.scope)
        assertEquals(McpServerState.NEEDS_AUTH, hs.state)
        assertEquals(mapOf("Authorization" to "***"), (http.transport as McpTransport.Http).headers)
        assertNull(ClaudeMcp.parseGet("x", "x:\n  Scope: User\n", mask = true))
    }

    @Test
    fun `plan with no grants is infantry only, a grant adds exactly that server`() {
        val infantry = McpServerSpec("infantry", McpTransport.Stdio(listOf("infantry", "mcp"), mapOf("K" to "v")), McpSource.INFANTRY)
        val none = McpPlan(add = listOf(infantry), deny = setOf("github", "sentry"))
        assertEquals(setOf("infantry"), servers(ClaudeMcp.configJson(none) { error("no lookup") }))
        assertEquals(listOf("mcp__github__*", "mcp__sentry__*"), ClaudeMcp.disallowed(none))
        val github = McpServerSpec("github", McpTransport.Stdio(listOf("npx")), McpSource.HARNESS)
        val granted = McpPlan(add = listOf(infantry, github), deny = setOf("sentry"))
        val json = ClaudeMcp.configJson(granted) { ClaudeMcp.parseGet(it, getStdio, mask = false)?.first }
        assertEquals(setOf("infantry", "github"), servers(json))
        assertTrue(json.contains("ghp_secret123"))
        assertEquals(listOf("mcp__sentry__*"), ClaudeMcp.disallowed(granted))
        // a granted CLI server whose definition cannot be read is left out, never guessed
        assertEquals(setOf("infantry"), servers(ClaudeMcp.configJson(granted) { null }))
    }

    @Test
    fun `commands are the official claude mcp ones`() = runSuspend {
        val calls = mutableListOf<List<String>>()
        val mcp = ClaudeMcp({ args, _ -> calls += args; (if (args[1] == "get") 0 to getStdio else 0 to list) }, windows = false)
        val spec = McpServerSpec("github", McpTransport.Stdio(listOf("npx", "-y", "x"), mapOf("T" to "s")), McpSource.INFANTRY_USER)
        assertTrue(mcp.add("/p", spec, "user").isSuccess)
        assertTrue(mcp.remove("/p", "github", "user").isSuccess)
        assertTrue(mcp.edit("/p", spec, "user", null).isSuccess)
        assertEquals(listOf("mcp", "add-json", "-s", "user", "github"), calls[0].take(5))
        assertEquals(listOf("mcp", "remove", "-s", "user", "github"), calls[1])
        assertEquals(listOf("remove", "add-json"), calls.drop(2).map { it[1] })
        assertTrue(mcp.add("/p", spec, "bogus").isFailure)
        assertEquals(McpServerState.CONNECTED, mcp.test("/p", "github").state)
        assertEquals(listOf("mcp", "get", "github"), calls.last())
        assertEquals(3, mcp.list("/p").size)
        assertEquals(listOf("mcp", "list"), calls.last())
    }

    @Test
    fun `init record becomes session status`() {
        val rec = kotlinx.serialization.json.Json.parseToJsonElement(
            "{\"type\":\"system\",\"subtype\":\"init\",\"mcp_servers\":[{\"name\":\"infantry\",\"status\":\"connected\"},{\"name\":\"gh\",\"status\":\"failed\"}]}",
        ) as kotlinx.serialization.json.JsonObject
        val s = ClaudeMcp.initStatuses(rec)
        assertEquals(listOf(McpServerState.CONNECTED, McpServerState.FAILED), s.map { it.state })
    }

    @Test
    fun `argv carries disallowedTools`() {
        val argv = ClaudeCommand.command("claude", null, null, emptyList(), true, null, disallowedTools = listOf("mcp__a__*", "mcp__b__*"))
        assertEquals("mcp__a__*,mcp__b__*", argv[argv.indexOf("--disallowedTools") + 1])
        assertFalse(ClaudeCommand.command("claude", null, null, emptyList(), true, null).contains("--disallowedTools"))
    }

    /** The harness API has no coroutines library on the test path: the fake runner never suspends, so the block finishes inline. */
    private fun runSuspend(block: suspend () -> Unit) {
        var failure: Throwable? = null
        var done = false
        block.startCoroutine(Continuation(EmptyCoroutineContext) { r -> failure = r.exceptionOrNull(); done = true })
        failure?.let { throw it }
        check(done) { "the block suspended" }
    }

    private fun servers(json: String): Set<String> =
        (kotlinx.serialization.json.Json.parseToJsonElement(json) as kotlinx.serialization.json.JsonObject).let {
            (it["mcpServers"] as kotlinx.serialization.json.JsonObject).keys
        }
}
