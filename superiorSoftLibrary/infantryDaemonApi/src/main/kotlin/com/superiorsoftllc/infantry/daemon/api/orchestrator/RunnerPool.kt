// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.daemon.api.orchestrator

/**
 * RUNNER-POOL-2 (docs/RUNNER-POOL.md 2.1, 2.2): the plain data of the [RunnerLink] command contract, the local binding and the
 * pool the orchestrator side reaches runners through. The pool always holds [RunnerPool.LOCAL]; RUNNER-POOL-6 adds the paired
 * remote runners, whose links a plugin's binding builds (`api.hooks.runnerBinding`). Nothing here names a transport.
 */

/**
 * RUNNER-CRED-1 (additive): the credentials an orchestrator LENDS a session it starts on a remote runner, so the runner needs no
 * key of its own (owner rule: a remote runner is `docker run` plus endpoint and bearer, nothing more). [env] is the harness
 * credential as a local launch would merge it (`{ENV: key}`); [secrets] are the keystore secrets the session may render
 * (`{{secret:NAME}}`), name -> fields; [git] is the credential for the ticket's git remote. The runner keeps them in memory for
 * that session only and never writes them to its log, state or volume. [toString] names keys only, never a value, so a log line
 * or an exception message that prints this object leaks nothing.
 */
@kotlinx.serialization.Serializable
data class SessionCredentials(
    val env: Map<String, String> = emptyMap(),
    val secrets: Map<String, Map<String, String>> = emptyMap(),
    val git: GitCredential? = null,
) {
    val isEmpty: Boolean get() = env.isEmpty() && secrets.isEmpty() && git == null
    override fun toString(): String = "SessionCredentials(env=${env.keys.sorted()}, secrets=${secrets.keys.sorted()}, git=${if (git == null) "none" else "set"})"
}

/** RUNNER-CRED-1: an HTTPS git credential (a token, with the user name the host expects; GitHub takes any). Never printed. */
@kotlinx.serialization.Serializable
data class GitCredential(val token: String, val username: String = DEFAULT_USER) {
    override fun toString(): String = "GitCredential(username=$username, token=***)"
    companion object { const val DEFAULT_USER = "x-access-token" }
}

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
    /** RUNNER-POOL-6: the composed first turn; null = an empty bind (a spawn). */
    val prompt: String? = null,
    /** RUNNER-POOL-6: the ticket's title (its branch slug), the base ref a fresh cut takes, and the branch the ticket already holds. */
    val title: String? = null,
    val base: String? = null,
    val branch: String? = null,
    /** RUNNER-POOL-6: the project's git remote a remote runner clones from (docs/RUNNER-POOL.md 4); null = a local start. */
    val gitRemote: String? = null,
    /** RUNNER-POOL-6: environment variable NAMES the session needs; the runner fills the values from its own store. */
    val envNames: List<String> = emptyList(),
    /**
     * RUNNER-POOL-6: a worktree the RUNNER already cut for this start (all paths runner-local); set only on the runner, never from
     * the wire. The session runs in it and none is provisioned.
     */
    val worktree: StartWorktree? = null,
    /**
     * RUNNER-POOL-8: push the ticket branch after each worker commit, not only after each turn (`runners.pushOnCommit`, orchestrator
     * side), so a restart elsewhere loses at most uncommitted edits. Default false (fewer pushes).
     */
    val pushOnCommit: Boolean = false,
    /**
     * RUNNER-GIT-IDENT-1: the git author/committer identity a remote worker commits with (the orchestrator checkout's `user.name` /
     * `user.email`); null = none known. A runner's own `runner.gitName` / `runner.gitEmail` win over it.
     */
    val gitName: String? = null,
    val gitEmail: String? = null,
    /**
     * RUNNER-PAIR-1 (additive): the Tool approval mode (`deny` | `allowEdits` | `allowAll` | `ask`) the orchestrator resolved for
     * this start, the same one its own local worker would run with. The runner applies it to the session, and since it cannot
     * relay a prompt it runs `ask` as `deny` at once (the local "can't prompt" rule). Null = an older orchestrator: the runner
     * uses its own resolved mode under the same rule.
     */
    val toolApproval: String? = null,
    /**
     * RUNNER-DOCKER-MATRIX-1 (additive): true when the orchestrator relays this session's prompts to its app
     * (RUNNER-PROMPT-RELAY-1), so the runner keeps `ask` and the tool-approval card shows on the orchestrator. False = an
     * orchestrator without the relay: the runner runs `ask` as `deny`, as above.
     */
    val promptRelay: Boolean = false,
    /**
     * RUNNER-CRED-1 (additive): the credentials lent to this session ([SessionCredentials]); null = none. Filled by the pool for a
     * REMOTE start only ([RunnerHub] `lend`), never for the local runner, which reads its own keystore.
     */
    val credentials: SessionCredentials? = null,
) {
    override fun toString(): String = "StartSpec(startId=$startId, provider=$provider, projectId=$projectId, ticketId=$ticketId, credentials=$credentials)"
}

/** RUNNER-POOL-6: a ticket worktree on the runner: [path], [branch], [base] (a commit), [repoRoot]. */
data class StartWorktree(val path: String, val branch: String, val base: String, val repoRoot: String)

/**
 * The answer to [RunnerLink.startSession]: the session started ([sessionId]), a refusal ([refusal], the spec named an unknown agent
 * or role, a project it does not belong to, ...), or neither when the spec names no agent and no role (nothing to bind yet).
 * RUNNER-POOL-6: [branch] / [base] are the ticket branch a remote runner cut or reused (null for a start with no git remote).
 * RUNNER-WORKTREE-PATH-1: [worktreePath] is where that branch is checked out ON THE RUNNER (a path on the runner's disk, never one
 * on the orchestrator's); null for a start with no worktree, or from a runner too old to send it.
 */
data class SessionRef(
    val sessionId: String? = null,
    val refusal: String? = null,
    val branch: String? = null,
    val base: String? = null,
    val worktreePath: String? = null,
)

/**
 * RUNNER-POOL-6: one event of a session a runner runs for its orchestrator: [kind] [STATUS] (payload = state) or [FINISHED] (payload =
 * exit code). RUNNER-POOL-8: [seq] is monotonic PER SESSION (1, 2, 3 ...), so (session, seq) is the event's natural key.
 * RUNNER-PROMPT-RELAY-1: [PROMPT] (payload = [RunnerPromptEvent.encode]: the runner's `prompt_request` JSON and the call's whole
 * input) and [PROMPT_RESOLVED] (payload = the runner's `prompt_resolved` JSON): a session on the runner asked a human; that prompt closed.
 */
data class RunnerEvent(val sessionId: String, val seq: Long, val kind: String, val payload: String = "") {
    companion object {
        const val STATUS = "status"
        const val FINISHED = "finished"
        const val PROMPT = "prompt"
        const val PROMPT_RESOLVED = "prompt_resolved"
    }
}

/**
 * RUNNER-PROMPT-RELAY-1: the payload of a [RunnerEvent.PROMPT] event as plain data: [request] is the runner's `prompt_request` JSON
 * (the card the owner sees; its id is the runner's prompt id), [input] the call's whole input (a card carries only a summary, and an
 * "Always allow" rule is made from the whole input). [encode] / [decode] are the one JSON form both ends use.
 */
data class RunnerPromptEvent(val request: String, val input: String? = null) {
    fun encode(): String = kotlinx.serialization.json.JsonObject(
        buildMap {
            put("request", kotlinx.serialization.json.JsonPrimitive(request))
            input?.let { put("input", kotlinx.serialization.json.JsonPrimitive(it)) }
        },
    ).toString()

    companion object {
        /** The payload back; null for a payload of another shape (an unknown peer). */
        fun decode(payload: String): RunnerPromptEvent? = runCatching {
            val o = kotlinx.serialization.json.Json.parseToJsonElement(payload) as kotlinx.serialization.json.JsonObject
            val request = (o["request"] as kotlinx.serialization.json.JsonPrimitive).content
            val input = (o["input"] as? kotlinx.serialization.json.JsonPrimitive)?.takeIf { it.isString }?.content
            RunnerPromptEvent(request, input)
        }.getOrNull()
    }
}

/**
 * RUNNER-PROMPT-RELAY-1: the orchestrator's answer to a runner's prompt ([RunnerLink.answerPrompt]), the fields of a client's
 * `prompt_response`: [decision] for a tool approval, [answers] / [otherText] for a question.
 */
data class PromptReply(val promptId: String, val decision: String? = null, val answers: List<String> = emptyList(), val otherText: String? = null)

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

/** A runner's heartbeat: [state] `up` | `degraded` | `draining` | `down`, sessions [running], [freeSlots] (-1 = no cap), [load] (0..1, null = unknown). */
data class RunnerHealth(val state: String, val running: Int = 0, val freeSlots: Int = -1, val load: Double? = null) {
    companion object {
        const val UP = "up"
        /** RUNNER-POOL-6: a late heartbeat, or no harness signed in; takes no new starts. */
        const val DEGRADED = "degraded"
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
    /** RUNNER-POOL-6: the events of link-started sessions after a seq; default none. */
    val events: (sinceSeq: Long) -> List<RunnerEvent> = { emptyList() },
    /** RUNNER-POOL-8: acknowledge (session -> highest applied seq) and read what is still kept; null = filter [events] (0), no ack. */
    val eventsAcked: ((acked: Map<String, Long>) -> List<RunnerEvent>)? = null,
    /** RUNNER-POOL-6: (session, fromLine, maxLines) -> transcript lines; default none. */
    val transcript: (sessionId: String, fromLine: Int, maxLines: Int) -> List<String> = { _, _, _ -> emptyList() },
    /**
     * RUNNER-TEST-1: forget a session for good (the `session_remove` the owner runs by hand): its record and durable row go, as if it
     * had never run. Returns false for an unknown session; a live one is refused with [IllegalArgumentException].
     */
    val remove: (sessionId: String) -> Boolean = { false },
    /**
     * RUNNER-PROMPT-RELAY-1: resolve an open prompt of a session with the orchestrator's answer; false = it was no longer open. An
     * answer that does not fit the prompt is refused with [IllegalArgumentException]. Default: no prompts here (false).
     */
    val answerPrompt: (sessionId: String, reply: PromptReply) -> Boolean = { _, _ -> false },
)

/** The binding for the runner in this process ([RunnerPool.LOCAL]). */
class LocalRunnerLink(private val services: LocalRunnerServices) : RunnerLink {
    override fun ticketIdOf(sessionId: String): String? = services.ticketIdOf(sessionId)
    override fun hello(): OrchestratorReply<RunnerInfo> = OrchestratorReply.Ok(services.info())

    override fun startSession(spec: StartSpec): OrchestratorReply<SessionRef> =
        try {
            OrchestratorReply.Ok(SessionRef(sessionId = services.start(spec), branch = spec.worktree?.branch, base = spec.worktree?.base, worktreePath = spec.worktree?.path))
        } catch (e: IllegalArgumentException) {
            OrchestratorReply.Ok(SessionRef(refusal = e.message ?: "start refused"))
        }

    override fun stopSession(sessionId: String, reason: String?, force: Boolean): OrchestratorReply<Boolean> =
        OrchestratorReply.Ok(services.stop(sessionId, reason, force))

    override fun removeSession(sessionId: String): OrchestratorReply<Boolean> =
        try {
            OrchestratorReply.Ok(services.remove(sessionId))
        } catch (e: IllegalArgumentException) {
            OrchestratorReply.Unavailable(e.message ?: "remove refused")
        }

    override fun deliverMail(sessionId: String, mail: MailItem): OrchestratorReply<MailResult> = OrchestratorReply.Ok(services.deliver(sessionId, mail))
    override fun health(): OrchestratorReply<RunnerHealth> = OrchestratorReply.Ok(services.health())
    override fun events(sinceSeq: Long): OrchestratorReply<List<RunnerEvent>> = OrchestratorReply.Ok(services.events(sinceSeq))
    override fun events(acked: Map<String, Long>): OrchestratorReply<List<RunnerEvent>> =
        services.eventsAcked?.let { OrchestratorReply.Ok(it(acked)) } ?: super.events(acked)
    override fun readTranscript(sessionId: String, fromLine: Int, maxLines: Int): OrchestratorReply<List<String>> =
        OrchestratorReply.Ok(services.transcript(sessionId, fromLine, maxLines))

    override fun answerPrompt(sessionId: String, reply: PromptReply): OrchestratorReply<Boolean> =
        try {
            OrchestratorReply.Ok(services.answerPrompt(sessionId, reply))
        } catch (e: IllegalArgumentException) {
            OrchestratorReply.Unavailable(e.message ?: "answer refused")
        }
}

/**
 * A runner the pool knows: its [name], the [binding] id that serves it (`local` for this process), its last [health] and [info],
 * and (RUNNER-POOL-6) the pool's verdict [state] from the heartbeat: `up` | `degraded` | `down`. Only `up` takes new starts.
 */
data class RunnerEntry(
    val name: String,
    val binding: String,
    val health: RunnerHealth? = null,
    val info: RunnerInfo? = null,
    val state: String = health?.state ?: RunnerHealth.UP,
)

/** The orchestrator-side view of every runner (docs/RUNNER-POOL.md 2.2). [LOCAL] is always present. */
interface RunnerPool {
    /** Every runner, [LOCAL] first. */
    fun runners(): List<RunnerEntry>

    /** The link to runner [name]; null = unknown runner. */
    fun link(name: String): RunnerLink?

    /** The runner a session lives on (the session index); [LOCAL] unless a remote start placed it elsewhere. */
    fun runnerOf(sessionId: String): String

    /** LOGS-PREFIX-1: the ids of the sessions placed on a remote runner (the session index); empty when none are tracked. */
    fun placedSessionIds(): Collection<String> = emptyList()

    companion object {
        const val LOCAL = "local"
    }
}

/** A plugin's runner binding: builds the link for a runner it serves, with the local link at hand (offered by `api.hooks.runnerBinding`). */
fun interface RunnerBinding {
    fun create(runnerName: String, local: RunnerLink): RunnerLink
}

/**
 * The pool core holds: the [LOCAL][RunnerPool.LOCAL] runner, the registry of runner bindings plugins offer, and (RUNNER-POOL-6) the
 * paired remote runners. [remotes] lists the paired runner names (core's link store); each one's link is built once by the binding
 * a plugin offered ([REMOTE_BINDING] when offered, else the only one). With no binding a paired runner is listed `down`, so
 * placement refuses it with that reason instead of failing later.
 *
 * Health is the pool's verdict, refreshed by [heartbeat] (the daemon calls it every `limits.runners.heartbeatSec`): `up` when the
 * last heartbeat answered and a harness is signed in; `degraded` when it answered with none signed in, or failed less than
 * `lostAfterMs` after the last good one; `down` when silent longer, or never heard. [runnerOf] answers from the session index
 * [placed] fills; every other session lives on `local`.
 */
class RunnerHub(
    private val local: RunnerLink,
    private val remotes: () -> List<String> = { emptyList() },
    private val clock: () -> Long = System::currentTimeMillis,
    /**
     * RUNNER-CRED-1: the credentials to lend a start on remote runner (name, spec), or null to lend none. Applied to every
     * [RunnerLink.startSession] of a remote link this pool hands out, so every start path (board, spawn, test) lends the same way;
     * the local runner is never wrapped. A spec that already carries [StartSpec.credentials] keeps them.
     */
    private val lend: (runner: String, spec: StartSpec) -> SessionCredentials? = { _, _ -> null },
    /**
     * RUNNER-CRED-1: the harness ids whose key this orchestrator lends runner [runner] at each start. In the [RunnerLink.hello] of
     * that runner's link such a harness reads as signed in (installed there, and usable: the key comes with the start), so
     * placement, the runner's health and the app treat it as ready without a login on the runner.
     */
    private val lentKeys: (runner: String) -> Set<String> = { emptySet() },
) : RunnerPool {
    private val bindings = java.util.concurrent.ConcurrentHashMap<String, RunnerBinding>()
    private val links = java.util.concurrent.ConcurrentHashMap<String, Pair<String, RunnerLink>>()
    private val beats = java.util.concurrent.ConcurrentHashMap<String, Beat>()
    private val sessions = java.util.concurrent.ConcurrentHashMap<String, String>()

    /**
     * The last heartbeat of one remote: when it last answered, what it said, and the pool's [state]. RUNNER-POOL-8: [silentSince] is
     * when it stopped answering (null = the last round answered).
     */
    private data class Beat(val okAt: Long?, val health: RunnerHealth?, val info: RunnerInfo?, val state: String, val silentSince: Long? = null)

    override fun runners(): List<RunnerEntry> {
        val binding = bindingFor()?.first
        return listOf(RunnerEntry(RunnerPool.LOCAL, RunnerPool.LOCAL, local.health().valueOr(null), local.hello().valueOr(null), RunnerHealth.UP)) +
            remoteNames().map { name ->
                val beat = beats[name]
                RunnerEntry(name, binding.orEmpty(), beat?.health, beat?.info, if (binding == null) RunnerHealth.DOWN else beat?.state ?: RunnerHealth.DOWN)
            }
    }

    override fun link(name: String): RunnerLink? {
        if (name == RunnerPool.LOCAL) return local
        if (name !in remoteNames()) return null
        val (id, binding) = bindingFor() ?: return null
        links[name]?.takeIf { it.first == id }?.let { return it.second }
        return lending(name, binding.create(name, local)).also { links[name] = id to it }
    }

    override fun runnerOf(sessionId: String): String = sessions[sessionId] ?: RunnerPool.LOCAL

    /** RUNNER-CRED-1: [inner] with [lend] applied to its starts; a lend that fails lends nothing (the start still goes). */
    private fun lending(name: String, inner: RunnerLink): RunnerLink = object : RunnerLink by inner {
        override fun hello(): OrchestratorReply<RunnerInfo> = when (val r = inner.hello()) {
            is OrchestratorReply.Unavailable -> r
            is OrchestratorReply.Ok -> {
                val keys = runCatching { lentKeys(name) }.getOrDefault(emptySet())
                if (keys.isEmpty()) r
                else OrchestratorReply.Ok(r.value.copy(harnesses = r.value.harnesses.map { h -> if (!h.loggedIn && h.id in keys) h.copy(loggedIn = true) else h }))
            }
        }

        override fun startSession(spec: StartSpec): OrchestratorReply<SessionRef> {
            if (spec.credentials != null) return inner.startSession(spec)
            val lent = runCatching { lend(name, spec) }.getOrNull()?.takeIf { !it.isEmpty }
            return inner.startSession(if (lent == null) spec else spec.copy(credentials = lent))
        }
    }

    override fun placedSessionIds(): Collection<String> = sessions.keys.toList()

    /** RUNNER-POOL-7: whether any session placed by this orchestrator lives on runner [runner] (its forwarded frames are pulled only then). */
    fun hasSessionsOn(runner: String): Boolean = runner != RunnerPool.LOCAL && sessions.containsValue(runner)

    /** RUNNER-POOL-8: the sessions this orchestrator placed on runner [runner], sorted. */
    fun sessionsOn(runner: String): List<String> = sessions.entries.filter { it.value == runner }.map { it.key }.sorted()

    /** RUNNER-POOL-8: session [sessionId] is no longer tracked on any runner (it was restarted elsewhere or ended). */
    fun forget(sessionId: String) {
        sessions.remove(sessionId)
    }

    /**
     * RUNNER-POOL-8: since when runner [name] has not answered a heartbeat; null = its last heartbeat answered, or it was never
     * heartbeat-checked yet. A runner never heard since this daemon started counts from its first silent round.
     */
    fun silentSince(name: String): Long? = beats[name]?.silentSince

    /** RUNNER-POOL-6: session [sessionId] was started on runner [runner]; [runnerOf] answers it from now on. */
    fun placed(sessionId: String, runner: String) {
        if (runner == RunnerPool.LOCAL) sessions.remove(sessionId) else sessions[sessionId] = runner
    }

    /** Offer a binding under [bindingId]; `local` is the built-in one. */
    fun register(bindingId: String, binding: RunnerBinding) {
        require(bindingId != RunnerPool.LOCAL) { "'${RunnerPool.LOCAL}' is the built-in runner binding" }
        bindings[bindingId] = binding
        links.clear()
    }

    /** The binding ids plugins offered, sorted. */
    fun bindingIds(): List<String> = bindings.keys.sorted()

    /**
     * RUNNER-POOL-6: one heartbeat round over every paired runner (`health`, then `hello`), then the verdict described on the class.
     * Returns one line per runner whose state changed, for the log.
     */
    fun heartbeat(lostAfterMs: Long): List<String> {
        val now = clock()
        val names = remoteNames()
        beats.keys.retainAll(names.toSet())
        return names.mapNotNull { name ->
            val before = beats[name]
            val link = link(name)
            val health = link?.health()?.valueOr(null)
            val info = health?.let { link.hello().valueOr(null) } ?: before?.info
            val beat = if (health != null) {
                val signedIn = info?.harnesses?.any { it.loggedIn } ?: false
                Beat(now, health, info, if (health.state == RunnerHealth.UP && signedIn) RunnerHealth.UP else RunnerHealth.DEGRADED)
            } else {
                val okAt = before?.okAt
                Beat(
                    okAt, before?.health, info, if (okAt != null && now - okAt < lostAfterMs) RunnerHealth.DEGRADED else RunnerHealth.DOWN,
                    silentSince = before?.silentSince ?: now,
                )
            }
            beats[name] = beat
            if (before?.state != beat.state) "runner '$name' is ${beat.state}" else null
        }
    }

    private fun remoteNames(): List<String> = runCatching(remotes).getOrDefault(emptyList()).filter { it != RunnerPool.LOCAL }

    private fun bindingFor(): Pair<String, RunnerBinding>? =
        bindings[REMOTE_BINDING]?.let { REMOTE_BINDING to it } ?: bindings.entries.singleOrNull()?.let { it.key to it.value }

    companion object {
        /** The binding id Infantry_RemoteRunner offers its paired-runner link under. */
        const val REMOTE_BINDING = "remote"
    }
}

/** The link to the runner [sessionId] lives on: the one way an orchestrator-side caller addresses a session's runner. */
fun RunnerPool.linkFor(sessionId: String): RunnerLink? = link(runnerOf(sessionId))
