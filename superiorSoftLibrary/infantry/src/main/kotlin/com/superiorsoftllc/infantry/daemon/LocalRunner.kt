// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.daemon

import com.superiorsoftllc.infantry.agents.AgentProfiles
import com.superiorsoftllc.infantry.harness.ToolApproval
import com.superiorsoftllc.infantry.session.ToolApprovals
import com.superiorsoftllc.infantry.daemon.api.orchestrator.CommandResult
import com.superiorsoftllc.infantry.daemon.api.orchestrator.LocalRunnerServices
import com.superiorsoftllc.infantry.daemon.api.orchestrator.MailItem
import com.superiorsoftllc.infantry.daemon.api.orchestrator.MailResult
import com.superiorsoftllc.infantry.daemon.api.orchestrator.OrchestratorReply
import com.superiorsoftllc.infantry.daemon.api.orchestrator.RunnerEvent
import com.superiorsoftllc.infantry.daemon.api.orchestrator.RunnerHealth
import com.superiorsoftllc.infantry.daemon.api.orchestrator.RunnerInfo
import com.superiorsoftllc.infantry.daemon.api.orchestrator.RunnerLink
import com.superiorsoftllc.infantry.daemon.api.orchestrator.RunnerPool
import com.superiorsoftllc.infantry.daemon.api.orchestrator.SessionCommand
import com.superiorsoftllc.infantry.daemon.api.orchestrator.SessionRef
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
        /** RUNNER-OUTPUT-1: (session, afterSequence) -> its stream frames; null = not offered. */
        frames: ((sessionId: String, afterSequence: Long) -> List<com.superiorsoftllc.infantry.protocol.Envelope>)? = null,
        /** RUNNER-PROMPT-RELAY-1: apply the orchestrator's answer to an open prompt of a session; default none open. */
        answerPrompt: (sessionId: String, reply: com.superiorsoftllc.infantry.daemon.api.orchestrator.PromptReply) -> Boolean = { _, _ -> false },
    ) = LocalRunnerServices(
        ticketIdOf = { sessionId -> manager.get(sessionId)?.ticketId },
        info = info,
        start = { spec ->
            val wt = spec.worktree
            // SPAWN-PARAMS-1: a start of THIS daemon's own (the `new` frame, infantry_spawn: promptRelay, no mode named) follows the
            // global setting, as the app's own Start did before the runner link; a named mode or a remote orchestrator's is applied.
            val approval = if (spec.promptRelay && spec.toolApproval == null) null else runnerToolApproval(spec)
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
        frames = frames,
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
        command = { sessionId, command -> execute(manager, sessionId, command) },
    )

    /**
     * RUNNER-UNIFY-2: one owner command on a session of THIS runner: the [SessionManager] calls the daemon's frame handlers made
     * directly before (moved here, not copied), with their exceptions turned into the [CommandResult] the handler turns back into the
     * same error frame. A turn that finds one running is [CommandResult.IN_FLIGHT] (the typed refusal a client queues on).
     */
    fun execute(manager: SessionManager, sessionId: String, command: SessionCommand): CommandResult = try {
        when (command.kind) {
            SessionCommand.TURN -> { manager.resume(sessionId, command.text, MessageOrigin.Owner, command.wakeReason); CommandResult.Done }
            SessionCommand.ATTACH -> { manager.attach(sessionId); CommandResult.Done }
            SessionCommand.INTERRUPT -> { manager.interrupt(sessionId); CommandResult.Done }
            SessionCommand.QUEUE_ADD -> CommandResult(CommandResult.DONE, started = manager.queueAdd(sessionId, command.text, command.maxQueued) == null)
            SessionCommand.QUEUE_REMOVE -> { manager.queueRemove(sessionId, command.text); CommandResult.Done }
            SessionCommand.QUEUE_FORCE -> { manager.queueForce(sessionId, command.text); CommandResult.Done }
            // The kill of an id this runner does not hold is refused with the manager's own words, without touching the process host.
            SessionCommand.KILL -> if (manager.get(sessionId) == null) {
                CommandResult(CommandResult.REFUSED, "unknown session $sessionId", "IllegalStateException")
            } else {
                val by = command.by
                if (by == null) manager.kill(sessionId, force = command.force, reason = command.reason)
                else manager.kill(sessionId, command.force, by, command.reason)
                CommandResult.Done
            }
            // SESSION-GHOST-1: an id this runner does not hold is already gone: a clean no-op.
            SessionCommand.DELETE -> if (manager.get(sessionId) == null) CommandResult(CommandResult.UNKNOWN) else { manager.delete(sessionId); CommandResult.Done }
            else -> CommandResult(CommandResult.REFUSED, "unknown session command '${command.kind}'", "IllegalArgumentException")
        }
    } catch (e: RunInFlightException) {
        CommandResult(CommandResult.IN_FLIGHT, e.message, e::class.simpleName.orEmpty())
    } catch (e: Exception) {
        CommandResult(CommandResult.REFUSED, e.message, e::class.simpleName.orEmpty())
    }

    /**
     * RUNNER-UNIFY-2 (docs/RUNNER-UNIFY.md 5.1): an owner command on [sessionId] through the runner it lives on
     * ([RunnerPool.linkFor]): the local runner for this daemon's own sessions, the paired runner for a remote one. No caller asks which.
     * CONTINGENCY `runners.unifiedCommands` = false ([unified]): a session this daemon's [manager] holds skips the pool and runs
     * [execute] directly, as the handlers did before; only the other sessions go by placement.
     */
    fun command(pool: RunnerPool, manager: SessionManager, sessionId: String, command: SessionCommand, unified: Boolean = true): CommandResult =
        command(pool, sessionId, command, unified, held = { manager.get(it) != null }, direct = { id, c -> execute(manager, id, c) })

    /** [command] with the manager's two uses spelled out: [held] (this daemon holds the session) and [direct] ([execute] on it). */
    fun command(
        pool: RunnerPool, sessionId: String, command: SessionCommand, unified: Boolean,
        held: (String) -> Boolean, direct: (String, SessionCommand) -> CommandResult,
    ): CommandResult {
        if (!unified && held(sessionId)) return direct(sessionId, command)
        val runner = pool.runnerOf(sessionId)
        val link = pool.linkFor(sessionId) ?: return CommandResult(CommandResult.UNAVAILABLE, "runner '$runner' is not connected; the session there is still running")
        return when (val r = link.command(sessionId, command)) {
            is OrchestratorReply.Ok -> r.value
            is OrchestratorReply.Unavailable -> CommandResult(CommandResult.UNAVAILABLE, "runner '$runner' unavailable: ${r.reason}")
        }
    }

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

    /**
     * SPAWN-PARAMS-1: a plugin's spawn ([SpawnRequest], `infantry_spawn` / Lua `spawn`) as the `new` frame's options, field for field,
     * so it starts through [start] exactly as the app's Start button does.
     */
    fun spawnOptions(request: com.superiorsoftllc.infantry.daemon.api.spawns.SpawnRequest) = SpawnOptions(
        provider = request.provider, model = request.model, agentId = request.agentId, projectId = request.projectId,
        effort = request.effort, role = request.role, toolApproval = request.toolApproval, contextLimit = request.contextLimit,
        runner = request.runner,
    )

    /**
     * SPAWN-PARAMS-1: the checks every start outside the board makes BEFORE [start], the `new` frame's and `infantry_spawn`'s alike:
     * the agent / role / project / provider / model resolve ([AgentProfiles.resolveSpawn], which refuses an unknown provider or a
     * model it does not list), the role is allowed, and a named Tool approval is a known mode. Throws [IllegalArgumentException]
     * with the exact reason.
     */
    fun checkSpawn(manager: SessionManager, agents: AgentProfiles, options: SpawnOptions) {
        ToolApproval.parseStrict(options.toolApproval)
        manager.requireAllowedRole(agents.resolveSpawn(options).role)
    }

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
            val viewOf = {
                entries?.invoke() ?: pool.runners().map {
                    com.superiorsoftllc.infantry.settings.RunnerEntry(it.name, it.state, it.health?.freeSlots?.takeIf { s -> s >= 0 } ?: Int.MAX_VALUE)
                }
            }
            val pick = { view: List<com.superiorsoftllc.infantry.settings.RunnerEntry> ->
                com.superiorsoftllc.infantry.settings.Placement.pick(null, null, defaultRunner, view, options?.projectId, options?.provider, options?.runner, agentRunner)
            }
            var view = viewOf()
            var placedFirst = pick(view)
            // HARNESS-ALL-1: the slot count is the last heartbeat's; a session ended since then still holds it there. Re-read the runner
            // the cached view calls full once, so ending a session and starting the next is not refused as "full" for a heartbeat period.
            if (placedFirst is com.superiorsoftllc.infantry.settings.PlacementResult.Refused && view.any { it.name == wanted && it.freeSlots <= 0 }) {
                pool.refresh(wanted)
                view = viewOf()
                placedFirst = pick(view)
            }
            when (val placed = placedFirst) {
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
            return startRemote(pool, manager, runner, options, options?.projectId?.let(projectDir) ?: repo, remotePlaced)
        }
        val link = pool.link(RunnerPool.LOCAL) ?: throw IllegalStateException("no '${RunnerPool.LOCAL}' runner")
        // SPAWN-PARAMS-1: the spawn's own Tool approval (null = the global setting); prompts of a local session reach this app.
        val spec = startSpec(options, repo).copy(toolApproval = ToolApproval.parseStrict(options?.toolApproval)?.wire, promptRelay = true)
        return when (val r = link.startSession(spec)) {
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
        pool: RunnerPool, manager: SessionManager, runner: String, options: SpawnOptions?, repo: File?,
        remotePlaced: (String, com.superiorsoftllc.infantry.runners.RemoteSessionMirror.Placed) -> Unit,
    ): StartedSession? {
        if (options?.agentId == null && options?.role == null) return null
        val hub = pool as? com.superiorsoftllc.infantry.daemon.api.orchestrator.RunnerHub
        // RUNNER-PAIR-1: the Tool approval a local start of these options would run with (its own override, else this daemon's setting).
        val approval = ToolApprovals.current(ToolApproval.parse(options.toolApproval)).mode.wire
        val spec = startSpec(options, null).copy(toolApproval = approval, promptRelay = true)
        val placed = { ref: SessionRef ->
            com.superiorsoftllc.infantry.runners.RemoteSessionMirror.Placed(
                options.provider.orEmpty(), options.model, null, ref.branch, null, ref.worktreePath,
                projectId = options.projectId, agentId = options.agentId, role = options.role,
                // RUNNER-ROLE-1: bound at rest with no first turn, so the first turn this daemon sends it carries the role brief.
                briefOwed = true,
            )
        }
        val out = startOnRunner(
            pool, runner, spec, repo,
            originOf = { com.superiorsoftllc.infantry.daemon.plugins.originUrl(it) },
            identityOf = { com.superiorsoftllc.infantry.daemon.plugins.gitIdentityOf(it) },
            index = { id -> (hub ?: throw IllegalStateException("this daemon cannot index a remote session")).placed(id, runner) },
            placed = placed, remotePlaced = remotePlaced,
        )
        return when (out) {
            is RemoteStart.Unavailable -> throw IllegalStateException(out.reason)
            is RemoteStart.Refused -> throw IllegalArgumentException(out.reason)
            is RemoteStart.Started -> StartedSession.Remote(
                // RUNNER-ROLE-1: the persona and traits frozen here, as a local bind freezes them, for that first turn.
                runner.also { manager.freezePlacedInstance(out.id, options.agentId, options.role) },
                com.superiorsoftllc.infantry.daemon.api.sessions.SessionView(
                    id = out.id, agentId = options.agentId, role = options.role, provider = options.provider.orEmpty(), model = options.model,
                    projectId = options.projectId, state = "STARTING", live = true, contextLimit = options.contextLimit, effort = options.effort,
                ),
            )
        }
    }

    /** RUNNER-UNIFY-2: what [startOnRunner] did. [Unavailable] = the runner did not answer; [Refused] = it, or this daemon, said no. */
    sealed interface RemoteStart {
        data class Started(val id: String, val ref: SessionRef) : RemoteStart
        data class Refused(val reason: String) : RemoteStart
        data class Unavailable(val reason: String) : RemoteStart
    }

    /**
     * RUNNER-UNIFY-2: the ONE remote start, for a ticket (the board) and a session outside the board alike. The runner clones
     * [checkout]'s git remote ([originOf]) and commits as its git identity ([identityOf]); then the session is indexed on the pool
     * ([index]) and recorded in the orchestrator's mirror ([placed] -> [remotePlaced]). [spec] carries everything else (ticket,
     * branch, Tool approval, prompt relay).
     */
    fun startOnRunner(
        pool: RunnerPool, runner: String, spec: StartSpec, checkout: File?,
        originOf: (File) -> String?, identityOf: (File) -> Pair<String, String>?,
        index: (String) -> Unit,
        placed: (SessionRef) -> com.superiorsoftllc.infantry.runners.RemoteSessionMirror.Placed,
        remotePlaced: (String, com.superiorsoftllc.infantry.runners.RemoteSessionMirror.Placed) -> Unit,
    ): RemoteStart {
        val link = pool.link(runner) ?: return RemoteStart.Refused("runner '$runner' is not registered, or no runner binding is loaded")
        val remote = checkout?.let { runCatching { originOf(it) }.getOrNull() }?.takeIf { it.isNotBlank() }
            ?: return RemoteStart.Refused("runner $runner needs the project's git remote, and ${checkout?.path ?: "this project"} has no `origin` remote. Fix: git -C <project folder> remote add origin <url the runner can reach>")
        val identity = runCatching { identityOf(checkout) }.getOrNull()
        val full = spec.copy(gitRemote = remote).let { s -> identity?.let { (n, e) -> s.copy(gitName = n, gitEmail = e) } ?: s }
        return when (val reply = link.startSession(full)) {
            is OrchestratorReply.Unavailable -> RemoteStart.Unavailable("runner $runner unavailable: ${reply.reason}")
            is OrchestratorReply.Ok -> {
                val id = reply.value.sessionId ?: return RemoteStart.Refused("runner $runner refused the start: ${reply.value.refusal ?: "no session"}")
                index(id)
                remotePlaced(id, placed(reply.value))
                RemoteStart.Started(id, reply.value)
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
