// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.mailbox

import com.superiorsoftllc.infantry.session.InfantryEnv

import com.superiorsoftllc.infantry.agents.AgentProfiles
import com.superiorsoftllc.infantry.agents.KnowledgeCounter
import com.superiorsoftllc.infantry.agents.SkillsSource
import com.superiorsoftllc.infantry.agents.firstTurn
import com.superiorsoftllc.infantry.agents.section
import com.superiorsoftllc.infantry.session.SessionEvent
import com.superiorsoftllc.infantry.session.SessionManager
import com.superiorsoftllc.infantry.session.SessionRecord
import com.superiorsoftllc.infantry.session.isAvailable
import com.superiorsoftllc.infantry.session.isBusy
import com.superiorsoftllc.infantry.session.isInSession
import java.util.concurrent.ConcurrentHashMap

/**
 * The bridge between the [Mailbox] and live sessions: it turns "a message is due" into "that
 * agent's console runs it".
 *
 * A message reaches a recipient the only way Infantry has to give an agent a turn: by RESUMING its
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
     * knowing the daemon's internals. Overridable with `INFANTRY_MAIL_BASE` for a non-local daemon.
     * MAIL-FOOTER-1: no longer rendered (the footer prints CLI commands, not curl); kept so the
     * daemon's wiring and its callers do not change with the footer.
     */
    private val mailBaseUrl: String = InfantryEnv.get("INFANTRY_MAIL_BASE")?.takeIf { it.isNotBlank() }
        ?: "http://127.0.0.1:8787",
    /**
     * When true, the curl commands rendered into a delivered prompt send `Authorization: Bearer
     * $INFANTRY_TOKEN`. The daemon puts that variable in each agent's environment (see [SessionManager]'s
     * `childEnv`), so the API works on a token-gated daemon while the literal token is never written
     * into a prompt. MAIL-FOOTER-1: no longer rendered either; the CLI reads the token itself.
     */
    private val agentsSendToken: Boolean = false,
    /**
     * BUG-S3 (docs/INFANTRY-ROLE-FIRST.md 4.2): cuts a fresh session bound to an agent, at rest, on the agent's CURRENT
     * executor (the daemon wires `bindSessionOnSpawn`). Used when mail addressed to an agent (by id or role) would
     * otherwise resume a session on a harness the agent was swapped away from. Null = never cut one (resume as before).
     */
    private val bindFreshSession: ((agentId: String) -> SessionRecord?)? = null,
    /** TRIGGER-FOOTER-1: the footer template for mail from a trigger / `system` sender (null = the normal answer block). */
    private val automationFooter: (sender: String) -> String? = { MailFooter.DEFAULT },
    /** P10-K9: a never-run recipient's knowledge section, read AT DELIVERY for its own project. */
    private val knowledge: KnowledgeCounter = KnowledgeCounter.NONE,
    /** P10-K15: a never-run recipient's skills section, from its frozen skill ids, bodies read AT DELIVERY. */
    private val skills: SkillsSource = SkillsSource.NONE,
    /**
     * BUG-MAIL-2: the session that started the ticket a worker session runs (null = none recorded). It makes
     * `starter` an address, and lets a worker and its starter mail each other across projects (CP-3's one exception).
     */
    private val starterOf: (workerSessionId: String) -> String? = { null },
    /** USAGE-REGRESS-1: how many further queued messages ride in the same wake turn (0 = one wake per message). */
    private val coalesceMax: () -> Int = { 10 },
    /** USAGE-REGRESS-1: `real` | `all` | `never` for a recipient role / session (settings `mail.wakeMode`). */
    private val wakeMode: (role: String, sessionId: String) -> String = { _, _ -> "all" },
    /** USAGE-REGRESS-1: ms a waking message waits for company before it wakes a resting session (0 = at once). */
    private val wakeDebounceMs: () -> Long = { 0L },
    /**
     * OVERNIGHT-1: HARD upper bound (ms) on how long any queued mail waits to wake a resting session, whatever [wakeMode] and the
     * debounce say: past it the message wakes the session, and a timer pumps it at that moment. No setting can switch this off.
     */
    private val wakeMaxMs: () -> Long = { 120_000L },
    private val clock: () -> Long = System::currentTimeMillis,
    /** MAIL-TRUNC-1: `notify` | `truncate` | `full` for a recipient role / session (settings `mail.deliveryMode`). Default here `full`; the daemon wires the setting. */
    private val deliveryMode: (role: String, sessionId: String) -> String = { _, _ -> "full" },
    /** MAIL-TRUNC-1: per-body cap (chars) in `truncate` mode. */
    private val injectMaxChars: () -> Int = { 2000 },
    /** MAIL-TRUNC-1: cap (chars) of the whole folded turn in `truncate` mode; mail past it is listed as notify lines. */
    private val injectTotalChars: () -> Int = { 6000 },
    /** USAGE-OUT-2: context cap (tokens) for a recipient role / session; 0 = off. Past it a wake restarts the session fresh. */
    private val contextCap: (role: String, sessionId: String) -> Long = { _, _ -> 0L },
    /** USAGE-OUT-2: the session's last request context size (tokens), or null when unknown. */
    private val contextTokens: (SessionRecord) -> Long? = { null },
    /** XPROJ-1: the projects (id, name) a `role@project` address and the sender-project origin field resolve against. */
    private val projectRefs: () -> List<ProjectRef> = { emptyList() },
) : MailDelivery {

    /** XPROJ-1: a project as mail addressing sees it. */
    data class ProjectRef(val id: String, val name: String)

    private val debounceTimer by lazy {
        java.util.concurrent.Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "mail-wake-debounce").apply { isDaemon = true } }
    }
    private val debouncing = ConcurrentHashMap.newKeySet<String>()

    /** USAGE-REGRESS-1: may [message] start a wake? `never` no; `real` not automation mail; `all` yes. */
    override fun wakes(message: Message): Boolean {
        // OVERNIGHT-1: a message that has waited the hard bound ALWAYS wakes, whatever the mode says.
        if (clock() - message.sentAt >= maxWait()) return true
        val session = (resolveRecipient(message.to, message.from, message.id in crossAllowed) as? RecipientResolution.Resolved)
            ?.let { manager.get(it.sessionId) } ?: return true
        return when (wakeMode(session.role ?: "", session.id)) {
            "never" -> false
            "real" -> !MailFooter.isAutomation(message.from) || isBgFinal(message)
            else -> true
        }
    }

    private fun maxWait(): Long = wakeMaxMs().coerceAtLeast(1L)

    /** OVERNIGHT-1: pump [to] when its oldest message reaches the hard bound (idempotent; one timer per recipient and bound). */
    private fun scheduleBound(to: String, sentAt: Long) {
        val due = (sentAt + maxWait() - clock()).coerceAtLeast(0L)
        debounceTimer.schedule({ runCatching { pump(to) } }, due + 50, java.util.concurrent.TimeUnit.MILLISECONDS)
    }

    /**
     * USAGE-REGRESS-1: a bg job's FINAL notice (exit / ended without an exit code / the timeout notice) still wakes the
     * session that started it: an idle session waiting on `bg run --notify` has nothing else to start its next turn.
     * In-between notices (pattern matches, quiet, heartbeat, closed output) are bundled into the next turn.
     */
    private fun isBgFinal(message: Message): Boolean =
        message.from.equals("system", ignoreCase = true) && message.threadId.startsWith("bg:") &&
            BG_FINAL.containsMatchIn(message.subject)

    private companion object { val BG_FINAL = Regex("""bg job \S+ (finished|ended without an exit code|still running after)""")
        const val MAIL_FOLD_SEPARATOR = "\n\n---\n\n" }

    /** DAEMON-CONTEXT-1: for mail a worker sends its `starter`: a refusal reason (a done report without Files read) or null. */
    @Volatile
    var reportCheck: ((from: String, body: String) -> String?)? = null

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
        // OVERNIGHT-1: whatever is still queued (young, or held by the mode) wakes at the hard bound.
        for (session in manager.list()) if (session.isAvailable()) {
            mailbox.inbox(session.id).filter { it.state == MessageState.QUEUED }.minOfOrNull { it.sentAt }?.let { scheduleBound(session.id, it) }
        }
    }

    /**
     * Send and, if it was accepted, immediately try to deliver. The send itself is persisted by the
     * mailbox before this returns, so the pump can only ever be a bonus, never a precondition.
     */
    /** SCRIPT-3: told after every accepted send (the daemon publishes `mail.sent`); a failing listener never fails the send. */
    @Volatile var onSent: ((Message) -> Unit)? = null

    /**
     * MAIL-HOOK-1: asked after [onSent], BEFORE any wake / injection, with the mail and its resolved recipient. A verdict with
     * `handled` marks the mail read and wakes nobody; one with `text` replaces the delivered text of that one mail. Null, a
     * throw or no hook = delivery as today: a failing hook never drops a mail.
     */
    @Volatile var onIncoming: ((IncomingMailInfo) -> MailVerdictInfo?)? = null

    /** What [onIncoming] sees: the stored [message] and the live session it resolves to (null when it does not resolve). */
    data class IncomingMailInfo(
        val message: Message, val recipientSessionId: String?, val recipientRole: String?, val projectId: String? = null,
        /** XPROJ-1: the sender's project when it differs from the recipient's (cross-project mail); null otherwise. */
        val fromProjectId: String? = null, val fromProjectName: String? = null,
    )

    /** [handled]: read, nobody woken. [text]: replaces the delivered text of this mail. */
    data class MailVerdictInfo(val handled: Boolean, val text: String? = null)

    /** Replacement texts a hook gave, by message id; consumed at delivery. */
    private val replacementText = ConcurrentHashMap<String, String>()

    private fun incomingVerdict(message: Message): MailVerdictInfo? {
        val hook = onIncoming ?: return null
        return runCatching {
            val resolved = (resolveRecipient(message.to, message.from, message.id in crossAllowed) as? RecipientResolution.Resolved)?.let { manager.get(it.sessionId) }
            val senderProject = manager.get(message.from)?.projectId?.takeIf { it.isNotBlank() }
            val foreign = senderProject != null && resolved?.projectId != senderProject
            val fromName = if (foreign) projectRefs().firstOrNull { it.id == senderProject }?.name else null
            hook(IncomingMailInfo(message, resolved?.id, resolved?.role, resolved?.projectId, if (foreign) senderProject else null, fromName))
        }.getOrNull()
    }

    fun send(original: MailSendRequest): MailResult {
        val request = when (val a = addressByRoleAndProject(original)) {
            is RoleAt.Refused -> return MailResult.Refused(a.reason)
            is RoleAt.Resolved -> original.copy(to = a.sessionId)
            RoleAt.NotRoleAt -> original
        }
        if (request.to == MailAddresses.STARTER) reportCheck?.invoke(request.from, request.body)?.let { return MailResult.Refused(it) }
        crossProjectRefusal(request)?.let { return MailResult.Refused(it) }
        val result = mailbox.send(addressStarter(request))
        if (result is MailResult.Sent) {
            runCatching { onSent?.invoke(result.message) }
            if (request.allowCrossProject) crossAllowed.add(result.message.id)
            // MAIL-HANDLED-WAKE-1: while the hook decides, a pump (session rest, debounce, bound timer) must not hand this mail over.
            mailbox.hold(result.message.id)
            val verdict = try { incomingVerdict(result.message) } finally { mailbox.release(result.message.id) }
            if (verdict != null && verdict.handled) {
                if (mailbox.markHandled(result.message.id) != null) {
                    // SCRIPT-VIS-1: a mail a hook handled saved the recipient a turn.
                    (resolveRecipient(result.message.to, result.message.from, result.message.id in crossAllowed) as? RecipientResolution.Resolved)
                        ?.let { r -> runCatching { manager.wakeSink?.invoke(r.sessionId, "mail-handled", result.message.id) } }
                    return result
                }
            } else if (verdict?.text != null) replacementText[result.message.id] = verdict.text
            // Pump by the ORIGINAL recipient address (session id, agent id, or role name), because
            // that is how the message is keyed in the mailbox. `deliver` resolves it to a session.
            // OVERNIGHT-1: the debounce never exceeds the hard bound.
            val delay = wakeDebounceMs().coerceAtMost(maxWait())
            if (delay > 0 && wakes(result.message)) {
                if (debouncing.add(result.message.to)) {
                    val to = result.message.to
                    debounceTimer.schedule({ debouncing.remove(to); runCatching { pump(to) } }, delay, java.util.concurrent.TimeUnit.MILLISECONDS)
                }
            } else {
                pump(result.message.to)
                // A message the mode would not wake for (automation mail under `real`/`never`) still wakes at the bound.
                if (mailbox.message(result.message.id)?.state == MessageState.QUEUED) scheduleBound(result.message.to, result.message.sentAt)
            }
        }
        return result
    }

    /**
     * Messages sent with `allowCrossProject`, so delivery's CP-3 check lets them through. In memory: a restart
     * between send and delivery of a still-queued override message makes it UNDELIVERABLE (visible, resend it).
     */
    private val crossAllowed = ConcurrentHashMap.newKeySet<String>()

    private sealed interface RoleAt {
        data object NotRoleAt : RoleAt
        data class Resolved(val sessionId: String) : RoleAt
        data class Refused(val reason: String) : RoleAt
    }

    /**
     * XPROJ-1: `role@project` (project = id or exact, case-insensitive name) names the role's session in that project, so no session id is
     * needed. A target in another project than the sender's still needs `allowCrossProject`. A clear refusal when the project is unknown or
     * ambiguous, or when the role has no live session there or several agents. An address that is itself a session or agent id is left alone.
     */
    private fun addressByRoleAndProject(request: MailSendRequest): RoleAt {
        val at = request.to.indexOf('@')
        if (at <= 0 || at == request.to.length - 1 || manager.get(request.to) != null || agentProfiles.get(request.to) != null) return RoleAt.NotRoleAt
        val role = request.to.substring(0, at).trim()
        val key = request.to.substring(at + 1).trim()
        val refs = projectRefs()
        val byId = refs.filter { it.id == key }
        val matches = byId.ifEmpty { refs.filter { it.name.equals(key, ignoreCase = true) } }
        if (matches.isEmpty()) return RoleAt.Refused("no project '$key' (look one up with infantry_projects)")
        if (matches.size > 1) return RoleAt.Refused("project name '$key' is ambiguous: ${matches.joinToString(", ") { it.id }}; use the project id")
        val project = matches.single()
        val senderProject = manager.get(request.from)?.projectId?.takeIf { it.isNotBlank() }
        if (!request.allowCrossProject && senderProject != null && senderProject != project.id) {
            return RoleAt.Refused("cross-project mail refused: '${request.to}' is in project ${project.id} (${project.name}) but sender '${request.from}' is in project $senderProject; pass allowCrossProject (--cross-project) to send anyway")
        }
        val agents = manager.list()
            .filter { it.projectId == project.id && it.role == role && it.isInSession() }
            .groupBy { it.agentId ?: it.id }
            .map { (_, sessions) -> sessions.sortedByDescending { it.spawnedAt }.let { s -> s.firstOrNull { it.live } ?: s.first() } }
        return when {
            agents.isEmpty() -> RoleAt.Refused("no live $role session in project ${project.id} (${project.name})")
            agents.size > 1 -> RoleAt.Refused("multiple $role agents in project ${project.id} (${project.name}): ${agents.joinToString(", ") { it.agentId ?: it.id }}; address one by session id")
            else -> RoleAt.Resolved(agents.single().id)
        }
    }

    /**
     * Send-time cross-project guard: a SESSION sender addressing a session or agent id of another project is refused
     * naming both projects, unless it passes `allowCrossProject` or the pair is a worker and its starter. Senders
     * without a session (owner, user, system) and role names are not affected (roles resolve in the sender's project).
     */
    private fun crossProjectRefusal(request: MailSendRequest): String? {
        if (request.allowCrossProject || request.from.isBlank() || request.to == MailAddresses.STARTER) return null
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
        if (request.to != MailAddresses.STARTER || request.from.isBlank()) return request
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

    override fun deliver(message: Message): DeliveryOutcome = deliver(message, emptyList())

    override fun deliver(message: Message, rest: List<Message>): DeliveryOutcome {
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
        manager.busyTwin(session.id, session.agentId, manager.bindingOf(session))?.let {
            return DeliveryOutcome.Retry("session ${it.id} of the same agent / ticket has a run in flight")
        }
        // LIMITS: the daemon already runs limits.concurrency.maxSessions sessions: queue until one finishes.
        manager.sessionCapRefusal(session.id)?.let { return DeliveryOutcome.Retry(it) }
        if (!session.resumable && !session.neverRun) {
            return DeliveryOutcome.Failed(
                "recipient '${session.id}' is not resumable: state=${session.state}, " +
                    "providerSessionId=${session.providerSessionId}; its console never came back",
            )
        }
        return try {
            // A bound-but-never-run session takes its persona and identity with its first turn, exactly as
            // a `prompt` frame gives them (BUG-808ce596: one composer, [firstTurn]).
            // USAGE-OUT-2: a long-lived session past its context cap restarts in place with a short handoff, and the
            // pending mail rides in that fresh first turn (a wake re-reads the whole context, so this is the saving).
            var handoff: String? = null
            var session = session
            if (!session.neverRun) {
                val cap = contextCap(session.role ?: "", session.id)
                val used = if (cap > 0) contextTokens(session) else null
                if (used != null && used > cap) {
                    handoff = composeHandoff(session, used, cap)
                    session = manager.restart(session.id)
                }
            }
            val source = MailSource.of(message, manager, automationFooter)
            // USAGE-REGRESS-1: every wake re-reads the whole context; fold the other messages already queued for this
            // session (same recipient, up to [coalesceMax]) into the one turn instead of one wake each.
            // OVERNIGHT-1: EVERYTHING queued for the session goes into this ONE turn (a backlog is never replayed as one turn per item).
            val folded = if (session.neverRun) emptyList() else rest
            val group = listOf(message) + folded
            val replaced = if (group.size == 1) replacementText.remove(message.id) else null
            val mailText = if (replaced != null) replaced else if (group.size > 1) compactTurn(group)
            else renderTurn(deliveryMode(session.role ?: "", session.id), group, source) { MailSource.of(it, manager, automationFooter) }
            val text = if (handoff != null) handoff + MAIL_FOLD_SEPARATOR + mailText else mailText
            val turn = if (session.neverRun) {
                // TRAITS-T5: the persona and traits FROZEN at spawn, never the live profile (a later edit never reaches it).
                // P10-K9: the knowledge counts are read NOW, at delivery, for the session's own project.
                val instance = manager.instance(session.id)
                firstTurn(instance, session.role, session.agentId, text, knowledge.section(session.projectId, session.role), skills.section(instance))
            } else {
                text
            }
            manager.resume(session.id, turn, source.origin, if (replaced != null) "mail-hook-delivered" else "mail", group.joinToString(",") { it.id })
            DeliveryOutcome.Delivered("resumed ${session.id}", folded.map { it.id })
        } catch (e: Exception) {
            DeliveryOutcome.Failed("resume of '${session.id}' failed: ${e.message}")
        }
    }

    /**
     * USAGE-OUT-2: the short handoff for a session restarted past its context cap, composed from the mailbox (no model
     * turn): the last mail it sent (its reports) and the subjects of its last received mail. Bodies are cut.
     */
    internal fun composeHandoff(session: SessionRecord, used: Long, cap: Long): String = buildString {
        append("CONTEXT HANDOFF: your session was restarted fresh because its context (").append(used).append(" tokens) passed the cap (")
            .append(cap).append("). Role notes and project knowledge are reloaded below; the mail below is yours to act on.")
        val sent = mailbox.outbox(session.id).takeLast(3)
        if (sent.isNotEmpty()) {
            append("\nYour last sent mail:")
            for (m in sent) append("\n- to ").append(m.to).append(": ").append(m.subject).append(" | ").append(m.body.take(600).replace('\n', ' '))
        }
        val got = mailbox.inbox(session.id).takeLast(5)
        if (got.isNotEmpty()) {
            append("\nYour last received mail:")
            for (m in got) append("\n- ").append(m.id.take(8)).append(" from ").append(m.from).append(": ").append(m.subject)
        }
        append("\nRead anything in full with `infantry read <id>`; `infantry inbox` lists the rest.")
    }

    /**
     * MAIL-TRUNC-1: the text of one wake turn for [all] (first = the waking message, [first] its source). `notify`: a NEW MAIL (n)
     * block (id, sender, subject per mail, the read command), no body; `truncate`: bodies capped, total capped; `full`: as before.
     */
    internal fun renderTurn(mode: String, all: List<Message>, first: MailSource, sourceOf: (Message) -> MailSource): String {
        return when (mode) {
            "notify" -> notifyBlock(all)
            "truncate" -> {
                val maxBody = injectMaxChars().coerceAtLeast(1)
                var budget = injectTotalChars().coerceAtLeast(1)
                val parts = ArrayList<String>()
                val over = ArrayList<Message>()
                for ((i, m) in all.withIndex()) {
                    if (budget <= 0) { over += m; continue }
                    val cut = if (m.body.length > maxBody)
                        m.copy(body = m.body.take(maxBody) + "\n... [${m.body.length - maxBody} more chars: infantry read ${m.id.take(8)}]") else m
                    val rendered = (if (i == 0) first.withMessage(cut) else sourceOf(cut).withMessage(cut)).render()
                    budget -= rendered.length
                    parts += rendered
                }
                if (over.isNotEmpty()) parts += notifyBlock(over)
                parts.joinToString(MAIL_FOLD_SEPARATOR)
            }
            else -> (listOf(first) + all.drop(1).map(sourceOf)).joinToString(MAIL_FOLD_SEPARATOR) { it.render() }
        }
    }

    /**
     * OVERNIGHT-1: the wake text for several queued messages: ONE line per distinct (from, subject) with the newest id, a count
     * ("x3") when it repeated, and a 1-line preview. Never a full body; `infantry read <id>` has it.
     */
    internal fun compactTurn(all: List<Message>): String {
        val groups = all.sortedWith(compareBy({ it.sentAt }, { it.seq })).groupBy { it.from to it.subject }.values
            .sortedBy { it.last().sentAt }
        return buildString {
            append("NEW MAIL (").append(all.size).append("): read with `infantry read <id>` (MCP: infantry_read)")
            for (g in groups) {
                val m = g.last()
                append("\n- ").append(m.id.take(8)).append(" from ").append(m.from)
                if (m.subject.isNotBlank()) append(": ").append(m.subject.take(120))
                if (g.size > 1) append(" (x").append(g.size).append(")")
                val preview = m.body.lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() }.orEmpty()
                if (preview.isNotEmpty() && preview != m.subject) append(" | ").append(preview.take(100))
            }
        }
    }

    private fun notifyBlock(all: List<Message>): String = buildString {
        append("NEW MAIL (").append(all.size).append("): read with `infantry inbox` / `infantry read <id>` (MCP: infantry_read)")
        for (m in all) {
            append("\n- ").append(m.id.take(8)).append(" from ").append(m.from)
            if (m.subject.isNotBlank()) append(": ").append(m.subject)
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
        if (recipient == MailAddresses.STARTER) {
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
}
