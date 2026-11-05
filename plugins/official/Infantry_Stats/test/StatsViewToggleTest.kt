// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.plugins.official.stats

import java.io.File
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** STATS-TOGGLE-1: the view switch reuses the shared segmented toggle (as Period / Group by), not plain text. */
class StatsViewToggleTest {
    private val src = listOf(File("src/StatsModule.kt"), File("plugins/official/Infantry_Stats/src/StatsModule.kt"))
        .first { it.exists() }.readText()

    @Test fun `view switch is the shared SegmentedControl with both options`() {
        assertTrue(Regex("""SegmentedControl\(\s*"View"""").containsMatchIn(src))
        assertTrue("\"Session\"" in src && "\"Tokens by day/week\"" in src)
        assertTrue("{ daily = it }" in src, "selecting an option (either way) sets the view")
        assertFalse("stats-mode-" in src, "the old plain-text switch is gone")
        assertTrue(STATS_VIEW_TOGGLE_TAG.isNotBlank())
    }
}
