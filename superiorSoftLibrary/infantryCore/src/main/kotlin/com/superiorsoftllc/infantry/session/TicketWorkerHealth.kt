package com.superiorsoftllc.infantry.session

/**
 * GAP-006b: the ONE rule for "can this in-progress ticket's worker carry its work", shared by the board (a start of an
 * in-progress ticket, the unbriefed sweep) and `infantry ticket start` / `infantry_ticket_start`, so the client and the
 * daemon never disagree about whether a start restarts the ticket.
 *
 * A worker carries its ticket when it has a turn in flight, or when it is at rest WITH a provider session id (it took
 * its brief, and Resume continues that conversation). A worker at rest with no provider session id never opened a
 * conversation, whatever its run count says: its brief never landed (a run that exited with no output, a turn
 * interrupted before the harness answered, a daemon restart mid-boot), so nothing can resume it. That is the phantom
 * session of ticket 006b778b: IDLE, transcript empty, runCount 1, "not resumable".
 */
object TicketWorkerHealth {

    /** Why [worker] (the ticket's recorded session, null when it names none or the daemon lists none) cannot carry it; null when it can. */
    fun problem(worker: SessionRecord?, recordedId: String? = worker?.id): String? = when {
        worker == null && recordedId == null -> "it has no worker session"
        worker == null -> "its worker session $recordedId is gone"
        worker.isBusy() -> null
        worker.providerSessionId == null ->
            "its worker session ${worker.id} never opened a provider session (state ${worker.state}, " +
                "${worker.runCount} run(s)), so it never received its brief and cannot be resumed"
        else -> null
    }

    /**
     * A worker that is BUSY yet has opened no provider session for longer than [graceMs] (measured from its last sign
     * of life): flagged, never ended here (a slow first boot is not proof of death).
     */
    fun slowToBrief(worker: SessionRecord, now: Long, graceMs: Long): Boolean =
        worker.isBusy() && worker.providerSessionId == null && graceMs != Long.MAX_VALUE && now - worker.lastHeartbeat >= graceMs
}
