package com.superiorsoftllc.agenthq.daemon

import com.superiorsoftllc.agenthq.backlog.TicketAnswer
import com.superiorsoftllc.agenthq.backlog.TicketBoard
import com.superiorsoftllc.agenthq.protocol.TicketMove
import com.superiorsoftllc.agenthq.protocol.TicketStatus
import com.superiorsoftllc.agenthq.session.SessionRecord
import com.superiorsoftllc.agenthq.session.TicketWorkerHealth

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
class TicketStartCharge(
    private val guard: SpawnGuard,
    private val board: () -> TicketBoard?,
    private val sessions: (String) -> SessionRecord?,
    private val setSpawner: (sessionId: String, spawnerId: String?) -> Unit,
) {
    fun move(move: TicketMove, startedBy: String?): TicketAnswer {
        val b = board() ?: throw IllegalArgumentException("this daemon has no board")
        val starter = startedBy?.trim()?.takeIf { it.isNotEmpty() && sessions(it) != null }
        val charged = starter != null && runsWorker(b, move)
        return guard.admitIf(starter, charged) { placement ->
            b.move(move, startedBy = starter).also { answer ->
                val worker = answer.ticket.takeIf { it.start?.ok == true }?.sessionId
                if (worker != null && starter != null) {
                    if (placement != null) guard.record(worker, starter)
                    setSpawner(worker, starter)
                }
            }
        }
    }

    /** Whether [move] would hand the ticket to a worker: as `TicketBoard.move` decides it (GAP-006b restart included). */
    fun runsWorker(b: TicketBoard, move: TicketMove): Boolean {
        if (move.status != TicketStatus.IN_PROGRESS) return false
        val current = runCatching { b.get(move.id) }.getOrNull() ?: return false
        if (current.status != TicketStatus.IN_PROGRESS) return true
        return move.position == null && TicketWorkerHealth.problem(current.sessionId?.let(sessions), current.sessionId) != null
    }
}
