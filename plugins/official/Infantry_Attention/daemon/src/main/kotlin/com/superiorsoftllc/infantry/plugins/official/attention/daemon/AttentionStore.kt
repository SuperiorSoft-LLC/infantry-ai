// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.plugins.official.attention.daemon

import com.superiorsoftllc.infantry.daemon.api.attention.AttentionEntry
import com.superiorsoftllc.infantry.daemon.api.storage.PluginStorage
import java.util.UUID

/**
 * ATTN-2b: the durable operator-attention records, rows of the adopted `attention` table (module.json `daemon.storage.adopt`;
 * core still creates it). [raise] and [acknowledge] publish each committed change through [eventSink] (the plugin broadcasts it).
 */
class AttentionStore(
    private val db: PluginStorage,
    private val clock: () -> Long = System::currentTimeMillis,
    private val idFactory: () -> String = { UUID.randomUUID().toString() },
) {
    @Volatile
    var eventSink: (AttentionEntry) -> Unit = {}

    fun raise(
        kind: String,
        triggerId: String? = null,
        ticketId: String? = null,
        podId: String? = null,
        sessionId: String? = null,
        outcome: String,
        detail: String,
    ): AttentionEntry {
        require(kind in KINDS) { "unknown attention kind '$kind'" }
        require(outcome.isNotBlank()) { "attention outcome must not be blank" }
        require(detail.isNotBlank()) { "attention detail must not be blank" }
        val item = AttentionEntry(idFactory(), clock(), kind, triggerId, ticketId, podId, sessionId, outcome, detail)
        db.execute(
            "INSERT INTO attention(id, created_at, kind, trigger_id, ticket_id, pod_id, session_id, outcome, detail, acked_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
            listOf(item.id, item.at, item.kind, item.triggerId, item.ticketId, item.podId, item.sessionId, item.outcome, item.detail, null),
        )
        publish(item)
        return item
    }

    /** Oldest first, so a reconnect can replay attention in its original arrival order. */
    fun list(): List<AttentionEntry> =
        db.query("SELECT * FROM attention ORDER BY created_at ASC, id ASC").map(::row)

    fun openCount(): Long = (db.query("SELECT COUNT(*) AS n FROM attention WHERE acked_at IS NULL").firstOrNull()?.get("n") as? Number)?.toLong() ?: 0L

    /** Marks an item acknowledged once; repeated acknowledgements retain the first timestamp. Null = no such item. */
    @Synchronized
    fun acknowledge(id: String, at: Long = clock()): AttentionEntry? {
        val existing = get(id) ?: return null
        if (existing.ackedAt != null) return existing
        db.execute("UPDATE attention SET acked_at = COALESCE(acked_at, ?) WHERE id = ?", listOf(at, id))
        return get(id)?.also(::publish)
    }

    private fun get(id: String): AttentionEntry? =
        db.query("SELECT * FROM attention WHERE id = ?", listOf(id)).firstOrNull()?.let(::row)

    private fun row(r: Map<String, Any?>) = AttentionEntry(
        id = r["id"] as String,
        at = (r["created_at"] as Number).toLong(),
        kind = r["kind"] as String,
        triggerId = r["trigger_id"] as String?,
        ticketId = r["ticket_id"] as String?,
        podId = r["pod_id"] as String?,
        sessionId = r["session_id"] as String?,
        outcome = r["outcome"] as String,
        detail = r["detail"] as String,
        ackedAt = (r["acked_at"] as Number?)?.toLong(),
    )

    private fun publish(item: AttentionEntry) {
        runCatching { eventSink(item) }.onFailure { System.err.println("[infantry] attention push failed: ${it.message}") }
    }

    companion object {
        val KINDS = setOf(
            "trigger_tripped", "deadline", "stalled", "ceiling", "launch_blocked", "prerequisite_declined",
            "queue_cleared", "unreported", "session_failed", "session_resumed",
        )
    }
}
