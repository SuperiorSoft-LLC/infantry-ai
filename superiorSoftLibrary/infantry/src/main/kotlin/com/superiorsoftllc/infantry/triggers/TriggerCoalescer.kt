package com.superiorsoftllc.infantry.triggers

/** Clock seam keeps coalescing behavior deterministic and independent of wall-clock time. */
fun interface TriggerClock {
    fun nowMillis(): Long
}

data class TriggerEventSummary(
    val count: Int,
    val firstEventId: String,
    val lastEventId: String,
    val firstAt: Long,
    val lastAt: Long,
)

data class TriggerRun(val runId: String, val triggerId: String, val events: TriggerEventSummary)

/**
 * In-memory, per-trigger event coalescing. A scheduler calls [pump] to release fixed windows;
 * completion is reported by run id. It deliberately never queues more than one follow-up.
 */
class TriggerCoalescer(
    private val clock: TriggerClock = TriggerClock(System::currentTimeMillis),
    private val launch: (TriggerRun) -> Unit,
    private val coalesceMs: Long = DEFAULT_COALESCE_MS,
) {
    private data class Pending(val count: Int, val firstId: String, val lastId: String, val firstAt: Long, val lastAt: Long) {
        fun summary() = TriggerEventSummary(count, firstId, lastId, firstAt, lastAt)
    }

    private data class TriggerState(
        var windowStartedAt: Long? = null,
        var pending: Pending? = null,
        var inFlight: String? = null,
        var followup: Pending? = null,
        var failed: Boolean = false,
    )

    private val states = mutableMapOf<String, TriggerState>()
    private val eventRecords = mutableListOf<TriggerEventRecord>()

    val dispositions: List<TriggerEventRecord>
        @Synchronized get() = eventRecords.toList()

    val inFlightCount: Int
        @Synchronized get() = states.values.count { it.inFlight != null }

    @Synchronized
    fun fire(eventId: String, triggerId: String = DEFAULT_TRIGGER_ID): TriggerEventDisposition {
        val now = clock.nowMillis()
        val state = states.getOrPut(triggerId, ::TriggerState)
        val disposition = when {
            state.failed -> TriggerEventDisposition.DROPPED_AFTER_FAILURE
            state.inFlight != null -> {
                state.followup = state.followup?.fold(eventId, now) ?: Pending(1, eventId, eventId, now, now)
                TriggerEventDisposition.FOLLOWUP_FOLDED
            }
            else -> {
                if (state.pending == null) {
                    state.pending = Pending(1, eventId, eventId, now, now)
                    state.windowStartedAt = now
                } else {
                    state.pending = state.pending?.fold(eventId, now) ?: Pending(1, eventId, eventId, now, now)
                }
                if (coalesceMs <= 0) {
                    startPending(triggerId, state)
                    TriggerEventDisposition.STARTED
                } else {
                    if (state.pending!!.count == 1) TriggerEventDisposition.STARTED else TriggerEventDisposition.FOLDED
                }
            }
        }
        eventRecords += TriggerEventRecord(eventId, triggerId, now, disposition)
        return disposition
    }

    /** Starts every pending trigger whose fixed coalesce window has elapsed. */
    @Synchronized
    fun pump(): Boolean {
        var started = false
        val now = clock.nowMillis()
        states.forEach { (triggerId, state) ->
            val openedAt = state.windowStartedAt
            if (!state.failed && state.inFlight == null && state.pending != null && openedAt != null && now - openedAt >= coalesceMs) {
                startPending(triggerId, state)
                started = true
            }
        }
        return started
    }

    /** Returns false for unknown or already completed runs. Failed runs drop their follow-up. */
    @Synchronized
    fun complete(runId: String, succeeded: Boolean): Boolean {
        val entry = states.entries.firstOrNull { it.value.inFlight == runId } ?: return false
        val (triggerId, state) = entry
        state.inFlight = null
        if (!succeeded) {
            state.failed = true
            // The original event records become visible as dropped, preserving one disposition per event.
            for (index in eventRecords.indices) {
                val record = eventRecords[index]
                if (record.triggerId == triggerId && record.disposition == TriggerEventDisposition.FOLLOWUP_FOLDED) {
                    eventRecords[index] = record.copy(disposition = TriggerEventDisposition.DROPPED_AFTER_FAILURE)
                }
            }
            state.followup = null
            return true
        }
        state.followup?.let { followup ->
            state.pending = followup
            state.followup = null
            state.windowStartedAt = clock.nowMillis()
        }
        return true
    }

    private fun startPending(triggerId: String, state: TriggerState) {
        val pending = state.pending ?: return
        val runId = "trigger-run-${RUN_SEQUENCE.incrementAndGet()}"
        state.pending = null
        state.windowStartedAt = null
        state.inFlight = runId
        launch(TriggerRun(runId, triggerId, pending.summary()))
    }

    private fun Pending.fold(eventId: String, at: Long) = Pending(count + 1, firstId, eventId, firstAt, at)

    companion object {
        /**
         * BUG-TriggerRunIds: ONE sequence for every coalescer in the process (the daemon). Each trigger owns its
         * own guard and coalescer, a guard builds a fresh coalescer on arm(), and TriggerService keys its in-flight
         * runs by run id across ALL triggers: a per-instance counter handed two triggers the same `trigger-run-1`,
         * and one trigger's session ending then settled the other's run.
         */
        private val RUN_SEQUENCE = java.util.concurrent.atomic.AtomicLong()

        const val DEFAULT_COALESCE_MS = 120_000L
        const val DEFAULT_TRIGGER_ID = "manual"
    }
}
