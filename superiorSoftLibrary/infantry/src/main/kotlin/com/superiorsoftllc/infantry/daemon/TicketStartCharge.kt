// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.daemon

import com.superiorsoftllc.infantry.protocol.Ticket
import com.superiorsoftllc.infantry.protocol.TicketMove
import com.superiorsoftllc.infantry.protocol.TicketStatus
import com.superiorsoftllc.infantry.session.SessionRecord
import com.superiorsoftllc.infantry.session.TicketWorkerHealth

/** What [TicketStartCharge] needs of the board: read a ticket, run a move, read the ticket out of the move's answer [A]. DaemonMain adapts the board. */
interface StartMoves<A> {
    fun get(id: String): Ticket
    fun move(move: TicketMove, startedBy: String?): A
    fun ticketOf(answer: A): Ticket
}

/**
 * RUNTIME-LOAD-6: a ticket move charged to the [SpawnGuard] like any session-originated spawn. The ONE path of a
 * session's ticket start: the `ticket_move` frame, `api.tickets.start` and `api.spawns.startTicket` all come here.
 *
 * A move by [startedBy] (a session the daemon holds) that would run a worker is checked against the depth limit and
 * the starter's pod cap under the guard's lock, then the board's own move runs under that same lock; the worker it
 * starts joins the starter's pod and records the starter as its spawner (contract 1.114.0's kill rule follows). A move
 * by nobody (the UI, a person), or one that runs no worker, is not charged. The board has the last word on whether a
 * worker runs: a start it queues or holds records nothing.
 */
class TicketStartCharge<A>(
    private val guard: SpawnGuard,
    private val board: () -> StartMoves<A>?,
    private val sessions: (String) -> SessionRecord?,
    private val setSpawner: (sessionId: String, spawnerId: String?) -> Unit,
) {
    fun move(move: TicketMove, startedBy: String?): A {
        val b = board() ?: throw IllegalArgumentException("this daemon has no board")
        val starter = startedBy?.trim()?.takeIf { it.isNotEmpty() && sessions(it) != null }
        val charged = starter != null && runsWorker(b, move)
        return guard.admitIf(starter, charged) { placement ->
            b.move(move, starter).also { answer ->
                val worker = b.ticketOf(answer).takeIf { it.start?.ok == true }?.sessionId
                if (worker != null && starter != null) {
                    if (placement != null) guard.record(worker, starter)
                    setSpawner(worker, starter)
                }
            }
        }
    }

    /** Whether [move] would hand the ticket to a worker: as `TicketBoard.move` decides it (GAP-006b restart included). */
    fun runsWorker(b: StartMoves<A>, move: TicketMove): Boolean {
        if (move.status != TicketStatus.IN_PROGRESS) return false
        val current = runCatching { b.get(move.id) }.getOrNull() ?: return false
        if (current.status != TicketStatus.IN_PROGRESS) return true
        return move.position == null && TicketWorkerHealth.problem(current.sessionId?.let(sessions), current.sessionId) != null
    }
}
