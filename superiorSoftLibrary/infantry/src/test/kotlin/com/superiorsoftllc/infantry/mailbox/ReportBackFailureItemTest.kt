// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.mailbox

import com.superiorsoftllc.infantry.session.KillOutcome
import com.superiorsoftllc.infantry.session.SessionEvent
import com.superiorsoftllc.infantry.session.host.SessionHost
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** FIRST-RUN-F1 (ticket 771ccf07): a harness process that never launched raises the same `session_failed` item a failed exit does. */
class ReportBackFailureItemTest {
    private val notSignedIn = "the claude ${SessionHost.DID_NOT_START}: Claude Code is not signed in. Run `claude` once in a terminal and sign in, then restart this agent."

    @Test fun `a launch that never started raises start_failed with the reason`() {
        val item = ReportBackMonitor.failureItem(SessionEvent.Exit("s", code = null, processExited = false), notSignedIn)
        assertEquals("start_failed" to "could not start: Claude Code is not signed in. Run `claude` once in a terminal and sign in, then restart this agent.", item)
    }

    @Test fun `a non-zero exit keeps its item, a clean exit and a kill raise none`() {
        assertEquals("exit_3" to "ended with exit code 3.", ReportBackMonitor.failureItem(SessionEvent.Exit("s", 3), null))
        assertNull(ReportBackMonitor.failureItem(SessionEvent.Exit("s", 0), notSignedIn))
        assertNull(ReportBackMonitor.failureItem(SessionEvent.Exit("s", null, KillOutcome.FORCED, processExited = false), notSignedIn))
    }

    @Test fun `only start failures, another failed turn without an exit code raises nothing`() {
        assertNull(ReportBackMonitor.failureItem(SessionEvent.Exit("s", null, processExited = false), "the turn failed"))
        assertNull(ReportBackMonitor.failureItem(SessionEvent.Exit("s", null, processExited = false), null))
    }
}
