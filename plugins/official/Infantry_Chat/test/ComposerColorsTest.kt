// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.plugins.official.chat

import com.superiorsoftllc.infantry.ui.Palette
import kotlin.test.Test
import kotlin.test.assertEquals

/** COMPOSER-FLOAT-1: the composer region paints the feed's background by default, so the prompt box floats. */
class ComposerColorsTest {
    @Test fun composerRegionUsesTheFeedBackground() {
        assertEquals(Palette.Bg, ComposerColors.background)
    }
}
