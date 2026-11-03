package com.superiorsoftllc.infantry.brief

/**
 * MVP Sprint-Zero leaf 5: what a WORKER does with the brief it was handed.
 *
 * `lead + relay` runs one story as two halves: a lead decomposes the project and [LeadBriefComposer]
 * copies exactly one task across; this is the other half, where a model is pointed at that task and
 * its answer comes back. Leaf 4 owns what crosses the seam; this owns what happens after.
 *
 * The model is a parameter, not a dependency: [call] is a `(String) -> String` taking the prompt and
 * returning the text. That is the whole contract, so nothing here knows a CLI, a provider, a socket
 * or a process exists -- a real provider is wired in later behind the same function type, and this
 * leaf's behaviour is provable today with a lambda that returns a constant.
 *
 * Two things are decided here and nowhere else:
 *
 *  - WHAT THE MODEL READS. The prompt handed to [call] is [WorkerBrief.prompt] -- the brief, field
 *    for field, verbatim. Not a re-rendering, not an enriched version with the project's goal or the
 *    board's other stories stitched back on: the composer already decided what a worker may see, and
 *    re-adding context at this layer would quietly undo it. A worker sees the task, its project id
 *    and its lead's instruction, and nothing else.
 *  - WHAT A FAILURE LOOKS LIKE. A model is the one component of this MVP that reliably fails for
 *    reasons outside the system's control, so a thrown [call] becomes a FAILED
 *    [WorkerRunResult] carrying the cause, never an exception escaping into the caller. A lead
 *    driving a board must be able to record "this task did not run" and carry on with the next one;
 *    a crash escaping [run] would instead take the whole relay down over one provider hiccup.
 *
 * Pure: same brief and same [call] in, same result out. No clock, no I/O, no daemon, no store, no
 * randomness. A [WorkerRunResult] is a fact about a run, not a mutable session state.
 */
data class WorkerRunResult(

    /** The task this run belongs to -- [WorkerBrief.taskId], copied so a result is never an orphan. */
    val taskId: String,

    /** True when [call] answered. False when it threw. */
    val succeeded: Boolean,

    /** What the model wrote. Empty on a failure, which never produced any text. */
    val output: String,

    /** Null on success; on a failure, the exception's own class and message, so a lead can act on it. */
    val error: String? = null,

    /**
     * Ticket 29ff7ace: true when the run was STOPPED from outside -- a cooperative cancellation
     * ([java.util.concurrent.CancellationException]) or an external hard kill ([WorkerRunStopped]) --
     * rather than failing on its own. The explicit signal, so nobody has to parse it out of [error].
     */
    val stopped: Boolean = false,
)

/**
 * Thrown by a model [WorkerRun.run] call whose run was stopped from OUTSIDE: the process killed, the
 * session ended by an operator. It is how the call says "I was stopped", as opposed to any other exception,
 * which says "I failed". [reason] is kept on the result's error text.
 */
class WorkerRunStopped(reason: String) : RuntimeException(reason)

/**
 * Runs ONE [WorkerBrief] through an injected model call.
 *
 * One story, one prompt, one result: the brief's prompt goes to the model exactly once and whatever
 * comes back is returned labelled with the task it belongs to. See [WorkerRunResult] for what does
 * and does not cross.
 */
object WorkerRun {

    fun run(brief: WorkerBrief, call: (String) -> String): WorkerRunResult {
        val output = try {
            call(brief.prompt)
        } catch (stop: java.util.concurrent.CancellationException) {
            return WorkerRunResult(taskId = brief.taskId, succeeded = false, output = "", error = stop.toString(), stopped = true)
        } catch (stop: WorkerRunStopped) {
            return WorkerRunResult(taskId = brief.taskId, succeeded = false, output = "", error = stop.toString(), stopped = true)
        } catch (failure: Exception) {
            return WorkerRunResult(
                taskId = brief.taskId,
                succeeded = false,
                output = "",
                error = failure.toString(),
            )
        }
        return WorkerRunResult(
            taskId = brief.taskId,
            succeeded = true,
            output = output,
            error = null,
        )
    }
}
