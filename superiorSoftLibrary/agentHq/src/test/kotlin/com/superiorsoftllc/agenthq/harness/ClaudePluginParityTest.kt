package com.superiorsoftllc.agenthq.harness

import com.superiorsoftllc.agenthq.claude.ClaudeCommand as CoreCommand
import com.superiorsoftllc.agenthq.claude.ClaudeContextLimit
import com.superiorsoftllc.agenthq.claude.ClaudeEffort
import com.superiorsoftllc.agenthq.claude.ClaudePermissionMode
import com.superiorsoftllc.agenthq.claude.ClaudeSessionAdapter
import com.superiorsoftllc.agenthq.daemon.AccessPolicy
import com.superiorsoftllc.agenthq.daemon.AgentHqMcp
import com.superiorsoftllc.agenthq.daemon.WatchHooks
import com.superiorsoftllc.agenthq.harness.claude.ClaudeCommands
import com.superiorsoftllc.agenthq.harness.claude.ClaudeModelCatalog
import com.superiorsoftllc.agenthq.harness.claude.ClaudeStreamTranslator as CoreTranslator
import com.superiorsoftllc.agenthq.harness.api.ToolApproval as ApiApproval
import com.superiorsoftllc.agenthq.plugins.official.claude.harness.ClaudeCommand as PluginCommand
import com.superiorsoftllc.agenthq.plugins.official.claude.harness.ClaudeSession
import com.superiorsoftllc.agenthq.plugins.official.claude.harness.ClaudeStreamTranslator as PluginTranslator
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * HARNESS H7b-2: the Infantry_Claude plugin and the daemon's built-in claude harness are two copies of the same behaviour (the plugin sees only
 * the harness API). The same inputs must give the same output from both: argv for every permission mode (one-shot, persistent, ask, watch,
 * effort, resume), the MCP config, the watch settings, a recorded stream transcript, the stdin lines, the capabilities, the `/` commands
 * and the model list. Production still runs the core copy.
 */
class ClaudePluginParityTest {
    private val approvals = ToolApproval.entries.map { it to ApiApproval.valueOf(it.name) }

    @Test fun `argv is the same for every permission mode, effort, resume and model`() {
        for ((core, api) in approvals) for (stream in listOf(false, true)) for (effort in listOf(null, "high")) for (resume in listOf(null, "sess-1")) {
            val expected = CoreCommand.command(
                executable = "claude", model = "sonnet", permissionMode = ClaudePermissionMode.forApproval(core), mcpConfigs = listOf("a.json"),
                strictMcp = true, resumeSessionId = resume, effort = effort, streamInput = stream,
            )
            val actual = PluginCommand.command(
                executable = "claude", model = "sonnet", permissionMode = PluginCommand.permissionMode(api), mcpConfigs = listOf("a.json"),
                strictMcp = true, resumeSessionId = resume, effort = effort, streamInput = stream,
            )
            assertEquals(expected, actual, "approval=$core stream=$stream effort=$effort resume=$resume")
        }
    }

    @Test fun `ask prompt tool, watch settings and no mcp give the same argv`() {
        val settings = "settings.json"
        assertEquals(
            CoreCommand.command("claude", null, null, emptyList(), false, null, permissionPromptTool = CoreCommand.PERMISSION_PROMPT_STDIO, settings = settings),
            PluginCommand.command("claude", null, null, emptyList(), false, null, permissionPromptTool = PluginCommand.PERMISSION_PROMPT_STDIO, settings = settings),
        )
    }

    @Test fun `the approval argv slot and the allow rules match the core harness`() {
        for ((core, api) in approvals) {
            assertEquals(ClaudePermissionMode.forApproval(core)?.let { listOf("--permission-mode", it) } ?: emptyList(), PluginCommand.approvalArgs(api), "$core")
        }
        assertEquals(CoreCommand.AGENTHQ_ALLOWED_TOOLS, PluginCommand.allowedTools())
        assertEquals(AgentHqMcp.CLAUDE_TOOL_RULE, PluginCommand.mcpToolRule(AgentHqMcp.SERVER_NAME))
    }

    @Test fun `mcp config json and user config parsing match`() {
        val launch = AgentHqMcp.Launch("cmd", listOf("/c", "C:\\bin\\agenthq.cmd", "mcp", "serve"), mapOf("AGENTHQ_SESSION_ID" to "s-1", "AGENTHQ_ROLE" to "backend"))
        assertEquals(AgentHqMcp.claudeConfigJson(launch), PluginCommand.mcpConfigJson(launch.argv, launch.env))
        val files = mutableListOf<String>()
        val configs = PluginCommand.mcpConfigs(listOf("user.json"), launch.argv, launch.env) { json -> files += json; "entry.json" }
        assertEquals(listOf("user.json", "entry.json"), configs)
        assertEquals(listOf(AgentHqMcp.claudeConfigJson(launch)), files)
        assertEquals(CoreCommand.parseMcpConfigs("a.json, b.json\n{\"x\":1}"), PluginCommand.parseMcpConfigs("a.json, b.json\n{\"x\":1}"))
        assertEquals(listOf("user.json"), PluginCommand.mcpConfigs(listOf("user.json"), null, emptyMap()))
    }

    @Test fun `watch settings match and are absent when watch is off`() {
        val env = mapOf("AGENTHQ_WATCH" to "on", AccessPolicy.MAIL_BASE_ENV to "http://127.0.0.1:7777/")
        assertEquals(WatchHooks.claudeSettings(env), PluginCommand.watchSettings(env))
        assertEquals(null, PluginCommand.watchSettings(mapOf(AccessPolicy.MAIL_BASE_ENV to "http://x")))
        assertEquals(WatchHooks.claudeSettings(emptyMap()), PluginCommand.watchSettings(emptyMap()))
    }

    @Test fun `capabilities, context limit env, commands and models match`() {
        assertEquals(ClaudeEffort.CAPABILITY.support.name, PluginCommand.EFFORT.support.name)
        assertEquals(ClaudeEffort.CAPABILITY.how, PluginCommand.EFFORT.how)
        assertEquals(ClaudeContextLimit.CAPABILITY.support.name, PluginCommand.CONTEXT_LIMIT.support.name)
        assertEquals(ClaudeContextLimit.CAPABILITY.how, PluginCommand.CONTEXT_LIMIT.how)
        for (limit in listOf(null, 0, -5, 150_000)) assertEquals(ClaudeContextLimit.env(limit), PluginCommand.contextLimitEnv(limit), "limit=$limit")
        assertEquals(
            ClaudeCommands.catalog.map { listOf(it.name, it.description, it.action.name, it.argument, it.values, it.native) },
            PluginCommand.COMMANDS.map { listOf(it.name, it.description, it.action.name, it.argument, it.values, it.native) },
        )
        assertEquals(ClaudeModelCatalog.MODELS.map { it.id }, PluginCommand.MODELS)
        assertEquals(ClaudeModelCatalog().models().single().models, PluginCommand.models().single().models)
    }

    @Test fun `stdin lines of the persistent session match`() {
        assertEquals(ClaudeSessionAdapter.userLine("hello \"x\"\nbye"), ClaudeSession.userLine("hello \"x\"\nbye"))
        assertEquals(ClaudeSessionAdapter.interruptLine("r-1"), ClaudeSession.interruptLine("r-1"))
    }

    private fun transcript(vararg lines: String) = lines.toList()

    private val success = transcript(
        """{"type":"system","subtype":"init","session_id":"s-1","model":"claude-sonnet-5-5"}""",
        """{"type":"system","subtype":"init","session_id":"s-1"}""",
        """{"type":"assistant","message":{"content":[{"type":"thinking","thinking":"plan"},{"type":"text","text":"Hello"},{"type":"tool_use","id":"t1","name":"Bash","input":{"command":"ls"}}]}}""",
        """{"type":"user","message":{"content":[{"type":"tool_result","tool_use_id":"t1","content":"a.txt"}]}}""",
        """{"type":"user","message":{"content":[{"type":"tool_result","tool_use_id":"t2","content":[{"type":"text","text":"boom"}],"is_error":true}]}}""",
        "not json",
        """{"type":"rate_limit_event"}""",
        """{"type":"result","subtype":"success","is_error":false,"result":"ok","usage":{"input_tokens":12,"output_tokens":34,"cache_creation_input_tokens":100,"cache_read_input_tokens":1000}}""",
    )
    private val failed = transcript(
        """{"type":"system","subtype":"init","session_id":"s-2"}""",
        """{"type":"result","subtype":"error_during_execution","is_error":true,"result":"API Error: 401"}""",
    )
    private val cut = transcript("""{"type":"system","subtype":"init","session_id":"s-3"}""")

    private fun coreEvents(lines: List<String>, exit: Int, err: String): List<String> {
        val t = CoreTranslator()
        return lines.flatMap { t.translate(it) }.map { it.toString() } + t.terminal(exit, err).toString()
    }

    private fun pluginEvents(lines: List<String>, exit: Int, err: String): List<String> {
        val t = PluginTranslator()
        return lines.flatMap { t.translate(it) }.map { it.toString() } + t.terminal(exit, err).toString()
    }

    @Test fun `a recorded stream gives the same events`() {
        for ((name, lines, exit, err) in listOf(
            Case("success", success, 0, ""), Case("failed", failed, 0, ""), Case("cut short", cut, 0, ""),
            Case("non-zero exit", success, 3, "boom"),
        )) assertEquals(coreEvents(lines, exit, err), pluginEvents(lines, exit, err), name)
    }

    private data class Case(val name: String, val lines: List<String>, val exit: Int, val err: String)
}
