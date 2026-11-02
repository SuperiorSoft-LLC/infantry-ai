package com.superiorsoftllc.agenthq.daemon

import com.superiorsoftllc.agenthq.harness.ToolApproval
import com.superiorsoftllc.agenthq.harness.RunRequest
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
}
