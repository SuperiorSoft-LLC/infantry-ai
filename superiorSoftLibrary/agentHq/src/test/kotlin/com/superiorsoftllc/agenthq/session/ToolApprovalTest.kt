package com.superiorsoftllc.agenthq.session

import com.superiorsoftllc.agenthq.claude.ClaudeCommand
import com.superiorsoftllc.agenthq.claude.ClaudePermissionMode
import com.superiorsoftllc.agenthq.daemon.denialEnvelope
import com.superiorsoftllc.agenthq.harness.ToolApproval
import com.superiorsoftllc.agenthq.protocol.Envelope
import com.superiorsoftllc.agenthq.protocol.EventNormalizer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * BUG-PERM-1: the harness-neutral Tool approval. Its resolution order (session, setting, AGENTHQ_TOOL_APPROVAL, deny),
 * Claude's mapping onto `--permission-mode` (the legacy AGENTHQ_CLAUDE_PERMISSION_MODE still wins), and the denial
 * notice a Claude `result` with `permission_denials` produces.
 */
class ToolApprovalTest {
    private val none: (String) -> String? = { null }
    private fun only(name: String, value: String): (String) -> String? = { if (it == name) value else null }

    /** The real argv for [approval], with the legacy env unset. */
    private fun claudeArgv(approval: ToolApproval): List<String> = ClaudeCommand.command(
        executable = "claude",
        model = null,
        permissionMode = ClaudePermissionMode.forApproval(approval, none, none),
        mcpConfigs = emptyList(),
        strictMcp = false,
        resumeSessionId = null,
    )

    private fun permissionFlag(argv: List<String>): String? =
        argv.indexOf("--permission-mode").takeIf { it >= 0 }?.let { argv[it + 1] }

    // --- Claude argv, one per mode ---------------------------------------------------------------------------------

    @Test
    fun denyPassesNoPermissionFlag() {
        val argv = claudeArgv(ToolApproval.DENY)
        assertFalse("--permission-mode" in argv, "deny = Claude's own default, no flag: $argv")
    }

    /** BUG-PERM-5: the agenthq CLI is allowed in every mode; nothing else (e.g. `git status`) is. */
    @Test
    fun everyModeAllowsTheAgentHqCliAndNothingElse() {
        for (mode in ToolApproval.entries) {
            val argv = claudeArgv(mode)
            val at = argv.indexOf("--allowedTools")
            assertTrue(at >= 0, "${mode.wire}: no --allowedTools in $argv")
            val rules = argv[at + 1].split(',')
            assertEquals(listOf("Bash(agenthq:*)", "PowerShell(agenthq:*)", "mcp__agenthq__*"), rules, mode.wire) // MCP-1: + its MCP tools
            assertFalse(rules.any { "git" in it || it == "Bash" || it == "PowerShell" || "(*)" in it }, "${mode.wire} widened beyond agenthq: $rules")
            assertEquals(1, argv.count { it == "--allowedTools" }, mode.wire)
        }
    }

    @Test
    fun allowEditsPassesAcceptEdits() {
        assertEquals("acceptEdits", permissionFlag(claudeArgv(ToolApproval.ALLOW_EDITS)))
    }

    @Test
    fun allowAllPassesBypassPermissions() {
        assertEquals("bypassPermissions", permissionFlag(claudeArgv(ToolApproval.ALLOW_ALL)))
    }

    @Test
    fun theLegacyClaudeVariableStillWinsForClaude() {
        val legacy = only(ClaudePermissionMode.LEGACY_ENV, "plan")
        assertEquals("plan", ClaudePermissionMode.forApproval(ToolApproval.DENY, none, legacy))
        assertEquals("plan", ClaudePermissionMode.forApproval(ToolApproval.ALLOW_ALL, none, legacy))
    }

    @Test
    fun theRunRequestHookReachesTheClaudeSpec() {
        // spec() is what the provider path calls with RunRequest.toolApproval. The legacy env would override it, so the
        // assertion only holds where it is unset (it is unset on every CI and dev box this board uses).
        if (com.superiorsoftllc.agenthq.session.agentHqSetting(ClaudePermissionMode.LEGACY_ENV) != null) return
        val argv = ClaudeCommand.spec(executable = "claude", toolApproval = ToolApproval.ALLOW_ALL).command
        assertEquals("bypassPermissions", permissionFlag(argv))
    }

    // --- resolution order ------------------------------------------------------------------------------------------

    @Test
    fun theSessionOverrideWinsOverEverything() {
        val env = only(ToolApprovals.ENV, "allowAll")
        assertEquals(
            ToolApprovals.Resolution(ToolApproval.DENY, "session"),
            ToolApprovals.resolve(ToolApproval.DENY, ToolApproval.ALLOW_EDITS, none, env),
        )
    }

    @Test
    fun theSettingWinsOverTheEnvironment() {
        val env = only(ToolApprovals.ENV, "allowAll")
        assertEquals(
            ToolApprovals.Resolution(ToolApproval.ALLOW_EDITS, "setting"),
            ToolApprovals.resolve(null, ToolApproval.ALLOW_EDITS, none, env),
        )
    }

    @Test
    fun theEnvironmentAppliesWhenNothingIsSetAndThePropertyBeatsTheEnv() {
        assertEquals(
            ToolApprovals.Resolution(ToolApproval.ALLOW_ALL, "env"),
            ToolApprovals.resolve(null, null, none, only(ToolApprovals.ENV, "allowAll")),
        )
        assertEquals(
            ToolApproval.ALLOW_EDITS,
            ToolApprovals.resolve(null, null, only(ToolApprovals.ENV, "allowEdits"), only(ToolApprovals.ENV, "allowAll")).mode,
        )
    }

    @Test
    fun nothingSetMeansDenyAndAnUnknownEnvValueIsIgnored() {
        assertEquals(ToolApprovals.Resolution(ToolApproval.DENY, "default"), ToolApprovals.resolve(null, null, none, none))
        assertEquals(ToolApprovals.Resolution(ToolApproval.DENY, "default"), ToolApprovals.resolve(null, null, none, only(ToolApprovals.ENV, "yolo")))
    }

    @Test
    fun parsingIsStrictWhereTheUserTyped() {
        assertEquals(ToolApproval.ALLOW_EDITS, ToolApproval.parseStrict(" allowEdits "))
        assertNull(ToolApproval.parseStrict(" "))
        assertFailsWith<IllegalArgumentException> { ToolApproval.parseStrict("bypassPermissions") }
    }

    // --- the denial notice -----------------------------------------------------------------------------------------

    @Test
    fun aClaudeResultWithPermissionDenialsProducesTheNotice() {
        val result = """{"type":"result","subtype":"success","is_error":false,"result":"I need approval to run agenthq.",""" +
            """"permission_denials":[{"tool_name":"Bash","tool_use_id":"toolu_1","tool_input":{"command":"agenthq ticket new"}},""" +
            """{"tool_name":"Bash","tool_use_id":"toolu_2","tool_input":{"command":"agenthq ticket new"}},""" +
            """{"tool_name":"Write","tool_use_id":"toolu_3","tool_input":{}}]}"""

        val denials = EventNormalizer.normalise(result).filterIsInstance<EventNormalizer.Line.Denial>()
        assertEquals(listOf(EventNormalizer.Line.Denial("claude", "Bash"), EventNormalizer.Line.Denial("claude", "Write")), denials)

        val frame = denialEnvelope("s1", denials.first(), ToolApproval.DENY)
        assertEquals(Envelope.STDERR, frame.type)
        assertEquals("s1", frame.sessionId)
        assertEquals(
            "[agenthq] claude denied tool Bash (Tool approval = deny). Change it in Settings > Tool approval.",
            frame.data,
        )
    }

    @Test
    fun aResultWithoutDenialsProducesNoDenialAndAFailureIsKept() {
        assertTrue(EventNormalizer.normalise("""{"type":"result","subtype":"success","permission_denials":[]}""").isEmpty())
        val failed = EventNormalizer.normalise("""{"type":"result","subtype":"error_max_turns","is_error":true,"permission_denials":[{"tool_name":"Bash"}]}""")
        assertEquals(EventNormalizer.Line.Denial("claude", "Bash"), failed.first())
        assertTrue(failed.any { it is EventNormalizer.Line.Failure }, "the failure is still reported: $failed")
    }
}
