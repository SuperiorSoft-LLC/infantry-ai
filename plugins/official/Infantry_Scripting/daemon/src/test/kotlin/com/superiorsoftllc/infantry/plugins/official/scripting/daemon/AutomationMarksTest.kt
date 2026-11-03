// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.plugins.official.scripting.daemon

import com.superiorsoftllc.infantry.daemon.api.AutomationMark
import kotlin.test.Test
import kotlin.test.assertEquals

class AutomationMarksTest {
    private var now = 10_000L
    private val calls = mutableListOf<Pair<String, String?>>()
    private val pending = mutableListOf<Pair<Long, () -> Unit>>()
    private fun marks() = AutomationMarks({ s, m -> calls += s to m?.script }, 1000, { now }, { ms, f -> pending += ms to f })

    @Test fun longRunClearsAtOnce() {
        val m = marks(); val c = m.begin("s1", "pong", "h1"); now += 1500; c.close()
        assertEquals(listOf("s1" to "pong", "s1" to null), calls); assertEquals(0, pending.size)
    }

    @Test fun shortRunStaysVisibleAboutOneSecond() {
        val m = marks(); val c = m.begin("s1", "pong", "h1"); now += 200; c.close()
        assertEquals(listOf<Pair<String, String?>>("s1" to "pong"), calls)
        assertEquals(800L, pending.single().first); pending.single().second(); assertEquals("s1" to null, calls.last())
    }

    @Test fun overlappingRunsClearOnlyWhenLastEnds() {
        val m = marks(); val a = m.begin("s1", "a", "h"); val b = m.begin("s1", "b", "h"); now += 2000
        a.close(); assertEquals(1, calls.size); b.close(); assertEquals("s1" to null, calls.last())
    }

    @Test fun restartDuringHoldKeepsMark() {
        val m = marks(); m.begin("s1", "a", "h").close(); val b = m.begin("s1", "b", "h"); pending.single().second()
        assertEquals(1, calls.count { it.second != null }); now += 2000; b.close(); assertEquals("s1" to null, calls.last())
    }

    @Test fun blankSessionAndDoubleCloseAreSafe() {
        val m = marks(); m.begin(null, "a", "h").close(); val c = m.begin("s", "a", "h"); now += 2000; c.close(); c.close()
        assertEquals(listOf<Pair<String, String?>>("s" to "a", "s" to null), calls)
    }
}
