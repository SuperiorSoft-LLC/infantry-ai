package com.superiorsoftllc.agenthq.daemon.wiring

import com.superiorsoftllc.agenthq.memory.MemoryStore
import com.superiorsoftllc.agenthq.memory.SqlMemoryPersistence
import com.superiorsoftllc.agenthq.persistence.AgentHqStore
import com.superiorsoftllc.agenthq.persistence.SqlAgentHqStore
import com.superiorsoftllc.agenthq.session.SessionManager

/** Knowledge wiring (CORE-SPLIT-4a1): the project-memory, store (decisions and issues are the Infantry_Notes plugin's since DP-7c), built over the daemon's own database handle. */
object KnowledgeWiring {
    /** MEM-2: project-memory notes, over the same database file as everything else; NOTES-1: a session's notes die with it. */
    fun buildMemoryNotes(store: AgentHqStore, manager: SessionManager): MemoryStore {
        val memoryNotes = MemoryStore(
            persistence = SqlMemoryPersistence((store as SqlAgentHqStore).database),
        )
        manager.onSessionEnded = { sessionId -> memoryNotes.purgeSession(sessionId) }
        return memoryNotes
    }
}
