package com.superiorsoftllc.infantry.plugins.official.opencode.harness

import com.superiorsoftllc.infantry.harness.api.ConfigParts
import com.superiorsoftllc.infantry.harness.api.HarnessProcess
import com.superiorsoftllc.infantry.harness.api.ToolApproval
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
 * HARNESS H8b-3: tests moved from the daemon's InfantryMcpTest (the opencode section: mcp merge in every mode, a user's own entry, a one-string
 * permission, the MCP tool ask answered once) and StallHookTest (the watch plugin entry and its shipped script).
 */
class OpenCodeMcpWatchMovedTest {
    private val mcp = HarnessProcess(listOf("cmd", "/c", "infantry.cmd", "mcp", "serve"), env = mapOf("INFANTRY_SESSION_ID" to "sess-1"))
    private val userOpenCode = """{"model":"m","mcp":{"mine":{"type":"local","command":["x"]}},"permission":{"*":"deny","bash":"ask"}}"""
    private fun parse(s: String) = Json.parseToJsonElement(s).jsonObject

    private fun assertMerged(config: String, label: String) {
        val root = parse(config)
        assertEquals("m", root["model"]!!.jsonPrimitive.content, "$label: user keys kept")
        val servers = root["mcp"]!!.jsonObject
        assertNotNull(servers["mine"], "$label: user mcp server kept")
        val infantry = servers["infantry"]!!.jsonObject
        assertEquals("local", infantry["type"]!!.jsonPrimitive.content)
        assertEquals("mcp", infantry["command"]!!.jsonArray.map { it.jsonPrimitive.content }.dropLast(1).last(), label)
        assertEquals("sess-1", infantry["environment"]!!.jsonObject["INFANTRY_SESSION_ID"]!!.jsonPrimitive.content, label)
        val permission = root["permission"]!!.jsonObject
        assertEquals("deny", permission["*"]!!.jsonPrimitive.content, "$label: user rule kept")
        assertEquals("allow", permission["infantry_*"]!!.jsonPrimitive.content, label)
        assertEquals("infantry_*", permission.keys.last(), "$label: the allow goes last")
        assertEquals("allow", permission["bash"]!!.jsonObject[OpenCodePermissions.INFANTRY_BASH_PATTERN]!!.jsonPrimitive.content, "$label: BUG-PERM-5 kept")
    }

    @Test fun `opencode serve and run configs carry mcp infantry in every mode, merged`() {
        for (mode in ToolApproval.entries) {
            assertMerged(OpenCodePermissions.serveConfig(mode, userOpenCode, mcp), "serve $mode")
            assertMerged(OpenCodePermissions.runEnv(mode, userOpenCode, mcp).getValue(OpenCodePermissions.CONFIG_CONTENT_ENV), "run $mode")
            assertNotNull(parse(OpenCodePermissions.serveConfig(mode, null, mcp))["mcp"]!!.jsonObject["infantry"], "blank inherited")
        }
    }

    @Test fun `opencode keeps a user's own mcp infantry entry`() {
        val own = """{"mcp":{"infantry":{"type":"remote","url":"http://x"}}}"""
        val infantry = parse(OpenCodePermissions.serveConfig(ToolApproval.DENY, own, mcp))["mcp"]!!.jsonObject["infantry"]!!.jsonObject
        assertEquals("remote", infantry["type"]!!.jsonPrimitive.content)
    }

    @Test fun `opencode permission given as one string keeps it as the fallback`() {
        val permission = parse(OpenCodeConfig.merge("""{"permission":"ask"}""", ConfigParts(mcp = mcp)))["permission"]!!.jsonObject
        assertEquals("ask", permission["*"]!!.jsonPrimitive.content)
        assertEquals("allow", permission["infantry_*"]!!.jsonPrimitive.content)
    }

    private fun mcpAsk(tool: String) = (Json.parseToJsonElement(
        """{"properties":{"id":"per_9","sessionID":"ses_1","permission":"$tool","patterns":["*"],"metadata":{}}}""",
    ) as JsonObject)["properties"] as JsonObject

    @Test fun `opencode serve answers an infantry mcp tool ask once in every mode and never asks the owner`() {
        for (mode in ToolApproval.entries) {
            val d = OpenCodeServeSession.permissionDecision(mcpAsk("infantry_infantry_ticket_create"), mode)!!
            assertEquals("""{"reply":"once"}""", d.replyBody, "$mode")
            assertNull(d.deniedTool, "$mode")
            assertFalse(OpenCodeServeSession.needsOwner(mcpAsk("infantry_infantry_ticket_create"), mode), "$mode")
        }
        val other = OpenCodeServeSession.permissionDecision(mcpAsk("othermcp_tool"), ToolApproval.DENY)!!
        assertEquals("""{"reply":"reject"}""", other.replyBody)
        assertTrue(OpenCodeServeSession.needsOwner(mcpAsk("othermcp_tool"), ToolApproval.ASK))
    }

    @Test fun `the watch plugin is appended and the user's plugins are kept`() {
        val plugin = File("infantry-watch.js").absoluteFile
        val merged = parse(OpenCodeConfig.merge("""{"plugin":["opencode-snip@latest"],"mcp":{}}""", ConfigParts(watchPlugin = plugin)))
        val list = merged["plugin"]!!.jsonArray.map { it.jsonPrimitive.content }
        assertEquals("opencode-snip@latest", list.first())
        assertTrue(list.last().startsWith("file:///") && list.last().endsWith("infantry-watch.js"), list.toString())
        assertTrue(merged.containsKey("mcp"))
    }

    @Test fun `the watch hook script ships as a resource`() {
        val js = OpenCodeServeSession::class.java.getResource("/opencode/infantry-watch.js")!!.readText()
        assertTrue(js.contains("\"tool.execute.before\"") && js.contains("output.args.command = out.command"))
    }
}
