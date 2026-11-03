// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.ui.model

import com.superiorsoftllc.infantry.ui.protocol.Ticket

/**
 * Ticket order is priority: dragging a card inside a column asks the daemon for a new `position` (`ticket_move`
 * with no status). This is the pure half of that: which card slot a drag ends in, and the daemon position for it.
 */
object TicketReorder {
    /**
     * The slot a dragged card lands in: how many of the OTHER cards have their centre above [draggedCenter].
     * [centers] are every card's centre, top first, the dragged one included at [draggedIndex].
     */
    fun slotFor(centers: List<Float>, draggedIndex: Int, draggedCenter: Float): Int =
        centers.filterIndexed { i, _ -> i != draggedIndex }.count { it < draggedCenter }

    /**
     * The `position` to send to move [movedId] to index [toIndex] of [shown] (one column, top first; it may be a
     * filtered view of the daemon's column, whose positions are not contiguous in it). Null = nothing to send, the
     * card is already there. The daemon counts the index after lifting the ticket out, so a card moving down is
     * one place above where a plain index would put it.
     */
    fun positionFor(shown: List<Ticket>, movedId: String, toIndex: Int): Int? {
        val moved = shown.firstOrNull { it.id == movedId } ?: return null
        val others = shown.filter { it.id != movedId }
        val slot = toIndex.coerceIn(0, others.size)
        if (slot == shown.indexOf(moved)) return null
        val lifted = { anchor: Ticket -> if (moved.position < anchor.position) 1 else 0 }
        return if (slot == 0) {
            others[0].position - lifted(others[0])
        } else {
            val above = others[slot - 1]
            above.position + 1 - lifted(above)
        }
    }
}
