package com.superiorsoftllc.agenthq.cli

import com.superiorsoftllc.agenthq.daemon.api.processes.ProcessReport
import com.superiorsoftllc.agenthq.daemon.api.processes.ProcessesAnswer
import com.superiorsoftllc.agenthq.daemon.api.processes.ProcessesRequest
import com.superiorsoftllc.agenthq.daemon.api.processes.TrackedProcess
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** ROGUE-1: `agenthq processes` argument rules and its text. */
class ProcessesCliTest {
    @Test fun `arguments map to requests and bad ones are usage errors`() {
        assertNull(ProcessesCli.usageError(emptyList(), all = false))
        assertNull(ProcessesCli.usageError(emptyList(), all = true))
        assertNull(ProcessesCli.usageError(listOf("reap", "42"), all = false))
        assertNull(ProcessesCli.usageError(listOf("reap"), all = true))
        assertNotNull(ProcessesCli.usageError(listOf("reap"), all = false))
        assertNotNull(ProcessesCli.usageError(listOf("reap", "x"), all = false))
        assertNotNull(ProcessesCli.usageError(listOf("reap", "42"), all = true))
        assertNotNull(ProcessesCli.usageError(listOf("kill"), all = false))
        assertEquals(ProcessesRequest("list"), ProcessesCli.requestFor(emptyList(), true))
        assertEquals(ProcessesRequest("reap_rogue"), ProcessesCli.requestFor(listOf("reap"), true))
        assertEquals(ProcessesRequest("reap", 42), ProcessesCli.requestFor(listOf("reap", "42"), false))
    }

    @Test fun `render shows rogue first and hides the rest without --all`() {
        val now = 10_000_000L
        val report = ProcessReport(
            freeMb = 2000, totalMb = 32000, floorMb = 4096, underPressure = true,
            tracked = listOf(
                TrackedProcess(7, "java GradleDaemon", "abcdef123456", now - 90 * 60_000, 1200, true, "session removed"),
                TrackedProcess(8, "node mcp.js", "abcdef123456", now - 5 * 60_000, 80),
            ),
            unattributed = listOf(TrackedProcess(9, "vmmem", memoryMb = 6000)),
        )
        val short = ProcessesCli.render(ProcessesAnswer(report), showAll = false, now = now)
        assertTrue("UNDER the 4096 MB floor" in short, short)
        assertTrue("pid 7 | 1200 MB | 1h30m | session abcdef12 | java GradleDaemon | session removed" in short, short)
        assertFalse("pid 9" in short)
        val all = ProcessesCli.render(ProcessesAnswer(report), showAll = true, now = now)
        assertTrue("pid 8" in all && "pid 9 | 6000 MB" in all && "not ours, never reaped (1)" in all, all)
        val refused = ProcessesCli.render(ProcessesAnswer(report, refused = "nope"), showAll = false, now = now)
        assertTrue(refused.startsWith("refused: nope"))
    }
}
