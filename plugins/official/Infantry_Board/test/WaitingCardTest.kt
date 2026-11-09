// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.plugins.official.board

import com.superiorsoftllc.infantry.ui.protocol.WaitingView
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** WAITING-FLAG-1: how a card words its worker's waiting flag. */
class WaitingCardTest {
    private val w = WaitingView(reason = "gradle test", since = 0, untilAt = 10 * 60_000, expiresAt = 15 * 60_000)

    @Test fun `a live flag reads as active and waiting with its age`() {
        val (text, expired) = WaitingCard.label(w, 3 * 60_000)
        assertEquals("active, waiting 3m: gradle test", text)
        assertFalse(expired)
    }

    @Test fun `a flag past its grace reads as over and is marked expired`() {
        val (text, expired) = WaitingCard.label(w, 17 * 60_000)
        assertEquals("wait over 7m ago? gradle test", text)
        assertTrue(expired)
    }

    @Test fun `the grace still counts as waiting`() {
        assertFalse(WaitingCard.label(w, 14 * 60_000).second)
    }
}
