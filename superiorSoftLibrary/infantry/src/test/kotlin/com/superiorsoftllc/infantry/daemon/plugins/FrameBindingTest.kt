// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.daemon.plugins

import com.superiorsoftllc.infantry.daemon.BoardFrames
import com.superiorsoftllc.infantry.daemon.api.Caller
import com.superiorsoftllc.infantry.daemon.api.Frame
import com.superiorsoftllc.infantry.daemon.api.Frames
import com.superiorsoftllc.infantry.daemon.api.spawns.NoSpawnCharge
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FrameBindingTest {
    @Test
    fun tableRegistersEveryTypeWithTheDirectFlag() {
        val handlers = HashMap<String, (Frame, Caller) -> Frame?>()
        val frames = object : Frames { override fun handle(type: String, handler: (Frame, Caller) -> Frame?) { handlers[type] = handler } }
        val seen = ArrayList<Boolean>()
        frames.table.register("ticket_get", "ticket_list") { f, direct -> seen += direct; Frame(f.type) }
        assertEquals(setOf("ticket_get", "ticket_list"), handlers.keys)
        handlers.getValue("ticket_get")(Frame("ticket_get", requestId = "r1"), Caller(owner = true))
        handlers.getValue("ticket_list")(Frame("ticket_list"), Caller(owner = true))
        assertEquals(listOf(true, false), seen)
    }

    @Test
    fun noSpawnChargeChargesNothing() {
        assertFalse(NoSpawnCharge.isSession("s"))
        assertEquals("ran false", NoSpawnCharge.admitIf("s", true) { placed -> "ran $placed" })
    }

    @Test
    fun projectFramesAreNotBoardOwned() {
        assertTrue(BoardFrames.TYPES.none { it in BoardFrames.CORE_PROJECT_TYPES })
        assertEquals("ticket_save refused: this daemon has no board", BoardFrames.noBoard("ticket_save").data)
    }
}
