package com.superiorsoftllc.agenthq.daemon

import com.superiorsoftllc.agenthq.agents.AgentProfiles
import com.superiorsoftllc.agenthq.agents.KnowledgeCounter
import com.superiorsoftllc.agenthq.agents.SkillsSource
import com.superiorsoftllc.agenthq.attention.AttentionKind
import com.superiorsoftllc.agenthq.attention.AttentionStore
import com.superiorsoftllc.agenthq.backlog.BoardEvent
import com.superiorsoftllc.agenthq.backlog.TicketBoard
import com.superiorsoftllc.agenthq.backlog.TicketEventLog
import com.superiorsoftllc.agenthq.backlog.asStartMoves
import com.superiorsoftllc.agenthq.backlog.toInfo
import com.superiorsoftllc.agenthq.context.TicketContext
import com.superiorsoftllc.agenthq.daemon.api.board.TicketEvent
import com.superiorsoftllc.agenthq.daemon.api.usage.UsageMeters
import com.superiorsoftllc.agenthq.epics.EpicStore
import com.superiorsoftllc.agenthq.mvp.BoardHandler
import com.superiorsoftllc.agenthq.mvp.ProjectStore
import com.superiorsoftllc.agenthq.mvp.StoryHandler
import com.superiorsoftllc.agenthq.mvp.StoryHandler.Companion.asStory
import com.superiorsoftllc.agenthq.persistence.AgentHqStore
import com.superiorsoftllc.agenthq.persistence.SqlAgentHqStore
import com.superiorsoftllc.agenthq.role.RoleIds
import com.superiorsoftllc.agenthq.role.RoleService
import com.superiorsoftllc.agenthq.session.SessionManager
import com.superiorsoftllc.agenthq.settings.LimitRegistry
import com.superiorsoftllc.agenthq.settings.SettingsService
import com.superiorsoftllc.agenthq.stories.StoryMigration
import com.superiorsoftllc.agenthq.stories.asStoryTickets
import com.superiorsoftllc.agenthq.ticketfiles.RelatedDescription
import com.superiorsoftllc.agenthq.ticketfiles.RelatedWorkSource
import com.superiorsoftllc.agenthq.ticketfiles.TicketFiles
import com.superiorsoftllc.agenthq.ticketfiles.TicketFilesText
import com.superiorsoftllc.agenthq.triggers.TriggerService
import com.superiorsoftllc.agenthq.usage.UsagePacing
import com.superiorsoftllc.agenthq.worktree.TicketBaseRef
import java.io.File
import java.util.concurrent.atomic.AtomicReference

/**
 * DP-5h-1: everything the daemon wires around the ticket board, in one core class (it used to be inline in `DaemonMain`).
 * `DaemonMain` holds one nullable `BoardWiring?`; null = a daemon with no board (its board frames refuse). Nothing here
 * is new behaviour: the construction order, the threads and the event subscriptions are exactly what `DaemonMain` did.
 *
 * Two phases, because the board's consumers (triggers, the done-worker reaper) are built after the board:
 *  1. [create]: the board, its push sink, the story shims.
 *  2. [attachTicketServices]: the ticket files, the typed ticket-event consumers, the story migration, the queue-mark clear.
 */
class BoardWiring private constructor(
    val board: TicketBoard,
    val stories: StoryHandler,
    val storyBoard: BoardHandler,
) {
    /** DP-5h-3e-a: the core ports over this core-built board; goes away with it (DP-5h-4). */
    val ports: BoardPorts = CoreBoardPorts(this)

    /** Set by [attachTicketServices]. */
    lateinit var ticketFiles: TicketFiles
        private set
    lateinit var ticketFilesText: TicketFilesText
        private set
    lateinit var ticketEvents: TicketEventLog
        private set
    internal fun ticketFilesOrNull(): TicketFiles? = if (this::ticketFiles.isInitialized) ticketFiles else null

    /**
     * Phase 2, at the point `DaemonMain` built the ticket files: after the mailbox, the triggers and the reaper exist.
     * The three core consumers subscribe like a plugin would; a cursor survives a restart, so a transition not handled when
     * the daemon stopped is replayed on the next start.
     */
    fun attachTicketServices(
        store: AgentHqStore,
        settings: SettingsService,
        triggers: TriggerService,
        doneWorkerReaper: DoneWorkerReaper,
        relatedRef: AtomicReference<RelatedWorkSource>,
        relatedDescriptionRef: AtomicReference<RelatedDescription>,
        storyMigrationFile: File?,
    ) {
        val database = (store as SqlAgentHqStore).database
        // TICKET-FILES (55476f3c): a ticket that goes done records the files it changed (one git diff, off the board's
        // lock, on its own thread) and pins its tip under refs/agenthq/tickets/<id>.
        val files = TicketFiles(
            db = database,
            tickets = { store.tickets.all().map { it.toInfo() } },
            repoFor = { projectId -> board.cutRepo(projectId) },
        )
        ticketFiles = files
        relatedRef.set(
            com.superiorsoftllc.agenthq.ticketfiles.LiveRelatedWork(
                files, ticket = { id -> store.tickets.get(id)?.toInfo() },
                rows = { store.tickets.all().map { it.toInfo() } }, links = { store.tickets.links().map { it.toInfo() } }, config = settings::relatedConfig,
            ),
        )
        // KNOWLEDGE-PTR-1 (C): the stored RELATED (auto) block a ticket save writes at the top of its description.
        relatedDescriptionRef.set(com.superiorsoftllc.agenthq.ticketfiles.RelatedDescriptionWriter(files, settings::relatedConfig))
        ticketFilesText = TicketFilesText(files) { id -> store.tickets.get(id)?.title }
        val ticketFilesExec = java.util.concurrent.Executors.newSingleThreadExecutor { r ->
            Thread(r, "agenthq-ticket-files").also { it.isDaemon = true }
        }
        // BOARD-API-2 (DP-4b): the typed, durable, ordered ticket events.
        val events = TicketEventLog(database)
        ticketEvents = events
        board.events = events
        events.subscribe("core.triggers") { e -> if (e.isTransition) triggers.onTicketTransition(e.ticketId, e.to!!) }
        events.subscribe("core.done-worker-reaper") { e -> if (e.isTransition) doneWorkerReaper.onTicketTransition(e.ticketId, e.to!!) }
        events.subscribe("core.ticket-files") { e ->
            if (e.kind == TicketEvent.DONE) ticketFilesExec.execute {
                runCatching { store.tickets.get(e.ticketId)?.let { files.record(it.toInfo()) } }
                    .onFailure { System.err.println("[agentHq] ticket files of ${e.ticketId} not recorded: ${it.message}") }
            }
        }
        // E1-a: fold the story board into the ticket board, once; idempotent, resumes after a crash. A failure is
        // logged and the daemon still starts (the next start retries the stories not yet mapped).
        storyMigrationFile?.let { file ->
            runCatching { StoryMigration(file, board, database).run() }
                .onFailure { System.err.println("[agentHq] story migration failed, will retry next start: $it") }
        }
        val clearedQueueMarks = board.clearQueueMarks()
        println("[agentHq] backlog queue marks cleared=$clearedQueueMarks (nothing starts on boot)")
    }

    /** Daemon shutdown, first of the board's steps (before the handover and the manager): stop draining the board's queue. */
    fun stopQueueDrain() = board.stopQueueDrain()

    /** Daemon shutdown, after the manager: everything the kills queued is applied before the store is released. */
    fun close() = board.close()

    companion object {
        /** The git checkout a directory is in (the banner line names it); core asks here rather than the board class. */
        fun gitRootOf(dir: File): File? = TicketBoard.gitRootOf(dir)

        /** Phase 1: exactly the block `DaemonMain` ran from `val board = TicketBoard(...)` to the story shims. */
        fun create(
            store: AgentHqStore,
            context: TicketContext,
            agents: AgentProfiles,
            manager: SessionManager,
            repo: File?,
            epics: EpicStore,
            projectStore: ProjectStore,
            baseRef: TicketBaseRef,
            knowledge: KnowledgeCounter,
            skills: SkillsSource,
            settings: SettingsService,
            usagePacing: UsagePacing,
            attention: AttentionStore,
            roles: RoleService,
            relatedDescriptionRef: AtomicReference<RelatedDescription>,
            eventSink: (BoardEvent) -> Unit,
            onEpicChanged: (String) -> Unit,
        ): BoardWiring {
            val board = TicketBoard(
                store, context = context, agents = agents, sessions = manager, repo = repo,
                // BASE-1c: the ticket's epic's base branch, then its project's (BASE-1a), then the daemon's
                // (AGENTHQ_BASE_BRANCH, else none: the start is refused).
                baseRef = TicketBaseRef.epicFirst(epics::baseBranchOf, TicketBaseRef.projectFirst(projectStore::baseBranchOf, baseRef)),
                knowledge = knowledge, skills = skills,
                exitBehavior = settings::ticketExitBehavior,
                autoDoneMinOutputs = { settings.limit(LimitRegistry.AUTO_DONE_MIN_OUTPUTS) },
                weightExecutors = settings::weightExecutors,
                weightContext = settings::weightContext,
                // USAGE-PACE (1.121.0): an agent's / the daemon's ticket start beyond the plan allowance is refused.
                pacing = { providerId, model ->
                    val pid = providerId ?: manager.providers.firstOrNull()?.id ?: UsageMeters.CLAUDE
                    usagePacing.check(UsageMeters.meterFor(pid, model ?: manager.providerFor(pid)?.defaultModel()))
                },
                laneCap = projectStore::laneCapOf,
                // ROUTING-1: the project's routing rules pick a start's executor; a refused rule parks the ticket and is raised.
                routingRules = projectStore::routingRulesOf,
                routingRefused = { ticketId, _, detail ->
                    attention.raise(kind = AttentionKind.LAUNCH_BLOCKED, ticketId = ticketId, outcome = "routing_refused", detail = detail)
                },
                // NOGIT-1: a project's own directory decides where its start runs / cuts, also with project folders off.
                projectDirectory = { id -> id?.let { runCatching { projectStore.get(it).directory }.getOrNull() } },
                // BUG-ASSIGN-1: a refused role lists the catalog's ids (custom roles included; the fallback persona not).
                roleIds = { roles.all().map { it.id }.filter { it != RoleIds.CUSTOM } },
                // IMG-3: a ticket's images live under its project's directory (.agenthq/uploads/ticket/).
                images = com.superiorsoftllc.agenthq.backlog.TicketImages { projectId ->
                    runCatching { projectStore.get(projectId) }.getOrNull()
                        ?.directory?.takeIf { it.isNotBlank() }?.let(::File)?.takeIf { it.isDirectory }
                },
                // KNOWLEDGE-PTR-1 (C): set once the file index exists (it needs the board's cutRepo); NONE until then.
                relatedDescription = RelatedDescription { draft -> relatedDescriptionRef.get().describe(draft) },
            )
            board.eventSink = eventSink
            // E1-c (contract 1.62.0): the story frames are deprecated shims over the ticket board. An old story id
            // resolves through E1-a's `story_migration`; a create that names an epic pushes that epic's frame (its
            // storyIds now list the new ticket) to every client, exactly the frame an `epic_save` answers with.
            val migrated = (store as SqlAgentHqStore).database.story_migrationQueries
            val stories = StoryHandler(
                board = board.asStoryTickets(),
                resolve = { storyId -> migrated.ticketFor(storyId).executeAsOneOrNull() },
                onEpicChanged = onEpicChanged,
            )
            val storyBoard = BoardHandler(stories = { board.list().map { it.asStory() } })
            return BoardWiring(board, stories, storyBoard)
        }
    }
}

/** The adapter of the core-built [TicketBoard] to the core's [BoardPorts]; behaviour is the direct calls it replaces. */
private class CoreBoardPorts(private val wiring: BoardWiring) : BoardPorts {
    private val board get() = wiring.board
    override fun ticket(id: String) = runCatching { board.get(id) }.getOrNull()?.let { BoardTicketRef(it.sessionId, it.projectId, it.status) }
    override fun starterOf(workerSessionId: String) = board.starterOf(workerSessionId)
    override fun titlesBySession() = board.list().mapNotNull { t -> t.sessionId?.let { it to t.title } }.toMap()
    override fun brief(sessionId: String) = board.brief(sessionId)
    @Suppress("UNCHECKED_CAST")
    override fun startMoves() = board.asStartMoves() as StartMoves<Any>
    override fun saveFilesRead(ticketId: String, line: String) { wiring.ticketFilesOrNull()?.saveFilesRead(ticketId, line) }
    override fun clearQueueMarks() { board.clearQueueMarks() }
    override fun sweepWorkers(graceMs: Long, flag: (String, String?, String) -> Unit) = board.sweepWorkers(graceMs, flag)
}
