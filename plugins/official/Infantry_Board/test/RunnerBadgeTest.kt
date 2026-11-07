// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.plugins.official.board

import com.superiorsoftllc.infantry.ui.protocol.SessionState
import com.superiorsoftllc.infantry.ui.protocol.SessionRecord
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** RUNNER-POOL-4: the runner badge is absent with one runner and present, with a health word, with two. */
class RunnerBadgeTest {
    private fun s(id: String, runner: String, state: SessionState = SessionState.IDLE) =
        SessionRecord(id = id, provider = "p", workingDir = "/", spawnedAt = 0, lastHeartbeat = 0, state = state, runner = runner)

    @Test fun absentWithOneRunner() {
        val all = listOf(s("a", "local"), s("b", "local"))
        assertNull(runnerBadgeFor(all[0], all))
    }

    @Test fun presentWithTwoRunnersAndHealthColourWord() {
        val all = listOf(s("a", "local"), s("b", "server-1"), s("c", "server-1", SessionState.FAILED), s("d", "gone", SessionState.EXITED), s("e", "gone", SessionState.EXITED))
        assertEquals(RunnerBadgeModel("local", "up"), runnerBadgeFor(all[0], all))
        assertEquals(RunnerBadgeModel("server-1", "degraded"), runnerBadgeFor(all[1], all))
        assertEquals(RunnerBadgeModel("gone", "offline"), runnerBadgeFor(all[3], all))
    }
}
