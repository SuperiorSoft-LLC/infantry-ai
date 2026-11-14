// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.harness

import com.superiorsoftllc.infantry.daemon.InfantryMcp
import com.superiorsoftllc.infantry.harness.api.HARNESS_API_VERSION
import com.superiorsoftllc.infantry.harness.api.Harness
import com.superiorsoftllc.infantry.harness.api.HarnessAnswer
import com.superiorsoftllc.infantry.harness.api.HarnessCapability
import com.superiorsoftllc.infantry.harness.api.HarnessHost
import com.superiorsoftllc.infantry.harness.api.HarnessMcp
import com.superiorsoftllc.infantry.harness.api.HarnessProcess
import com.superiorsoftllc.infantry.harness.api.HarnessProcessListener
import com.superiorsoftllc.infantry.harness.api.HarnessQuestion
import com.superiorsoftllc.infantry.harness.api.HarnessRequest
import com.superiorsoftllc.infantry.harness.api.HarnessRunningProcess
import com.superiorsoftllc.infantry.harness.api.HarnessTranslator
import com.superiorsoftllc.infantry.harness.api.INFANTRY_MCP_SERVER
import com.superiorsoftllc.infantry.harness.api.McpPlan
import com.superiorsoftllc.infantry.harness.api.McpServerSpec
import com.superiorsoftllc.infantry.harness.api.McpServerState
import com.superiorsoftllc.infantry.harness.api.McpServerStatus
import com.superiorsoftllc.infantry.harness.api.McpSource
import com.superiorsoftllc.infantry.harness.api.McpTransport
import com.superiorsoftllc.infantry.harness.api.SessionOutput
import com.superiorsoftllc.infantry.harness.api.SessionTurnEnd
import kotlinx.coroutines.runBlocking
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** MCP-SPEC-1: API 2.19.0 harness-neutral MCP types; every new member defaults to today's behaviour. */
class McpSpecTest {
    @Test fun infantryServerNameMatchesTheDaemon() = assertEquals(InfantryMcp.SERVER_NAME, INFANTRY_MCP_SERVER)

    @Test fun secretsNeverPrint() {
        val stdio = McpServerSpec("gh", McpTransport.Stdio(listOf("gh-mcp"), mapOf("GH_TOKEN" to "s3cret-token")), McpSource.INFANTRY_USER)
        val http = McpServerSpec("api", McpTransport.Http("https://x.example/mcp", mapOf("Authorization" to "Bearer s3cret-bearer")), McpSource.HARNESS, scope = "user")
        for (spec in listOf(stdio, http)) assertFalse("s3cret" in spec.toString(), spec.toString())
        assertTrue("GH_TOKEN" in stdio.toString())
        assertTrue("Authorization" in http.toString())
    }

    @Test fun specDefaultsAndValidation() {
        val spec = McpServerSpec("x", McpTransport.Stdio(listOf("x")), McpSource.PLUGIN)
        assertNull(spec.scope)
        assertTrue(spec.provides.isEmpty())
        assertFalse(spec.localOnly)
        assertFailsWith<IllegalArgumentException> { McpServerSpec(" ", McpTransport.Stdio(listOf("x")), McpSource.PLUGIN) }
        assertFailsWith<IllegalArgumentException> { McpTransport.Stdio(emptyList()) }
        assertFailsWith<IllegalArgumentException> { McpTransport.Http("") }
    }

    @Test fun planDenyWins() {
        val a = McpServerSpec("a", McpTransport.Stdio(listOf("a")), McpSource.PLUGIN)
        val b = McpServerSpec("b", McpTransport.Http("http://b"), McpSource.INFANTRY_USER)
        assertEquals(listOf(b), McpPlan(add = listOf(a, b), deny = setOf("a")).delivered)
        assertEquals(emptyList(), McpPlan.EMPTY.delivered)
    }

    @Test fun hostDefaultPlanIsTheInfantryEntryAlone() {
        val launch = HarnessProcess(listOf("infantry", "mcp"), File("wd"), mapOf("INFANTRY_TOKEN" to "t"))
        val plan = StubHost(launch).mcpServers(mapOf("INFANTRY_SESSION_ID" to "s1"))
        assertTrue(plan.deny.isEmpty())
        val only = plan.add.single()
        assertEquals(INFANTRY_MCP_SERVER, only.name)
        assertEquals(McpSource.INFANTRY, only.source)
        assertEquals(McpTransport.Stdio(listOf("infantry", "mcp"), mapOf("INFANTRY_TOKEN" to "t"), File("wd").path), only.transport)
        assertEquals(McpPlan.EMPTY, StubHost(null).mcpServers(emptyMap()), "no mcp grant: nothing to deliver")
    }

    @Test fun harnessDefaultsKeepTodaysBehaviour() {
        val h = object : Harness {
            override val id = "stub"
            override val capabilities = emptySet<HarnessCapability>()
            override fun process(request: HarnessRequest) = HarnessProcess(listOf("stub"))
            override fun newTranslator(): HarnessTranslator = throw UnsupportedOperationException()
        }
        assertNull(h.mcp)
        assertFalse(h.loadsUserMcp)
        val out = object : SessionOutput {
            override fun write(text: String) = true
            override fun closeInput() {}
            override fun detail(text: String) {}
            override fun providerSession(id: String) {}
            override fun turnEnded(end: SessionTurnEnd, exitCode: Int?, reason: String?) {}
        }
        out.mcpServers(listOf(McpServerStatus("a", McpServerState.CONNECTED))) // default: dropped, no throw
    }

    @Test fun harnessMcpDefaultsSayCannot() = runBlocking {
        val mcp = object : HarnessMcp {}
        assertFalse(mcp.canAdd || mcp.canRemove || mcp.canEdit)
        assertTrue(mcp.scopes.isEmpty())
        assertTrue(mcp.list("p").isEmpty())
        val spec = McpServerSpec("a", McpTransport.Stdio(listOf("a")), McpSource.INFANTRY_USER)
        assertTrue(mcp.add("p", spec, "user").isFailure)
        assertTrue(mcp.remove("p", "a", null).isFailure)
        assertEquals(McpServerState.UNKNOWN, mcp.test("p", "a").state)
    }

    private class StubHost(private val launch: HarnessProcess?) : HarnessHost {
        override val moduleId = "Stub"
        override val apiVersion = HARNESS_API_VERSION
        override val stateDir = File(".")
        override fun log(line: String) {}
        override fun setting(key: String): String? = null
        override fun resolveCli(tool: String): String? = null
        override fun start(process: HarnessProcess, listener: HarnessProcessListener): HarnessRunningProcess = throw SecurityException()
        override fun mcpLaunch(sessionEnv: Map<String, String>) = launch
        override fun watchLauncher(sessionEnv: Map<String, String>): String? = null
        override suspend fun ask(sessionId: String, question: HarnessQuestion) = HarnessAnswer.NONE
    }
}
