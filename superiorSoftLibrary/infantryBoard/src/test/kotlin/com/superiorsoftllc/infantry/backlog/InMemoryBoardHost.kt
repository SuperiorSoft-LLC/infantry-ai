// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.backlog

import com.superiorsoftllc.infantry.daemon.api.board.BoardAgent
import com.superiorsoftllc.infantry.daemon.api.board.BoardAgents
import com.superiorsoftllc.infantry.daemon.api.board.BoardExecutor
import com.superiorsoftllc.infantry.daemon.api.board.BoardHost
import com.superiorsoftllc.infantry.daemon.api.board.BoardProviderInfo
import com.superiorsoftllc.infantry.daemon.api.board.BoardSessions
import com.superiorsoftllc.infantry.daemon.api.board.SessionStart
import com.superiorsoftllc.infantry.daemon.api.board.SessionStartResult
import com.superiorsoftllc.infantry.daemon.api.sessions.SessionView
import com.superiorsoftllc.infantry.daemon.api.board.BoardStorage
import com.superiorsoftllc.infantry.daemon.api.board.BoardTables
import com.superiorsoftllc.infantry.daemon.api.board.StoredLink
import com.superiorsoftllc.infantry.daemon.api.board.StoredRun
import com.superiorsoftllc.infantry.daemon.api.board.StoredTicket
import com.superiorsoftllc.infantry.daemon.api.board.TicketEventAppender
import com.superiorsoftllc.infantry.daemon.api.board.TicketRunTable
import com.superiorsoftllc.infantry.daemon.api.board.TicketTable

/**
 * DP-5f-5: a board-side fake [BoardHost] -- tickets, links and runs in memory, events recorded in a list. Sessions, agents, git,
 * context and images keep the [com.superiorsoftllc.infantry.daemon.api.board.NoBoardHost] answers (no sessions, no git). A
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

    /** DP-5f-6: a session fake -- every start is recorded and answered `Started` (state BUSY, live) unless [refuseStarts] is set. */
    /** The directory a start without a worktree runs in (a path only: the fake never touches the disk). */
    var workingDir: String = System.getProperty("java.io.tmpdir")
    val starts = mutableListOf<SessionStart>()
    var refuseStarts: String? = null
    private val sessionViews = LinkedHashMap<String, SessionView>()
    private val fakeProvider = BoardProviderInfo("fake", contextLimitSupport = "supported", effortSupport = "supported")

    override val sessions: BoardSessions = object : BoardSessions {
        override fun start(request: SessionStart): SessionStartResult {
            refuseStarts?.let { return SessionStartResult.Refused(it) }
            starts += request
            val view = SessionView(
                id = "session-${starts.size}", agentId = request.agentId, role = request.role, provider = request.providerId ?: "fake",
                model = request.model, projectId = request.projectId, state = "BUSY", live = true, ticketId = request.ticketId,
                workingDir = request.worktree?.worktreePath ?: request.workingDir, worktreePath = request.worktree?.worktreePath,
                worktreeBranch = request.worktree?.branch, repoRoot = request.worktree?.repoRoot, contextLimit = request.contextLimit,
            )
            sessionViews[view.id] = view
            return SessionStartResult.Started(view)
        }
        override fun checkSpawn(providerId: String?, model: String?): String? =
            if (providerId != null && providerId != "fake") "unknown provider '$providerId'" else null
        override fun get(sessionId: String) = sessionViews[sessionId]
        override fun list() = sessionViews.values.toList()
        override fun kill(sessionId: String, force: Boolean, by: String, reason: String?): Boolean {
            val v = sessionViews[sessionId] ?: return false
            sessionViews[sessionId] = v.copy(state = "EXITED", live = false)
            return true
        }
        override fun remove(sessionId: String): String? { sessionViews.remove(sessionId); return null }
        override fun providers() = listOf(fakeProvider)
        override fun providerId() = "fake"
        override fun hasProvider(id: String) = id == "fake"
        override fun providerFor(id: String) = fakeProvider.takeIf { id == "fake" }
        override fun available() = true
        override fun defaultWorkingDir(): String = workingDir
    }

    /** DP-5f-6: agent profiles in memory (ids `agent-1`...); every role is known. */
    private val profiles = LinkedHashMap<String, BoardAgent>()
    override val agents: BoardAgents = object : BoardAgents {
        override fun get(id: String) = profiles[id]
        override fun list() = profiles.values.toList()
        override fun save(agent: BoardAgent): BoardAgent {
            val saved = agent.copy(id = agent.id ?: "agent-${profiles.size + 1}")
            profiles[saved.id!!] = saved
            return saved
        }
        override fun delete(id: String) { profiles.remove(id) }
        override fun roleKnown(role: String) = true
        override fun validateExecutor(provider: String?, model: String?, effort: String?): BoardExecutor {
            if (provider != null && provider != "fake") throw IllegalArgumentException("unknown provider '$provider'")
            return BoardExecutor(provider, model, effort)
        }
    }

    override val events: TicketEventAppender = appender
    override fun now(): Long = clock()
}
