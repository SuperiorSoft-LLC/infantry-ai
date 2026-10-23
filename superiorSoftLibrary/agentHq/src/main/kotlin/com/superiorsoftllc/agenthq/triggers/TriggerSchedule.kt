package com.superiorsoftllc.agenthq.triggers

enum class ScheduleFrequency { EVERY_MINUTES, HOURLY, DAILY, WEEKLY, MONTHLY }

enum class TriggerEventSource { MANUAL, SCHEDULE, SHIP, MANUAL_REPLAY }

enum class TriggerEventDisposition {
    STARTED,
    FOLDED,
    FOLLOWUP_FOLDED,
    DROPPED_AFTER_FAILURE,
    REFUSED_CEILING,
    SUPPRESSED_TRIPPED,
    SUPPRESSED_DISABLED,
    DUPLICATE,
    MISSED,
    NOTHING_TO_START,
    REFUSED_LAUNCH_GATE,
    WONT_FIX,
}

/** One in-memory event-history row; persistence adapters can store these without conflating runs. */
data class TriggerEventRecord(
    val eventId: String,
    val triggerId: String,
    val receivedAt: Long,
    val disposition: TriggerEventDisposition,
    val source: TriggerEventSource = TriggerEventSource.MANUAL,
    val deliveryId: String? = null,
    val digest: String? = null,
)

/** Schedule slot calculation and downtime catch-up independent of a daemon's timer implementation. */
class TriggerSchedule(
    val triggerId: String,
    val frequency: ScheduleFrequency,
    private val everyMinutes: Int? = null,
    private val catchUp: Boolean = true,
    private val coalesceMs: Long = TriggerCoalescer.DEFAULT_COALESCE_MS,
    private val clock: TriggerClock = TriggerClock(System::currentTimeMillis),
    private val launch: (TriggerRun) -> Unit,
    private val recentThresholdMs: Long = DEFAULT_RECENT_THRESHOLD_MS,
) {
    private val seenSlots = mutableSetOf<Long>()
    private val records = mutableListOf<TriggerEventRecord>()
    private val coalescer = TriggerCoalescer(clock, launch, coalesceMs)
    private var activeRunId: String? = null
    private var followupRunId: String? = null
    private var pendingFollowup: TriggerRun? = null
    private var coalescedCount = 0

    val dispositions: List<TriggerEventRecord>
        @Synchronized get() = (records + coalescer.dispositions.map { it.copy(source = TriggerEventSource.SCHEDULE) })
            .distinctBy { it.eventId to it.triggerId }

    /** Enumerates elapsed schedule slots; catch-up folds them into one event summary. */
    @Synchronized
    fun resume(lastObservedAt: Long, now: Long = clock.nowMillis()) {
        val slots = slotsBetween(lastObservedAt, now).filter { it !in seenSlots }
        if (slots.isEmpty()) return
        val eligible = if (catchUp) slots else slots.filter { now - it < recentThresholdMs }
        val missed = slots - eligible.toSet()
        missed.forEach { slot -> record("slot-$slot", slot, TriggerEventDisposition.MISSED) }
        seenSlots.addAll(slots)
        if (eligible.isNotEmpty()) fireSlots(eligible)
    }

    @Synchronized
    fun fireSlot(eventId: String, slotAt: Long = clock.nowMillis()): TriggerEventDisposition {
        if (slotAt in seenSlots) {
            record(eventId, slotAt, TriggerEventDisposition.DUPLICATE)
            return TriggerEventDisposition.DUPLICATE
        }
        seenSlots.add(slotAt)
        return fireEvent(eventId, slotAt)
    }

    @Synchronized fun pump(): Boolean = coalescer.pump()

    @Synchronized
    fun complete(runId: String, outcome: TriggerOutcome): Boolean {
        val completed = runId == activeRunId || runId == followupRunId
        if (!completed) return false
        if (runId == activeRunId) activeRunId = null else followupRunId = null
        if (outcome != TriggerOutcome.SUCCEEDED) {
            records.indices.forEach { index ->
                if (records[index].disposition == TriggerEventDisposition.FOLLOWUP_FOLDED) {
                    records[index] = records[index].copy(disposition = TriggerEventDisposition.DROPPED_AFTER_FAILURE)
                }
            }
            coalescer.dispositions.filter { it.disposition == TriggerEventDisposition.DROPPED_AFTER_FAILURE }
                .forEach { record(it.eventId, it.receivedAt, TriggerEventDisposition.DROPPED_AFTER_FAILURE) }
            pendingFollowup = null
            followupRunId = null
            return true
        }
        if (pendingFollowup != null) {
            val next = pendingFollowup!!
            pendingFollowup = null
            followupRunId = next.runId
            activeRunId = next.runId
            launch(next)
            records.indices.forEach { index ->
                if (records[index].disposition == TriggerEventDisposition.FOLLOWUP_FOLDED) {
                    records[index] = records[index].copy(disposition = TriggerEventDisposition.STARTED)
                }
            }
        }
        return true
    }

    private fun fireSlots(slots: List<Long>) {
        coalescedCount = slots.size
        val start = slots.first()
        val summary = TriggerEventSummary(slots.size, "slot-$start", "slot-${slots.last()}", start, slots.last())
        val eventId = "catch-up-${slots.size}:$start:${slots.last()}"
        val run = TriggerRun("${triggerId}-catch-up-${start}-${slots.last()}", triggerId, summary)
        if (activeRunId != null) {
            pendingFollowup = run
            followupRunId = run.runId
            record(eventId, slots.last(), TriggerEventDisposition.FOLLOWUP_FOLDED)
        } else {
            activeRunId = run.runId
            launch(run)
            record(eventId, slots.last(), TriggerEventDisposition.STARTED)
        }
    }

    private fun fireEvent(eventId: String, at: Long): TriggerEventDisposition {
        val run = TriggerRun(eventId, triggerId, TriggerEventSummary(1, eventId, eventId, at, at))
        if (activeRunId != null) {
            val prior = pendingFollowup
            val merged = if (prior == null) run else run.copy(
                runId = "${triggerId}-followup-${prior.events.firstAt}-${at}",
                events = prior.events.copy(count = prior.events.count + 1, lastEventId = eventId, lastAt = at),
            )
            pendingFollowup = merged
            followupRunId = merged.runId
            return TriggerEventDisposition.FOLLOWUP_FOLDED.also { record(eventId, at, it) }
        }
        val actual = if (coalescedCount > 1) run.copy(events = TriggerEventSummary(
            coalescedCount, "slot-${at - (coalescedCount - 1) * 600_000L}", eventId,
            at - (coalescedCount - 1) * 600_000L, at,
        )) else run
        coalescedCount = 0
        activeRunId = actual.runId
        launch(actual)
        return TriggerEventDisposition.STARTED.also { record(eventId, at, it) }
    }

    private fun slotsBetween(lastObservedAt: Long, now: Long): List<Long> {
        val step = when (frequency) {
            ScheduleFrequency.EVERY_MINUTES -> (everyMinutes ?: error("everyMinutes is required")) * MINUTE_MS
            ScheduleFrequency.HOURLY -> HOUR_MS
            ScheduleFrequency.DAILY -> DAY_MS
            ScheduleFrequency.WEEKLY -> 7 * DAY_MS
            ScheduleFrequency.MONTHLY -> 30 * DAY_MS
        }
        require(step > 0) { "schedule interval must be positive" }
        var next = (Math.floorDiv(lastObservedAt, step) + 1) * step
        val slots = mutableListOf<Long>()
        while (next <= now) {
            slots += next
            next += step
        }
        return slots
    }

    private fun record(id: String, at: Long, disposition: TriggerEventDisposition) {
        records += TriggerEventRecord(id, triggerId, at, disposition, TriggerEventSource.SCHEDULE)
    }

    companion object {
        const val DEFAULT_RECENT_THRESHOLD_MS = 600_000L
        private const val MINUTE_MS = 60_000L
        private const val HOUR_MS = 60 * MINUTE_MS
        private const val DAY_MS = 24 * HOUR_MS
    }
}
