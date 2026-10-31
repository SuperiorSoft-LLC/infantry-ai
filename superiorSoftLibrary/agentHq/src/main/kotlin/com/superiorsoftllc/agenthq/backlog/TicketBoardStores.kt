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
import com.superiorsoftllc.agenthq.daemon.api.board.BoardAgent
import com.superiorsoftllc.agenthq.daemon.api.board.BoardHost
import com.superiorsoftllc.agenthq.daemon.api.board.BoardSessionEvent
import com.superiorsoftllc.agenthq.daemon.api.sessions.SessionView
import com.superiorsoftllc.agenthq.daemon.api.board.BranchOutcome
import com.superiorsoftllc.agenthq.daemon.api.board.BranchRequest
import com.superiorsoftllc.agenthq.daemon.api.board.SessionStart
import com.superiorsoftllc.agenthq.daemon.api.board.SessionStartResult
import com.superiorsoftllc.agenthq.daemon.api.board.StoredRun
import com.superiorsoftllc.agenthq.daemon.api.board.TicketEventAppender
import com.superiorsoftllc.agenthq.daemon.plugins.LocalBoardHost
import com.superiorsoftllc.agenthq.daemon.plugins.toRow
import com.superiorsoftllc.agenthq.daemon.plugins.toStored
import com.superiorsoftllc.agenthq.persistence.TicketLinkRow
import com.superiorsoftllc.agenthq.persistence.TicketRow
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
import com.superiorsoftllc.agenthq.session.SessionManager
import com.superiorsoftllc.agenthq.worktree.BaseScope
import com.superiorsoftllc.agenthq.worktree.TicketBaseRef
import com.superiorsoftllc.agenthq.worktree.TicketBranchOutcome
import com.superiorsoftllc.agenthq.worktree.TicketBranchRequest
import com.superiorsoftllc.agenthq.worktree.TicketBranches
import com.superiorsoftllc.agenthq.worktree.TicketWorktree
import com.superiorsoftllc.agenthq.worktree.WorktreeRecord
import com.superiorsoftllc.agenthq.session.SessionState
import com.superiorsoftllc.agenthq.session.TicketWorkerHealth
import com.superiorsoftllc.agenthq.session.isBusy
import com.superiorsoftllc.agenthq.session.ContextSource
import java.io.File
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.TimeUnit

/**
 * DP-5f-3a/3d: the TicketBoard over the real stores (what every caller used before the host): builds a [LocalBoardHost] over them
 * and delegates to the primary constructor, so behaviour is whatever the stores did. Lives core-side so the class itself imports no core host.
 */
fun TicketBoard(
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
): TicketBoard = TicketBoard(
        host = LocalBoardHost(store, { sessions }, branches, baseRef, projectDirectory, repo, { null }, clock, agents, knowledge, skills, images),
        clock = clock, idFactory = idFactory, context = context, profilesAvailable = agents != null, roleIds = roleIds, exitBehavior = exitBehavior, autoDoneMinOutputs = autoDoneMinOutputs, weightExecutors = weightExecutors, weightContext = weightContext, laneCap = laneCap, routingRules = routingRules, routingRefused = routingRefused, pacing = pacing, relatedDescription = relatedDescription,
        unknownSkills = { ids -> store.transaction { ids.filter { store.skills.get(it) == null } } },
)

/** The in-process host a [TicketBoard] built by the store factory runs on: the daemon feeds its one session event sink to [LocalBoardHost.publish]. */
val TicketBoard.localHost: LocalBoardHost? get() = host as? LocalBoardHost
