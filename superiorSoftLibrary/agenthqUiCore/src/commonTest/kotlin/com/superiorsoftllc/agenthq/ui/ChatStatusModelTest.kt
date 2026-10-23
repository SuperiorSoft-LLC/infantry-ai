package com.superiorsoftllc.agenthq.ui

import com.superiorsoftllc.agenthq.ui.model.AppState
import com.superiorsoftllc.agenthq.ui.model.ChatStatus
import com.superiorsoftllc.agenthq.ui.model.LineKind
import com.superiorsoftllc.agenthq.ui.model.STALE_AFTER_MS
import com.superiorsoftllc.agenthq.ui.model.TranscriptEntry
import com.superiorsoftllc.agenthq.ui.model.chatStatusOf
import com.superiorsoftllc.agenthq.ui.model.reduce
import com.superiorsoftllc.agenthq.ui.protocol.Envelope
import com.superiorsoftllc.agenthq.ui.protocol.FinishedPayload
import com.superiorsoftllc.agenthq.ui.protocol.RunActivityPayload
import com.superiorsoftllc.agenthq.ui.protocol.SessionRecord
import com.superiorsoftllc.agenthq.ui.protocol.SessionState
import com.superiorsoftllc.agenthq.ui.protocol.StatusPayload
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * CHATSTATUS-1: the chat pane shows that the agent is working between the human's send and the first
 * assistant token, and clears it when the reply arrives. Derived from the session stream the daemon
 * already sends (`turn` before the `text_delta` it caused), so no new frame is required.
 */
class ChatStatusModelTest {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private fun rec(id: String, state: SessionState = SessionState.BUSY, heartbeat: Long = 1) =
        SessionRecord(id, "stub", "/w", spawnedAt = 1, lastHeartbeat = heartbeat, state = state, providerSessionId = "p")

    private fun session(r: SessionRecord, seq: Long?) =
        Envelope(Envelope.SESSION, json.encodeToString(SessionRecord.serializer(), r), r.id, seq)

    private fun ready() = Envelope(Envelope.READY, "provider=stub contract=1.11.0 instance=A since=0")
    private fun turn(id: String, seq: Long, t: String) = Envelope(Envelope.TURN, t, id, seq)
    private fun text(id: String, seq: Long, t: String) = Envelope(Envelope.TEXT_DELTA, t, id, seq)

    private fun feed(vararg e: Envelope, from: AppState = AppState()) = e.fold(from) { s, f -> reduce(s, f, 100) }
    private fun AppState.status(id: String, now: Long = 100) = agents.getValue(id).chatStatus(now)

    @Test fun chatStatusIsThinkingBetweenSendAndFirstToken() {
        // The human's turn is on the transcript; the provider has not produced its first token yet.
        val sent = feed(ready(), session(rec("a"), 1), turn("a", 2, "why?"))
        assertIs<ChatStatus.Thinking>(sent.status("a"))

        // 1c24fdc4: the first assistant text does NOT end the turn; the session is still BUSY, so the
        // indicator stays. It clears when the session leaves flight (the header badge's own state).
        val answered = reduce(sent, text("a", 3, "because"), 100)
        assertIs<ChatStatus.Thinking>(answered.status("a"))
        val idle = reduce(answered, session(rec("a", SessionState.IDLE), 4), 100)
        assertNull(idle.status("a"))
    }

    @Test fun chatStatusElapsedLabel() {
        // The SAME turn read at two instants: the label advances with the clock (3s, then 9s).
        val entries = listOf(TranscriptEntry(1, LineKind.USER, "why?", 0))
        assertEquals("3s", (chatStatusOf(entries, 3000) as ChatStatus.Thinking).elapsed)
        assertEquals("9s", (chatStatusOf(entries, 9000) as ChatStatus.Thinking).elapsed)
    }

    private fun status(id: String, seq: Long, p: StatusPayload) =
        Envelope(Envelope.STATUS, json.encodeToString(StatusPayload.serializer(), p), id, seq)

    // --- Contract 1.26.0 (AGENT-UX-12): what the run is DOING, and whether it has gone silent. ---

    @Test fun statusCarriesWhatTheRunningToolIsDoing() {
        // A tool `status` frame names the tool AND its salient argument: the activity line.
        val s = feed(
            ready(), session(rec("a"), 1), turn("a", 2, "list the dir"),
            status("a", 3, StatusPayload(SessionState.BUSY, tool = "Bash", activity = "ls -la src")),
        )
        val st = s.status("a") as ChatStatus.Thinking
        assertEquals("Bash: ls -la src", st.activity, "the status must say WHAT the tool is doing, not just its name")
        assertNull(st.stalledFor, "a frame this recent is not a stall")
    }

    @Test fun theActivityAdvancesAsToolsChange() {
        // LIVE: each new tool call replaces the previous activity, so the line is never frozen.
        val sent = reduce(reduce(reduce(AppState(), ready(), 0), session(rec("a"), 1), 0), turn("a", 2, "go"), 0)
        val s1 = reduce(sent, status("a", 3, StatusPayload(SessionState.BUSY, tool = "Read", activity = "src/a.kt")), 1_000)
        val s2 = reduce(s1, status("a", 4, StatusPayload(SessionState.BUSY, tool = "Bash", activity = "ls")), 2_000)
        assertEquals("Read: src/a.kt", (s1.status("a", 1_000) as ChatStatus.Thinking).activity)
        assertEquals("Bash: ls", (s2.status("a", 2_000) as ChatStatus.Thinking).activity)
    }

    @Test fun aRunWithNoSignOfLifeIsShownStalled() {
        // The owner's core ask: a silent run must LOOK different from a working one.
        val sent = reduce(reduce(reduce(AppState(), ready(), 0), session(rec("a"), 1), 0), turn("a", 2, "why?"), 0)
        val fresh = sent.status("a", now = STALE_AFTER_MS - 1) as ChatStatus.Thinking
        assertNull(fresh.stalledFor, "just under the threshold is not yet stalled")
        val stale = sent.status("a", now = STALE_AFTER_MS) as ChatStatus.Thinking
        assertNotNull(stale.stalledFor, "no frame at all past the threshold must be visible as a stall")
    }

    @Test fun aRecentToolCallIsActivityNotAStall() {
        val sent = reduce(reduce(reduce(AppState(), ready(), 0), session(rec("a"), 1), 0), turn("a", 2, "why?"), 0)
        // A tool frame at t=90s is a sign of life: at t=100s the run is working, not stalled.
        val withTool = reduce(sent, status("a", 3, StatusPayload(SessionState.BUSY, tool = "Grep", activity = "src")), 90_000)
        val alive = withTool.status("a", now = 100_000) as ChatStatus.Thinking
        assertEquals("Grep: src", alive.activity)
        assertNull(alive.stalledFor, "a tool frame 10s ago is activity, not a stall")
    }

    @Test fun aToolWithNoArgumentStillCarriesTheToolName() {
        // A tool call with no salient argument carries the name alone ("Running Read"), never null.
        val s = feed(
            ready(), session(rec("a"), 1), turn("a", 2, "read it"),
            status("a", 3, StatusPayload(SessionState.BUSY, tool = "Read")),
        )
        assertEquals("Read", (s.status("a") as ChatStatus.Thinking).activity)
    }

    @Test fun chatStatusShowsFailureOrStopped() {
        val finished = { outcome: FinishedPayload.Outcome ->
            Envelope(Envelope.FINISHED, json.encodeToString(FinishedPayload.serializer(), FinishedPayload(1, outcome)), "a", 3)
        }

        // A refused or errored turn after the human's message is a failure, not an open wait.
        val errored = feed(ready(), session(rec("a"), 1), turn("a", 2, "why?"), Envelope(Envelope.ERROR, "boom", "a", 3), session(rec("a", SessionState.IDLE), 4))
        assertEquals(ChatStatus.Failure, errored.status("a"))

        // A turn whose run failed or was killed reads as stopped.
        val failed = feed(ready(), session(rec("a"), 1), turn("a", 2, "why?"), finished(FinishedPayload.Outcome.FAILED), session(rec("a", SessionState.IDLE), 4))
        assertEquals(ChatStatus.Stopped, failed.status("a"))
        val killed = feed(ready(), session(rec("a"), 1), turn("a", 2, "why?"), finished(FinishedPayload.Outcome.KILLED_FORCED), session(rec("a", SessionState.EXITED), 4))
        assertEquals(ChatStatus.Stopped, killed.status("a"))
    }

    // --- CHATSTATUS-2: the running tool's name appears in the chat status and clears on reply. ---

    @Test fun chatStatusShowsRunningToolName() {
        // Feed a tool status frame: the status must name the tool.
        val withTool = feed(
            ready(), session(rec("a"), 1), turn("a", 2, "read the file"),
            status("a", 3, StatusPayload(SessionState.BUSY, tool = "Read")),
        )
        assertEquals("Read", (withTool.status("a") as ChatStatus.Thinking).activity)

        // 1c24fdc4: text after the tool means the agent is past that call: the indicator stays (the
        // session is BUSY) but drops the stale tool name.
        val answered = reduce(withTool, text("a", 4, "here is the content"), 200)
        val st = answered.status("a", 200) as ChatStatus.Thinking
        assertNull(st.activity, "a tool reported before the newest text is no longer in flight")
    }

    // --- 1c24fdc4: the indicator follows the session state, not the transcript's last line. ---

    @Test fun aToolOnlyStretchAfterTextKeepsTheIndicatorOn() {
        // The owner's screenshot: the worker said a sentence, then ran tools for minutes with no new text.
        val spoke = reduce(reduce(reduce(reduce(AppState(), ready(), 0), session(rec("a"), 1), 0), turn("a", 2, "fix it"), 0), text("a", 3, "Looking at it."), 1_000)
        assertNull((spoke.status("a", 2_000) as ChatStatus.Thinking).activity, "no tool yet: plain Thinking")
        val tool = reduce(spoke, status("a", 4, StatusPayload(SessionState.BUSY, tool = "Bash", activity = "gradlew test")), 5_000)
        val st = tool.status("a", 65_000) as ChatStatus.Thinking
        assertEquals("Bash: gradlew test", st.activity)
        assertEquals("1m", st.elapsed, "elapsed counts from the turn's opening, not from the last text")
    }

    @Test fun aRunWithNoUserLineStillShowsWorking() {
        // A session that is BUSY with nothing on its transcript yet (a ticket start before any frame).
        val s = feed(ready(), session(rec("a"), 1))
        assertIs<ChatStatus.Thinking>(s.status("a"))
    }

    @Test fun startingAndWaitingInputCountAsWorkingIdleDoesNot() {
        // The same in-flight set as the header badge and the sidebar Running group (isRunning).
        for (st in SessionState.entries) {
            val s = feed(ready(), session(rec("a", st), 1), turn("a", 2, "go"), text("a", 3, "ok"))
            val expectOn = st in setOf(SessionState.STARTING, SessionState.BUSY, SessionState.WAITING_INPUT)
            assertEquals(expectOn, s.status("a") is ChatStatus.Thinking, "state $st")
        }
    }

    @Test fun aToolFromAnEarlierTurnIsNotInFlight() {
        val first = feed(
            ready(), session(rec("a"), 1), turn("a", 2, "one"),
            status("a", 3, StatusPayload(SessionState.BUSY, tool = "Read")),
            Envelope(Envelope.FINISHED, json.encodeToString(FinishedPayload.serializer(), FinishedPayload(0, FinishedPayload.Outcome.SUCCEEDED)), "a", 4),
        )
        val second = reduce(first, turn("a", 5, "two"), 200)
        assertNull((second.status("a", 300) as ChatStatus.Thinking).activity)
    }

    // --- 1c24fdc4 S2 (contract 1.95.0): the daemon's harness-neutral run activity decides the label. ---

    @Test fun theDaemonsRunActivityClearsTheToolWhenItEndsWithNoTextAfter() {
        val sent = reduce(reduce(reduce(AppState(), ready(), 0), session(rec("a"), 1), 0), turn("a", 2, "build it"), 0)
        val running = reduce(
            sent,
            status("a", 3, StatusPayload(SessionState.BUSY, tool = "Bash", activity = "gradlew build", runActivity = RunActivityPayload("tool_running", "Bash: gradlew build"))),
            1_000,
        )
        val st = running.status("a", 4_000) as ChatStatus.Thinking
        assertEquals("Bash: gradlew build", st.activity)
        assertEquals("3s", st.elapsed, "a running tool is timed from its own start")

        // The tool ENDED and the model is thinking again, with no text in between: the label must drop the tool.
        // (Before 1.95.0 the UI could not know this, and "Running Bash" stayed up until the next sentence.)
        val thinking = reduce(running, status("a", 4, StatusPayload(SessionState.BUSY, runActivity = RunActivityPayload("thinking"))), 5_000)
        val after = thinking.status("a", 6_000) as ChatStatus.Thinking
        assertNull(after.activity, "a tool the daemon reported ended is not in flight")
        assertEquals("6s", after.elapsed, "thinking is timed from the turn's opening")

        // The turn ends: the session leaves flight and the indicator goes, whatever the last activity said.
        val idle = reduce(thinking, session(rec("a", SessionState.IDLE), 5), 7_000)
        assertNull(idle.status("a", 7_000))
    }

    @Test fun anUnknownRunPhaseFromANewerDaemonStillDecodes() {
        val wire = """{"state":"BUSY","runActivity":{"state":"compacting","startedAt":1,"turnStartedAt":0,"lastEventAt":1}}"""
        val s = feed(ready(), session(rec("a"), 1), turn("a", 2, "go"), Envelope(Envelope.STATUS, wire, "a", 3))
        val st = s.status("a") as ChatStatus.Thinking
        assertNull(st.activity, "an unknown phase is not a tool")
    }
}
