package com.superiorsoftllc.infantry.orchestration

import com.superiorsoftllc.infantry.persistence.RunEvent
import com.superiorsoftllc.infantry.persistence.RunRecord
import com.superiorsoftllc.infantry.persistence.StoredEvent
import com.superiorsoftllc.infantry.protocol.FinishedPayload
import com.superiorsoftllc.infantry.protocol.StatusPayload
import com.superiorsoftllc.infantry.session.SessionState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RetryFeedbackTest {

    private fun run(exitCode: Int?, error: String?) = RunRecord(
        id = "r-1", sessionId = "s-1", provider = "stub", workingDir = "w", prompt = "fix the bug",
        state = SessionState.FAILED, startedAt = 1L, endedAt = 2L, exitCode = exitCode, error = error,
    )

    private fun stored(vararg events: RunEvent) =
        events.mapIndexed { i, e -> StoredEvent(seq = i + 1L, runId = "r-1", event = e, vocabularyVersion = "1.0.0", createdAt = 1L) }

    @Test
    fun `feedback quotes the failed attempt's exit code, error frames and last output`() {
        val text = RetryFeedback.compose(
            originalPrompt = "fix the bug",
            nextAttempt = 2,
            maxAttempts = 3,
            previousRun = run(exitCode = 3, error = null),
            previousEvents = stored(
                RunEvent.Status(StatusPayload(SessionState.BUSY)),
                RunEvent.TextDelta("hello "),
                RunEvent.TextDelta("world"),
                RunEvent.Error("boom 1"),
                RunEvent.Error("boom 2"),
                RunEvent.Finished(FinishedPayload(exitCode = 3, outcome = FinishedPayload.Outcome.FAILED)),
            ),
        )
        assertEquals(
            "fix the bug\n" +
                "\n" +
                "---\n" +
                "INFANTRY RETRY FEEDBACK (attempt 2 of 3)\n" +
                "The previous attempt (attempt 1) did not succeed.\n" +
                "exit code: 3\n" +
                "daemon error: none\n" +
                "provider errors:\n" +
                "- boom 1\n" +
                "- boom 2\n" +
                "last output:\n" +
                "hello world\n" +
                "---\n" +
                "Address the failure above, then complete the original task.",
            text,
        )
    }

    @Test
    fun `a run that never started says so instead of inventing an exit code`() {
        val text = RetryFeedback.compose("do it", 2, 2, run(exitCode = null, error = "could not start: no such file"), emptyList())
        assertTrue(text.contains("\nexit code: none (the run could not be started)\n"), text)
        assertTrue(text.contains("\ndaemon error: could not start: no such file\n"), text)
        assertTrue(text.contains("\nprovider errors:\n- none\n"), text)
        assertTrue(text.contains("\nlast output:\n(none)\n"), text)
    }

    @Test
    fun `errors and output are bounded`() {
        val errors = (1..12).map { RunEvent.Error("e$it") }
        val output = RunEvent.TextDelta("x".repeat(2_400) + "TAIL")
        val text = RetryFeedback.compose("p", 2, 2, run(1, null), stored(*(errors + output).toTypedArray()))
        assertTrue(!text.contains("- e1\n") && !text.contains("- e2\n"), "only the last ${RetryFeedback.MAX_ERRORS} errors")
        assertTrue(text.contains("- e3\n") && text.contains("- e12\n"))
        val lastOutput = text.substringAfter("last output:\n").substringBefore("\n---\n")
        assertEquals(RetryFeedback.MAX_OUTPUT_CHARS, lastOutput.length)
        assertTrue(lastOutput.endsWith("TAIL"))
    }
}
