package com.superiorsoftllc.agenthq.triggers

import com.superiorsoftllc.agenthq.daemon.api.attention.AttentionKinds
import com.superiorsoftllc.agenthq.daemon.api.attention.Attention

enum class TriggerOutcome { SUCCEEDED, FAILED, CANCELLED, INTERRUPTED, KILLED, ORPHANED, DEADLINE, STALLED, START_REFUSED }
enum class TriggerState { ARMED, RUNNING, TRIPPED, DISABLED }
enum class TriggerFireResult { STARTED, FOLDED, FOLLOWUP_FOLDED, REFUSED_CEILING, SUPPRESSED_TRIPPED, SUPPRESSED_DISABLED }

data class TriggerTrip(val triggerId: String, val attentionKind: String, val outcome: String, val detail: String) {
    /** Persists and publishes this trip through P9.4a attention. */
    fun reportTo(attention: Attention) {
        attention.raise(kind = attentionKind, triggerId = triggerId, outcome = outcome, detail = detail)
    }
}

/** Hourly run ceiling and explicit re-arm policy layered around P9.5a coalescing. */
class TriggerGuard(
    private val clock: TriggerClock = TriggerClock(System::currentTimeMillis),
    private val launch: (TriggerRun) -> Unit,
    private val onTrip: (TriggerTrip) -> Unit = {},
    private val coalesceMs: Long = TriggerCoalescer.DEFAULT_COALESCE_MS,
    private val ceilingRuns: Int = DEFAULT_CEILING_RUNS,
    private val ceilingWindowMs: Long = DEFAULT_CEILING_WINDOW_MS,
) {
    private data class State(
        var status: TriggerState = TriggerState.ARMED,
        val starts: ArrayDeque<Long> = ArrayDeque(),
        val runs: MutableSet<String> = mutableSetOf(),
    )

    private val states = mutableMapOf<String, State>()
    private val tripRecords = mutableListOf<TriggerTrip>()
    private val eventRecords = mutableListOf<TriggerEventRecord>()
    private var coalescer = newCoalescer()

    private fun newCoalescer() = TriggerCoalescer(clock, { run ->
        val state = states.getOrPut(run.triggerId, ::State)
        state.starts.removeExpired(clock.nowMillis())
        if (state.status == TriggerState.TRIPPED || state.status == TriggerState.DISABLED) {
            run.events.eventIds().forEach { addRecord(it, run.triggerId, TriggerEventDisposition.SUPPRESSED_TRIPPED) }
        } else if (state.starts.size >= ceilingRuns) {
            trip(run.triggerId, state, AttentionKinds.CEILING, "REFUSED_CEILING", "Hourly trigger run ceiling of $ceilingRuns starts was reached.")
            run.events.eventIds().forEach { addRecord(it, run.triggerId, TriggerEventDisposition.REFUSED_CEILING) }
        } else {
            state.starts.addLast(clock.nowMillis())
            state.status = TriggerState.RUNNING
            state.runs += run.runId
            launch(run)
        }
    }, coalesceMs)

    val dispositions: List<TriggerEventRecord>
        @Synchronized get() = (coalescer.dispositions + eventRecords).distinctBy { it.eventId to it.triggerId }
    val inFlightCount: Int get() = coalescer.inFlightCount

    @Synchronized
    fun fire(eventId: String, triggerId: String = TriggerCoalescer.DEFAULT_TRIGGER_ID): TriggerFireResult {
        val state = states.getOrPut(triggerId, ::State)
        val now = clock.nowMillis()
        if (state.status == TriggerState.TRIPPED || state.status == TriggerState.DISABLED) {
            addRecord(eventId, triggerId, TriggerEventDisposition.SUPPRESSED_TRIPPED, now)
            return if (state.status == TriggerState.TRIPPED) TriggerFireResult.SUPPRESSED_TRIPPED else TriggerFireResult.SUPPRESSED_DISABLED
        }
        state.starts.removeExpired(now)
        if (state.starts.size >= ceilingRuns) {
            trip(triggerId, state, AttentionKinds.CEILING, "REFUSED_CEILING", "Hourly trigger run ceiling of $ceilingRuns starts was reached.")
            addRecord(eventId, triggerId, TriggerEventDisposition.REFUSED_CEILING, now)
            return TriggerFireResult.REFUSED_CEILING
        }
        val disposition = coalescer.fire(eventId, triggerId)
        if (coalesceMs == 0L && coalescer.inFlightCount > 0) state.status = TriggerState.RUNNING
        return when (disposition) {
            TriggerEventDisposition.STARTED -> TriggerFireResult.STARTED
            TriggerEventDisposition.FOLDED -> TriggerFireResult.FOLDED
            TriggerEventDisposition.FOLLOWUP_FOLDED -> TriggerFireResult.FOLLOWUP_FOLDED
            TriggerEventDisposition.DROPPED_AFTER_FAILURE -> TriggerFireResult.SUPPRESSED_TRIPPED
            else -> error("Unexpected coalescer disposition")
        }
    }

    @Synchronized fun pump(): Boolean = coalescer.pump()

    @Synchronized
    fun complete(runId: String, outcome: TriggerOutcome): Boolean {
        val (triggerId, state) = states.entries.firstOrNull { runId in it.value.runs } ?: return false
        if (!coalescer.complete(runId, outcome == TriggerOutcome.SUCCEEDED)) return false
        state.runs.remove(runId)
        if (outcome == TriggerOutcome.SUCCEEDED) {
            state.status = if (state.runs.isEmpty()) TriggerState.ARMED else TriggerState.RUNNING
        } else {
            eventRecords += coalescer.dispositions.filter { it.triggerId == triggerId && it.disposition == TriggerEventDisposition.DROPPED_AFTER_FAILURE }
            trip(triggerId, state, AttentionKinds.TRIGGER_TRIPPED, outcome.name, "Trigger run $runId ended with ${outcome.name}; automatic retries are disabled.")
        }
        return true
    }

    /**
     * TRIGGER-NUDGE: run [runId] started nothing (a prompt that was only MAILED), so it gives back the start it took
     * against the hourly ceiling. The ceiling bounds session starts; mail is bounded by one pending per trigger.
     * Call before [complete]. False when the run is unknown.
     */
    @Synchronized
    fun refundStart(runId: String): Boolean {
        val state = states.values.firstOrNull { runId in it.runs } ?: return false
        if (state.starts.isEmpty()) return false
        state.starts.removeLast()
        return true
    }

    @Synchronized
    fun arm(triggerId: String): Boolean {
        val state = states[triggerId] ?: return false
        if (state.status == TriggerState.DISABLED) return false
        if (state.status != TriggerState.TRIPPED) return false
        coalescer = newCoalescer()
        state.status = TriggerState.ARMED
        state.runs.clear()
        return true
    }

    @Synchronized fun state(triggerId: String = TriggerCoalescer.DEFAULT_TRIGGER_ID): TriggerState = states[triggerId]?.status ?: TriggerState.ARMED
    @Synchronized fun stateOrNull(triggerId: String): TriggerState? = states[triggerId]?.status
    @Synchronized fun trips(): List<TriggerTrip> = tripRecords.toList()

    private fun trip(triggerId: String, state: State, kind: String, outcome: String, detail: String) {
        if (state.status == TriggerState.TRIPPED) return
        state.status = TriggerState.TRIPPED
        val trip = TriggerTrip(triggerId, kind, outcome, detail)
        tripRecords += trip
        onTrip(trip)
    }

    private fun addRecord(eventId: String, triggerId: String, disposition: TriggerEventDisposition, at: Long = clock.nowMillis()) {
        eventRecords += TriggerEventRecord(eventId, triggerId, at, disposition)
    }

    private fun ArrayDeque<Long>.removeExpired(now: Long) {
        while (isNotEmpty() && first() <= now - ceilingWindowMs) removeFirst()
    }

    private fun TriggerEventSummary.eventIds(): List<String> = if (count == 1 || firstEventId == lastEventId) listOf(firstEventId) else listOf(firstEventId, lastEventId)

    companion object {
        const val DEFAULT_CEILING_RUNS = 6
        const val DEFAULT_CEILING_WINDOW_MS = 60 * 60 * 1000L
    }
}
