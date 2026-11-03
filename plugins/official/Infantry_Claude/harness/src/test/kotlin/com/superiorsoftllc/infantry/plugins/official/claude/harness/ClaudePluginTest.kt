package com.superiorsoftllc.infantry.plugins.official.claude.harness

import com.superiorsoftllc.infantry.harness.api.ToolApproval
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * HARNESS H7b-3: the tests the daemon's built-in claude harness had, moved into the plugin that now runs it. The expected argv, stdin
 * lines and stream events are the ones the retired core copy produced (ClaudePluginParityTest proved them equal in H7b-2).
 */
class ClaudePluginTest {
    private val infantryRules = "Bash(infantry:*),PowerShell(infantry:*),mcp__infantry__*"

    @Test fun `one-shot argv with every option, in the golden order`() {
        val argv = ClaudeCommand.command(
            executable = "claude", model = "sonnet", permissionMode = "acceptEdits", mcpConfigs = listOf("a.json"),
            strictMcp = true, resumeSessionId = "sess-1", effort = "high",
        )
        assertEquals(
            listOf(
                "claude", "-p", "--output-format", "stream-json", "--verbose", "--thinking", "adaptive", "--thinking-display", "summarized",
                "--model", "sonnet", "--effort", "high", "--permission-mode", "acceptEdits", "--allowedTools", infantryRules,
                "--mcp-config", "a.json", "--strict-mcp-config", "--resume", "sess-1",
            ),
            argv,
        )
    }

    @Test fun `the persistent session adds the stream-json input and the ask prompt tool and watch settings follow`() {
        val argv = ClaudeCommand.command("claude", null, null, emptyList(), false, null, streamInput = true, permissionPromptTool = ClaudeCommand.PERMISSION_PROMPT_STDIO, settings = "s.json")
        assertEquals(listOf("claude", "-p", "--input-format", "stream-json", "--output-format", "stream-json"), argv.take(6))
        assertEquals("stdio", argv[argv.indexOf("--permission-prompt-tool") + 1])
        assertEquals("s.json", argv[argv.indexOf("--settings") + 1])
        assertFalse("--mcp-config" in argv)
    }

    @Test fun `tool approval maps to the permission mode and the infantry cli is allowed in every mode`() {
        assertNull(ClaudeCommand.permissionMode(ToolApproval.DENY, legacy = null))
        assertNull(ClaudeCommand.permissionMode(ToolApproval.ASK, legacy = null))
        assertEquals("acceptEdits", ClaudeCommand.permissionMode(ToolApproval.ALLOW_EDITS, legacy = null))
        assertEquals("bypassPermissions", ClaudeCommand.permissionMode(ToolApproval.ALLOW_ALL, legacy = null))
        assertEquals("plan", ClaudeCommand.permissionMode(ToolApproval.DENY, legacy = " plan "), "the legacy variable still wins")
        for (mode in ToolApproval.entries) {
            val argv = ClaudeCommand.command("claude", null, ClaudeCommand.permissionMode(mode, legacy = null), emptyList(), false, null)
            assertEquals(infantryRules, argv[argv.indexOf("--allowedTools") + 1], mode.name)
            assertEquals(1, argv.count { it == "--allowedTools" })
        }
        assertEquals("mcp__infantry__*", ClaudeCommand.mcpToolRule("infantry"))
    }

    @Test fun `watch settings are a pre and post http hook, and absent when the watch is off`() {
        val env = mapOf("INFANTRY_WATCH" to "on", ClaudeCommand.MAIL_BASE_ENV to "http://127.0.0.1:7777/")
        val text = ClaudeCommand.watchSettings(env)!!
        assertTrue("http://127.0.0.1:7777/watch/rewrite/claude\"" in text, text)
        assertTrue("/watch/rewrite/claude-post" in text, text)
        assertTrue("Bearer \$INFANTRY_TOKEN" in text && "Bash|PowerShell" in text, "the token stays out of the argv: $text")
        assertNull(ClaudeCommand.watchSettings(mapOf(ClaudeCommand.MAIL_BASE_ENV to "http://x")))
        assertNull(ClaudeCommand.watchSettings(emptyMap()))
    }

    @Test fun `context limit env, effort levels and the command catalog`() {
        assertEquals(mapOf("CLAUDE_CODE_AUTO_COMPACT_WINDOW" to "150000"), ClaudeCommand.contextLimitEnv(150_000))
        for (limit in listOf(null, 0, -5)) assertEquals(emptyMap(), ClaudeCommand.contextLimitEnv(limit), "limit=$limit")
        assertEquals(listOf("low", "medium", "high", "xhigh", "max"), ClaudeCommand.EFFORT_LEVELS)
        assertEquals(listOf("model", "effort", "compact"), ClaudeCommand.COMMANDS.map { it.name })
    }

    @Test fun `mcp configs keep the user's first and add infantry as a file`() {
        assertEquals(listOf("a.json", "b.json", "{\"x\":1}"), ClaudeCommand.parseMcpConfigs("a.json, b.json\n{\"x\":1}"))
        val files = mutableListOf<String>()
        val configs = ClaudeCommand.mcpConfigs(listOf("user.json"), listOf("infantry", "mcp", "serve"), mapOf("INFANTRY_SESSION_ID" to "s-1")) { json -> files += json; "entry.json" }
        assertEquals(listOf("user.json", "entry.json"), configs)
        assertTrue("\"infantry\"" in files.single() && "\"mcpServers\"" in files.single(), files.single())
        assertEquals(listOf("user.json"), ClaudeCommand.mcpConfigs(listOf("user.json"), null, emptyMap()))
    }

    @Test fun `stdin lines of the persistent session`() {
        val user = ClaudeSession.userLine("hello \"x\"\nbye")
        assertTrue(user.startsWith("{") && "\"type\":\"user\"" in user && "hello \\\"x\\\"\\nbye" in user, user)
        assertFalse('\n' in user, "one JSON object per line")
        val interrupt = ClaudeSession.interruptLine("r-1")
        assertTrue("interrupt" in interrupt && "r-1" in interrupt, interrupt)
    }

    private fun events(lines: List<String>, exit: Int = 0, err: String = ""): List<String> {
        val t = ClaudeStreamTranslator()
        return lines.flatMap { t.translate(it) }.map { it.toString() } + t.terminal(exit, err).toString()
    }

    @Test fun `a recorded stream gives the session, text, tool call, usage and a finished terminal`() {
        val out = events(
            listOf(
                """{"type":"system","subtype":"init","session_id":"s-1","model":"claude-sonnet-5-5"}""",
                """{"type":"assistant","message":{"content":[{"type":"thinking","thinking":"plan"},{"type":"text","text":"Hello"},{"type":"tool_use","id":"t1","name":"Bash","input":{"command":"ls"}}]}}""",
                """{"type":"user","message":{"content":[{"type":"tool_result","tool_use_id":"t1","content":"a.txt"}]}}""",
                "not json",
                """{"type":"result","subtype":"success","is_error":false,"result":"ok","usage":{"input_tokens":12,"output_tokens":34,"cache_creation_input_tokens":100,"cache_read_input_tokens":1000}}""",
            ),
        ).joinToString("\n")
        for (needle in listOf("s-1", "plan", "Hello", "Bash", "a.txt", "12", "34", "1000", "100")) assertTrue(needle in out, "missing '$needle' in:\n$out")
    }

    @Test fun `a failed run and a cut-short run end in a failure terminal`() {
        val failed = events(listOf("""{"type":"system","subtype":"init","session_id":"s-2"}""", """{"type":"result","subtype":"error_during_execution","is_error":true,"result":"API Error: 401"}""")).last()
        assertTrue("API Error: 401" in failed, failed)
        val cut = events(listOf("""{"type":"system","subtype":"init","session_id":"s-3"}"""), exit = 3, err = "boom").last()
        assertTrue("boom" in cut || "3" in cut, cut)
    }

    @Test fun `blank optional values add no flag and the model list is the catalog`() {
        val argv = ClaudeCommand.command("claude", " ", " ", emptyList(), false, " ", effort = "")
        for (flag in listOf("--model", "--effort", "--permission-mode", "--resume", "--mcp-config")) assertFalse(flag in argv, flag)
        val models = ClaudeCommand.models().single()
        assertEquals("claude", models.group)
        assertTrue(models.models.isNotEmpty() && models.models == ClaudeCommand.MODELS.distinct())
    }
}
