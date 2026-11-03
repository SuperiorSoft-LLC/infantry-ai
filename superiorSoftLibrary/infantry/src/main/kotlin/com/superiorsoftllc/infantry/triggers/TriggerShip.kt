package com.superiorsoftllc.infantry.triggers

/** Ship trigger ledger: each ticket id is eligible once, independent of reopen/close cycles. */
class TriggerShip(
    val triggerId: String,
    private val coalesceMs: Long = TriggerCoalescer.DEFAULT_COALESCE_MS,
    private val clock: TriggerClock = TriggerClock(System::currentTimeMillis),
    private val launch: (TriggerRun) -> Unit,
) {
    private val shippedTicketIds = mutableSetOf<String>()
    private val records = mutableListOf<TriggerEventRecord>()
    private var activeRun: TriggerRun? = null
    private var followupRun: TriggerRun? = null
    private var windowStartedAt: Long? = null
    private var pendingWindow: TriggerRun? = null
    private var lastShippedTicketId: String? = null

    val dispositions: List<TriggerEventRecord>
        @Synchronized get() = records.toList()

    @Synchronized
    fun ticketDone(ticketId: String, wontFix: Boolean = false): TriggerEventDisposition {
        lastShippedTicketId = ticketId
        if (wontFix) return record(ticketId, TriggerEventDisposition.WONT_FIX)
        if (!shippedTicketIds.add(ticketId)) return record(ticketId, TriggerEventDisposition.DUPLICATE)
        val eventId = "ship:$ticketId:${records.size}"
        val now = clock.nowMillis()
        val run = TriggerRun(eventId, triggerId, TriggerEventSummary(1, ticketId, ticketId, now, now))
        if (activeRun != null) {
            followupRun = merge(followupRun, run)
            return TriggerEventDisposition.FOLLOWUP_FOLDED.also { record(ticketId, it) }
        }
        val disposition = run(run)
        return disposition.also { record(ticketId, it) }
    }

    /** Manual replay intentionally bypasses the once-per-ticket guard for the last shipped item. */
    @Synchronized
    fun replayLast(): TriggerEventDisposition {
        val ticketId = lastShippedTicketId ?: return record("replay-empty", TriggerEventDisposition.NOTHING_TO_START, TriggerEventSource.MANUAL_REPLAY)
        if (coalesceMs == 0L && activeRun != null) activeRun = null
        val now = clock.nowMillis()
        val run = TriggerRun("replay:$ticketId:${records.size}", triggerId, TriggerEventSummary(1, ticketId, ticketId, now, now))
        if (activeRun != null) {
            followupRun = merge(followupRun, run)
            return TriggerEventDisposition.FOLLOWUP_FOLDED.also { record(ticketId, it, TriggerEventSource.MANUAL_REPLAY) }
        }
        val disposition = run(run)
        return disposition.also { record(ticketId, it, TriggerEventSource.MANUAL_REPLAY) }
    }

    @Synchronized
    fun complete(runId: String, outcome: TriggerOutcome): Boolean {
        if (activeRun?.runId != runId && pendingWindow?.runId != runId) return false
        val completed = true
        activeRun = null
        pendingWindow = null
        val followup = followupRun
        followupRun = null
        if (outcome != TriggerOutcome.SUCCEEDED) {
            records.indices.forEach { index ->
                if (records[index].disposition == TriggerEventDisposition.FOLLOWUP_FOLDED) {
                    records[index] = records[index].copy(disposition = TriggerEventDisposition.DROPPED_AFTER_FAILURE)
                }
            }
        }
        if (outcome == TriggerOutcome.SUCCEEDED && followup != null) {
            start(followup)
            records.indices.forEach { index ->
                if (records[index].disposition == TriggerEventDisposition.FOLLOWUP_FOLDED) {
                    records[index] = records[index].copy(disposition = TriggerEventDisposition.STARTED)
                }
            }
        }
        if (outcome != TriggerOutcome.SUCCEEDED) pendingWindow = null
        return true
    }
    @Synchronized fun pump(): Boolean {
        if (activeRun != null && coalesceMs == 0L) activeRun = null
        followupRun?.let { pending ->
            if (activeRun != null) return false
            followupRun = null
            start(pending)
            records.indices.forEach { index ->
                if (records[index].disposition == TriggerEventDisposition.FOLLOWUP_FOLDED) {
                    records[index] = records[index].copy(disposition = TriggerEventDisposition.STARTED)
                }
            }
            return true
        }
        if (activeRun != null) return false
        val pending = pendingWindow ?: return false
        val openedAt = windowStartedAt ?: return false
        if (clock.nowMillis() - openedAt < coalesceMs) return false
        pendingWindow = null
        windowStartedAt = null
        start(pending)
        records.indices.forEach { index ->
            if (records[index].disposition == TriggerEventDisposition.FOLDED) {
                records[index] = records[index].copy(disposition = TriggerEventDisposition.STARTED)
            }
        }
        return true
    }

    private fun start(run: TriggerRun) {
        activeRun = run
        launch(run)
    }

    private fun run(run: TriggerRun): TriggerEventDisposition {
        val active = activeRun
        if (active == null) {
            if (coalesceMs == 0L) {
                start(run)
                return TriggerEventDisposition.STARTED
            }
            val pending = pendingWindow
            pendingWindow = if (pending == null) run else pending.copy(events = pending.events.copy(
                count = pending.events.count + run.events.count,
                lastEventId = run.events.lastEventId,
                lastAt = run.events.lastAt,
            ))
            if (pending == null) windowStartedAt = clock.nowMillis()
            return if (pending == null) TriggerEventDisposition.STARTED else TriggerEventDisposition.FOLDED
        }
        followupRun = merge(followupRun, run)
        return TriggerEventDisposition.FOLLOWUP_FOLDED
    }

    private fun merge(existing: TriggerRun?, next: TriggerRun): TriggerRun = if (existing == null) next else existing.copy(
        runId = "${triggerId}-followup-${existing.events.firstAt}-${next.events.lastAt}",
        events = existing.events.copy(count = existing.events.count + next.events.count, lastEventId = next.events.lastEventId, lastAt = next.events.lastAt),
    )
    @Synchronized fun hasShipped(ticketId: String): Boolean = ticketId in shippedTicketIds

    private fun record(
        ticketId: String,
        disposition: TriggerEventDisposition,
        source: TriggerEventSource = TriggerEventSource.SHIP,
    ): TriggerEventDisposition {
        records += TriggerEventRecord(ticketId, triggerId, clock.nowMillis(), disposition, source)
        return disposition
    }
}
