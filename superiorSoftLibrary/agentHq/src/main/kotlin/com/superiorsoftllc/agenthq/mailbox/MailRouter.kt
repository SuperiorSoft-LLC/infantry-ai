package com.superiorsoftllc.agenthq.mailbox

import com.superiorsoftllc.agenthq.session.InfantryEnv

import com.superiorsoftllc.agenthq.agents.AgentProfiles
import com.superiorsoftllc.agenthq.agents.KnowledgeCounter
import com.superiorsoftllc.agenthq.agents.SkillsSource
import com.superiorsoftllc.agenthq.agents.firstTurn
import com.superiorsoftllc.agenthq.agents.section
import com.superiorsoftllc.agenthq.context.TicketContextComposer
import com.superiorsoftllc.agenthq.session.SessionEvent
import com.superiorsoftllc.agenthq.session.SessionManager
import com.superiorsoftllc.agenthq.session.SessionRecord
import com.superiorsoftllc.agenthq.session.isAvailable
import com.superiorsoftllc.agenthq.session.isBusy
import com.superiorsoftllc.agenthq.session.isInSession
import java.util.concurrent.ConcurrentHashMap

/**
 * The bridge between the [Mailbox] and live sessions: it turns "a message is due" into "that
 * agent's console runs it".
 *
 * A message reaches a recipient the only way AgentHQ has to give an agent a turn: by RESUMING its
 * session with the message rendered as the next prompt. That is literally "it lands when the
 * agent's console returns" -- the console is the session, and a session at rest is one a message
 * can wake.
 *
 * DELIVERY POLICY, stated so it is testable and not magic:
 *  - recipient has a run in flight (STARTING / BUSY / WAITING_INPUT) -> [DeliveryOutcome.Retry]:
 *    the message stays QUEUED and is handed over when that run finishes;
 *  - recipient is resumable and at rest (IDLE, or a terminal state with a provider session id) ->
 *    resume it carrying the message;
 *  - recipient does not exist, or has no provider session id to resume -> [DeliveryOutcome.Failed]:
 *    recorded as [MessageState.UNDELIVERABLE], which is the visible delivery failure the phase
 *    requires. It is never retried silently, because it can never succeed.
 *
 * ADDRESSING: a recipient may be a SESSION id or an AGENT PROFILE id. An agent id resolves to the
 * agent's session whether it is in flight (BUSY) or at rest (resumable), so a message to a busy
 * colleague QUEUES like any busy recipient -- it is refused only when the agent has no session at
 * all ("no running session -- start it first"). A queued agent-id-addressed message is drained both
 * when the message was sent and when that session next comes to rest (see [pump]'s agent alias).
 *
 * RE-ENTRANCY: [SessionManager.resume] emits a status event synchronously, which calls back into
 * [onSessionEvent] -> [pump]. A per-session guard makes that inner pump a no-op, so one message is
 * never delivered twice by its own delivery.
 */
class MailRouter(
    private val manager: SessionManager,
    private val mailbox: Mailbox,
    private val agentProfiles: AgentProfiles,
    /**
     * Base URL the recipient's CLI uses to call back (the daemon's HTTP mail API, see
     * `MailHttp.kt`). Rendered into the delivered prompt so a spawned agent can answer without
     * knowing the daemon's internals. Overridable with `AGENTHQ_MAIL_BASE` for a non-local daemon.
     * MAIL-FOOTER-1: no longer rendered (the footer prints CLI commands, not curl); kept so the
     * daemon's wiring and its callers do not change with the footer.
     */
    private val mailBaseUrl: String = InfantryEnv.get("AGENTHQ_MAIL_BASE")?.takeIf { it.isNotBlank() }
        ?: "http://127.0.0.1:8787",
    /**
     * When true, the curl commands rendered into a delivered prompt send `Authorization: Bearer
     * $AGENTHQ_TOKEN`. The daemon puts that variable in each agent's environment (see [SessionManager]'s
     * `childEnv`), so the API works on a token-gated daemon while the literal token is never written
     * into a prompt. MAIL-FOOTER-1: no longer rendered either; the CLI reads the token itself.
     */
    private val agentsSendToken: Boolean = false,
    /**
     * BUG-S3 (docs/AGENTHQ-ROLE-FIRST.md 4.2): cuts a fresh session bound to an agent, at rest, on the agent's CURRENT
     * executor (the daemon wires `bindSessionOnSpawn`). Used when mail addressed to an agent (by id or role) would
     * otherwise resume a session on a harness the agent was swapped away from. Null = never cut one (resume as before).
     */
    private val bindFreshSession: ((agentId: String) -> SessionRecord?)? = null,
    /** P10-K9: a never-run recipient's knowledge section, read AT DELIVERY for its own project. */
    private val knowledge: KnowledgeCounter = KnowledgeCounter.NONE,
    /** P10-K15: a never-run recipient's skills section, from its frozen skill ids, bodies read AT DELIVERY. */
    private val skills: SkillsSource = SkillsSource.NONE,
    /**
     * BUG-MAIL-2: the session that started the ticket a worker session runs (null = none recorded). It makes
     * `starter` an address, and lets a worker and its starter mail each other across projects (CP-3's one exception).
     */
    private val starterOf: (workerSessionId: String) -> String? = { null },
) : MailDelivery {

    /** Sessions currently being pumped; the re-entrancy guard. */
    private val pumping = ConcurrentHashMap.newKeySet<String>()

    /** Feed every session status event here; a session that just came to rest gets its mail. */
    fun onSessionEvent(event: SessionEvent) {
        if (event !is SessionEvent.Status) return
        if (!event.session.isAvailable()) return
        pump(event.session.id)
        // BASICS-KILL-1: mail for another session of the same agent waited for this one's turn to end (busyTwin): drain it.
        val agentId = event.session.agentId ?: return
        for (sibling in manager.list()) {
            if (sibling.id != event.session.id && sibling.agentId == agentId && sibling.isAvailable()) pump(sibling.id)
        }
    }

    /** At startup, deliver anything already queued for a session that is at rest. */
    fun onStartup() {
        for (session in manager.list()) if (session.isAvailable()) pump(session.id)
    }

    /**
     * Send and, if it was accepted, immediately try to deliver. The send itself is persisted by the
     * mailbox before this returns, so the pump can only ever be a bonus, never a precondition.
     */
    fun send(request: MailSendRequest): MailResult {
        crossProjectRefusal(request)?.let { return MailResult.Refused(it) }
        val result = mailbox.send(addressStarter(request))
        if (result is MailResult.Sent) {
            if (request.allowCrossProject) crossAllowed.add(result.message.id)
            // Pump by the ORIGINAL recipient address (session id, agent id, or role name), because
            // that is how the message is keyed in the mailbox. `deliver` resolves it to a session.
            pump(result.message.to)
        }
        return result
    }

    /**
     * Messages sent with `allowCrossProject`, so delivery's CP-3 check lets them through. In memory: a restart
     * between send and delivery of a still-queued override message makes it UNDELIVERABLE (visible, resend it).
     */
    private val crossAllowed = ConcurrentHashMap.newKeySet<String>()

    /**
     * Send-time cross-project guard: a SESSION sender addressing a session or agent id of another project is refused
     * naming both projects, unless it passes `allowCrossProject` or the pair is a worker and its starter. Senders
     * without a session (owner, user, system) and role names are not affected (roles resolve in the sender's project).
     */
    private fun crossProjectRefusal(request: MailSendRequest): String? {
        if (request.allowCrossProject || request.from.isBlank() || request.to == TicketContextComposer.STARTER) return null
        val sender = manager.get(request.from) ?: return null
        val senderProject = sender.projectId?.takeIf { it.isNotBlank() } ?: return null
        val target = manager.get(request.to)
        val targetProject = (target?.projectId ?: agentProfiles.get(request.to)?.projectId)?.takeIf { it.isNotBlank() } ?: return null
        if (targetProject == senderProject) return null
        val targetId = target?.id ?: request.to
        if (starterOf(request.from) == targetId || starterOf(targetId) == request.from) return null
        return "cross-project mail refused: sender '${request.from}' is in project $senderProject but recipient " +
            "'${request.to}' is in project $targetProject; pass allowCrossProject (--cross-project) to send anyway"
    }

    /**
     * BUG-MAIL-2: `starter` is stored addressed to the starter's SESSION id, so a message to a busy starter queues
     * under the key its next rest drains (a bare alias would never be pumped again). With no starter recorded the
     * alias is kept, and delivery fails visibly with the reason ([resolveRecipient]).
     */
    private fun addressStarter(request: MailSendRequest): MailSendRequest {
        if (request.to != TicketContextComposer.STARTER || request.from.isBlank()) return request
        val starter = starterOf(request.from) ?: return request
        return request.copy(to = starter)
    }

    /**
     * FAN-OUT: dispatch one task to N recipients and hand each copy to that recipient's console, one
     * pump per recipient so a busy agent's copy stays QUEUED and is re-pumped when it rests (exactly
     * the per-recipient policy a one-to-one send uses). Returns the persisted batch; delivery is
     * reported through the mailbox's state, not the returned snapshot.
     */
    fun fanOut(request: MailFanOutRequest): FanOutResult {
        val result = mailbox.fanOut(request)
        if (result is FanOutResult.Sent) {
            // Pump by the ORIGINAL recipient address (session id or agent id), exactly like [send]:
            // the message is keyed in the mailbox by that address, and `deliver` resolves it to a
            // session. Resolving to a session id here would make the pump rely on the agent-alias
            // drain, which is the very re-entrancy path the exactly-once guard protects.
            for (message in result.messages) {
                pump(message.to)
            }
        }
        return result
    }

    /** Try to deliver everything queued for [sessionId]. Safe to call from anywhere; guarded. */
    fun pump(sessionId: String) {
        if (!pumping.add(sessionId)) return
        try {
            mailbox.deliverQueued(sessionId, this)
            // A message addressed to an AGENT ID is keyed by that id, not by the session id, so a
            // session coming to rest must ALSO drain the queue addressed to its own agent profile.
            // Without this, an agent-id-addressed message deferred while the recipient was busy would
            // sit QUEUED forever: the status event only ever names the session id. `deliver` then
            // resolves the agent id back to this session.
            //
            // EXACTLY-ONCE GUARD: the agent-alias drain must also be registered in the pumping set.
            // Without this, `deliver()` → `resume()` → status event → `pump(sessionId)` re-enters
            // and calls `deliverQueued(agentId)` again while the outer `deliverQueued(agentId)` is
            // still inside `deliver()` (before markDeliveredLocked), so the same message is handed
            // to the console twice. Adding agentId here blocks the nested drain.
            manager.get(sessionId)?.agentId?.let { agentId ->
                if (agentId != sessionId && pumping.add(agentId)) {
                    try {
                        mailbox.deliverQueued(agentId, this)
                    } finally {
                        pumping.remove(agentId)
                    }
                }
            }
        } finally {
            pumping.remove(sessionId)
        }
    }

    override fun deliver(message: Message): DeliveryOutcome {
        // Resolve agent profile ID to live session ID
        val resolution = resolveRecipient(message.to, message.from, message.id in crossAllowed)
        if (resolution == null) {
            return DeliveryOutcome.Failed("unknown recipient session '${message.to}'")
        }
        if (resolution is RecipientResolution.NoLiveSession) {
            return DeliveryOutcome.Failed("agent ${resolution.agentId} has no running session — start it first")
        }
        if (resolution is RecipientResolution.Failed) {
            return DeliveryOutcome.Failed(resolution.reason)
        }
        val resolved = resolution as RecipientResolution.Resolved

        val latest = manager.get(resolved.sessionId)
            ?: return DeliveryOutcome.Failed("unknown recipient session '${resolved.sessionId}'")
        // BUG-S3: mail addressed to the AGENT (not to one conversation by its session id) follows the agent's current
        // harness. A session at rest on a harness the agent was swapped away from (a cross-harness swap during a run
        // answers `none`) is left in history; the message opens a fresh session on the new harness instead.
        val session = if (resolved.viaAgent && !latest.isBusy()) {
            try {
                freshSessionIfHarnessMoved(latest) ?: latest
            } catch (e: Exception) {
                return DeliveryOutcome.Failed("a new session for agent '${latest.agentId}' could not be started: ${e.message}")
            }
        } else {
            latest
        }

        if (session.isBusy()) {
            return DeliveryOutcome.Retry("recipient '${session.id}' has a run in flight")
        }
        // TICKET-HANDOVER: a session mid-switch takes its next turn on the new harness, once it has switched.
        if (manager.isHeld(session.id)) {
            return DeliveryOutcome.Retry("recipient '${session.id}' is switching harness")
        }
        // BASICS-KILL-1: waking this session would run it next to another session of the same agent / ticket: queue.
        manager.busyTwin(session.id, session.agentId, session.ticketId)?.let {
            return DeliveryOutcome.Retry("session ${it.id} of the same agent / ticket has a run in flight")
        }
        if (!session.resumable && !session.neverRun) {
            return DeliveryOutcome.Failed(
                "recipient '${session.id}' is not resumable: state=${session.state}, " +
                    "providerSessionId=${session.providerSessionId}; its console never came back",
            )
        }
        return try {
            // A bound-but-never-run session takes its persona and identity with its first turn, exactly as
            // a `prompt` frame gives them (BUG-808ce596: one composer, [firstTurn]).
            val turn = if (session.neverRun) {
                // TRAITS-T5: the persona and traits FROZEN at spawn, never the live profile (a later edit never reaches it).
                // P10-K9: the knowledge counts are read NOW, at delivery, for the session's own project.
                val instance = manager.instance(session.id)
                firstTurn(instance, session.role, session.agentId, render(message), knowledge.section(session.projectId, session.role), skills.section(instance))
            } else {
                render(message)
            }
            manager.resume(session.id, turn)
            DeliveryOutcome.Delivered("resumed ${session.id}")
        } catch (e: Exception) {
            DeliveryOutcome.Failed("resume of '${session.id}' failed: ${e.message}")
        }
    }

    /**
     * Result of resolving a recipient identifier.
     */
    private sealed interface RecipientResolution {
        /** [viaAgent] = the address named an agent (profile id or role), not this exact session. */
        data class Resolved(val sessionId: String, val viaAgent: Boolean = false) : RecipientResolution
        data class NoLiveSession(val agentId: String) : RecipientResolution
        data class Failed(val reason: String) : RecipientResolution
    }

    /**
     * Resolve a recipient identifier to a session ID.
     * - If it's a session ID (matches an existing session), return Resolved with that ID.
     * - If it's an agent profile ID with ANY session (in flight OR at rest), return Resolved with that
     *   session's ID; the busy/at-rest decision is [deliver]'s (Retry vs resume), not this resolver's.
     * - If it's an agent profile ID with no session at all (but the agent is known), return NoLiveSession.
     * - If it's a role name (e.g. "pm"), resolve among the sessions of the sender's project (BUG-MAIL-2).
     * - If it's unknown entirely, return null.
     */
    private fun resolveRecipient(recipient: String, senderId: String? = null, allowCross: Boolean = false): RecipientResolution? {
        // CP-3: mail never crosses projects. When the sender is bound to a project, a recipient bound to a
        // DIFFERENT one is unreachable, however it is addressed (session id or agent profile id).
        val senderProject = senderId?.let { manager.get(it)?.projectId }?.takeIf { it.isNotBlank() }
        fun foreign(project: String?) = !allowCross && senderProject != null && !project.isNullOrBlank() && project != senderProject

        // BUG-MAIL-2: `starter` is the session that started the sender's ticket, wherever it is.
        val senderStarter = senderId?.let(starterOf)
        if (recipient == TicketContextComposer.STARTER) {
            if (senderStarter == null || manager.get(senderStarter) == null) {
                return RecipientResolution.Failed("no starter recorded for your ticket (it was not started from a session)")
            }
            return RecipientResolution.Resolved(senderStarter)
        }
        // A worker and the session that started its ticket are each other's route across projects, both ways.
        fun linked(target: String) = senderId != null && (senderStarter == target || starterOf(target) == senderId)

        // First check if it's a direct session ID
        manager.get(recipient)?.let { target ->
            if (foreign(target.projectId) && !linked(target.id)) {
                return RecipientResolution.Failed("recipient '$recipient' is in another project")
            }
            return RecipientResolution.Resolved(recipient)
        }

        // Check if it's a known agent profile ID
        val agentProfile = agentProfiles.get(recipient)
        if (agentProfile != null) {
            if (foreign(agentProfile.projectId)) return RecipientResolution.Failed("agent '$recipient' is in another project")
            // It's a valid agent profile ID. Resolve to the agent's session whether it is in flight (BUSY)
            // or at rest and resumable. A message to a BUSY colleague must QUEUE (the `isBusy` branch in
            // [deliver]), never be refused as "no running session": a colleague at work is not one that
            // does not exist. Only an agent with NO session at all is [RecipientResolution.NoLiveSession].
            // Prefer the session in flight, else the most recently started resumable one.
            val sessions = manager.list()
                .filter { it.agentId == recipient }
                .sortedByDescending { it.spawnedAt }
            val target = sessions.firstOrNull { it.live } ?: sessions.firstOrNull { it.resumable || it.neverRun }
            if (target != null) return RecipientResolution.Resolved(target.id, viaAgent = true)
            return RecipientResolution.NoLiveSession(recipient)
        }

        // Not a session ID, not an agent profile ID -> a ROLE, resolved among the agents of the sender's project.
        // BUG-MAIL-2 (owner's rule): those are the project's own agents -- the sessions opened in it ("New Agent",
        // with or without a profile, and ticket workers) -- never the profile store ("New Profile"). A profile with
        // the role but no session in the project is not one of them, so it no longer answers a role.
        if (senderProject != null) {
            val matches = manager.list()
                .filter { it.projectId == senderProject && it.role == recipient && it.isInSession() }
                .groupBy { it.agentId ?: it.id }
                .map { (_, sessions) ->
                    val newest = sessions.sortedByDescending { it.spawnedAt }
                    newest.firstOrNull { it.live } ?: newest.first()
                }
            return when {
                matches.size == 1 -> RecipientResolution.Resolved(matches[0].id, viaAgent = true)
                matches.isEmpty() -> RecipientResolution.Failed("no $recipient agent in this project")
                else -> {
                    val ids = matches.map { it.agentId ?: it.id }.joinToString(", ")
                    RecipientResolution.Failed("multiple $recipient agents in this project: $ids")
                }
            }
        }

        return null // Not a session ID, not an agent profile, no project context -> unknown
    }

    /**
     * A fresh session for [session]'s agent when [session] runs on a harness other than the agent's stored executor
     * provider; null when it is on the right harness, has no agent, or no binder is wired.
     */
    private fun freshSessionIfHarnessMoved(session: SessionRecord): SessionRecord? {
        val bind = bindFreshSession ?: return null
        val agentId = session.agentId ?: return null
        // BASICS-KILL-1: a ticket worker runs on its TICKET's provider/model, which may differ from the profile's on
        // purpose. Cutting a fresh profile-harness session here put a second session (other harness, same agent id) in
        // the ticket's worktree next to the real worker. A ticket session keeps its harness; mail resumes it.
        if (session.ticketId != null) return null
        val profile = agentProfiles.get(agentId) ?: return null
        val wanted = agentProfiles.executorFor(profile, null, null, null).provider ?: manager.providerId
        if (session.provider == wanted) return null
        return bind(agentId)
    }

    /**
     * Open needs-input signals, filtered to agents that are genuinely idle. An agent mid-run is not
     * "waiting on you" -- it is working, and saying otherwise is the false signal that erodes trust.
     */
    fun waiting(): List<WaitingOn> = mailbox.waiting().filter { w ->
        val session = manager.get(w.session) ?: return@filter false
        !session.isBusy()
    }

    /**
     * Render a message as the prompt the recipient's CLI will read.
     *
     * It states who sent it, prints any checkpoint, and -- crucially -- gives the recipient the exact
     * `agenthq` commands that answer it, and the MCP tools that do the same, so a handoff is completed
     * unattended with no human relaying anything. MAIL-FOOTER-1: this used to print `curl` recipes
     * against the HTTP mail API, and cmd.exe does not treat '...' as quotes, so their JSON reached the
     * daemon mangled ("malformed mail_read payload"). The CLI takes plain arguments that survive any
     * shell. The reply carries the FULL message id, ready to paste; `read` takes the 8-char id.
     */
    private fun render(message: Message): String = buildString {
        append("[Infantry AI COMMS] message ").append(message.id)
        append(" from ").append(message.from)
        append(" · thread ").append(message.threadId)
        if (message.subject.isNotBlank()) append(" · ").append(message.subject)
        append('\n')
        append('\n')
        append(message.body)
        message.checkpoint?.takeUnless { it.isEmpty }?.let { cp ->
            append("\n\n-- checkpoint --\n")
            cp.state?.takeIf { it.isNotBlank() }?.let { append("state: ").append(it).append('\n') }
            cp.decisions?.takeIf { it.isNotBlank() }?.let { append("decisions: ").append(it).append('\n') }
            cp.evidence?.takeIf { it.isNotBlank() }?.let { append("evidence: ").append(it).append('\n') }
            cp.blockers?.takeIf { it.isNotBlank() }?.let { append("blockers: ").append(it).append('\n') }
            cp.nextAction?.takeIf { it.isNotBlank() }?.let { append("next: ").append(it).append('\n') }
        }
        append("\n-- answer this message --\n")
        append("mark it read:\n")
        append("  agenthq read ").append(message.id.take(8)).append('\n')
        append("reply (goes back to ").append(message.from).append(" in this same thread):\n")
        append("  agenthq send ").append(message.from).append(" \"<your answer, one line>\" --reply-to ")
        append(message.id).append('\n')
        append("check the reply landed:\n")
        append("  agenthq threads\n")
        append("or with the MCP tools: agenthq_read {\"messageId\":\"").append(message.id).append("\"}, then ")
        append("agenthq_message {\"to\":\"").append(message.from).append("\",\"replyTo\":\"").append(message.id)
        append("\",\"body\":\"<your answer>\"}\n")
        append("all mail commands: agenthq mail\n")
    }
}
