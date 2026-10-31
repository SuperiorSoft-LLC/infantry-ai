package com.superiorsoftllc.agenthq.backlog

import com.superiorsoftllc.agenthq.daemon.api.board.BoardHost
import com.superiorsoftllc.agenthq.daemon.api.board.BoardStorage
import com.superiorsoftllc.agenthq.daemon.api.board.BoardTables
import com.superiorsoftllc.agenthq.daemon.api.board.StoredLink
import com.superiorsoftllc.agenthq.daemon.api.board.StoredRun
import com.superiorsoftllc.agenthq.daemon.api.board.StoredTicket
import com.superiorsoftllc.agenthq.daemon.api.board.TicketEventAppender
import com.superiorsoftllc.agenthq.daemon.api.board.TicketRunTable
import com.superiorsoftllc.agenthq.daemon.api.board.TicketTable

/**
 * DP-5f-5: a board-side fake [BoardHost] -- tickets, links and runs in memory, events recorded in a list. Sessions, agents, git,
 * context and images keep the [com.superiorsoftllc.agenthq.daemon.api.board.NoBoardHost] answers (no sessions, no git). A
 * transaction is a lock only: there is no rollback, which the moved rule tests never need (a refusal throws before any write).
 */
class InMemoryBoardHost(private val clock: () -> Long = System::currentTimeMillis) : BoardHost {
    private val tickets = LinkedHashMap<String, StoredTicket>()
    private val links = ArrayList<StoredLink>()
    private val runRows = ArrayList<StoredRun>()

    /** Every `record` the board made: (before, after, changedIds, deleted). */
    val recorded = mutableListOf<List<Any>>()

    private val appender = object : TicketEventAppender {
        override fun record(before: Map<String, String>, after: Map<String, String>, changedIds: Collection<String>, deleted: Collection<String>) {
            recorded += listOf(before, after, changedIds.toList(), deleted.toList())
        }
    }

    private val ticketTable = object : TicketTable {
        override fun create(ticket: StoredTicket) {
            require(ticket.id !in tickets) { "duplicate ticket ${ticket.id}" }
            tickets[ticket.id] = ticket
        }
        override fun update(ticket: StoredTicket) {
            val old = tickets[ticket.id] ?: return
            tickets[ticket.id] = ticket.copy(createdAt = old.createdAt)
        }
        override fun get(id: String) = tickets[id]
        override fun all() = tickets.values.toList()
        override fun delete(id: String): Boolean {
            val had = tickets.remove(id) != null
            links.removeAll { it.ticketId == id || it.relatedId == id }
            return had
        }
        override fun link(link: StoredLink): Boolean {
            if (links.any { it.ticketId == link.ticketId && it.relatedId == link.relatedId && it.type == link.type }) return false
            links += link
            return true
        }
        override fun unlink(link: StoredLink) =
            links.removeAll { it.ticketId == link.ticketId && it.relatedId == link.relatedId && it.type == link.type }
        override fun links() = links.toList()
    }

    private val runTable = object : TicketRunTable {
        override fun insert(run: StoredRun) { runRows += run }
        override fun finish(ticketId: String, sessionId: String, endedAt: Long, outcome: String, detail: String?) {
            val i = runRows.indexOfFirst { it.ticketId == ticketId && it.sessionId == sessionId }
            if (i >= 0) runRows[i] = runRows[i].copy(endedAt = endedAt, outcome = outcome, detail = detail)
        }
        override fun byTicket(ticketId: String) = runRows.filter { it.ticketId == ticketId }
    }

    private val boardTables = object : BoardTables {
        override val tickets = ticketTable
        override val runs = runTable
        override val events = appender
    }

    override val storage: BoardStorage = object : BoardStorage {
        override fun <T> transaction(block: BoardTables.() -> T): T = synchronized(tickets) { boardTables.block() }
        override fun <T> writeTransaction(block: BoardTables.() -> T): T = synchronized(tickets) { boardTables.block() }
        override val tables: BoardTables get() = boardTables
    }

    override val events: TicketEventAppender = appender
    override fun now(): Long = clock()
}
