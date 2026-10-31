package com.superiorsoftllc.agenthq.attention

import com.superiorsoftllc.agenthq.daemon.api.attention.Attention
import com.superiorsoftllc.agenthq.daemon.api.attention.AttentionEntry

/** ATTN-1: core's local [Attention] provider over [AttentionStore]. */
class LocalAttention(private val store: AttentionStore) : Attention {
    override fun raise(kind: String, triggerId: String?, ticketId: String?, podId: String?, sessionId: String?, outcome: String, detail: String): String? =
        store.raise(AttentionKind.fromWireName(kind), triggerId, ticketId, podId, sessionId, outcome, detail).id

    override fun resolve(id: String): Boolean = store.acknowledge(id) != null

    override fun list(project: String?): List<AttentionEntry> = store.list().map {
        AttentionEntry(it.id, it.at, it.kind.wireName, it.triggerId, it.ticketId, it.podId, it.sessionId, it.outcome, it.detail, it.ackedAt)
    }
}

/**
 * ATTN-1: what a consumer holds. Looks the provider up per call (the plugin host is built after the consumers); absent = the
 * item is dropped, with one log line the first time.
 */
class FoundAttention(private val find: () -> Attention?) : Attention {
    @Volatile private var warned = false

    private fun provider(): Attention? = find() ?: run {
        if (!warned) { warned = true; System.err.println("[agentHq] no Attention provider registered; attention items are dropped") }
        null
    }

    override fun raise(kind: String, triggerId: String?, ticketId: String?, podId: String?, sessionId: String?, outcome: String, detail: String): String? =
        provider()?.raise(kind, triggerId, ticketId, podId, sessionId, outcome, detail)

    override fun resolve(id: String): Boolean = provider()?.resolve(id) ?: false

    override fun list(project: String?): List<AttentionEntry> = provider()?.list(project).orEmpty()
}
