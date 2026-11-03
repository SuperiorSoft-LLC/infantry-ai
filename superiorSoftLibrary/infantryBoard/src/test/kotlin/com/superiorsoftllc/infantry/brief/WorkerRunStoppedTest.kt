// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.brief

import com.superiorsoftllc.infantry.protocol.Project
import com.superiorsoftllc.infantry.protocol.Ticket
import com.superiorsoftllc.infantry.protocol.TicketStatus
import java.util.concurrent.CancellationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Ticket 29ff7ace: a run stopped from OUTSIDE (cooperative cancellation, or an external hard kill) is a
 * distinct, explicit result -- [WorkerRunResult.stopped] -- never inferred from an error string and never
 * confused with a provider failure.
 */
class WorkerRunStoppedTest {
    private val brief = LeadBriefComposer.compose(
        BriefInput(
            ticket = Ticket(position = 0, createdAt = 0L, updatedAt = 0L, id = "s1", projectId = "p1", title = "T", status = TicketStatus.TODO),
            project = Project(id = "p1", name = "P", goal = "g"),
            instruction = "do it",
        ),
    )

    @Test
    fun aCooperativeCancellationIsAStoppedRunNotAFailure() {
        val result = WorkerRun.run(brief) { throw CancellationException("lead cancelled the relay") }
        assertTrue(result.stopped)
        assertFalse(result.succeeded)
        assertEquals("", result.output)
    }

    @Test
    fun anExternalHardKillIsAStoppedRunWithItsReason() {
        val result = WorkerRun.run(brief) { throw WorkerRunStopped("process tree killed (FORCED)") }
        assertTrue(result.stopped, "the call reports the kill explicitly; nothing is parsed out of a message")
        assertFalse(result.succeeded)
        assertTrue(result.error!!.contains("process tree killed (FORCED)"), result.error)
    }

    @Test
    fun anOrdinaryProviderExceptionStaysAFailureNotAStop() {
        val result = WorkerRun.run(brief) { throw IllegalStateException("provider exploded") }
        assertFalse(result.stopped)
        assertFalse(result.succeeded)
        assertFalse(WorkerRun.run(brief) { "answer" }.stopped, "a success is not stopped")
    }
}
