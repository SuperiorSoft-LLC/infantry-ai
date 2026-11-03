package com.superiorsoftllc.infantry.brief

import com.superiorsoftllc.infantry.ledger.Asked
import com.superiorsoftllc.infantry.ledger.Done
import com.superiorsoftllc.infantry.ledger.Outcome

/** The brief is the single source of truth for what was asked; the ledger holds plain fields and imports no board package. */
fun Asked.Companion.from(brief: WorkerBrief): Asked = Asked(
    taskId = brief.taskId,
    projectId = brief.projectId,
    title = brief.title,
    instruction = brief.instruction,
    promptChars = brief.prompt.length,
)

/**
 * Maps a [WorkerRunResult] to a [Done] entry for the ledger.
 *
 * A run stopped from outside ([WorkerRunResult.stopped]: cooperative cancellation or an external hard
 * kill, ticket 29ff7ace) maps to STOPPED; any other failure remains FAILED. The MVP 6.3 rule -- a
 * CancellationException class prefix in the error string means STOPPED -- is kept for results built
 * before the explicit flag existed.
 */
fun WorkerRunResult.toDone(): Done = when {
    succeeded -> Done(taskId, Outcome.COMPLETED, output)
    stopped -> Done(taskId, Outcome.STOPPED, error.orEmpty())
    error?.startsWith("java.util.concurrent.CancellationException:") == true ->
        Done(taskId, Outcome.STOPPED, error)
    else -> Done(taskId, Outcome.FAILED, error.orEmpty())
}
