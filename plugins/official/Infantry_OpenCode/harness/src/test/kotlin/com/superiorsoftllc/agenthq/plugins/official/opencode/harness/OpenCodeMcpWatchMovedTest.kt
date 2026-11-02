package com.superiorsoftllc.agenthq.plugins.official.opencode.harness

import com.superiorsoftllc.agenthq.harness.api.ConfigParts
import com.superiorsoftllc.agenthq.harness.api.HarnessProcess
import com.superiorsoftllc.agenthq.harness.api.ToolApproval
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * HARNESS H8b-3: tests moved from the daemon's AgentHqMcpTest (the opencode section: mcp merge in every mode, a user's own entry, a one-string
 * permission, the MCP tool ask answered once) and StallHookTest (the watch plugin entry and its shipped script).
 */
class OpenCodeMcpWatchMovedTest {
    private val mcp = HarnessProcess(listOf("cmd", "/c", "agenthq.cmd", "mcp", "serve"), env = mapOf("AGENTHQ_SESSION_ID" to "sess-1"))
    private val userOpenCode = """{"model":"m","mcp":{"mine":{"type":"local","command":["x"]}},"permission":{"*":"deny","bash":"ask"}}"""
    private fun parse(s: String) = Json.parseToJsonElement(s).jsonObject

    private fun assertMerged(config: String, label: String) {
        val root = parse(config)
        assertEquals("m", root["model"]!!.jsonPrimitive.content, "$label: user keys kept")
        val servers = root["mcp"]!!.jsonObject
        assertNotNull(servers["mine"], "$label: user mcp server kept")
        val agenthq = servers["agenthq"]!!.jsonObject
        assertEquals("local", agenthq["type"]!!.jsonPrimitive.content)
        assertEquals("mcp", agenthq["command"]!!.jsonArray.map { it.jsonPrimitive.content }.dropLast(1).last(), label)
        assertEquals("sess-1", agenthq["environment"]!!.jsonObject["AGENTHQ_SESSION_ID"]!!.jsonPrimitive.content, label)
        val permission = root["permission"]!!.jsonObject
        assertEquals("deny", permission["*"]!!.jsonPrimitive.content, "$label: user rule kept")
        assertEquals("allow", permission["agenthq_*"]!!.jsonPrimitive.content, label)
        assertEquals("agenthq_*", permission.keys.last(), "$label: the allow goes last")
        assertEquals("allow", permission["bash"]!!.jsonObject[OpenCodePermissions.AGENTHQ_BASH_PATTERN]!!.jsonPrimitive.content, "$label: BUG-PERM-5 kept")
    }

    @Test fun `opencode serve and run configs carry mcp agenthq in every mode, merged`() {
        for (mode in ToolApproval.entries) {
            assertMerged(OpenCodePermissions.serveConfig(mode, userOpenCode, mcp), "serve $mode")
            assertMerged(OpenCodePermissions.runEnv(mode, userOpenCode, mcp).getValue(OpenCodePermissions.CONFIG_CONTENT_ENV), "run $mode")
            assertNotNull(parse(OpenCodePermissions.serveConfig(mode, null, mcp))["mcp"]!!.jsonObject["agenthq"], "blank inherited")
        }
    }

    @Test fun `opencode keeps a user's own mcp agenthq entry`() {
        val own = """{"mcp":{"agenthq":{"type":"remote","url":"http://x"}}}"""
        val agenthq = parse(OpenCodePermissions.serveConfig(ToolApproval.DENY, own, mcp))["mcp"]!!.jsonObject["agenthq"]!!.jsonObject
        assertEquals("remote", agenthq["type"]!!.jsonPrimitive.content)
    }

    @Test fun `opencode permission given as one string keeps it as the fallback`() {
        val permission = parse(OpenCodeConfig.merge("""{"permission":"ask"}""", ConfigParts(mcp = mcp)))["permission"]!!.jsonObject
        assertEquals("ask", permission["*"]!!.jsonPrimitive.content)
        assertEquals("allow", permission["agenthq_*"]!!.jsonPrimitive.content)
    }

    private fun mcpAsk(tool: String) = (Json.parseToJsonElement(
        """{"properties":{"id":"per_9","sessionID":"ses_1","permission":"$tool","patterns":["*"],"metadata":{}}}""",
    ) as JsonObject)["properties"] as JsonObject

    @Test fun `opencode serve answers an agenthq mcp tool ask once in every mode and never asks the owner`() {
        for (mode in ToolApproval.entries) {
            val d = OpenCodeServeSession.permissionDecision(mcpAsk("agenthq_agenthq_ticket_create"), mode)!!
            assertEquals("""{"reply":"once"}""", d.replyBody, "$mode")
            assertNull(d.deniedTool, "$mode")
            assertFalse(OpenCodeServeSession.needsOwner(mcpAsk("agenthq_agenthq_ticket_create"), mode), "$mode")
        }
        val other = OpenCodeServeSession.permissionDecision(mcpAsk("othermcp_tool"), ToolApproval.DENY)!!
        assertEquals("""{"reply":"reject"}""", other.replyBody)
        assertTrue(OpenCodeServeSession.needsOwner(mcpAsk("othermcp_tool"), ToolApproval.ASK))
    }

    @Test fun `the watch plugin is appended and the user's plugins are kept`() {
        val plugin = File("agenthq-watch.js").absoluteFile
        val merged = parse(OpenCodeConfig.merge("""{"plugin":["opencode-snip@latest"],"mcp":{}}""", ConfigParts(watchPlugin = plugin)))
        val list = merged["plugin"]!!.jsonArray.map { it.jsonPrimitive.content }
        assertEquals("opencode-snip@latest", list.first())
        assertTrue(list.last().startsWith("file:///") && list.last().endsWith("agenthq-watch.js"), list.toString())
        assertTrue(merged.containsKey("mcp"))
    }

    @Test fun `the watch hook script ships as a resource`() {
        val js = OpenCodeServeSession::class.java.getResource("/opencode/agenthq-watch.js")!!.readText()
        assertTrue(js.contains("\"tool.execute.before\"") && js.contains("output.args.command = out.command"))
    }
}
