// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.daemon.api.orchestrator

/**
 * ORCH-1 (docs/DAEMON-CORE.md section 6): the ONE seam between the runner side of the daemon (sessions, harnesses, working
 * copies, the watch runner, CLI process handling) and the orchestration side (board and ticket rules, mail rules, policy,
 * usage, accounts). Callers hold an [OrchestratorLink] and nothing else: they never know whether it is answered in this
 * process ([LocalOrchestratorLink]) or by a remote service. Local vs remote is a BINDING chosen once at startup by
 * configuration (`orchestrator.provider = local | <plugin id>`), never an if/else in a caller.
 *
 * Data in and out is plain (strings, booleans, [TicketRef], [LaunchVerdict]); no daemon class crosses it. A binding owns
 * transport, auth, retries and encoding; this file and every caller name no transport (the guard tests fail when they do).
 *
 * Contract (version [CONTRACT_VERSION], minor bump per added operation, major when a meaning changes):
 *  - every operation returns an [OrchestratorReply]; it never throws for an expected failure. A refusal is a VALUE, not an error.
 *  - ERROR MODEL: [OrchestratorReply.Unavailable] = no answer (remote down, timeout, peer version not understood). Each caller
 *    owns and documents its fail mode (see each operation).
 *  - IDEMPOTENCY: every operation is a read, or idempotent on the natural key it names; repeating it after Unavailable is safe.
 *  - ORDERING: operations are independent request/answer pairs; none depends on an earlier one having been delivered. Event
 *    streams (session events, usage records) arrive in a later minor version with a per-session sequence number; consumers
 *    dedupe by (session, seq).
 */
interface OrchestratorLink {
    /** The contract this binding speaks. A caller may log it; it never branches on it. */
    val contractVersion: String get() = CONTRACT_VERSION

    /**
     * MAIL: review a report a worker session mails to its starter. Refused = the text to send back to the worker; accepted = null.
     * Natural key: (sender session, body). Fail mode on Unavailable: fail OPEN (no refusal), a worker is never blocked by a dead service.
     */
    fun reviewReport(from: String, body: String): OrchestratorReply<String?>

    /**
     * BOARD/TICKETS: the session and project a ticket is bound to, null when the ticket is unknown. A read.
     * Fail mode on Unavailable: treat as "no session" (nothing is reaped or stopped on a guess).
     */
    fun ticketOf(ticketId: String): OrchestratorReply<TicketRef?>

    /**
     * BOARD/TICKETS: the agent that started the ticket a worker session runs, null when unknown. A read.
     * Fail mode on Unavailable: treat as "no starter" (the mail router falls back to its non-ticket route).
     */
    fun starterOf(workerSessionId: String): OrchestratorReply<String?>

    /**
     * POLICY/USAGE: may a session launch on [provider]? [unattended] = started by a trigger or rule rather than a person.
     * Natural key: (provider, unattended, ticket); a pure read of limit and budget state.
     * Fail mode on Unavailable: fail to the local default (allow); the runner keeps its own guards.
     */
    fun launchDecision(provider: String, unattended: Boolean, ticketId: String?): OrchestratorReply<LaunchVerdict>

    companion object {
        const val CONTRACT_VERSION = "1.1.0"
    }
}

/** The error model: an answer, or "the orchestrator could not be reached / did not understand", never an exception. */
sealed interface OrchestratorReply<out T> {
    data class Ok<T>(val value: T) : OrchestratorReply<T>

    /** The binding could not get an answer (remote down, timeout, version mismatch). The caller decides its fail mode. */
    data class Unavailable(val reason: String) : OrchestratorReply<Nothing>
}

/** The session and project a ticket is bound to. */
data class TicketRef(val sessionId: String?, val projectId: String?)

/** [kind] is [ALLOW], [WARN] (the run starts anyway) or [REFUSED]; [disposition] names the refusal for the audit line. */
data class LaunchVerdict(val kind: String, val reason: String = "", val disposition: String = "") {
    companion object {
        const val ALLOW = "allow"
        const val WARN = "warn"
        const val REFUSED = "refused"
        val Allow = LaunchVerdict(ALLOW)
    }
}

/** The value of an Ok reply, or [fallback] when the binding was unavailable: the caller's fail mode in one place. */
fun <T> OrchestratorReply<T>.valueOr(fallback: T): T = if (this is OrchestratorReply.Ok) value else fallback

/** The caller's fail-open reading of a refusal-shaped reply: unavailable means "no refusal", the local behaviour stays reachable. */
fun OrchestratorReply<String?>.refusalOrNull(): String? = valueOr(null)

/**
 * The ORCHESTRATOR -> RUNNER interface: the only way orchestrator-side code reaches sessions. The local orchestrator uses it too,
 * so neither side names the other's classes.
 *
 * RUNNER-POOL-2 (docs/RUNNER-POOL.md 2.1): the runner COMMAND contract, version [CONTRACT_VERSION] (minor bump per added
 * operation, major when a meaning changes). The same rules as [OrchestratorLink]: plain data in and out, every command returns an
 * [OrchestratorReply] (a refusal is a value, [OrchestratorReply.Unavailable] = no answer), each is a read or idempotent on the
 * natural key it names. A binding that does not offer an operation answers [OrchestratorReply.Unavailable] (the defaults below), so
 * an older binding keeps compiling and each caller's documented fail mode applies. Callers reach a runner through
 * [RunnerPool.link], never a concrete binding. [LocalRunnerLink] answers in this process.
 */
interface RunnerLink {
    /** The contract this binding speaks. A caller may log it; it never branches on it. */
    val contractVersion: String get() = CONTRACT_VERSION

    /** The ticket a session works on, null for none. */
    fun ticketIdOf(sessionId: String): String?

    /** What the runner can run. A read. Fail mode on Unavailable: the runner is marked `down`. */
    fun hello(): OrchestratorReply<RunnerInfo> = unoffered("hello")

    /**
     * Start (bind) a session as [spec] describes. Natural key: [StartSpec.startId]. Ok carries the new session, or a refusal (a bad
     * spec, an unknown agent or role) as [SessionRef.refusal]. Fail mode on Unavailable: nothing started; the caller reports the start failed.
     */
    fun startSession(spec: StartSpec): OrchestratorReply<SessionRef> = unoffered("startSession")

    /**
     * Stop a session (end its run and close it). Natural key: the session id; stopping a stopped or unknown session answers Ok(false).
     * Fail mode on Unavailable: the binding retries; the caller shows "stop pending".
     */
    fun stopSession(sessionId: String, reason: String? = null, force: Boolean = false): OrchestratorReply<Boolean> = unoffered("stopSession")

    /**
     * Hand one composed mail turn to a resting session (the `MailDelivery` edge, docs/DAEMON-CORE.md 6.3). Natural key: (session id,
     * [MailItem.messageIds]). Fail mode on Unavailable: the mail stays persisted in the orchestrator's mailbox and is redelivered.
     */
    fun deliverMail(sessionId: String, mail: MailItem): OrchestratorReply<MailResult> = unoffered("deliverMail")

    /** The runner's heartbeat. A read. Fail mode on Unavailable: after `runners.lostAfterSec` the runner is `down`. */
    fun health(): OrchestratorReply<RunnerHealth> = unoffered("health")

    /**
     * RUNNER-POOL-6 (1.1.0): the events of the sessions this orchestrator started on the runner, after [sinceSeq] (0 = all kept), in
     * seq order. Natural key: (session, seq); the caller dedupes. A read. Fail mode on Unavailable: asked again from the same seq.
     */
    fun events(sinceSeq: Long): OrchestratorReply<List<RunnerEvent>> = unoffered("events")

    /** RUNNER-POOL-6 (1.1.0): up to [maxLines] (0 = the runner's own bound) transcript lines of a session from [fromLine]; read on demand. */
    fun readTranscript(sessionId: String, fromLine: Int = 0, maxLines: Int = 0): OrchestratorReply<List<String>> = unoffered("readTranscript")

    companion object {
        /** 1.1.0 (RUNNER-POOL-6): events, readTranscript; StartSpec may name a git remote, SessionRef carries the ticket branch. */
        const val CONTRACT_VERSION = "1.1.0"

        private fun unoffered(op: String): OrchestratorReply<Nothing> = OrchestratorReply.Unavailable("this runner binding does not offer '$op'")
    }
}

/**
 * The orchestrator-side functions the local binding answers from. The daemon builds one from its board, ticket files and launch
 * gate; the local binding wraps it unchanged, so local-only behaviour is the same functions called the same way.
 */
class LocalOrchestratorServices(
    val reviewReport: (sessionTicketId: String?, from: String, body: String) -> String?,
    val ticketOf: (ticketId: String) -> TicketRef?,
    val starterOf: (workerSessionId: String) -> String?,
    val launchDecision: (provider: String, unattended: Boolean, ticketId: String?) -> LaunchVerdict,
)

/** The binding for `orchestrator.provider = local`: answers in this process. */
class LocalOrchestratorLink(
    private val runner: RunnerLink,
    private val services: LocalOrchestratorServices,
) : OrchestratorLink {
    override fun reviewReport(from: String, body: String): OrchestratorReply<String?> =
        OrchestratorReply.Ok(services.reviewReport(runner.ticketIdOf(from)?.takeIf { it.isNotBlank() }, from, body))

    override fun ticketOf(ticketId: String): OrchestratorReply<TicketRef?> = OrchestratorReply.Ok(services.ticketOf(ticketId))
    override fun starterOf(workerSessionId: String): OrchestratorReply<String?> = OrchestratorReply.Ok(services.starterOf(workerSessionId))
    override fun launchDecision(provider: String, unattended: Boolean, ticketId: String?): OrchestratorReply<LaunchVerdict> =
        OrchestratorReply.Ok(services.launchDecision(provider, unattended, ticketId))
}

/** A plugin's binding: builds the link for its provider id from the runner and the local link (its fallback). */
fun interface OrchestratorBinding {
    fun create(runner: RunnerLink, local: OrchestratorLink): OrchestratorLink
}

/**
 * The binding registry plus the link every caller holds. Callers get [link] at construction and keep it for ever; startup (or a
 * plugin registering) calls [select] to point it at the configured provider. An unknown provider keeps the previous binding and
 * is reported, so a typo never leaves the daemon without an orchestrator.
 */
class OrchestratorHub(private val local: OrchestratorLink, private val runner: RunnerLink) {
    private val bindings = java.util.concurrent.ConcurrentHashMap<String, OrchestratorBinding>()

    @Volatile private var target: OrchestratorLink = local

    @Volatile var provider: String = LOCAL
        private set

    /** The link callers hold: delegates to the selected binding. */
    val link: OrchestratorLink = object : OrchestratorLink {
        override val contractVersion: String get() = target.contractVersion
        override fun reviewReport(from: String, body: String) = target.reviewReport(from, body)
        override fun ticketOf(ticketId: String) = target.ticketOf(ticketId)
        override fun starterOf(workerSessionId: String) = target.starterOf(workerSessionId)
        override fun launchDecision(provider: String, unattended: Boolean, ticketId: String?) = target.launchDecision(provider, unattended, ticketId)
    }

    fun register(providerId: String, binding: OrchestratorBinding) {
        require(providerId != LOCAL) { "'$LOCAL' is the built-in binding" }
        bindings[providerId] = binding
    }

    /** Point [link] at [providerId]; returns null when selected, or the reason it stayed on the previous binding. */
    fun select(providerId: String): String? {
        if (providerId.isBlank() || providerId == LOCAL) { target = local; provider = LOCAL; return null }
        val b = bindings[providerId] ?: return "orchestrator.provider '$providerId' is not registered; staying on '$provider'"
        target = runCatching { b.create(runner, local) }.getOrElse { return "orchestrator binding '$providerId' failed to start: ${it.message}; staying on '$provider'" }
        provider = providerId
        return null
    }

    companion object { const val LOCAL = "local" }
}
