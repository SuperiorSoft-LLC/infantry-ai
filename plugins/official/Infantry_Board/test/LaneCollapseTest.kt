// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.plugins.official.board

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import com.superiorsoftllc.infantry.ui.model.DropPlan
import com.superiorsoftllc.infantry.ui.protocol.Ticket
import com.superiorsoftllc.infantry.ui.protocol.TicketColumns
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** LANE-COLLAPSE-1: collapse state per project survives a reload; a drop on a collapsed strip lands at the lane's end. */
class LaneCollapseTest {
    private val store = mutableMapOf<String, String>()
    private fun lanes(project: String?) = LaneCollapse(project, { store[it] }, { k, v -> store[k] = v })

    @Test fun defaultAllExpandedAndStateSurvivesReloadPerProject() {
        val a = lanes("p1")
        assertFalse(a.isCollapsed(TicketColumns.DONE))
        a.toggle(TicketColumns.DONE); a.toggle("custom-lane")
        assertTrue(lanes("p1").isCollapsed(TicketColumns.DONE))
        assertTrue(lanes("p1").isCollapsed("custom-lane"))
        assertFalse(lanes("p2").isCollapsed(TicketColumns.DONE))
        lanes("p1").toggle(TicketColumns.DONE)
        assertFalse(lanes("p1").isCollapsed(TicketColumns.DONE))
    }

    @Test fun dropOnCollapsedStripMovesTicketToLaneEnd() {
        fun t(id: String, s: String) = Ticket(id = id, title = id, status = s)
        val board = mapOf(TicketColumns.TODO to listOf(t("a", TicketColumns.TODO)), TicketColumns.BACKLOG to listOf(t("b", TicketColumns.BACKLOG), t("c", TicketColumns.BACKLOG)))
        val columnOf: (String) -> List<Ticket> = { board[it].orEmpty() }
        val drag = BoardDrag()
        drag.columns[TicketColumns.BACKLOG] = Rect(0f, 0f, 36f, 500f)
        drag.collapsed = setOf(TicketColumns.BACKLOG)
        drag.begin(board.getValue(TicketColumns.TODO).first(), Offset(10f, 5f))
        val hover = drag.hover(columnOf)!!
        assertEquals(TicketColumns.BACKLOG, hover.column)
        assertEquals(2, hover.slot)
        assertFalse(hover.plan is DropPlan.NotAllowed)
        assertIs<DropPlan>(hover.plan)
        drag.end()
    }
}
