// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.daemon

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class BoardSeamTest {
    private class Host(val got: MutableList<Int> = mutableListOf())

    private fun seam() = BoardSeam<Host, Int>({ h, e -> h.got += e })

    @Test fun `board attached late receives events from before attach in order`() {
        val s = seam()
        assertNull(s.host)
        assertFailsWith<IllegalStateException> { s.require() }
        (1..3).forEach(s::emit)
        val h = Host()
        s.attach(h)
        s.emit(4)
        assertEquals(listOf(1, 2, 3, 4), h.got)
        assertEquals(0, s.pending)
    }

    @Test fun `reload detaches, queues, and re-adopts after attach`() {
        val s = seam()
        val first = Host(); s.attach(first); s.emit(1)
        s.detach(); s.emit(2); s.emit(3)
        assertEquals(2, s.pending)
        var adopted = 0
        val second = Host(); s.attach(second) { adopted++ }
        assertEquals(listOf(1), first.got)
        assertEquals(listOf(2, 3), second.got)
        assertEquals(1, adopted)
    }

    @Test fun `a failing re-adopt does not stop the attach`() {
        val s = seam(); val h = Host()
        s.attach(h) { error("boom") }
        s.emit(9)
        assertEquals(listOf(9), h.got)
    }
}
