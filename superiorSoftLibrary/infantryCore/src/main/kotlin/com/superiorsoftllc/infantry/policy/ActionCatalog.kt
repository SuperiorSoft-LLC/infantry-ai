// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.policy

import com.superiorsoftllc.infantry.plugin.api.ActionKind.DESTRUCTIVE
import com.superiorsoftllc.infantry.plugin.api.ActionKind.READ
import com.superiorsoftllc.infantry.plugin.api.ActionKind.SPAWN
import com.superiorsoftllc.infantry.plugin.api.ActionKind.WRITE
import com.superiorsoftllc.infantry.protocol.Envelope as E

/**
 * POLICY-1 (docs/POLICY.md section 3): every client frame the daemon dispatches, as ONE named action. The policy gate
 * refuses a frame by its action, with the same matcher the UI dims a control with, so the two cannot disagree.
 * [scope] tells the seated lockdown which payload names what: a PROJECT frame names a project, a SESSION frame a session.
 * ActionCatalogCoverageTest fails when a dispatched frame is missing here.
 */
object ActionCatalog {
    enum class Scope { GLOBAL, PROJECT, SESSION }

    data class Action(val id: String, val kind: String, val frames: List<String>, val scope: Scope = Scope.GLOBAL)

    /** `replay_request` is read before the dispatch (a session's stream replay). */
    const val REPLAY_REQUEST = "replay_request"
    const val POLICY_GET = "policy_get"
    const val POLICY_SET = "policy_set"
    const val PLUGIN_TOOLS = "plugin_tools"
    /** RUNNER-POOL-6a: `infantry runner pair|add|rotate|detach <name>` through the daemon (owner-only; not a `runner_link_` frame). */
    const val RUNNER_ADMIN = "runner_admin"

    private fun a(id: String, kind: String, vararg frames: String, scope: Scope = Scope.GLOBAL) = Action(id, kind, frames.toList(), scope)

    val ACTIONS: List<Action> = listOf(
        // Reads.
        a("sessions.read", READ, E.SESSIONS, E.SESSION_STATS_GET, E.USAGE_METER_GET),
        a("sessions.replay", READ, REPLAY_REQUEST, scope = Scope.SESSION),
        a("projects.read", READ, E.PROJECT_LIST, E.PROJECT_GET, E.WORKSPACE_LIST),
        a("settings.read", READ, E.SETTINGS_GET, E.LIMIT_CATALOG, E.LIMITS, E.CLI_LOCATIONS, E.TOOL_APPROVAL, E.APPROVAL_RULES),
        a("usage.read", READ, E.USAGE_GET),
        a("board.read", READ, E.TICKET_LIST, E.TICKET_GET),
        a("board.files.read", READ, E.TICKET_FILES),
        a("board.archive.read", READ, E.ARCHIVE_QUERY),
        a("triggers.read", READ, E.TRIGGER_LIST, E.TRIGGER_EVENTS),
        a("commands.read", READ, E.COMMAND_LIST),
        a("commands.catalog.read", READ, E.COMMAND_CATALOG),
        a("roles.read", READ, E.ROLE_LIST, E.ROLE_BINDING_LIST, E.ROLE_BINDING_GET, E.TRAIT_LIST, E.TRAIT_GET, E.ROLE_TRAITS_LIST, E.ROLE_TRAITS_GET),
        a("harness.read", READ, E.PROVIDERS, E.MODELS, E.PROVIDER_STATES),
        a("harness.install.read", READ, E.HARNESS_INSTALL_STATUS),
        a("questions.read", READ, E.QUESTION_LIST),
        a("mail.read", READ, E.MAIL_INBOX, E.MAIL_OUTBOX, E.MAIL_THREAD, E.MAIL_THREADS, E.MAIL_WAITING, E.MAIL_READ),
        a("agents.read", READ, E.AGENT_LIST, E.AGENT_ROSTER),
        a("attention.read", READ, E.ATTENTION_LIST),
        a("timeline.read", READ, E.TIMELINE_LIST),
        a("prompts.read", READ, E.PROMPT_LIST, E.PROMPT_GET),
        a("skills.read", READ, E.SKILL_LIST, E.SKILL_GET, E.LAYER_SKILL_LIST),
        a("pods.read", READ, E.POD_QUERY),
        a("worktrees.read", READ, E.WORKTREES),
        a("runs.read", READ, E.RUN_LIST),
        a("config.read", READ, E.CONFIG_GET),
        // POLICY-1 (1.143.0): listing is a read; the killing half is its own frame (see [forEnvelope] for a legacy reap).
        a("host.processes.read", READ, E.PROCESSES),
        a("host.processes.reap", DESTRUCTIVE, E.PROCESSES_REAP),
        a("host.health.read", READ, E.HEALTH),
        a("secrets.read", READ, E.SECRET_STATUS),
        // PACE-TREND-1 (b4e46daf): Infantry_Pace answers this frame from its daemon half, so it has no Envelope constant.
        a("pace.read", READ, "pace_get"),
        a("secrets.plugin.read", READ, E.PLUGIN_SECRET_GET),
        a("stories.read", READ, E.STORY_GET, E.STORY_LIST),
        a("knowledge.read", READ, E.DECISION_LIST, E.DECISION_GET, E.ISSUE_LIST, E.ISSUE_GET, E.ISSUE_PRECHECK),
        a(
            "project.git.read", READ, E.PROJECT_GIT_STATUS, E.PROJECT_GIT_LOG, E.PROJECT_GIT_DIFF, E.PROJECT_GIT_BRANCHES,
            E.PROJECT_GIT_BRANCH_CHANGES, E.PROJECT_GIT_BRANCH_DIFF, E.PROJECT_GIT_WORKTREES, E.PROJECT_GIT_WORKTREE_STATUS,
            scope = Scope.PROJECT,
        ),
        a(
            "project.git.history.read", READ, E.PROJECT_GIT_HISTORY, E.PROJECT_GIT_BRANCH_LIST, E.PROJECT_GIT_BRANCH_DIFFSTAT,
            E.PROJECT_GIT_WORKTREE_LIST, scope = Scope.PROJECT,
        ),
        a("epics.read", READ, E.EPIC_LIST, scope = Scope.PROJECT),
        a("memory.read", READ, E.MEMORY_LIST, E.MEMORY_SEARCH, E.MEMORY_GET, scope = Scope.PROJECT),
        a("pipeline.read", READ, E.PIPELINE_SHOW, scope = Scope.PROJECT),
        a("policy.read", READ, POLICY_GET),
        // Listing the daemon plugins' tools is a read; a call is gated by the called tool's own action.
        a("plugin.tools.list", READ, PLUGIN_TOOLS),

        // Chat and sessions.
        // CHAT-QUEUE-1: holding, taking back and forcing a message are prompting the session.
        a("chat.prompt", WRITE, E.PROMPT, E.QUEUE_ADD, E.QUEUE_REMOVE, E.QUEUE_FORCE, scope = Scope.SESSION),
        a("chat.resume", WRITE, E.RESUME, scope = Scope.SESSION),
        a("chat.interrupt", WRITE, E.INTERRUPT),
        a("agents.kill", DESTRUCTIVE, E.KILL),
        a("agents.spawn", SPAWN, E.NEW),
        a("agents.assistant", SPAWN, E.ASSISTANT),
        a("agents.delete", DESTRUCTIVE, E.SESSION_DELETE),
        a("agents.restart", DESTRUCTIVE, E.SESSION_RESTART),
        a("agents.project.set", WRITE, E.SESSION_PROJECT),
        a("agents.swap", WRITE, E.SESSION_SWAP, E.AGENT_SWAP),
        a("agents.profile.save", WRITE, E.AGENT_SAVE),
        a("agents.profile.delete", DESTRUCTIVE, E.AGENT_DELETE),
        a("agents.report", WRITE, E.BG_JOB, E.PROGRESS, E.WAITING),
        a("worktrees.remove", DESTRUCTIVE, E.WORKTREE_REMOVE),
        a("repo.set", WRITE, E.REPO),
        a("mail.send", WRITE, E.MAIL_SEND),
        a("notify.send", WRITE, E.NOTIFY),
        a("approvals.answer", WRITE, E.PROMPT_RESPONSE),
        a("questions.ask", WRITE, E.QUESTION_ASK, E.PROMPT_ASK),
        a("questions.answer", WRITE, E.QUESTION_ANSWER, E.QUESTION_CANCEL),
        a("attention.ack", WRITE, E.ATTENTION_ACK),
        a("pods.submit", SPAWN, E.POD_SUBMIT),
        a("pods.cancel", DESTRUCTIVE, E.POD_CANCEL),

        // Roles, traits, prompts, skills, profiles.
        a("roles.save", WRITE, E.ROLE_SAVE, E.ROLE_BINDING_SET, E.ROLE_TRAITS_SET),
        a("roles.delete", DESTRUCTIVE, E.ROLE_DELETE),
        a("traits.save", WRITE, E.TRAIT_SAVE),
        a("traits.delete", DESTRUCTIVE, E.TRAIT_DELETE),
        a("prompts.save", WRITE, E.PROMPT_SAVE),
        a("prompts.delete", DESTRUCTIVE, E.PROMPT_DELETE),
        a("skills.save", WRITE, E.SKILL_SAVE, E.LAYER_SKILL_SAVE),
        a("skills.delete", DESTRUCTIVE, E.SKILL_DELETE, E.LAYER_SKILL_DELETE),

        // Board, triggers, epics, stories, knowledge.
        a("board.ticket.save", WRITE, E.TICKET_SAVE, E.TICKET_LINK, E.TICKET_UNLINK),
        a("board.ticket.move", WRITE, E.TICKET_MOVE, E.TICKET_REORDER, E.TICKET_UNASSIGN, E.AGENT_QUEUE, E.TICKET_ASSIGN, E.AGENT_SWITCH),
        a("board.ticket.delete", DESTRUCTIVE, E.TICKET_DELETE),
        a("board.archive.write", WRITE, E.ARCHIVE_APPLY),
        a("board.ticket.start", SPAWN, E.TICKET_START_NEXT),
        a("triggers.save", WRITE, E.TRIGGER_SAVE),
        a("triggers.fire", WRITE, E.TRIGGER_FIRE, E.TRIGGER_ARM, E.TRIGGER_DISABLE),
        a("triggers.delete", DESTRUCTIVE, E.TRIGGER_DELETE),
        a("epics.save", WRITE, E.EPIC_SAVE, E.EPIC_BASE),
        a("stories.save", WRITE, E.STORY_SAVE, E.STORY_MOVE),
        a("memory.save", WRITE, E.MEMORY_SAVE),
        a("memory.delete", DESTRUCTIVE, E.MEMORY_DELETE, E.MEMORY_PURGE_ROLE),
        a("knowledge.decision.add", WRITE, E.DECISION_ADD),
        a("knowledge.issue.save", WRITE, E.ISSUE_OPEN, E.ISSUE_ATTEMPT, E.ISSUE_FIX),
        a("pipeline.save", WRITE, E.PIPELINE_CONFIG, E.PIPELINE_RECORD, E.PIPELINE_CHECK),

        // Projects and their git.
        a("projects.save", WRITE, E.PROJECT_SAVE, E.PROJECT_BASE, E.PROJECT_CONFIG),
        a("workspaces.save", WRITE, E.WORKSPACE_SAVE),
        a("images.upload", WRITE, E.IMAGE_UPLOAD),
        a("project.git.checkout", WRITE, E.PROJECT_GIT_CHECKOUT),

        // Configuration, harnesses, usage.
        a("settings.save", WRITE, E.SETTINGS_SET, E.CLI_LOCATION_SET, E.TOOL_APPROVAL_SET, E.APPROVAL_RULE_DELETE),
        a("config.save", WRITE, E.CONFIG_SET),
        a("usage.set", WRITE, E.USAGE_SET),
        a("harness.install", WRITE, E.HARNESS_INSTALL),
        a("policy.set", WRITE, POLICY_SET),

        // Owner-only (FrameGate.OWNER_ONLY): saved commands, runs, secrets, the runner link's pairing (RUNNER-POOL-6a).
        a("commands.save", WRITE, E.COMMAND_SAVE, E.COMMAND_DELETE),
        a("runs.start", SPAWN, E.RUN_START, E.RUN_ATTACH, E.RUN_RESIZE, E.RUN_INPUT),
        a("runs.stop", DESTRUCTIVE, E.RUN_STOP),
        a("secrets.save", WRITE, E.SECRET_SET, E.SECRET_CLEAR, E.SECRET_IMPORT, E.SECRET_FIELD_SET, E.SECRET_FIELD_DELETE, E.SECRET_DELETE, E.SECRET_MOVE),
        a("runners.admin", WRITE, RUNNER_ADMIN),

        // PLUGIN-BRIDGES-1: a client hosting its UI halves' capabilities (announce, answer), and their declared events.
        a("capabilities.host", READ, com.superiorsoftllc.infantry.plugin.api.CapabilityWire.HOST, com.superiorsoftllc.infantry.plugin.api.CapabilityWire.RESULT),
        a("capabilities.emit", WRITE, com.superiorsoftllc.infantry.plugin.api.CapabilityWire.EVENT),
        // NOTIFY-CORE-1: a pressed notification action (it can start work) and a dismissed notification (stops its replay).
        a("notifications.act", WRITE, com.superiorsoftllc.infantry.plugin.api.NotificationWire.ACTION),
        a("notifications.dismiss", READ, com.superiorsoftllc.infantry.plugin.api.NotificationWire.DISMISS),
    )

    private val byFrame: Map<String, Action> = buildMap {
        for (action in ACTIONS) for (frame in action.frames) {
            check(put(frame, action) == null) { "frame '$frame' is mapped twice" }
        }
    }

    private val byId: Map<String, Action> = ACTIONS.associateBy { it.id }.also { check(it.size == ACTIONS.size) { "an action id is declared twice" } }

    fun forFrame(type: String): Action? = byFrame[type]

    /**
     * The action [envelope] performs: its frame's, except a legacy `processes` whose op is not `list` (a client below
     * 1.143.0 reaps with it), which is the reap action. Gate with this, never with the frame alone.
     */
    fun forEnvelope(envelope: E): Action? {
        if (envelope.type == E.PROCESSES && processesOp(envelope.data) != "list") return byFrame[E.PROCESSES_REAP]
        return byFrame[envelope.type]
    }

    /** The op of a `processes` payload (blank = `list`); an unreadable payload is not `list`, so it is gated as a reap. */
    private fun processesOp(data: String): String? {
        if (data.isBlank()) return "list"
        return runCatching {
            (kotlinx.serialization.json.Json.parseToJsonElement(data) as kotlinx.serialization.json.JsonObject)["op"]
                ?.let { (it as kotlinx.serialization.json.JsonPrimitive).content } ?: "list"
        }.getOrNull()
    }

    fun forId(id: String): Action? = byId[id]

    val frames: Set<String> get() = byFrame.keys

    /**
     * GAP-AUDIT-1 (h): the catalog as the `policy` frame sends it (frame -> action id + kind), without the switch frames,
     * which the policy never gates for the owner; the client's send gate runs the same matcher on it.
     */
    fun wireFrames(): Map<String, com.superiorsoftllc.infantry.plugin.api.FrameAction> =
        byFrame.filterKeys { it !in SWITCH_FRAMES }.mapValues { (_, a) -> com.superiorsoftllc.infantry.plugin.api.FrameAction(a.id, a.kind) }

    /** The switch itself: never policy-gated for the owner (pinning is how a deployment locks it). */
    val SWITCH_FRAMES: Set<String> = setOf(POLICY_GET, POLICY_SET)
}
