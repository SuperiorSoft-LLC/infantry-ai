// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.ui

import com.superiorsoftllc.infantry.ui.model.formatLocal
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlin.test.Test
import kotlin.test.assertEquals

class FormatLocalTest {
    private val ms = Instant.parse("2026-11-04T01:41:29Z").toEpochMilliseconds()

    @Test fun newYorkShowsNov3Evening() = assertEquals("Nov 3, 8:41 PM", formatLocal(ms, TimeZone.of("America/New_York")))

    @Test fun utcShowsNov4() = assertEquals("Nov 4, 1:41 AM", formatLocal(ms, TimeZone.UTC))
}
