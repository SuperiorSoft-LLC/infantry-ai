package com.superiorsoftllc.infantry.daemon.plugins

import com.superiorsoftllc.infantry.daemon.api.Caller
import com.superiorsoftllc.infantry.daemon.api.board.Board
import com.superiorsoftllc.infantry.daemon.api.board.BoardResult
import com.superiorsoftllc.infantry.daemon.api.board.Epics
import com.superiorsoftllc.infantry.daemon.api.board.GRANT_EPICS_READ
import com.superiorsoftllc.infantry.daemon.api.board.GRANT_EPICS_WRITE
import com.superiorsoftllc.infantry.daemon.api.board.GRANT_TICKETS_DELETE
import com.superiorsoftllc.infantry.daemon.api.board.GRANT_TICKETS_READ
import com.superiorsoftllc.infantry.daemon.api.board.GRANT_TICKETS_START
import com.superiorsoftllc.infantry.daemon.api.board.GRANT_TICKETS_WRITE
import com.superiorsoftllc.infantry.daemon.api.board.TicketDraft
import com.superiorsoftllc.infantry.daemon.api.board.TicketEvent
import com.superiorsoftllc.infantry.daemon.api.board.TicketEvents
import com.superiorsoftllc.infantry.daemon.api.board.TicketPatch
import com.superiorsoftllc.infantry.daemon.api.board.TicketQuery
import com.superiorsoftllc.infantry.daemon.api.board.Tickets

/**
 * DP-4a, the trust model for the board API: bundled / official plugins are trusted, a COMMUNITY plugin needs the grant of each
 * operation in modules.json (`tickets.read`, `tickets.write`, `tickets.delete`, `tickets.start`, `epics.read`, `epics.write`);
 * without it the call is a [BoardResult.Refused] naming the missing grant. Default deny. [allowed] answers for one grant.
 */
internal class GrantedBoard(private val tickets: () -> Tickets, private val epics: () -> Epics, private val board: () -> Board, private val allowed: (String) -> Boolean) {
    private inline fun <T> need(grant: String, call: () -> BoardResult<T>): BoardResult<T> =
        if (allowed(grant)) call() else BoardResult.Refused("missing grant '$grant' in modules.json")

    /** DP-4b: subscribing to the ticket events needs `tickets.read`; a missing grant throws (the host disables the plugin, as for a bad declaration). */
    fun ticketEvents(source: () -> TicketEvents): TicketEvents = object : TicketEvents {
        override fun subscribe(consumer: String, listener: (TicketEvent) -> Unit): AutoCloseable {
            check(allowed(GRANT_TICKETS_READ)) { "missing grant '$GRANT_TICKETS_READ' in modules.json" }
            return source().subscribe(consumer, listener)
        }
    }

    val ticketsApi: Tickets = object : Tickets {
        override fun get(id: String, by: Caller) = need(GRANT_TICKETS_READ) { tickets().get(id, by) }
        override fun list(by: Caller, query: TicketQuery) = need(GRANT_TICKETS_READ) { tickets().list(by, query) }
        override fun create(draft: TicketDraft, by: Caller) = need(GRANT_TICKETS_WRITE) { tickets().create(draft, by) }
        override fun update(id: String, patch: TicketPatch, by: Caller) = need(GRANT_TICKETS_WRITE) { tickets().update(id, patch, by) }
        override fun move(id: String, status: String?, position: Int?, by: Caller) = need(GRANT_TICKETS_WRITE) { tickets().move(id, status, position, by) }
        override fun delete(id: String, by: Caller) = need(GRANT_TICKETS_DELETE) { tickets().delete(id, by) }
        override fun link(from: String, to: String, kind: String, by: Caller) = need(GRANT_TICKETS_WRITE) { tickets().link(from, to, kind, by) }
        override fun unlink(from: String, to: String, kind: String, by: Caller) = need(GRANT_TICKETS_WRITE) { tickets().unlink(from, to, kind, by) }
        override fun reorder(status: String, ids: List<String>, by: Caller) = need(GRANT_TICKETS_WRITE) { tickets().reorder(status, ids, by) }
        override fun start(id: String, by: Caller) = need(GRANT_TICKETS_START) { tickets().start(id, by) }
        override fun starterOf(sessionId: String, by: Caller) = need(GRANT_TICKETS_READ) { tickets().starterOf(sessionId, by) }
        override fun ticketOf(sessionId: String, by: Caller) = need(GRANT_TICKETS_READ) { tickets().ticketOf(sessionId, by) }
        override fun workerOf(ticketId: String, by: Caller) = need(GRANT_TICKETS_READ) { tickets().workerOf(ticketId, by) }
        override fun mayFile(projectId: String, by: Caller) = need(GRANT_TICKETS_WRITE) { tickets().mayFile(projectId, by) }
    }

    val epicsApi: Epics = object : Epics {
        override fun get(id: String, by: Caller) = need(GRANT_EPICS_READ) { epics().get(id, by) }
        override fun list(projectId: String?, by: Caller) = need(GRANT_EPICS_READ) { epics().list(projectId, by) }
        override fun create(projectId: String, name: String, goal: String, by: Caller) = need(GRANT_EPICS_WRITE) { epics().create(projectId, name, goal, by) }
        override fun setBase(id: String, branch: String?, clear: Boolean, by: Caller) = need(GRANT_EPICS_WRITE) { epics().setBase(id, branch, clear, by) }
    }

    val boardApi: Board = object : Board {
        override fun columns(projectId: String?, by: Caller) = need(GRANT_TICKETS_READ) { board().columns(projectId, by) }
        override fun brief(ticketId: String, by: Caller) = need(GRANT_TICKETS_READ) { board().brief(ticketId, by) }
    }
}
