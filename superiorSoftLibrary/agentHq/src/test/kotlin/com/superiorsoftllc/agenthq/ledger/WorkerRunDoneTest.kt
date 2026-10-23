package com.superiorsoftllc.agenthq.ledger

import com.superiorsoftllc.agenthq.brief.WorkerRunResult
import java.util.concurrent.CancellationException
import kotlin.test.Test
import kotlin.test.assertEquals

class WorkerRunDoneTest {

    @Test
    fun succeededMapsToCompletedWithOutputAsSummary() {
        val result = WorkerRunResult(
            taskId = "task-1",
            succeeded = true,
            output = "model output here",
            error = null,
        )

        val done = result.toDone()

        assertEquals(Done("task-1", Outcome.COMPLETED, "model output here"), done)
    }

    @Test
    fun failedMapsToFailedWithErrorAsSummary() {
        val result = WorkerRunResult(
            taskId = "task-2",
            succeeded = false,
            output = "",
            error = "provider exploded",
        )

        val done = result.toDone()

        assertEquals(Done("task-2", Outcome.FAILED, "provider exploded"), done)
    }

    @Test
    fun failedWithNullErrorMapsToFailedWithEmptySummary() {
        val result = WorkerRunResult(
            taskId = "task-3",
            succeeded = false,
            output = "",
            error = null,
        )

        val done = result.toDone()

        assertEquals(Done("task-3", Outcome.FAILED, ""), done)
    }

    @Test
    fun endToEndBriefToAskedToWorkerRunToDoneToVerdict() {
        val ledger = InMemoryAskedDoneLedger()
        val asked = Asked(
            taskId = "task-e2e",
            projectId = "project-1",
            title = "Wire the project store",
            instruction = "Build the store over the Project DTO.",
            promptChars = 100,
        )

        // compose brief → recordAsked(Asked.from(brief))
        ledger.recordAsked(asked)

        // worker result → recordDone(run.toDone())
        val runResult = WorkerRunResult(
            taskId = "task-e2e",
            succeeded = true,
            output = "store wired successfully",
            error = null,
        )
        val done = runResult.toDone()
        ledger.recordDone(done)

        // entry verdict
        val entry = ledger.entry("task-e2e")

        assertEquals(LedgerEntry(asked, done, Verdict.DELIVERED), entry)
    }

    @Test
    fun endToEndBriefToAskedToFailedWorkerRunToDoneToVerdict() {
        val ledger = InMemoryAskedDoneLedger()
        val asked = Asked(
            taskId = "task-e2e-fail",
            projectId = "project-1",
            title = "Wire the project store",
            instruction = "Build the store over the Project DTO.",
            promptChars = 100,
        )

        ledger.recordAsked(asked)

        val runResult = WorkerRunResult(
            taskId = "task-e2e-fail",
            succeeded = false,
            output = "",
            error = "model timeout",
        )
        val done = runResult.toDone()
        ledger.recordDone(done)

        val entry = ledger.entry("task-e2e-fail")

        assertEquals(LedgerEntry(asked, done, Verdict.UNDELIVERED), entry)
    }

    @Test
    fun cancellationExceptionMapsToStopped() {
        val result = WorkerRunResult(
            taskId = "task-stopped",
            succeeded = false,
            output = "",
            error = CancellationException("worker stopped").toString(),
        )

        val done = result.toDone()

        assertEquals(Outcome.STOPPED, done.outcome)
        assertEquals("task-stopped", done.taskId)
        assertEquals(CancellationException("worker stopped").toString(), done.summary)
    }

    @Test
    fun ordinaryFailureWithCancellationExceptionInMessageRemainsFailed() {
        val result = WorkerRunResult(
            taskId = "task-failed",
            succeeded = false,
            output = "",
            error = IllegalStateException("CancellationException: unrelated message").toString(),
        )

        val done = result.toDone()

        assertEquals(Outcome.FAILED, done.outcome)
        assertEquals("task-failed", done.taskId)
        assertEquals(result.error, done.summary)
    }

    @Test
    fun anExplicitlyStoppedRunMapsToStoppedWhateverItsErrorSays() {
        val killed = WorkerRunResult(taskId = "task-k", succeeded = false, output = "", error = "killed by operator", stopped = true)
        assertEquals(Done("task-k", Outcome.STOPPED, "killed by operator"), killed.toDone())
        val failed = WorkerRunResult(taskId = "task-f", succeeded = false, output = "", error = "killed by operator")
        assertEquals(Outcome.FAILED, failed.toDone().outcome, "the same text without the flag is a failure: no string guessing")
    }
}
