// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.ui

import com.superiorsoftllc.infantry.ui.model.AppState
import com.superiorsoftllc.infantry.ui.model.reduce
import com.superiorsoftllc.infantry.ui.protocol.Envelope
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** DEMO-MERGE-1: core keeps `ready`'s fields verbatim and declared refusal notices; it decides nothing from a module's own keys. */
class ReadyFieldsModelTest {
    private val base = "provider=stub contract=1.115.0 instance=A since=0 build=dev"

    @Test
    fun readyKeepsEveryFieldVerbatim() {
        val s = reduce(AppState(), Envelope(Envelope.READY, "$base x=1 y=a=b"), 0)
        assertEquals("1", s.connection.readyFields["x"])
        assertEquals("a=b", s.connection.readyFields["y"])
        assertFalse(s.connection.restricted)
    }

    @Test
    fun accessRestrictedMarksTheLink() {
        val s = reduce(AppState(), Envelope(Envelope.READY, "$base access=restricted"), 0)
        assertTrue(s.connection.restricted)
    }

    /** DEMO-CHECK-1: the owner reads close on `ready` access=restricted, before the `policy` frame arrives (it came after the window's two refused calls). */
    @Test
    fun ownerReadsCloseOnReadyBeforeThePolicyFrame() {
        assertFalse(reduce(AppState(), Envelope(Envelope.READY, base), 0).connection.ownerReadsClosed)
        val s = reduce(AppState(), Envelope(Envelope.READY, "$base access=restricted"), 0)
        assertTrue(s.connection.ownerReadsClosed, "closed from ready alone, policy frame not yet in")
    }

    @Test
    fun aDeclaredNoticeIsKeptAndAnUndeclaredCodeIsNot() {
        val ready = reduce(AppState(), Envelope(Envelope.READY, "$base notices=turn_cap"), 0)
        val noticed = reduce(ready, Envelope(Envelope.ERROR, "turn_cap You have used your turns."), 0)
        assertEquals("You have used your turns.", noticed.connection.notices["turn_cap"])
        val plain = reduce(reduce(AppState(), Envelope(Envelope.READY, base), 0), Envelope(Envelope.ERROR, "turn_cap You have used your turns."), 0)
        assertTrue(plain.connection.notices.isEmpty())
    }

    @Test
    fun aNewReadyClearsTheNotices() {
        val ready = reduce(AppState(), Envelope(Envelope.READY, "$base notices=turn_cap"), 0)
        val noticed = reduce(ready, Envelope(Envelope.ERROR, "turn_cap used up"), 0)
        assertTrue(reduce(noticed, Envelope(Envelope.READY, "$base notices=turn_cap"), 0).connection.notices.isEmpty())
    }
}
