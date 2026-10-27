package com.superiorsoftllc.agenthq.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.superiorsoftllc.agenthq.ui.model.BoardDrop
import com.superiorsoftllc.agenthq.ui.model.DropPlan
import com.superiorsoftllc.agenthq.ui.model.TicketReorder
import com.superiorsoftllc.agenthq.ui.net.CommandResult
import com.superiorsoftllc.agenthq.ui.net.Controller
import com.superiorsoftllc.agenthq.ui.protocol.Ticket
import com.superiorsoftllc.agenthq.ui.protocol.TicketColumns

/** The three requests a drop can make; each answers null once sent, else why it could not be. */
internal interface TicketMoves {
    suspend fun reorder(id: String, position: Int): String?
    suspend fun move(id: String, status: String, position: Int): String?
    /** The Start action: `ticket_move` to `in_progress`, the same daemon path as `agenthq ticket start`. */
    suspend fun start(id: String): String?
}

internal fun Controller.ticketMoves(): TicketMoves = object : TicketMoves {
    override suspend fun reorder(id: String, position: Int) = reorderTicket(id, position).refusal()
    override suspend fun move(id: String, status: String, position: Int) = moveTicket(id, status, position = position).refusal()
    override suspend fun start(id: String) = moveTicket(id, TicketColumns.IN_PROGRESS).refusal()
}

private fun CommandResult.refusal(): String? = when (this) {
    is CommandResult.Refused -> reason
    else -> null
}

/**
 * The board's drops: sends what a [DropPlan] asks and keeps the one line a card that snapped back shows ([notes], by
 * ticket id). The daemon owns the columns and the order; nothing here moves a card itself. A drop into In Progress
 * waits for the daemon's answer ([observe]): started, it places the card at its slot; held, queued or refused, the
 * card never left its column and shows why.
 */
internal class BoardDropSession(private val moves: TicketMoves) {
    var notes by mutableStateOf<Map<String, String>>(emptyMap())
        private set

    /** A start waiting for its answer: where to place the card, and the ticket as last seen (a new one is the answer). */
    private class PendingStart(val slot: Int, var seen: Ticket?)
    private val starting = mutableMapOf<String, PendingStart>()

    fun isStarting(id: String): Boolean = id in starting

    fun dismiss(id: String) { notes = notes - id }

    private fun note(id: String, text: String) { notes = notes + (id to text) }

    /** Send what [plan] asks; [current] is the dragged ticket as the board shows it now. */
    suspend fun drop(plan: DropPlan, current: Ticket?) {
        val id = plan.ticketId
        dismiss(id)
        val refused = when (plan) {
            is DropPlan.Stay -> null
            is DropPlan.NotAllowed -> plan.reason
            is DropPlan.Reorder -> moves.reorder(id, plan.position)
            is DropPlan.Move -> moves.move(id, plan.status, plan.position)
            is DropPlan.Start -> {
                // Registered before the send: the answer can arrive before it returns.
                starting[id] = PendingStart(plan.slot, current)
                moves.start(id).also { if (it != null) starting.remove(id) }
            }
        }
        refused?.let { note(id, it) }
    }

    /**
     * Read the board after each change: a waiting start's ticket that changed is its answer. [column] is the board's
     * view of a column, top first, the one the drop's slot was counted in.
     */
    suspend fun observe(tickets: List<Ticket>, column: (String) -> List<Ticket>) {
        for ((id, pending) in starting.toList()) {
            val t = tickets.firstOrNull { it.id == id }
            if (t == null) { starting.remove(id); continue }
            if (t === pending.seen) continue
            pending.seen = t
            val start = t.start
            when {
                t.status == TicketColumns.IN_PROGRESS -> {
                    starting.remove(id)
                    TicketReorder.positionFor(column(TicketColumns.IN_PROGRESS), id, pending.slot)?.let { moves.reorder(id, it) }
                }
                start != null && !start.ok -> {
                    starting.remove(id)
                    note(id, BoardDrop.startNote(start))
                }
            }
        }
    }
}
