package com.superiorsoftllc.infantry.protocol

import com.superiorsoftllc.infantry.session.SessionState
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.util.concurrent.ConcurrentHashMap

/**
 * Contract 1.95.0 (1c24fdc4): what a session's run is doing right now, in ONE harness-neutral shape. A UI reads only
 * this to draw its working indicator; it never needs to know which CLI is running.
 *
 * The phases, as they appear on the wire:
 *  - `idle`: no turn in flight.
 *  - `thinking`: a turn is open and no tool call is in flight. Derived from the TURN, never from a reasoning event:
 *    a harness that emits no thinking at all still reads `thinking` between its tool calls.
 *  - `tool_running`: at least one tool call has started and not ended; [RunActivity.label] names the newest one.
 *  - `compacting`: the harness is compacting its context (the turn stays in flight; busy, not interruptible-by-default).
 *  - `waiting_for_input`: the session is [SessionState.WAITING_INPUT].
 *  - `finished` / `error`: the turn just ended (cleanly or killed / with a failure). Followed by `idle` once the
 *    session's own state leaves flight.
 */
@Serializable
enum class RunPhase {
    @SerialName("idle") IDLE,
    @SerialName("thinking") THINKING,
    @SerialName("tool_running") TOOL_RUNNING,
    @SerialName("compacting") COMPACTING,
    @SerialName("waiting_for_input") WAITING_FOR_INPUT,
    @SerialName("finished") FINISHED,
    @SerialName("error") ERROR,
}

/**
 * Contract 1.95.0: the run activity carried on a `status` frame's `runActivity`.
 *
 * [label] is the tool call ("Bash: ls -la", the same text as `tool` + `activity`) while [state] is `tool_running`,
 * else absent. [startedAt] is when [state] began (epoch ms), [turnStartedAt] when the turn opened, [lastEventAt] the
 * newest event the daemon had seen from the run when this frame was produced.
 */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class RunActivity(
    val state: RunPhase,
    @EncodeDefault(EncodeDefault.Mode.NEVER) val label: String? = null,
    val startedAt: Long,
    val turnStartedAt: Long,
    val lastEventAt: Long,
)

/**
 * The daemon's per-session [RunActivity], fed by three things every harness has: the turn's start and end (the
 * daemon's own), the session state, and the neutral [EventNormalizer.Line]s. All harness-specific parsing happens in
 * [EventNormalizer]; a new harness only has to map its tool start to [EventNormalizer.Line.Tool] and its tool end to
 * [EventNormalizer.Line.ToolEnd] there.
 *
 * Each call returns the new activity when the phase or label changed (a frame worth sending), else null: a text delta
 * only advances [RunActivity.lastEventAt], so a streaming reply does not flood the wire.
 */
class RunActivityTracker(private val clock: () -> Long = System::currentTimeMillis) {
    private data class Slot(
        val phase: RunPhase,
        val label: String?,
        val startedAt: Long,
        val turnStartedAt: Long,
        val lastEventAt: Long,
        val openTools: Int,
    ) {
        fun activity() = RunActivity(phase, label, startedAt, turnStartedAt, lastEventAt)
    }

    private val slots = ConcurrentHashMap<String, Slot>()

    /** The session's activity now; `idle` for a session the tracker has not seen. */
    fun current(sessionId: String): RunActivity {
        val now = clock()
        return slots[sessionId]?.activity() ?: RunActivity(RunPhase.IDLE, null, now, now, now)
    }

    /** A turn was accepted: the run is thinking from now on. */
    fun turnStarted(sessionId: String): RunActivity = open(clock()).also { slots[sessionId] = it; com.superiorsoftllc.infantry.session.RunPhaseOverlay.set(sessionId, null) }.activity()

    /** The session's state changed. In flight with no open turn opens one; out of flight closes it. */
    fun sessionState(sessionId: String, state: SessionState): RunActivity? = update(sessionId) { s, now ->
        when (state) {
            SessionState.STARTING, SessionState.BUSY ->
                if (s == null || !s.phase.inFlight) open(now)
                else if (s.phase == RunPhase.WAITING_FOR_INPUT) s.to(RunPhase.THINKING, null, now)
                else s
            SessionState.WAITING_INPUT -> (s ?: open(now)).to(RunPhase.WAITING_FOR_INPUT, null, now)
            // Leaving flight with the turn still open: the turn is over (its `finished` may come next). Leaving it
            // after the turn's end was reported: the run is idle.
            SessionState.IDLE, SessionState.EXITED, SessionState.ORPHANED, SessionState.FAILED -> when {
                s == null -> null
                s.phase.inFlight -> s.to(if (state == SessionState.FAILED) RunPhase.ERROR else RunPhase.FINISHED, null, now).copy(openTools = 0)
                else -> s.to(RunPhase.IDLE, null, now)
            }
        }
    }

    /** One neutral line of the run's output. */
    fun line(sessionId: String, line: EventNormalizer.Line): RunActivity? = update(sessionId) { prev, now ->
        val s = prev?.takeIf { it.phase.inFlight } ?: open(now)
        when (line) {
            is EventNormalizer.Line.Tool ->
                s.to(RunPhase.TOOL_RUNNING, if (line.activity.isNullOrBlank()) line.name else "${line.name}: ${line.activity}", now)
                    .copy(openTools = s.openTools + 1)
            EventNormalizer.Line.CompactionStart -> s.to(RunPhase.COMPACTING, null, now).copy(openTools = 0)
            EventNormalizer.Line.CompactionEnd ->
                if (s.phase == RunPhase.COMPACTING) s.to(RunPhase.THINKING, null, now) else s.copy(lastEventAt = now)
            EventNormalizer.Line.ToolEnd -> {
                val open = (s.openTools - 1).coerceAtLeast(0)
                if (open == 0 && s.phase == RunPhase.TOOL_RUNNING) s.to(RunPhase.THINKING, null, now).copy(openTools = 0)
                else s.copy(openTools = open, lastEventAt = now)
            }
            // The model is speaking again, so whatever tool it called is over (covers a harness that reports a
            // tool's start but never its end).
            is EventNormalizer.Line.Text, is EventNormalizer.Line.Reasoning ->
                if (s.phase == RunPhase.TOOL_RUNNING) s.to(RunPhase.THINKING, null, now).copy(openTools = 0)
                else s.copy(lastEventAt = now)
            else -> s.copy(lastEventAt = now)
        }
    }

    /**
     * The turn ended: `error` when [failed], else `finished`. The daemon may report the session leaving flight BEFORE
     * the turn's end; then the run already reads `finished`, and this settles it (to `error` if [failed]) and reports
     * `idle`, so every turn reads `... -> finished|error -> idle` whichever of the two arrives first.
     */
    fun turnEnded(sessionId: String, failed: Boolean): List<RunActivity> {
        val endPhase = if (failed) RunPhase.ERROR else RunPhase.FINISHED
        val prev = slots[sessionId]?.phase
        if (prev == RunPhase.FINISHED || prev == RunPhase.ERROR) {
            val settled = if (failed) update(sessionId) { s, now -> s?.to(RunPhase.ERROR, null, now) } else null
            return listOfNotNull(settled, update(sessionId) { s, now -> s?.to(RunPhase.IDLE, null, now) })
        }
        return listOfNotNull(update(sessionId) { s, now -> (s ?: open(now)).to(endPhase, null, now).copy(openTools = 0) })
    }

    fun forget(sessionId: String) {
        slots.remove(sessionId)
        com.superiorsoftllc.infantry.session.RunPhaseOverlay.set(sessionId, null)
    }

    private fun open(now: Long) =
        Slot(RunPhase.THINKING, null, now, now, now, 0)

    private fun Slot.to(phase: RunPhase, label: String?, now: Long) =
        if (phase == this.phase && label == this.label) copy(lastEventAt = now)
        else copy(phase = phase, label = label, startedAt = now, lastEventAt = now)

    /** Apply [step]; report the result only when the phase or label moved. */
    private fun update(sessionId: String, step: (Slot?, Long) -> Slot?): RunActivity? {
        val now = clock()
        var changed: Slot? = null
        slots.compute(sessionId) { _, prev ->
            val next = step(prev, now)
            if (next != null && (prev == null || prev.phase != next.phase || prev.label != next.label)) changed = next
            com.superiorsoftllc.infantry.session.RunPhaseOverlay.set(sessionId, if (next?.phase == RunPhase.COMPACTING) "compacting" else null)
            next
        }
        return changed?.activity()
    }

    private val RunPhase.inFlight: Boolean
        get() = this == RunPhase.THINKING || this == RunPhase.TOOL_RUNNING || this == RunPhase.COMPACTING || this == RunPhase.WAITING_FOR_INPUT
}
