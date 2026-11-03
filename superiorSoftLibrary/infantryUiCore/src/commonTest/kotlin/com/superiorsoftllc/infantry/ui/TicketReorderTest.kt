package com.superiorsoftllc.infantry.ui

import com.superiorsoftllc.infantry.ui.model.TicketReorder
import com.superiorsoftllc.infantry.ui.protocol.Ticket
import com.superiorsoftllc.infantry.ui.protocol.TicketColumns
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Drag-and-drop ticket reordering: which slot a drop lands in and the daemon position it sends. */
class TicketReorderTest {
    private fun column(vararg positions: Int) =
        positions.mapIndexed { i, p -> Ticket(id = "t$i", title = "T$i", status = TicketColumns.TODO, position = p) }

    @Test fun `slot counts the other cards centred above the dragged card`() {
        val centers = listOf(10f, 40f, 70f)
        assertEquals(2, TicketReorder.slotFor(centers, 0, 80f))
        assertEquals(0, TicketReorder.slotFor(centers, 2, 5f))
        assertEquals(1, TicketReorder.slotFor(centers, 1, 42f))
    }

    @Test fun `dropping in place sends nothing`() {
        assertNull(TicketReorder.positionFor(column(0, 1, 2), "t1", 1))
        assertNull(TicketReorder.positionFor(column(0, 1, 2), "missing", 0))
    }

    @Test fun `moving up puts the card at the target's index`() {
        assertEquals(0, TicketReorder.positionFor(column(0, 1, 2), "t2", 0))
        assertEquals(1, TicketReorder.positionFor(column(0, 1, 2), "t2", 1))
    }

    @Test fun `moving down counts the index after the card is lifted out`() {
        assertEquals(2, TicketReorder.positionFor(column(0, 1, 2), "t0", 2))
        assertEquals(1, TicketReorder.positionFor(column(0, 1, 2), "t0", 1))
    }

    @Test fun `a filtered view anchors on the daemon's own positions`() {
        // the epic filter shows the tickets at daemon positions 1, 4 and 7
        val shown = column(1, 4, 7)
        assertEquals(7, TicketReorder.positionFor(shown, "t0", 2))
        assertEquals(1, TicketReorder.positionFor(shown, "t2", 0))
    }
}
