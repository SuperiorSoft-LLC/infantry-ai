package com.superiorsoftllc.agenthq.protocol

import com.superiorsoftllc.agenthq.protocol.RunPhase.FINISHED
import com.superiorsoftllc.agenthq.protocol.RunPhase.IDLE
import com.superiorsoftllc.agenthq.protocol.RunPhase.THINKING
import com.superiorsoftllc.agenthq.protocol.RunPhase.TOOL_RUNNING
import com.superiorsoftllc.agenthq.session.SessionState
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 1c24fdc4 (contract 1.95.0): every harness drives the SAME run-activity sequence. Each stream below is shaped like
 * that CLI's real output; it goes through the real [EventNormalizer] (the only place a harness is known) into the real
 * [RunActivityTracker], framed by what the daemon does around every run (turn accepted, session BUSY, turn ended,
 * session IDLE). None of the streams carries a reasoning event, so "thinking" between the tools can only come from
 * the turn.
 */
class RunActivityContractTest {

    /** One harness-neutral turn: speak, run two tools, speak, end. What every harness must produce. */
    private val twoToolTurn = listOf(IDLE, THINKING, TOOL_RUNNING, THINKING, TOOL_RUNNING, THINKING, FINISHED, IDLE)

    private class Run(lines: List<String>, failed: Boolean = false, idleFirst: Boolean = false) {
        private var t = 0L
        private val tracker = RunActivityTracker { t++ }
        val phases = mutableListOf(tracker.current(ID).state)
        val labels = mutableListOf<String>()
        val normalised = mutableListOf<EventNormalizer.Line>()

        private fun see(a: RunActivity?) {
            a ?: return
            if (a.state == TOOL_RUNNING) a.label?.let { labels += it }
            if (phases.last() != a.state) phases += a.state
        }

        init {
            see(tracker.turnStarted(ID))
            see(tracker.sessionState(ID, SessionState.BUSY))
            for (raw in lines) for (line in EventNormalizer.normalise(raw)) {
                normalised += line
                see(tracker.line(ID, line))
            }
            if (idleFirst) {
                // The real daemon's order: the session leaves flight, then the turn's end is reported.
                see(tracker.sessionState(ID, SessionState.IDLE))
                tracker.turnEnded(ID, failed).forEach(::see)
            } else {
                tracker.turnEnded(ID, failed).forEach(::see)
                see(tracker.sessionState(ID, SessionState.IDLE))
            }
        }
    }

    private fun Run.assertNoReasoning() =
        assertTrue(normalised.none { it is EventNormalizer.Line.Reasoning }, "the stream must carry no reasoning, or it proves nothing about the turn rule")

    @Test fun claude() {
        val run = Run(
            listOf(
                """{"type":"system","subtype":"init","session_id":"c1"}""",
                """{"type":"assistant","message":{"content":[{"type":"text","text":"Looking."}]}}""",
                """{"type":"assistant","message":{"content":[{"type":"tool_use","id":"t1","name":"Bash","input":{"command":"ls"}}]}}""",
                """{"type":"user","message":{"content":[{"type":"tool_result","tool_use_id":"t1","content":"a.kt"}]}}""",
                """{"type":"assistant","message":{"content":[{"type":"tool_use","id":"t2","name":"Read","input":{"file_path":"a.kt"}}]}}""",
                """{"type":"user","message":{"content":[{"type":"tool_result","tool_use_id":"t2","content":"..."}]}}""",
                """{"type":"assistant","message":{"content":[{"type":"text","text":"Done."}]}}""",
                """{"type":"result","subtype":"success","is_error":false}""",
            ),
        )
        run.assertNoReasoning()
        assertEquals(twoToolTurn, run.phases)
        assertEquals(listOf("Bash: ls", "Read: a.kt"), run.labels)
    }

    @Test fun opencode() {
        val run = Run(
            listOf(
                """{"type":"step_start","sessionID":"o1"}""",
                """{"type":"text","part":{"text":"Looking."}}""",
                """{"type":"tool_use","part":{"type":"tool","tool":"bash","state":{"status":"completed","input":{"command":"ls"}}}}""",
                """{"type":"tool_use","part":{"type":"tool","tool":"read","state":{"status":"completed","input":{"filePath":"a.kt"}}}}""",
                """{"type":"text","part":{"text":"Done."}}""",
                """{"type":"step_finish"}""",
            ),
        )
        run.assertNoReasoning()
        assertEquals(twoToolTurn, run.phases)
        assertEquals(listOf("bash: ls", "read: a.kt"), run.labels)
    }

    @Test fun pi() {
        val run = Run(
            listOf(
                """{"type":"session","id":"p1"}""",
                """{"type":"message_update","assistantMessageEvent":{"type":"text_delta","delta":"Looking."}}""",
                """{"type":"tool_execution_start","toolCallId":"c1","toolName":"bash","args":{"command":"ls"}}""",
                """{"type":"tool_execution_end","toolCallId":"c1","toolName":"bash","result":{"content":[]},"isError":false}""",
                """{"type":"tool_execution_start","toolCallId":"c2","toolName":"read","args":{"path":"a.kt"}}""",
                """{"type":"tool_execution_end","toolCallId":"c2","toolName":"read","result":{"content":[]},"isError":false}""",
                """{"type":"message_update","assistantMessageEvent":{"type":"text_delta","delta":"Done."}}""",
                """{"type":"agent_settled"}""",
            ),
        )
        run.assertNoReasoning()
        assertEquals(twoToolTurn, run.phases)
        assertEquals(listOf("bash: ls", "read: a.kt"), run.labels)
    }

    @Test fun aRecordedOpencodeRunGoesThinkingBetweenEveryTool() {
        // A real `opencode run` capture: five tool calls, then the answer. Each call must end before the next.
        val lines = File("src/test/resources/retrieval/opencode-tools-run.jsonl").readLines()
        val run = Run(lines)
        assertEquals(listOf(IDLE, THINKING) + List(5) { listOf(TOOL_RUNNING, THINKING) }.flatten() + listOf(FINISHED, IDLE), run.phases)
    }

    @Test fun aToolWhoseEndNeverComesIsOverWhenTheModelSpeaksAgain() {
        // A harness that reports only a tool's start must not leave "Running <tool>" up for the rest of the turn.
        val run = Run(
            listOf(
                """{"type":"assistant","message":{"content":[{"type":"tool_use","name":"Bash","input":{"command":"ls"}}]}}""",
                """{"type":"assistant","message":{"content":[{"type":"text","text":"Done."}]}}""",
            ),
        )
        assertEquals(listOf(IDLE, THINKING, TOOL_RUNNING, THINKING, FINISHED, IDLE), run.phases)
    }

    @Test fun parallelToolCallsStayRunningUntilTheLastOneEnds() {
        var t = 0L
        val tracker = RunActivityTracker { t++ }
        tracker.turnStarted(ID)
        for (l in EventNormalizer.normalise("""{"type":"assistant","message":{"content":[{"type":"tool_use","name":"Read"},{"type":"tool_use","name":"Grep"}]}}""")) tracker.line(ID, l)
        tracker.line(ID, EventNormalizer.Line.ToolEnd)
        assertEquals(TOOL_RUNNING, tracker.current(ID).state, "one of two calls is still running")
        tracker.line(ID, EventNormalizer.Line.ToolEnd)
        assertEquals(THINKING, tracker.current(ID).state)
    }

    @Test fun theTurnEndsIdleWhicheverOfItsEndAndTheSessionsIdleComesFirst() {
        val lines = listOf("""{"type":"tool_use","part":{"type":"tool","tool":"bash","state":{"status":"completed"}}}""")
        assertEquals(Run(lines).phases, Run(lines, idleFirst = true).phases)
        assertEquals(listOf(IDLE, THINKING, TOOL_RUNNING, THINKING, FINISHED, IDLE), Run(lines, idleFirst = true).phases)
    }

    @Test fun aFailedTurnEndsInError() {
        val run = Run(listOf("""{"type":"text","part":{"text":"hm"}}"""), failed = true)
        assertEquals(listOf(IDLE, THINKING, RunPhase.ERROR, IDLE), run.phases)
    }

    @Test fun theWireNamesArePinned() {
        val json = kotlinx.serialization.json.Json
        val wire = json.encodeToString(RunActivity.serializer(), RunActivity(TOOL_RUNNING, "Bash: ls", 1, 0, 2))
        assertEquals("""{"state":"tool_running","label":"Bash: ls","startedAt":1,"turnStartedAt":0,"lastEventAt":2}""", wire)
        assertEquals(
            listOf("idle", "thinking", "tool_running", "compacting", "waiting_for_input", "finished", "error"),
            RunPhase.entries.map { json.encodeToString(RunPhase.serializer(), it).trim('"') },
        )
    }

    private companion object {
        const val ID = "s1"
    }
}
