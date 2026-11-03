// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.ui

import com.superiorsoftllc.infantry.ui.model.activityTimestamp
import com.superiorsoftllc.infantry.ui.model.isRunning
import com.superiorsoftllc.infantry.ui.protocol.SessionState
import kotlinx.datetime.TimeZone
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * UI-ACT-2: the Activity box's per-line time prefix and its running/idle split, before the box itself.
 * A model test pins UTC so the wall-clock format is deterministic across machines.
 */
class ActivityBoxModelTest {
    @Test fun theTimestampIsWallClockHHmmssInTheGivenZone() {
        // 3_723_000 ms past the epoch = 1h 2m 3s, so the prefix is zero-padded "1:02:03 AM".
        assertEquals("1:02:03 AM", activityTimestamp(3_723_000L, TimeZone.UTC))
    }

    @Test fun theTimestampZeroPadsEveryField() {
        assertEquals("12:00:00 AM", activityTimestamp(0L, TimeZone.UTC))
    }

    @Test fun runningStatesAreInFlight() {
        assertTrue(isRunning(SessionState.STARTING))
        assertTrue(isRunning(SessionState.BUSY))
        assertTrue(isRunning(SessionState.WAITING_INPUT))
    }

    @Test fun terminalStatesAreNotRunning() {
        assertFalse(isRunning(SessionState.IDLE))
        assertFalse(isRunning(SessionState.EXITED))
        assertFalse(isRunning(SessionState.FAILED))
        assertFalse(isRunning(SessionState.ORPHANED))
    }
}
