// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.daemon.plugins

import com.superiorsoftllc.infantry.agents.AgentProfiles
import com.superiorsoftllc.infantry.agents.KnowledgeCounter
import com.superiorsoftllc.infantry.agents.SkillsSource
import com.superiorsoftllc.infantry.agents.section
import com.superiorsoftllc.infantry.backlog.TicketEventLog
import com.superiorsoftllc.infantry.backlog.TicketImages
import com.superiorsoftllc.infantry.daemon.api.board.BoardAgent
import com.superiorsoftllc.infantry.daemon.api.board.BoardAgents
import com.superiorsoftllc.infantry.daemon.api.board.BoardContextSources
import com.superiorsoftllc.infantry.daemon.api.board.BoardExecutor
import com.superiorsoftllc.infantry.daemon.api.board.BoardImages
import com.superiorsoftllc.infantry.daemon.api.board.BoardRunHealth
import com.superiorsoftllc.infantry.daemon.api.board.BoardRunOutcome
import com.superiorsoftllc.infantry.daemon.api.board.BoardProviderInfo
import com.superiorsoftllc.infantry.daemon.api.board.BoardRoleInstance
import com.superiorsoftllc.infantry.daemon.api.board.BoardSessionDetail
import com.superiorsoftllc.infantry.daemon.api.board.BoardTrait
import com.superiorsoftllc.infantry.protocol.TraitCheck
import com.superiorsoftllc.infantry.session.RoleInstance
import com.superiorsoftllc.infantry.session.TraitSnapshot
import com.superiorsoftllc.infantry.session.isBusy
import com.superiorsoftllc.infantry.daemon.api.board.BoardSessionEvent
import com.superiorsoftllc.infantry.daemon.api.board.BoardSessionListener
import com.superiorsoftllc.infantry.orchestration.RunOutcomes
import com.superiorsoftllc.infantry.protocol.AgentProfile
import com.superiorsoftllc.infantry.protocol.FinishedPayload
import com.superiorsoftllc.infantry.session.SessionEvent
import com.superiorsoftllc.infantry.session.SessionState
import com.superiorsoftllc.infantry.session.TicketWorkerHealth
import java.util.concurrent.CopyOnWriteArrayList
import com.superiorsoftllc.infantry.daemon.api.board.BaseChoice
import com.superiorsoftllc.infantry.daemon.api.board.BoardGit
import com.superiorsoftllc.infantry.daemon.api.board.BoardHost
import com.superiorsoftllc.infantry.daemon.api.board.BoardSessions
import com.superiorsoftllc.infantry.daemon.api.board.BoardSettings
import com.superiorsoftllc.infantry.daemon.api.board.BoardStorage
import com.superiorsoftllc.infantry.daemon.api.board.BoardTables
import com.superiorsoftllc.infantry.daemon.api.board.BranchHandoffFacts
import com.superiorsoftllc.infantry.protocol.MessageOrigin
import com.superiorsoftllc.infantry.daemon.api.board.BranchOutcome
import com.superiorsoftllc.infantry.daemon.api.board.BranchRequest
import com.superiorsoftllc.infantry.daemon.api.board.LaunchVerdict
import com.superiorsoftllc.infantry.daemon.api.board.SessionStart
import com.superiorsoftllc.infantry.daemon.api.board.SessionStartResult
import com.superiorsoftllc.infantry.daemon.api.board.StoredLink
import com.superiorsoftllc.infantry.daemon.api.board.StoredRun
import com.superiorsoftllc.infantry.daemon.api.board.StoredTicket
import com.superiorsoftllc.infantry.daemon.api.board.TicketEventAppender
import com.superiorsoftllc.infantry.daemon.api.board.TicketRunTable
import com.superiorsoftllc.infantry.daemon.api.board.TicketTable
import com.superiorsoftllc.infantry.daemon.api.sessions.SessionView
import com.superiorsoftllc.infantry.persistence.InfantryStore
import com.superiorsoftllc.infantry.persistence.TicketLinkRow
import com.superiorsoftllc.infantry.persistence.TicketRow
import com.superiorsoftllc.infantry.persistence.toRow
import com.superiorsoftllc.infantry.persistence.toStored
import com.superiorsoftllc.infantry.persistence.TicketRunRow
import com.superiorsoftllc.infantry.session.ContextSource
import com.superiorsoftllc.infantry.session.SessionManager
import com.superiorsoftllc.infantry.usage.GateDecision
import com.superiorsoftllc.infantry.usage.LaunchOrigin
import com.superiorsoftllc.infantry.worktree.BaseScope
import com.superiorsoftllc.infantry.worktree.TicketBaseRef
import com.superiorsoftllc.infantry.worktree.TicketBranchOutcome
import com.superiorsoftllc.infantry.worktree.TicketBranchRequest
import com.superiorsoftllc.infantry.worktree.TicketBranches
import com.superiorsoftllc.infantry.worktree.WorktreeRecord
import java.io.File

/**
 * DP-5c (docs/DAEMON-BOARD-API.md section 9): the LOCAL [BoardHost] over the daemon's own classes -- the store, the session manager,
 * the ticket branches and the settings. Mapping only, no decision of its own; behaviour is whatever the wrapped class does. Nothing
 * consumes it yet (DP-5 moves `TicketBoard` onto it).
 */
class LocalBoardHost(
    private val store: InfantryStore,
    private val sessionManager: () -> SessionManager?,
    private val branches: TicketBranches = TicketBranches(),
    private val baseRef: TicketBaseRef = TicketBaseRef.fixed(TicketBaseRef.HEAD),
    private val projectDirectory: (projectId: String?) -> String? = { null },
    private val defaultRepo: File? = null,
    private val eventLog: () -> TicketEventLog? = { null },
    private val clock: () -> Long = System::currentTimeMillis,
    private val agentProfiles: AgentProfiles? = null,
    private val knowledge: KnowledgeCounter = KnowledgeCounter.NONE,
    private val skills: SkillsSource = SkillsSource.NONE,
    private val ticketImages: TicketImages = TicketImages.NONE,
    /** RUNNER-POOL-6: the runner pool a start placed on a remote runner goes through; null = every start is local. */
    private val runners: com.superiorsoftllc.infantry.daemon.api.orchestrator.RunnerHub? = null,
    /** RUNNER-POOL-6: the git remote a remote runner clones (`origin`'s url in the orchestrator's checkout); null = none. */
    private val gitRemoteOf: (repo: File) -> String? = ::originUrl,
    /** RUNNER-POOL-8: `runners.pushOnCommit`, read at each remote start (the runner pushes the ticket branch after each worker commit). */
    private val pushOnCommit: () -> Boolean = { false },
    /** RUNNER-UNIFY-2: `runners.unifiedCommands` (false = a session this daemon holds is killed directly, skipping the pool). */
    private val unifiedCommands: () -> Boolean = { true },
    /** RUNNER-TICKET-ROW-1: told what each remote ticket start pinned (harness, model, checkout, branch), so the board row can show it. */
    private val remotePlaced: (sessionId: String, info: com.superiorsoftllc.infantry.runners.RemoteSessionMirror.Placed) -> Unit = { _, _ -> },
) : BoardHost {

    private val listeners = CopyOnWriteArrayList<BoardSessionListener>()

    /**
     * DP-5f-2: the fan-in for the daemon's one session event sink. Maps a [SessionEvent] the board cares about (status, exit) and
     * hands it to every subscriber in subscription order; the call order is the arrival order. A throwing listener is skipped.
     */
    fun publish(event: SessionEvent) {
        if (listeners.isEmpty()) return
        val mapped: BoardSessionEvent = when (event) {
            is SessionEvent.Status -> BoardSessionEvent.Status(SessionViews.of(event.session))
            is SessionEvent.Exit -> BoardSessionEvent.Exit(
                event.sessionId, event.code, event.processExited, sessionManager()?.get(event.sessionId)?.let(SessionViews::of),
            )
            else -> return
        }
        for (l in listeners) runCatching { l.onEvent(mapped) }
    }

    /**
     * RUNNER-TICKET-ROW-1: a remote worker's turn ended (the runner's FINISHED event). The board hears it as the exit a local worker
     * would give, so `tickets.exitBehavior` and the run record work for a runner worker too. [view] is the mirror's view of it.
     */
    fun publishRemoteExit(view: SessionView, code: Int?) {
        for (l in listeners) runCatching { l.onEvent(BoardSessionEvent.Exit(view.id, code, processExited = false, session = view)) }
    }

    override fun now(): Long = clock()

    /**
     * RUNNER-POOL-6 (docs/RUNNER-POOL.md 4): a ticket start placed on runner [runner]. The runner clones the project's git remote
     * (refused here when the checkout has none) and cuts or reuses the ticket branch itself; only names cross (env NAMES, never
     * values; no path). The session is then indexed on the pool, so mail and kills for it go to that runner.
     */
    private fun startRemote(runner: String, request: SessionStart): SessionStartResult {
        val pool = runners ?: return SessionStartResult.Refused("runner $runner: this daemon has no runner pool")
        val checkout = request.workingDir?.let(::File) ?: defaultRepo
        val spec = com.superiorsoftllc.infantry.daemon.api.orchestrator.StartSpec(
            startId = "${request.ticketId ?: "spawn"}:${clock()}",
            agentId = request.agentId, role = request.role, projectId = request.projectId, provider = request.providerId,
            model = request.model, effort = request.effort, contextLimit = request.contextLimit, ticketId = request.ticketId,
            prompt = request.prompt, title = request.title, base = request.baseBranch, branch = request.recordedBranch,
            pushOnCommit = runCatching(pushOnCommit).getOrDefault(false),
            // RUNNER-PAIR-1: the Tool approval this daemon's own worker would run with, so the remote one behaves the same.
            toolApproval = com.superiorsoftllc.infantry.session.ToolApprovals.current().mode.wire,
            // RUNNER-DOCKER-MATRIX-1: this daemon relays the runner session's prompts to its app (RUNNER-PROMPT-RELAY-1).
            promptRelay = true,
        )
        // RUNNER-UNIFY-2: the same remote start a session outside the board makes (LocalRunner.startOnRunner).
        val placed = { ref: com.superiorsoftllc.infantry.daemon.api.orchestrator.SessionRef ->
            com.superiorsoftllc.infantry.runners.RemoteSessionMirror.Placed(request.providerId.orEmpty(), request.model, checkout?.path, ref.branch, request.ticketId, ref.worktreePath, projectId = request.projectId, agentId = request.agentId, role = request.role)
        }
        return when (val out = com.superiorsoftllc.infantry.daemon.LocalRunner.startOnRunner(pool, runner, spec, checkout, gitRemoteOf, ::gitIdentityOf, { pool.placed(it, runner) }, placed, remotePlaced)) {
            is com.superiorsoftllc.infantry.daemon.LocalRunner.RemoteStart.Refused -> SessionStartResult.Refused(out.reason)
            is com.superiorsoftllc.infantry.daemon.LocalRunner.RemoteStart.Unavailable -> SessionStartResult.Refused(out.reason)
            is com.superiorsoftllc.infantry.daemon.LocalRunner.RemoteStart.Started -> SessionStartResult.Started(
                SessionView(
                    id = out.id, agentId = request.agentId, role = request.role, provider = request.providerId.orEmpty(), model = request.model,
                    projectId = request.projectId, state = "STARTING", live = true, ticketId = request.ticketId, worktreeBranch = out.ref.branch,
                    contextLimit = request.contextLimit, effort = request.effort,
                ),
                branch = out.ref.branch, base = out.ref.base ?: request.recordedBase,
            )
        }
    }

    private val tablesView = object : BoardTables {
        override val tickets: TicketTable = Tickets()
        override val runs: TicketRunTable = Runs()
        override val events: TicketEventAppender = object : TicketEventAppender {
            override fun record(before: Map<String, String>, after: Map<String, String>, changedIds: Collection<String>, deleted: Collection<String>) {
                eventLog()?.record(before, after, changedIds, deleted)
            }

            override fun deliver() {
                eventLog()?.deliver()
            }
        }
    }

    override val storage: BoardStorage = object : BoardStorage {
        override val tables: BoardTables get() = tablesView
        override fun <T> transaction(block: BoardTables.() -> T): T = store.transaction { tablesView.block() }
        override fun <T> writeTransaction(block: BoardTables.() -> T): T = store.writeTransaction { tablesView.block() }
    }

    private inner class Tickets : TicketTable {
        override fun create(ticket: StoredTicket) = store.tickets.create(ticket.toRow())
        override fun update(ticket: StoredTicket) = store.tickets.update(ticket.toRow())
        override fun get(id: String) = store.tickets.get(id)?.toStored()
        override fun all() = store.tickets.all().map { it.toStored() }
        override fun delete(id: String) = store.tickets.delete(id)
        override fun link(link: StoredLink) = store.tickets.link(link.toRow())
        override fun unlink(link: StoredLink) = store.tickets.unlink(link.toRow())
        override fun links() = store.tickets.links().map { it.toStored() }
    }

    private inner class Runs : TicketRunTable {
        override fun insert(run: StoredRun) = store.ticketRuns.insert(TicketRunRow(run.ticketId, run.sessionId, run.startedAt, run.endedAt, run.outcome, run.detail))
        override fun finish(ticketId: String, sessionId: String, endedAt: Long, outcome: String, detail: String?) =
            store.ticketRuns.finish(ticketId, sessionId, endedAt, outcome, detail)

        override fun byTicket(ticketId: String) =
            store.ticketRuns.byTicket(ticketId).map { StoredRun(it.ticketId, it.sessionId, it.startedAt, it.endedAt, it.outcome, it.detail) }
    }

    override val git: BoardGit = object : BoardGit {
        override fun ensureBranch(request: BranchRequest): BranchOutcome {
            request.recordedBranch?.let { fetchMissingTicketBranch(File(request.repo), it) }
            return when (val out = branches.ensure(TicketBranchRequest(request.ticketId, request.title, File(request.repo), request.baseBranch, request.recordedBranch, request.recordedBase))) {
                is TicketBranchOutcome.Ready -> BranchOutcome.Ready(out.worktree.branch, out.worktree.base, out.worktree.path, out.worktree.repoRoot, out.note, out.warning)
                is TicketBranchOutcome.Refused -> BranchOutcome.Refused(out.reason)
            }
        }

        override fun baseRef(projectId: String?, epicId: String?): BaseChoice =
            this@LocalBoardHost.baseRef.choose(BaseScope(projectId, epicId)).let { BaseChoice(it.ref, it.source) }

        override fun resolveBase(repo: String, ref: String): String? = branches.resolveBase(File(repo), ref)
        override fun projectDirectory(projectId: String?): String? = this@LocalBoardHost.projectDirectory(projectId)
        override fun defaultRepo(): String? = this@LocalBoardHost.defaultRepo?.path
        override fun ahead(repo: String, branch: String, base: String): Int? = branches.ahead(File(repo), branch, base)
        override fun handoffFacts(repo: String, branch: String, base: String, worktree: String?, maxCommits: Int): BranchHandoffFacts? {
            val root = File(repo)
            val tip = branches.tip(root, branch) ?: return null
            return BranchHandoffFacts(
                tip = tip,
                ahead = branches.ahead(root, branch, base) ?: 0,
                subjects = branches.subjects(root, branch, base, maxCommits),
                dirtyFiles = worktree?.let { branches.dirtyFiles(File(it)) },
            )
        }
        override fun projectFolders(): Boolean = sessionManager()?.projectFolders != null
        override fun projectFolderFind(projectId: String?): String? = sessionManager()?.projectFolders?.find(projectId)?.path
        override fun projectFolderRequire(projectId: String?): String =
            (sessionManager()?.projectFolders ?: throw IllegalArgumentException(BoardGit.NO_GIT)).require(projectId).path
    }

    override val sessions: BoardSessions = object : BoardSessions {
        override fun start(request: SessionStart): SessionStartResult {
            request.runner?.takeIf { it != com.superiorsoftllc.infantry.daemon.api.orchestrator.RunnerPool.LOCAL }?.let { return startRemote(it, request) }
            val manager = sessionManager() ?: return SessionStartResult.Refused(BoardSessions.NO_SESSIONS)
            return try {
                val record = manager.startNew(
                    prompt = request.prompt,
                    workingDir = request.workingDir?.let(::File),
                    branchHint = request.branchHint,
                    providerId = request.providerId,
                    model = request.model,
                    effort = request.effort,
                    agentId = request.agentId,
                    role = request.role,
                    ticketId = request.ticketId,
                    contextSources = request.contextSources.map { ContextSource(it.source, it.chars) },
                    projectId = request.projectId,
                    contextLimit = request.contextLimit,
                    instance = request.instance?.toCore(),
                    unattended = true,
                    launchOrigin = origin(request.origin),
                    preWorktree = request.worktree?.let {
                        WorktreeRecord(sessionId = "", repoRoot = it.repoRoot, path = it.worktreePath, branch = it.branch, baseRef = it.base, createdAt = clock())
                    },
                )
                SessionStartResult.Started(SessionViews.of(record))
            } catch (e: IllegalArgumentException) {
                SessionStartResult.Refused(e.message ?: "session start refused")
            }
        }

        override fun checkSpawn(providerId: String?, model: String?): String? {
            val manager = sessionManager() ?: return BoardSessions.NO_SESSIONS
            return try {
                manager.checkSpawn(providerId, model)
                null
            } catch (e: IllegalArgumentException) {
                e.message ?: "spawn refused"
            }
        }

        override fun launchGate(providerId: String?, origin: String, ticketId: String?): LaunchVerdict =
            when (val d = sessionManager()?.launchCheck(providerId, origin(origin), sessionManager()?.bindingPolicy?.bindingFor(ticketId))) {
                is GateDecision.Refused -> LaunchVerdict(refusal = d.reason, disposition = d.disposition)
                is GateDecision.Warn -> LaunchVerdict(warning = d.reason)
                else -> LaunchVerdict()
            }

        override fun get(sessionId: String): SessionView? = sessionManager()?.get(sessionId)?.let(SessionViews::of)
        override fun list(): List<SessionView> = sessionManager()?.list().orEmpty().map(SessionViews::of)

        // RUNNER-UNIFY-2: through the session's runner (RunnerPool.linkFor), local or remote alike; an unknown session answers false.
        override fun kill(sessionId: String, force: Boolean, by: String, reason: String?): Boolean {
            val manager = sessionManager() ?: return false
            val command = com.superiorsoftllc.infantry.daemon.api.orchestrator.SessionCommand(
                com.superiorsoftllc.infantry.daemon.api.orchestrator.SessionCommand.KILL, force = force, by = by, reason = reason,
            )
            val pool = runners ?: return com.superiorsoftllc.infantry.daemon.LocalRunner.execute(manager, sessionId, command).ok
            return com.superiorsoftllc.infantry.daemon.LocalRunner.command(pool, manager, sessionId, command, runCatching(unifiedCommands).getOrDefault(true)).ok
        }

        override fun lastAssistantText(sessionId: String): String? = sessionManager()?.lastReply(sessionId)

        override fun prompt(sessionId: String, text: String): Boolean {
            val manager = sessionManager() ?: return false
            val record = manager.get(sessionId) ?: return false
            if (!record.live || record.isBusy()) return false
            return runCatching { manager.resume(sessionId, text, MessageOrigin.Owner) }.isSuccess
        }

        override fun providers() = sessionManager()?.providers.orEmpty().map { it.toInfo() }
        override fun providerId() = sessionManager()?.providerId.orEmpty()
        override fun hasProvider(id: String) = sessionManager()?.hasProvider(id) ?: false
        override fun providerFor(id: String) = sessionManager()?.providerFor(id)?.toInfo()
        override fun defaultModel(providerId: String?): String? {
            val manager = sessionManager() ?: return null
            return (providerId?.takeIf { it.isNotBlank() }?.let(manager::providerFor) ?: manager.providers.firstOrNull())?.defaultModel()
        }

        override fun defaultWorkingDir(): String? = sessionManager()?.defaultWorkingDir?.path
        override fun workEvents(sessionId: String) = sessionManager()?.workEvents(sessionId) ?: 0
        override fun requireAllowedRole(role: String?): String? {
            val manager = sessionManager() ?: return null
            return try {
                manager.requireAllowedRole(role)
                null
            } catch (e: IllegalArgumentException) {
                e.message ?: "role not allowed"
            }
        }

        override fun detail(sessionId: String) = sessionManager()?.get(sessionId)?.let(::detailOf)
        override fun details() = sessionManager()?.list().orEmpty().map(::detailOf)
        // QA-FIX-A: an event raised under the lock and delivered after it (DEADLOCK-1) counts as under it, so the board still queues it.
        override fun holdsSessionsLock(): Boolean = sessionManager()?.let { Thread.holdsLock(it) || it.deliveringLockedEvent() } ?: false
        override fun available(): Boolean = sessionManager() != null
        override fun instance(sessionId: String) = sessionManager()?.instance(sessionId)?.toBoard()

        override fun subscribe(listener: BoardSessionListener): AutoCloseable {
            listeners.add(listener)
            return AutoCloseable { listeners.remove(listener) }
        }

        override fun remove(sessionId: String): String? {
            val manager = sessionManager() ?: return BoardSessions.NO_SESSIONS
            return try {
                manager.delete(sessionId)
                null
            } catch (e: IllegalArgumentException) {
                e.message ?: "session not removed"
            }
        }
    }

    override val events: TicketEventAppender get() = tablesView.events

    override val settings: BoardSettings = object : BoardSettings {
        override fun get(key: String): String? = store.settings.get(key)?.value
    }

    override val agents: BoardAgents = object : BoardAgents {
        override fun get(id: String) = agentProfiles?.get(id)?.toAgent()
        override fun list() = agentProfiles?.list().orEmpty().map { it.toAgent() }
        override fun save(agent: BoardAgent): BoardAgent =
            (agentProfiles ?: throw UnsupportedOperationException(BoardAgents.UNAVAILABLE)).save(agent.toProfile()).toAgent()

        override fun delete(id: String) {
            (agentProfiles ?: throw UnsupportedOperationException(BoardAgents.UNAVAILABLE)).delete(id)
        }

        override fun instanceFor(profileId: String?, role: String?) = agentProfiles?.instanceFor(profileId, role)?.toBoard()
        override fun withSkills(instance: BoardRoleInstance, ticketId: String?): BoardRoleInstance {
            val manager = sessionManager() ?: return instance
            return manager.withSkills(instance.toCore(), manager.bindingPolicy.bindingFor(ticketId)).toBoard()
        }

        override fun roleKnown(role: String) = agentProfiles?.roleKnown(role) ?: false
        override fun executorFor(agent: BoardAgent?, provider: String?, model: String?, effort: String?, role: String?): BoardExecutor {
            val profiles = agentProfiles ?: return super.executorFor(agent, provider, model, effort, role)
            return profiles.executorFor(agent?.toProfile(), provider, model, effort, role).let { BoardExecutor(it.provider, it.model, it.effort) }
        }

        override fun validateExecutor(provider: String?, model: String?, effort: String?): BoardExecutor {
            val profiles = agentProfiles ?: return super.validateExecutor(provider, model, effort)
            return profiles.validateExecutor(provider, model, effort).let { BoardExecutor(it.provider, it.model, it.effort) }
        }
    }

    override val runs: BoardRunHealth = object : BoardRunHealth {
        override fun isAttemptOver(state: String) =
            runCatching { RunOutcomes.isAttemptOver(SessionState.valueOf(state)) }.getOrDefault(false)

        override fun outcomeOf(sessionId: String): BoardRunOutcome? =
            sessionManager()?.get(sessionId)?.let { RunOutcomes.of(it) }?.let { BoardRunOutcome.valueOf(it.name) }

        override fun isRetryable(outcome: BoardRunOutcome) = RunOutcomes.isRetryable(FinishedPayload.Outcome.valueOf(outcome.name))
        override fun workerProblem(sessionId: String?): String? {
            // RUNNER-E2E-2: a session on a remote runner is carried by that runner (its loss is the runner watch's business), not gone.
            if (sessionId != null && runners?.runnerOf(sessionId)?.let { it != com.superiorsoftllc.infantry.daemon.api.orchestrator.RunnerPool.LOCAL } == true) return null
            return TicketWorkerHealth.problem(sessionId?.let { sessionManager()?.get(it) }, sessionId)
        }

        override fun slowToBrief(sessionId: String, now: Long, graceMs: Long) =
            sessionManager()?.get(sessionId)?.let { TicketWorkerHealth.slowToBrief(it, now, graceMs) } ?: false
    }

    private val layeredSkills = com.superiorsoftllc.infantry.agents.LayeredSkillStore(store, clock)

    override val context: BoardContextSources = object : BoardContextSources {
        override fun knowledgeSection(projectId: String?, role: String?) =
            listOfNotNull(
                knowledge.section(projectId, role),
                com.superiorsoftllc.infantry.agents.renderLayeredSkills(layeredSkills.resolve(projectId, role), com.superiorsoftllc.infantry.agents.LayerSkillWire.inlineMax(store)),
            ).joinToString("\n\n").ifEmpty { null }
        override fun environmentSection(projectId: String?): String? {
            val on = (store.settings.get(com.superiorsoftllc.infantry.settings.LimitRegistry.HEALTH_BRIEF_ENVIRONMENT.key)?.value
                ?: com.superiorsoftllc.infantry.settings.LimitRegistry.HEALTH_BRIEF_ENVIRONMENT.default.toString()).trim() == "1"
            return if (on) com.superiorsoftllc.infantry.daemon.health.DaemonHealth.briefLine(projectId) else null
        }
        override fun skillsSection(skillIds: List<String>?, include: Boolean?) = skills.section(skillIds, include)
    }

    override val images: BoardImages = object : BoardImages {
        override fun normalize(projectId: String?, requested: List<String>) = ticketImages.normalize(projectId, requested)
        override fun absolute(projectId: String?, relPath: String) = ticketImages.absolute(projectId, relPath)?.path
        override fun release(projectId: String?, dropped: Collection<String>, remaining: Collection<StoredTicket>) =
            ticketImages.release(projectId, dropped, remaining.map { it.toRow() })
    }

    private fun detailOf(r: com.superiorsoftllc.infantry.session.SessionRecord) = BoardSessionDetail(SessionViews.of(r), r.lastHeartbeat, r.isBusy(), r.spawnedAt, r.providerSessionId)

    private fun com.superiorsoftllc.infantry.harness.AgentProvider.toInfo() = BoardProviderInfo(
        id, defaultModel(), contextLimit().support.wire, contextLimit().how, effort().support.wire, effort().how, ::knownModels,
    )

    private fun RoleInstance.toBoard() = BoardRoleInstance(
        agentId, role, instructions,
        traits.map { BoardTrait(it.id, it.version, it.name, it.body, it.check?.kind, it.check?.reportTo, it.check?.fields.orEmpty(), it.check?.reprompt ?: true) },
        skillIds,
    )

    private fun BoardRoleInstance.toCore() = RoleInstance(
        agentId, role, instructions,
        traits.map { t -> TraitSnapshot(t.id, t.version, t.name, t.body, t.checkKind?.let { TraitCheck(it, t.checkReportTo ?: TraitCheck.DEFAULT_REPORT_TO, t.checkFields, t.checkReprompt) }) },
        skillIds,
    )

    private fun AgentProfile.toAgent() = BoardAgent(id, name, role, provider, model, effort, instructions, projectId, traitIds, skillIds)
    private fun BoardAgent.toProfile() = AgentProfile(
        id = id, name = name, role = role, provider = provider, model = model, instructions = instructions, projectId = projectId,
        effort = effort, traitIds = traitIds, skillIds = skillIds,
    )

    private fun origin(name: String) = if (name == ORIGIN_UNATTENDED) LaunchOrigin.UNATTENDED else LaunchOrigin.HUMAN

    private companion object {
        const val ORIGIN_UNATTENDED = "unattended"
    }
}

/**
 * RUNNER-POOL-8: a ticket whose worker ran on a remote runner (restarted here after that runner was lost) has its branch only on
 * the project's remote. When [repo] lacks the recorded `ticket/` branch, it is fetched from `origin` so the new worker starts from
 * what was last pushed. A branch the checkout has is never touched (no rewind of local work); a failed fetch leaves the branch
 * to be cut as before.
 */
internal fun fetchMissingTicketBranch(repo: File, branch: String, git: com.superiorsoftllc.infantry.worktree.GitCli = com.superiorsoftllc.infantry.worktree.SystemGitCli()) {
    if (!com.superiorsoftllc.infantry.runners.RemoteStarts.isTicketBranch(branch) || !repo.isDirectory) return
    runCatching {
        if (git.run(repo, listOf("rev-parse", "--verify", "--quiet", "refs/heads/$branch")).ok) return
        if (originUrl(repo) == null) return
        git.run(repo, listOf("fetch", "origin", "refs/heads/$branch:refs/heads/$branch"))
    }
}

/**
 * RUNNER-TICKET-ROW-1: after a runner worker's turn, bring its pushed ticket branch to the orchestrator's checkout so the board row
 * has a commit tip and done-enter's merge request has something to merge. The runner is the authority for that branch, so it is
 * forced; git itself refuses when the checkout has the branch checked out, which leaves local work alone. True when it was fetched.
 */
internal fun fetchRunnerTicketBranch(repo: File, branch: String, git: com.superiorsoftllc.infantry.worktree.GitCli = com.superiorsoftllc.infantry.worktree.SystemGitCli()): Boolean {
    if (!com.superiorsoftllc.infantry.runners.RemoteStarts.isTicketBranch(branch) || !repo.isDirectory || originUrl(repo) == null) return false
    return runCatching { git.run(repo, listOf("fetch", "origin", "+refs/heads/$branch:refs/heads/$branch")).ok }.getOrDefault(false)
}

/** `git remote get-url origin` in [repo], or null when it has none. */
/**
 * RUNNER-GIT-IDENT-1: the git identity commits in [repo] get (`git config user.name` / `user.email`, local over global), or null
 * when either is unset. A remote start carries it so the runner's worker commits as a local worker would.
 */
internal fun gitIdentityOf(repo: File): Pair<String, String>? = com.superiorsoftllc.infantry.runners.RunnerLinkServer.userGitIdentity(repo)

internal fun originUrl(repo: File): String? =
    com.superiorsoftllc.infantry.worktree.SystemGitCli().run(repo, listOf("remote", "get-url", "origin")).takeIf { it.ok }?.out()?.takeIf { it.isNotBlank() }
