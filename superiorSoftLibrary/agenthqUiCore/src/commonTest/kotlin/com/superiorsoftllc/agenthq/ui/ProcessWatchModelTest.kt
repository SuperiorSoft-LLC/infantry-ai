package com.superiorsoftllc.agenthq.ui

import com.superiorsoftllc.agenthq.ui.model.AppState
import com.superiorsoftllc.agenthq.ui.model.NoticeKind
import com.superiorsoftllc.agenthq.ui.model.ProcessWatchView
import com.superiorsoftllc.agenthq.ui.model.reduce
import com.superiorsoftllc.agenthq.ui.protocol.Envelope
import com.superiorsoftllc.agenthq.ui.protocol.ProcessReport
import com.superiorsoftllc.agenthq.ui.protocol.TrackedProcess
import com.superiorsoftllc.agenthq.ui.protocol.WireRequests
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** ROGUE-1 (contract 1.120.0): the `processes` / `process_pressure` frames, the badge words and the auto-reap setting. */
class ProcessWatchModelTest {
    private val rogueReport = """{"report":{"freeMb":2048,"totalMb":32768,"floorMb":4096,"underPressure":true,"autoReap":false,
        "tracked":[{"pid":7,"command":"java GradleDaemon","sessionId":"abcdef123456","startedAt":0,"memoryMb":1200,"rogue":true,"reason":"session removed"},
                   {"pid":8,"command":"node x","sessionId":"abcdef123456","memoryMb":80}],
        "unattributed":[{"pid":9,"command":"vmmem","memoryMb":6000}],"newField":1}}"""

    @Test fun `a processes answer replaces the report and the badge turns amber`() {
        val s = reduce(AppState(), Envelope(Envelope.PROCESSES, rogueReport), 1)
        val r = s.processReport!!
        assertEquals(listOf(7L), r.rogue.map { it.pid })
        assertEquals(false, s.processAutoReap)
        assertEquals("RAM 2.0 GB free · 1 rogue process", ProcessWatchView.badge(r))
        assertTrue(ProcessWatchView.alarming(r))
        assertTrue(s.notices.isEmpty(), "a plain list raises no notice")
    }

    @Test fun `a quiet report is muted and before any report nothing shows`() {
        assertNull(ProcessWatchView.badge(null))
        val quiet = ProcessReport(freeMb = 20_000, floorMb = 4096)
        assertEquals("RAM 19.5 GB free", ProcessWatchView.badge(quiet))
        assertFalse(ProcessWatchView.alarming(quiet))
    }

    @Test fun `reaped and refused answers raise notices`() {
        val reaped = """{"report":{"floorMb":4096},"reaped":[{"pid":7,"command":"java"}]}"""
        val s = reduce(AppState(), Envelope(Envelope.PROCESSES, reaped), 1)
        assertTrue(s.notices.single().text.startsWith("reaped 1 process: pid 7"))
        val refused = """{"report":{"floorMb":4096},"refused":"pid 9 was not started by an Infantry AI session"}"""
        val r = reduce(AppState(), Envelope(Envelope.PROCESSES, refused), 1)
        assertEquals(NoticeKind.ERROR, r.notices.single().kind)
    }

    @Test fun `a pressure push stores the report and warns`() {
        val push = """{"freeMb":1024,"floorMb":4096,"underPressure":true,"tracked":[]}"""
        val s = reduce(AppState(), Envelope(Envelope.PROCESS_PRESSURE, push), 1)
        assertTrue(s.processReport!!.underPressure)
        assertEquals(NoticeKind.HEADS_UP, s.notices.single().kind)
        assertTrue(s.notices.single().text.startsWith("low memory: 1.0 GB free"))
    }

    @Test fun `auto-reap is read from the settings frame and the requests are well formed`() {
        val s = reduce(AppState(), Envelope(Envelope.SETTINGS, """{"processes.autoReap":true,"session.keepAliveMinutes":30}"""), 1)
        assertEquals(true, s.processAutoReap)
        assertEquals(30, s.keepAliveMinutes)
        assertEquals("""{"op":"list"}""", WireRequests.processes().data)
        assertEquals("""{"op":"reap","pid":42}""", WireRequests.processes("reap", 42).data)
        assertEquals(
            "pid 7 · 1.2 GB · 1h30m · session abcdef12 · java GradleDaemon — session removed",
            ProcessWatchView.row(TrackedProcess(7, "java GradleDaemon", "abcdef123456", 0, 1200, true, "session removed"), 90 * 60_000L),
        )
    }

    /** Contract 1.132.0 (ticket 66153313): agent CPU in the badge, per row, and amber while it stays above the alert. */
    @Test fun `agent CPU shows in the badge and rows, amber while sustained`() {
        val r = ProcessReport(
            freeMb = 20_000, floorMb = 4096, agentCpuPercent = 63.4,
            tracked = listOf(TrackedProcess(7, "java GradleDaemon", "abcdef123456", null, 1200, cpuPercent = 2.46)),
        )
        assertEquals("RAM 19.5 GB free · agents CPU 63%", ProcessWatchView.badge(r))
        assertFalse(ProcessWatchView.alarming(r), "high but not yet sustained")
        assertEquals("pid 7 · 1.2 GB · CPU 2.5% · session abcdef12 · java GradleDaemon", ProcessWatchView.row(r.tracked.single(), 0))
        assertTrue(ProcessWatchView.alarming(r.copy(cpuHighSince = 1L)))
        assertFalse(ProcessWatchView.alarming(r.copy(cpuHighSince = 1L, cpuAlertMinutes = 0)), "alert off: never amber for CPU")
        val wire = reduce(
            AppState(),
            Envelope(Envelope.PROCESSES, """{"report":{"floorMb":4096,"agentCpuPercent":12.5,"cpuAlertPercent":80,"cpuAlertMinutes":5,"cpuHighSince":null,"tracked":[{"pid":1,"command":"pi","cpuPercent":12.5}]}}"""),
            1,
        ).processReport!!
        assertEquals(12.5, wire.agentCpuPercent)
        assertEquals(80, wire.cpuAlertPercent)
        assertEquals(12.5, wire.tracked.single().cpuPercent)
    }
}
