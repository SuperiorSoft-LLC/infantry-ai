// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.daemon.api.orchestrator

/**
 * RUNNER-POOL-2 (docs/RUNNER-POOL.md 2.1, 2.2): the plain data of the [RunnerLink] command contract, the local binding and the
 * pool the orchestrator side reaches runners through. Today the pool holds exactly one runner, [RunnerPool.LOCAL]; remote runners
 * are bindings a plugin registers (`api.hooks.runnerBinding`) and arrive in a later step. Nothing here names a transport.
 */

/** What a runner can run, answered by [RunnerLink.hello]. */
data class RunnerInfo(
    val name: String,
    val contract: String,
    val harnesses: List<RunnerHarness> = emptyList(),
    val projects: List<String> = emptyList(),
    /** Sessions the runner may run at once; 0 = no cap. */
    val capacity: Int = 0,
    val os: String = "",
)

/** One harness a runner offers: its id, its models, whether its CLI is signed in. */
data class RunnerHarness(val id: String, val models: List<String> = emptyList(), val loggedIn: Boolean = false)

/**
 * A session start. [startId] is the natural key (a ticket id plus attempt, or a fresh id for a spawn): a binding that is asked
 * twice for one [startId] starts one session. A null field is "the runner's default" (the agent profile, its role binding, the
 * project's routing rules), resolved on the runner exactly as a local spawn resolves it.
 */
data class StartSpec(
    val startId: String,
    val agentId: String? = null,
    val role: String? = null,
    val projectId: String? = null,
    val provider: String? = null,
    val model: String? = null,
    val effort: String? = null,
    val contextLimit: Int? = null,
    val ticketId: String? = null,
    val parentSessionId: String? = null,
    /** The repository path ON THE RUNNER the session's working copy is cut from; null = none (the runner's default directory). */
    val repo: String? = null,
)

/**
 * The answer to [RunnerLink.startSession]: the session started ([sessionId]), a refusal ([refusal], the spec named an unknown agent
 * or role, a project it does not belong to, ...), or neither when the spec names no agent and no role (nothing to bind yet).
 */
data class SessionRef(val sessionId: String? = null, val refusal: String? = null)

/**
 * One composed mail turn for [RunnerLink.deliverMail]. [origin] is the sender as the wire's `MessageOrigin` JSON (so the contract
 * names no protocol class); [wakeReason] is the audit word (`mail`, `mail-hook-delivered`); [messageIds] are the mailbox ids folded
 * into this one turn.
 */
data class MailItem(val text: String, val origin: String, val wakeReason: String, val messageIds: List<String>)

/** [kind] is [DELIVERED], [QUEUED] (the session has a run in flight: keep it queued) or [FAILED] ([reason] says why). */
data class MailResult(val kind: String, val reason: String = "") {
    companion object {
        const val DELIVERED = "delivered"
        const val QUEUED = "queued"
        const val FAILED = "failed"
        val Delivered = MailResult(DELIVERED)
    }
}

/** A runner's heartbeat: [state] `up` | `draining` | `down`, sessions [running], [freeSlots] (-1 = no cap), [load] (0..1, null = unknown). */
data class RunnerHealth(val state: String, val running: Int = 0, val freeSlots: Int = -1, val load: Double? = null) {
    companion object {
        const val UP = "up"
        const val DRAINING = "draining"
        const val DOWN = "down"
    }
}

/**
 * The runner-side functions the local binding answers from. The daemon builds one over its session manager and mail delivery; the
 * local binding wraps it unchanged, so local behaviour is the same functions called with the same arguments.
 * [start] returns the new session id (null = the spec names no agent and no role) and throws [IllegalArgumentException] for a
 * refused spec, exactly as the local spawn does; [stop] returns false for an unknown session.
 */
class LocalRunnerServices(
    val ticketIdOf: (sessionId: String) -> String?,
    val info: () -> RunnerInfo,
    val start: (spec: StartSpec) -> String?,
    val stop: (sessionId: String, reason: String?, force: Boolean) -> Boolean,
    val deliver: (sessionId: String, mail: MailItem) -> MailResult,
    val health: () -> RunnerHealth,
)

/** The binding for the runner in this process ([RunnerPool.LOCAL]). */
class LocalRunnerLink(private val services: LocalRunnerServices) : RunnerLink {
    override fun ticketIdOf(sessionId: String): String? = services.ticketIdOf(sessionId)
    override fun hello(): OrchestratorReply<RunnerInfo> = OrchestratorReply.Ok(services.info())

    override fun startSession(spec: StartSpec): OrchestratorReply<SessionRef> =
        try {
            OrchestratorReply.Ok(SessionRef(sessionId = services.start(spec)))
        } catch (e: IllegalArgumentException) {
            OrchestratorReply.Ok(SessionRef(refusal = e.message ?: "start refused"))
        }

    override fun stopSession(sessionId: String, reason: String?, force: Boolean): OrchestratorReply<Boolean> =
        OrchestratorReply.Ok(services.stop(sessionId, reason, force))

    override fun deliverMail(sessionId: String, mail: MailItem): OrchestratorReply<MailResult> = OrchestratorReply.Ok(services.deliver(sessionId, mail))
    override fun health(): OrchestratorReply<RunnerHealth> = OrchestratorReply.Ok(services.health())
}

/** A runner the pool knows: its [name], the [binding] id that serves it (`local` for this process), its last [health] and [info]. */
data class RunnerEntry(val name: String, val binding: String, val health: RunnerHealth? = null, val info: RunnerInfo? = null)

/** The orchestrator-side view of every runner (docs/RUNNER-POOL.md 2.2). [LOCAL] is always present. */
interface RunnerPool {
    /** Every runner, [LOCAL] first. */
    fun runners(): List<RunnerEntry>

    /** The link to runner [name]; null = unknown runner. */
    fun link(name: String): RunnerLink?

    /** The runner a session lives on (the session index); [LOCAL] for every session today. */
    fun runnerOf(sessionId: String): String

    companion object {
        const val LOCAL = "local"
    }
}

/** A plugin's runner binding: builds the link for a runner it serves, with the local link at hand (offered by `api.hooks.runnerBinding`). */
fun interface RunnerBinding {
    fun create(runnerName: String, local: RunnerLink): RunnerLink
}

/**
 * The pool core holds: the [LOCAL][RunnerPool.LOCAL] runner and the registry of runner bindings plugins offer. In this step no
 * remote runner is configured, so [runners] lists only `local` and every session lives there; a registered binding is kept for the
 * placement step (RUNNER-POOL-3 and later) and changes nothing yet.
 */
class RunnerHub(private val local: RunnerLink) : RunnerPool {
    private val bindings = java.util.concurrent.ConcurrentHashMap<String, RunnerBinding>()

    override fun runners(): List<RunnerEntry> =
        listOf(RunnerEntry(RunnerPool.LOCAL, RunnerPool.LOCAL, local.health().valueOr(null), local.hello().valueOr(null)))

    override fun link(name: String): RunnerLink? = if (name == RunnerPool.LOCAL) local else null
    override fun runnerOf(sessionId: String): String = RunnerPool.LOCAL

    /** Offer a binding under [bindingId]; `local` is the built-in one. */
    fun register(bindingId: String, binding: RunnerBinding) {
        require(bindingId != RunnerPool.LOCAL) { "'${RunnerPool.LOCAL}' is the built-in runner binding" }
        bindings[bindingId] = binding
    }

    /** The binding ids plugins offered, sorted. */
    fun bindingIds(): List<String> = bindings.keys.sorted()
}

/** The link to the runner [sessionId] lives on: the one way an orchestrator-side caller addresses a session's runner. */
fun RunnerPool.linkFor(sessionId: String): RunnerLink? = link(runnerOf(sessionId))
