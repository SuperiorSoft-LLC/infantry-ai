package com.superiorsoftllc.infantry.ui

import com.superiorsoftllc.infantry.ui.protocol.SessionState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ActiveBorderTest {
    @Test fun statusMapsToStyle() {
        assertEquals(ActiveBorderStyle.THINKING, activeBorderStyle(SessionState.BUSY, true, false))
        assertEquals(ActiveBorderStyle.THINKING, activeBorderStyle(SessionState.BUSY, null, false))
        assertEquals(ActiveBorderStyle.BACKGROUND, activeBorderStyle(SessionState.BUSY, false, true))
        assertEquals(ActiveBorderStyle.NONE, activeBorderStyle(SessionState.IDLE, false, true))
        assertEquals(ActiveBorderStyle.NONE, activeBorderStyle(SessionState.EXITED, null, false))
    }

    @Test fun reducedMotionOrOffIsStaticBright() {
        assertEquals(1f, activeBorderAlpha(ActiveBorderStyle.THINKING, 300, animate = false))
        val a = activeBorderAlpha(ActiveBorderStyle.THINKING, 100, animate = true)
        assertTrue(a in 0.35f..1f && a != 1f)
        assertTrue(ActiveBorderStyle.BACKGROUND.periodMs() > ActiveBorderStyle.THINKING.periodMs())
    }
}
