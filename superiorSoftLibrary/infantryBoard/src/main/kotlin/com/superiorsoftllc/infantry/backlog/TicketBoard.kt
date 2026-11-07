// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.backlog

import com.superiorsoftllc.infantry.ticketfiles.RelatedDescription
import com.superiorsoftllc.infantry.ticketfiles.RelatedDraft
import com.superiorsoftllc.infantry.settings.RoutingRules
import com.superiorsoftllc.infantry.settings.WeightExecutor
import com.superiorsoftllc.infantry.settings.WeightExecutors
import com.superiorsoftllc.infantry.usage.GateDecision
import com.superiorsoftllc.infantry.usage.LaunchOrigin
import com.superiorsoftllc.infantry.agents.composeFirstTurn
import com.superiorsoftllc.infantry.context.ContextInput
import com.superiorsoftllc.infantry.context.LinkLine
import com.superiorsoftllc.infantry.context.ReportTo
import com.superiorsoftllc.infantry.context.TicketContext
import com.superiorsoftllc.infantry.daemon.api.board.BoardAgent
import com.superiorsoftllc.infantry.daemon.api.board.BoardHost
import com.superiorsoftllc.infantry.daemon.api.board.BoardSessionDetail
import com.superiorsoftllc.infantry.daemon.api.board.BoardSessionEvent
import com.superiorsoftllc.infantry.daemon.api.board.ContextSourceInfo
import com.superiorsoftllc.infantry.daemon.api.sessions.SessionView
import com.superiorsoftllc.infantry.daemon.api.board.BranchOutcome
import com.superiorsoftllc.infantry.daemon.api.board.BranchRequest
import com.superiorsoftllc.infantry.daemon.api.board.SessionStart
import com.superiorsoftllc.infantry.daemon.api.board.SessionStartResult
import com.superiorsoftllc.infantry.daemon.api.board.StoredRun
import com.superiorsoftllc.infantry.daemon.api.board.TicketEventAppender
import com.superiorsoftllc.infantry.persistence.toRow
import com.superiorsoftllc.infantry.persistence.toStored
import com.superiorsoftllc.infantry.persistence.TicketLinkRow
import com.superiorsoftllc.infantry.persistence.TicketRow
import com.superiorsoftllc.infantry.protocol.AgentQueueEntry
import com.superiorsoftllc.infantry.protocol.AgentQueueState
import com.superiorsoftllc.infantry.protocol.EffectiveExecutor
import com.superiorsoftllc.infantry.protocol.ExecutorOverrides
import com.superiorsoftllc.infantry.protocol.ExecutorField
import com.superiorsoftllc.infantry.protocol.HasWork
import com.superiorsoftllc.infantry.protocol.TicketWeight
import com.superiorsoftllc.infantry.protocol.FinishedPayload
import com.superiorsoftllc.infantry.protocol.Ticket
import com.superiorsoftllc.infantry.usage.PacingVerdict
import com.superiorsoftllc.infantry.protocol.TicketLink
import com.superiorsoftllc.infantry.protocol.TicketLinkRequest
import com.superiorsoftllc.infantry.protocol.TicketLinkType
import com.superiorsoftllc.infantry.protocol.TicketMove
import com.superiorsoftllc.infantry.protocol.TicketReorder
import com.superiorsoftllc.infantry.protocol.TicketRun
import com.superiorsoftllc.infantry.protocol.TicketSave
import com.superiorsoftllc.infantry.protocol.SkillsMode
import com.superiorsoftllc.infantry.settings.requireKnownSkills
import com.superiorsoftllc.infantry.protocol.TicketStart
import com.superiorsoftllc.infantry.protocol.TicketStatus
import com.superiorsoftllc.infantry.protocol.TicketType
import com.superiorsoftllc.infantry.protocol.RoleIds
import com.superiorsoftllc.infantry.worktree.TicketBaseRef
import com.superiorsoftllc.infantry.worktree.TicketWorktree
import com.superiorsoftllc.infantry.worktree.WorktreeRecord
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

/** AGENT-TASKS-4: how `assign` places a ticket on an agent: [START] it now, or [QUEUE] it behind the agent's work. */
enum class AssignMode { START, QUEUE }

/** GAP-006b: [REPAIR] is the unbriefed sweep restarting a phantom worker: unattended, never queued. */
private enum class HandOutOrigin { MOVE, START_NEXT, QUEUE_DRAIN, TRIGGER, REPAIR }

/** P9.5d: what a trigger's `prompt` action got from the board -- a session id, or the reason it was refused. */
sealed interface PromptLaunch {
    data class Started(val sessionId: String) : PromptLaunch
    data class Refused(val detail: String) : PromptLaunch
}

private data class AssignmentResolution(
    val profile: BoardAgent? = null,
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
    val host: BoardHost,
    private val clock: () -> Long = System::currentTimeMillis,
    private val idFactory: () -> String = { "ticket-" + UUID.randomUUID().toString().take(8) },
    private val context: TicketContext = TicketContext.NONE,
    private val profilesAvailable: Boolean = false,
    /**
     * BUG-ASSIGN-1: the role ids a ticket may name (the daemon passes its live catalog), listed in the refusal of a
     * role that is not one, so the agent that sent a display name ("Backend Developer") can send the id instead.
     */
    private val roleIds: () -> List<String> = { RoleIds.CANONICAL },
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
    private val weightContext: () -> com.superiorsoftllc.infantry.settings.WeightContext = { com.superiorsoftllc.infantry.settings.WeightContext.DEFAULT },
    /** LANE-CAPS: the most agents of a role that may run at once in a project (1 = one per role, today's behaviour). */
    private val laneCap: (projectId: String?, role: String) -> Int = { _, _ -> 1 },
    /** RUNNER-POOL-3: `runners.default`, read at every start (`local` unless set). */
    private val runnersDefault: () -> String = { com.superiorsoftllc.infantry.settings.Placement.LOCAL },
    /** RUNNER-POOL-3: the registered runners (RUNNER-POOL-2's pool fills this); today only `local`. */
    private val runnerEntries: () -> List<com.superiorsoftllc.infantry.settings.RunnerEntry> = { listOf(com.superiorsoftllc.infantry.settings.RunnerEntry(com.superiorsoftllc.infantry.settings.Placement.LOCAL)) },
    /** ROUTING-1 (contract 1.128.0): a project's routing rules, in order (empty = none), read at every start and view. */
    private val routingRules: (projectId: String?) -> List<com.superiorsoftllc.infantry.protocol.RoutingRule> = { emptyList() },
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
    /** KNOWLEDGE-PTR-1 (C): the stored RELATED (auto) block a save or a `relates_to` link writes atop the description. */
    private val relatedDescription: RelatedDescription = RelatedDescription.NONE,
    /** Skill ids that no stored skill carries (part c moves this onto the host); the default knows none, so any named skill is refused. */
    private val unknownSkills: (List<String>) -> List<String> = { it },
    /** AGENT-TASKS-2: `tickets.agentTasks.handoffNote`, read at every unassign: `auto` (default), `worker` or `off`. */
    private val handoffMode: () -> String = { "auto" },
    /** AGENT-TASKS-2: `limits.agentTasks.unassignWaitSeconds`: how long an unassign waits for a busy worker before forcing it. */
    private val unassignWaitSeconds: () -> Long = { 120 },
    /** AGENT-TASKS-2: `limits.agentTasks.handoffCommits`: commit subjects listed in a handoff note. */
    private val handoffCommits: () -> Long = { 10 },
    /** AGENT-TASKS-2: `limits.agentTasks.handoffChars`: the worker message kept in a handoff note. */
    private val handoffChars: () -> Long = { 1500 },
    /** AGENT-TASKS-3: `limits.agentTasks.queueMax`: how many todo tickets one agent's persistent queue holds. */
    private val queueMax: () -> Long = { 5 },
    /** AGENT-TASKS-3: `tickets.agentTasks.closeDoneWorkerWhenQueued`: end a done ticket's idle worker when its agent has a queued ticket. */
    private val closeDoneWorkerWhenQueued: () -> Boolean = { true },
) {

    /** DP-5f-3d: whether the host has a session manager (was a null check on the constructor's manager). */
    private val sessionsOn: Boolean get() = host.sessions.available()

    /** DP-5f-3a: the board's tables through the host, in the row types the rules below were written against. */
    private val store = RowStore(host)

    /** DP-5f-3b: the daemon's own checkout (the configured repo) through the host's git port. */
    private val repo: File? get() = host.git.defaultRepo()?.let(::File)

    /** The board's own [events] log when one is set (the legacy wiring), else the host's appender. Null log = no events, as before. */
    private fun eventAppender(): TicketEventAppender = events ?: host.events

    /** ROUTING-1: the (ticket, reason) pairs already reported through [routingRefused]. */
    private val reportedRoutingRefusals = java.util.Collections.synchronizedSet(HashSet<String>())

    @Volatile
    private var queueDrainEnabled = true

    /** The board's own thread for session events (see [onSessionEvent]); a daemon, so it never keeps a JVM open. */
    private val sessionEvents = Executors.newSingleThreadExecutor { r ->
        Thread(r, "infantry-ticket-session-events").also { it.isDaemon = true }
    }

    /** AGENT-TASKS-2: fires the bounded wait of an unassign ([unassignWaitSeconds]); a daemon thread, stopped by [close]. */
    private val waitTimer = java.util.concurrent.Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "infantry-ticket-unassign-wait").also { it.isDaemon = true }
    }

    /**
     * AGENT-TASKS-2: where the handoff comment goes (set by the board plugin once its comment store exists): ticket id,
     * project id, body. Null = no comment store, so no note is written.
     */
    @Volatile
    var handoffSink: ((ticketId: String, projectId: String?, body: String) -> Unit)? = null

    /** AGENT-TASKS-2: the latest `handoff` comment's body of a ticket (set by the board plugin); the next start brief carries it. */
    @Volatile
    var latestHandoff: ((ticketId: String) -> String?)? = null

    /** AGENT-TASKS-2: an unassigned ticket's worker that has not ended yet, and what it still owes before it is ended. */
    private class PendingEnd(val ticketId: String, val projectId: String?, val note: String?, var sawBusy: Boolean, var promptFirst: Boolean)

    private val pendingEnds = java.util.concurrent.ConcurrentHashMap<String, PendingEnd>()

    /** AGENT-TASKS-2: sessions the board ended through an unassign; their run is recorded with detail [UNASSIGNED_DETAIL]. */
    private val unassigned: MutableSet<String> = java.util.concurrent.ConcurrentHashMap.newKeySet()

    /** DP-5f-3c: the board hears sessions only through the host's port; closed by [close]. */
    private val sessionSubscription: AutoCloseable = host.sessions.subscribe(::onSessionEvent)

    /** Where each committed change goes; the daemon pushes it to every connected client. */
    @Volatile
    var eventSink: (BoardEvent) -> Unit = {}

    /**
     * BOARD-API-2 (DP-4b): the typed event log (docs/DAEMON-BOARD-API.md). A commit records its events inside its own transaction,
     * and [announce] asks for their delivery after the commit. BOARD-REENTRY-1: the daemon's log delivers on its own thread, so a
     * consumer runs after the board monitor is released and may call the board (a lane hook's ticket_get) without waiting on the move;
     * a log that delivers inline (tests) still runs under the monitor, in board -> consumer order. `TriggerService.onTicketTransition` briefly takes the
     * TriggerService monitor (its `fire`); the order board -> TriggerService cannot cycle because TriggerService never calls the
     * board under its own lock -- a trigger's `start_next` runs on its own single-thread executor, off-lock. An unchanged re-commit
     * is no event, so a ship trigger fires once per move into done. Null = the board keeps no log (tests).
     */
    @Volatile
    var events: TicketEventAppender? = null

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
                .filter { it.status == TicketStatus.TODO && s.holding(it.id).isEmpty() && it.queuedAgent == null } // AGENT-TASKS-3: a queued ticket runs only from its agent's queue
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
                .filter { it.status == TicketStatus.TODO && s.holding(it.id).isEmpty() && it.queuedAgent == null }
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
        if (!host.sessions.available()) return PromptLaunch.Refused("ticket session manager is unavailable")
        if (!profilesAvailable) return PromptLaunch.Refused("agent profiles are unavailable")
        val assignment = resolveAssignment(agentId)
        assignment.refusal?.let { return PromptLaunch.Refused(it) }
        if (assignment.busy) return PromptLaunch.Refused("profile lane '${assignment.queueLane}' is at capacity")
        val profile = assignment.profile ?: return PromptLaunch.Refused("no profile resolved for prompt")
        val executor = host.agents.executorFor(profile, null, null, null, profile.role)
        host.sessions.checkSpawn(executor.provider, executor.model)?.let { return PromptLaunch.Refused(it) }
        // TRAITS-T4/T5: the instance is resolved first and passed in, so the stored row is what this turn rendered.
        // P10-K15: with the skill ids the row will freeze, so the turn renders exactly those.
        val instance = host.agents.instanceFor(profile.id, profile.role)?.let { host.agents.withSkills(it, null) }
        // P10-K9: the session's own project (TRIGGER-NUDGE: the trigger's, when it names one).
        val first = composeFirstTurn(instance?.instructions, instance?.toSnapshots().orEmpty(), profile.role, profile.id, prompt, host.context.knowledgeSection(projectId, profile.role), host.context.skillsSection(instance?.skillIds))
        return when (val started = host.sessions.start(
            SessionStart(
                prompt = first,
                workingDir = repo?.path,
                providerId = executor.provider,
                model = executor.model,
                effort = executor.effort,
                agentId = profile.id,
                role = profile.role,
                projectId = projectId,
                instance = instance,
                origin = "unattended",
            ),
        )) {
            is SessionStartResult.Started -> PromptLaunch.Started(started.session.id)
            is SessionStartResult.Refused -> PromptLaunch.Refused(started.reason)
        }
    }

    /** AGENT-TASKS-3: writes a plain note on a ticket (set by the board plugin; null = comments unavailable, the refusal is not written). */
    var queueNoteSink: ((ticketId: String, projectId: String?, body: String) -> Unit)? = null

    /** AGENT-TASKS-3: the (ticket, reason) pairs a refused queue drain already commented. */
    private val reportedQueueRefusals = java.util.Collections.synchronizedSet(HashSet<String>())

    private fun noteQueueRefusal(ticketId: String, projectId: String?, why: String) {
        if (!reportedQueueRefusals.add("$ticketId|$why")) return
        runCatching { queueNoteSink?.invoke(ticketId, projectId, "Queue start refused, the ticket stays queued: $why") }
    }

    /** AGENT-TASKS-3: ids of [agentId]'s queued todo tickets, lowest rank first. */
    private fun agentQueueIds(agentId: String): List<String> = store.transaction {
        val snapshot = load()
        boardOrder(snapshot.rows.values)
            .filter { it.status == TicketStatus.TODO && it.queuedAgent == agentId && snapshot.holding(it.id).isEmpty() }
            .sortedBy { it.queueRank ?: Int.MAX_VALUE }
            .map { it.id }
    }

    /** AGENT-TASKS-3: the head of [agentId]'s queue (a ticket id), or null when it is empty. */
    fun agentQueueHead(agentId: String): String? = agentQueueIds(agentId).firstOrNull()

    private fun resolveQueueAgent(ref: String): BoardAgent {
        val key = ref.trim()
        require(key.isNotEmpty()) { "an agent (profile id or unique name) is required" }
        host.agents.get(key)?.let { return it }
        val byName = host.agents.list().filter { it.name.equals(key, ignoreCase = true) }
        require(byName.isNotEmpty()) { "no agent '${key.take(80)}'" }
        require(byName.size == 1) { "agent name '${key.take(80)}' is ambiguous (${byName.size} agents): use the profile id" }
        return byName.single()
    }

    /**
     * AGENT-TASKS-3: an agent's persistent ticket queue (docs/AGENT-TASKS-DESIGN.md 2.4). [action] is `list`, `add` (ticket, at = 1-based
     * place, default the end), `remove`, `move` (ticket, at) or `clear`. Only todo tickets, one queue per ticket, at most
     * `limits.agentTasks.queueMax` per agent. Never starts anything: the agent's worker ending does (drainProfileLane).
     */
    fun queue(agentRef: String, action: String = QUEUE_LIST, ticketId: String? = null, at: Int? = null): AgentQueueState {
        // AGENT-QUEUE-HANG-1: the agent is resolved (a host call-out) BEFORE any board lock; a list is one lock-free snapshot read.
        val agent = resolveQueueAgent(agentRef)
        val agentId = agent.id ?: throw IllegalArgumentException("agent '${agent.name}' has no profile id")
        require(action in QUEUE_ACTIONS) { "unknown queue action '${action.take(40)}': use ${QUEUE_ACTIONS.joinToString("|")}" }
        if (action == QUEUE_LIST) return queueState(agentId, agent.name, null)
        return synchronized(this) { queueMutate(agent, agentId, action, ticketId, at) }
    }

    private fun queueState(agentId: String, agentName: String, message: String?): AgentQueueState {
        val snapshot = store.transaction { load() }
        val ids = boardOrder(snapshot.rows.values)
            .filter { it.status == TicketStatus.TODO && it.queuedAgent == agentId && snapshot.holding(it.id).isEmpty() }
            .sortedBy { it.queueRank ?: Int.MAX_VALUE }
        return AgentQueueState(agentId, agentName, ids.mapIndexed { i, r -> AgentQueueEntry(r.id, r.title, i + 1) }, message)
    }

    private fun queueMutate(agent: BoardAgent, agentId: String, action: String, ticketId: String?, at: Int?): AgentQueueState {
        var message: String? = null
        run {
            val id = if (action == QUEUE_CLEAR) null else checkId(ticketId ?: throw IllegalArgumentException("queue $action needs a ticket id"))
            if (action == QUEUE_MOVE) require(at != null) { "queue move needs a place (at)" }
            at?.let { require(it >= 1) { "place must be 1 or more" } }
            val ordered = agentQueueIds(agentId).toMutableList()
            when (action) {
                QUEUE_ADD, QUEUE_MOVE -> {
                    val row = store.transaction { load() }.require(id!!)
                    require(row.status == TicketStatus.TODO) { "ticket $id is ${row.status}: only a todo ticket can be queued" }
                    require(row.queuedAgent == null || row.queuedAgent == agentId) {
                        "ticket $id is already queued for agent ${row.queuedAgent}: remove it there first"
                    }
                    val member = id in ordered
                    require(action != QUEUE_MOVE || member) { "ticket $id is not in agent ${agent.name}'s queue" }
                    require(member || ordered.size < queueMax()) { "agent ${agent.name}'s queue is full (limits.agentTasks.queueMax = ${queueMax()})" }
                    ordered.remove(id)
                    ordered.add(((at ?: (ordered.size + 1)) - 1).coerceIn(0, ordered.size), id)
                }
                QUEUE_REMOVE -> {
                    require(id in ordered) { "ticket $id is not in agent ${agent.name}'s queue" }
                    ordered.remove(id)
                }
                QUEUE_CLEAR -> {
                    message = "cleared ${ordered.size}"
                    ordered.clear()
                }
            }
            val outcome = commit { s ->
                // Everything of this agent's that was queued and is no longer listed leaves; the listed get places 1..n.
                s.rows.values.filter { it.queuedAgent == agentId && it.id !in ordered }.forEach {
                    s.rows[it.id] = it.copy(queuedAgent = null, queueRank = null)
                }
                ordered.forEachIndexed { i, tid -> s.rows[tid] = s.rows.getValue(tid).copy(queuedAgent = agentId, queueRank = i + 1) }
            }
            if (outcome.changed.isNotEmpty()) {
                val byId = outcome.board.associateBy { it.id }
                push(BoardEvent.Changed(outcome.changed.map { byId.getValue(it) }))
                announce(outcome)
            }
        }
        return queueState(agentId, agent.name, message)
    }

    /**
     * AGENT-TASKS-3: a done ticket's worker is ended once it is IDLE when its agent has a queued ticket, so the exit drains the queue
     * even if no hook closes the worker (`tickets.agentTasks.closeDoneWorkerWhenQueued`). Runs on [sessionEvents], never under a lock.
     */
    private fun closeDoneWorkerWhenQueued(sessionId: String) {
        if (!closeDoneWorkerWhenQueued() || !queueDrainEnabled) return
        try {
            sessionEvents.execute {
                runCatching {
                    val view = host.sessions.get(sessionId)?.takeIf { it.live && it.state == "IDLE" } ?: return@runCatching
                    val ticketId = view.ticketId ?: return@runCatching
                    val agentId = view.agentId ?: return@runCatching
                    val done = store.transaction { store.tickets.get(ticketId)?.status == TicketStatus.DONE }
                    if (done && agentQueueIds(agentId).isNotEmpty()) {
                        host.sessions.kill(sessionId, force = true, by = "board", reason = DONE_QUEUED_DETAIL)
                    }
                }
            }
        } catch (_: RejectedExecutionException) {
            // closed
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
        sessionSubscription.close()
        waitTimer.shutdownNow()
        sessionEvents.shutdown()
        if (!sessionEvents.awaitTermination(CLOSE_DRAIN_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
            System.err.println("[infantry] board session events did not drain within ${CLOSE_DRAIN_TIMEOUT_MS}ms")
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
    private fun onSessionEvent(event: BoardSessionEvent) {
        val view = when (event) {
            is BoardSessionEvent.Status -> event.session
            is BoardSessionEvent.Exit -> event.session
        }
        // Read on the emitting thread: the session as it stood when the event arrived.
        val facts = view?.let { v ->
            val detail = host.sessions.detail(v.id)
            RunFacts(v, detail?.lastHeartbeat ?: 0, detail?.spawnedAt ?: 0, detail?.providerSessionId)
        }
        when {
            Thread.holdsLock(this) -> applySessionEvent(event, facts)
            host.sessions.holdsSessionsLock() -> try {
                sessionEvents.execute { applySessionEvent(event, facts) }
            } catch (_: RejectedExecutionException) {
                // The board is already closed ([close]). Only Status and Exit write anything -- they carry the
                // ticket outcome -- and the session manager cannot emit one once [shutdown] has removed every
                // run, so a late event here is process output, which applies nothing. Same shape as
                // Orchestrator.onSessionEvent: a no-op after close(), never an exception on a session thread.
            }
            else -> applySessionEvent(event, facts)
        }
    }

    /** The session as it stood when an event arrived, with the three fields only the host's detail carries. */
    private class RunFacts(val view: SessionView, val lastHeartbeat: Long, val spawnedAt: Long, val providerSessionId: String?)

    @Synchronized
    private fun applySessionEvent(event: BoardSessionEvent, facts: RunFacts?) {
        when (event) {
            is BoardSessionEvent.Status -> {
                val record = facts ?: return
                pendingEnds[record.view.id]?.let { pending ->
                    // AGENT-TASKS-2: only a turn that STARTED and then ended counts; the end itself runs off this monitor.
                    if (record.view.state == "BUSY") pending.sawBusy = true
                    else if (record.view.state == "IDLE" && pending.sawBusy) queueUnassignStep(record.view.id)
                }
                if (record.view.state == "IDLE") closeDoneWorkerWhenQueued(record.view.id)
                val ticketId = record.view.ticketId
                if (ticketId != null && record.view.runCount == 0) {
                    ensureRunRow(record)
                    if (record.view.state == BoardSessionStates.FAILED) {
                        finishTicketRun(record)
                        publishRunUpdate(ticketId)
                        drainProfileLane(record)
                    }
                }
            }
            is BoardSessionEvent.Exit -> {
                val record = facts ?: return
                val ticketId = record.view.ticketId ?: return
                // AGENT-TASKS-2: a turn that ran ends with an Exit (the session stays resumable), which is the signal a pending unassign waits for.
                pendingEnds[record.view.id]?.let { if (it.sawBusy) queueUnassignStep(record.view.id) }
                ensureRunRow(record)
                finishTicketRun(record)
                publishRunUpdate(ticketId)
                drainProfileLane(record)
            }
        }
    }

    /**
     * AGENT-TASKS-2: an in_progress ticket goes back to todo (top of the column), its branch, base, agent and worktree kept.
     * ONE write commit first, so the ticket is in todo at once; THEN its worker is ended OUTSIDE the board lock (lock order
     * is board, then sessions): an idle worker at once, a busy one when its turn ends, or after
     * `limits.agentTasks.unassignWaitSeconds`; [now] ends it without waiting. Not `@Synchronized` for that reason.
     */
    fun unassign(ticketId: String, now: Boolean = false, note: String? = null): TicketAnswer {
        val id = checkId(ticketId)
        val (answer, row) = unassignCommit(id)
        return endUnassigned(answer, row, now, note)
    }

    /** The worker half of an unassign, run after the commit and outside the board lock. */
    private fun endUnassigned(answer: TicketAnswer, row: TicketRow, now: Boolean, note: String?): TicketAnswer {
        val id = row.id
        val sessionId = row.sessionId
        val view = sessionId?.let(host.sessions::get)?.takeIf { it.live }
        val pending = PendingEnd(id, row.projectId, note?.trim()?.takeIf { it.isNotEmpty() }, sawBusy = false, promptFirst = false)
        if (sessionId == null || view == null) {
            writeHandoff(pending, sessionId?.let(host.sessions::get), row)
            sessionId?.let { sid -> runCatching { host.sessions.remove(sid) } } // an ended (not live) session is only removed
            return answer
        }
        unassigned += sessionId
        val busy = host.sessions.detail(sessionId)?.busy == true
        if (now) {
            endWorker(sessionId, pending, row)
            return answer
        }
        pending.sawBusy = busy
        pending.promptFirst = handoffMode() == HANDOFF_WORKER
        if (!busy && !pending.promptFirst) {
            endWorker(sessionId, pending, row)
            return answer
        }
        pendingEnds[sessionId] = pending
        if (!busy) {
            // worker mode, idle: ask for the handoff now; a refused prompt means there is nothing to wait for.
            pending.promptFirst = false
            if (!host.sessions.prompt(sessionId, HANDOFF_PROMPT)) {
                pendingEnds.remove(sessionId)?.let { endWorker(sessionId, it, row) }
                return answer
            }
        }
        val wait = unassignWaitSeconds().coerceAtLeast(0)
        try {
            waitTimer.schedule({
                try { sessionEvents.execute { pendingEnds.remove(sessionId)?.let { endWorker(sessionId, it, null) } } } catch (_: RejectedExecutionException) { }
            }, wait, TimeUnit.SECONDS)
        } catch (_: RejectedExecutionException) {
            // closed: nothing left to wait with
        }
        return answer
    }


    /** AGENT-TASKS-4: the refusals shared by assign and switch for putting todo ticket [id] on [agent]; returns the agent's id. */
    private fun requireAssignable(id: String, agent: BoardAgent, needsLane: Boolean): String {
        val agentId = agent.id ?: throw IllegalArgumentException("agent '${agent.name}' has no profile id")
        val row = store.transaction { load() }.require(id)
        require(row.status == TicketStatus.TODO) { "ticket $id is ${row.status}: only a todo ticket can be assigned" }
        require(agent.projectId == null || agent.projectId == row.projectId) {
            "agent ${agent.name} belongs to another project than ticket $id"
        }
        require(host.agents.roleKnown(agent.role)) { "agent ${agent.name} has role '${agent.role}', which cannot run tickets" }
        if (needsLane) {
            val occupied = occupiedAgents(id)
            val running = host.agents.list().filter { it.role == agent.role }.count { c -> c.id in occupied }
            require(running < laneCap(row.projectId, agent.role)) {
                "the lane cap for role '${agent.role}' is full ($running running): end a worker or raise the cap"
            }
        }
        return agentId
    }

    private fun liveSessionsOf(agentId: String): List<SessionView> = host.sessions.list().filter { it.live && it.agentId == agentId }

    /** The in_progress ticket [agentId] works on (pinned to one of its live sessions, or to its profile with no session), or null. */
    private fun currentTicketOf(agentId: String): String? {
        val sessionIds = liveSessionsOf(agentId).map { it.id }.toSet()
        return store.transaction {
            load().rows.values.firstOrNull {
                it.status == TicketStatus.IN_PROGRESS && (it.sessionId in sessionIds || (it.agentId == agentId && it.sessionId == null))
            }?.id
        }
    }

    /**
     * AGENT-TASKS-4: give todo ticket [ticketId] to an existing agent ([agentRef], a profile id or unique name); sets its agentId.
     * [AssignMode.START]: no live session -> hand out now; an IDLE session on no in-progress ticket is ended and the ticket starts on
     * its exit (it heads the agent's queue and the exit drain starts it, on [sessionEvents]); an agent on an in-progress ticket, or
     * BUSY, is refused. [AssignMode.QUEUE] adds to the agent's queue at [at]. Not `@Synchronized`: no start under the manager lock.
     */
    fun assign(ticketId: String, agentRef: String, mode: AssignMode = AssignMode.START, at: Int? = null): TicketAnswer {
        val id = checkId(ticketId)
        val agent = resolveQueueAgent(agentRef)
        val live = liveSessionsOf(agent.id ?: "")
        val agentId = requireAssignable(id, agent, needsLane = mode == AssignMode.START && live.isEmpty())
        if (mode == AssignMode.START && live.isNotEmpty()) {
            val onTicket = currentTicketOf(agentId)
            require(onTicket == null) { "agent ${agent.name} is on $onTicket; use switch, or assign --queue" }
            require(live.none { it.state == "BUSY" }) { "agent ${agent.name} is busy with no ticket; use switch, or assign --queue" }
        }
        setAgent(id, agentId)
        if (mode == AssignMode.QUEUE) {
            queue(agentId, QUEUE_ADD, id, at)
            return TicketAnswer(get(id), pushed = false)
        }
        if (live.isEmpty()) return handOut(id, HandOutOrigin.MOVE)
        queue(agentId, QUEUE_ADD, id, 1)
        endIdleAndDrain(live, id)
        return TicketAnswer(
            get(id).copy(start = TicketStart(ok = true, reason = "deferred", detail = "worker ends after its turn; $id starts on exit")),
            pushed = false,
        )
    }

    /** Ends each idle [sessions] member on [sessionEvents]; its Exit drains the agent queue. A ticketless session drains here. */
    private fun endIdleAndDrain(sessions: List<SessionView>, ticketId: String) {
        try {
            sessionEvents.execute {
                for (view in sessions) {
                    runCatching { host.sessions.kill(view.id, force = true, by = "board", reason = REPLACED_DETAIL) }
                    runCatching { host.sessions.remove(view.id) }
                    if (view.ticketId == null && queueDrainEnabled) runCatching { handOut(ticketId, HandOutOrigin.QUEUE_DRAIN) }
                }
            }
        } catch (_: RejectedExecutionException) {
            // closed
        }
    }

    private fun setAgent(id: String, agentId: String) {
        val outcome = commit { s -> s.rows[id] = s.rows.getValue(id).copy(agentId = agentId) }
        if (outcome.changed.isNotEmpty()) {
            val byId = outcome.board.associateBy { it.id }
            push(BoardEvent.Changed(outcome.changed.map { byId.getValue(it) }))
            announce(outcome)
        }
    }

    /**
     * AGENT-TASKS-4: move [agentRef] from its in-progress ticket to [toTicketId]. ONE commit: the current ticket goes to todo (top,
     * handoff) and [toTicketId] heads the agent's queue; then the worker ends like unassign ([now] honoured) and its exit starts the
     * new ticket, so the agent never has two in-progress tickets. With no current ticket this is [assign] (start).
     */
    fun switch(agentRef: String, toTicketId: String, now: Boolean = false): TicketAnswer {
        val to = checkId(toTicketId)
        val agent = resolveQueueAgent(agentRef)
        val agentId = agent.id ?: throw IllegalArgumentException("agent '${agent.name}' has no profile id")
        val current = currentTicketOf(agentId) ?: return assign(to, agentId, AssignMode.START)
        require(current != to) { "agent ${agent.name} is already on $to" }
        requireAssignable(to, agent, needsLane = false)
        val queued = agentQueueIds(agentId)
        val rest = queued.filter { it != to }
        require(to in queued || rest.size < queueMax()) { "agent ${agent.name}'s queue is full (limits.agentTasks.queueMax = ${queueMax()})" }
        val next = listOf(to) + rest
        val (answer, row) = unassignCommit(current) { s ->
            s.rows.values.filter { it.queuedAgent == agentId && it.id !in next }.forEach { s.rows[it.id] = it.copy(queuedAgent = null, queueRank = null) }
            next.forEachIndexed { i, tid -> s.rows[tid] = s.rows.getValue(tid).copy(agentId = agentId, queuedAgent = agentId, queueRank = i + 1) }
        }
        endUnassigned(answer, row, now, null)
        return TicketAnswer(
            get(to).copy(start = TicketStart(ok = true, reason = "deferred", detail = "worker ends after its turn; $to starts on exit")),
            pushed = false,
        )
    }

    @Synchronized
    private fun unassignCommit(id: String, also: ((Snapshot) -> Unit)? = null): Pair<TicketAnswer, TicketRow> {
        val row = store.transaction { load() }.require(id)
        require(row.status == TicketStatus.IN_PROGRESS) {
            "ticket $id is ${row.status}, not in_progress: only an in_progress ticket can be unassigned"
        }
        val outcome = commit { s ->
            s.place(id, TicketStatus.TODO, 0)
            s.rows[id] = s.rows.getValue(id).copy(sessionId = null)
            s.named += id
            also?.invoke(s)
        }
        return answer(id, outcome) to row
    }

    /** AGENT-TASKS-2: a pending unassign hit its next step (a worker turn ended); runs on [sessionEvents], never under a lock. */
    private fun queueUnassignStep(sessionId: String) {
        try {
            sessionEvents.execute {
                val pending = pendingEnds[sessionId] ?: return@execute
                if (pending.promptFirst) {
                    pending.promptFirst = false
                    pending.sawBusy = false
                    if (host.sessions.prompt(sessionId, HANDOFF_PROMPT)) return@execute
                }
                pendingEnds.remove(sessionId)?.let { endWorker(sessionId, it, null) }
            }
        } catch (_: RejectedExecutionException) {
            // closed
        }
    }

    /** Write the handoff, then kill and remove the worker; every failure is swallowed (the ticket is already in todo). */
    private fun endWorker(sessionId: String, pending: PendingEnd, row: TicketRow?) {
        runCatching { writeHandoff(pending, host.sessions.get(sessionId), row) }
        runCatching { host.sessions.kill(sessionId, force = true, by = "board", reason = UNASSIGNED_DETAIL) }
        runCatching { host.sessions.remove(sessionId) }
    }

    private fun writeHandoff(pending: PendingEnd, view: SessionView?, row: TicketRow? = null) {
        val mode = handoffMode()
        if (mode == HANDOFF_OFF) return
        val sink = handoffSink ?: return
        val current = row ?: store.transaction { store.tickets.get(pending.ticketId) } ?: return
        val body = handoffBody(current, view, pending.note)
        runCatching { sink(pending.ticketId, pending.projectId, body) }
    }

    /** AGENT-TASKS-2: branch, tip, commits ahead with subjects, dirty files, the worker's last message, then the caller's note. */
    private fun handoffBody(row: TicketRow, view: SessionView?, note: String?): String {
        val branch = row.branch
        val base = row.base
        val repoPath = (view?.repoRoot ?: cutRepo(row.projectId)?.path)
        val facts = if (branch != null && base != null && repoPath != null) {
            runCatching { host.git.handoffFacts(repoPath, branch, base, view?.worktreePath, handoffCommits().toInt().coerceAtLeast(0)) }.getOrNull()
        } else null
        val last = view?.id?.let { runCatching { host.sessions.lastAssistantText(it) }.getOrNull() }?.trim()?.takeIf { it.isNotEmpty() }
        return buildString {
            append("Handed back to todo.")
            if (branch != null) append("\nBranch: ").append(branch).append(base?.let { " (base $it)" } ?: "")
            if (facts != null) {
                append("\nTip: ").append(facts.tip.take(7)).append(", ").append(facts.ahead).append(" commit(s) ahead")
                facts.subjects.forEach { append("\n- ").append(it) }
                facts.dirtyFiles?.let { append("\nUncommitted files: ").append(it) }
            }
            if (last != null) {
                val max = handoffChars().toInt().coerceAtLeast(0)
                append("\nLast worker message:\n").append(if (last.length > max) last.take(max) + "..." else last)
            }
            if (note != null) append("\nNote: ").append(note)
        }
    }

    /** AGENT-TASKS-2: a todo ticket whose recorded branch is ahead of its base keeps work a second start continues. */
    private fun hasWork(row: TicketRow): HasWork? {
        val branch = row.branch ?: return null
        val base = row.base ?: return null
        if (row.status != TicketStatus.TODO) return null
        val repoPath = runCatching { cutRepo(row.projectId)?.path }.getOrNull() ?: return null
        val facts = runCatching { host.git.handoffFacts(repoPath, branch, base, null, 0) }.getOrNull() ?: return null
        return if (facts.ahead > 0) HasWork(branch, facts.tip, facts.ahead) else null
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
        val imagePaths = request.images?.let { host.images.normalize(projectId, it) } ?: existing?.images.orEmpty()
        // TICKET-WEIGHT: absent or null keeps the weight, 0 clears it, 1..5 sets it.
        val weight = when (request.weight) {
            null -> existing?.weight
            TicketWeight.CLEAR -> null
            else -> request.weight.also { require(it in TicketWeight.RANGE) { "weight must be ${TicketWeight.RANGE} (or ${TicketWeight.CLEAR} to clear), was $it" } }
        }
        // CONTEXT-LIMIT-1: absent or null keeps the limit, 0 clears it, a token count sets it (ContextLimits' range).
        val contextLimit = when (request.contextLimit) {
            null -> existing?.contextLimit
            com.superiorsoftllc.infantry.protocol.TicketContextLimit.CLEAR -> null
            else -> request.contextLimit.also { v -> com.superiorsoftllc.infantry.daemon.api.board.ContextLimits.problem(v)?.let { throw IllegalArgumentException(it) } }
        }
        // RUNNER-POOL-3: absent or null keeps the pin, "" / none clears it, local | any | a runner name sets it.
        val runner = request.runner?.trim()?.lowercase()?.let { v ->
            if (v.isEmpty() || v == com.superiorsoftllc.infantry.protocol.TicketRunner.CLEAR) null
            else v.also { com.superiorsoftllc.infantry.protocol.TicketRunner.problem(it)?.let { m -> throw IllegalArgumentException(m) } }
        } ?: if (request.runner == null) existing?.runner else null
        // EFFORT-1: absent or null keeps the effort, "" / off / default clears it, a level sets it; an unknown level refuses the save.
        val effort = if (request.effort == null) existing?.effort else {
            val parsed = com.superiorsoftllc.infantry.session.EffortLevels.parse(request.effort)
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
                        runner = runner,
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
                    runner = runner,
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
        host.images.release(projectId, dropped, store.transaction { store.tickets.all().map { it.toStored() } })
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
    fun import(
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

        // AUTOSTART-SAME-AGENT-1: the worker of an in-progress ticket that leaves the column, so its agent's queue drains.
        val leavingWorker = if (status != null && status != TicketStatus.IN_PROGRESS) {
            store.transaction { store.tickets.get(id) }?.takeIf { it.status == TicketStatus.IN_PROGRESS }?.sessionId
        } else null
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
        leavingWorker?.let { drainAfterLeave(id, it) }
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

        if (!host.sessions.available()) return refusedStart(initialTicket, "ticket session manager is unavailable")
        if (!profilesAvailable) return refusedStart(initialTicket, "agent profiles are unavailable")
        // ROUTING-1: the project's first matching rule (none when the ticket pins its own provider / model or the project
        // has no rules). Its setRole is the role of a ticket that has none; it never replaces the ticket's own.
        val rule = routingChoice(row)
        val assigned = (if (origin == HandOutOrigin.QUEUE_DRAIN) row.queuedAgent else null) ?: row.agentId ?: rule?.setRole?.let { "role:$it" } // AGENT-TASKS-3: a drained ticket runs on the queue's own agent
        val assignment = resolveAssignment(assigned, row.projectId, id)
        if (assignment.refusal != null) return refusedStart(initialTicket, assignment.refusal)
        // BUG-WT-1: with no repo configured and no explicit pin, a project folder that is a git checkout IS the
        // repo: the ticket gets its own branch and worktree there, never the checkout itself.
        // INSTALL-CWD-1: with project folders on (the installed app), the project's own directory is the folder, and a
        // project with none is refused here: never the daemon's cwd.
        val projectFolder: File? = if (repo == null) {
            if (host.git.projectFolders()) {
                try {
                    File(host.git.projectFolderRequire(row.projectId))
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
            val folder = repo ?: projectFolder ?: File(host.sessions.defaultWorkingDir() ?: return refusedStart(initialTicket, "ticket session manager is unavailable"))
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
        val bound = host.agents.executorFor(profile, row.provider, row.model, row.effort, profile.role)
        // TICKET-WEIGHT: a weighted ticket that pins nothing (and whose profile / role binding pins nothing) runs on the weight's executor.
        // ROUTING-1: in a project with rules the matched rule beats the profile / role binding and the weight mapping.
        val byWeight = if (rule == null && routingRules(row.projectId).isEmpty()) weightChoice(row, bound.provider, bound.model) else null
        val executor = when {
            rule != null -> host.agents.executorFor(profile, rule.provider, rule.model, row.effort ?: rule.effort, profile.role) // EFFORT-1: the ticket's own pin beats the rule's
            byWeight != null -> host.agents.executorFor(profile, byWeight.provider, byWeight.model, row.effort, profile.role)
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
                host.agents.validateExecutor(providerId, model, executor.effort)
            } catch (e: IllegalArgumentException) {
                val detail = "rule '${rule.name}': $providerId/$model refused: ${e.message ?: "provider or model refused"}; " +
                    "the ticket stays in todo (no fallback): fix the rule or pin the ticket"
                if (reportedRoutingRefusals.add("$id|$detail")) runCatching { routingRefused(id, row.projectId, detail) }
                return refusedStart(initialTicket, detail)
            }
        }
        // RUNNER-POOL-3: pin > rule > runners.default. With only `local` registered every start resolves to local;
        // a named runner that is unknown, down or full refuses the start (no silent fallback).
        // RUNNER-POOL-8: a restart elsewhere skips the lost runner's pin or rule (placement falls through to the next source) and
        // is refused when placement still lands on it.
        val avoided = avoidRunners[id]
        val placed = com.superiorsoftllc.infantry.settings.Placement.pick(
            row.runner?.takeIf { it != avoided }, ruleRunnerOf(row)?.takeIf { it != avoided }, runnersDefault(), runnerEntries(), row.projectId, providerId,
        )
        if (placed is com.superiorsoftllc.infantry.settings.PlacementResult.Refused) return refusedStart(initialTicket, placed.reason)
        if (avoided != null && (placed as com.superiorsoftllc.infantry.settings.PlacementResult.Placed).runner == avoided) {
            return refusedStart(initialTicket, "placement picks runner $avoided again, and it is lost: pin the ticket or a rule to another runner")
        }
        // RUNNER-POOL-6: a start placed on a remote runner cuts no branch here: the runner clones the project's git remote and
        // cuts (or reuses) the ticket branch itself; the board records the branch the runner answers.
        val remoteRunner = (placed as com.superiorsoftllc.infantry.settings.PlacementResult.Placed).runner
            .takeIf { it != com.superiorsoftllc.infantry.settings.Placement.LOCAL }
        if (remoteRunner != null && worktreeRepo == null) {
            return refusedStart(initialTicket, "runner $remoteRunner needs the project's git remote, and this project has no git repository")
        }
        val remoteBase = remoteRunner?.let { host.git.baseRef(row.projectId, row.epicId).ref }
        if (remoteRunner != null && row.branch == null && remoteBase.isNullOrBlank()) return refusedStart(initialTicket, noBaseReason(row.projectId))
        host.sessions.checkSpawn(providerId, model)?.let { return refusedStart(initialTicket, it) }
        // Story 9.7b: a drained queue is an UNATTENDED launch (startNew refuses it over a spent budget or an
        // exhausted provider); a human move or start-next is only warned, and still starts.
        val unattendedLaunch = origin == HandOutOrigin.QUEUE_DRAIN || origin == HandOutOrigin.TRIGGER || origin == HandOutOrigin.REPAIR
        val launchWarning = if (unattendedLaunch) null else
            host.sessions.launchGate(providerId, "human", id).warning
        // USAGE-PACE: only the owner's own move (no starter session) may go past the plan allowance, with a warning.
        val humanMove = origin == HandOutOrigin.MOVE && startedBy == null
        val pacingWarning = when (val verdict = pacing(providerId, model)) {
            is PacingVerdict.Over -> if (humanMove) verdict.reason else return refusedStart(initialTicket, verdict.reason)
            is PacingVerdict.Allow -> null
        }

        // BRANCH-PER-TASK 1/3: the ticket's own branch and worktree, cut or reused BEFORE the session exists,
        // so a refused base leaves nothing behind and a start never creates a second branch for one ticket.
        var cutNote: String? = null
        var readyBranch: BranchOutcome.Ready? = null
        val ticketWorktree: TicketWorktree? = if (requestedWorktree && remoteRunner == null) {
            val root = worktreeRepo ?: return refusedStart(initialTicket, "no repo configured")
            // BASE-1c: the ref AND the setting that chose it, so a fresh cut's note names an epic's base.
            val baseChoice = host.git.baseRef(row.projectId, row.epicId)
            // BUG-BASE-087: nothing configured is refused, not cut from whatever the main checkout has checked out.
            // A ticket that already holds its branch reuses it and needs no base.
            if (row.branch == null && baseChoice.ref.isBlank()) {
                if (host.git.resolveBase(root.path, "HEAD") == null) {
                    return refusedStart(initialTicket, "repository ${root.absolutePath} has no commits yet; make a first commit, then set a base branch")
                }
                return refusedStart(initialTicket, noBaseReason(row.projectId))
            }
            val request = BranchRequest(
                ticketId = id,
                title = row.title,
                repo = root.path,
                baseBranch = baseChoice.ref,
                recordedBranch = row.branch,
                recordedBase = row.base,
            )
            when (val outcome = host.git.ensureBranch(request)) {
                is BranchOutcome.Refused -> return refusedStart(initialTicket, outcome.reason)
                is BranchOutcome.Ready -> TicketWorktree(outcome.branch, outcome.base, outcome.worktreePath, outcome.repoRoot).also {
                    readyBranch = outcome
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
        val instance = host.agents.instanceFor(profile.id, profile.role)?.let { host.agents.withSkills(it, id) }
        val input = briefInput(
            row, initial, profile.role, profile.id, instance,
            startedBy?.let { starter -> reportTo(starter) },
        )
        val composed = context.compose(input)
        var remoteBranch: TicketWorktree? = null
        val started = when (val result = host.sessions.start(
            SessionStart(
                prompt = composed.prompt,
                workingDir = if (remoteRunner != null) worktreeRepo?.path else if (requestedWorktree) null else (repo ?: projectFolder)?.path,
                worktree = readyBranch,
                contextSources = composed.sources.map { ContextSourceInfo(it.source, it.chars) },
                branchHint = null,
                providerId = providerId,
                model = model,
                effort = executor.effort ?: byWeightContext?.effort,
                agentId = profile.id,
                role = profile.role,
                ticketId = id,
                projectId = row.projectId,
                contextLimit = row.contextLimit ?: byWeightContext?.contextLimit, // CONTEXT-LIMIT-1; TOKEN-7 weight default
                instance = instance,
                origin = if (unattendedLaunch) "unattended" else "human",
                runner = remoteRunner,
                title = remoteRunner?.let { row.title },
                baseBranch = remoteBase,
                recordedBranch = remoteRunner?.let { row.branch },
                recordedBase = remoteRunner?.let { row.base },
            ),
        )) {
            is SessionStartResult.Started -> {
                remoteBranch = result.branch?.let { b -> TicketWorktree(b, result.base ?: row.base.orEmpty(), "", "") }
                result.session
            }
            is SessionStartResult.Refused -> return refusedStart(initialTicket, result.reason)
        }

        // BUG-TriggerRunIds: spawn first, commit second -- the commit records the session's id, which only exists
        // once startNew returns, and committing in_progress first would leave a ticket to roll back on every
        // refused start. So the commit failing (SQLITE_BUSY_SNAPSHOT, a full disk) is compensated here: the
        // session it would have recorded is killed, never left running against a ticket still filed as todo.
        val outcome = try {
            commitStarted(id, started.id, ticketWorktree ?: remoteBranch, startedBy, keepQueue = origin == HandOutOrigin.QUEUE_DRAIN)
        } catch (e: Throwable) {
            runCatching { host.sessions.kill(started.id, force = true, by = "ticket start rollback", reason = "the start could not be committed") }
                .onFailure { e.addSuppressed(it) }
            throw e
        }
        // BUG-REGRESS-1: what the branch was cut from, then any launch warning (TicketStarter reads them apart).
        val weightNote = byWeight?.let { "weight ${row.weight}: ${providerId ?: "default provider"}/${model ?: "default model"} from ${WeightExecutors.SETTING}" }
        val ruleNote = rule?.let { "rule '${it.name}': $providerId/$model" }
        // CONTEXT-LIMIT-1: said on the start, so a limit the harness cannot apply is never silent.
        val limitNote = started.contextLimit?.let { "context limit ${com.superiorsoftllc.infantry.daemon.api.board.ContextLimits.label(it)}: ${started.contextLimitSupport}" +
            (if (started.contextLimitSupport == "unsupported") " (recorded, not applied by ${started.provider})" else "") }
        // EFFORT-1: the same for a reasoning effort the harness cannot apply.
        val effortNote = started.effort?.let { "effort $it: ${started.effortSupport}" +
            (if (started.effortSupport == "unsupported") " (recorded, not applied by ${started.provider})" else "") }
        val runnerNote = remoteRunner?.let { "on runner $it" + (remoteBranch?.let { b -> " (branch ${b.branch})" } ?: "") }
        val detail = listOfNotNull(runnerNote, cutNote, ruleNote, weightNote, limitNote, effortNote, launchWarning?.let { TicketStart.LAUNCH_WARNING + it }, pacingWarning).joinToString("; ").ifEmpty { null }
        return startAnswer(id, TicketStart(ok = true, reason = "started", detail = detail), outcome)
    }

    /**
     * DP-5h-3a: whether [move] would hand the ticket to a worker, as [move] decides it (GAP-006b restart included); what a
     * session-originated start is charged on. A ticket that does not exist answers false.
     */
    fun runsWorker(move: TicketMove): Boolean {
        if (move.status != TicketStatus.IN_PROGRESS) return false
        val current = runCatching { get(move.id) }.getOrNull() ?: return false
        if (current.status != TicketStatus.IN_PROGRESS) return true
        return move.position == null && host.sessions.available() &&
            BoardWorkerHealth.problem(current.sessionId?.let(host.sessions::detail), current.sessionId) != null
    }

    /** GAP-006b: why in-progress [row]'s worker cannot carry it ([BoardWorkerHealth]); null when it can, or with no session manager. */
    private fun workerProblem(row: TicketRow): String? {
        if (!host.sessions.available()) return null
        return BoardWorkerHealth.problem(row.sessionId?.let(host.sessions::detail), row.sessionId)
    }

    private fun answerTicket(id: String): Ticket = store.transaction { load() }.let { views(it.rows, it.links).getValue(id) }

    /**
     * GAP-006b: start in-progress ticket [id] again because its worker cannot carry it ([problem]). The old worker is
     * retired first -- removed outright when it never opened a provider session (nothing in it to keep or resume), so
     * the ticket never ends up with two instances -- then the ticket is handed out exactly as a fresh start, reusing
     * its branch. The answer's detail starts with [TicketStart.RESTARTED] and the problem.
     */
    private fun restart(id: String, current: TicketRow, problem: String, origin: HandOutOrigin, startedBy: String?): TicketAnswer {
        if (!host.sessions.available()) return refusedStart(answerTicket(id), "ticket session manager is unavailable")
        current.sessionId?.let(host.sessions::detail)?.let { old ->
            runCatching {
                if (old.view.live) host.sessions.kill(old.view.id, force = true, by = "ticket restart", reason = problem)
                if (old.providerSessionId == null) host.sessions.remove(old.view.id)?.let { throw IllegalArgumentException(it) }
            }.onFailure { System.err.println("[infantry] ticket $id: retiring worker ${old.view.id} failed: ${it.message}") }
        }
        val answer = handOut(id, origin, startedBy)
        val start = answer.ticket.start ?: return answer
        if (!start.ok) return answer
        val detail = TicketStart.RESTARTED + problem + (start.detail?.let { "; $it" } ?: "")
        return answer.copy(ticket = answer.ticket.copy(start = start.copy(detail = detail)))
    }

    /** RUNNER-POOL-8: ticket id -> the lost runner its restart must not be placed on (set only while [restartElsewhere] runs). */
    private val avoidRunners = java.util.concurrent.ConcurrentHashMap<String, String>()

    /**
     * RUNNER-POOL-8 (`runners.onLost = restart-elsewhere`, docs/RUNNER-POOL.md 3.1): start in-progress ticket [id] again because
     * runner [lostRunner] went silent. Placement runs as for any start, minus the lost runner's pin or rule; the ticket keeps its
     * branch, so the new worker starts from what was last pushed. The old worker is not killed (its runner cannot be reached).
     * Refused (the answer's start says why) for a ticket not in progress.
     */
    @Synchronized
    fun restartElsewhere(id: String, lostRunner: String, problem: String): TicketAnswer {
        val current = store.transaction { load() }.require(checkId(id))
        if (current.status != TicketStatus.IN_PROGRESS) return refusedStart(answerTicket(id), "ticket is ${current.status}, not in progress")
        avoidRunners[current.id] = lostRunner
        try {
            return restart(current.id, current, problem, HandOutOrigin.REPAIR, null)
        } finally {
            avoidRunners.remove(current.id)
        }
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
        if (!host.sessions.available()) return emptyList()
        if (graceMs == Long.MAX_VALUE) return emptyList()
        val now = clock()
        val restarted = mutableListOf<String>()
        val inProgress = store.transaction { store.tickets.all() }.filter { it.status == TicketStatus.IN_PROGRESS }
        for (row in inProgress) {
            val worker = row.sessionId?.let(host.sessions::detail)
            fun surface(detail: String) {
                if (surfacedWorkers.add("${row.id}|${row.sessionId}|${detail.take(40)}")) runCatching { flag(row.id, row.sessionId, detail) }
            }
            if (worker == null) {
                if (now - row.updatedAt >= graceMs) {
                    surface("Ticket is in progress but ${BoardWorkerHealth.problem(null, row.sessionId)}: no session - start it.")
                }
                continue
            }
            if (BoardWorkerHealth.slowToBrief(worker, now, graceMs)) {
                surface("Worker ${worker.view.id} is busy but has opened no provider session for ${(now - worker.lastHeartbeat) / 60_000} min: its brief may never have landed.")
                continue
            }
            val problem = BoardWorkerHealth.problem(worker)
            if (problem == null) {
                // GAP 855db590: a briefed worker whose clean run did no work is resumable, so it is flagged, never restarted.
                if (!worker.busy && now - worker.lastHeartbeat >= graceMs) {
                    val run = store.transaction { store.ticketRuns.byTicket(row.id) }.lastOrNull { it.sessionId == worker.view.id }
                    run?.detail?.takeIf { it.startsWith(NO_WORK_DETAIL) }?.let {
                        surface("Ticket is in progress but its worker ${worker.view.id} exited cleanly having done no work (${it.removePrefix(NO_WORK_DETAIL)}): resume or start it again.")
                    }
                    // AUTOSTART-SAME-AGENT-1: the run's no-work detail is only written under tickets.exitBehavior=done, and a
                    // harness that names its provider session at launch (claude interactive's --session-id) never reads as
                    // unbriefed. So a worker at rest that has produced nothing at all ("out never") is flagged here directly.
                    if (run?.detail?.startsWith(NO_WORK_DETAIL) != true && worker.view.runCount > 0 &&
                        runCatching { host.sessions.workEvents(worker.view.id) }.getOrDefault(-1) == 0
                    ) {
                        surface("Ticket is in progress but its worker ${worker.view.id} is at rest and has produced no output at all " +
                            "(${worker.view.runCount} run(s)): its brief may never have landed. Resume or start it again.")
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
        instance: com.superiorsoftllc.infantry.daemon.api.board.BoardRoleInstance?,
        reportTo: ReportTo?,
    ) = ContextInput(
        instructions = instance?.instructions,
        title = row.title,
        // AGENT-TASKS-2: only the LATEST handoff comment rides along, so a ticket handed over many times stays one note long.
        description = row.description + (latestHandoff?.let { runCatching { it(row.id) }.getOrNull() }?.takeIf { it.isNotBlank() }
            ?.let { "\n\n## Handoff from the previous worker\n$it" } ?: ""),
        links = linkLines(row.id, snapshot),
        includeLinkedOverride = row.includeLinkedTasksContext,
        ticketId = row.id,
        projectId = row.projectId,
        role = role,
        agentId = agentId,
        traits = instance?.toSnapshots().orEmpty(),
        skillIds = instance?.skillIds,
        includeSkillsOverride = row.includeSkillsContext,
        reportTo = reportTo,
        // IMG-3: each attached image's absolute path, listed in the brief so the worker can read it.
        images = row.images.mapNotNull { host.images.absolute(row.projectId, it) },
    )

    /**
     * TICKET-HANDOVER: the start brief of the ticket session [sessionId] works on, composed as a fresh start of that ticket composes
     * it ([handOut]): the same composer, the session's own role, agent and frozen instance, and the ticket's starter as
     * who to report to. Re-read NOW, so an edit to the ticket since its start lands. Null when the session has no ticket
     * or the ticket is gone. Takes no board monitor (the handover calls it from its own thread).
     */
    fun brief(sessionId: String): String? {
        val session = host.sessions.get(sessionId) ?: return null
        val ticketId = session.ticketId?.takeIf { it.isNotBlank() } ?: return null
        val snapshot = store.transaction { load() }
        val row = snapshot.rows[ticketId] ?: return null
        val reportTo = row.startedBy?.let { starter -> reportTo(starter).takeIf { profilesAvailable } }
        return context.compose(briefInput(row, snapshot, session.role, session.agentId, host.sessions.instance(session.id), reportTo)).prompt
    }

    /**
     * BUG-MAIL-2: who the worker reports to -- the session that started the ticket, named after its agent profile
     * (else its role, else its id). Null when [starter] names no session, so a stale id never reaches a preamble.
     */
    private fun reportTo(starter: String): ReportTo? {
        val session = host.sessions.get(starter) ?: return null
        val name = session.agentId?.let { host.agents.get(it)?.name }?.takeIf { it.isNotBlank() }
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

    private fun commitStarted(id: String, sessionId: String, ticketWorktree: TicketWorktree?, startedBy: String?, keepQueue: Boolean = false): Outcome =
        commit { snapshot ->
            val queued = snapshot.require(id)
            snapshot.place(id, TicketStatus.IN_PROGRESS, null)
            val current = snapshot.rows.getValue(id)
            // AGENT-TASKS-3: any start pulls the ticket out of its queue (place); a queue drain also takes the queue's agent as the ticket's.
            snapshot.rows[id] = current.copy(
                agentId = if (keepQueue && queued.queuedAgent != null) queued.queuedAgent else current.agentId,
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
        if (host.git.projectFolders()) return host.git.projectFolderFind(projectId)?.let { gitRootOf(File(it)) }
        // NOGIT-1: the project's own directory decides; the daemon's checkout is only the fallback for a project with none.
        // TODO: gitRootOf walks UP, so a non-git folder nested under any parent repo still counts as that repo's checkout.
        projectDir(projectId)?.let { return gitRootOf(it) }
        return host.sessions.defaultWorkingDir()?.let { gitRootOf(File(it)) }
    }

    private fun projectDir(projectId: String?): File? =
        host.git.projectDirectory(projectId)?.takeIf { it.isNotBlank() }?.let { File(it).absoluteFile }?.takeIf { it.isDirectory }

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
        val set = projectId?.let { "infantry project base $it <branch>" } ?: "infantry project base <projectId> <branch>"
        return "no base branch configured for ${projectId?.let { "project $it" } ?: "this ticket"}; " +
            "pick its \"Base branch for new tasks\" in the app's Git tab, or set one with `$set`, an epic base (`infantry epic base <epicId> <branch>`) or the daemon's ${TicketBaseRef.ENV} (a headless daemon also needs INFANTRY_REPO=<git repo>)"
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

    /**
     * AUTOSTART-SAME-AGENT-1: the agents that cannot take a ticket now: those with a turn in flight, and those whose session
     * works ANOTHER in-progress ticket (other than [exceptTicket]) even while it rests between turns (waiting on mail or a
     * background job). Counting only turns in flight let auto-start hand three tickets to one idle-between-turns agent; the
     * extra sessions could never run beside it (one agent works one ticket in one session) and sat unbriefed for hours.
     * A FAILED or killed worker holds nothing: its run did not end by itself, and the worker sweep surfaces its ticket.
     */
    private fun occupiedAgents(exceptTicket: String? = null): Set<String> {
        val sessions = host.sessions.list()
        val workers = store.transaction { store.tickets.all() }
            .filter { it.status == TicketStatus.IN_PROGRESS && it.id != exceptTicket }
            .mapNotNull { it.sessionId }
            .toSet()
        return sessions.filter { view ->
            view.state in ACTIVE_STATE_NAMES ||
                (view.id in workers && view.state != BoardSessionStates.FAILED && view.killOutcome == null)
        }.mapNotNull { it.agentId }.toSet()
    }

    private fun resolveAssignment(
        assigned: String?,
        projectId: String? = null,
        /** AUTOSTART-SAME-AGENT-1: the ticket being started; null for a ticketless start (a trigger's prompt). */
        ticketId: String? = null,
    ): AssignmentResolution {
        assigned ?: return AssignmentResolution(
            refusal = "ticket has no role; set one with `infantry ticket update <id> --role <role id>` " +
                "or infantry_ticket_update's role",
        )
        // A ticketless start keeps the old rule (only a turn in flight occupies an agent); a ticket start also counts the
        // workers of the other in-progress tickets.
        val occupied = if (ticketId != null) occupiedAgents(ticketId) else
            host.sessions.list().filter { it.state in ACTIVE_STATE_NAMES }.mapNotNull { it.agentId }.toSet()
        if (assigned.startsWith("role:")) {
            val role = assigned.removePrefix("role:")
            val matching = host.agents.list().filter { it.role == role }.ifEmpty {
                firstRunProfile(role, projectId)?.let(::listOf).orEmpty()
            }
            if (matching.isEmpty()) return AssignmentResolution(refusal = "no agent profile has role '$role'")
            val profile = matching.firstOrNull { candidate -> candidate.id !in occupied }
                ?: extraAgent(matching, occupied, role, projectId)
                ?: return AssignmentResolution(queueLane = assigned, busy = true)
            return AssignmentResolution(profile = profile, queueLane = assigned)
        }
        val profile = host.agents.get(assigned)
            ?: return AssignmentResolution(refusal = "no agent profile '$assigned'")
        return if (profile.id in occupied) {
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
        matching: List<BoardAgent>,
        occupied: Set<String>,
        role: String,
        projectId: String?,
    ): BoardAgent? {
        val running = matching.count { candidate -> candidate.id in occupied }
        if (running >= laneCap(projectId, role)) return null
        val template = matching.first()
        val taken = host.agents.list().map { it.name }.toSet()
        val name = generateSequence(2) { it + 1 }.map { "${template.name} $it" }.first { it !in taken }
        return try {
            host.agents.save(template.copy(id = null, name = name))
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
    private fun firstRunProfile(role: String, projectId: String?): BoardAgent? {
        if (!host.agents.roleKnown(role)) return null
        val name = role.split('-').joinToString(" ") { part -> part.replaceFirstChar { it.uppercase() } }
        return try {
            host.agents.save(BoardAgent(name = name, role = role, projectId = projectId))
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

    private fun ensureRunRow(record: RunFacts) {
        val ticketId = record.view.ticketId ?: return
        if (store.tickets.get(ticketId) == null) return
        if (store.ticketRuns.byTicket(ticketId).none { it.sessionId == record.view.id }) {
            store.ticketRuns.insert(
                StoredRun(ticketId = ticketId, sessionId = record.view.id, startedAt = record.spawnedAt),
            )
        }
    }

    private fun finishTicketRun(record: RunFacts) {
        val ticketId = record.view.ticketId ?: return
        val outcome = BoardRunOutcomes.of(record.view.state, record.view.killOutcome)
        // Owner decision 2026-10-22: a clean exit leaves the ticket where it is (`tickets.exitBehavior` = leave, the
        // default) so the PM or another agent decides; `done` restores the old BUG-E2E-2 auto-done. Only the ticket's
        // CURRENT run decides (an older session ending late does not), and only a SUCCEEDED one: a failed, killed or
        // orphaned run stays in_progress with lastRun showing why.
        val eligible = exitBehavior() == "done" && outcome == FinishedPayload.Outcome.SUCCEEDED &&
            store.tickets.get(ticketId)?.let { it.status == TicketStatus.IN_PROGRESS && it.sessionId == record.view.id } == true
        // GAP 855db590: exit 0 is not proof of work. A run that never opened a provider session, or produced fewer
        // assistant outputs / tool calls than the setting asks, leaves the ticket in_progress; the worker sweep restarts
        // an unbriefed one and flags the rest.
        val noWork = if (eligible) noWork(record) else null
        val autoDone = eligible && noWork == null
        store.ticketRuns.finish(
            ticketId = ticketId,
            sessionId = record.view.id,
            endedAt = record.lastHeartbeat,
            outcome = outcome.name,
            detail = when {
                record.view.id in unassigned -> UNASSIGNED_DETAIL
                autoDone -> AUTO_DONE_DETAIL
                noWork != null -> NO_WORK_DETAIL + noWork
                else -> record.view.lastError ?: ""
            },
        )
        if (!record.view.live) unassigned.remove(record.view.id)
        if (autoDone) {
            // The ticket frame itself goes out from publishRunUpdate, with this run as lastRun; only the transition
            // is announced here, so a ship trigger fires exactly as for a move into done by hand.
            announce(commit { s -> s.place(ticketId, TicketStatus.DONE, null) })
        }
    }

    /** GAP 855db590: why [record]'s clean run does not count as work done on its ticket; null when it does. */
    private fun noWork(record: RunFacts): String? {
        val min = autoDoneMinOutputs()
        if (min <= 0) return null
        if (record.providerSessionId == null) return "the worker never opened a provider session, so it never received its brief"
        val outputs = if (!sessionsOn) return null else host.sessions.workEvents(record.view.id)
        if (outputs >= min) return null
        return "the worker produced $outputs assistant output(s) or tool call(s), fewer than the $min that " +
            "'limits.tickets.autoDoneMinOutputs' requires"
    }

    private fun publishRunUpdate(ticketId: String) {
        if (store.tickets.get(ticketId) != null) push(BoardEvent.Changed(listOf(get(ticketId))))
    }

    private fun drainProfileLane(record: RunFacts) = drainProfileLane(record.view.agentId, record.view.role)

    /**
     * AUTOSTART-SAME-AGENT-1: in-progress ticket [ticketId] just left the column while its worker [sessionId] rests between
     * turns. That worker held its agent ([occupiedAgents]), so a ticket queued on the agent or its role waited for it; no
     * Exit follows (the worker is already at rest), so its lane is drained here, off the move's own answer.
     */
    private fun drainAfterLeave(ticketId: String, sessionId: String) {
        if (!queueDrainEnabled || !sessionsOn) return
        try {
            sessionEvents.execute {
                runCatching {
                    synchronized(this@TicketBoard) {
                        val view = host.sessions.get(sessionId) ?: return@runCatching
                        if (view.state in ACTIVE_STATE_NAMES) return@runCatching // its turn's Exit drains the lane
                        if (store.transaction { store.tickets.get(ticketId) }?.status == TicketStatus.IN_PROGRESS) return@runCatching
                        drainProfileLane(view.agentId, view.role)
                    }
                }.onFailure { System.err.println("[infantry] ticket $ticketId: draining its agent's lane failed: ${it.message}") }
            }
        } catch (_: RejectedExecutionException) {
            // closed
        }
    }

    private fun drainProfileLane(agentId: String?, role: String?) {
        if (!queueDrainEnabled) return
        val profileId = agentId ?: return
        val candidates = store.transaction {
            val snapshot = load()
            boardOrder(snapshot.rows.values).filter { ticket ->
                ticket.status == TicketStatus.TODO && snapshot.holding(ticket.id).isEmpty() &&
                    (ticket.queuedFor == profileId || (role != null && ticket.queuedFor == "role:$role"))
            }.map { it.id }
        }
        // AGENT-TASKS-3: the agent's own queue goes first (lowest rank); a refusal leaves it untouched and says why once.
        val own = agentQueueIds(profileId).firstOrNull()
        val drained = own?.let { id ->
            val answer = runCatching { handOut(id, HandOutOrigin.QUEUE_DRAIN) }.getOrNull()
            val start = answer?.ticket?.start
            if (start?.ok != true && start != null) noteQueueRefusal(id, answer.ticket.projectId, "${start.reason}${start.detail?.let { ": $it" } ?: ""}")
            start?.ok == true
        } == true
        if (!drained) candidates.firstOrNull()?.let { handOut(it, HandOutOrigin.QUEUE_DRAIN) }
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
                        ticket.queuedFor?.startsWith("role:") == true && snapshot.holding(ticket.id).isEmpty() && ticket.queuedAgent == null
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
        val roles = host.agents.list().filter { it.projectId == projectId }.map { it.role }.distinct()
        roles.forEach { pruneIdleClones(it) }
    }

    /**
     * LANE-CAPS: a clone ("<name> 2", added by [extraAgent]) that is idle while the role has more agents than the cap of its
     * project allows is removed. The role's first agent is never removed, and nothing running is touched.
     */
    private fun pruneIdleClones(role: String) {
        if (!profilesAvailable || !sessionsOn) return
        val matching = host.agents.list().filter { it.role == role }
        val original = matching.firstOrNull() ?: return
        val cap = maxOf(1, laneCap(original.projectId, role))
        // AUTOSTART-SAME-AGENT-1: a clone resting between turns of an in-progress ticket is running, never pruned.
        val occupied = occupiedAgents()
        val clone = Regex("^" + Regex.escape(original.name) + " \\d+$")
        var excess = matching.size - cap
        for (candidate in matching.drop(1).reversed()) {
            if (excess <= 0) break
            val id = candidate.id ?: continue
            if (!clone.matches(candidate.name) || id in occupied) continue
            runCatching { host.agents.delete(id) }.onSuccess { excess-- }
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
     * A WRITE-FIRST transaction (`InfantryStore.writeTransaction`, BUG-BoardCommitImmediate): it reads the whole
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
        // AGENT-TASKS-3: a ticket that just went done may leave an idle worker behind while its agent has a queue.
        outcome.changed.map { byId.getValue(it) }.filter { it.status == TicketStatus.DONE }.forEach { t -> t.sessionId?.let(::closeDoneWorkerWhenQueued) }
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
        runCatching { eventSink(event) }.onFailure { System.err.println("[infantry] ticket push failed: $it") }
    }

    private fun announce(outcome: Outcome) {
        runCatching { eventAppender().deliver() }.onFailure { System.err.println("[infantry] ticket event delivery failed: $it") }
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

        /** AGENT-TASKS-2: the run detail of a worker the board ended because its ticket was unassigned. */
        const val UNASSIGNED_DETAIL = "unassigned"
        const val REPLACED_DETAIL = "replaced by an assigned ticket"

        /** AGENT-TASKS-3: the run detail of an idle worker the board ended because its ticket is done and its agent has a queue. */
        const val DONE_QUEUED_DETAIL = "done, agent has a queued ticket"

        /** AGENT-TASKS-3: the `queue` actions. */
        const val QUEUE_LIST = "list"
        const val QUEUE_ADD = "add"
        const val QUEUE_REMOVE = "remove"
        const val QUEUE_MOVE = "move"
        const val QUEUE_CLEAR = "clear"
        val QUEUE_ACTIONS = listOf(QUEUE_LIST, QUEUE_ADD, QUEUE_REMOVE, QUEUE_MOVE, QUEUE_CLEAR)

        /** AGENT-TASKS-2: `tickets.agentTasks.handoffNote` values. */
        const val HANDOFF_AUTO = "auto"
        const val HANDOFF_WORKER = "worker"
        const val HANDOFF_OFF = "off"

        /** AGENT-TASKS-2: what a `worker`-mode unassign asks the idle worker to do in its one last turn. */
        const val HANDOFF_PROMPT =
            "This ticket is being unassigned from you. Write a short handoff for the next worker: what is done, what is " +
                "left, anything surprising. Send it with the report script (status info), commit nothing new, then stop."

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
        fun gitRootOf(dir: File): File? =
            generateSequence(dir.absoluteFile) { it.parentFile }.firstOrNull { File(it, ".git").exists() }
        private val ACTIVE_STATE_NAMES = BoardSessionStates.ACTIVE

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
    /** BOARD-STALL-1: the agents read once for one [views] pass (callers hold the board lock), not once per ticket. */
    private var agentsSnapshot: List<BoardAgent>? = null

    private fun views(rows: Map<String, TicketRow>, links: List<TicketLinkRow>): Map<String, Ticket> {
        val outer = agentsSnapshot
        if (outer == null) agentsSnapshot = host.agents.list()
        try { return viewsOf(rows, links) } finally { if (outer == null) agentsSnapshot = null }
    }

    private fun viewsOf(rows: Map<String, TicketRow>, links: List<TicketLinkRow>): Map<String, Ticket> {
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
                queuedAgent = row.queuedAgent,
                queueRank = row.queueRank,
                runner = row.runner,
                queuedAgentName = row.queuedAgent?.let { runCatching { host.agents.get(it)?.name }.getOrNull() },
                sessionId = row.sessionId,
                hasWork = hasWork(row),
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
    private fun effectiveContextLimit(row: TicketRow): com.superiorsoftllc.infantry.protocol.EffectiveContextLimit {
        val harness = sessionsOn.takeIf { it }?.let { runCatching { effectiveExecutor(row)?.provider?.value }.getOrNull() ?: host.sessions.providerId() }
        val capability = harness?.let { host.sessions.providerFor(it) }
        return com.superiorsoftllc.infantry.protocol.EffectiveContextLimit(
            value = row.contextLimit,
            source = if (row.contextLimit != null) "ticket" else "default",
            support = capability?.contextLimitSupport,
            how = capability?.contextLimitHow,
        )
    }

    /** EFFORT-1: the ticket's effort (ticket pin, else rule, agent profile, role binding, else none) with its source, and how its effective harness honours one. */
    private fun effectiveEffort(row: TicketRow): com.superiorsoftllc.infantry.protocol.EffectiveEffort {
        val harness = sessionsOn.takeIf { it }?.let { runCatching { effectiveExecutor(row)?.provider?.value }.getOrNull() ?: host.sessions.providerId() }
        val capability = harness?.let { host.sessions.providerFor(it) }
        val rule = routingChoice(row)
        val assigned = row.agentId ?: rule?.setRole?.let { "role:$it" }
        val profile = assigned?.let { ticketProfile(it) }
        val roleId = assigned?.takeIf { it.startsWith("role:") }?.removePrefix("role:")
        val (value, source) = when {
            row.effort != null -> row.effort to "ticket"
            rule?.effort != null -> rule.effort to "rule"
            profile?.effort != null -> profile.effort to "agent"
            else -> {
                val bound = (profile ?: roleId?.let { BoardAgent(name = it, role = it) })?.let { host.agents.executorFor(it, null, null, null, it.role) }
                if (bound?.effort != null) bound.effort to "role" else null to "default"
            }
        }
        return com.superiorsoftllc.infantry.protocol.EffectiveEffort(value = value, source = source, support = capability?.effortSupport, how = capability?.effortHow)
    }

    /** TICKET-WEIGHT: the mapping's choice for [row] when [WeightExecutors.applies] (nothing else pins an executor), else null. */
    private fun weightChoice(row: TicketRow, boundProvider: String?, boundModel: String?): WeightExecutor? =
        if (WeightExecutors.applies(row.weight, row.provider, row.model, boundProvider, boundModel)) {
            // H2: a weight route naming a harness that is not installed is skipped (model with it, which only fits that
            // harness), so the ticket runs on the first installed one; with none installed the start says so.
            weightExecutors().forWeight(row.weight)?.takeIf { e -> e.provider.let { p -> p == null || !sessionsOn || host.sessions.hasProvider(p) } }
        } else null

    /**
     * ROUTING-1: the first rule of [row]'s project that matches it, or null when the ticket pins its own provider or model
     * (an explicit pin always wins) or the project has no rules. The ticket's role is read from its assignment.
     */
    private fun routingChoice(row: TicketRow): com.superiorsoftllc.infantry.protocol.RoutingRule? {
        if (row.provider != null || row.model != null) return null
        val rules = routingRules(row.projectId)
        if (rules.isEmpty()) return null
        val role = row.agentId?.let { a -> if (a.startsWith("role:")) a.removePrefix("role:") else ticketProfile(a)?.role }
        return RoutingRules.match(rules, row.weight, row.ticketType, role)
    }

    /** RUNNER-POOL-3: the matching routing rule's `runner`, whether or not the ticket pins its own executor. */
    private fun ruleRunnerOf(row: TicketRow): String? {
        val rules = routingRules(row.projectId)
        if (rules.isEmpty()) return null
        val role = row.agentId?.let { a -> if (a.startsWith("role:")) a.removePrefix("role:") else ticketProfile(a)?.role }
        return RoutingRules.match(rules, row.weight, row.ticketType, role)?.runner
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
        val bound = (profile ?: roleId?.let { BoardAgent(name = it, role = it) })
            ?.let { host.agents.executorFor(it, row.provider, row.model, null, it.role) }
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
            sessionsOn -> ExecutorField(host.sessions.providerId(), "default")
            else -> ExecutorField(null, "default")
        }
        val profileProviderOrDefault = profileProvider ?: sessionsOn.takeIf { it }?.let { host.sessions.providerId() }
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
                val defaultModel = host.sessions.providers().firstOrNull { it.id == providerId }?.defaultModel
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
        return host.agents.get(ticketAgentId)?.role == filter.removePrefix("role:")
    }

    private fun normalizeRoleAgentId(agentId: String): String =
        if (agentId.startsWith("role:")) "role:" + RoleIds.normalize(agentId.removePrefix("role:")) else agentId

    private fun ticketProfile(agentId: String): BoardAgent? = when {
        agentId.startsWith("role:") -> (agentsSnapshot ?: host.agents.list()).firstOrNull { it.role == agentId.removePrefix("role:") }
        else -> host.agents.get(agentId)
    }

    private fun validateAgentId(agentId: String?, checkCatalog: Boolean) {
        if (agentId == null) return
        // LIMITS: a ticket newly assigned to a role the role allowlist leaves out is refused (a stored one stays editable).
        if (checkCatalog) host.sessions.requireAllowedRole(if (agentId.startsWith("role:")) agentId.removePrefix("role:") else ticketProfile(agentId)?.role)?.let { throw IllegalArgumentException(it) }
        if (agentId.startsWith("role:")) {
            val role = agentId.removePrefix("role:")
            require(RoleIds.PATTERN.matches(role) && (!checkCatalog || role in roleIds())) {
                "agentId '$agentId' must be a profile id or role:<canonical role id>; " +
                    "valid role ids: ${roleIds().joinToString(", ")}"
            }
            return
        }
        require(host.agents.get(agentId) != null) {
            "no agent profile '${agentId.take(80)}' (agent_list shows the profiles)"
        }
    }

    private fun validateExecutorPins(agentId: String?, provider: String?, model: String?) {
        val profile = agentId?.let(::ticketProfile)
        val profileProvider = profile?.provider
        val profileModel = profile?.model?.takeIf { provider == null || provider == (profileProvider ?: sessionsOn.takeIf { it }?.let { host.sessions.providerId() }) }
        val needsCheck = provider != null || model != null || profileProvider != null || profileModel != null
        if (!needsCheck) return
        require(sessionsOn) { "ticket executor validation is unavailable" }
        host.sessions.checkSpawn(provider ?: profileProvider, model ?: profileModel)?.let { throw IllegalArgumentException(it) }
        // BUG-E2E-1: the ticket's OWN model must be one its provider lists, when that provider can list them (the same
        // rule an agent profile save applies). Never coerced: a typo is refused with the provider's list to pick from.
        if (model != null) {
            val providerId = provider ?: profileProvider ?: host.sessions.providerId()
            val known = host.sessions.providerFor(providerId)?.knownModels?.invoke()
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
                // AGENT-TASKS-3: leaving todo drops the ticket from its agent's queue; a move back into todo keeps it.
                queuedAgent = if (status == TicketStatus.TODO) row.queuedAgent else null,
                queueRank = if (status == TicketStatus.TODO) row.queueRank else null,
            )
            // The queue the ticket left closes up: the tickets behind it move one place forward.
            val left = row.queuedAgent
            val rank = row.queueRank
            if (left != null && rank != null && status != TicketStatus.TODO) {
                rows.values.filter { it.queuedAgent == left && (it.queueRank ?: 0) > rank }.forEach {
                    rows[it.id] = it.copy(queueRank = it.queueRank!! - 1)
                }
            }
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
