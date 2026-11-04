// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.ui

import com.superiorsoftllc.infantry.plugins.official.chat.COMPOSER_TOUCH_DP
import com.superiorsoftllc.infantry.plugins.official.chat.composerStatusLine
import kotlin.test.Test
import kotlin.test.assertEquals

/** CHAT-MINIMIZE-2: the one status line above the composer box, and the touch-target floor. */
class ComposerStatusLineTest {
    @Test fun lineNamesModeAgentsAndState() {
        assertEquals("permissions: allowAll · 2 agents · busy", composerStatusLine("allowAll", 2, "busy"))
        assertEquals("1 agent", composerStatusLine(null, 1, null))
    }

    @Test fun touchTargetIsAtLeast48dp() = assertEquals(true, COMPOSER_TOUCH_DP >= 48)
}
