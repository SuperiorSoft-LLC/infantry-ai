// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.backlog

import com.superiorsoftllc.infantry.daemon.api.board.TicketEvent
import com.superiorsoftllc.infantry.daemon.api.board.TicketEvents
import com.superiorsoftllc.infantry.db.InfantryDatabase
import com.superiorsoftllc.infantry.protocol.TicketStatus
import java.util.concurrent.Executor
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.atomic.AtomicBoolean

/**
 * BOARD-API-2 (DP-4b, docs/DAEMON-BOARD-API.md section 3): the durable, ordered, at-least-once ticket event stream that replaced
 * `TicketBoard.transitionSink`.
 *
 * WRITE. [record] is called by the board INSIDE the transaction of the change, so a stored change always has its event and a rolled
 * back one has none. The row's `seq` is the global order.
 * DELIVER. [deliver] (called after the commit) hands every consumer its events in seq order, on [executor]. BOARD-REENTRY-1: the
 * daemon passes its own single delivery thread, so a move answers once it committed and a consumer (a lane hook that reads the
 * ticket back through a plugin) runs after the board let go, never inside the move's frame or monitor. Calls coalesce: a [deliver]
 * while a drain runs (a consumer's own move included) is picked up by that drain. The default runs inline (tests, synchronous).
 * A consumer's cursor is stored after its listener returned, so a crash in between replays that one event after a restart: the
 * delivery is at-least-once and a consumer dedupes on [TicketEvent.seq]. A listener that throws keeps its cursor (the event is
 * retried on the next delivery); after [MAX_ATTEMPTS] failures the event is skipped and logged, so one bad event cannot wedge it.
 * SUBSCRIBE. A consumer replays what its cursor has not reached; a first-ever consumer starts at the head (no history).
 */
class TicketEventLog(
    private val db: InfantryDatabase,
    private val clock: () -> Long = System::currentTimeMillis,
    private val log: (String) -> Unit = { System.err.println(it) },
    /** BOARD-REENTRY-1: where delivery runs; one thread keeps the seq order. Inline by default. */
    private val executor: Executor = Executor { it.run() },
) : TicketEvents, com.superiorsoftllc.infantry.daemon.api.board.TicketEventAppender {
    private val q get() = db.ticket_eventsQueries

    private class Consumer(val name: String, val listener: (TicketEvent) -> Unit) {
        var failedSeq = 0L
        var failures = 0
    }

    private val consumers = ArrayList<Consumer>()
    /** Guards [consumers]. */
    private val lock = Any()
    /** Serialises a drain (cursor reads and writes, listener calls). */
    private val drainLock = Any()
    private val pending = AtomicBoolean()
    private val scheduled = AtomicBoolean()

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

    /** Asks for every consumer to get the events it has not seen, in order, on [executor]. Safe to call any time; returns at once off-inline. */
    override fun deliver() {
        pending.set(true)
        if (!scheduled.compareAndSet(false, true)) return
        try {
            executor.execute(::drain)
        } catch (e: RejectedExecutionException) {
            scheduled.set(false)
            log("[infantry] ticket event delivery refused (shutting down?): $e")
        }
    }

    private fun drain() {
        while (true) {
            while (pending.getAndSet(false)) {
                runCatching { synchronized(drainLock) { deliverAll() } }.onFailure { log("[infantry] ticket event delivery failed: $it") }
            }
            scheduled.set(false)
            // A deliver() that landed between the last check and the reset found the drain still scheduled: take it here.
            if (!pending.get() || !scheduled.compareAndSet(false, true)) return
        }
    }

    private fun deliverAll() {
        for (c in synchronized(lock) { consumers.toList() }) deliverTo(c)
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
                val ok = runCatching { c.listener(event) }.onFailure { log("[infantry] ticket event ${row.seq} to '${c.name}' failed: $it") }.isSuccess
                if (!ok) {
                    if (c.failedSeq != row.seq) { c.failedSeq = row.seq; c.failures = 0 }
                    if (++c.failures < MAX_ATTEMPTS) return
                    log("[infantry] ticket event ${row.seq} skipped for '${c.name}' after $MAX_ATTEMPTS failures")
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
