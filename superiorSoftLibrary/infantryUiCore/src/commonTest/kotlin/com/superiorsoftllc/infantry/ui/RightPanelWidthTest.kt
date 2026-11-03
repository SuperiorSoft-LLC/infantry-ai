// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.ui

import com.superiorsoftllc.infantry.ui.model.RightPanelWidth
import kotlin.test.Test
import kotlin.test.assertEquals

class RightPanelWidthTest {
    @Test fun defaultIsThreeTwentyAndInsideBounds() {
        assertEquals(320f, RightPanelWidth.DEFAULT)
        assertEquals(RightPanelWidth.DEFAULT, RightPanelWidth.clamp(RightPanelWidth.DEFAULT))
    }

    @Test fun clampHoldsBothEnds() {
        assertEquals(280f, RightPanelWidth.clamp(10f))
        assertEquals(640f, RightPanelWidth.clamp(5000f))
        assertEquals(400f, RightPanelWidth.clamp(400f))
    }

    @Test fun draggingLeftWidensAndRightNarrows() {
        assertEquals(370f, RightPanelWidth.dragged(320f, -50f))
        assertEquals(290f, RightPanelWidth.dragged(320f, 30f))
    }

    @Test fun dragNeverLeavesBounds() {
        assertEquals(640f, RightPanelWidth.dragged(630f, -500f))
        assertEquals(280f, RightPanelWidth.dragged(290f, 500f))
    }
}
