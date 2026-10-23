package com.superiorsoftllc.agenthq.orchestration

import com.superiorsoftllc.agenthq.protocol.FinishedPayload
import com.superiorsoftllc.agenthq.session.KillOutcome
import com.superiorsoftllc.agenthq.session.SessionRecord
import com.superiorsoftllc.agenthq.session.SessionState

object RunOutcomes {
    /** States in which a run is over. Everything else (STARTING, BUSY, WAITING_INPUT) is in flight. */
    val ATTEMPT_OVER: Set<SessionState> = setOf(SessionState.IDLE, SessionState.FAILED, SessionState.EXITED, SessionState.ORPHANED)

    fun isAttemptOver(state: SessionState): Boolean = state in ATTEMPT_OVER

    /** Resolution order is the contract's: WHY it stopped first, then what state it reached. */
    fun of(record: SessionRecord): FinishedPayload.Outcome = when {
        record.killOutcome == KillOutcome.GRACEFUL -> FinishedPayload.Outcome.KILLED_GRACEFUL
        record.killOutcome == KillOutcome.FORCED -> FinishedPayload.Outcome.KILLED_FORCED
        record.state == SessionState.ORPHANED -> FinishedPayload.Outcome.ORPHANED
        record.state == SessionState.IDLE -> FinishedPayload.Outcome.SUCCEEDED
        else -> FinishedPayload.Outcome.FAILED
    }

    /** Only a genuine failure is worth another attempt. A kill is a decision; an orphan may still be alive. */
    fun isRetryable(outcome: FinishedPayload.Outcome): Boolean = outcome == FinishedPayload.Outcome.FAILED
}
