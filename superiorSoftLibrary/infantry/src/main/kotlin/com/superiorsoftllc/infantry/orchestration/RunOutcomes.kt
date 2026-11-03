package com.superiorsoftllc.infantry.orchestration

import com.superiorsoftllc.infantry.protocol.FinishedPayload
import com.superiorsoftllc.infantry.session.KillOutcome
import com.superiorsoftllc.infantry.session.SessionRecord
import com.superiorsoftllc.infantry.session.SessionState

object RunOutcomes {
    /** States in which a run is over. Everything else (STARTING, BUSY, WAITING_INPUT) is in flight. */
    val ATTEMPT_OVER: Set<SessionState> = setOf(SessionState.IDLE, SessionState.FAILED, SessionState.EXITED, SessionState.ORPHANED)

    fun isAttemptOver(state: SessionState): Boolean = state in ATTEMPT_OVER

    /** Resolution order is the contract's: WHY it stopped first, then what state it reached. */
    /** The same resolution over a session view's names ([SessionState] / [KillOutcome] `name`s). */
    fun of(state: String, killOutcome: String?): FinishedPayload.Outcome = when {
        killOutcome == KillOutcome.GRACEFUL.name -> FinishedPayload.Outcome.KILLED_GRACEFUL
        killOutcome == KillOutcome.FORCED.name -> FinishedPayload.Outcome.KILLED_FORCED
        state == SessionState.ORPHANED.name -> FinishedPayload.Outcome.ORPHANED
        state == SessionState.IDLE.name -> FinishedPayload.Outcome.SUCCEEDED
        else -> FinishedPayload.Outcome.FAILED
    }

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
