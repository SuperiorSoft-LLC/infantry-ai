package com.superiorsoftllc.infantry.session

import com.superiorsoftllc.infantry.daemon.denialEnvelope
import com.superiorsoftllc.infantry.harness.ToolApproval
import com.superiorsoftllc.infantry.protocol.Envelope
import com.superiorsoftllc.infantry.protocol.EventNormalizer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * BUG-PERM-1: the harness-neutral Tool approval. Its resolution order (session, setting, INFANTRY_TOOL_APPROVAL, deny),
 * Claude's mapping onto `--permission-mode` (the legacy INFANTRY_CLAUDE_PERMISSION_MODE still wins), and the denial
 * notice a Claude `result` with `permission_denials` produces.
 */
class ToolApprovalTest {
    private val none: (String) -> String? = { null }
    private fun only(name: String, value: String): (String) -> String? = { if (it == name) value else null }

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
        val result = """{"type":"result","subtype":"success","is_error":false,"result":"I need approval to run infantry.",""" +
            """"permission_denials":[{"tool_name":"Bash","tool_use_id":"toolu_1","tool_input":{"command":"infantry ticket new"}},""" +
            """{"tool_name":"Bash","tool_use_id":"toolu_2","tool_input":{"command":"infantry ticket new"}},""" +
            """{"tool_name":"Write","tool_use_id":"toolu_3","tool_input":{}}]}"""

        val denials = EventNormalizer.normalise(result).filterIsInstance<EventNormalizer.Line.Denial>()
        assertEquals(listOf(EventNormalizer.Line.Denial("claude", "Bash"), EventNormalizer.Line.Denial("claude", "Write")), denials)

        val frame = denialEnvelope("s1", denials.first(), ToolApproval.DENY)
        assertEquals(Envelope.STDERR, frame.type)
        assertEquals("s1", frame.sessionId)
        assertEquals(
            "[infantry] claude denied tool Bash (Tool approval = deny). Change it in Settings > Tool approval.",
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
