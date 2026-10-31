package com.superiorsoftllc.agenthq.daemon.wiring

import com.superiorsoftllc.agenthq.attention.AttentionItem
import com.superiorsoftllc.agenthq.attention.AttentionStore
import com.superiorsoftllc.agenthq.persistence.AgentHqStore

/** Attention wiring (CORE-SPLIT-4a1). */
object AttentionWiring {
    fun build(store: AgentHqStore, eventSink: (AttentionItem) -> Unit): AttentionStore {
        val attention = AttentionStore(store.attention)
        attention.eventSink = eventSink
        return attention
    }
}
