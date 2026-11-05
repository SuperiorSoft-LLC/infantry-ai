// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.plugins.official.chat

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

/** PROMPT-BORDER-1: hover and focus draw the same outline (thickness and colour) as the hanging tabs; idle is thinner. */
class ComposerOutlineTest {
    @Test fun hoverAndFocusShareOneOutline() {
        val hover = composerOutline(focused = false, hovered = true)
        val focus = composerOutline(focused = true, hovered = false)
        assertEquals(focus, hover)
        assertEquals(focus, composerOutline(focused = true, hovered = true))
        assertEquals(COMPOSER_BORDER_DP, hover.dp)
        assertNotEquals(hover, composerOutline(focused = false, hovered = false))
    }
}

/** PROMPT-BORDER-2: at every phone/desktop density the tab's fill covers the box's bottom stroke, antialiased edge included. */
class ComposerTabCoverTest {
    @Test fun fillCoversBoxStrokeAtEveryDensity() {
        val boxHeight = 300
        for (density in listOf(1f, 1.25f, 1.5f, 1.75f, 2f, 2.25f, 2.625f, 2.75f, 3f, 3.5f, 4f)) for (dp in listOf(1, COMPOSER_BORDER_DP)) {
            val b = dp * density
            val tabTop = boxHeight - kotlin.math.round(b) // Modifier.offset(y = -b) rounds to whole pixels
            val strokeFirstRow = kotlin.math.floor(boxHeight - b) // first pixel row the antialiased stroke touches
            kotlin.test.assertTrue(tabTop + composerTabFillTop(b) <= strokeFirstRow, "density $density, ${dp}dp")
        }
    }
}
