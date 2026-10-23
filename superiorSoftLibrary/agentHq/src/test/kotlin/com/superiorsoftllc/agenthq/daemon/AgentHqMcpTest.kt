package com.superiorsoftllc.agenthq.daemon

import com.superiorsoftllc.agenthq.claude.ClaudeCommand
import com.superiorsoftllc.agenthq.claude.ClaudeSessionAdapter
import com.superiorsoftllc.agenthq.harness.ToolApproval
import com.superiorsoftllc.agenthq.opencode.OpenCodePermissions
import com.superiorsoftllc.agenthq.opencode.OpenCodeServeAdapter
import com.superiorsoftllc.agenthq.provider.RunRequest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * MCP-1: every session the daemon spawns gets `agenthq mcp serve` as an MCP server, and its tools are allowed in every
 * Tool approval mode. Claude: an extra `--mcp-config` (merged after the user's, never replacing it) and
 * `mcp__agenthq__*` in `--allowedTools`. OpenCode: `mcp.agenthq` + `permission."agenthq_*": "allow"` merged into
 * `OPENCODE_CONFIG_CONTENT`, on `run` and `serve`, and a serve ask for one of those tools is answered `once`.
 */
class AgentHqMcpTest {
    private val bin: File = Files.createTempDirectory("agenthq-mcp-bin").toFile().also {
        File(it, "agenthq").writeText("#!/bin/sh\n")
        File(it, "agenthq.bat").writeText("@echo off\r\n")
    }
    private val configDir: File = Files.createTempDirectory("agenthq-mcp-config").toFile()

    @AfterTest
    fun cleanup() {
        bin.deleteRecursively()
        configDir.deleteRecursively()
    }

    private val sessionEnv = mapOf(
        AgentHqBin.BIN_ENV to bin.absolutePath,
        AccessPolicy.SESSION_ID_ENV to "sess-1",
        AccessPolicy.PROJECT_ID_ENV to "proj-1",
        AccessPolicy.ROLE_ENV to "backend",
        AgentHqBin.URL_ENV to "ws://127.0.0.1:9999/ws",
        AccessPolicy.TOKEN_ENV to "secret-token-never-written",
        "PATH" to "/usr/bin",
    )

    private fun parse(text: String): JsonObject = Json.parseToJsonElement(text).jsonObject

    @Test
    fun `windows launch runs the session's own agenthq launcher through cmd, with the session identity`() {
        val launch = AgentHqMcp.launch(sessionEnv, windows = true)
        assertEquals("cmd", launch.command)
        assertEquals(listOf("/c", File(bin, "agenthq.bat").absolutePath, "mcp", "serve"), launch.args)
        assertEquals("sess-1", launch.env[AccessPolicy.SESSION_ID_ENV])
        assertEquals("proj-1", launch.env[AccessPolicy.PROJECT_ID_ENV])
        assertEquals("backend", launch.env[AccessPolicy.ROLE_ENV])
        assertEquals("ws://127.0.0.1:9999/ws", launch.env[AgentHqBin.URL_ENV])
        assertNull(launch.env[AccessPolicy.TOKEN_ENV], "the bearer token is inherited, never written into the entry")
        assertNull(launch.env["PATH"])
    }

    @Test
    fun `an installed agenthq cmd wins over a bat on windows, and posix runs the script directly`() {
        File(bin, "agenthq.cmd").writeText("@echo off\r\n")
        assertEquals(File(bin, "agenthq.cmd").absolutePath, AgentHqMcp.launch(sessionEnv, windows = true).args[1])
        val posix = AgentHqMcp.launch(sessionEnv, windows = false)
        assertEquals(File(bin, "agenthq").absolutePath, posix.command)
        assertEquals(listOf("mcp", "serve"), posix.args)
    }

    @Test
    fun `no launcher anywhere falls back to agenthq on PATH`() {
        val launch = AgentHqMcp.launch(emptyMap(), binDir = null, windows = false)
        assertEquals(listOf("agenthq", "mcp", "serve"), launch.argv)
    }

    @Test
    fun `claude config names the agenthq stdio server`() {
        val config = parse(AgentHqMcp.claudeConfigJson(AgentHqMcp.launch(sessionEnv, windows = true)))
        val server = config["mcpServers"]!!.jsonObject["agenthq"]!!.jsonObject
        assertEquals("stdio", server["type"]!!.jsonPrimitive.content)
        assertEquals("cmd", server["command"]!!.jsonPrimitive.content)
        assertEquals("mcp", server["args"]!!.jsonArray[2].jsonPrimitive.content)
        assertEquals("sess-1", server["env"]!!.jsonObject[AccessPolicy.SESSION_ID_ENV]!!.jsonPrimitive.content)
    }

    @Test
    fun `claude mcp configs keep the user's first and add agenthq as a file`() {
        val user = listOf("C:\\some dir\\mine.json", """{"mcpServers":{"other":{"command":"x"}}}""")
        val configs = AgentHqMcp.claudeMcpConfigs(user, AgentHqMcp.launch(sessionEnv)) { AgentHqMcp.configFile(it, configDir) }
        assertEquals(3, configs.size)
        assertEquals(user, configs.take(2), "the user's configs are passed first and unchanged")
        val file = File(configs[2])
        assertTrue(file.isFile, "the agenthq entry is a file path, not inline JSON (Windows argv drops inner quotes)")
        assertNotNull(parse(file.readText())["mcpServers"]!!.jsonObject["agenthq"])
    }

    @Test
    fun `a user config that already defines agenthq is never overwritten`() {
        val inline = """{"mcpServers":{"agenthq":{"command":"my-own"}}}"""
        assertEquals(listOf(inline), AgentHqMcp.claudeMcpConfigs(listOf(inline), AgentHqMcp.launch(sessionEnv)))
        val file = File(configDir, "user.json").apply { writeText(inline) }
        assertEquals(listOf(file.path), AgentHqMcp.claudeMcpConfigs(listOf(file.path), AgentHqMcp.launch(sessionEnv)))
    }

    @Test
    fun `the config file is content-addressed, so the same session gets the same path every turn`() {
        val a = AgentHqMcp.configFile("""{"a":1}""", configDir)
        assertEquals(a, AgentHqMcp.configFile("""{"a":1}""", configDir))
        assertFalse(a == AgentHqMcp.configFile("""{"a":2}""", configDir))
    }

    /** The real persistent-session launch argv (the fingerprint is built from it). */
    private fun claudeArgv(mode: ToolApproval): List<String> =
        ClaudeSessionAdapter(executable = { "claude" })
            .fingerprint(RunRequest(prompt = "", toolApproval = mode), sessionEnv)
            .split('\u0000')

    @Test
    fun `claude argv carries the agenthq mcp config and allows its tools in every mode`() {
        for (mode in ToolApproval.entries) {
            val argv = claudeArgv(mode)
            val allowed = argv[argv.indexOf("--allowedTools") + 1].split(',')
            assertTrue(AgentHqMcp.CLAUDE_TOOL_RULE in allowed, "${mode.wire}: $allowed")
            assertTrue("Bash(agenthq:*)" in allowed && "PowerShell(agenthq:*)" in allowed, "${mode.wire}: BUG-PERM-5 kept")
            val at = argv.indexOf("--mcp-config")
            assertTrue(at >= 0, "${mode.wire}: no --mcp-config in $argv")
            val agenthq = argv.drop(at + 1).takeWhile { !it.startsWith("--") }
                .map { File(it) }.firstOrNull { it.isFile && "\"agenthq\"" in it.readText() }
            assertNotNull(agenthq, "${mode.wire}: no agenthq entry among the --mcp-config values")
            assertTrue("sess-1" in agenthq.readText(), mode.wire)
        }
    }

    @Test
    fun `claude command keeps the user's mcp config ahead of the agenthq one`() {
        val user = """{"mcpServers":{"other":{"command":"x"}}}"""
        for (mode in ToolApproval.entries) {
            val argv = ClaudeCommand.command(
                executable = "claude", model = null, permissionMode = ClaudeCommand.permissionMode(mode),
                mcpConfigs = ClaudeCommand.sessionMcpConfigs(sessionEnv, listOf(user)), strictMcp = false, resumeSessionId = null,
            )
            val at = argv.indexOf("--mcp-config")
            assertEquals(user, argv[at + 1], mode.wire)
            assertTrue("\"agenthq\"" in File(argv[at + 2]).readText(), mode.wire)
        }
    }

    // ---- opencode ------------------------------------------------------------------------------------------------

    private val userOpenCode = """{"model":"m","mcp":{"mine":{"type":"local","command":["x"]}},"permission":{"*":"deny","bash":"ask"}}"""

    private fun assertOpenCodeMerged(config: String, label: String) {
        val root = parse(config)
        assertEquals("m", root["model"]!!.jsonPrimitive.content, "$label: user keys kept")
        val mcp = root["mcp"]!!.jsonObject
        assertNotNull(mcp["mine"], "$label: user mcp server kept")
        val agenthq = mcp["agenthq"]!!.jsonObject
        assertEquals("local", agenthq["type"]!!.jsonPrimitive.content)
        assertEquals("mcp", agenthq["command"]!!.jsonArray.map { it.jsonPrimitive.content }.dropLast(1).last(), label)
        assertEquals("sess-1", agenthq["environment"]!!.jsonObject[AccessPolicy.SESSION_ID_ENV]!!.jsonPrimitive.content, label)
        val permission = root["permission"]!!.jsonObject
        assertEquals("deny", permission["*"]!!.jsonPrimitive.content, "$label: user rule kept")
        assertEquals("allow", permission[AgentHqMcp.OPENCODE_TOOL_PATTERN]!!.jsonPrimitive.content, label)
        assertEquals(AgentHqMcp.OPENCODE_TOOL_PATTERN, permission.keys.last(), "$label: the allow goes last")
        assertEquals("allow", permission["bash"]!!.jsonObject[OpenCodePermissions.AGENTHQ_BASH_PATTERN]!!.jsonPrimitive.content, "$label: BUG-PERM-5 kept")
    }

    @Test
    fun `opencode serve and run configs carry mcp agenthq in every mode, merged`() {
        for (mode in ToolApproval.entries) {
            assertOpenCodeMerged(OpenCodePermissions.serveConfig(mode, userOpenCode, sessionEnv), "serve ${mode.wire}")
            assertOpenCodeMerged(
                OpenCodePermissions.runEnv(mode, userOpenCode, sessionEnv).getValue(OpenCodePermissions.CONFIG_CONTENT_ENV),
                "run ${mode.wire}",
            )
            assertNotNull(parse(OpenCodePermissions.serveConfig(mode, null, sessionEnv))["mcp"]!!.jsonObject["agenthq"], "blank inherited")
        }
    }

    @Test
    fun `opencode keeps a user's own mcp agenthq entry`() {
        val own = """{"mcp":{"agenthq":{"type":"remote","url":"http://x"}}}"""
        val agenthq = parse(OpenCodePermissions.serveConfig(ToolApproval.DENY, own, sessionEnv))["mcp"]!!.jsonObject["agenthq"]!!.jsonObject
        assertEquals("remote", agenthq["type"]!!.jsonPrimitive.content)
    }

    @Test
    fun `opencode permission given as one string keeps it as the fallback`() {
        val permission = parse(AgentHqMcp.mergeOpenCode("""{"permission":"ask"}""", AgentHqMcp.launch(sessionEnv)))["permission"]!!.jsonObject
        assertEquals("ask", permission["*"]!!.jsonPrimitive.content)
        assertEquals("allow", permission[AgentHqMcp.OPENCODE_TOOL_PATTERN]!!.jsonPrimitive.content)
    }

    private fun mcpAsk(tool: String) = (Json.parseToJsonElement(
        """{"properties":{"id":"per_9","sessionID":"ses_1","permission":"$tool","patterns":["*"],"metadata":{}}}""",
    ) as JsonObject)["properties"] as JsonObject

    @Test
    fun `opencode serve answers an agenthq mcp tool ask once in every mode and never asks the owner`() {
        for (mode in ToolApproval.entries) {
            val d = OpenCodeServeAdapter.permissionDecision(mcpAsk("agenthq_agenthq_ticket_create"), mode)!!
            assertEquals("""{"reply":"once"}""", d.replyBody, mode.wire)
            assertNull(d.deniedTool, mode.wire)
            assertFalse(OpenCodeServeAdapter.needsOwner(mcpAsk("agenthq_agenthq_ticket_create"), mode), mode.wire)
        }
        // Nothing else is widened.
        val other = OpenCodeServeAdapter.permissionDecision(mcpAsk("othermcp_tool"), ToolApproval.DENY)!!
        assertEquals("""{"reply":"reject"}""", other.replyBody)
        assertTrue(OpenCodeServeAdapter.needsOwner(mcpAsk("othermcp_tool"), ToolApproval.ASK))
    }
}
