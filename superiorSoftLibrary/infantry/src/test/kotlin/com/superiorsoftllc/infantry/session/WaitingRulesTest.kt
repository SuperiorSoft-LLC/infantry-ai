// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.session

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** WAITING-FLAG-1: the pure rules of the session waiting flag. */
class WaitingRulesTest {
    private val cfg = WaitingRules.Config(defaultMinutes = 30, graceMinutes = 5, clearOnOutput = false, outputGraceSeconds = 10, maxMinutes = 10_080, reasonChars = 200)
    private fun on(minutes: Int? = null, reason: String? = "gradle build", prev: SessionWaiting? = null, now: Long = 1_000_000, c: WaitingRules.Config = cfg) =
        WaitingRules.apply(prev, WaitingRequest("on", reason, minutes), now, c)

    @Test fun `on sets a reason and the asked duration, expiry is duration plus grace`() {
        val w = assertNotNull(on(minutes = 10))
        assertEquals("gradle build", w.reason)
        assertEquals(1_000_000, w.since)
        assertEquals(1_000_000 + 10 * 60_000L, w.untilAt)
        assertEquals(w.untilAt + 5 * 60_000L, w.expiresAt)
    }

    @Test fun `no duration uses the default setting`() {
        assertEquals(1_000_000 + 30 * 60_000L, assertNotNull(on()).untilAt)
    }

    @Test fun `a flag counts until its expiry and not after`() {
        val w = assertNotNull(on(minutes = 1))
        assertTrue(w.active(w.untilAt))
        assertTrue(w.active(w.expiresAt))
        assertFalse(w.active(w.expiresAt + 1))
    }

    @Test fun `off clears and get reads`() {
        val w = on()
        assertNull(WaitingRules.apply(w, WaitingRequest("off"), 2_000_000, cfg))
        assertEquals(w, WaitingRules.apply(w, WaitingRequest("get"), 2_000_000, cfg))
    }

    @Test fun `a malformed request changes nothing`() {
        assertFailsWith<IllegalArgumentException> { on(reason = "  ") }
        assertFailsWith<IllegalArgumentException> { on(reason = null) }
        assertFailsWith<IllegalArgumentException> { on(minutes = 0) }
        assertFailsWith<IllegalArgumentException> { WaitingRules.apply(null, WaitingRequest("maybe"), 1, cfg) }
    }

    @Test fun `output clears the flag only when the setting is on and the output is past the grace`() {
        val off = assertNotNull(on())
        assertEquals(off, WaitingRules.overlay(off, 1_000_000 + 60_000), "default: only the next turn clears it")
        val onOut = assertNotNull(on(c = cfg.copy(clearOnOutput = true)))
        assertEquals(onOut, WaitingRules.overlay(onOut, 1_000_000 + 5_000), "the flagging command's own answer")
        assertNull(WaitingRules.overlay(onOut, 1_000_000 + 11_000), "later output is work")
        assertNull(WaitingRules.overlay(null, 5))
    }

    @Test fun `a reason is cut at the reason limit and a long minute value is refused`() {
        val short = assertNotNull(on(reason = "x".repeat(50), c = cfg.copy(reasonChars = 10)))
        assertEquals(10, short.reason.length)
        assertFailsWith<IllegalArgumentException> { on(minutes = 20_000) }
    }

    @Test fun `the one-line answer says waiting, expired or not waiting`() {
        val w = assertNotNull(on(minutes = 10))
        assertEquals("not waiting", WaitingRules.line(null, 0))
        assertEquals("waiting 2 min of ~10: gradle build", WaitingRules.line(w, w.since + 2 * 60_000))
        assertTrue(WaitingRules.line(w, w.expiresAt + 60_000).startsWith("waiting EXPIRED"))
    }
}
