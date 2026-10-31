package com.superiorsoftllc.agenthq.daemon.wiring

import com.superiorsoftllc.agenthq.persistence.AgentHqStore
import com.superiorsoftllc.agenthq.persistence.SqlAgentHqStore
import com.superiorsoftllc.agenthq.pipeline.PipelineHandler
import com.superiorsoftllc.agenthq.pipeline.PipelineStore
import com.superiorsoftllc.agenthq.protocol.Envelope
import com.superiorsoftllc.agenthq.session.SessionManager
import kotlinx.coroutines.channels.Channel

/** Pipeline wiring (CORE-SPLIT-4a1). */
object PipelineWiring {
    /** PIPE-1: the optional project pipeline, over the same file. */
    fun buildFrames(store: AgentHqStore, manager: SessionManager, clients: List<Channel<Envelope>>): PipelineHandler =
        PipelineHandler(
            store = PipelineStore(store as SqlAgentHqStore),
            author = { sessionId -> manager.get(sessionId)?.let { "${it.role ?: "agent"} (${it.agentId})" } },
            push = { envelope -> for (client in clients) client.trySend(envelope) },
        )
}
