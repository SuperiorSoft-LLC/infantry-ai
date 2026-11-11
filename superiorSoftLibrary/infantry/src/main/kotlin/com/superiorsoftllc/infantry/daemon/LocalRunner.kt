// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.daemon

import com.superiorsoftllc.infantry.agents.AgentProfiles
import com.superiorsoftllc.infantry.harness.ToolApproval
import com.superiorsoftllc.infantry.session.ToolApprovals
import com.superiorsoftllc.infantry.daemon.api.orchestrator.LocalRunnerServices
import com.superiorsoftllc.infantry.daemon.api.orchestrator.MailItem
import com.superiorsoftllc.infantry.daemon.api.orchestrator.MailResult
import com.superiorsoftllc.infantry.daemon.api.orchestrator.OrchestratorReply
import com.superiorsoftllc.infantry.daemon.api.orchestrator.RunnerEvent
import com.superiorsoftllc.infantry.daemon.api.orchestrator.RunnerHealth
import com.superiorsoftllc.infantry.daemon.api.orchestrator.RunnerInfo
import com.superiorsoftllc.infantry.daemon.api.orchestrator.RunnerLink
import com.superiorsoftllc.infantry.daemon.api.orchestrator.RunnerPool
import com.superiorsoftllc.infantry.daemon.api.orchestrator.StartSpec
import com.superiorsoftllc.infantry.protocol.MessageOrigin
import com.superiorsoftllc.infantry.protocol.SpawnOptions
import com.superiorsoftllc.infantry.session.RunInFlightException
import com.superiorsoftllc.infantry.session.SessionManager
import com.superiorsoftllc.infantry.session.SessionRecord
import com.superiorsoftllc.infantry.session.isBusy
import com.superiorsoftllc.infantry.usage.LaunchOrigin
import com.superiorsoftllc.infantry.worktree.WorktreeRecord
import kotlinx.serialization.json.Json
import java.io.File
import java.util.UUID

/**
 * RUNNER-POOL-2 (docs/RUNNER-POOL.md 2.1): the runner-side functions the local [com.superiorsoftllc.infantry.daemon.api.orchestrator.LocalRunnerLink]
 * answers from. Each wraps what the daemon called before, unchanged: a start is [bindSessionOnSpawn], a stop is
 * [SessionManager.kill], a mail turn is [SessionManager.resume].
 *
 * RUNNER-POOL-6: a spec that carries a worktree the runner cut (a start its orchestrator sent with a git remote) starts the
 * session in it with the composed first turn, as a ticket start does ([SessionManager.startNew] with `preWorktree`), unattended.
 */
object LocalRunner {
    private val originJson = Json { ignoreUnknownKeys = true }

    fun services(
        manager: SessionManager,
        agents: AgentProfiles,
        info: () -> RunnerInfo,
        capacity: () -> Int,
        events: (sinceSeq: Long) -> List<RunnerEvent> = { emptyList() },
        /** RUNNER-POOL-8: acknowledge and read the outbox; null = filter [events] (no ack). */
        eventsAcked: ((acked: Map<String, Long>) -> List<RunnerEvent>)? = null,
        transcript: (sessionId: String, fromLine: Int, maxLines: Int) -> List<String> = { _, _, _ -> emptyList() },
        /** RUNNER-PROMPT-RELAY-1: apply the orchestrator's answer to an open prompt of a session; default none open. */
        answerPrompt: (sessionId: String, reply: com.superiorsoftllc.infantry.daemon.api.orchestrator.PromptReply) -> Boolean = { _, _ -> false },
    ) = LocalRunnerServices(
        ticketIdOf = { sessionId -> manager.get(sessionId)?.ticketId },
        info = info,
        start = { spec ->
            val wt = spec.worktree
            val approval = runnerToolApproval(spec)
            // RUNNER-CRED-1: the harness key the orchestrator lent (already screened by the link server), in place before the first run.
            val lentEnv = spec.credentials?.env.orEmpty()
            if (wt == null) {
                val dir = spec.repo?.let(::File)
                // A spec naming an agent or a role binds that session at rest (its prompt, if any, is the caller's first mail turn).
                bindSessionOnSpawn(manager, agents, spawnOptions(spec), dir, toolApproval = approval)?.id?.also { manager.lendEnv(it, lentEnv) }
                    // RUNNER-TEST-1: an ANONYMOUS start (no role, no agent) has nothing to bind, exactly like an anonymous console: its
                    // first prompt creates the session and is its first turn. `infantry runner test` starts one this way.
                    ?: spec.prompt?.takeIf { it.isNotBlank() }?.let { prompt ->
                        manager.startNew(
                            prompt = prompt, repo = dir, providerId = spec.provider, model = spec.model, effort = spec.effort,
                            projectId = spec.projectId, contextLimit = spec.contextLimit, toolApproval = approval, lentEnv = lentEnv,
                        ).id
                    }
            } else {
                manager.startNew(
                    prompt = spec.prompt.orEmpty(),
                    providerId = spec.provider, model = spec.model, effort = spec.effort,
                    agentId = spec.agentId, role = spec.role, ticketId = spec.ticketId, projectId = spec.projectId,
                    contextLimit = spec.contextLimit, toolApproval = approval, lentEnv = lentEnv,
                    unattended = true, launchOrigin = LaunchOrigin.UNATTENDED,
                    preWorktree = WorktreeRecord(sessionId = "", repoRoot = wt.repoRoot, path = wt.path, branch = wt.branch, baseRef = wt.base, createdAt = System.currentTimeMillis()),
                ).id
            }
        },
        stop = { sessionId, reason, force -> if (manager.get(sessionId) == null) false else { manager.kill(sessionId, force = force, reason = reason); true } },
        deliver = { sessionId, mail ->
            try {
                manager.resume(sessionId, mail.text, decodeOrigin(mail.origin), mail.wakeReason, mail.messageIds.joinToString(","))
                MailResult.Delivered
            } catch (e: RunInFlightException) {
                MailResult(MailResult.QUEUED)
            } catch (e: Exception) {
                MailResult(MailResult.FAILED, e.message ?: e::class.simpleName.orEmpty())
            }
        },
        health = {
            val running = manager.list().count { it.isBusy() }
            val cap = capacity()
            RunnerHealth(RunnerHealth.UP, running, if (cap <= 0) -1 else maxOf(0, cap - running))
        },
        events = events,
        eventsAcked = eventsAcked,
        transcript = transcript,
        // RUNNER-TEST-1: `session_remove --force` by another name, so a throwaway turn leaves nothing on this runner's list: a live
        // session is ended first (a forced kill marks the record ENDED before it returns), then its record goes.
        remove = { sessionId ->
            val record = manager.get(sessionId)
            if (record == null) false else {
                if (record.live) runCatching { manager.kill(sessionId, force = true) }
                manager.delete(sessionId)
                true
            }
        },
        answerPrompt = answerPrompt,
    )

    /** A pool with only the local runner over [manager] (a connection handler's default when the daemon passes none). */
    fun pool(manager: SessionManager, agents: AgentProfiles): RunnerPool = com.superiorsoftllc.infantry.daemon.api.orchestrator.RunnerHub(
        com.superiorsoftllc.infantry.daemon.api.orchestrator.LocalRunnerLink(
            services(manager, agents, info = { RunnerInfo(RunnerPool.LOCAL, RunnerLink.CONTRACT_VERSION) }, capacity = { 0 }),
        ),
    )

    /** A spawn's options as a start spec: the fields a local spawn resolves (agent, role, project, executor, context limit). */
    fun startSpec(options: SpawnOptions?, repo: File?, startId: String = UUID.randomUUID().toString(), parentSessionId: String? = null) = StartSpec(
        startId = startId,
        agentId = options?.agentId, role = options?.role, projectId = options?.projectId,
        provider = options?.provider, model = options?.model, effort = options?.effort, contextLimit = options?.contextLimit,
        parentSessionId = parentSessionId, repo = repo?.path,
    )

    /**
     * RUNNER-PAIR-1: the Tool approval a session started over the runner link runs with: the orchestrator's mode for the start
     * ([StartSpec.toolApproval]), else this runner's own; `ask` becomes `deny` ([ToolApproval.withoutPrompt]) because the link
     * carries no prompt to anyone, so a call is denied at once with the "can't prompt" notice instead of waiting out a timer.
     * RUNNER-DOCKER-MATRIX-1: an orchestrator that relays prompts ([StartSpec.promptRelay], RUNNER-PROMPT-RELAY-1) gets `ask` as
     * is, so the approval card shows in its app; live on nightly .60 every runner call was still denied with no card.
     */
    fun runnerToolApproval(spec: StartSpec, own: () -> ToolApproval = { ToolApprovals.current().mode }): ToolApproval =
        (ToolApproval.parse(spec.toolApproval) ?: own()).let { if (spec.promptRelay) it else it.withoutPrompt() }

    /** The inverse of [startSpec]: exactly the options [bindSessionOnSpawn] reads. */
    fun spawnOptions(spec: StartSpec) = SpawnOptions(
        provider = spec.provider, model = spec.model, agentId = spec.agentId, projectId = spec.projectId,
        effort = spec.effort, role = spec.role, contextLimit = spec.contextLimit,
    )

    fun encodeOrigin(origin: MessageOrigin): String = originJson.encodeToString(MessageOrigin.serializer(), origin)
    fun decodeOrigin(origin: String): MessageOrigin = originJson.decodeFromString(MessageOrigin.serializer(), origin)

    /**
     * Start a session for [options] (working copy from [repo]) on the pool's runner and return it (local record or remote view), null when the options name no agent and no role.
     * A refused spec throws [IllegalArgumentException] with the runner's reason (the callers' existing refusal path); an unreachable
     * runner throws [IllegalStateException]. Placement (RUNNER-POOL-3) picks the runner later; today it is always `local`.
     */
    fun start(
        pool: RunnerPool, manager: SessionManager, options: SpawnOptions?, repo: File?,
        /** RUNNER-AGENT-1: the agent profile's runner, the next source after the spawn's own `runner`. */
        agentRunner: String? = null,
        /** RUNNER-AGENT-1: the routing rule / `runners.default` tail of the precedence (Placement.pick). */
        defaultRunner: String = RunnerPool.LOCAL,
        /** The placement view of the pool (paused, per-runner caps); null = health and free slots straight from the pool. */
        entries: (() -> List<com.superiorsoftllc.infantry.settings.RunnerEntry>)? = null,
        /** RUNNER-PAIR-1: a project's own folder; a runner start clones ITS `origin`, not the daemon's start-up [repo]. */
        projectDir: (String) -> File? = { null },
        /**
         * RUNNER-SESSION-GHOST-1: records what a remote start pinned (project, agent, role, harness, model) in the orchestrator's
         * mirror of runner sessions. Without it the session listed as provider `remote` with no project, which no project-scoped
         * caller could remove.
         */
        remotePlaced: (String, com.superiorsoftllc.infantry.runners.RemoteSessionMirror.Placed) -> Unit = { _, _ -> },
    ): StartedSession? {
        val wanted = options?.runner?.trim().orEmpty().ifEmpty { agentRunner?.trim().orEmpty() }.ifEmpty { defaultRunner.trim() }
        var runner = RunnerPool.LOCAL
        if (wanted.isNotEmpty() && wanted != RunnerPool.LOCAL) {
            val view = entries?.invoke() ?: pool.runners().map {
                com.superiorsoftllc.infantry.settings.RunnerEntry(it.name, it.state, it.health?.freeSlots?.takeIf { s -> s >= 0 } ?: Int.MAX_VALUE)
            }
            when (val placed = com.superiorsoftllc.infantry.settings.Placement.pick(null, null, defaultRunner, view, options?.projectId, options?.provider, options?.runner, agentRunner)) {
                is com.superiorsoftllc.infantry.settings.PlacementResult.Refused -> throw IllegalArgumentException("runner refused: ${placed.reason}")
                is com.superiorsoftllc.infantry.settings.PlacementResult.Placed -> runner = placed.runner
            }
        }
        if (runner != RunnerPool.LOCAL) {
            // RUNNER-SESSION-GHOST-1: a runner the caller ASKED for (the spawn's own, or its agent profile's) is never answered by a
            // local session: with nothing to bind there the start is refused here, loudly, instead of the next prompt starting locally.
            val asked = options?.runner?.trim().orEmpty().ifEmpty { agentRunner?.trim().orEmpty() }
            if (options?.agentId == null && options?.role == null && asked.isNotEmpty() && asked != RunnerPool.LOCAL) {
                throw IllegalArgumentException("a session on runner '$runner' needs a role or an agent profile; pick one, or choose the local runner")
            }
            return startRemote(pool, runner, options, options?.projectId?.let(projectDir) ?: repo, remotePlaced)
        }
        val link = pool.link(RunnerPool.LOCAL) ?: throw IllegalStateException("no '${RunnerPool.LOCAL}' runner")
        return when (val r = link.startSession(startSpec(options, repo))) {
            is OrchestratorReply.Ok -> {
                r.value.refusal?.let { throw IllegalArgumentException(it) }
                r.value.sessionId?.let(manager::get)?.let(StartedSession::Local)
            }
            is OrchestratorReply.Unavailable -> throw IllegalStateException("runner unavailable: ${r.reason}")
        }
    }

    /**
     * RUNNER-AGENT-1: a session outside the board on runner [runner]. The runner clones the project's git remote (the checkout's
     * `origin`) and runs the session in that clone, with no ticket branch; the session is indexed on the pool so mail and kills go there.
     */
    private fun startRemote(
        pool: RunnerPool, runner: String, options: SpawnOptions?, repo: File?,
        remotePlaced: (String, com.superiorsoftllc.infantry.runners.RemoteSessionMirror.Placed) -> Unit,
    ): StartedSession? {
        if (options?.agentId == null && options?.role == null) return null
        val link = pool.link(runner) ?: throw IllegalArgumentException("runner '$runner' is not registered, or no runner binding is loaded")
        val remote = repo?.let { runCatching { com.superiorsoftllc.infantry.daemon.plugins.originUrl(it) }.getOrNull() }
            ?: throw IllegalArgumentException("runner $runner needs the project's git remote, and ${repo?.path ?: "this project"} has no `origin` remote. Fix: git -C <project folder> remote add origin <url the runner can reach>")
        val identity = repo?.let { runCatching { com.superiorsoftllc.infantry.daemon.plugins.gitIdentityOf(it) }.getOrNull() }
        // RUNNER-PAIR-1: the Tool approval a local start of these options would run with (its own override, else this daemon's setting).
        val approval = ToolApprovals.current(ToolApproval.parse(options?.toolApproval)).mode.wire
        val spec = startSpec(options, null).copy(gitRemote = remote, gitName = identity?.first, gitEmail = identity?.second, toolApproval = approval, promptRelay = true)
        return when (val reply = link.startSession(spec)) {
            is OrchestratorReply.Unavailable -> throw IllegalStateException("runner $runner unavailable: ${reply.reason}")
            is OrchestratorReply.Ok -> {
                val id = reply.value.sessionId ?: throw IllegalArgumentException("runner $runner refused the start: ${reply.value.refusal ?: "no session"}")
                (pool as? com.superiorsoftllc.infantry.daemon.api.orchestrator.RunnerHub ?: throw IllegalStateException("this daemon cannot index a remote session")).placed(id, runner)
                remotePlaced(
                    id,
                    com.superiorsoftllc.infantry.runners.RemoteSessionMirror.Placed(
                        options.provider.orEmpty(), options.model, null, reply.value.branch, null, reply.value.worktreePath,
                        projectId = options.projectId, agentId = options.agentId, role = options.role,
                    ),
                )
                StartedSession.Remote(
                    runner,
                    com.superiorsoftllc.infantry.daemon.api.sessions.SessionView(
                        id = id, agentId = options.agentId, role = options.role, provider = options.provider.orEmpty(), model = options.model,
                        projectId = options.projectId, state = "STARTING", live = true, contextLimit = options.contextLimit, effort = options.effort,
                    ),
                )
            }
        }
    }

    /** RUNNER-AGENT-1: the placement view a non-ticket start uses (paused runners, per-runner caps), the same as the board's. */
    fun placementEntries(pool: RunnerPool, settings: com.superiorsoftllc.infantry.settings.SettingsService): List<com.superiorsoftllc.infantry.settings.RunnerEntry> = pool.runners().map { e ->
        com.superiorsoftllc.infantry.settings.RunnerEntry(
            e.name, if (settings.runnerPaused(e.name)) "paused" else e.state,
            minOf(
                e.health?.freeSlots?.let { if (it < 0) Int.MAX_VALUE else it } ?: 1,
                (if (e.name == RunnerPool.LOCAL) settings.runnerMaxWorkersExplicit(e.name) ?: Int.MAX_VALUE else settings.runnerMaxWorkers(e.name))
                    .let { cap -> if (cap == Int.MAX_VALUE) cap else (cap - (e.health?.running ?: 0)).coerceAtLeast(0) },
            ),
            e.info?.projects.orEmpty().toSet(), e.info?.harnesses.orEmpty().filter { it.loggedIn }.map { it.id }.toSet(),
        )
    }

    /** MailRouter's resume edge through the session's runner; an unreachable runner keeps the mail queued (it is persisted). */
    fun deliverVia(pool: RunnerPool): (String, String, MessageOrigin, String, List<String>) -> MailResult = { sessionId, turn, origin, wakeReason, ids ->
        val link: RunnerLink? = pool.link(pool.runnerOf(sessionId))
        when (val r = link?.deliverMail(sessionId, MailItem(turn, encodeOrigin(origin), wakeReason, ids))) {
            null -> MailResult(MailResult.QUEUED, "runner '${pool.runnerOf(sessionId)}' is unknown")
            is OrchestratorReply.Ok -> r.value
            is OrchestratorReply.Unavailable -> MailResult(MailResult.QUEUED, "runner unavailable: ${r.reason}")
        }
    }
}

/**
 * RUNNER-AGENT-1: the one result of starting a session outside the board. [Local] holds the daemon's own record; [Remote] has none
 * (the runner owns the process), only the view and the runner it was placed on. Every caller of [LocalRunner.start] handles both.
 */
sealed interface StartedSession {
    val id: String
    data class Local(val record: SessionRecord) : StartedSession { override val id get() = record.id }
    data class Remote(val runner: String, val view: com.superiorsoftllc.infantry.daemon.api.sessions.SessionView) : StartedSession { override val id get() = view.id }
}
