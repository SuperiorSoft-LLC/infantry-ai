package com.superiorsoftllc.infantry.daemon

import com.superiorsoftllc.infantry.daemon.api.attention.Attention
import com.superiorsoftllc.infantry.daemon.api.attention.AttentionEntry

/** ATTN-2b test support: an in-memory [Attention] provider (the real one is Infantry_Attention's). */
class MemAttention(private val clock: () -> Long = { 1L }) : Attention {
    val rows = mutableListOf<AttentionEntry>()
    @Volatile var eventSink: (AttentionEntry) -> Unit = {}

    @Synchronized
    override fun raise(kind: String, triggerId: String?, ticketId: String?, podId: String?, sessionId: String?, outcome: String, detail: String): String? {
        val e = AttentionEntry("attention-${rows.size + 1}", clock(), kind, triggerId, ticketId, podId, sessionId, outcome, detail)
        rows += e
        eventSink(e)
        return e.id
    }

    @Synchronized override fun resolve(id: String) = rows.any { it.id == id }
    @Synchronized override fun list(project: String?) = rows.toList()
}
