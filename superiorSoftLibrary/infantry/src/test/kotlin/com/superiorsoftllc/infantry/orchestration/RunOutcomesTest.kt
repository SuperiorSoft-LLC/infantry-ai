package com.superiorsoftllc.infantry.orchestration

import com.superiorsoftllc.infantry.protocol.FinishedPayload.Outcome
import com.superiorsoftllc.infantry.session.KillOutcome
import com.superiorsoftllc.infantry.session.SessionRecord
import com.superiorsoftllc.infantry.session.SessionState
import kotlin.test.Test
import kotlin.test.assertEquals

class RunOutcomesTest {

    private fun rec(state: SessionState, kill: KillOutcome? = null, exit: Int? = null, error: String? = null) =
        SessionRecord(
            id = "s", provider = "stub", workingDir = "w", spawnedAt = 0, lastHeartbeat = 0,
            state = state, lastExitCode = exit, killOutcome = kill, lastError = error,
        )

    @Test
    fun `the outcome comes from why the run stopped, never from the exit code alone`() {
        assertEquals(Outcome.SUCCEEDED, RunOutcomes.of(rec(SessionState.IDLE, exit = 0)))
        assertEquals(Outcome.FAILED, RunOutcomes.of(rec(SessionState.FAILED, exit = 3)))
        assertEquals(Outcome.FAILED, RunOutcomes.of(rec(SessionState.FAILED, error = "could not start")))
        // The trap Phase 5 must not fall into: a graceful kill that exited 0 is NOT a success,
        // and a forced kill that exited 137 is NOT a failure.
        assertEquals(Outcome.KILLED_GRACEFUL, RunOutcomes.of(rec(SessionState.EXITED, kill = KillOutcome.GRACEFUL, exit = 0)))
        assertEquals(Outcome.KILLED_FORCED, RunOutcomes.of(rec(SessionState.EXITED, kill = KillOutcome.FORCED, exit = 137)))
        assertEquals(Outcome.ORPHANED, RunOutcomes.of(rec(SessionState.ORPHANED)))
    }

    @Test
    fun `only FAILED is retryable`() {
        assertEquals(setOf(Outcome.FAILED), Outcome.entries.filter { RunOutcomes.isRetryable(it) }.toSet())
    }

    @Test
    fun `in-flight states, WAITING_INPUT included, never end an attempt`() {
        assertEquals(
            setOf(SessionState.IDLE, SessionState.FAILED, SessionState.EXITED, SessionState.ORPHANED),
            SessionState.entries.filter { RunOutcomes.isAttemptOver(it) }.toSet(),
        )
    }
}
