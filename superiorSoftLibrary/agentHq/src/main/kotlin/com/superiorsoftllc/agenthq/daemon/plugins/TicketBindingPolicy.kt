package com.superiorsoftllc.agenthq.daemon.plugins

import com.superiorsoftllc.agenthq.daemon.api.sessions.SessionBinding
import com.superiorsoftllc.agenthq.session.AutoProgress
import com.superiorsoftllc.agenthq.session.isBusy
import com.superiorsoftllc.agenthq.session.GateLock
import com.superiorsoftllc.agenthq.session.SessionBindingPolicy
import com.superiorsoftllc.agenthq.session.SessionRecord
import com.superiorsoftllc.agenthq.session.agentHqSetting
import com.superiorsoftllc.agenthq.settings.LimitDef
import com.superiorsoftllc.agenthq.settings.LimitRegistry

/**
 * AR-3b: the board's answers about a session bound to a ticket (moved out of SessionManager): the ticket deadline, the
 * one-agent-one-ticket (busy twin) rule and its refusal text, the machine-wide gate lock and the PROGRESS-ALL commit counts.
 */
class TicketBindingPolicy(
    /** Ticket 7e9bed2a: the deadline of an unattended TICKET worker (setting AGENTHQ_TICKET_DEADLINE_MIN). */
    private val ticketDeadlineMs: Long = defaultTicketDeadlineMs(),
    /** Ticket 7e9bed2a: the machine-wide gate lock; a live gate run for a session keeps it from counting as stalled. */
    private val gateLock: GateLock = GateLock(),
) : SessionBindingPolicy {
    override fun bindingFor(boundId: String?): SessionBinding? =
        boundId?.takeIf { it.isNotBlank() }?.let { SessionBinding(id = it, kind = KIND, facts = mapOf("ticketId" to it)) }

    /**
     * BASICS-KILL-1: a ticket's work is exclusive: two sessions may not run the same ticket, nor the same agent while either
     * of them works a ticket (they would share its worktree). Ticketless sessions of one agent (pod nodes) are not restricted.
     */
    override fun twin(exceptId: String?, agentId: String?, binding: SessionBinding?, sessions: Collection<SessionRecord>): SessionRecord? {
        val ticketId = binding?.id
        return sessions.firstOrNull { other ->
            other.id != exceptId && other.isBusy() && (
                (ticketId != null && other.ticketId == ticketId) ||
                    (agentId != null && other.agentId == agentId && (ticketId != null || other.ticketId != null))
                )
        }
    }

    override fun twinRefusal(what: String, agentId: String?, binding: SessionBinding?, twin: SessionRecord): String {
        val ticketId = binding?.id
        return "$what refused: session ${twin.id} already runs " +
            (if (ticketId != null && twin.ticketId == ticketId) "ticket $ticketId" else "agent $agentId") +
            " (${twin.state}, ${twin.provider}${twin.model?.let { " / $it" } ?: ""}); one agent works one ticket in one session. " +
            "Wait for it, or kill it first (agenthq kill ${twin.id})"
    }

    override fun deadlineMs(binding: SessionBinding?, live: (LimitDef, Long) -> Long): Long? =
        binding?.let { live(LimitRegistry.TICKET_DEADLINE_MINUTES, ticketDeadlineMs) }

    override fun deadlineSetting(binding: SessionBinding?): String? = binding?.let { "limits.watchdog.ticketDeadlineMinutes" }

    override fun lockHeld(sessionId: String, binding: SessionBinding?): Boolean =
        runCatching { gateLock.liveFor(sessionId, binding?.id) }.getOrDefault(false)

    override fun progressCount(session: SessionRecord): Int? {
        val dir = session.worktreePath
        val base = session.worktreeBaseRef
        return if (dir != null && base != null) AutoProgress.commitCount(dir, base) else null
    }

    companion object {
        const val KIND = "ticket"
        val DEFAULT_TICKET_DEADLINE_MS = LimitRegistry.TICKET_DEADLINE_MINUTES.default * 60_000L

        /** AGENTHQ_TICKET_DEADLINE_MIN (minutes, > 0), else 3 h. */
        fun defaultTicketDeadlineMs(): Long =
            agentHqSetting("AGENTHQ_TICKET_DEADLINE_MIN")?.trim()?.toLongOrNull()?.takeIf { it > 0 }?.let { it * 60_000L } ?: DEFAULT_TICKET_DEADLINE_MS
    }
}
