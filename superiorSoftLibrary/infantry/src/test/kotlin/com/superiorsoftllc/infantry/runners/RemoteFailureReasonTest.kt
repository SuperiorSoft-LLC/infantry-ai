// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.runners

import com.superiorsoftllc.infantry.session.SessionState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * GREEN-GAP-1 / RUNNER-UNIFY-6: a session that FAILED on its runner carries the reason its frames gave, so the failure card never says
 * "no reason", also before (or without) the runner's own record.
 */
class RemoteFailureReasonTest {
    @Test
    fun aFailedRunnerSessionCarriesItsReason() {
        val placements = RunnerPlacements(placedOn = { "r" })
        placements.placed("s1", RunnerPlacements.Placed("claude", null, null, null))
        val dir = SessionDirectory(local = { emptyList() }, localGet = { null }, placed = { listOf("s1") }, placements = placements)
        placements.status("s1", "FAILED")
        assertNull(dir.get("s1")!!.lastError)
        placements.failed("s1", "Claude Code is not signed in.")
        val r = dir.get("s1")!!
        assertEquals(SessionState.FAILED, r.state)
        assertEquals("Claude Code is not signed in.", r.lastError)
        placements.status("s1", "IDLE")
        assertNull(dir.get("s1")!!.lastError)
    }
}
