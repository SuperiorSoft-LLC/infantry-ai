package com.superiorsoftllc.agenthq.attention

import com.superiorsoftllc.agenthq.persistence.AttentionRepository
import java.util.UUID

/** Creates and acknowledges durable attention rows, publishing each committed change to listeners. */
class AttentionStore(
    private val repository: AttentionRepository,
    private val clock: () -> Long = System::currentTimeMillis,
    private val idFactory: () -> String = { UUID.randomUUID().toString() },
) {
    @Volatile
    var eventSink: (AttentionItem) -> Unit = {}

    fun raise(
        kind: AttentionKind,
        triggerId: String? = null,
        ticketId: String? = null,
        podId: String? = null,
        sessionId: String? = null,
        outcome: String,
        detail: String,
    ): AttentionItem {
        require(outcome.isNotBlank()) { "attention outcome must not be blank" }
        require(detail.isNotBlank()) { "attention detail must not be blank" }
        val item = AttentionItem(
            id = idFactory(),
            at = clock(),
            kind = kind,
            triggerId = triggerId,
            ticketId = ticketId,
            podId = podId,
            sessionId = sessionId,
            outcome = outcome,
            detail = detail,
        )
        repository.insert(item)
        publish(item)
        return item
    }

    /** Oldest first, so a reconnect can replay attention in its original arrival order. */
    fun list(): List<AttentionItem> = repository.all()

    fun openCount(): Long = repository.openCount()

    /** Marks an item acknowledged once; repeated acknowledgements retain the first timestamp. */
    @Synchronized
    fun acknowledge(id: String, at: Long = clock()): AttentionItem? {
        val existing = repository.get(id) ?: return null
        if (existing.ackedAt != null) return existing
        return repository.acknowledge(id, at)?.also(::publish)
    }

    private fun publish(item: AttentionItem) {
        runCatching { eventSink(item) }
            .onFailure { System.err.println("[agentHq] attention push failed: ${it.message}") }
    }
}
