// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.usagemeter

import com.superiorsoftllc.infantry.DisplayZone
import java.time.Instant
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class DisplayZoneTest {
    private val ms = Instant.parse("2026-11-04T01:41:29Z").toEpochMilli()

    @Test fun stampIsLocalWithOffset() {
        assertEquals("2026-11-03 20:41 -05:00", DisplayZone.stamp(ms, ZoneId.of("America/New_York")))
        assertEquals("2026-11-03", DisplayZone.day(ms, ZoneId.of("America/New_York")))
    }

    @Test fun settingAppliesAndBlankIsSystem() {
        try {
            DisplayZone.set("America/New_York")
            assertEquals("2026-11-03 20:41 -05:00", DisplayZone.stamp(ms))
            DisplayZone.set("")
            assertEquals(ZoneId.systemDefault(), DisplayZone.zone)
            assertFailsWith<IllegalArgumentException> { DisplayZone.set("Nowhere/Land") }
        } finally { DisplayZone.set("") }
    }
}
