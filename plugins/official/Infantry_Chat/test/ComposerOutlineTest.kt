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
