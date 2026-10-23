package com.superiorsoftllc.agenthq.ui

import com.superiorsoftllc.agenthq.ui.model.SidebarWidth
import kotlin.test.Test
import kotlin.test.assertEquals

class SidebarWidthTest {
    @Test fun defaultIsTwoSixtyAndInsideBounds() {
        assertEquals(260f, SidebarWidth.DEFAULT)
        assertEquals(SidebarWidth.DEFAULT, SidebarWidth.clamp(SidebarWidth.DEFAULT))
    }

    @Test fun clampHoldsBothEnds() {
        assertEquals(200f, SidebarWidth.clamp(10f))
        assertEquals(480f, SidebarWidth.clamp(5000f))
        assertEquals(300f, SidebarWidth.clamp(300f))
    }

    @Test fun draggingRightWidensAndLeftNarrows() {
        assertEquals(310f, SidebarWidth.dragged(260f, 50f))
        assertEquals(230f, SidebarWidth.dragged(260f, -30f))
    }

    @Test fun dragNeverLeavesBounds() {
        assertEquals(480f, SidebarWidth.dragged(470f, 500f))
        assertEquals(200f, SidebarWidth.dragged(210f, -500f))
    }
}
