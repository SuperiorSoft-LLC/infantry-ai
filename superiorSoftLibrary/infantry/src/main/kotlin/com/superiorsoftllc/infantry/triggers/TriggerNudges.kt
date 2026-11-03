package com.superiorsoftllc.infantry.triggers

import com.superiorsoftllc.infantry.daemon.api.board.PromptStart
import com.superiorsoftllc.infantry.daemon.api.triggers.TriggerMailRequest
import com.superiorsoftllc.infantry.daemon.api.triggers.TriggerMailResult
import com.superiorsoftllc.infantry.daemon.api.triggers.TriggerSession

/**
 * TRIGGER-NUDGE (contract 1.105.0): a `prompt` action delivered the way a colleague would deliver it -- as MAIL to the
 * target's session in the trigger's project. The mailbox persists it first and hands it over when that session comes
 * to rest, so a target in mid-turn gets the nudge after its turn instead of losing it; nothing here ever refuses
 * because the target is busy.
 *
 * TARGET. `role:<role>`: the project's sessions of that role that mail can still reach (in flight, resumable, or bound
 * and never run), the live one first, then the newest. A profile id: that agent's sessions, wherever they are. A
 * trigger with no project matches sessions with no project.
 *
 * NO SESSION. Only then is a session started, with the prompt as its first turn ([startSession], the unattended prompt
 * launch). A start refused because the role's agents are all busy elsewhere is [PromptDelivery.Busy], never a trip.
 *
 * Each fire is its own mail thread (`trigger:<id>:<n>`), so a trigger that runs all night never reaches the
 * per-thread ceiling.
 */
class TriggerNudges(
    private val sessions: () -> List<TriggerSession>,
    private val send: (TriggerMailRequest) -> TriggerMailResult,
    private val startSession: (agentId: String, prompt: String, projectId: String?) -> PromptStart,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    fun deliver(nudge: TriggerNudge): PromptDelivery {
        val target = target(nudge)
        if (target == null) {
            return when (val launch = startSession(nudge.agentId, nudge.prompt, nudge.projectId)) {
                is PromptStart.Started -> PromptDelivery.Started(launch.sessionId)
                is PromptStart.Refused ->
                    if (launch.detail.contains("at capacity")) PromptDelivery.Busy(launch.detail) else PromptDelivery.Refused(launch.detail)
            }
        }
        // Addressed to the agent when the session has one, so the mail follows the agent (and drains when it rests).
        val request = TriggerMailRequest(
            from = "$SENDER_PREFIX${nudge.triggerId}",
            to = target.agentId?.takeIf { it.isNotBlank() } ?: target.id,
            subject = "Trigger: ${nudge.triggerName}",
            body = nudge.prompt,
            threadId = "$SENDER_PREFIX${nudge.triggerId}:${clock()}",
        )
        return when (val sent = send(request)) {
            is TriggerMailResult.Sent -> PromptDelivery.Mailed(sent.mailId)
            is TriggerMailResult.Refused -> PromptDelivery.Refused("prompt mail refused: ${sent.reason}")
        }
    }

    internal fun target(nudge: TriggerNudge): TriggerSession? {
        val reachable = sessions().filter { it.reachable }
        val candidates = if (nudge.agentId.startsWith(ROLE_PREFIX)) {
            val role = nudge.agentId.removePrefix(ROLE_PREFIX)
            val project = nudge.projectId?.takeIf { it.isNotBlank() }
            reachable.filter { it.role == role && it.projectId?.takeIf { p -> p.isNotBlank() } == project }
        } else {
            reachable.filter { it.agentId == nudge.agentId }
        }
        return candidates.sortedByDescending { it.spawnedAt }.let { newest -> newest.firstOrNull { it.live } ?: newest.firstOrNull() }
    }

    companion object {
        const val SENDER_PREFIX = "trigger:"
        private const val ROLE_PREFIX = "role:"
    }
}
