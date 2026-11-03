package com.superiorsoftllc.infantry.backlog

import com.superiorsoftllc.infantry.daemon.api.board.BoardSessionDetail
import com.superiorsoftllc.infantry.protocol.FinishedPayload

/** DP-5f-3d: session state names as the API's SessionView spells them (the core enum's `name`s), so the board holds no core enum. */
internal object BoardSessionStates {
    const val FAILED = "FAILED"
    val ACTIVE: Set<String> = setOf("STARTING", "BUSY", "WAITING_INPUT")
}

/** DP-5f-3d: the run outcome from a session view's names; the same resolution as core `RunOutcomes.of(state, killOutcome)`. */
internal object BoardRunOutcomes {
    fun of(state: String, killOutcome: String?): FinishedPayload.Outcome = when {
        killOutcome == "GRACEFUL" -> FinishedPayload.Outcome.KILLED_GRACEFUL
        killOutcome == "FORCED" -> FinishedPayload.Outcome.KILLED_FORCED
        state == "ORPHANED" -> FinishedPayload.Outcome.ORPHANED
        state == "IDLE" -> FinishedPayload.Outcome.SUCCEEDED
        else -> FinishedPayload.Outcome.FAILED
    }
}

/** DP-5f-3d: GAP-006b's worker-health rule over a [BoardSessionDetail]; the same text and conditions as core `TicketWorkerHealth`. */
internal object BoardWorkerHealth {
    fun problem(worker: BoardSessionDetail?, recordedId: String? = worker?.view?.id): String? = when {
        worker == null && recordedId == null -> "it has no worker session"
        worker == null -> "its worker session $recordedId is gone"
        worker.busy -> null
        worker.providerSessionId == null ->
            "its worker session ${worker.view.id} never opened a provider session (state ${worker.view.state}, " +
                "${worker.view.runCount} run(s)), so it never received its brief and cannot be resumed"
        else -> null
    }

    fun slowToBrief(worker: BoardSessionDetail, now: Long, graceMs: Long): Boolean =
        worker.busy && worker.providerSessionId == null && graceMs != Long.MAX_VALUE && now - worker.lastHeartbeat >= graceMs
}
