// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.ui

import com.superiorsoftllc.infantry.ui.model.WatchFlag
import com.superiorsoftllc.infantry.ui.model.watchFlagOf
import com.superiorsoftllc.infantry.ui.protocol.AgentWatchView
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class WatchFlagModelTest {
    private fun flag(state: String) = watchFlagOf(AgentWatchView(state))

    @Test
    fun flaggedStatesMapAndQuietOnesShowNothing() {
        assertEquals(WatchFlag.STALLED, flag("STALLED"))
        assertEquals(WatchFlag.QUIET, flag("QUIET"))
        assertEquals(WatchFlag.UNREPORTED, flag("UNREPORTED"))
        assertEquals(WatchFlag.WAITING, flag("WAITING"))
        assertNull(flag("IDLE"), "idle with nothing pending looks different from stalled")
        assertNull(flag("WORKING"))
        assertNull(flag("SOMETHING_NEW"))
        assertNull(watchFlagOf(null))
    }
}
