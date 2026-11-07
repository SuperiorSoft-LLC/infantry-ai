// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.plugins.demo.banner

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class DemoBannerTest {
    @Test fun `the compact card keeps the turn count`() {
        assertEquals("10", turnCount("This is a demo. You have 10 turns."))
        assertEquals("1", turnCount("You have 1 turn left"))
        assertNull(turnCount(DEFAULT_TEXT))
    }

    @Test fun `the card sits in the header, after its lead, not before the body`() {
        val d = DemoBannerModule.manifest.shell.single()
        assertEquals("infantry.shell.header.lead", d.node)
        assertEquals("after", d.mode)
    }
}
