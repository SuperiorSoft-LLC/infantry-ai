// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.plugins.official.mcp.daemon

import com.superiorsoftllc.infantry.daemon.api.Frame
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** MCP-CLI-1: infantry_mcp reads for an agent, refuses every change from an agent (no self-grant), and prints compact lines. */
class McpToolTest {
    private fun args(s: String) = Json.parseToJsonElement(s).jsonObject
    private val sent = mutableListOf<String>()
    private fun send(type: String, data: String, session: String?): Frame {
        sent += type
        return when (type) {
            "mcp_list" -> Frame("mcp_servers", """{"servers":[{"name":"docs","source":"infantry_user","target":"infantry","scope":"app","state":"configured","enabled":true},{"name":"playwright","source":"harness","target":"claude","scope":"user","state":"connected","toolCount":21}],"targets":[]}""")
            "mcp_plan" -> Frame("mcp_plan", """{"add":["infantry"],"deny":["playwright"],"reasons":{"infantry":"daemon","playwright":"no grant"}}""")
            else -> Frame("mcp_grants", "[]")
        }
    }

    @Test
    fun `an agent cannot grant itself or change servers and no frame is sent`() {
        for (op in listOf("grant", "add", "remove", "enable", "disable", "revoke")) {
            val r = McpDaemonPlugin.McpTool.handle(args("""{"op":"$op","name":"x","grantId":"g","server":{},"grant":{}}"""), agent = true, sessionId = "s1", projectId = null, send = ::send)
            assertTrue(r.isError, op)
            assertTrue("refused" in r.text, r.text)
        }
        assertTrue(sent.isEmpty(), sent.toString())
    }

    @Test
    fun `list is compact and merges the sources, my_tools explains the session`() {
        val list = McpDaemonPlugin.McpTool.handle(args("""{"op":"list"}"""), agent = true, sessionId = "s1", projectId = "p", send = ::send)
        assertFalse(list.isError)
        assertEquals(2, list.text.lines().size, list.text)
        assertTrue("claude/harness:user  connected  21 tools" in list.text, list.text)
        val mine = McpDaemonPlugin.McpTool.handle(args("""{"op":"my_tools"}"""), agent = true, sessionId = "s1", projectId = null, send = ::send)
        assertTrue("you get: infantry" in mine.text && "skipped: playwright" in mine.text, mine.text)
    }

    @Test
    fun `a non-agent caller may grant, through the grant frame`() {
        val r = McpDaemonPlugin.McpTool.handle(args("""{"op":"grant","grant":{"server":"docs","scope":"role","target":"qa","purpose":"p","approvedBy":"owner"}}"""), agent = false, sessionId = null, projectId = null, send = ::send)
        assertFalse(r.isError, r.text)
        assertEquals(listOf("mcp_assign"), sent)
    }
}
