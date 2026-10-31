package com.superiorsoftllc.agenthq.backlog

import com.superiorsoftllc.agenthq.ticketfiles.RelatedDescription
import com.superiorsoftllc.agenthq.ticketfiles.RelatedDraft
import com.superiorsoftllc.agenthq.settings.RoutingRules
import com.superiorsoftllc.agenthq.settings.WeightExecutor
import com.superiorsoftllc.agenthq.settings.WeightExecutors
import com.superiorsoftllc.agenthq.usage.GateDecision
import com.superiorsoftllc.agenthq.usage.LaunchOrigin
import com.superiorsoftllc.agenthq.agents.AgentProfiles
import com.superiorsoftllc.agenthq.agents.KnowledgeCounter
import com.superiorsoftllc.agenthq.agents.SkillsSource
import com.superiorsoftllc.agenthq.agents.firstTurn
import com.superiorsoftllc.agenthq.agents.section
import com.superiorsoftllc.agenthq.context.ContextInput
import com.superiorsoftllc.agenthq.context.LinkLine
import com.superiorsoftllc.agenthq.context.ReportTo
import com.superiorsoftllc.agenthq.context.TicketContext
import com.superiorsoftllc.agenthq.orchestration.RunOutcomes
import com.superiorsoftllc.agenthq.persistence.AgentHqStore
import com.superiorsoftllc.agenthq.daemon.api.board.BoardHost
import com.superiorsoftllc.agenthq.daemon.api.board.StoredRun
import com.superiorsoftllc.agenthq.daemon.api.board.TicketEventAppender
import com.superiorsoftllc.agenthq.daemon.plugins.LocalBoardHost
import com.superiorsoftllc.agenthq.daemon.plugins.toRow
import com.superiorsoftllc.agenthq.daemon.plugins.toStored
import com.superiorsoftllc.agenthq.persistence.TicketLinkRow
import com.superiorsoftllc.agenthq.persistence.TicketRow
import com.superiorsoftllc.agenthq.protocol.AgentProfile
import com.superiorsoftllc.agenthq.protocol.EffectiveExecutor
import com.superiorsoftllc.agenthq.protocol.ExecutorOverrides
import com.superiorsoftllc.agenthq.protocol.ExecutorField
import com.superiorsoftllc.agenthq.protocol.TicketWeight
import com.superiorsoftllc.agenthq.protocol.FinishedPayload
import com.superiorsoftllc.agenthq.protocol.Ticket
import com.superiorsoftllc.agenthq.usage.PacingVerdict
import com.superiorsoftllc.agenthq.protocol.TicketLink
import com.superiorsoftllc.agenthq.protocol.TicketLinkRequest
import com.superiorsoftllc.agenthq.protocol.TicketLinkType
import com.superiorsoftllc.agenthq.protocol.TicketMove
import com.superiorsoftllc.agenthq.protocol.TicketReorder
import com.superiorsoftllc.agenthq.protocol.TicketRun
import com.superiorsoftllc.agenthq.protocol.TicketSave
import com.superiorsoftllc.agenthq.protocol.SkillsMode
import com.superiorsoftllc.agenthq.settings.requireKnownSkills
import com.superiorsoftllc.agenthq.protocol.TicketStart
import com.superiorsoftllc.agenthq.protocol.TicketStatus
import com.superiorsoftllc.agenthq.protocol.TicketType
import com.superiorsoftllc.agenthq.role.RoleIds
import com.superiorsoftllc.agenthq.session.SessionEvent
import com.superiorsoftllc.agenthq.session.SessionManager
import com.superiorsoftllc.agenthq.worktree.BaseScope
import com.superiorsoftllc.agenthq.worktree.TicketBaseRef
import com.superiorsoftllc.agenthq.worktree.TicketBranchOutcome
import com.superiorsoftllc.agenthq.worktree.TicketBranchRequest
import com.superiorsoftllc.agenthq.worktree.TicketBranches
import com.superiorsoftllc.agenthq.worktree.TicketWorktree
import com.superiorsoftllc.agenthq.worktree.WorktreeRecord
import com.superiorsoftllc.agenthq.session.SessionRecord
import com.superiorsoftllc.agenthq.session.SessionState
import com.superiorsoftllc.agenthq.session.TicketWorkerHealth
import com.superiorsoftllc.agenthq.session.isBusy
import com.superiorsoftllc.agenthq.session.ContextSource
import java.io.File
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit

/** A change to the board, for the daemon to push to every client. */
sealed interface BoardEvent {
    /** These tickets changed, the one the request named first: one `ticket` frame each. */
    data class Changed(val tickets: List<Ticket>) : BoardEvent

    /** A ticket was deleted or a column reordered: the whole board, as one `tickets` frame. */
    data class Replaced(val tickets: List<Ticket>) : BoardEvent
}

/** The answer to a one-ticket request. [pushed]: it already went to every client as a [BoardEvent]. */
data class TicketAnswer(val ticket: Ticket, val pushed: Boolean)

/** The answer to a whole-board request. [pushed]: it already went to every client as a [BoardEvent]. */
data class BoardAnswer(val tickets: List<Ticket>, val pushed: Boolean)

/** GAP-006b: [REPAIR] is the unbriefed sweep restarting a phantom worker: unattended, never queued. */
private enum class HandOutOrigin { MOVE, START_NEXT, QUEUE_DRAIN, TRIGGER, REPAIR }

/** P9.5d: what a trigger's `prompt` action got from the board -- a session id, or the reason it was refused. */
sealed interface PromptLaunch {
    data class Started(val sessionId: String) : PromptLaunch
    data class Refused(val detail: String) : PromptLaunch
}

private data class AssignmentResolution(
    val profile: AgentProfile? = null,
    val queueLane: String? = null,
    val refusal: String? = null,
    val busy: Boolean = false,
)

/**
 * Story 9.3 (contract 1.6.0): the backlog, ticket hand-out and capacity queue, owned by the daemon.
 *
 * Every rule of the board lives here, so a client that bypasses the UI still meets it:
 *
 * - **Columns** are `todo`, `in_progress`, `pending` and `done`. Positions are contiguous per column, 0 at
 *   the top. A new ticket, and a ticket the daemon moves by itself, goes to the bottom.
 * - **`pending` is derived.** A ticket is held while one of its `blocked_by` prerequisites is not done, or
 *   was closed as won't fix. A held `todo` ticket moves to `pending` and back to `todo` once nothing holds
 *   it, in the same transaction as the change that decided it. A started ticket (`in_progress`, `done`)
 *   is never moved by the daemon: nothing is undone silently. A move INTO `pending` needs an open
 *   prerequisite; a move OUT of it while held is refused, naming what holds it.
 * - **Won't fix** is a flag on a `done` ticket, and it does not release the tickets it blocks.
 * - **Links** are stored in one direction ([TicketLinkType]); `blocked_by` and `child_of` refuse a cycle
 *   and every type refuses a self-link. Deleting a ticket deletes its links and releases what it held.
 * - **Starting** is owned by one serialized `handOut` path shared by ticket moves, start-next and queue drains.
 *
 * Each operation loads the board inside one transaction, applies the request and the rules in memory,
 * writes back only what changed, and then (after the commit, still under the board's lock, so pushes
 * reach clients in commit order) hands every changed ticket to [eventSink]. A refusal throws
 * [IllegalArgumentException] naming the problem, and nothing is written.
 */
class TicketBoard(
    private val host: BoardHost,
    private val clock: () -> Long = System::currentTimeMillis,
    private val idFactory: () -> String = { "ticket-" + UUID.randomUUID().toString().take(8) },
    private val context: TicketContext = TicketContext.NONE,
    private val agents: AgentProfiles? = null,
    private val sessions: SessionManager? = null,
    private val repo: File? = null,
    /** BRANCH-PER-TASK 1/3: cuts each ticket's own branch and worktree, and reuses them on a later start. */
    private val branches: TicketBranches = TicketBranches(),
    /**
     * BRANCH-PER-TASK 1/3 + BUG-REGRESS-1: the one resolver of the ref a start cuts from, asked once per start with
     * the ticket's project (the daemon passes `AGENTHQ_BASE_BRANCH`, else `HEAD`; `HEAD` by default).
     */
    private val baseRef: TicketBaseRef = TicketBaseRef.fixed(TicketBaseRef.HEAD),
    /** P10-K9: the knowledge section of an unattended prompt start, read for that session's project. */
    private val knowledge: KnowledgeCounter = KnowledgeCounter.NONE,
    /** P10-K15: the skills section of an unattended prompt start, from the skills its instance freezes. */
    private val skills: SkillsSource = SkillsSource.NONE,
    /**
     * BUG-ASSIGN-1: the role ids a ticket may name (the daemon passes its live catalog), listed in the refusal of a
     * role that is not one, so the agent that sent a display name ("Backend Developer") can send the id instead.
     */
    private val roleIds: () -> List<String> = { RoleIds.CANONICAL },
    /** IMG-3: resolves, validates and releases ticket images against each ticket's project directory. */
    private val images: TicketImages = TicketImages.NONE,
    /** `tickets.exitBehavior`, read at every run end: `leave` (default) keeps a cleanly finished ticket in_progress; `done` moves it. */
    private val exitBehavior: () -> String = { "leave" },
    /**
     * GAP 855db590 (setting `limits.tickets.autoDoneMinOutputs`, read at every run end): the assistant outputs / tool calls a
     * clean run must have produced, with a provider session opened, before [exitBehavior] `done` moves its ticket; 0 = any
     * clean exit counts (the pre-855db590 rule).
     */
    private val autoDoneMinOutputs: () -> Long = { 1 },
    /** TICKET-WEIGHT: the weight -> executor mapping, read at every start and view. */
    private val weightExecutors: () -> WeightExecutors = { WeightExecutors.DEFAULT },
    /** TOKEN-7: the weight -> {contextLimit, effort} defaults, read at every start; they fill only what the ticket leaves unset. */
    private val weightContext: () -> com.superiorsoftllc.agenthq.settings.WeightContext = { com.superiorsoftllc.agenthq.settings.WeightContext.DEFAULT },
    /** LANE-CAPS: the most agents of a role that may run at once in a project (1 = one per role, today's behaviour). */
    private val laneCap: (projectId: String?, role: String) -> Int = { _, _ -> 1 },
    /** ROUTING-1 (contract 1.128.0): a project's routing rules, in order (empty = none), read at every start and view. */
    private val routingRules: (projectId: String?) -> List<com.superiorsoftllc.agenthq.protocol.RoutingRule> = { emptyList() },
    /**
     * ROUTING-1: told when a rule-driven start was refused (the ticket is parked in todo, never retried on another rule or
     * harness), with the ticket, its project and the reason. The daemon raises an attention item. Called once per distinct
     * (ticket, reason) so a lane drain or a trigger retrying the same ticket does not repeat it.
     */
    private val routingRefused: (ticketId: String, projectId: String?, detail: String) -> Unit = { _, _, _ -> },
    /**
     * USAGE-PACE (contract 1.121.0): whether one more ticket worker may start on the plan meter (provider, model) spends.
     * An agent's or the daemon's start beyond the allowance is refused with the reason; a human move only warns.
     */
    private val pacing: (providerId: String?, model: String?) -> PacingVerdict = { _, _ -> PacingVerdict.Allow() },
    /**
     * NOGIT-1: a project's own stored directory (blank / unknown = null). With no AGENTHQ_REPO and project folders
     * off (a dev or headless daemon), a start still works in THAT folder: a folder that is not a git checkout gets
     * no branch and no worktree, and the daemon's own checkout is never used for it.
     */
    private val projectDirectory: (projectId: String?) -> String? = { null },
    /** KNOWLEDGE-PTR-1 (C): the stored RELATED (auto) block a save or a `relates_to` link writes atop the description. */
    private val relatedDescription: RelatedDescription = RelatedDescription.NONE,
    /** Skill ids that no stored skill carries (part c moves this onto the host); the default knows none, so any named skill is refused. */
    private val unknownSkills: (List<String>) -> List<String> = { it },
) {
    /**
     * DP-5f-3a: the legacy constructor over the real stores (what every caller used before the host): builds a [LocalBoardHost]
     * over them and delegates, so behaviour is whatever the stores did.
     */
    constructor(
    store: AgentHqStore,
    clock: () -> Long = System::currentTimeMillis,
    idFactory: () -> String = { "ticket-" + UUID.randomUUID().toString().take(8) },
    context: TicketContext = TicketContext.NONE,
    agents: AgentProfiles? = null,
    sessions: SessionManager? = null,
    repo: File? = null,
    /** BRANCH-PER-TASK 1/3: cuts each ticket's own branch and worktree, and reuses them on a later start. */
    branches: TicketBranches = TicketBranches(),
    /**
     * BRANCH-PER-TASK 1/3 + BUG-REGRESS-1: the one resolver of the ref a start cuts from, asked once per start with
     * the ticket's project (the daemon passes `AGENTHQ_BASE_BRANCH`, else `HEAD`; `HEAD` by default).
     */
    baseRef: TicketBaseRef = TicketBaseRef.fixed(TicketBaseRef.HEAD),
    /** P10-K9: the knowledge section of an unattended prompt start, read for that session's project. */
    knowledge: KnowledgeCounter = KnowledgeCounter.NONE,
    /** P10-K15: the skills section of an unattended prompt start, from the skills its instance freezes. */
    skills: SkillsSource = SkillsSource.NONE,
    /**
     * BUG-ASSIGN-1: the role ids a ticket may name (the daemon passes its live catalog), listed in the refusal of a
     * role that is not one, so the agent that sent a display name ("Backend Developer") can send the id instead.
     */
    roleIds: () -> List<String> = { RoleIds.CANONICAL },
    /** IMG-3: resolves, validates and releases ticket images against each ticket's project directory. */
    images: TicketImages = TicketImages.NONE,
    /** `tickets.exitBehavior`, read at every run end: `leave` (default) keeps a cleanly finished ticket in_progress; `done` moves it. */
    exitBehavior: () -> String = { "leave" },
    /**
     * GAP 855db590 (setting `limits.tickets.autoDoneMinOutputs`, read at every run end): the assistant outputs / tool calls a
     * clean run must have produced, with a provider session opened, before [exitBehavior] `done` moves its ticket; 0 = any
     * clean exit counts (the pre-855db590 rule).
     */
    autoDoneMinOutputs: () -> Long = { 1 },
    /** TICKET-WEIGHT: the weight -> executor mapping, read at every start and view. */
    weightExecutors: () -> WeightExecutors = { WeightExecutors.DEFAULT },
    /** TOKEN-7: the weight -> {contextLimit, effort} defaults, read at every start; they fill only what the ticket leaves unset. */
    weightContext: () -> com.superiorsoftllc.agenthq.settings.WeightContext = { com.superiorsoftllc.agenthq.settings.WeightContext.DEFAULT },
    /** LANE-CAPS: the most agents of a role that may run at once in a project (1 = one per role, today's behaviour). */
    laneCap: (projectId: String?, role: String) -> Int = { _, _ -> 1 },
    /** ROUTING-1 (contract 1.128.0): a project's routing rules, in order (empty = none), read at every start and view. */
    routingRules: (projectId: String?) -> List<com.superiorsoftllc.agenthq.protocol.RoutingRule> = { emptyList() },
    /**
     * ROUTING-1: told when a rule-driven start was refused (the ticket is parked in todo, never retried on another rule or
     * harness), with the ticket, its project and the reason. The daemon raises an attention item. Called once per distinct
     * (ticket, reason) so a lane drain or a trigger retrying the same ticket does not repeat it.
     */
    routingRefused: (ticketId: String, projectId: String?, detail: String) -> Unit = { _, _, _ -> },
    /**
     * USAGE-PACE (contract 1.121.0): whether one more ticket worker may start on the plan meter (provider, model) spends.
     * An agent's or the daemon's start beyond the allowance is refused with the reason; a human move only warns.
     */
    pacing: (providerId: String?, model: String?) -> PacingVerdict = { _, _ -> PacingVerdict.Allow() },
    /**
     * NOGIT-1: a project's own stored directory (blank / unknown = null). With no AGENTHQ_REPO and project folders
     * off (a dev or headless daemon), a start still works in THAT folder: a folder that is not a git checkout gets
     * no branch and no worktree, and the daemon's own checkout is never used for it.
     */
    projectDirectory: (projectId: String?) -> String? = { null },
    /** KNOWLEDGE-PTR-1 (C): the stored RELATED (auto) block a save or a `relates_to` link writes atop the description. */
    relatedDescription: RelatedDescription = RelatedDescription.NONE,
    ) : this(
        host = LocalBoardHost(store, { sessions }, branches, baseRef, projectDirectory, repo, { null }, clock, agents, knowledge, skills, images),
        clock = clock, idFactory = idFactory, context = context, agents = agents, sessions = sessions, repo = repo, branches = branches, baseRef = baseRef, knowledge = knowledge, skills = skills, roleIds = roleIds, images = images, exitBehavior = exitBehavior, autoDoneMinOutputs = autoDoneMinOutputs, weightExecutors = weightExecutors, weightContext = weightContext, laneCap = laneCap, routingRules = routingRules, routingRefused = routingRefused, pacing = pacing, projectDirectory = projectDirectory, relatedDescription = relatedDescription,
        unknownSkills = { ids -> store.transaction { ids.filter { store.skills.get(it) == null } } },
    )

    /** DP-5f-3a: the board's tables through the host, in the row types the rules below were written against. */
    private val store = RowStore(host)

    /** The board's own [events] log when one is set (the legacy wiring), else the host's appender. Null log = no events, as before. */
    private fun eventAppender(): TicketEventAppender = events?.let { log ->
        object : TicketEventAppender {
            override fun record(before: Map<String, String>, after: Map<String, String>, changedIds: Collection<String>, deleted: Collection<String>) =
                log.record(before, after, changedIds, deleted)

            override fun deliver() = log.deliver()
        }
    } ?: host.events

    /** ROUTING-1: the (ticket, reason) pairs already reported through [routingRefused]. */
    private val reportedRoutingRefusals = java.util.Collections.synchronizedSet(HashSet<String>())

    @Volatile
    private var queueDrainEnabled = true

    /** The board's own thread for session events (see [onSessionEvent]); a daemon, so it never keeps a JVM open. */
    private val sessionEvents = Executors.newSingleThreadExecutor { r ->
        Thread(r, "agenthq-ticket-session-events").also { it.isDaemon = true }
    }

    /** Where each committed change goes; the daemon pushes it to every connected client. */
    @Volatile
    var eventSink: (BoardEvent) -> Unit = {}

    /**
     * BOARD-API-2 (DP-4b): the typed event log (docs/DAEMON-BOARD-API.md). A commit records its events inside its own transaction,
     * and [announce] delivers them after the commit, still under the board monitor (every public board method is `@Synchronized`),
     * so a consumer that takes a lock does so in board -> consumer order. `TriggerService.onTicketTransition` briefly takes the
     * TriggerService monitor (its `fire`); the order board -> TriggerService cannot cycle because TriggerService never calls the
     * board under its own lock -- a trigger's `start_next` runs on its own single-thread executor, off-lock. An unchanged re-commit
     * is no event, so a ship trigger fires once per move into done. Null = the board keeps no log (tests).
     */
    @Volatile
    var events: TicketEventLog? = null

    /**
     * The board in board order: column by column ([TicketStatus.ALL]), each column top first. With a
     * [projectId] only that project's tickets are listed (UI-BOARD-PROJ); without one every ticket is,
     * the answer a legacy client expects. With an [epicId] (E1-b, contract 1.66.0) only the tickets filed
     * under that epic are, still in board order; an epic no ticket names answers an empty list. Both filters
     * given means both must match.
     */
    @Synchronized
    fun list(projectId: String? = null, epicId: String? = null): List<Ticket> = store.transaction {
        val s = load()
        val views = views(s.rows, s.links)
        boardOrder(s.rows.values)
            .map { views.getValue(it.id) }
            .filter { projectId == null || it.projectId == projectId }
            .filter { epicId == null || it.epicId == epicId }
    }

    /** Ticket [id]; throws [IllegalArgumentException] when there is none. */
    @Synchronized
    fun get(id: String): Ticket {
        val wanted = checkId(id)
        return store.transaction {
            val s = load()
            s.require(wanted)
            views(s.rows, s.links).getValue(wanted)
        }
    }

    /**
     * Start the highest todo ticket whose prerequisites and assigned profile have capacity. When none starts, the
     * refusal names why each candidate did not (BUG-REGRESS-1: never a bare "nothing startable" over real refusals).
     */
    @Synchronized
    fun startNext(): TicketAnswer {
        val candidates = store.transaction {
            val s = load()
            boardOrder(s.rows.values)
                .filter { it.status == TicketStatus.TODO && s.holding(it.id).isEmpty() }
                .map { it.id }
        }
        val notStarted = mutableListOf<Ticket>()
        for (ticketId in candidates) {
            val answer = handOut(ticketId, HandOutOrigin.START_NEXT)
            if (answer.ticket.start?.reason == "started") return answer
            notStarted += answer.ticket
        }
        throw IllegalArgumentException(nothingStartable(notStarted))
    }

    /** `nothing startable`, then why the first few candidates did not start, so a refusal is never silent. */
    private fun nothingStartable(notStarted: List<Ticket>): String {
        if (notStarted.isEmpty()) return "nothing startable"
        val shown = notStarted.take(MAX_REFUSALS_SHOWN).joinToString("; ") { ticket ->
            val start = ticket.start
            val why = listOfNotNull(start?.reason?.takeIf { it != "start_refused" }, start?.detail)
                .joinToString(": ").ifEmpty { "not started" }
            "${ticket.id} \"${ticket.title.take(60)}\" did not start: $why"
        }
        val more = notStarted.size - MAX_REFUSALS_SHOWN
        return "nothing startable: $shown" + if (more > 0) "; and $more more did not start" else ""
    }

    /**
     * Story 9.5: a trigger's `start_next` -- an UNATTENDED hand-out (the launch gate applies) of the highest
     * startable todo ticket, optionally only one assigned to [agentFilter] (a profile id or `role:<role>`).
     * Null when no todo ticket qualifies: `nothing_to_start`, which is not a failure. Otherwise the started
     * answer, or the last refusal when every candidate was refused. A busy lane is refused, never queued: a
     * trigger does not leave work behind for a later drain.
     */
    @Synchronized
    fun startNextUnattended(agentFilter: String? = null): TicketAnswer? {
        val candidates = store.transaction {
            val s = load()
            boardOrder(s.rows.values)
                .filter { it.status == TicketStatus.TODO && s.holding(it.id).isEmpty() }
                .filter { matchesTriggerAgent(it.agentId, agentFilter) }
                .map { it.id }
        }
        var last: TicketAnswer? = null
        for (ticketId in candidates) {
            val answer = handOut(ticketId, HandOutOrigin.TRIGGER)
            if (answer.ticket.start?.reason == "started") return answer
            last = answer
        }
        return last
    }

    /**
     * P9.5d: a trigger's `prompt` action -- an UNATTENDED session for [agentId] (a profile id or `role:<role>`)
     * whose first message is [prompt] (after the profile's own instructions, like a ticket start), with NO
     * ticket: nothing on the board moves. It takes the same road a ticket hand-out does -- the same assignment
     * resolution, executor resolution (ROLE-1b), spawn check and launch gate (an exhausted provider or spent
     * budget refuses) -- but never queues: a busy agent is refused, because a trigger does not leave work
     * behind for a later drain. It runs in the daemon's shared checkout ([repo]); a ticketless run has no
     * branch to cut.
     */
    @Synchronized
    fun startPromptUnattended(agentId: String, prompt: String, projectId: String? = null): PromptLaunch {
        val sessionManager = sessions ?: return PromptLaunch.Refused("ticket session manager is unavailable")
        val profiles = agents ?: return PromptLaunch.Refused("agent profiles are unavailable")
        val assignment = resolveAssignment(agentId, profiles, sessionManager)
        assignment.refusal?.let { return PromptLaunch.Refused(it) }
        if (assignment.busy) return PromptLaunch.Refused("profile lane '${assignment.queueLane}' is at capacity")
        val profile = assignment.profile ?: return PromptLaunch.Refused("no profile resolved for prompt")
        val executor = profiles.executorFor(profile, null, null, null)
        return try {
            sessionManager.checkSpawn(executor.provider, executor.model)
            // TRAITS-T4/T5: the instance is resolved first and passed in, so the stored row is what this turn rendered.
            // P10-K15: with the skill ids the row will freeze, so the turn renders exactly those.
            val instance = profiles.instanceFor(profile.id, profile.role)?.let { sessionManager.withSkills(it, null) }
            // P10-K9: the session's own project (TRIGGER-NUDGE: the trigger's, when it names one).
            val first = firstTurn(instance, profile.role, profile.id, prompt, knowledge.section(projectId, profile.role), skills.section(instance))
            val started = sessionManager.startNew(
                prompt = first,
                instance = instance,
                workingDir = repo,
                branchHint = null,
                providerId = executor.provider,
                model = executor.model,
                effort = executor.effort,
                agentId = profile.id,
                role = profile.role,
                ticketId = null,
                projectId = projectId,
                unattended = true,
                launchOrigin = LaunchOrigin.UNATTENDED,
            )
            PromptLaunch.Started(started.id)
        } catch (e: IllegalArgumentException) {
            PromptLaunch.Refused(e.message ?: "session start refused")
        }
    }

    /** Clear volatile capacity-queue marks at boot; a restart never launches work automatically. */
    @Synchronized
    // Read-then-write: write-first, so a concurrent writer cannot stale the read (BUG-BoardCommitImmediate).
    fun clearQueueMarks(): Int = store.writeTransaction {
        val marked = store.tickets.all().filter { it.queuedFor != null || it.queuedAt != null }
        marked.forEach { store.tickets.update(it.copy(queuedFor = null, queuedAt = null)) }
        marked.size
    }

    /** Prevent shutdown-time session kills from starting another queued ticket. */
    fun stopQueueDrain() {
        queueDrainEnabled = false
    }

    /**
     * Stop the board's own writer thread and WAIT for the session events already queued to be applied.
     *
     * [onSessionEvent] cannot apply an event inline when it arrives under the session manager's monitor
     * -- a killed run's exit is exactly that case -- so it hands the event to [sessionEvents]. The work it
     * defers is a WRITE: `ensureRunRow` and `finishTicketRun` write `ticket_runs` for that run. A daemon
     * that stops its session manager and returns while such a write is still queued is not stopped, and
     * the consequences are not cosmetic: every restart test boots a SECOND daemon over the state
     * directory the first one left, so the two contend the same SQLite file, whose busy handler gives up
     * after a fixed 5 s. `TicketStartDaemonTest.aRestartClearsQueueMarksAndStartsNothing` was reported
     * failing exactly that way: `SQLITE_BUSY The database file is locked` from `TicketBoard.clearQueueMarks`.
     *
     * Drained, not dropped ([shutdown], never [shutdownNow]): the ticket outcome is the durable record of
     * the run, so it is allowed to land rather than cancelled. Bounded, so a wedged write cannot hang a
     * shutdown -- and a wait that gives up is REPORTED, never silent.
     */
    fun close() {
        sessionEvents.shutdown()
        if (!sessionEvents.awaitTermination(CLOSE_DRAIN_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
            System.err.println("[agentHq] board session events did not drain within ${CLOSE_DRAIN_TIMEOUT_MS}ms")
        }
    }

    /**
     * The daemon's fourth session-event arm: persist ticket outcomes, then drain that profile's lane.
     *
     * [handOut] takes the board monitor before the session-manager monitor, and the session manager fires this
     * while it holds its own monitor (its status, exit and turn events). Waiting for the board there would invert
     * that order and deadlock, so such an event is queued to [sessionEvents] (one thread, FIFO) and the session
     * thread returns at once. An event on a thread that already holds the board monitor (a hand-out's own start)
     * applies inline, so its run row exists before the hand-out answers. Any other event (process output) holds no
     * lock, so it waits for the board as before. The exited record is read here, on the emitting thread.
     */
    fun onSessionEvent(event: SessionEvent) {
        val exited = (event as? SessionEvent.Exit)?.let { sessions?.get(it.sessionId) }
        when {
            Thread.holdsLock(this) -> applySessionEvent(event, exited)
            sessions?.let { Thread.holdsLock(it) } == true -> try {
                sessionEvents.execute { applySessionEvent(event, exited) }
            } catch (_: RejectedExecutionException) {
                // The board is already closed ([close]). Only Status and Exit write anything -- they carry the
                // ticket outcome -- and the session manager cannot emit one once [shutdown] has removed every
                // run, so a late event here is process output, which applies nothing. Same shape as
                // Orchestrator.onSessionEvent: a no-op after close(), never an exception on a session thread.
            }
            else -> applySessionEvent(event, exited)
        }
    }

    @Synchronized
    private fun applySessionEvent(event: SessionEvent, exited: SessionRecord?) {
        when (event) {
            is SessionEvent.Status -> {
                val record = event.session
                if (record.ticketId != null && record.runCount == 0) {
                    ensureRunRow(record)
                    if (record.state == SessionState.FAILED) {
                        finishTicketRun(record)
                        publishRunUpdate(record.ticketId)
                        drainProfileLane(record)
                    }
                }
            }
            is SessionEvent.Exit -> {
                val record = exited ?: return
                val ticketId = record.ticketId ?: return
                ensureRunRow(record)
                finishTicketRun(record)
                publishRunUpdate(ticketId)
                drainProfileLane(record)
            }
            else -> Unit
        }
    }

    /** Create a ticket (no id) or edit one (see [TicketSave]). */
    @Synchronized
    fun save(request: TicketSave): TicketAnswer {
        val editing = request.id?.trim()?.takeIf { it.isNotEmpty() }
        val title = request.title?.let { checkTitle(it) }
        val description = request.description?.let { checkDescription(it) }
        val clearType = request.ticketType != null && request.ticketType!!.isBlank()
        val type = request.ticketType?.takeIf { it.isNotBlank() }?.let { checkType(it) }
        if (editing == null) require(title != null) { "title is required to create a ticket" }
        // BACKLOG (1.131.0): a create enters todo (default) or backlog; an edit never changes the column.
        val createStatus = request.status?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }?.also { wanted ->
            require(editing == null) { "status is set on a create only; move a ticket with ticket_move" }
            require(wanted == TicketStatus.TODO || wanted == TicketStatus.BACKLOG) {
                "a new ticket enters ${TicketStatus.TODO} or ${TicketStatus.BACKLOG}, not '${wanted.take(40)}'"
            }
        } ?: TicketStatus.TODO

        val existing = editing?.let { wanted ->
            store.transaction { store.tickets.get(wanted) }
                ?: throw IllegalArgumentException(
                    "no ticket '${wanted.take(80)}' (ticket_save creates a ticket only when it carries no id)",
                )
        }
        val agentId = when {
            // ORIGIN-SCRUB-1a: an older client's `role:<retired id>` is stored under the id that replaced it.
            request.agentId != null -> request.agentId!!.trim().takeIf { it.isNotEmpty() }?.let(::normalizeRoleAgentId)
            existing != null -> existing.agentId
            // BUG-ROLE-3: no role given means unassigned, never the first profile's role.
            else -> null
        }
        // BUG-ROLE-3: a role this request names must be in the live catalog; a stored one (legacy) stays editable.
        validateAgentId(agentId, checkCatalog = request.agentId != null)
        val provider = when {
            request.provider == null -> existing?.provider
            else -> request.provider!!.trim().lowercase().takeIf { it.isNotEmpty() }
        }
        val model = when {
            request.model == null -> existing?.model
            else -> request.model!!.trim().takeIf { it.isNotEmpty() }
        }
        // MVP Sprint-Zero: a blank or absent projectId on an edit keeps the project the ticket already has.
        val projectId = request.projectId?.trim()?.takeIf { it.isNotEmpty() } ?: existing?.projectId
        // M2-17 (contract 1.60.0): absent or null keeps the current epic; on an edit an empty one clears it (None).
        val epicId = when {
            request.epicId == null -> existing?.epicId
            else -> request.epicId!!.trim().takeIf { it.isNotEmpty() }
        }
        val useWorktree = request.useWorktree ?: existing?.useWorktree
        val worktreeBranch = when {
            request.worktreeBranch == null -> existing?.worktreeBranch
            else -> request.worktreeBranch!!.trim().takeIf { it.isNotEmpty() }
        }
        val includeLinkedTasksContext = request.includeLinkedTasksContext ?: existing?.includeLinkedTasksContext
        // P10-K11 (K-D9): absent or null keeps the stored list and `[]` clears it; the mode and the switch keep the same way.
        val skillIds = request.skillIds?.map { it.trim() } ?: existing?.skillIds.orEmpty()
        if (request.skillIds != null) requireKnownSkills(skillIds, "skillIds") { all -> unknownSkills(all) }
        val skillsMode = request.skillsMode?.let { mode ->
            mode.trim().lowercase().also { require(it in SkillsMode.ALL) { "skillsMode must be merge or replace, was '${mode.take(40)}'" } }
        } ?: existing?.skillsMode ?: SkillsMode.MERGE
        val includeSkillsContext = request.includeSkillsContext ?: existing?.includeSkillsContext
        // IMG-3: absent or null keeps the stored images, `[]` clears them; every path must be the project's ticket upload.
        val imagePaths = request.images?.let { images.normalize(projectId, it) } ?: existing?.images.orEmpty()
        // TICKET-WEIGHT: absent or null keeps the weight, 0 clears it, 1..5 sets it.
        val weight = when (request.weight) {
            null -> existing?.weight
            TicketWeight.CLEAR -> null
            else -> request.weight.also { require(it in TicketWeight.RANGE) { "weight must be ${TicketWeight.RANGE} (or ${TicketWeight.CLEAR} to clear), was $it" } }
        }
        // CONTEXT-LIMIT-1: absent or null keeps the limit, 0 clears it, a token count sets it (ContextLimits' range).
        val contextLimit = when (request.contextLimit) {
            null -> existing?.contextLimit
            com.superiorsoftllc.agenthq.protocol.TicketContextLimit.CLEAR -> null
            else -> request.contextLimit.also { v -> com.superiorsoftllc.agenthq.session.ContextLimits.problem(v)?.let { throw IllegalArgumentException(it) } }
        }
        // EFFORT-1: absent or null keeps the effort, "" / off / default clears it, a level sets it; an unknown level refuses the save.
        val effort = if (request.effort == null) existing?.effort else {
            val parsed = com.superiorsoftllc.agenthq.session.EffortLevels.parse(request.effort)
            parsed.error?.let { throw IllegalArgumentException(it) }
            parsed.value
        }
        validateExecutorPins(agentId, provider, model)
        // KNOWLEDGE-PTR-1 (C): a save that sets the title or the description rewrites the stored RELATED (auto) block.
        val described = if (title != null || description != null) {
            describeRelated(editing, title ?: existing!!.title, description ?: existing?.description.orEmpty(), projectId, extraLink = null)
        } else null

        var primary = editing ?: ""
        val outcome = commit { s ->
            if (editing == null) {
                primary = newId(s)
                s.create(
                    TicketRow(
                        id = primary,
                        title = title!!,
                        description = described ?: description ?: "",
                        ticketType = type,
                        status = createStatus,
                        position = 0, // renumbered to the bottom of its column
                        createdAt = s.now,
                        updatedAt = s.now,
                        agentId = agentId,
                        provider = provider,
                        model = model,
                        useWorktree = useWorktree,
                        worktreeBranch = worktreeBranch,
                        includeLinkedTasksContext = includeLinkedTasksContext,
                        skillIds = skillIds,
                        skillsMode = skillsMode,
                        includeSkillsContext = includeSkillsContext,
                        projectId = projectId,
                        epicId = epicId,
                        images = imagePaths,
                        weight = weight,
                        contextLimit = contextLimit,
                        effort = effort,
                    ),
                )
            } else {
                val row = s.rows.getValue(editing)
                s.rows[editing] = row.copy(
                    title = title ?: row.title,
                    projectId = projectId,
                    epicId = epicId,
                    description = described ?: description ?: row.description,
                    ticketType = if (clearType) null else type ?: row.ticketType,
                    agentId = agentId,
                    provider = provider,
                    model = model,
                    useWorktree = useWorktree,
                    worktreeBranch = worktreeBranch,
                    includeLinkedTasksContext = includeLinkedTasksContext,
                    skillIds = skillIds,
                    skillsMode = skillsMode,
                    includeSkillsContext = includeSkillsContext,
                    images = imagePaths,
                    weight = weight,
                    contextLimit = contextLimit,
                    effort = effort,
                    queuedFor = if (agentId != row.agentId) null else row.queuedFor,
                    queuedAt = if (agentId != row.agentId) null else row.queuedAt,
                )
            }
        }
        // IMG-3: an image this save dropped goes once no ticket references it (after the commit, still under the lock).
        existing?.let { before -> releaseImages(before.projectId, before.images - imagePaths.toSet()) }
        return answer(primary, outcome)
    }

    /** KNOWLEDGE-PTR-1 (C): the description to store with its RELATED (auto) block rewritten; null = store it as given. */
    private fun describeRelated(id: String?, title: String, description: String, projectId: String?, extraLink: TicketLinkRow?): String? {
        if (relatedDescription === RelatedDescription.NONE) return null
        val (rows, links) = store.transaction { store.tickets.all() to store.tickets.links() }
        return runCatching { relatedDescription.describe(RelatedDraft(id, title, description, projectId, rows.map { it.toInfo() }, (links + listOfNotNull(extraLink)).map { it.toInfo() })) }
            .getOrNull()?.takeIf { it.length <= MAX_DESCRIPTION_LENGTH }
    }

    /** IMG-3: delete each of [dropped] that no ticket on the board still references. */
    private fun releaseImages(projectId: String?, dropped: List<String>) {
        if (dropped.isEmpty()) return
        images.release(projectId, dropped, store.transaction { store.tickets.all() })
    }

    /**
     * E1-a: INTERNAL ticket creation for the startup story migration (stories/StoryMigration.kt), not a wire frame.
     * `ticket_save` cannot be used: it refuses an id it does not know, and a migrated ticket needs its column set.
     *
     * Creates one ticket in column [status] (appended at the bottom), with no agent assigned, and runs [inSameTransaction]
     * with the new id INSIDE the board's write transaction, so the caller's own row (the story mapping) commits or rolls
     * back with the ticket. Answers the new ticket id. Nothing is pushed and no transition is announced: this runs at
     * daemon startup, before any client or trigger listens, and a migrated `done` story must not fire a ship trigger.
     */
    @Synchronized
    internal fun import(
        title: String,
        status: String,
        projectId: String?,
        epicId: String?,
        description: String,
        inSameTransaction: (ticketId: String) -> Unit,
    ): String {
        val checkedTitle = checkTitle(title)
        val checkedDescription = checkDescription(description)
        require(status in TicketStatus.ALL) { "status '${status.take(40)}' is not a ticket column" }
        var created = ""
        commit { s ->
            created = newId(s)
            s.create(
                TicketRow(
                    id = created,
                    title = checkedTitle,
                    description = checkedDescription,
                    status = status,
                    position = 0, // renumbered to the bottom of its column
                    createdAt = s.now,
                    updatedAt = s.now,
                    projectId = projectId?.trim()?.takeIf { it.isNotEmpty() },
                    epicId = epicId?.trim()?.takeIf { it.isNotEmpty() },
                ),
            )
            inSameTransaction(created)
        }
        return created
    }

    /**
     * Move a ticket to another column and/or place (see [TicketMove] and the class rules).
     *
     * E1-c: [startOnInProgress] false is the deprecated `story_move` shim's form -- a move into `in_progress`
     * is a plain column change there (what a story move always was), never a hand-out that launches a run.
     */
    @Synchronized
    fun move(request: TicketMove, startOnInProgress: Boolean = true, startedBy: String? = null): TicketAnswer {
        val id = checkId(request.id)
        val status = request.status?.let { checkStatus(it) }
        val position = request.position
        require(position == null || position >= 0) { "position must be 0 or more, was $position" }

        if (status == TicketStatus.IN_PROGRESS && startOnInProgress) {
            val current = store.transaction { store.tickets.get(id) }
                ?: throw IllegalArgumentException("no ticket '${id.take(80)}'")
            if (current.status != TicketStatus.IN_PROGRESS) return handOut(id, HandOutOrigin.MOVE, startedBy)
            // GAP-006b: a START of a ticket already in progress (a move into its own column with no place: a board drop
            // always names one) restarts it when its worker cannot carry it, and otherwise answers so, never silently.
            if (request.status == TicketStatus.IN_PROGRESS && position == null) {
                val problem = workerProblem(current)
                    ?: return TicketAnswer(answerTicket(id).copy(start = TicketStart(ok = false, reason = TicketStart.ALREADY_IN_PROGRESS)), pushed = false)
                return restart(id, current, problem, HandOutOrigin.MOVE, startedBy)
            }
        }

        val outcome = commit { s ->
            val row = s.require(id)
            val target = status ?: row.status
            val holding = s.holding(id)
            if (target == TicketStatus.PENDING && row.status != TicketStatus.PENDING) {
                require(holding.isNotEmpty()) {
                    "ticket $id has no open prerequisite: pending is set by the daemon while a blocked_by " +
                        "prerequisite is open, never by hand"
                }
            }
            // 1.131.0: a held ticket may still be parked in backlog (it stays held there; promoted back to todo it
            // settles into pending again), but it leaves pending for no other column by hand.
            if (row.status == TicketStatus.PENDING && target != TicketStatus.PENDING && target != TicketStatus.BACKLOG) {
                require(holding.isEmpty()) {
                    "ticket $id is held (${holdReason(holding)}): a held ticket leaves pending by itself, once nothing holds it"
                }
            }
            val wontFix = when {
                request.wontFix == true -> {
                    require(target == TicketStatus.DONE) { "wontFix closes a ticket as done: send status done with it" }
                    true
                }
                target != TicketStatus.DONE -> false
                request.wontFix == false -> false
                else -> row.status == TicketStatus.DONE && row.wontFix
            }
            if (target != row.status || position != null) s.place(id, target, position)
            s.rows[id] = s.rows.getValue(id).copy(wontFix = wontFix)
            s.named += id
        }
        return answer(id, outcome)
    }

    /** The one serialized path for ticket_move, ticket_start_next and capacity-queue hand-outs. */
    @Synchronized
    private fun handOut(ticketId: String, origin: HandOutOrigin, startedBy: String? = null): TicketAnswer {
        val id = checkId(ticketId)
        val initial = store.transaction { load() }
        val row = initial.require(id)
        val initialTicket = views(initial.rows, initial.links).getValue(id)
        // 1.131.0: a backlog ticket is limbo, never started from any path (ticket_start, a move to in_progress, the
        // UI's Start, a drop on In progress); it must be promoted to todo first.
        if (row.status == TicketStatus.BACKLOG) return refusedStart(initialTicket, TicketStatus.BACKLOG_START_REFUSAL)
        val holding = initial.holding(id)
        if (holding.isNotEmpty()) {
            return TicketAnswer(
                initialTicket.copy(start = TicketStart(ok = false, reason = "held", detail = holding.joinToString { it.id })),
                pushed = false,
            )
        }

        val sessionManager = sessions
            ?: return refusedStart(initialTicket, "ticket session manager is unavailable")
        val profiles = agents
            ?: return refusedStart(initialTicket, "agent profiles are unavailable")
        // ROUTING-1: the project's first matching rule (none when the ticket pins its own provider / model or the project
        // has no rules). Its setRole is the role of a ticket that has none; it never replaces the ticket's own.
        val rule = routingChoice(row)
        val assigned = row.agentId ?: rule?.setRole?.let { "role:$it" }
        val assignment = resolveAssignment(assigned, profiles, sessionManager, row.projectId)
        if (assignment.refusal != null) return refusedStart(initialTicket, assignment.refusal)
        // BUG-WT-1: with no repo configured and no explicit pin, a project folder that is a git checkout IS the
        // repo: the ticket gets its own branch and worktree there, never the checkout itself.
        // INSTALL-CWD-1: with project folders on (the installed app), the project's own directory is the folder, and a
        // project with none is refused here: never the daemon's cwd.
        val projectFolder: File? = if (repo == null) {
            val folders = sessionManager.projectFolders
            if (folders != null) {
                try {
                    folders.require(row.projectId)
                } catch (e: IllegalArgumentException) {
                    return refusedStart(initialTicket, e.message ?: "project has no directory")
                }
            } else {
                projectDir(row.projectId)
            }
        } else {
            null
        }
        val worktreeRepo = repo
            ?: if (row.useWorktree == null) cutRepo(row.projectId) else null
        val requestedWorktree = row.useWorktree ?: (worktreeRepo != null)
        if (requestedWorktree && worktreeRepo == null) {
            return refusedStart(
                initialTicket,
                if (projectFolder != null && gitRootOf(projectFolder) == null) {
                    "project folder ${projectFolder.absolutePath} is not a git repository, so a worktree cannot be cut; " +
                        "clear the ticket's worktree setting to run in the folder"
                } else {
                    "no repo configured"
                },
            )
        }
        // BUG-WT-1: an explicit useWorktree=false runs the worker in the project folder itself (the repo, or the
        // daemon's own directory). Over a git checkout it would commit straight onto whatever branch is checked
        // out, bypassing review, so the start is refused. A folder that is not a git checkout is unchanged.
        if (!requestedWorktree) {
            val folder = repo ?: projectFolder ?: sessionManager.defaultWorkingDir
            if (gitRootOf(folder) != null) {
                return refusedStart(
                    initialTicket,
                    "project folder ${folder.absolutePath} is a git repo: configure the repo or enable worktree",
                )
            }
        }
        if (assignment.busy) {
            val lane = assignment.queueLane ?: return refusedStart(initialTicket, "assigned profile is at capacity")
            if (origin == HandOutOrigin.START_NEXT || origin == HandOutOrigin.TRIGGER || origin == HandOutOrigin.REPAIR) {
                return refusedStart(initialTicket, "profile lane '$lane' is at capacity")
            }
            if (row.queuedFor == lane && row.queuedAt != null) {
                return TicketAnswer(
                    initialTicket.copy(start = TicketStart(ok = false, reason = "queued", detail = lane)),
                    pushed = false,
                )
            }
            val queuedAt = clock()
            val outcome = commit { snapshot ->
                val latest = snapshot.require(id)
                snapshot.rows[id] = latest.copy(queuedFor = lane, queuedAt = queuedAt)
            }
            return startAnswer(id, TicketStart(ok = false, reason = "queued", detail = lane), outcome)
        }

        val profile = assignment.profile ?: return refusedStart(initialTicket, "no profile resolved for ticket")

        // ROLE-1b: ticket pins > profile > the role's binding > daemon default (one resolver, shared with spawn).
        val bound = profiles.executorFor(profile, row.provider, row.model, row.effort)
        // TICKET-WEIGHT: a weighted ticket that pins nothing (and whose profile / role binding pins nothing) runs on the weight's executor.
        // ROUTING-1: in a project with rules the matched rule beats the profile / role binding and the weight mapping.
        val byWeight = if (rule == null && routingRules(row.projectId).isEmpty()) weightChoice(row, bound.provider, bound.model) else null
        val executor = when {
            rule != null -> profiles.executorFor(profile, rule.provider, rule.model, row.effort ?: rule.effort) // EFFORT-1: the ticket's own pin beats the rule's
            byWeight != null -> profiles.executorFor(profile, byWeight.provider, byWeight.model, row.effort)
            else -> bound
        }
        val providerId = executor.provider
        val model = executor.model
        // TOKEN-7: the weight's compact window / effort fill what the ticket, its role and any rule left unset.
        val byWeightContext = weightContext().forWeight(row.weight)
        if (rule != null) {
            // ROUTING-1: the rule's executor is checked again at start (the harness list may have changed since the save),
            // with the Opus line. A refusal PARKS the ticket: no other rule, no default row, no other harness is tried.
            try {
                RoutingRules.requireOpusSafe(rule.name, providerId, model)
                profiles.validateExecutor(providerId, model, executor.effort)
            } catch (e: IllegalArgumentException) {
                val detail = "rule '${rule.name}': $providerId/$model refused: ${e.message ?: "provider or model refused"}; " +
                    "the ticket stays in todo (no fallback): fix the rule or pin the ticket"
                if (reportedRoutingRefusals.add("$id|$detail")) runCatching { routingRefused(id, row.projectId, detail) }
                return refusedStart(initialTicket, detail)
            }
        }
        try {
            sessionManager.checkSpawn(providerId, model)
        } catch (e: IllegalArgumentException) {
            return refusedStart(initialTicket, e.message ?: "provider or model refused")
        }
        // Story 9.7b: a drained queue is an UNATTENDED launch (startNew refuses it over a spent budget or an
        // exhausted provider); a human move or start-next is only warned, and still starts.
        val unattendedLaunch = origin == HandOutOrigin.QUEUE_DRAIN || origin == HandOutOrigin.TRIGGER || origin == HandOutOrigin.REPAIR
        val launchWarning = if (unattendedLaunch) null else
            (sessionManager.launchCheck(providerId, LaunchOrigin.HUMAN, id) as? GateDecision.Warn)?.reason
        // USAGE-PACE: only the owner's own move (no starter session) may go past the plan allowance, with a warning.
        val humanMove = origin == HandOutOrigin.MOVE && startedBy == null
        val pacingWarning = when (val verdict = pacing(providerId, model)) {
            is PacingVerdict.Over -> if (humanMove) verdict.reason else return refusedStart(initialTicket, verdict.reason)
            is PacingVerdict.Allow -> null
        }

        // BRANCH-PER-TASK 1/3: the ticket's own branch and worktree, cut or reused BEFORE the session exists,
        // so a refused base leaves nothing behind and a start never creates a second branch for one ticket.
        var cutNote: String? = null
        val ticketWorktree: TicketWorktree? = if (requestedWorktree) {
            val root = worktreeRepo ?: return refusedStart(initialTicket, "no repo configured")
            // BASE-1c: the ref AND the setting that chose it, so a fresh cut's note names an epic's base.
            val baseChoice = baseRef.choose(BaseScope(row.projectId, row.epicId))
            // BUG-BASE-087: nothing configured is refused, not cut from whatever the main checkout has checked out.
            // A ticket that already holds its branch reuses it and needs no base.
            if (row.branch == null && baseChoice.ref.isBlank()) {
                if (branches.resolveBase(root, "HEAD") == null) {
                    return refusedStart(initialTicket, "repository ${root.absolutePath} has no commits yet; make a first commit, then set a base branch")
                }
                return refusedStart(initialTicket, noBaseReason(row.projectId))
            }
            val request = TicketBranchRequest(
                ticketId = id,
                title = row.title,
                repo = root,
                baseBranch = baseChoice.ref,
                recordedBranch = row.branch,
                recordedBase = row.base,
            )
            when (val outcome = branches.ensure(request)) {
                is TicketBranchOutcome.Refused -> return refusedStart(initialTicket, outcome.reason)
                is TicketBranchOutcome.Ready -> outcome.worktree.also {
                    // The setting that chose the base qualifies the cut, never the worktree's warning after it.
                    val cut = outcome.note?.let { note -> baseChoice.source?.let { "$note ($it)" } ?: note }
                    cutNote = listOfNotNull(cut, outcome.warning).joinToString("; ").ifEmpty { null }
                }
            }
        } else {
            null
        }
        // Recorded now, before the session: a start refused after the branch was cut (the launch gate, the
        // session itself) leaves a branch the ticket knows about, so the next start reuses it. Not pushed here:
        // a client waiting for this start's answer must not see an earlier `ticket` frame with no `start`; the
        // answer below carries the branch and is the one push.
        ticketWorktree?.let { wt ->
            if (row.branch != wt.branch || row.base != wt.base) {
                commit { snapshot ->
                    snapshot.rows[id] = snapshot.rows.getValue(id).copy(branch = wt.branch, base = wt.base)
                }
            }
        }

        // TRAITS-T4/T5: resolved BEFORE composing, then passed to startNew, so the frozen row is what this start rendered.
        // P10-K15: with the skill ids the row will freeze for this ticket, so the `skills` source renders exactly those.
        val instance = profiles.instanceFor(profile.id, profile.role)?.let { sessionManager.withSkills(it, id) }
        val input = briefInput(
            row, initial, profile.role, profile.id, instance,
            startedBy?.let { starter -> reportTo(starter, sessionManager, profiles) },
        )
        val composed = context.compose(input)
        val started = try {
            sessionManager.startNew(
                prompt = composed.prompt,
                workingDir = if (requestedWorktree) null else (repo ?: projectFolder),
                branchHint = null,
                preWorktree = ticketWorktree?.asRecord(clock()),
                providerId = providerId,
                model = model,
                effort = executor.effort ?: byWeightContext?.effort,
                agentId = profile.id,
                role = profile.role,
                ticketId = id,
                contextSources = composed.sources,
                instance = instance,
                projectId = row.projectId,
                contextLimit = row.contextLimit ?: byWeightContext?.contextLimit, // CONTEXT-LIMIT-1; TOKEN-7 weight default
                unattended = true,
                launchOrigin = if (unattendedLaunch) LaunchOrigin.UNATTENDED else LaunchOrigin.HUMAN,
            )
        } catch (e: IllegalArgumentException) {
            return refusedStart(initialTicket, e.message ?: "session start refused")
        }

        // BUG-TriggerRunIds: spawn first, commit second -- the commit records the session's id, which only exists
        // once startNew returns, and committing in_progress first would leave a ticket to roll back on every
        // refused start. So the commit failing (SQLITE_BUSY_SNAPSHOT, a full disk) is compensated here: the
        // session it would have recorded is killed, never left running against a ticket still filed as todo.
        val outcome = try {
            commitStarted(id, started.id, ticketWorktree, startedBy)
        } catch (e: Throwable) {
            runCatching { sessionManager.kill(started.id, force = true, by = "ticket start rollback", reason = "the start could not be committed") }
                .onFailure { e.addSuppressed(it) }
            throw e
        }
        // BUG-REGRESS-1: what the branch was cut from, then any launch warning (TicketStarter reads them apart).
        val weightNote = byWeight?.let { "weight ${row.weight}: ${providerId ?: "default provider"}/${model ?: "default model"} from ${WeightExecutors.SETTING}" }
        val ruleNote = rule?.let { "rule '${it.name}': $providerId/$model" }
        // CONTEXT-LIMIT-1: said on the start, so a limit the harness cannot apply is never silent.
        val limitNote = started.contextLimit?.let { "context limit ${com.superiorsoftllc.agenthq.session.ContextLimits.label(it)}: ${started.contextLimitSupport}" +
            (if (started.contextLimitSupport == com.superiorsoftllc.agenthq.harness.ContextLimitSupport.UNSUPPORTED.wire) " (recorded, not applied by ${started.provider})" else "") }
        // EFFORT-1: the same for a reasoning effort the harness cannot apply.
        val effortNote = started.effort?.let { "effort $it: ${started.effortSupport}" +
            (if (started.effortSupport == com.superiorsoftllc.agenthq.harness.EffortSupport.UNSUPPORTED.wire) " (recorded, not applied by ${started.provider})" else "") }
        val detail = listOfNotNull(cutNote, ruleNote, weightNote, limitNote, effortNote, launchWarning?.let { TicketStart.LAUNCH_WARNING + it }, pacingWarning).joinToString("; ").ifEmpty { null }
        return startAnswer(id, TicketStart(ok = true, reason = "started", detail = detail), outcome)
    }

    /** GAP-006b: why in-progress [row]'s worker cannot carry it ([TicketWorkerHealth]); null when it can, or with no session manager. */
    private fun workerProblem(row: TicketRow): String? {
        val sessionManager = sessions ?: return null
        return TicketWorkerHealth.problem(row.sessionId?.let(sessionManager::get), row.sessionId)
    }

    private fun answerTicket(id: String): Ticket = store.transaction { load() }.let { views(it.rows, it.links).getValue(id) }

    /**
     * GAP-006b: start in-progress ticket [id] again because its worker cannot carry it ([problem]). The old worker is
     * retired first -- removed outright when it never opened a provider session (nothing in it to keep or resume), so
     * the ticket never ends up with two instances -- then the ticket is handed out exactly as a fresh start, reusing
     * its branch. The answer's detail starts with [TicketStart.RESTARTED] and the problem.
     */
    private fun restart(id: String, current: TicketRow, problem: String, origin: HandOutOrigin, startedBy: String?): TicketAnswer {
        val sessionManager = sessions ?: return refusedStart(answerTicket(id), "ticket session manager is unavailable")
        current.sessionId?.let(sessionManager::get)?.let { old ->
            runCatching {
                if (old.live) sessionManager.kill(old.id, force = true, by = "ticket restart", reason = problem)
                if (old.providerSessionId == null) sessionManager.delete(old.id)
            }.onFailure { System.err.println("[agentHq] ticket $id: retiring worker ${old.id} failed: ${it.message}") }
        }
        val answer = handOut(id, origin, startedBy)
        val start = answer.ticket.start ?: return answer
        if (!start.ok) return answer
        val detail = TicketStart.RESTARTED + problem + (start.detail?.let { "; $it" } ?: "")
        return answer.copy(ticket = answer.ticket.copy(start = start.copy(detail = detail)))
    }

    /** GAP-006b: tickets the sweep already restarted once in this daemon's life; a second phantom is surfaced, not restarted. */
    private val repairedTickets = java.util.Collections.synchronizedSet(HashSet<String>())

    /** GAP-006b: (ticket, session) pairs already surfaced, so one problem is reported once. */
    private val surfacedWorkers = java.util.Collections.synchronizedSet(HashSet<String>())

    /**
     * GAP-006b, the in-progress invariant enforced in code: an in-progress ticket has a worker that received its brief,
     * or it is surfaced. Run on a timer by the daemon; [graceMs] (setting `limits.watchdog.unbriefedGraceMinutes`) is how
     * long a worker may go without a provider session id, from its last sign of life, before anything happens.
     *
     *  - a worker at rest that never opened a provider session (the phantom) is restarted ONCE per ticket, and [flag]
     *    is told; a second phantom for the same ticket is only flagged, so a harness that always exits silently cannot loop;
     *  - a BUSY worker with no provider session past the grace is flagged, never ended (a slow boot is not death);
     *  - an in-progress ticket with no worker session at all is flagged and never started here: someone may be working
     *    it outside the board, and a start is a person's or an agent's call (`ticket start` restarts it on demand).
     *
     * Returns the ids of the tickets it restarted.
     */
    @Synchronized
    fun sweepWorkers(graceMs: Long, flag: (ticketId: String, sessionId: String?, detail: String) -> Unit = { _, _, _ -> }): List<String> {
        val sessionManager = sessions ?: return emptyList()
        if (graceMs == Long.MAX_VALUE) return emptyList()
        val now = clock()
        val restarted = mutableListOf<String>()
        val inProgress = store.transaction { store.tickets.all() }.filter { it.status == TicketStatus.IN_PROGRESS }
        for (row in inProgress) {
            val worker = row.sessionId?.let(sessionManager::get)
            fun surface(detail: String) {
                if (surfacedWorkers.add("${row.id}|${row.sessionId}|${detail.take(40)}")) runCatching { flag(row.id, row.sessionId, detail) }
            }
            if (worker == null) {
                if (now - row.updatedAt >= graceMs) {
                    surface("Ticket is in progress but ${TicketWorkerHealth.problem(null, row.sessionId)}: no session - start it.")
                }
                continue
            }
            if (TicketWorkerHealth.slowToBrief(worker, now, graceMs)) {
                surface("Worker ${worker.id} is busy but has opened no provider session for ${(now - worker.lastHeartbeat) / 60_000} min: its brief may never have landed.")
                continue
            }
            val problem = TicketWorkerHealth.problem(worker)
            if (problem == null) {
                // GAP 855db590: a briefed worker whose clean run did no work is resumable, so it is flagged, never restarted.
                if (!worker.isBusy() && now - worker.lastHeartbeat >= graceMs) {
                    val run = store.transaction { store.ticketRuns.byTicket(row.id) }.lastOrNull { it.sessionId == worker.id }
                    run?.detail?.takeIf { it.startsWith(NO_WORK_DETAIL) }?.let {
                        surface("Ticket is in progress but its worker ${worker.id} exited cleanly having done no work (${it.removePrefix(NO_WORK_DETAIL)}): resume or start it again.")
                    }
                }
                continue
            }
            if (now - worker.lastHeartbeat < graceMs) continue
            if (!repairedTickets.add(row.id)) {
                surface("Ticket is in progress but $problem; it was already restarted once, so it is left for a person: start it again.")
                continue
            }
            val answer = runCatching { restart(row.id, row, problem, HandOutOrigin.REPAIR, row.startedBy) }
                .getOrElse { e -> surface("Restarting the ticket failed: ${e.message}"); continue }
            val start = answer.ticket.start
            if (start?.ok == true) {
                restarted += row.id
                runCatching { flag(row.id, answer.ticket.sessionId, "Restarted: $problem. New worker ${answer.ticket.sessionId}.") }
            } else {
                surface("Ticket is in progress but $problem; the restart did not start: ${start?.detail ?: start?.reason ?: "no answer"}.")
            }
        }
        return restarted
    }

    /** What a ticket start composes its brief from: the ticket [row], run as [role] / [agentId] with [instance]. */
    private fun briefInput(
        row: TicketRow,
        snapshot: Snapshot,
        role: String?,
        agentId: String?,
        instance: com.superiorsoftllc.agenthq.session.RoleInstance?,
        reportTo: ReportTo?,
    ) = ContextInput(
        instructions = instance?.instructions,
        title = row.title,
        description = row.description,
        links = linkLines(row.id, snapshot),
        includeLinkedOverride = row.includeLinkedTasksContext,
        ticketId = row.id,
        projectId = row.projectId,
        role = role,
        agentId = agentId,
        traits = instance?.traits.orEmpty(),
        skillIds = instance?.skillIds,
        includeSkillsOverride = row.includeSkillsContext,
        reportTo = reportTo,
        // IMG-3: each attached image's absolute path, listed in the brief so the worker can read it.
        images = row.images.mapNotNull { images.absolute(row.projectId, it)?.path },
    )

    /**
     * TICKET-HANDOVER: the start brief of the ticket [session] works on, composed as a fresh start of that ticket composes
     * it ([handOut]): the same composer, the session's own role, agent and frozen instance, and the ticket's starter as
     * who to report to. Re-read NOW, so an edit to the ticket since its start lands. Null when the session has no ticket
     * or the ticket is gone. Takes no board monitor (the handover calls it from its own thread).
     */
    fun brief(session: com.superiorsoftllc.agenthq.session.SessionRecord): String? {
        val ticketId = session.ticketId?.takeIf { it.isNotBlank() } ?: return null
        val sessionManager = sessions ?: return null
        val snapshot = store.transaction { load() }
        val row = snapshot.rows[ticketId] ?: return null
        val reportTo = row.startedBy?.let { starter -> agents?.let { reportTo(starter, sessionManager, it) } }
        return context.compose(briefInput(row, snapshot, session.role, session.agentId, sessionManager.instance(session.id), reportTo)).prompt
    }

    /**
     * BUG-MAIL-2: who the worker reports to -- the session that started the ticket, named after its agent profile
     * (else its role, else its id). Null when [starter] names no session, so a stale id never reaches a preamble.
     */
    private fun reportTo(starter: String, sessionManager: SessionManager, profiles: AgentProfiles): ReportTo? {
        val session = sessionManager.get(starter) ?: return null
        val name = session.agentId?.let { profiles.get(it)?.name }?.takeIf { it.isNotBlank() }
            ?: session.role?.takeIf { it.isNotBlank() }
            ?: session.id
        return ReportTo(name = name, sessionId = session.id)
    }

    /**
     * BUG-MAIL-2: the session that started the ticket [workerSessionId] runs, or null (a UI start, or no such ticket).
     * Read straight from the store and NOT under the board monitor: the mail router asks while it may hold the
     * mailbox lock, and a hand-out holds this monitor while its session events pump mail.
     */
    fun starterOf(workerSessionId: String): String? = store.transaction {
        store.tickets.all().firstOrNull { it.sessionId == workerSessionId }?.startedBy
    }

    private fun commitStarted(id: String, sessionId: String, ticketWorktree: TicketWorktree?, startedBy: String?): Outcome =
        commit { snapshot ->
            snapshot.require(id)
            snapshot.place(id, TicketStatus.IN_PROGRESS, null)
            val current = snapshot.rows.getValue(id)
            snapshot.rows[id] = current.copy(
                sessionId = sessionId,
                // BUG-MAIL-2: each start names its own starter; a UI start (null) clears an earlier one.
                startedBy = startedBy,
                queuedFor = null,
                queuedAt = null,
                branch = ticketWorktree?.branch ?: current.branch,
                base = ticketWorktree?.base ?: current.base,
            )
            snapshot.named += id
        }

    /**
     * BASE-1a: the repository a ticket start cuts its branch in -- the configured repo, else the git checkout the
     * daemon's sessions run in (BUG-WT-1) -- or null when there is none. The same answer [handOut] uses, so a
     * project's base branch is checked where a cut will actually resolve it.
     *
     * INSTALL-CWD-1: with project folders on (the installed app), the checkout [projectId]'s own directory is in;
     * never the daemon's cwd.
     */
    fun cutRepo(projectId: String? = null): File? {
        repo?.let { return it }
        val folders = sessions?.projectFolders
        if (folders != null) return folders.find(projectId)?.let(::gitRootOf)
        // NOGIT-1: the project's own directory decides; the daemon's checkout is only the fallback for a project with none.
        // TODO: gitRootOf walks UP, so a non-git folder nested under any parent repo still counts as that repo's checkout.
        projectDir(projectId)?.let { return gitRootOf(it) }
        return sessions?.defaultWorkingDir?.let(::gitRootOf)
    }

    private fun projectDir(projectId: String?): File? =
        projectDirectory(projectId)?.takeIf { it.isNotBlank() }?.let { File(it).absoluteFile }?.takeIf { it.isDirectory }

    private fun TicketWorktree.asRecord(at: Long) = WorktreeRecord(
        sessionId = "", // the session that runs in it names itself when it is created
        repoRoot = repoRoot,
        path = path,
        branch = branch,
        baseRef = base,
        createdAt = at,
    )

    /** BUG-BASE-087: why a fresh cut with no base configured is refused, and how to configure one. */
    private fun noBaseReason(projectId: String?): String {
        val set = projectId?.let { "agenthq project base $it <branch>" } ?: "agenthq project base <projectId> <branch>"
        return "no base branch configured for ${projectId?.let { "project $it" } ?: "this ticket"}; " +
            "set one with `$set`, an epic base (`agenthq epic base <epicId> <branch>`) or the daemon's ${TicketBaseRef.ENV} (a headless daemon also needs AGENTHQ_REPO=<git repo>)"
    }

    private fun refusedStart(ticket: Ticket, detail: String) = TicketAnswer(
        ticket.copy(start = TicketStart(ok = false, reason = "start_refused", detail = detail)),
        pushed = false,
    )

    private fun startAnswer(id: String, start: TicketStart, outcome: Outcome): TicketAnswer {
        val byId = outcome.board.associateBy { it.id }
        val answer = byId.getValue(id).copy(start = start)
        if (outcome.changed.isEmpty()) return TicketAnswer(answer, pushed = false)
        val changed = outcome.changed.map { changedId ->
            val ticket = byId.getValue(changedId)
            if (changedId == id) answer else ticket
        }
        push(BoardEvent.Changed(changed))
        return TicketAnswer(answer, pushed = id in outcome.changed)
    }

    private fun resolveAssignment(
        assigned: String?,
        profiles: AgentProfiles,
        sessionManager: SessionManager,
        projectId: String? = null,
    ): AssignmentResolution {
        assigned ?: return AssignmentResolution(
            refusal = "ticket has no role; set one with `agenthq ticket update <id> --role <role id>` " +
                "or agenthq_ticket_update's role",
        )
        val active = sessionManager.list().filter { it.state in ACTIVE_STATES }
        if (assigned.startsWith("role:")) {
            val role = assigned.removePrefix("role:")
            val matching = profiles.list().filter { it.role == role }.ifEmpty {
                firstRunProfile(role, profiles, projectId)?.let(::listOf).orEmpty()
            }
            if (matching.isEmpty()) return AssignmentResolution(refusal = "no agent profile has role '$role'")
            val profile = matching.firstOrNull { candidate -> active.none { it.agentId == candidate.id } }
                ?: extraAgent(matching, active, role, profiles, projectId)
                ?: return AssignmentResolution(queueLane = assigned, busy = true)
            return AssignmentResolution(profile = profile, queueLane = assigned)
        }
        val profile = profiles.get(assigned)
            ?: return AssignmentResolution(refusal = "no agent profile '$assigned'")
        return if (active.any { it.agentId == profile.id }) {
            AssignmentResolution(queueLane = profile.id, busy = true)
        } else {
            AssignmentResolution(profile = profile, queueLane = profile.id)
        }
    }

    /**
     * LANE-CAPS: every agent of [role] is busy; when fewer than the project's cap are running, add one more agent of the
     * role (a copy of the first, named "<name> <n>", with its own id) so the ticket starts instead of queueing. Null at the
     * cap (the ticket queues) or when the save is refused. Lowering the cap never stops a running agent.
     */
    private fun extraAgent(
        matching: List<AgentProfile>,
        active: List<SessionRecord>,
        role: String,
        profiles: AgentProfiles,
        projectId: String?,
    ): AgentProfile? {
        val running = matching.count { candidate -> active.any { it.agentId == candidate.id } }
        if (running >= laneCap(projectId, role)) return null
        val template = matching.first()
        val taken = profiles.list().map { it.name }.toSet()
        val name = generateSequence(2) { it + 1 }.map { "${template.name} $it" }.first { it !in taken }
        return try {
            profiles.save(template.copy(id = null, name = name))
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    /**
     * INSTALL-PROFILE-1: a first-run install has no agent profiles, so a `role:<role>` ticket would be refused with
     * nothing for the owner to act on. When [role] is in the live catalog and no profile holds it, create ONE default
     * profile for it in [projectId], with no provider, model or effort pinned: it runs on the role's binding, else the
     * daemon's default provider and model, exactly as an empty pin does everywhere else. Null when the role is unknown
     * or the save is refused, which keeps the original refusal.
     */
    private fun firstRunProfile(role: String, profiles: AgentProfiles, projectId: String?): AgentProfile? {
        if (!profiles.roleKnown(role)) return null
        val name = role.split('-').joinToString(" ") { part -> part.replaceFirstChar { it.uppercase() } }
        return try {
            profiles.save(AgentProfile(name = name, role = role, projectId = projectId))
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    private fun linkLines(ticketId: String, snapshot: Snapshot): List<LinkLine> = buildList {
        for (link in snapshot.links) {
            val linkedId: String
            val type: String
            when (ticketId) {
                link.ticketId -> {
                    linkedId = link.relatedId
                    type = link.type
                }
                link.relatedId -> {
                    linkedId = link.ticketId
                    type = reverse(link.type)
                }
                else -> continue
            }
            val linked = snapshot.rows[linkedId] ?: continue
            add(LinkLine(type = type, ticketId = linked.id, title = linked.title, status = linked.status))
        }
    }

    private fun ensureRunRow(record: SessionRecord) {
        val ticketId = record.ticketId ?: return
        if (store.tickets.get(ticketId) == null) return
        if (store.ticketRuns.byTicket(ticketId).none { it.sessionId == record.id }) {
            store.ticketRuns.insert(
                StoredRun(ticketId = ticketId, sessionId = record.id, startedAt = record.spawnedAt),
            )
        }
    }

    private fun finishTicketRun(record: SessionRecord) {
        val ticketId = record.ticketId ?: return
        val outcome = RunOutcomes.of(record)
        // Owner decision 2026-10-22: a clean exit leaves the ticket where it is (`tickets.exitBehavior` = leave, the
        // default) so the PM or another agent decides; `done` restores the old BUG-E2E-2 auto-done. Only the ticket's
        // CURRENT run decides (an older session ending late does not), and only a SUCCEEDED one: a failed, killed or
        // orphaned run stays in_progress with lastRun showing why.
        val eligible = exitBehavior() == "done" && outcome == FinishedPayload.Outcome.SUCCEEDED &&
            store.tickets.get(ticketId)?.let { it.status == TicketStatus.IN_PROGRESS && it.sessionId == record.id } == true
        // GAP 855db590: exit 0 is not proof of work. A run that never opened a provider session, or produced fewer
        // assistant outputs / tool calls than the setting asks, leaves the ticket in_progress; the worker sweep restarts
        // an unbriefed one and flags the rest.
        val noWork = if (eligible) noWork(record) else null
        val autoDone = eligible && noWork == null
        store.ticketRuns.finish(
            ticketId = ticketId,
            sessionId = record.id,
            endedAt = record.lastHeartbeat,
            outcome = outcome.name,
            detail = when {
                autoDone -> AUTO_DONE_DETAIL
                noWork != null -> NO_WORK_DETAIL + noWork
                else -> record.lastError ?: ""
            },
        )
        if (autoDone) {
            // The ticket frame itself goes out from publishRunUpdate, with this run as lastRun; only the transition
            // is announced here, so a ship trigger fires exactly as for a move into done by hand.
            announce(commit { s -> s.place(ticketId, TicketStatus.DONE, null) })
        }
    }

    /** GAP 855db590: why [record]'s clean run does not count as work done on its ticket; null when it does. */
    private fun noWork(record: SessionRecord): String? {
        val min = autoDoneMinOutputs()
        if (min <= 0) return null
        if (record.providerSessionId == null) return "the worker never opened a provider session, so it never received its brief"
        val outputs = sessions?.workEvents(record.id) ?: return null
        if (outputs >= min) return null
        return "the worker produced $outputs assistant output(s) or tool call(s), fewer than the $min that " +
            "'${com.superiorsoftllc.agenthq.settings.LimitRegistry.AUTO_DONE_MIN_OUTPUTS.key}' requires"
    }

    private fun publishRunUpdate(ticketId: String) {
        if (store.tickets.get(ticketId) != null) push(BoardEvent.Changed(listOf(get(ticketId))))
    }

    private fun drainProfileLane(record: SessionRecord) {
        if (!queueDrainEnabled) return
        val profileId = record.agentId ?: return
        val role = record.role
        val candidates = store.transaction {
            val snapshot = load()
            boardOrder(snapshot.rows.values).filter { ticket ->
                ticket.status == TicketStatus.TODO && snapshot.holding(ticket.id).isEmpty() &&
                    (ticket.queuedFor == profileId || (role != null && ticket.queuedFor == "role:$role"))
            }.map { it.id }
        }
        candidates.firstOrNull()?.let { handOut(it, HandOutOrigin.QUEUE_DRAIN) }
        if (role != null) pruneIdleClones(role)
    }

    /**
     * LANE-CAPS follow-up: a project's caps changed. Queued tickets of each role are started (board order) while the role
     * has room under its new cap, then idle clones above a lowered cap are pruned.
     */
    @Synchronized
    fun laneCapsChanged(projectId: String) {
        if (queueDrainEnabled) {
            val queued = store.transaction {
                val snapshot = load()
                boardOrder(snapshot.rows.values).filter { ticket ->
                    ticket.status == TicketStatus.TODO && ticket.projectId == projectId &&
                        ticket.queuedFor?.startsWith("role:") == true && snapshot.holding(ticket.id).isEmpty()
                }.map { it.id }
            }
            val refusedRoles = mutableSetOf<String?>()
            for (id in queued) {
                val lane = store.transaction { store.tickets.get(id)?.queuedFor }
                if (lane in refusedRoles) continue
                val answer = runCatching { handOut(id, HandOutOrigin.QUEUE_DRAIN) }.getOrNull()
                if (answer?.ticket?.start?.ok != true) refusedRoles += lane
            }
        }
        val roles = agents?.list().orEmpty().filter { it.projectId == projectId }.map { it.role }.distinct()
        roles.forEach { pruneIdleClones(it) }
    }

    /**
     * LANE-CAPS: a clone ("<name> 2", added by [extraAgent]) that is idle while the role has more agents than the cap of its
     * project allows is removed. The role's first agent is never removed, and nothing running is touched.
     */
    private fun pruneIdleClones(role: String) {
        val profiles = agents ?: return
        val sessionManager = sessions ?: return
        val matching = profiles.list().filter { it.role == role }
        val original = matching.firstOrNull() ?: return
        val cap = maxOf(1, laneCap(original.projectId, role))
        val active = sessionManager.list().filter { it.state in ACTIVE_STATES }
        val clone = Regex("^" + Regex.escape(original.name) + " \\d+$")
        var excess = matching.size - cap
        for (candidate in matching.drop(1).reversed()) {
            if (excess <= 0) break
            val id = candidate.id ?: continue
            if (!clone.matches(candidate.name) || active.any { it.agentId == id }) continue
            runCatching { profiles.delete(id) }.onSuccess { excess-- }
        }
    }

    /** Link two tickets (idempotent: an existing link changes nothing). Answered with `ticketId`'s ticket. */
    @Synchronized
    fun link(request: TicketLinkRequest): TicketAnswer {
        val (primary, link) = normalise(request)
        // KNOWLEDGE-PTR-1 (C): a `relates_to` link (ticket_create/update relatedTo) refreshes the asking ticket's RELATED block.
        val described = if (link.type == TicketLinkType.RELATES_TO) {
            store.transaction { store.tickets.get(primary) }?.let { row ->
                describeRelated(primary, row.title, row.description, row.projectId, extraLink = link)?.takeIf { it != row.description }
            }
        } else null
        val outcome = commit { s ->
            described?.let { d -> s.rows[primary]?.let { s.rows[primary] = it.copy(description = d) } }
            s.require(link.ticketId)
            s.require(link.relatedId)
            if (link.type == TicketLinkType.BLOCKED_BY || link.type == TicketLinkType.CHILD_OF) {
                // The new edge closes a cycle exactly when its target already reaches its source.
                s.path(from = link.relatedId, to = link.ticketId, type = link.type)?.let { path ->
                    throw IllegalArgumentException(
                        "${link.ticketId} ${link.type} ${link.relatedId} would close a cycle: " +
                            "${path.joinToString(" ${link.type} ")} already",
                    )
                }
            }
            s.addLink(link.copy(createdAt = s.now))
        }
        return answer(primary, outcome)
    }

    /** Remove a link; refused when there is no such link. Answered with `ticketId`'s ticket. */
    @Synchronized
    fun unlink(request: TicketLinkRequest): TicketAnswer {
        val (primary, link) = normalise(request)
        val outcome = commit { s ->
            s.require(link.ticketId)
            s.require(link.relatedId)
            require(s.removeLink(link)) {
                "no link '${request.ticketId.trim()} ${request.type.trim().lowercase()} ${request.relatedId.trim()}'"
            }
        }
        return answer(primary, outcome)
    }

    /** Put the listed tickets on top of their columns, in order (see [TicketReorder]). */
    @Synchronized
    fun reorder(request: TicketReorder): BoardAnswer {
        val ids = request.ids.map { it.trim() }
        require(ids.isNotEmpty()) { "ids is empty: list the tickets to put on top, in order" }
        ids.groupingBy { it }.eachCount().entries.firstOrNull { it.value > 1 }?.let {
            throw IllegalArgumentException("ids names '${it.key.take(80)}' more than once")
        }
        val status = request.status?.let { checkStatus(it) }

        val outcome = commit { s ->
            ids.forEach { s.require(it) }
            if (status != null) {
                val strays = ids.filter { s.rows.getValue(it).status != status }
                require(strays.isEmpty()) {
                    "${strays.joinToString(", ")} ${if (strays.size == 1) "is" else "are"} not in $status: " +
                        "a reorder never moves a ticket to another column (ticket_move does)"
                }
            }
            for ((column, listed) in ids.groupBy { s.rows.getValue(it).status }) {
                listed.forEachIndexed { index, id -> s.place(id, column, index) }
                s.named += listed
            }
        }
        return boardAnswer(outcome)
    }

    /** Delete ticket [id] and its links, releasing whatever it held. */
    @Synchronized
    fun delete(id: String): BoardAnswer {
        val wanted = checkId(id)
        var gone: TicketRow? = null
        val outcome = commit { s ->
            gone = s.require(wanted)
            s.delete(wanted)
        }
        // IMG-3: the ticket's images go with it, except one another remaining ticket still references (shared sha256).
        gone?.let { releaseImages(it.projectId, it.images) }
        return boardAnswer(outcome)
    }

    // --- the operation frame ------------------------------------------------------------------------

    /** What one committed operation did: the board after it, and which tickets changed or went. */
    private class Outcome(
        val board: List<Ticket>,
        val changed: List<String>,
        val deleted: List<String>,
    )

    private fun load(): Snapshot = Snapshot(store.tickets.all(), store.tickets.links(), clock())

    /**
     * One operation: load, apply [op], settle the derived column, renumber, stamp, write -- all in one
     * transaction, so a refusal thrown by [op] (or a failed write) leaves the database as it was.
     *
     * A WRITE-FIRST transaction ([AgentHqStore.writeTransaction], BUG-BoardCommitImmediate): it reads the whole
     * board and then writes, so as a deferred transaction any other writer (a trigger, a usage row, the
     * orchestrator) committing between that read and the write aborted it with SQLITE_BUSY_SNAPSHOT, which
     * busy_timeout does not cover. Holding the lock from the start makes that writer wait instead.
     */
    private fun commit(op: (Snapshot) -> Unit): Outcome = store.writeTransaction {
        val s = load()
        val before = views(s.original, s.originalLinks)
        op(s)
        s.settle()
        s.renumber()
        s.stamp()
        s.write(store.tickets)
        val after = views(s.rows, s.links)
        val board = boardOrder(s.rows.values).map { after.getValue(it.id) }
        val changed = board.filter { before[it.id] != it }.map { it.id }
        eventAppender().record(
            before = before.mapValues { it.value.status }, after = board.associate { it.id to it.status },
            changedIds = changed, deleted = s.deleted,
        )
        Outcome(board, changed, s.deleted.toList())
    }

    private fun answer(primary: String, outcome: Outcome): TicketAnswer {
        val byId = outcome.board.associateBy { it.id }
        val ticket = byId.getValue(primary)
        if (outcome.changed.isEmpty()) return TicketAnswer(ticket, pushed = false)
        val order = outcome.changed.filter { it == primary } + outcome.changed.filter { it != primary }
        push(BoardEvent.Changed(order.map { byId.getValue(it) }))
        announce(outcome)
        return TicketAnswer(ticket, pushed = primary in outcome.changed)
    }

    private fun boardAnswer(outcome: Outcome): BoardAnswer {
        if (outcome.changed.isEmpty() && outcome.deleted.isEmpty()) return BoardAnswer(outcome.board, pushed = false)
        push(BoardEvent.Replaced(outcome.board))
        announce(outcome)
        return BoardAnswer(outcome.board, pushed = true)
    }

    // After the commit and its push: a sink that throws must not turn a stored change into a reported failure.
    private fun push(event: BoardEvent) {
        runCatching { eventSink(event) }.onFailure { System.err.println("[agentHq] ticket push failed: $it") }
    }

    private fun announce(outcome: Outcome) {
        runCatching { eventAppender().deliver() }.onFailure { System.err.println("[agentHq] ticket event delivery failed: $it") }
    }

    private fun newId(s: Snapshot): String {
        repeat(10) {
            val candidate = idFactory()
            if (candidate !in s.rows && candidate !in s.deleted) return candidate
        }
        error("could not find a free ticket id")
    }

    companion object {
        const val MAX_TITLE_LENGTH = 200

        /** How long [close] waits for the session events already queued to land; see there. */
        const val CLOSE_DRAIN_TIMEOUT_MS = 5_000L

        /** BUG-E2E-2: lastRun.detail of a run whose ticket the daemon moved to done itself. */
        const val AUTO_DONE_DETAIL =
            "moved to done by the daemon: the run SUCCEEDED and the ticket was still in_progress"

        /** GAP 855db590: lastRun.detail prefix of a SUCCEEDED run that did no work, so the ticket was NOT moved to done. */
        const val NO_WORK_DETAIL = "not moved to done: the run exited cleanly but did no work: "
        const val MAX_DESCRIPTION_LENGTH = 100_000
        /** BUG-REGRESS-1: how many candidates' refusals a `nothing startable` names before counting the rest. */
        private const val MAX_REFUSALS_SHOWN = 3

        /**
         * BUG-WT-1: the top of the git checkout [dir] sits in ([dir] or the nearest parent holding a `.git`, a
         * directory or the file a linked worktree has), or null when [dir] is not in a git checkout. Also what the
         * daemon's boot line reads (BUG-REGRESS-1), so the line names the checkout a start really cuts from.
         */
        internal fun gitRootOf(dir: File): File? =
            generateSequence(dir.absoluteFile) { it.parentFile }.firstOrNull { File(it, ".git").exists() }
        private val ACTIVE_STATES = setOf(SessionState.STARTING, SessionState.BUSY, SessionState.WAITING_INPUT)

        private fun checkId(raw: String): String {
            val id = raw.trim()
            require(id.isNotEmpty()) { "expects a ticket id" }
            return id
        }

        private fun checkTitle(raw: String): String {
            val title = raw.trim()
            require(title.isNotEmpty()) { "title is blank" }
            require(title.length <= MAX_TITLE_LENGTH) { "title is longer than $MAX_TITLE_LENGTH characters" }
            require(title.none { it.isISOControl() }) { "title contains a control character" }
            return title
        }

        private fun checkDescription(raw: String): String {
            val description = raw.trim()
            require(description.length <= MAX_DESCRIPTION_LENGTH) {
                "description is longer than $MAX_DESCRIPTION_LENGTH characters"
            }
            return description
        }

        private fun checkType(raw: String): String {
            // ORIGIN-SCRUB-1b: an older client may still send a retired spelling; it is stored under its replacement.
            val type = TicketType.normalize(raw.trim().lowercase())
            require(type in TicketType.ALL) {
                "ticketType '${type.take(40)}' is not a ticket type: ${TicketType.ALL.joinToString(", ")}"
            }
            return type
        }

        private fun checkStatus(raw: String): String {
            val status = raw.trim().lowercase()
            require(status in TicketStatus.ALL) {
                "status '${status.take(40)}' is not a column: ${TicketStatus.ALL.joinToString(", ")}"
            }
            return status
        }

        /** The request's own ticket, and the link in its stored direction. */
        private fun normalise(request: TicketLinkRequest): Pair<String, TicketLinkRow> {
            val a = request.ticketId.trim()
            val b = request.relatedId.trim()
            require(a.isNotEmpty() && b.isNotEmpty()) { "ticketId and relatedId are both required" }
            require(a != b) { "a ticket cannot be linked to itself" }
            val link = when (val type = request.type.trim().lowercase()) {
                TicketLinkType.BLOCKED_BY, TicketLinkType.DEPENDS_ON -> TicketLinkRow(a, b, TicketLinkType.BLOCKED_BY)
                TicketLinkType.BLOCKS -> TicketLinkRow(b, a, TicketLinkType.BLOCKED_BY)
                TicketLinkType.CHILD_OF -> TicketLinkRow(a, b, TicketLinkType.CHILD_OF)
                TicketLinkType.PARENT_OF -> TicketLinkRow(b, a, TicketLinkType.CHILD_OF)
                TicketLinkType.DUPLICATES -> TicketLinkRow(a, b, TicketLinkType.DUPLICATES)
                TicketLinkType.DUPLICATED_BY -> TicketLinkRow(b, a, TicketLinkType.DUPLICATES)
                // Symmetric, so one row whichever way round it was asked for.
                TicketLinkType.RELATES_TO -> TicketLinkRow(minOf(a, b), maxOf(a, b), TicketLinkType.RELATES_TO)
                else -> throw IllegalArgumentException(
                    "type '${type.take(40)}' is not a link type: ${TicketLinkType.ALL.joinToString(", ")}",
                )
            }
            return a to link
        }

        /** How [type] (a stored direction) reads from the other ticket's side. */
        private fun reverse(type: String): String = when (type) {
            TicketLinkType.BLOCKED_BY -> TicketLinkType.BLOCKS
            TicketLinkType.CHILD_OF -> TicketLinkType.PARENT_OF
            TicketLinkType.DUPLICATES -> TicketLinkType.DUPLICATED_BY
            else -> type
        }

        /** The prerequisites among [ids] that hold a ticket: not done, or done as won't fix. */
        private fun holders(ids: List<String>, rows: Map<String, TicketRow>): List<TicketRow> =
            ids.mapNotNull { rows[it] }.filter { it.status != TicketStatus.DONE || it.wontFix }

        /** Why [holding] holds a ticket, in words; null when nothing does. */
        private fun holdReason(holding: List<TicketRow>): String? {
            if (holding.isEmpty()) return null
            val waiting = holding.filterNot { it.wontFix }.map { it.id }
            val declined = holding.filter { it.wontFix }.map { it.id }
            return listOfNotNull(
                waiting.takeIf { it.isNotEmpty() }?.let { "waiting for ${it.joinToString(", ")}" },
                declined.takeIf { it.isNotEmpty() }?.let {
                    "${it.joinToString(", ")} ${if (it.size == 1) "was" else "were"} closed as won't fix"
                },
            ).joinToString("; ")
        }

        /** [rows] in board order: column by column ([TicketStatus.ALL] first), then position. */
        private fun boardOrder(rows: Collection<TicketRow>): List<TicketRow> {
            val rank = TicketStatus.ALL.withIndex().associate { it.value to it.index }
            return rows.sortedWith(
                compareBy<TicketRow>({ rank[it.status] ?: rank.size }, { it.status }, { it.position }, { it.id }),
            )
        }

    }

    /** Every ticket as the wire shows it, with dependency and executor/context views derived by the daemon. */
    private fun views(rows: Map<String, TicketRow>, links: List<TicketLinkRow>): Map<String, Ticket> {
        val own = HashMap<String, MutableList<TicketLink>>()
        val prerequisites = HashMap<String, MutableList<String>>()
        for (link in links) {
            own.getOrPut(link.ticketId) { mutableListOf() } += TicketLink(link.type, link.relatedId)
            own.getOrPut(link.relatedId) { mutableListOf() } += TicketLink(reverse(link.type), link.ticketId)
            if (link.type == TicketLinkType.BLOCKED_BY) {
                prerequisites.getOrPut(link.ticketId) { mutableListOf() } += link.relatedId
            }
        }
        return rows.mapValues { (id, row) ->
            val holding = holders(prerequisites[id].orEmpty(), rows)
            Ticket(
                id = row.id,
                projectId = row.projectId,
                epicId = row.epicId,
                title = row.title,
                description = row.description,
                ticketType = row.ticketType,
                status = row.status,
                position = row.position,
                wontFix = row.wontFix,
                links = own[id].orEmpty().toList(),
                heldBy = holding.map { it.id },
                holdReason = holdReason(holding),
                createdAt = row.createdAt,
                updatedAt = row.updatedAt,
                agentId = row.agentId,
                provider = row.provider,
                model = row.model,
                useWorktree = row.useWorktree,
                worktreeBranch = row.worktreeBranch,
                branch = row.branch,
                base = row.base,
                includeLinkedTasksContext = row.includeLinkedTasksContext,
                skillIds = row.skillIds,
                skillsMode = row.skillsMode,
                includeSkillsContext = row.includeSkillsContext,
                images = row.images,
                weight = row.weight,
                contextLimit = row.contextLimit,
                effectiveContextLimit = effectiveContextLimit(row),
                effort = row.effort,
                effectiveEffort = effectiveEffort(row),
                queuedFor = row.queuedFor,
                queuedAt = row.queuedAt,
                sessionId = row.sessionId,
                executorOverrides = ExecutorOverrides(provider = row.provider, model = row.model),
                effectiveExecutor = effectiveExecutor(row),
                effectiveContext = context.effective(row.includeLinkedTasksContext, row.includeSkillsContext),
                lastRun = store.ticketRuns.byTicket(id).lastOrNull { it.endedAt != null }?.let {
                    TicketRun(
                        sessionId = it.sessionId,
                        startedAt = it.startedAt,
                        endedAt = it.endedAt,
                        outcome = it.outcome,
                        detail = it.detail,
                    )
                },
            )
        }
    }

    /** CONTEXT-LIMIT-1: the ticket's limit (or none) and how its effective harness would honour one. */
    private fun effectiveContextLimit(row: TicketRow): com.superiorsoftllc.agenthq.protocol.EffectiveContextLimit {
        val manager = sessions
        val harness = manager?.let { m -> runCatching { effectiveExecutor(row)?.provider?.value }.getOrNull() ?: m.providerId }
        val capability = harness?.let { manager.providerFor(it) }?.contextLimit()
        return com.superiorsoftllc.agenthq.protocol.EffectiveContextLimit(
            value = row.contextLimit,
            source = if (row.contextLimit != null) "ticket" else "default",
            support = capability?.support?.wire,
            how = capability?.how,
        )
    }

    /** EFFORT-1: the ticket's effort (ticket pin, else rule, agent profile, role binding, else none) with its source, and how its effective harness honours one. */
    private fun effectiveEffort(row: TicketRow): com.superiorsoftllc.agenthq.protocol.EffectiveEffort {
        val manager = sessions
        val harness = manager?.let { m -> runCatching { effectiveExecutor(row)?.provider?.value }.getOrNull() ?: m.providerId }
        val capability = harness?.let { manager.providerFor(it) }?.effort()
        val rule = routingChoice(row)
        val assigned = row.agentId ?: rule?.setRole?.let { "role:$it" }
        val profile = assigned?.let { ticketProfile(it) }
        val roleId = assigned?.takeIf { it.startsWith("role:") }?.removePrefix("role:")
        val (value, source) = when {
            row.effort != null -> row.effort to "ticket"
            rule?.effort != null -> rule.effort to "rule"
            profile?.effort != null -> profile.effort to "agent"
            else -> {
                val bound = (profile ?: roleId?.let { AgentProfile(name = it, role = it) })?.let { agents?.executorFor(it, null, null, null) }
                if (bound?.effort != null) bound.effort to "role" else null to "default"
            }
        }
        return com.superiorsoftllc.agenthq.protocol.EffectiveEffort(value = value, source = source, support = capability?.support?.wire, how = capability?.how)
    }

    /** TICKET-WEIGHT: the mapping's choice for [row] when [WeightExecutors.applies] (nothing else pins an executor), else null. */
    private fun weightChoice(row: TicketRow, boundProvider: String?, boundModel: String?): WeightExecutor? =
        if (WeightExecutors.applies(row.weight, row.provider, row.model, boundProvider, boundModel)) {
            // H2: a weight route naming a harness that is not installed is skipped (model with it, which only fits that
            // harness), so the ticket runs on the first installed one; with none installed the start says so.
            weightExecutors().forWeight(row.weight)?.takeIf { e -> e.provider.let { p -> p == null || sessions?.hasProvider(p) != false } }
        } else null

    /**
     * ROUTING-1: the first rule of [row]'s project that matches it, or null when the ticket pins its own provider or model
     * (an explicit pin always wins) or the project has no rules. The ticket's role is read from its assignment.
     */
    private fun routingChoice(row: TicketRow): com.superiorsoftllc.agenthq.protocol.RoutingRule? {
        if (row.provider != null || row.model != null) return null
        val rules = routingRules(row.projectId)
        if (rules.isEmpty()) return null
        val role = row.agentId?.let { a -> if (a.startsWith("role:")) a.removePrefix("role:") else ticketProfile(a)?.role }
        return RoutingRules.match(rules, row.weight, row.ticketType, role)
    }

    private fun effectiveExecutor(row: TicketRow): EffectiveExecutor? {
        val rule = routingChoice(row)
        val assigned = row.agentId ?: rule?.setRole?.let { "role:$it" } ?: return null
        val isRole = assigned.startsWith("role:")
        val roleId = if (isRole) assigned.removePrefix("role:") else null
        val profile = ticketProfile(assigned)
        val role = profile?.role ?: roleId
        val profileProvider = profile?.provider
        // ROLE-1d: the role's binding comes from the one resolver (executorFor), never a second path.
        val bound = (profile ?: roleId?.let { AgentProfile(name = it, role = it) })
            ?.let { agents?.executorFor(it, row.provider, row.model, null) }
        if (rule != null) {
            return EffectiveExecutor(
                agentId = profile?.id ?: assigned.takeUnless { isRole },
                role = role,
                provider = ExecutorField(rule.provider, "rule"),
                model = ExecutorField(rule.model, "rule"),
                rule = rule.name,
            )
        }
        val byWeight = if (routingRules(row.projectId).isEmpty()) weightChoice(row, profileProvider ?: bound?.provider, profile?.model ?: bound?.model) else null
        val provider = when {
            row.provider != null -> ExecutorField(row.provider, "ticket")
            byWeight?.provider != null -> ExecutorField(byWeight.provider, "weight")
            profileProvider != null -> ExecutorField(profileProvider, "profile")
            bound?.provider != null -> ExecutorField(bound.provider, "role")
            isRole -> ExecutorField(null, "role")
            sessions != null -> ExecutorField(sessions.providerId, "default")
            else -> ExecutorField(null, "default")
        }
        val profileProviderOrDefault = profileProvider ?: sessions?.providerId
        val profileModelApplies = profile?.model != null &&
            (row.provider == null || row.provider == profileProviderOrDefault)
        val model = when {
            row.model != null -> ExecutorField(row.model, "ticket")
            byWeight?.model != null -> ExecutorField(byWeight.model, "weight")
            profileModelApplies -> ExecutorField(profile!!.model, "profile")
            bound?.model != null -> ExecutorField(bound.model, "role")
            isRole && row.provider == null && profile?.model == null && profileProvider == null ->
                ExecutorField(null, "role")
            else -> {
                val providerId = row.provider ?: profileProviderOrDefault
                val defaultModel = sessions?.providers?.firstOrNull { it.id == providerId }?.defaultModel()
                ExecutorField(defaultModel, "default")
            }
        }
        return EffectiveExecutor(
            agentId = profile?.id ?: assigned.takeUnless { isRole },
            role = role,
            provider = provider,
            model = model,
        )
    }

    /** A `role:<role>` trigger also takes todo tickets assigned to a profile id whose role is that role. */
    private fun matchesTriggerAgent(ticketAgentId: String?, filter: String?): Boolean {
        if (filter == null || ticketAgentId == filter) return true
        if (!filter.startsWith("role:") || ticketAgentId == null) return false
        return agents?.get(ticketAgentId)?.role == filter.removePrefix("role:")
    }

    private fun normalizeRoleAgentId(agentId: String): String =
        if (agentId.startsWith("role:")) "role:" + RoleIds.normalize(agentId.removePrefix("role:")) else agentId

    private fun ticketProfile(agentId: String): AgentProfile? = when {
        agentId.startsWith("role:") -> agents?.list()?.firstOrNull { it.role == agentId.removePrefix("role:") }
        else -> agents?.get(agentId)
    }

    private fun validateAgentId(agentId: String?, checkCatalog: Boolean) {
        if (agentId == null) return
        // LIMITS: a ticket newly assigned to a role the role allowlist leaves out is refused (a stored one stays editable).
        if (checkCatalog) sessions?.requireAllowedRole(if (agentId.startsWith("role:")) agentId.removePrefix("role:") else ticketProfile(agentId)?.role)
        if (agentId.startsWith("role:")) {
            val role = agentId.removePrefix("role:")
            require(RoleIds.PATTERN.matches(role) && (!checkCatalog || role in roleIds())) {
                "agentId '$agentId' must be a profile id or role:<canonical role id>; " +
                    "valid role ids: ${roleIds().joinToString(", ")}"
            }
            return
        }
        require(agents?.get(agentId) != null) {
            "no agent profile '${agentId.take(80)}' (agent_list shows the profiles)"
        }
    }

    private fun validateExecutorPins(agentId: String?, provider: String?, model: String?) {
        val profile = agentId?.let(::ticketProfile)
        val profileProvider = profile?.provider
        val profileModel = profile?.model?.takeIf { provider == null || provider == (profileProvider ?: sessions?.providerId) }
        val needsCheck = provider != null || model != null || profileProvider != null || profileModel != null
        if (!needsCheck) return
        val manager = requireNotNull(sessions) { "ticket executor validation is unavailable" }
        manager.checkSpawn(provider ?: profileProvider, model ?: profileModel)
        // BUG-E2E-1: the ticket's OWN model must be one its provider lists, when that provider can list them (the same
        // rule an agent profile save applies). Never coerced: a typo is refused with the provider's list to pick from.
        if (model != null) {
            val providerId = provider ?: profileProvider ?: manager.providerId
            val known = manager.providerFor(providerId)?.knownModels()
            require(known == null || model in known) {
                "unknown model '${model.take(80)}' for provider '$providerId' (it lists: ${known!!.take(20).joinToString()})"
            }
        }
    }

    /**
     * One operation's working copy of the board, loaded inside its transaction: every ticket, every link,
     * and each column as an ordered list of ids. The operation edits it in memory; [write] stores the
     * difference from what was loaded.
     */
    private class Snapshot(rows: List<TicketRow>, links: List<TicketLinkRow>, val now: Long) {
        val original: Map<String, TicketRow> = rows.associateBy { it.id }
        val originalLinks: List<TicketLinkRow> = links
        val rows: MutableMap<String, TicketRow> = LinkedHashMap(original)
        val links: MutableList<TicketLinkRow> = links.toMutableList()

        /** Tickets a client named in this operation: a new position counts as their own change. */
        val named = mutableSetOf<String>()
        val deleted = mutableListOf<String>()
        private val addedLinks = mutableListOf<TicketLinkRow>()
        private val removedLinks = mutableListOf<TicketLinkRow>()

        /** Each column's ids, top first. Known columns always exist; a stored unknown one is kept as found. */
        private val columns = LinkedHashMap<String, MutableList<String>>().apply {
            TicketStatus.ALL.forEach { put(it, mutableListOf()) }
            rows.sortedWith(compareBy<TicketRow>({ it.position }, { it.id }))
                .forEach { getOrPut(it.status) { mutableListOf() }.add(it.id) }
        }

        fun require(id: String): TicketRow = rows[id] ?: throw IllegalArgumentException("no ticket '${id.take(80)}'")

        fun create(row: TicketRow) {
            rows[row.id] = row
            columns.getOrPut(row.status) { mutableListOf() }.add(row.id)
        }

        fun delete(id: String) {
            val row = rows.remove(id) ?: return
            columns[row.status]?.remove(id)
            links.removeAll { it.ticketId == id || it.relatedId == id } // the database cascades the same way
            deleted += id
        }

        /** Put [id] in column [status] at [index] (clamped to the column; null = the bottom). */
        fun place(id: String, status: String, index: Int?) {
            val row = rows.getValue(id)
            columns[row.status]?.remove(id)
            val target = columns.getOrPut(status) { mutableListOf() }
            target.add(index?.coerceIn(0, target.size) ?: target.size, id)
            rows[id] = row.copy(
                status = status,
                queuedFor = if (status == TicketStatus.TODO) row.queuedFor else null,
                queuedAt = if (status == TicketStatus.TODO) row.queuedAt else null,
            )
        }

        fun addLink(link: TicketLinkRow) {
            if (links.none { it.sameAs(link) }) {
                links += link
                addedLinks += link
            }
        }

        fun removeLink(link: TicketLinkRow): Boolean {
            val existing = links.firstOrNull { it.sameAs(link) } ?: return false
            links.remove(existing)
            removedLinks += existing
            return true
        }

        /** The `blocked_by` prerequisites holding [id] right now. */
        fun holding(id: String): List<TicketRow> =
            holders(links.filter { it.type == TicketLinkType.BLOCKED_BY && it.ticketId == id }.map { it.relatedId }, rows)

        /** A chain of [type] links from [from] to [to] (both ends included), or null when there is none. */
        fun path(from: String, to: String, type: String): List<String>? {
            val next = links.filter { it.type == type }.groupBy({ it.ticketId }, { it.relatedId })
            val cameFrom = HashMap<String, String>()
            val queue = ArrayDeque(listOf(from))
            val seen = mutableSetOf(from)
            while (queue.isNotEmpty()) {
                val node = queue.removeFirst()
                if (node == to) return generateSequence(to) { cameFrom[it] }.toList().reversed()
                for (n in next[node].orEmpty()) if (seen.add(n)) {
                    cameFrom[n] = node
                    queue.addLast(n)
                }
            }
            return null
        }

        /** The derived column: a held `todo` ticket goes to `pending`, a `pending` one nothing holds to `todo`. */
        fun settle() {
            val hold = columns.getValue(TicketStatus.TODO).filter { holding(it).isNotEmpty() }
            val release = columns.getValue(TicketStatus.PENDING).filter { holding(it).isEmpty() }
            hold.forEach { place(it, TicketStatus.PENDING, null) }
            release.forEach { place(it, TicketStatus.TODO, null) }
        }

        /** Positions = index in the column, so every column is contiguous from 0. */
        fun renumber() {
            for ((status, ids) in columns) {
                ids.forEachIndexed { index, id ->
                    val row = rows.getValue(id)
                    if (row.position != index || row.status != status) rows[id] = row.copy(status = status, position = index)
                }
            }
        }

        /** Move updatedAt where the ticket itself changed ([Ticket.updatedAt] says what counts). */
        fun stamp() {
            for ((id, row) in rows) {
                val before = original[id] ?: continue // created now: its timestamps are already [now]
                val own = row.title != before.title || row.description != before.description ||
                    row.ticketType != before.ticketType || row.status != before.status ||
                    row.wontFix != before.wontFix || row.agentId != before.agentId ||
                    row.provider != before.provider || row.model != before.model ||
                    row.useWorktree != before.useWorktree || row.worktreeBranch != before.worktreeBranch ||
                    row.includeLinkedTasksContext != before.includeLinkedTasksContext ||
                    row.skillIds != before.skillIds || row.skillsMode != before.skillsMode ||
                    row.includeSkillsContext != before.includeSkillsContext ||
                    row.images != before.images ||
                    (id in named && row.position != before.position)
                if (own) rows[id] = row.copy(updatedAt = now)
            }
        }

        /** Store the difference from what was loaded: deletions first, so links never name a missing ticket. */
        fun write(repository: RowTickets) {
            deleted.forEach { repository.delete(it) }
            removedLinks.forEach { repository.unlink(it) }
            for ((id, row) in rows) {
                val before = original[id]
                if (before == null) repository.create(row) else if (before != row) repository.update(row)
            }
            addedLinks.forEach { repository.link(it) }
        }

        private fun TicketLinkRow.sameAs(other: TicketLinkRow) =
            ticketId == other.ticketId && relatedId == other.relatedId && type == other.type
    }
}

/** DP-5f-3a: the board's store as the rules see it (TicketRow / TicketLinkRow), every call going through [BoardHost.storage]. */
private class RowStore(private val host: BoardHost) {
    val tickets = RowTickets(host)
    val ticketRuns get() = host.storage.tables.runs

    fun <T> transaction(block: () -> T): T = host.storage.transaction { block() }
    fun <T> writeTransaction(block: () -> T): T = host.storage.writeTransaction { block() }
}

private class RowTickets(private val host: BoardHost) {
    private val table get() = host.storage.tables.tickets

    fun create(row: TicketRow) = table.create(row.toStored())
    fun update(row: TicketRow) = table.update(row.toStored())
    fun get(id: String): TicketRow? = table.get(id)?.toRow()
    fun all(): List<TicketRow> = table.all().map { it.toRow() }
    fun delete(id: String): Boolean = table.delete(id)
    fun link(link: TicketLinkRow): Boolean = table.link(link.toStored())
    fun unlink(link: TicketLinkRow): Boolean = table.unlink(link.toStored())
    fun links(): List<TicketLinkRow> = table.links().map { it.toRow() }
}
