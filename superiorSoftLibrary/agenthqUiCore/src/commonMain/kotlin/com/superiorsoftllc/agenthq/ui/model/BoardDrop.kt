package com.superiorsoftllc.agenthq.ui.model

import com.superiorsoftllc.agenthq.ui.protocol.Ticket
import com.superiorsoftllc.agenthq.ui.protocol.TicketColumns
import com.superiorsoftllc.agenthq.ui.protocol.TicketStart

/**
 * What dropping a dragged card means. [Stay] sends nothing (the card is already there); [NotAllowed] sends nothing
 * and says why; [Reorder] and [Move] are one `ticket_move`; [Start] is the Start action (`ticket_move` to
 * `in_progress`, the daemon's hand-out), [slot] being where to place it once it has started.
 */
sealed interface DropPlan {
    val ticketId: String

    data class Stay(override val ticketId: String) : DropPlan
    data class NotAllowed(override val ticketId: String, val reason: String) : DropPlan
    data class Reorder(override val ticketId: String, val position: Int) : DropPlan
    data class Move(override val ticketId: String, val status: String, val position: Int) : DropPlan
    data class Start(override val ticketId: String, val slot: Int) : DropPlan
}

/**
 * The pure half of the board's drag and drop: which slot a pointer is over, and what a drop there asks the daemon.
 * A slot counts the target column's cards WITHOUT the dragged one (0 = top), as [TicketReorder.positionFor] does.
 */
object BoardDrop {
    const val PENDING_REFUSAL = "Pending is set by the daemon while a prerequisite is open; a ticket can't be dropped there."
    const val HELD_REFUSAL = "A held ticket leaves Pending by itself once nothing holds it."
    const val BACKLOG_START_REFUSAL = "A Backlog ticket is never started: move it to To do (the sprint) first."

    /** The slot a pointer at [pointer] is over: how many of the other cards' centres ([centers], top first) are above it. */
    fun slotAt(centers: List<Float>, pointer: Float): Int = centers.count { it < pointer }

    /** Whether a card from [from] may be dropped in [to]; the refusal to show, or null when it may. */
    fun refusal(from: String, to: String): String? = when {
        from == to -> null
        to == TicketColumns.PENDING -> PENDING_REFUSAL
        // 1.131.0: a held ticket may be parked in Backlog (it stays held there), nowhere else by hand.
        from == TicketColumns.PENDING && to != TicketColumns.BACKLOG -> HELD_REFUSAL
        from == TicketColumns.BACKLOG && to == TicketColumns.IN_PROGRESS -> BACKLOG_START_REFUSAL
        else -> null
    }

    /**
     * What dropping [ticket] at [slot] of [to] asks for. [column] is the board's view of a column, top first (it
     * may be filtered; positions are the daemon's). A move inside `in_progress` is a reorder, never a new start.
     */
    fun plan(column: (String) -> List<Ticket>, ticket: Ticket, to: String, slot: Int): DropPlan {
        refusal(ticket.status, to)?.let { return DropPlan.NotAllowed(ticket.id, it) }
        if (to == ticket.status) {
            val position = TicketReorder.positionFor(column(to), ticket.id, slot) ?: return DropPlan.Stay(ticket.id)
            return DropPlan.Reorder(ticket.id, position)
        }
        if (to == TicketColumns.IN_PROGRESS) return DropPlan.Start(ticket.id, slot)
        return DropPlan.Move(ticket.id, to, insertPosition(column(to).filter { it.id != ticket.id }, slot))
    }

    /** The daemon position that puts a card arriving from another column at [slot] of [target]. */
    private fun insertPosition(target: List<Ticket>, slot: Int): Int {
        val at = slot.coerceIn(0, target.size)
        return when {
            target.isEmpty() -> 0
            at == 0 -> target[0].position
            else -> target[at - 1].position + 1
        }
    }

    /** A start refused for want of a role (the daemon's "ticket has no role"): the card offers the role pick, not its form. */
    fun isNoRoleNote(note: String): Boolean = note.contains("ticket has no role")

    /** The line a start that did not start shows on the card that snapped back, from the daemon's answer. */
    fun startNote(start: TicketStart): String = when (start.reason) {
        "held" -> "Not started: held by ${start.detail ?: "an open prerequisite"}"
        "queued" -> "Not started: queued for ${start.detail ?: "its lane"}, which is at capacity"
        else -> "Not started: ${start.detail?.takeIf { it.isNotBlank() } ?: start.reason.replace('_', ' ')}"
    }
}
