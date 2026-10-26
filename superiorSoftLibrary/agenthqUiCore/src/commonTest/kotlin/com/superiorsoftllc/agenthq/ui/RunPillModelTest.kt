package com.superiorsoftllc.agenthq.ui

import com.superiorsoftllc.agenthq.ui.model.AppState
import com.superiorsoftllc.agenthq.ui.model.RunPillKind
import com.superiorsoftllc.agenthq.ui.model.reduce
import com.superiorsoftllc.agenthq.ui.model.runElapsed
import com.superiorsoftllc.agenthq.ui.model.runPills
import com.superiorsoftllc.agenthq.ui.protocol.Envelope
import com.superiorsoftllc.agenthq.ui.protocol.WireRequests
import kotlin.test.Test
import kotlin.test.assertEquals

/** RUN-CONSOLE (ticket 67f117b6, contract 1.134.0): the Run tab's pills, from the run frames alone. */
class RunPillModelTest {
    private fun feed(vararg e: Envelope) = e.fold(AppState()) { s, f -> reduce(s, f, 100) }
    private fun run(id: String, state: String, startedAt: Long, name: String? = "npm test") = Envelope(
        Envelope.RUN,
        """{"runId":"$id","commandId":"test","state":"$state","startedAt":$startedAt${name?.let { ",\"name\":\"$it\"" } ?: ""},"terminal":true}""",
    )
    private fun finished(id: String, outcome: String, code: Int?) =
        Envelope(Envelope.RUN_FINISHED, """{"runId":"$id","exitCode":${code ?: "null"},"outcome":"$outcome","endedAt":9}""")

    @Test fun `a running run shows its label and the time since it started`() {
        val s = feed(run("r1", "RUNNING", startedAt = 1_000))
        val pill = runPills(s.runs, nowMs = 43_500).single()
        assertEquals("npm test", pill.label)
        assertEquals(RunPillKind.RUNNING, pill.kind)
        assertEquals("running 0:42", pill.status)
    }

    @Test fun `done, failed with the exit code, and stopped`() {
        val s = feed(
            run("a", "RUNNING", 1), finished("a", "SUCCEEDED", 0),
            run("b", "RUNNING", 2), finished("b", "FAILED", 3),
            run("c", "RUNNING", 3), finished("c", "KILLED_GRACEFUL", null),
        )
        val pills = runPills(s.runs, 10).associateBy { it.runId }
        assertEquals(RunPillKind.DONE to "done", pills.getValue("a").let { it.kind to it.status })
        assertEquals(RunPillKind.FAILED to "failed, exit 3", pills.getValue("b").let { it.kind to it.status })
        assertEquals(RunPillKind.STOPPED to "stopped", pills.getValue("c").let { it.kind to it.status })
    }

    @Test fun `pills are newest first`() {
        val s = feed(run("old", "RUNNING", 1), finished("old", "SUCCEEDED", 0), run("new", "RUNNING", 5))
        assertEquals(listOf("new", "old"), runPills(s.runs, 10).map { it.runId })
    }

    @Test fun `the requester's late STARTING never takes a running or finished run back`() {
        val s = feed(run("r", "RUNNING", 1), run("r", "STARTING", 1))
        assertEquals(RunPillKind.RUNNING, runPills(s.runs, 2).single().kind)
        val f = feed(run("r", "RUNNING", 1), finished("r", "SUCCEEDED", 0), run("r", "STARTING", 1))
        assertEquals(RunPillKind.DONE, runPills(f.runs, 2).single().kind)
    }

    @Test fun `with no name the label falls back to the command's name, then its id`() {
        val cmds = Envelope(Envelope.COMMANDS, """[{"id":"test","name":"Unit tests"}]""")
        assertEquals("Unit tests", runPills(feed(cmds, run("r", "RUNNING", 1, name = null)).runs, 2).single().label)
        assertEquals("test", runPills(feed(run("r", "RUNNING", 1, name = null)).runs, 2).single().label)
    }

    @Test fun `the daemon's kept runs become pills after a restart of the window, finished ones with their outcome`() {
        val runs = Envelope(
            Envelope.RUNS,
            """[{"runId":"n","commandId":"test","name":"npm test","state":"RUNNING","startedAt":10,"terminal":true},
               {"runId":"o","commandId":"test","name":"npm test","state":"FINISHED","startedAt":1,"endedAt":5,"exitCode":2,"outcome":"FAILED","terminal":true}]""",
        )
        val s = feed(runs)
        assertEquals(listOf("n" to RunPillKind.RUNNING, "o" to RunPillKind.FAILED), runPills(s.runs, 20).map { it.runId to it.kind })
        assertEquals("n", s.runs.currentRunId, "the newest kept run is shown when the window had none")
    }

    @Test fun `elapsed time formats minutes and hours`() {
        assertEquals("0:05", runElapsed(5_000))
        assertEquals("12:05", runElapsed(725_000))
        assertEquals("1:02:09", runElapsed(3_729_000))
        assertEquals("0:00", runElapsed(-50))
    }

    @Test fun `the new requests encode what the daemon expects`() {
        assertEquals("""{"commandId":"t","interactive":true,"cols":100,"rows":30}""", WireRequests.runStart("t", true, 100, 30).data)
        assertEquals("""{"commandId":"t"}""", WireRequests.runStart("t", false, null, null).data)
        assertEquals("""{"runId":"r","cols":80,"rows":24}""", WireRequests.runResize("r", 80, 24).data)
        assertEquals(Envelope.RUN_LIST, WireRequests.runList().type)
        assertEquals("""{"runId":"r"}""", WireRequests.runAttach("r").data)
        assertEquals("""{"runId":"r","data":"eA=="}""", WireRequests.runInput("r", "eA==").data)
    }
}
