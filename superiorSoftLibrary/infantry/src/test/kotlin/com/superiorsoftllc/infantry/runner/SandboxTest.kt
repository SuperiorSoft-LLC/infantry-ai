package com.superiorsoftllc.infantry.runner

import com.superiorsoftllc.infantry.daemon.AccessConfigurationException
import com.superiorsoftllc.infantry.daemon.AccessPolicy
import com.superiorsoftllc.infantry.daemon.AccessTier
import com.superiorsoftllc.infantry.daemon.AgentFrames
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** SANDBOX-1 (ticket d4c1dbd9): the launch rewrite, the per-CLI permission layer and the scoped agent token. */
class SandboxTest {
    private val state = Files.createTempDirectory("sbx-state").toFile()
    private val home = Files.createTempDirectory("sbx-home").toFile()
    private val helper = File(state, "landlock-exec").apply { writeText("#!/bin/sh\n"); setExecutable(true) }
    private val work = File("/work/demo")
    private val on = SandboxConfig(true, listOf("/work"), agentToken = "a".repeat(64))

    private fun plan(linux: Boolean, tool: File? = helper) =
        SandboxPlan(state, home, helper = { tool }, isLinux = linux, env = { null })

    @Test fun `off is a pass-through`() {
        val spec = ProcessSpec(listOf("/usr/bin/opencode", "serve"), work, mapOf("INFANTRY_TOKEN" to "owner"))
        assertEquals(spec, plan(true).wrap(spec, SandboxConfig.OFF))
    }

    @Test fun `on Linux the CLI runs under the helper, the state dir is never granted, the owner token is replaced`() {
        val spec = ProcessSpec(listOf("/usr/local/bin/pi", "--mode", "rpc"), work, mapOf("INFANTRY_TOKEN" to "owner"))
        val out = plan(true).wrap(spec, on)
        assertEquals(helper.absolutePath, out.command.first())
        val sep = out.command.indexOf("--")
        val opts = out.command.subList(1, sep).chunked(2).map { it[0] to it[1] }
        val rw = opts.filter { it.first == "--rw" }.map { it.second }
        val ro = opts.filter { it.first == "--ro" }.map { it.second }
        assertTrue(work.absolutePath in rw && "/work" in rw, "working dir and allowed dirs are writable: $rw")
        assertTrue(home.absolutePath in ro && home.absolutePath !in rw, "home (CLI config) is read-only")
        assertTrue(File(home, ".pi/agent/sessions").path in rw, "pi keeps its sessions")
        assertFalse((ro + rw).any { it == state.absolutePath }, "the daemon state dir (DB, token) is never granted")
        assertEquals(listOf("/usr/local/bin/pi", "--mode", "rpc", "--exclude-tools", "bash,powershell"), out.command.subList(sep + 1, out.command.size))
        assertEquals("a".repeat(64), out.env["INFANTRY_TOKEN"])
        assertEquals("", out.env["INFANTRY_TOKEN_FILE"])
    }

    @Test fun `on Linux without the helper the launch is refused, never run unconfined`() {
        val spec = ProcessSpec(listOf("opencode", "serve"), work)
        assertFailsWith<SandboxUnavailableException> { plan(true, tool = null).wrap(spec, on) }
    }

    @Test fun `elsewhere the OS layer is not applied and the status says so`() {
        val out = plan(false, tool = null).wrap(ProcessSpec(listOf("opencode", "serve"), work), on)
        assertEquals("opencode", out.command.first())
        assertEquals("1", out.env["OPENCODE_DISABLE_PROJECT_CONFIG"])
        if (!SandboxSupport.isLinux) assertTrue(SandboxSupport.status(true).startsWith("NOT ENFORCED YET"))
        assertEquals("off", SandboxSupport.status(false))
    }

    @Test fun `opencode permissions deny shell, web and outside dirs, and keep the daemon's own inline config`() {
        val existing = """{"mcp":{"infantry":{"type":"local"}},"permission":{"infantry_*":"allow"}}"""
        val env = HarnessRestrictions.opencodeEnv(existing, listOf("/work/demo", "/work"))
        val cfg = Json.parseToJsonElement(env.getValue("OPENCODE_CONFIG_CONTENT")).jsonObject
        val p = cfg.getValue("permission").jsonObject
        assertEquals(JsonPrimitive("deny"), p["bash"])
        assertEquals(JsonPrimitive("deny"), p["webfetch"])
        assertEquals(JsonPrimitive("allow"), p["infantry_*"], "the infantry MCP tools stay allowed")
        val ext = p.getValue("external_directory").jsonObject
        assertEquals(JsonPrimitive("deny"), ext["*"])
        assertEquals(JsonPrimitive("allow"), ext["/work/**"])
        assertEquals(JsonPrimitive("deny"), p.getValue("edit").jsonObject["**/opencode.json"])
        assertTrue(cfg["mcp"] is JsonObject)
    }

    @Test fun `claude gets tool denies, a config deny list and the allowed dirs`() {
        val args = HarnessRestrictions.claudeArgs(listOf("/work"))
        assertTrue(args.containsAll(listOf("--disallowedTools", "Bash", "--add-dir", "/work")))
        assertTrue(args[args.indexOf("--settings") + 1].contains("Edit(~/.claude/**)"))
        assertNull(HarnessRestrictions.cliOf(listOf("git", "status")))
    }

    @Test fun `the agent token is admitted with the agent tier and its frames are scoped`() {
        val owner = "o".repeat(40)
        val agent = AgentFrames.newToken()
        val p = AccessPolicy.of("0.0.0.0", owner, webHosts = emptyList(), agentToken = agent, localAddresses = { emptySet() })
        assertNull(p.check("/ws", "h", null, "Bearer $agent"))
        assertEquals(AccessTier.AGENT, p.tierOf("Bearer $agent"))
        assertEquals(AccessTier.OWNER, p.tierOf("Bearer $owner"))
        assertTrue(p.check("/ws", "h", null, "Bearer nope") != null)
        for (t in listOf("mail_send", "mail_inbox", "agent_roster", "ticket_move", "progress")) assertTrue(AgentFrames.allows(t), t)
        for (t in listOf("settings_set", "cli_location_set", "harness_install", "role_save", "agent_save", "new", "pod_submit",
            "prompt", "kill", "tool_approval_set", "secret_set", "project_save", "command_save", "run_start", "trigger_save")) {
            assertFalse(AgentFrames.allows(t), t)
        }
        assertFalse(AgentFrames.allowsHttp("POST", "/update/apply"))
        assertFailsWith<AccessConfigurationException> { AccessPolicy.of("127.0.0.1", owner, agentToken = owner) }
    }
}
