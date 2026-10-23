package com.superiorsoftllc.agenthq.ui

import com.superiorsoftllc.agenthq.ui.model.TimeFormat
import kotlin.test.Test
import kotlin.test.assertEquals

class TimeFormatTest {
    @Test fun twelveHourClock() {
        assertEquals("12:00 AM", TimeFormat.clock12(0, 0))
        assertEquals("12:09 AM", TimeFormat.clock12(0, 9))
        assertEquals("11:59 AM", TimeFormat.clock12(11, 59))
        assertEquals("12:00 PM", TimeFormat.clock12(12, 0))
        assertEquals("1:36 PM", TimeFormat.clock12(13, 36))
        assertEquals("11:09 PM", TimeFormat.clock12(23, 9))
        assertEquals("2:59 PM", TimeFormat.clock12(14, 59))
        assertEquals("3:11 AM", TimeFormat.clock12(3, 11))
        assertEquals("1:02:03 PM", TimeFormat.clock12(13, 2, 3))
    }
}
