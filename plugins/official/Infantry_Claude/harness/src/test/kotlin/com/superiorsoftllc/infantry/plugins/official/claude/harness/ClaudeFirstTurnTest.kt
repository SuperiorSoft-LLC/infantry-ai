// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.plugins.official.claude.harness

import com.superiorsoftllc.infantry.harness.api.HarnessRequest
import com.superiorsoftllc.infantry.harness.api.SessionOutput
import com.superiorsoftllc.infantry.harness.api.SessionTurnEnd
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * FIRST-TURN-1 (ticket 36419e4e): a started interactive claude whose first turn shows no sign of life gets one recovery, then fails with
 * its screen, instead of sitting silent until the host's stall watchdog kills it. Driven by hand (no terminal, no poller thread).
 */
class ClaudeFirstTurnTest {
    private val root: File = Files.createTempDirectory("claude-first-turn-test").toFile()
    private val config = File(root, "config")
    private val state = File(root, "state")
    private val cwd = File(root, "work tree").apply { mkdirs() }
    private var now = 1_000L
    private val id = "11111111-2222-3333-4444-555555555555"

    @AfterTest fun cleanup() { root.deleteRecursively() }

    private class Recorder : SessionOutput {
        val written = mutableListOf<String>()
        val ends = mutableListOf<Triple<SessionTurnEnd, Int?, String?>>()
        val details = mutableListOf<String>()
        override fun write(text: String): Boolean { written += text; return true }
        override fun closeInput() {}
        override fun detail(text: String) { details += text }
        override fun providerSession(id: String) {}
        override fun turnEnded(end: SessionTurnEnd, exitCode: Int?, reason: String?) { ends += Triple(end, exitCode, reason) }
    }

    private class Started(val out: Recorder, val c: ClaudeTtySession.TtyConnection, val events: File) {
        val pastes get() = out.written.filter { it.startsWith(ClaudeTtySession.PASTE_START) }
    }

    private fun session(limits: ClaudeFirstTurn) = ClaudeTtySession(
        executable = { "claude.exe" }, mcp = { null to emptyMap() }, stateDir = { state }, configDir = { config },
        newSessionId = { id }, clock = { now }, poll = false, firstTurn = { limits },
    )

    private fun request(prompt: String) =
        HarnessRequest(prompt = prompt, harnessId = "claude", project = cwd.absolutePath, model = "sonnet")

    private val limits = ClaudeFirstTurn(submitSeconds = 30, replySeconds = 300)

    /** Launched, connected and up (SessionStart ran, the ready delay passed). */
    private fun up(limits: ClaudeFirstTurn = this.limits): Started {
        val s = session(limits)
        val launched = s.launch(request(""), emptyMap())
        val out = Recorder()
        val c = s.connect(out, launched) as ClaudeTtySession.TtyConnection
        val events = File(File(state, ClaudeTtySession.TTY_DIR), "$id.events")
        val started = Started(out, c, events)
        c.onStdout("\u001b[2J welcome")
        event(started, "start")
        c.tick()
        now += ClaudeTtySession.READY_AFTER_START_MS
        return started
    }

    private fun event(s: Started, e: String) = s.events.appendText("$e\r\n")

    private val transcript get() = File(File(File(config, "projects"), ClaudeTranscripts.slug(cwd.absolutePath)), "$id.jsonl")

    private fun record(line: String) { transcript.parentFile.mkdirs(); transcript.appendText(line + "\n") }

    private val prompt = """{"type":"user","uuid":"u-p","entrypoint":"cli","message":{"role":"user","content":"BRIEF"}}"""
    private val assistant =
        """{"type":"assistant","uuid":"u-a","message":{"id":"m1","role":"assistant","model":"claude-sonnet-5-5","content":[{"type":"text","text":"on it"}],"usage":{"input_tokens":3,"output_tokens":5}}}"""

    /** The turn is pasted, Enter pressed, and the CLI took it (UserPromptSubmit + the prompt recorded). */
    private fun pastedAndTaken(s: Started, text: String = "BRIEF: do the ticket") {
        s.c.sendTurn(request(text))
        s.c.tick()
        now += ClaudeTtySession.PASTE_SETTLE_MS
        s.c.tick()
        event(s, "submit")
        record(prompt)
        s.c.tick()
    }

    @Test fun `a first request that never answers is stopped and nudged once, then the turn fails with the screen`() {
        // The owner's case (session e73cf3f2, ticket 02c1fe44): submit seen, one api-request sent, then nothing for 15 minutes.
        val s = up()
        pastedAndTaken(s)
        s.c.onStdout("✻ Thinking… (esc to interrupt)")
        now += limits.replyMs - 1
        s.c.tick()
        assertFalse(ClaudeTtySession.ESC in s.out.written, "nothing before the deadline")

        now += 1
        s.c.tick()
        assertEquals(ClaudeTtySession.ESC, s.out.written.last(), "the stuck request is stopped with Esc")
        assertTrue(s.out.ends.isEmpty(), "a recovery, not a failure yet")
        assertTrue(s.out.details.any { "got no reply" in it && "Esc" in it }, "${s.out.details}")

        record("""{"type":"user","message":{"role":"user","content":[{"type":"text","text":"[Request interrupted by user]"}]}}""")
        s.c.tick()
        assertEquals(ClaudeTtySession.PASTE_START + ClaudeFirstTurn.NUDGE + ClaudeTtySession.PASTE_END, s.pastes.last(), "the nudge carries on")
        now += ClaudeTtySession.PASTE_SETTLE_MS
        s.c.tick()
        assertEquals(ClaudeTtySession.ENTER, s.out.written.last())
        event(s, "submit")
        s.c.tick()

        now += limits.replyMs
        s.c.tick()
        val end = s.out.ends.single()
        assertEquals(SessionTurnEnd.FAILED, end.first)
        assertTrue("got no reply 300 s" in end.third!! && "after one recovery" in end.third!!, end.third)
        assertTrue("screen:" in end.third!! && "Thinking" in end.third!!, "the reason carries the screen tail: ${end.third}")
        assertEquals(ClaudeTtySession.ESC, s.out.written.last(), "the CLI is left at its prompt")
    }

    @Test fun `a reply after the nudge is a normal turn`() {
        val s = up()
        pastedAndTaken(s)
        now += limits.replyMs
        s.c.tick()
        now += ClaudeTtySession.ABORT_GRACE_MS
        s.c.tick() // nudge pasted (no interrupt record: the grace passed)
        now += ClaudeTtySession.PASTE_SETTLE_MS
        s.c.tick()
        event(s, "submit")
        record(assistant)
        s.c.tick()
        now += limits.replyMs * 2
        s.c.tick()
        assertTrue(s.out.ends.isEmpty(), "the model answered: no failure")
        event(s, "stop")
        s.c.tick()
        now += ClaudeTtySession.STOP_SETTLE_MS
        s.c.tick()
        assertEquals(SessionTurnEnd.COMPLETED, s.out.ends.single().first)
    }

    @Test fun `a first reply inside the deadline changes nothing, and a later turn is not watched`() {
        val s = up()
        pastedAndTaken(s)
        now += limits.replyMs - 1_000
        record(assistant)
        s.c.tick()
        now += limits.replyMs * 3
        s.c.tick()
        assertFalse(ClaudeTtySession.ESC in s.out.written)
        event(s, "stop"); s.c.tick(); now += ClaudeTtySession.STOP_SETTLE_MS; s.c.tick()
        assertEquals(SessionTurnEnd.COMPLETED, s.out.ends.single().first)

        s.c.sendTurn(request("mail: next"))
        s.c.tick(); now += ClaudeTtySession.PASTE_SETTLE_MS; s.c.tick()
        event(s, "submit"); s.c.tick()
        now += limits.replyMs * 3
        s.c.tick()
        assertEquals(1, s.out.ends.size, "only the first turn is held to the deadline")
        assertFalse(ClaudeTtySession.ESC in s.out.written)
    }

    @Test fun `a paste the cli never took is pasted again once, then the turn fails with the screen`() {
        val s = up()
        s.c.sendTurn(request("BRIEF: do the ticket"))
        s.c.tick()
        s.c.onStdout("Update available! Run: claude update")
        now += ClaudeTtySession.PASTE_SETTLE_MS
        s.c.tick()
        now += limits.submitMs
        s.c.tick()
        assertEquals(2, s.pastes.size, "the prompt is not on screen: pasted again")
        assertTrue(s.out.ends.isEmpty())
        now += ClaudeTtySession.PASTE_SETTLE_MS
        s.c.tick()
        assertEquals(ClaudeTtySession.ENTER, s.out.written.last())

        now += limits.submitMs
        s.c.tick()
        val end = s.out.ends.single()
        assertEquals(SessionTurnEnd.FAILED, end.first)
        assertTrue("took no prompt 30 s" in end.third!! && "pasted again" in end.third!!, end.third)
        assertTrue("Update available" in end.third!!, "the screen tail says why: ${end.third}")
    }

    @Test fun `a paste that sits in the input box gets Enter again, not a second paste`() {
        val s = up()
        s.c.sendTurn(request("BRIEF: do the ticket"))
        s.c.tick()
        s.c.onStdout("> [Pasted text #1 +40 lines]")
        now += ClaudeTtySession.PASTE_SETTLE_MS
        s.c.tick()
        now += limits.submitMs
        s.c.tick()
        assertEquals(1, s.pastes.size)
        assertEquals(ClaudeTtySession.ENTER, s.out.written.last())
        event(s, "submit")
        record(prompt)
        s.c.tick()
        record(assistant)
        s.c.tick()
        now += limits.replyMs * 2
        s.c.tick()
        assertTrue(s.out.ends.isEmpty(), "taken and answered after the recovery")
    }

    @Test fun `zero turns the deadlines off`() {
        val s = up(ClaudeFirstTurn(submitSeconds = 0, replySeconds = 0))
        s.c.sendTurn(request("BRIEF"))
        s.c.tick(); now += ClaudeTtySession.PASTE_SETTLE_MS; s.c.tick()
        now += 3_600_000
        s.c.tick()
        event(s, "submit"); s.c.tick()
        now += 3_600_000
        s.c.tick()
        assertTrue(s.out.ends.isEmpty())
        assertEquals(1, s.pastes.size)
        assertFalse(ClaudeTtySession.ESC in s.out.written)
    }

    @Test fun `the settings read whole seconds, blank or bad keeps the default, zero is off`() {
        assertEquals(ClaudeFirstTurn(), ClaudeFirstTurn.of(null, " "))
        assertEquals(ClaudeFirstTurn(10, 0), ClaudeFirstTurn.of(" 10 ", "0"))
        assertEquals(ClaudeFirstTurn(), ClaudeFirstTurn.of("-5", "soon"))
    }
}
