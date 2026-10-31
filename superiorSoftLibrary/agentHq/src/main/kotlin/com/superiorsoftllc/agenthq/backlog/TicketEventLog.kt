package com.superiorsoftllc.agenthq.backlog

import com.superiorsoftllc.agenthq.daemon.api.board.TicketEvent
import com.superiorsoftllc.agenthq.daemon.api.board.TicketEvents
import com.superiorsoftllc.agenthq.db.AgentHQDatabase
import com.superiorsoftllc.agenthq.protocol.TicketStatus

/**
 * BOARD-API-2 (DP-4b, docs/DAEMON-BOARD-API.md section 3): the durable, ordered, at-least-once ticket event stream that replaced
 * `TicketBoard.transitionSink`.
 *
 * WRITE. [record] is called by the board INSIDE the transaction of the change, so a stored change always has its event and a rolled
 * back one has none. The row's `seq` is the global order.
 * DELIVER. [deliver] (after the commit, under the board's monitor, as the sink was) hands every consumer its events in seq order.
 * A consumer's cursor is stored after its listener returned, so a crash in between replays that one event after a restart: the
 * delivery is at-least-once and a consumer dedupes on [TicketEvent.seq]. A listener that throws keeps its cursor (the event is
 * retried on the next delivery); after [MAX_ATTEMPTS] failures the event is skipped and logged, so one bad event cannot wedge it.
 * SUBSCRIBE. A consumer replays what its cursor has not reached; a first-ever consumer starts at the head (no history).
 */
class TicketEventLog(
    private val db: AgentHQDatabase,
    private val clock: () -> Long = System::currentTimeMillis,
    private val log: (String) -> Unit = { System.err.println(it) },
) : TicketEvents, com.superiorsoftllc.agenthq.daemon.api.board.TicketEventAppender {
    private val q get() = db.ticket_eventsQueries

    private class Consumer(val name: String, val listener: (TicketEvent) -> Unit) {
        var failedSeq = 0L
        var failures = 0
    }

    private val consumers = ArrayList<Consumer>()
    private val lock = Any()

    /** Stores the events of one commit; call inside the commit's transaction. [before]/[after] map ticket id to column. */
    override fun record(before: Map<String, String>, after: Map<String, String>, changedIds: Collection<String>, deleted: Collection<String>) {
        val now = clock()
        for ((id, status) in after) {
            val was = before[id]
            when {
                was == null -> q.insertEvent(id, TicketEvent.CREATED, null, status, now)
                was != status -> q.insertEvent(id, kindOfMove(status), was, status, now)
                id in changedIds -> q.insertEvent(id, TicketEvent.UPDATED, was, status, now)
            }
        }
        for (id in deleted) q.insertEvent(id, TicketEvent.DELETED, before[id], null, now)
    }

    private fun kindOfMove(to: String) = when (to) {
        TicketStatus.IN_PROGRESS -> TicketEvent.STARTED
        TicketStatus.DONE -> TicketEvent.DONE
        else -> TicketEvent.MOVED
    }

    override fun subscribe(consumer: String, listener: (TicketEvent) -> Unit): AutoCloseable {
        val c = Consumer(consumer, listener)
        synchronized(lock) {
            check(consumers.none { it.name == consumer }) { "ticket event consumer '$consumer' is already subscribed" }
            if (q.cursorOf(consumer).executeAsOneOrNull() == null) q.setCursor(consumer, q.headSeq().executeAsOne())
            consumers += c
        }
        deliver()
        return AutoCloseable { synchronized(lock) { consumers -= c } }
    }

    /** The newest seq stored (0 = none). */
    fun head(): Long = q.headSeq().executeAsOne()

    /** Hands every consumer the events it has not seen, in order. Safe to call any time; calls are serialised. */
    override fun deliver() = synchronized(lock) {
        for (c in consumers.toList()) deliverTo(c)
        val head = q.headSeq().executeAsOne()
        if (head > KEEP) q.trimBefore(head - KEEP)
    }

    private fun deliverTo(c: Consumer) {
        while (true) {
            val after = q.cursorOf(c.name).executeAsOneOrNull() ?: 0L
            val batch = q.eventsAfter(after, BATCH.toLong()).executeAsList()
            if (batch.isEmpty()) return
            for (row in batch) {
                val event = TicketEvent(row.seq, row.ticket_id, row.kind, row.from_status, row.to_status, row.at)
                val ok = runCatching { c.listener(event) }.onFailure { log("[agentHq] ticket event ${row.seq} to '${c.name}' failed: $it") }.isSuccess
                if (!ok) {
                    if (c.failedSeq != row.seq) { c.failedSeq = row.seq; c.failures = 0 }
                    if (++c.failures < MAX_ATTEMPTS) return
                    log("[agentHq] ticket event ${row.seq} skipped for '${c.name}' after $MAX_ATTEMPTS failures")
                }
                q.setCursor(c.name, row.seq)
            }
        }
    }

    companion object {
        const val MAX_ATTEMPTS = 3
        private const val BATCH = 200
        private const val KEEP = 10_000L
    }
}
