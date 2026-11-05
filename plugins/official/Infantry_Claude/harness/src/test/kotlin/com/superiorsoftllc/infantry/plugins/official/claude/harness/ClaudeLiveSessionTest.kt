// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.plugins.official.claude.harness

import com.superiorsoftllc.infantry.harness.api.ApprovalReply
import com.superiorsoftllc.infantry.harness.api.HarnessRequest
import com.superiorsoftllc.infantry.harness.api.ToolApproval
import com.superiorsoftllc.infantry.harness.api.SessionOutput
import com.superiorsoftllc.infantry.harness.api.SessionTurnEnd
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** CLI-LIVE-1: the stream-json line protocol of ONE live claude process, driven through a recording [SessionOutput]. */
class ClaudeLiveSessionTest {
    private class Recorder(var writeOk: Boolean = true, val approval: (String, String) -> ApprovalReply = { _, _ -> ApprovalReply.DENY }) : SessionOutput {
        val written: MutableList<String> = java.util.Collections.synchronizedList(mutableListOf())
        val asked: MutableList<Pair<String, String>> = java.util.Collections.synchronizedList(mutableListOf())
        override fun requestApproval(tool: String, input: String): ApprovalReply { asked += tool to input; return approval(tool, input) }
        val ends = mutableListOf<Triple<SessionTurnEnd, Int?, String?>>()
        var inputClosed = false
        override fun write(text: String): Boolean { if (writeOk) written += text; return writeOk }
        override fun closeInput() { inputClosed = true }
        override fun detail(text: String) {}
        override fun providerSession(id: String) {}
        override fun turnEnded(end: SessionTurnEnd, exitCode: Int?, reason: String?) { ends += Triple(end, exitCode, reason) }
        val starts = mutableListOf<String?>()
        override fun turnStarted(reason: String?) { starts += reason }
    }

    private val session = ClaudeSession({ "claude" }, { null to emptyMap() }, requestId = { "req-1" })
    private fun request(prompt: String) = HarnessRequest(prompt = prompt, harnessId = "claude", project = "p", model = "haiku", sessionId = "s")
    private val ok = """{"type":"result","subtype":"success","is_error":false,"result":"done"}"""

    @Test fun `every wake is one stdin line into the same process`() {
        val out = Recorder()
        val c = session.connect(out)
        c.sendTurn(request("one")); c.onStdout(ok)
        c.sendTurn(request("two")); c.onStdout(ok)
        assertEquals(2, out.written.size)
        assertTrue(out.written.all { it.endsWith("\n") && it.count { ch -> ch == '\n' } == 1 }, "one JSON line per message")
        assertTrue("\"text\":\"two\"" in out.written[1])
        assertFalse(out.inputClosed, "the input stays open between turns")
    }

    @Test fun `a turn ends on the result record and not before`() {
        val out = Recorder()
        val c = session.connect(out)
        c.sendTurn(request("go"))
        c.onStdout("""{"type":"assistant","message":{}}""")
        assertTrue(out.ends.isEmpty(), "streamed records do not end the turn")
        c.onStdout(ok)
        assertEquals(listOf(Triple<SessionTurnEnd, Int?, String?>(SessionTurnEnd.COMPLETED, 0, null)), out.ends)
    }

    @Test fun `an error result fails the turn with the cli's reason`() {
        val out = Recorder()
        val c = session.connect(out)
        c.sendTurn(request("go"))
        c.onStdout("""{"type":"result","subtype":"error_max_turns","is_error":true,"result":"too many turns"}""")
        assertEquals(SessionTurnEnd.FAILED, out.ends.single().first)
        assertEquals("too many turns", out.ends.single().third)
    }

    @Test fun `a message sent while a turn runs is written after the interrupt result, never lost`() {
        val out = Recorder()
        val c = session.connect(out)
        c.sendTurn(request("long"))
        assertTrue(c.abortTurn(), "interrupt is accepted")
        assertTrue(out.written.last().contains("\"subtype\":\"interrupt\""))
        c.sendTurn(request("mail"))
        c.onStdout("""{"type":"result","subtype":"error_during_execution","is_error":true}""")
        assertTrue(out.ends.isEmpty(), "the superseded turn's result ends nothing")
        c.onStdout(ok)
        assertEquals(listOf(Triple<SessionTurnEnd, Int?, String?>(SessionTurnEnd.COMPLETED, 0, null)), out.ends)
    }

    @Test fun `interrupt keeps the process alive`() {
        val out = Recorder()
        val c = session.connect(out)
        c.sendTurn(request("long"))
        assertTrue(c.abortTurn())
        c.onStdout("""{"type":"result","subtype":"error_during_execution","is_error":true}""")
        assertEquals(SessionTurnEnd.INTERRUPTED, out.ends.single().first)
        assertFalse(out.inputClosed, "an interrupt never closes stdin, so the process and its background jobs live on")
    }

    @Test fun `an unwritable stdin fails the turn so the host can start a new process with resume`() {
        val out = Recorder(writeOk = false)
        val c = session.connect(out)
        c.sendTurn(request("go"))
        assertEquals(SessionTurnEnd.FAILED, out.ends.single().first)
    }

    @Test fun `the idle limit is the module setting and 0 means never`() {
        assertEquals(0, session.idleMinutes())
        val tuned = ClaudeSession({ "claude" }, { null to emptyMap() }, idleMinutes = { 45 })
        assertEquals(45, tuned.idleMinutes())
    }

    // ---- CLI-PTY-4: a turn claude begins by itself ---------------------------------------------------------------------

    @Test fun `a reply with no turn sent is a turn of claude's own, reported once and ended by its result`() {
        val out = Recorder()
        val c = session.connect(out)
        c.sendTurn(request("go")); c.onStdout(ok)
        // claude took up a background task's notice by itself
        c.onStdout("""{"type":"assistant","message":{"id":"m1"}}""")
        c.onStdout("""{"type":"assistant","message":{"id":"m2"}}""")
        assertEquals(1, out.starts.size, "one start per own turn")
        assertEquals(1, out.ends.size, "only the sent turn has ended so far")
        c.onStdout(ok)
        assertEquals(2, out.ends.size, "the own turn ends on its result")
    }

    @Test fun `a turn sent while claude runs one of its own ends after it, in order`() {
        val out = Recorder()
        val c = session.connect(out)
        c.onStdout("""{"type":"assistant","message":{"id":"m1"}}""")
        assertEquals(1, out.starts.size)
        c.sendTurn(request("mail"))
        c.onStdout(ok) // the own turn's result
        assertEquals(1, out.ends.size)
        c.onStdout("""{"type":"assistant","message":{"id":"m2"}}""")
        assertEquals(1, out.starts.size, "a reply inside the sent turn is no new turn")
        c.onStdout(ok) // the sent turn's result
        assertEquals(2, out.ends.size)
    }

    // G4 (ticket c2f05317): Tool approval ASK on headless claude, the `can_use_tool` control request relayed to the owner.
    private fun canUseTool(id: String, tool: String, input: String) =
        """{"type":"control_request","request_id":"$id","request":{"subtype":"can_use_tool","tool_name":"$tool","input":$input,"permission_suggestions":[]}}"""

    private fun awaitWrites(out: Recorder, n: Int): List<String> {
        val deadline = System.currentTimeMillis() + 5_000
        while (out.written.size < n && System.currentTimeMillis() < deadline) Thread.sleep(10)
        return synchronized(out.written) { out.written.toList() }
    }

    private fun launchArgv(approval: ToolApproval) =
        session.launch(HarnessRequest(prompt = "p", harnessId = "claude", project = "p", toolApproval = approval), emptyMap()).command

    @Test fun `ask launches with the stdio permission prompt tool, no other mode does`() {
        val ask = launchArgv(ToolApproval.ASK)
        assertEquals(ClaudeCommand.PERMISSION_PROMPT_STDIO, ask[ask.indexOf("--permission-prompt-tool") + 1])
        for (mode in listOf(ToolApproval.DENY, ToolApproval.ALLOW_EDITS, ToolApproval.ALLOW_ALL)) {
            assertFalse("--permission-prompt-tool" in launchArgv(mode), "$mode never asks the owner")
        }
    }

    @Test fun `an approved call is allowed with its own input`() {
        val out = Recorder(approval = { _, _ -> ApprovalReply.ALLOW_ONCE })
        val c = session.connect(out)
        c.sendTurn(request("go"))
        c.onStdout(canUseTool("cr-1", "Bash", """{"command":"rm -rf build"}"""))
        val reply = awaitWrites(out, 2)[1]
        assertEquals(listOf("Bash" to "rm -rf build"), out.asked.toList(), "the owner reads the shell command")
        assertTrue(reply.endsWith("\n"))
        assertTrue("\"type\":\"control_response\"" in reply && "\"request_id\":\"cr-1\"" in reply, reply)
        assertTrue("\"behavior\":\"allow\"" in reply && "\"updatedInput\":{\"command\":\"rm -rf build\"}" in reply, reply)
        assertTrue(out.ends.isEmpty(), "an approval does not end the turn")
    }

    @Test fun `a denied or timed-out call is refused with a message`() {
        // The host's requestApproval answers DENY for a deny, a prompt timeout and a session that ended alike.
        val out = Recorder(approval = { _, _ -> ApprovalReply.DENY })
        val c = session.connect(out)
        c.sendTurn(request("go"))
        c.onStdout(canUseTool("cr-2", "Write", """{"file_path":"a.txt","content":"x"}"""))
        val reply = awaitWrites(out, 2)[1]
        assertEquals("Write", out.asked.single().first)
        assertTrue("\"file_path\"" in out.asked.single().second, "a non-shell call shows its input JSON")
        assertTrue("\"behavior\":\"deny\"" in reply && "\"message\":" in reply && "updatedInput" !in reply, reply)
    }

    @Test fun `a failing approval prompt denies, never allows`() {
        val out = Recorder(approval = { _, _ -> error("host gone") })
        val c = session.connect(out)
        c.onStdout(canUseTool("cr-3", "Bash", """{"command":"ls"}"""))
        assertTrue("\"behavior\":\"deny\"" in awaitWrites(out, 1)[0])
    }

    @Test fun `an unknown control request is answered with an error at once`() {
        val out = Recorder()
        val c = session.connect(out)
        c.onStdout("""{"type":"control_request","request_id":"cr-4","request":{"subtype":"hook_callback"}}""")
        val reply = out.written.single()
        assertTrue("\"subtype\":\"error\"" in reply && "\"request_id\":\"cr-4\"" in reply, reply)
        assertTrue(out.asked.isEmpty())
    }
}
