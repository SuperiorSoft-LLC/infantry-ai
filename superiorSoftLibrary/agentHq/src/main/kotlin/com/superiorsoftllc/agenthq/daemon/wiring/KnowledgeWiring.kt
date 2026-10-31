package com.superiorsoftllc.agenthq.daemon.wiring

import com.superiorsoftllc.agenthq.decisions.DecisionAuthor
import com.superiorsoftllc.agenthq.decisions.DecisionHandler
import com.superiorsoftllc.agenthq.decisions.DecisionStore
import com.superiorsoftllc.agenthq.issues.IssueStore
import com.superiorsoftllc.agenthq.memory.MemoryStore
import com.superiorsoftllc.agenthq.memory.SqlMemoryPersistence
import com.superiorsoftllc.agenthq.persistence.AgentHqStore
import com.superiorsoftllc.agenthq.persistence.SqlAgentHqStore
import com.superiorsoftllc.agenthq.protocol.Envelope
import com.superiorsoftllc.agenthq.session.SessionManager
import kotlinx.coroutines.channels.Channel

/** Knowledge wiring (CORE-SPLIT-4a1): the project-memory, decisions and issues stores, built over the daemon's own database handle. */
object KnowledgeWiring {
    /** MEM-2: project-memory notes, over the same database file as everything else; NOTES-1: a session's notes die with it. */
    fun buildMemoryNotes(store: AgentHqStore, manager: SessionManager): MemoryStore {
        val memoryNotes = MemoryStore(
            persistence = SqlMemoryPersistence((store as SqlAgentHqStore).database),
        )
        manager.onSessionEnded = { sessionId -> memoryNotes.purgeSession(sessionId) }
        return memoryNotes
    }

    /** What [buildDecisions] builds. */
    data class Decisions(val decisions: DecisionStore, val frames: DecisionHandler)

    /** P10-K4: the decisions log, over the same file. A decision_add's author comes from the declared session. */
    fun buildDecisions(store: AgentHqStore, manager: SessionManager, clients: List<Channel<Envelope>>): Decisions {
        val decisions = DecisionStore(store as SqlAgentHqStore)
        val decisionFrames = DecisionHandler(
            store = decisions,
            author = { sessionId ->
                manager.get(sessionId)?.let { DecisionAuthor(sessionId = it.id, agentId = it.agentId, role = it.role) }
            },
            push = { envelope -> for (client in clients) client.trySend(envelope) },
        )
        return Decisions(decisions, decisionFrames)
    }

    /** P10-K6: the issues log, over the same file. */
    fun buildIssues(store: AgentHqStore): IssueStore = IssueStore(store as SqlAgentHqStore)
}
