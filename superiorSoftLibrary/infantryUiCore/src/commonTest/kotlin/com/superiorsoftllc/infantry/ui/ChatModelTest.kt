// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.ui

import com.superiorsoftllc.infantry.ui.model.AppState
import com.superiorsoftllc.infantry.ui.model.ConnectionInfo
import com.superiorsoftllc.infantry.ui.model.HistoryRequest
import com.superiorsoftllc.infantry.ui.model.LineKind
import com.superiorsoftllc.infantry.ui.model.historyRequest
import com.superiorsoftllc.infantry.ui.model.promptBlocker
import com.superiorsoftllc.infantry.ui.model.reduce
import com.superiorsoftllc.infantry.ui.model.resyncRequests
import com.superiorsoftllc.infantry.ui.model.withPendingSpawn
import com.superiorsoftllc.infantry.ui.protocol.Envelope
import com.superiorsoftllc.infantry.ui.protocol.FinishedPayload
import com.superiorsoftllc.infantry.ui.protocol.ReplayGap
import com.superiorsoftllc.infantry.ui.protocol.SessionRecord
import com.superiorsoftllc.infantry.ui.protocol.SessionState
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Talking to an agent (contract 1.11.0): the transcript is the session's own sequenced stream, so it can
 * be replayed into any window, in order, exactly once -- and a follow-up goes to the session on screen.
 */
class ChatModelTest {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private fun rec(id: String, state: SessionState = SessionState.IDLE, psid: String? = "p", heartbeat: Long = 1) =
        SessionRecord(id, "stub", "/w", spawnedAt = 1, lastHeartbeat = heartbeat, state = state, providerSessionId = psid)

    private fun session(r: SessionRecord, seq: Long? = null) =
        Envelope(Envelope.SESSION, json.encodeToString(SessionRecord.serializer(), r), r.id, seq)

    private fun sessions(vararg r: SessionRecord) =
        Envelope(Envelope.SESSIONS, json.encodeToString(ListSerializer(SessionRecord.serializer()), r.toList()))

    private fun ready(instance: String = "A", since: Long = 0, contract: String = "1.11.0") =
        Envelope(Envelope.READY, "provider=stub contract=$contract instance=$instance since=$since")

    private fun text(id: String, seq: Long?, t: String) = Envelope(Envelope.TEXT_DELTA, t, id, seq)
    private fun reasoning(id: String, seq: Long?, t: String) = Envelope(Envelope.REASONING, t, id, seq)
    private fun turn(id: String, seq: Long?, t: String) = Envelope(Envelope.TURN, t, id, seq)
    private fun finished(id: String, seq: Long?) = Envelope(
        Envelope.FINISHED,
        json.encodeToString(FinishedPayload.serializer(), FinishedPayload(0, FinishedPayload.Outcome.SUCCEEDED)),
        id,
        seq,
    )

    private fun feed(vararg e: Envelope, from: AppState = AppState()) = e.fold(from) { s, f -> reduce(s, f, 100) }
    private fun AppState.lines(id: String) = agents.getValue(id).output.map { it.kind to it.text }

    @Test fun `a turn is the user's line, ahead of the reply it caused`() {
        val s = feed(ready(), session(rec("a"), 1), turn("a", 2, "why?"), text("a", 3, "be"), text("a", 4, "cause"), finished("a", 5))
        assertEquals(
            listOf(LineKind.USER to "why?", LineKind.TEXT to "because", LineKind.FINISHED to "finished: succeeded (exit 0)"),
            s.lines("a"),
        )
    }

    @Test fun `a frame already rendered is never rendered twice`() {
        val s = feed(ready(), session(rec("a"), 1), turn("a", 2, "q"), text("a", 3, "x"), text("a", 3, "x"), turn("a", 2, "q"))
        assertEquals(listOf(LineKind.USER to "q", LineKind.TEXT to "x"), s.lines("a"))
    }

    // UI-LOG-1: stderr and exit lines must not be dropped -- they become muted log lines.
    @Test fun `a stderr frame adds one log line with that text`() {
        val s = feed(ready(), session(rec("a"), 1), Envelope(Envelope.STDERR, "auth failed", "a", 2))
        assertEquals(listOf(LineKind.LOG to "auth failed"), s.lines("a"))
    }

    @Test fun `an exit frame adds a process-exited log line`() {
        val s = feed(ready(), session(rec("a"), 1), Envelope(Envelope.EXIT, "1", "a", 2))
        assertEquals(listOf(LineKind.LOG to "process exited with code 1"), s.lines("a"))
    }

    @Test fun `model reasoning is its own line, never merged into the answer`() {
        val s = feed(
            ready(), session(rec("a"), 1),
            reasoning("a", 2, "weighing the options"),
            text("a", 3, "here is "),
            reasoning("a", 4, "picking the second"),
            text("a", 5, "the answer"),
        )
        assertEquals(
            listOf(
                LineKind.REASONING to "weighing the options",
                LineKind.TEXT to "here is ",
                LineKind.REASONING to "picking the second",
                LineKind.TEXT to "the answer",
            ),
            s.lines("a"),
        )
    }

    @Test fun `a replayed frame that arrives after live ones is placed by its sequence`() {
        // Live frames 5 and 6 reach the window before the replay of 1..6 it asked for on opening the agent.
        val s = feed(
            ready(), sessions(rec("a")),
            text("a", 5, "world"), finished("a", 6),
            session(rec("a"), 1), turn("a", 2, "say hi"), text("a", 3, "hello "), Envelope(Envelope.STDOUT, "{raw}", "a", 4), text("a", 5, "world"), finished("a", 6),
        )
        assertEquals(
            listOf(LineKind.USER to "say hi", LineKind.TEXT to "hello world", LineKind.FINISHED to "finished: succeeded (exit 0)"),
            s.lines("a"),
        )
        assertEquals(6L, s.agents.getValue("a").cursor.contiguous, "the cursor closes over the filled hole")
    }

    @Test fun `a stale replayed session record never overrides a newer one`() {
        val s = feed(ready(), sessions(rec("a", SessionState.IDLE, heartbeat = 50)), session(rec("a", SessionState.STARTING, heartbeat = 10), 1))
        assertEquals(SessionState.IDLE, s.agents.getValue("a").session.state)
        val newer = reduce(s, session(rec("a", SessionState.BUSY, heartbeat = 60), 2), 100)
        assertEquals(SessionState.BUSY, newer.agents.getValue("a").session.state)
    }

    @Test fun `a replay gap moves the cursor past evicted frames and says so in the transcript`() {
        val gap = ReplayGap(requestedAfterSequence = 0, oldestAvailableSequence = 10, latestAvailableSequence = 12, evictedCount = 9)
        val s = feed(
            ready(), sessions(rec("a")),
            Envelope(Envelope.REPLAY_GAP, json.encodeToString(ReplayGap.serializer(), gap), "a"),
            text("a", 10, "kept"), finished("a", 11),
        )
        val agent = s.agents.getValue("a")
        assertEquals(11L, agent.cursor.contiguous)
        assertEquals(LineKind.STATUS, agent.output.first().kind)
        assertTrue(agent.output.first().text.contains("9"), agent.output.first().text)
        assertEquals(LineKind.TEXT to "kept", s.lines("a")[1])
    }

    @Test fun `a ready from another daemon process resets cursors but keeps the transcript`() {
        val before = feed(ready("A"), session(rec("a"), 1), turn("a", 2, "old question"), text("a", 3, "old answer"))
        // Same process again (a dropped socket): sequence 3 is still a duplicate.
        val sameProcess = feed(ready("A"), text("a", 3, "old answer"), from = before)
        assertEquals(before.lines("a"), sameProcess.lines("a"))
        // A restarted daemon numbers from 1 again: its frames are new, and land after the old transcript.
        val restarted = feed(ready("B"), session(rec("a", heartbeat = 9), 1), turn("a", 2, "new question"), from = before)
        assertEquals(LineKind.USER to "new question", restarted.lines("a").last())
        assertEquals(LineKind.USER to "old question", restarted.lines("a").first())
        assertEquals("B", restarted.connection.instance)
    }

    @Test fun `a spawn selects the session it created, not one that already existed`() {
        val pending = feed(ready(), sessions(rec("a")), session(rec("a"), 1)).copy(selectedId = "a").withPendingSpawn()
        val unrelated = reduce(pending, session(rec("a", SessionState.BUSY, heartbeat = 5), 2), 100)
        assertEquals("a", unrelated.selectedId)
        val created = reduce(unrelated, session(rec("b", SessionState.STARTING), 1), 100)
        assertEquals("b", created.selectedId)
        assertNull(created.pendingSpawn)
        // A refused spawn (a session-less error) clears the marker, so a later session is not grabbed.
        val refused = reduce(pending, Envelope(Envelope.ERROR, "no session started"), 100)
        assertNull(refused.pendingSpawn)
        assertEquals("a", reduce(refused, session(rec("c")), 100).selectedId)
    }

    @Test fun `an ended turn with background work accepts input and an active turn queues it`() {
        val bg = com.superiorsoftllc.infantry.ui.protocol.BackgroundWorkView(300, "sleep 300", 5)
        val ended = rec("ended", SessionState.BUSY).copy(turnActive = false, backgroundWork = bg)
        val generating = rec("gen", SessionState.BUSY).copy(turnActive = true, bgJobs = listOf(com.superiorsoftllc.infantry.ui.protocol.BgJobView("j", "gate", 1, 1)))
        val old = rec("old", SessionState.BUSY).copy(backgroundWork = bg)
        val s = feed(ready(), sessions(ended, generating, old))
        assertNull(promptBlocker(s.agents.getValue("ended")), "background work alone never refuses the owner")
        assertFalse(com.superiorsoftllc.infantry.ui.model.isBusy(s.agents.getValue("ended")))
        assertTrue(ended.resumable)
        assertTrue(com.superiorsoftllc.infantry.ui.model.isBusy(s.agents.getValue("gen")), "a generating turn queues even with bg jobs")
        assertNotNull(promptBlocker(s.agents.getValue("gen")))
        assertNull(promptBlocker(s.agents.getValue("old")), "an older daemon: background work is not busy")
    }

    @Test fun `a message is refused while the agent is answering or has nothing to continue`() {
        val ranNoId = rec("fresh", SessionState.IDLE, psid = null).copy(runCount = 1)
        val failedNoId = rec("crashed", SessionState.FAILED, psid = null).copy(runCount = 1)
        val s = feed(ready(), sessions(rec("busy", SessionState.BUSY), ranNoId, failedNoId, rec("bound", SessionState.IDLE, psid = null), rec("idle"), rec("failed", SessionState.FAILED)))
        assertTrue(promptBlocker(s.agents.getValue("busy"))!!.contains("still answering"))
        // GAP-006b: at rest after a run that never reported an id (the phantom): the message is its FIRST turn, on the
        // same session (the daemon puts a ticket worker's brief ahead of it), never refused into a second instance.
        assertNull(promptBlocker(s.agents.getValue("fresh")), "a phantom at rest takes the message as its first turn")
        assertNotNull(promptBlocker(s.agents.getValue("crashed")), "a run that FAILED before reporting an id stays refused, as the daemon does")
        // CMD-HARNESS-1: bound but never run (what a harness switch cuts): its first turn goes, as the daemon's neverRun.
        assertNull(promptBlocker(s.agents.getValue("bound")))
        assertNull(promptBlocker(s.agents.getValue("idle")))
        assertNull(promptBlocker(s.agents.getValue("failed")), "a failed turn can be followed up: the daemon resumes it")
    }

    @Test fun `history is asked for once, for the session on screen, only inside the daemon's history window`() {
        val s = feed(ready(since = 100), sessions(rec("old", heartbeat = 50), rec("live", heartbeat = 150)))
        assertNull(historyRequest(s.copy(selectedId = "old"), emptySet()), "nothing to replay from before the daemon started")
        assertEquals(HistoryRequest("live", 0), historyRequest(s.copy(selectedId = "live"), emptySet()))
        assertNull(historyRequest(s.copy(selectedId = "live"), setOf("live")), "asked once per daemon process")
        assertNull(historyRequest(s.copy(selectedId = null), emptySet()))
    }

    @Test fun `a reconnect to the same daemon asks again only after each cursor`() {
        val s = feed(ready(since = 0), sessions(rec("a", heartbeat = 5), rec("b", heartbeat = 5)), turn("a", 1, "q"), text("a", 2, "x"))
        assertEquals(listOf(HistoryRequest("a", 2)), resyncRequests(s, setOf("a")))
        assertEquals(emptyList(), resyncRequests(s, emptySet()))
    }

    @Test fun `only a daemon that echoes turns is trusted to show the user's line`() {
        assertFalse(ConnectionInfo(contract = "1.10.0").echoesTurns)
        assertTrue(ConnectionInfo(contract = "1.11.0").echoesTurns)
        assertTrue(ConnectionInfo(contract = "2.0.0").echoesTurns)
        assertFalse(ConnectionInfo(contract = null).echoesTurns)
    }

    // BUG-UX-8 (Test A): a cursor issued by daemon instance A must not be resumed against instance B.
    @Test fun `a cursor from one daemon instance is not resumed by a different instance`() {
        val before = feed(
            ready("A", since = 0), sessions(rec("a", heartbeat = 5)),
            turn("a", 1, "old question"), text("a", 2, "old answer"), finished("a", 3),
        ).copy(selectedId = "a")
        assertEquals(3L, before.agents.getValue("a").cursor.contiguous, "instance A's cursor reached 3")

        // The daemon restarted as instance B: the transcript is kept, but A's cursor is dropped and the
        // WHOLE session is asked for -- not resumed from a sequence instance B never issued.
        val after = feed(ready("B", since = 0), sessions(rec("a", heartbeat = 9)), from = before).copy(selectedId = "a")
        assertEquals(0L, after.agents.getValue("a").cursor.contiguous, "a new instance resets the cursor")
        assertEquals(
            HistoryRequest("a", 0),
            historyRequest(after, emptySet()),
            "the new instance is asked for the session from the start, not resumed",
        )
    }

    // BUG-UX-8 (Test B): a reconnect to the SAME instance must still honour the stored cursor.
    @Test fun `a cursor is honoured when the daemon comes back as the same instance`() {
        val before = feed(
            ready("A", since = 0), sessions(rec("a", heartbeat = 5)),
            turn("a", 1, "q"), text("a", 2, "a"), finished("a", 3),
        ).copy(selectedId = "a")
        val back = feed(ready("A", since = 0), from = before).copy(selectedId = "a")
        assertEquals("A", back.connection.instance)
        assertEquals(3L, back.agents.getValue("a").cursor.contiguous, "the same instance keeps the cursor")
        assertEquals(HistoryRequest("a", 3), historyRequest(back, emptySet()), "resume after the cursor, not from 0")
    }
}
