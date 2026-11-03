// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.ui

import com.superiorsoftllc.infantry.ui.model.PacingView
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class PacingUpdatedTextTest {
    @Test
    fun updatedTextCountsMinutesAndHours() {
        val now = 10_000_000L
        assertNull(PacingView.updatedText(null, now))
        assertEquals("updated just now", PacingView.updatedText(now - 30_000, now))
        assertEquals("updated 4m ago", PacingView.updatedText(now - 4 * 60_000, now))
        assertEquals("updated 2h ago", PacingView.updatedText(now - 125 * 60_000, now))
    }
}
