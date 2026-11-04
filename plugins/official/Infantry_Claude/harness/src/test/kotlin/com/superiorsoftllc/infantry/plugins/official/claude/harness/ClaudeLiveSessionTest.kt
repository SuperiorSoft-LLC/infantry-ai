// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.plugins.official.claude.harness

import com.superiorsoftllc.infantry.harness.api.HarnessRequest
import com.superiorsoftllc.infantry.harness.api.SessionOutput
import com.superiorsoftllc.infantry.harness.api.SessionTurnEnd
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** CLI-LIVE-1: the stream-json line protocol of ONE live claude process, driven through a recording [SessionOutput]. */
class ClaudeLiveSessionTest {
    private class Recorder(var writeOk: Boolean = true) : SessionOutput {
        val written = mutableListOf<String>()
        val ends = mutableListOf<Triple<SessionTurnEnd, Int?, String?>>()
        var inputClosed = false
        override fun write(text: String): Boolean { if (writeOk) written += text; return writeOk }
        override fun closeInput() { inputClosed = true }
        override fun detail(text: String) {}
        override fun providerSession(id: String) {}
        override fun turnEnded(end: SessionTurnEnd, exitCode: Int?, reason: String?) { ends += Triple(end, exitCode, reason) }
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
}
