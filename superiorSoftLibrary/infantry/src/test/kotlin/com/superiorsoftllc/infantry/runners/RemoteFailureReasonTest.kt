// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.runners

import com.superiorsoftllc.infantry.session.SessionState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** GREEN-GAP-1: a session that FAILED on its runner carries the runner's error, so the failure card never says "no reason". */
class RemoteFailureReasonTest {
    @Test
    fun aFailedRunnerSessionCarriesItsReason() {
        val m = RemoteSessionMirror(sessionIds = { listOf("s1") })
        m.status("s1", "FAILED")
        assertNull(m.get("s1")!!.lastError)
        m.failed("s1", "Claude Code is not signed in.")
        val r = m.get("s1")!!
        assertEquals(SessionState.FAILED, r.state)
        assertEquals("Claude Code is not signed in.", r.lastError)
        m.status("s1", "IDLE")
        assertNull(m.get("s1")!!.lastError)
    }
}
