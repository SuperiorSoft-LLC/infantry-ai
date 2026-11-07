// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.daemon.api

import com.superiorsoftllc.infantry.plugin.api.PolicyPreset
import kotlinx.serialization.json.JsonObject
import java.io.File
import java.time.format.DateTimeFormatter

/** [DaemonApi.displayStamp]'s default pattern (the host's own formatter uses the same one). */
private val DISPLAY_STAMP: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm xxx")

/**
 * DAEMON-PLUGINS-1 (docs/DAEMON-PLUGINS.md): the class a module's `daemon.entry` names. It needs a public no-arg constructor.
 * [start] runs once at daemon start, after the stores and sessions exist and before the server binds; everything the plugin
 * does goes through [DaemonApi]. [stop] runs when the daemon closes, or when the host disables the plugin.
 */
interface DaemonPlugin {
    fun start(api: DaemonApi)
    fun stop() {}
}

/**
 * The declared daemon surface a plugin sees, and nothing else (its class loader hides every daemon class). Everything a
 * plugin registers must be declared in its module.json `daemon` object; an undeclared registration throws
 * [IllegalStateException] (the host logs it and disables the plugin).
 */
interface DaemonApi {
    /** The module's name (`Vendor_Module`). */
    val moduleId: String
    /** `core`, `official` or `community`: the folder's tier. */
    val tier: String
    /** [VERSION] of the daemon the plugin runs in. */
    val apiVersion: String
    /** The plugin's own folder, `<state dir>/plugin-data/<moduleId>/` (created on first use). */
    val stateDir: File

    fun log(line: String)

    /** A value of the module's `daemon.config` (CONFIG-8), or null when unset. */
    fun config(key: String): String?

    /** An environment variable the module declared in `daemon.env` (community: and was granted); else null. */
    fun env(name: String): String?

    /** A keystore secret the module declared in `daemon.secrets` (community: and was granted); else null. */
    fun secret(name: String): String?

    /** Runs [job] every [periodMs] (at least 1 s) under the plugin's limits; close the handle to stop it. */
    fun schedule(name: String, periodMs: Long, job: () -> Unit): AutoCloseable

    /**
     * DEMO-CHECK-1 (1.39.0, additive): runs [block] once, under the plugin's limits, when the daemon has finished starting:
     * every module's daemon half has started and every harness module is registered, before the server binds. [start] runs
     * while later modules are still loading, so work at boot that needs another plugin's service (e.g. the Roles port) or a
     * harness (e.g. spawning a session) belongs here. A plugin loaded after start-up (a live load) runs [block] at once, on
     * its own thread. The default (a host without the signal) runs it at once.
     */
    fun whenStarted(block: () -> Unit) { block() }

    /**
     * SCRIPTING-NCDFE-1 (1.40.0, additive): [epochMs] (UTC) as a person reads it, `2026-11-03 20:41 -05:00`, in the daemon's
     * `display.timeZone` setting (blank = the system zone). A plugin's class loader cannot link core's time helper, so this is
     * a plugin's way to the daemon's display zone. The default (a host without the setting) uses the system zone.
     */
    fun displayStamp(epochMs: Long): String = DISPLAY_STAMP.format(java.time.Instant.ofEpochMilli(epochMs).atZone(java.time.ZoneId.systemDefault()))

    val projects: Projects
    val sessions: Sessions
    val policy: Policy
    val events: Events
    val tools: Tools
    val frames: Frames
    val routes: Routes
    val hooks: Hooks

    /** DP-4a (1.2.0, additive): tickets, epics and the board (docs/DAEMON-BOARD-API.md). A daemon without a board answers Unavailable. */
    val tickets: com.superiorsoftllc.infantry.daemon.api.board.Tickets get() = com.superiorsoftllc.infantry.daemon.api.board.NoBoard.tickets
    val epics: com.superiorsoftllc.infantry.daemon.api.board.Epics get() = com.superiorsoftllc.infantry.daemon.api.board.NoBoard.epics
    /** DP-5h-3a (1.12.0, additive): the host side of a board plugin's board (settings, project tables, pacing, push). A daemon without one answers [com.superiorsoftllc.infantry.daemon.api.board.NoBoardRuntime]. */
    val boardRuntime: com.superiorsoftllc.infantry.daemon.api.board.BoardRuntime get() = com.superiorsoftllc.infantry.daemon.api.board.NoBoardRuntime
    val board: com.superiorsoftllc.infantry.daemon.api.board.Board get() = com.superiorsoftllc.infantry.daemon.api.board.NoBoard.board

    /** AGENT-REGISTRY-1 (1.5.0, additive): the agent registry (docs/AGENT-REGISTRY.md). A daemon without one lists nothing. */
    val agents: com.superiorsoftllc.infantry.daemon.api.agents.Agents get() = com.superiorsoftllc.infantry.daemon.api.agents.NoAgents

    /**
     * RUNTIME-LOAD-5 (1.6.0, additive): the sessions READ model, host-scoped (docs/RUNTIME-LOAD.md section 11). A daemon without
     * one answers Unavailable. Community plugins need the grants `sessions.read` / `sessions.transcript`.
     */
    val sessionReads: com.superiorsoftllc.infantry.daemon.api.sessions.SessionReads get() = com.superiorsoftllc.infantry.daemon.api.sessions.NoSessionReads

    /**
     * RUNTIME-LOAD-6 (1.7.0, additive): the HOST spawn service (docs/RUNTIME-LOAD.md section 12): spawn, ticket start and kill
     * guardrails keyed by the calling session, so every client and plugin is held to one state. A daemon without one answers
     * Unavailable. Community plugins need the grant `sessions.spawn`.
     */
    val spawns: com.superiorsoftllc.infantry.daemon.api.spawns.Spawns get() = com.superiorsoftllc.infantry.daemon.api.spawns.NoSpawns

    /**
     * STACK-NEUTRAL-2 (1.8.0, additive): the host process services (docs/STACK-NEUTRAL.md section 6): the OS process table, the
     * start-instant-checked kill, the session roots, the session-closed event and the gate lock. Official / core tier; a community
     * plugin needs the grant `daemon.processes` and never gets `kill`. A daemon without one lists nothing and kills nothing.
     */
    val processes: com.superiorsoftllc.infantry.daemon.api.processes.Processes get() = com.superiorsoftllc.infantry.daemon.api.processes.NoProcesses

    /** STACK-NEUTRAL-2 (1.8.0, additive): contribute environment variables to every session launch. Fail open. */
    val launchEnv: com.superiorsoftllc.infantry.daemon.api.processes.LaunchEnv get() = com.superiorsoftllc.infantry.daemon.api.processes.NoLaunchEnv

    /** STACK-NEUTRAL-2 (1.8.0, additive): read access to `limits.*`, `watch.*` and `processes.*` settings. */
    val settings: com.superiorsoftllc.infantry.daemon.api.processes.PluginSettings get() = com.superiorsoftllc.infantry.daemon.api.processes.NoPluginSettings

    /**
     * PACE-TREND-1 (1.10.0, additive): the plan meters, read-only (daemon.api.usage.MeterReading). A community plugin needs the
     * grant `usage.read`. A daemon without meters reads nothing.
     */
    val meters: com.superiorsoftllc.infantry.daemon.api.usage.Meters get() = com.superiorsoftllc.infantry.daemon.api.usage.NoMeters

    /** DAEMON-STORAGE-1 (1.15.0, additive): plugin-owned tables over the daemon's SQLite (daemon.api.storage). Community needs the grant `storage`. */
    val storage: com.superiorsoftllc.infantry.daemon.api.storage.PluginStorage get() = com.superiorsoftllc.infantry.daemon.api.storage.NoPluginStorage

    /** AR-5 (1.16.0, additive): the typed optional service registry (provide / find by interface). Community needs the grant `services.provide` to publish. */
    val services: ServicesApi get() = NoServices

    /** DP-7e (1.19.0, additive): the mail port (notify one session or every live session of a role). Community needs the grant `sessions.notify`. */
    val sessionNotifier: com.superiorsoftllc.infantry.daemon.api.sessions.SessionNotifier get() = com.superiorsoftllc.infantry.daemon.api.sessions.NoSessionNotifier

    /**
     * DP-10a (1.21.0, additive): the project-scoped file surface `<project directory>/.infantry/` (daemon.api.files.ProjectFiles).
     * Official / core tier, or a community module with the grant `files`; an unknown project or one without a directory is refused
     * with [IllegalArgumentException]. A daemon without one refuses with [IllegalStateException].
     */
    fun files(projectId: String): com.superiorsoftllc.infantry.daemon.api.files.ProjectFiles = com.superiorsoftllc.infantry.daemon.api.files.NoProjectFiles

    /** TRIG-1 (1.28.0, additive): the ports the triggers service runs on (daemon.api.triggers). A daemon without them answers nothing. */
    val triggerHost: com.superiorsoftllc.infantry.daemon.api.triggers.TriggerHost? get() = null

    /** PLUGIN-MCP-1 (1.36.0, additive): read-only plugin manager view and the owner-gated install request. Official / core, or a community module with the grant `plugins.read`. */
    val plugins: com.superiorsoftllc.infantry.daemon.api.plugins.PluginCatalogReader get() = com.superiorsoftllc.infantry.daemon.api.plugins.NoPluginCatalog

    /** CMD-1 (1.31.0, additive): the cwd-bound run surface (daemon.api.runs). Official / core tier, or a community module with the grant `runs`. */
    val runs: com.superiorsoftllc.infantry.daemon.api.runs.Runs get() = com.superiorsoftllc.infantry.daemon.api.runs.NoRuns

    /**
     * UPDATE-ENGINE-1 (1.39.0, additive): the update engine (daemon.api.updates, docs/wiki/Updates.md): every component (the app and
     * each module with an `update` block), its source, check / fetch / verify / stage, and the appliers. Registering a source type
     * needs it in `daemon.updateSources`. A daemon without one answers [com.superiorsoftllc.infantry.daemon.api.updates.NoUpdates].
     */
    val updates: com.superiorsoftllc.infantry.daemon.api.updates.Updates get() = com.superiorsoftllc.infantry.daemon.api.updates.NoUpdates

    /**
     * RUNNER-POOL-6 (1.45.0, additive): calls to the runners this orchestrator is paired with (daemon.api.runners.RunnerLinks), for a
     * runner binding plugin. Core keeps each runner's url, pin and credential; the plugin sends frames by runner name only.
     */
    val runnerLinks: com.superiorsoftllc.infantry.daemon.api.runners.RunnerLinks get() = com.superiorsoftllc.infantry.daemon.api.runners.NoRunnerLinks

    companion object {
        // 1.31.0 (CMD-1, additive): `runs` (daemon.api.runs.Runs) and `PluginSettings.hint`.
        // 1.30.0 (MAIL-HOOK-1, additive): `hooks.incomingMail` (IncomingMailHook, IncomingMail, MailVerdict): a pre-delivery look at each accepted mail.
        // 1.29.0 (TRIG-2a, additive): template routes (`{name}` segments, HttpCall.pathParams) and `hooks.status` (StatusHook).
        // 1.28.0 (TRIG-1, additive): `triggerHost` (daemon.api.triggers.TriggerHost): trigger store, sessions, mail, spawn-with-binding, run events.
        // 1.27.0 (SCRIPT-4, additive): `tools.catalog` / `tools.invoke` (ToolInfo, ToolInvoker): the live tool registry, reachable by an official plugin.
        // 1.26.0 (SCRIPT-3, additive): `events.publish` / `events.subscribe` (NamedEvent): generic named events any plugin / agent publishes.
        // 1.24.0 (TICKET-COMMENTS-4, additive): daemon.api.tickets.TicketReports (published by Infantry_Board); a new ticket carries the report line (setting board.ticket.reportLine).
        // 1.25.0 (SCRIPT-2, additive): `services.publishEndpoint` / `services.endpoints(prefix)` (ServiceEndpoint): name-keyed string contracts between plugins.
        // 1.24.0 (ATTN-2a, additive): `events.broadcast`, `hooks.connected` (ConnectedHook), daemon.api.attention.QuestionsOpen. The session-closed reason is `processes.onSessionClosed` (1.8.0).
        // 1.23.0 (TICKET-COMMENTS-2, additive): no new member; Infantry_Board's daemon half gains infantry_ticket_comment / infantry_ticket_comments,
        // notify on ticket_create / ticket_update and createdBy / notify / openComments on ticket_get, over `storage`, `agents`, `sessionNotifier`.
        // 1.22.0 (DP-10b, additive): `hooks.approvalRules` (ApprovalRulesHook): the stored "allow always" rules of tool approval.
        // 1.21.0 (DP-10a, additive): `files(projectId)` (daemon.api.files.ProjectFiles), scoped to `<project>/.infantry/`.
        // 1.19.0 (DP-7e, additive): `sessionNotifier` (daemon.api.sessions.SessionNotifier).
        // 1.18.0 (DP-7d, additive): `events.onSessionRemoved`.
        // 1.17.0 (DP-7c, additive): daemon.api.knowledge.KnowledgeRecords, the decisions / issues half of Knowledge (published by Infantry_Notes).
        // 1.16.0 (AR-5, additive): `services` (ServicesApi).
        // 1.15.0 (DAEMON-STORAGE-1, additive): `storage` (daemon.api.storage.PluginStorage).
        // 1.13.0 (DP-5h-3a2, additive): BoardRuntime.ticketEvents / stories / epics / relatedDescription / unknownSkills.
        // 1.20.0 (USAGE-FIT, additive): `Meters.requests(sinceMs)` and `MeterRequest`, the recorded model requests (read-only).
        // 1.12.0 (DP-5h-3a, additive): `boardRuntime` (BoardRuntime): what a board plugin builds its board from.
        // 1.11.0 (DP-5h-2, additive): `spawns.charge` (SpawnCharge) and `frames.table` (FrameBinding: typed frames with the direct flag).
        // 1.10.0 (PACE-TREND-1, additive): `meters` (daemon.api.usage.Meters), the current reading of every provider window.
        // 1.9.0 (RUNTIME-LOAD-7, additive): `ToolCall.clientNarrowing` (the MCP client's depth / spawn narrowing).
        // 1.8.0 (STACK-NEUTRAL-2, additive): `processes`, `launchEnv`, `settings` (daemon.api.processes).
        // 1.7.0 (RUNTIME-LOAD-6, additive): `spawns` (daemon.api.spawns): host spawn / ticket start / kill guardrails.
        // 1.6.0 (RUNTIME-LOAD-5, additive): `sessionReads` (daemon.api.sessions), SessionText, board.BoardLines.
        // 1.5.0 (AGENT-REGISTRY-1, additive): `agents` (daemon.api.agents.Agents) and module.json `agents`.
        // 1.4.0 (RUNTIME-LOAD-4, additive): BOARD_API_VERSION 1.3.0 (daemon.api.board.TicketRefs / TicketPlacement).
        /** The DaemonApi version (semver). A module's `daemon.apiAtLeast` above it is refused. */
        // 1.32.0 (AR-4b, additive): daemon.api.roles.Roles (read-only role catalog service) and RoleRecord response fields.
        // 1.33.0 (AR-4b-ii-a, additive): daemon.api.roles.RoleBindings / RoleTraitLists / RoleEnvironment.
        // 1.34.0 (AR-4b-ii-b, additive): RoleBindingRecord / RoleTraitsRecord replace the protocol types on the role binding / trait ports (a plugin cannot link protocol types).
        // 1.14.0 (DP-5h-3e-b, additive): BoardRuntime.attachCore / detachCore and BoardCoreAccess (core consumers reach the plugin-built board).
        // 1.36.0 (PLUGIN-MCP-1, additive): `plugins` (daemon.api.plugins.PluginCatalogReader) and daemon.api.attention.OwnerAsks.
        // 1.35.0 (AR-3a, additive): daemon.api.sessions.SessionBinding / FirstTurnContextProvider (core composes the first turn from a provider list).
        // 1.37.0 (PROC-LEAK-1, additive): Processes.sessionMembers / containment, ProcessToolProfile.stop / stuck, TrackedProcess.stuck.
        // 1.38.0 (CHAT-QUEUE-1, additive): Sessions.queued / queueAdd / queueRemove / queueForce and QueuedMessage.
        // 1.39.0 (DEMO-CHECK-1, additive): `whenStarted`, work a plugin runs once the whole daemon (all daemon halves and harnesses) is up.
        // 1.39.0 (PROC-LEAK-2, additive): ProcessToolProfile.daemonDir / ProcessDaemonDir.
        // 1.39.0 (UPDATE-ENGINE-1, additive): `updates` (daemon.api.updates.Updates): the one update engine; source types and appliers by plugins.
        // 1.40.0 (SCRIPTING-NCDFE-1, additive): `displayStamp`, a time in the daemon's display zone (a plugin cannot link core's DisplayZone).
        // 1.41.0 (SECRETS-2b, additive): Runs.secretFields(name, projectId) (project first, then app) and Runs.secretNames(projectId).
        // 1.42.0 (PROJECT-ISOLATION-1, additive): `Caller.project` (a call confined to one project); every agent tool call and every hook is
        // held to its own project by the host (named events carry `projectId`).
        // 1.43.0 (RUNNER-POOL-2, additive): RunnerLink command contract 1.0.0 (hello/startSession/stopSession/deliverMail/health),
        // LocalRunnerLink, RunnerPool / RunnerHub, `hooks.runnerBinding`.
        // 1.44.0 (RUNNER-POOL-6a, additive): daemon.api.runners.RunnerLinkTransport (LinkAnswer, LinkFailure, LinkTlsMaterial,
        // LinkHandler, LinkServing) and `hooks.runnerLinkTransport`: the runner link transport is a plugin's.
        // 1.45.0 (RUNNER-POOL-6, additive): `runnerLinks` (RunnerLinks), daemon.api.runners.RunnerLinkWire (the link frame family, contract
        // 1.1.0), RunnerLink contract 1.1.0 (events, readTranscript; StartSpec git remote / prompt / worktree, SessionRef branch),
        // RunnerHub remote runners + heartbeat, SessionStart.runner and BoardRuntime.runners (placement on a paired runner).
        const val VERSION = "1.45.0"
    }
}

/** One client frame as a plugin sees it (the daemon's envelope, fields only). */
data class Frame(val type: String, val data: String = "", val sessionId: String? = null, val requestId: String? = null)

/** Who sent a frame or called a tool. */
data class Caller(
    /** The connection presented the owner credential. */
    val owner: Boolean,
    /** The connection presented the scoped agent token (a sandboxed agent). */
    val agent: Boolean = false,
    /** The socket's peer address (no port). */
    val remoteAddress: String? = null,
    /** The request's `X-Forwarded-For` header, verbatim. */
    val forwardedFor: String? = null,
    /** The session this connection drives, if any. */
    val activeSession: String? = null,
    /**
     * PROJECT-ISOLATION-1 (1.42.0): a call confined to this project although it carries no session: a project hook's script runs with
     * owner permissions but only inside the hook's own project. Null = the connection's own scope (a session's project, or the owner).
     */
    val project: String? = null,
)

// ---- data contract ------------------------------------------------------------------------------------------------

/** [description] (additive, XPROJ-1): the project's one-line description, its data key `project.description` ("" = none). */
data class ProjectInfo(val id: String, val name: String, val directory: String?, val description: String = "")

interface Projects {
    fun list(): List<ProjectInfo>
    fun create(name: String, goal: String, directory: String): ProjectInfo
}

data class SessionInfo(
    val id: String,
    val projectId: String?,
    val role: String?,
    val agentId: String?,
    val global: Boolean,
    val spawnedAt: Long,
    /** The session state's name (`IDLE`, `BUSY`, `STARTING`, `WAITING_INPUT`, `FAILED`, `ORPHANED`, ...). */
    val state: String,
    val neverRun: Boolean,
    val providerSessionId: String?,
    val runCount: Int,
    /** CLI-PTY-3 (additive): the live process's MCP link as its harness reports it (`connected`, `disconnected`, `reconnecting`); null = not reported. */
    val mcp: String? = null,
    /** CLI-PTY-3 (additive): epoch millis of the last change of [mcp]. */
    val mcpChangedAt: Long? = null,
)

interface Sessions {
    fun list(): List<SessionInfo>
    fun get(id: String): SessionInfo?
    /** A new role session bound like a `new` + first prompt would bind it. The spawn gates apply. */
    fun spawn(provider: String, model: String, role: String, projectId: String): SessionInfo
    /** Runs [prompt] on session [id]; a never-run session gets its first turn composed (persona, identity, notes). */
    fun resume(id: String, prompt: String): SessionInfo
    /** Back to never-run (the next turn is a first turn again). */
    fun restart(id: String)

    /**
     * SCRIPT-VIS-1b (additive): marks session [id] as being worked on by automation (a script hook) until cleared with null. Display only:
     * the session shows [AutomationMark] on its record and the UI groups it under Running; its state (BUSY/IDLE) is not touched. Memory only.
     */
    fun setAutomation(id: String, mark: AutomationMark?) {}

    /** SCRIPT-SESSION-1 (additive): the session's state and last-activity facts, or null for an unknown session. */
    fun status(id: String): SessionStatus? = null
    /** SCRIPT-SESSION-1 (additive): the last [max] chat lines (assistant text, `[tool]` and `[error]` lines), oldest first; capped by the daemon. */
    fun recentLines(id: String, max: Int): List<String> = emptyList()
    /** SCRIPT-SESSION-1 (additive): stops the turn in flight now. False when the session is unknown or has no turn running. */
    fun interrupt(id: String): Boolean = false
    /** SCRIPT-SESSION-1 (additive): the daemon's built-in notice [name] (or `*`) sends nothing for [ms] millis, for [sessionId] or (null) any session. */
    fun suppressNotice(name: String, sessionId: String?, ms: Long) {}
    /**
     * CLI-PTY-3 (additive): bring session [id]'s MCP servers back without losing the session: reconnect inside the live process, else restart
     * it so the next turn resumes the same conversation. A turn in flight is refused unless [force]. Returns one line saying what happened;
     * throws [IllegalArgumentException] when refused. The daemon calls this nowhere on its own: a script or tool decides when.
     */
    fun reconnectMcp(id: String, force: Boolean = false): String = throw UnsupportedOperationException("this daemon cannot reconnect MCP servers")

    /** CHAT-QUEUE-1 (1.38.0, additive): the messages the daemon holds for session [id]'s next turn, oldest first (empty = none or unknown). */
    fun queued(id: String): List<QueuedMessage> = emptyList()
    /**
     * CHAT-QUEUE-1 (1.38.0, additive): hold [text] for session [id] until its turn ends; a session at rest takes it as a turn now (null is
     * returned: nothing was held). Throws [IllegalArgumentException] when refused (unknown session, blank text, the queue is full).
     */
    fun queueAdd(id: String, text: String): QueuedMessage? = throw UnsupportedOperationException("this daemon holds no message queue")
    /** CHAT-QUEUE-1 (1.38.0, additive): drop the held message [itemId]; it is never delivered. Throws [IllegalArgumentException] for an unknown one. */
    fun queueRemove(id: String, itemId: String): QueuedMessage = throw UnsupportedOperationException("this daemon holds no message queue")
    /**
     * CHAT-QUEUE-1 (1.38.0, additive): deliver the held message [itemId] now: into the running turn when the harness takes input mid-turn
     * (`injected`), as a turn of its own when none runs (`sent as turn`). Throws when refused; the message then stays held.
     */
    fun queueForce(id: String, itemId: String): String = throw UnsupportedOperationException("this daemon holds no message queue")
}

/** CHAT-QUEUE-1: one message held for a busy session: its [id], the [text], when it was queued ([at], epoch millis). */
data class QueuedMessage(val id: String, val text: String, val at: Long)

/** SCRIPT-SESSION-1: [state] is the state name; [lastAction] a short text; the times are epoch millis (null = none yet). */
data class SessionStatus(val id: String, val state: String, val lastAction: String?, val lastOutputAt: Long?, val lastEventAt: Long?, val lastTurnAt: Long? = null)

/** SCRIPT-VIS-1b: which script is running for a session ([hookId] names the hook, [since] is epoch millis). */
data class AutomationMark(val script: String, val hookId: String, val since: Long)

/** The daemon's catalog entry of a frame: its action id, kind (`read|write|destructive|spawn`) and scope (`GLOBAL|PROJECT|SESSION`). */
data class ActionInfo(val id: String, val kind: String, val scope: String)

interface Policy {
    /** The active preset (POLICY-1). */
    fun active(): PolicyPreset
    /** Told after every switch, with the new active preset. */
    fun onChange(listener: (PolicyPreset) -> Unit): AutoCloseable
    /** The catalog's action of [frame] (read from its payload when the type alone does not say), or null. */
    fun actionOf(frame: Frame): ActionInfo?
    /** The active preset's refusal of [frame] by its rules, or null (production refuses nothing). */
    fun refusal(frame: Frame): String?
    /** The active preset's refusal text for a frame of [type]. */
    fun refusalText(type: String): String
    /** The outcome of seating the active preset (null = seated), shown to the owner in the `policy` frame. */
    fun reportSeating(error: String?)
    /**
     * This plugin implements the active preset's `seats` (hook `seats`). A preset with seats that no loaded plugin claims is
     * fail closed by core: every frame but the switch is refused.
     */
    fun claimSeats()
}

interface Events {
    /** Every frame of [type] the daemon broadcasts to its clients (`*` = all); runs under the plugin's limits. */
    fun on(type: String, listener: (Frame) -> Unit): AutoCloseable

    /**
     * DP-4b (1.3.0, additive): the typed ticket events, ordered and at-least-once (see
     * [com.superiorsoftllc.infantry.daemon.api.board.TicketEvents]). [consumer] names this subscription's cursor within the plugin.
     * A community plugin needs the `tickets.read` grant (a missing grant throws, and the host disables the plugin).
     */
    fun onTicket(consumer: String, listener: (com.superiorsoftllc.infantry.daemon.api.board.TicketEvent) -> Unit): AutoCloseable = AutoCloseable {}

    /** DP-7d (1.18.0, additive): told with the session id once per session that is killed or removed (a plugin drops that session's own data). */
    fun onSessionRemoved(listener: (sessionId: String) -> Unit): AutoCloseable = AutoCloseable {}

    /**
     * ATTN-2a (1.24.0, additive): pushes one frame of [type] with payload [data] to every connected client (the way core pushes
     * `attention` / `question`). Official / core only; a community plugin's call throws [IllegalStateException]. Best effort: a
     * daemon without clients ignores it.
     */
    fun broadcast(type: String, data: String) {}

    /**
     * SCRIPT-3 (1.26.0, additive): publishes the named event [name] (dotted, e.g. `ticket.moved`) with a JSON [payload]. [causeId] and
     * [depth] are carried to every subscriber unchanged; core enforces no policy on them. A new event with no [causeId] gets its own
     * id as the cause. The source defaults to the calling plugin's id. Returns the event as delivered.
     */
    fun publish(name: String, payload: String = "{}", causeId: String? = null, depth: Int = 0): NamedEvent =
        throw IllegalStateException("this daemon has no named events")

    /**
     * SCRIPT-3 (1.26.0, additive): subscribes to named events. [pattern] is an exact name (`ticket.moved`), a prefix ending in `.*`
     * or `.` (`ticket.*` = every event whose name starts with `ticket.`), or `*` (all). Delivery is off the publishing thread, in publish order.
     */
    fun subscribe(pattern: String, listener: (NamedEvent) -> Unit): AutoCloseable = AutoCloseable {}
}

/**
 * SCRIPT-3 (1.26.0): one named event. [name] is dotted lower-case; [payload] a JSON text; [source] who published it (plugin id,
 * `session:<id>`, `cli` or `daemon`); [id] is unique per event; [causeId] names the event chain this one belongs to (the root
 * event's id) and [depth] counts the hops from the root. Core only carries [causeId] and [depth]; a loop guard is a plugin's job.
 */
data class NamedEvent(
    val name: String,
    val payload: String = "{}",
    val source: String = "",
    val id: String = "",
    val causeId: String = "",
    val depth: Int = 0,
    val at: Long = 0,
)

// ---- tools ----------------------------------------------------------------------------------------------------------

/**
 * A daemon tool: a named callable with a JSON schema, listed to clients (`plugin_tools`) and to agents (`infantry mcp serve`).
 * Its policy action is the one module.json declares for [name]; a call is refused when the active preset refuses that action.
 */
interface DaemonTool {
    /** Declared in module.json `daemon.tools`; core/official may use `infantry_`, community only its own vendor prefix. */
    val name: String
    val description: String
    /** A JSON Schema object (`{"type":"object","properties":{...}}`). */
    val inputSchema: JsonObject
    /** MCP tool annotations (`readOnlyHint`, ...); empty = none. */
    val annotations: JsonObject get() = JsonObject(emptyMap())
    /**
     * How long one call may run, in ms (RUNTIME-LOAD-2); null = the module's / default tool timeout. The daemon caps it at
     * limits.plugins.toolMaxTimeoutMs, and the MCP client waits a quarter longer than the effective value.
     */
    val timeoutMs: Long? get() = null
    /**
     * RUNTIME-LOAD-4 (1.4.0, additive): the argument keys (arrays of paths) that name files on the CALLER's machine; the client that
     * holds them sends their bytes inline ([LocalFiles]). Empty = none.
     */
    val localFiles: List<String> get() = emptyList()
    fun call(call: ToolCall): ToolResult
}

interface ToolCall {
    val arguments: JsonObject
    val caller: Caller
    /**
     * RUNTIME-LOAD-3 (1.1.0, additive): the session (`INFANTRY_SESSION_ID`) and role (`INFANTRY_ROLE`) of the MCP process that made the
     * call, as it reported them; null = none, i.e. a person running `infantry mcp serve`. A client's own claim, like the environment
     * it read: use it only to narrow what a call may do (the roles tools' agent-caller rule), never to widen it.
     */
    val clientSessionId: String? get() = null
    val clientRole: String? get() = null
    /**
     * RUNTIME-LOAD-4 (1.4.0, additive): the project the MCP process works in when a call names none (its `--project`, its
     * `.infantry/mcp.json`, else its `INFANTRY_PROJECT_ID`); null = none. A client's own default, never a grant: the host still
     * scopes whatever a tool does with it.
     */
    val clientProjectId: String? get() = null
    /**
     * RUNTIME-LOAD-7 (1.9.0, additive): the calling MCP process's own guardrail values (`--max-depth`, `--max-spawns`, `--mcp-depth`,
     * one `clientKey` per process); null = it sent none. A claim of the client: hand it to `api.spawns`, which only narrows with it.
     */
    val clientNarrowing: com.superiorsoftllc.infantry.daemon.api.spawns.SpawnNarrowing? get() = null
    /**
     * Sends frame [type] on the CALLER's own connection, through every gate the caller's own frames pass, and returns the
     * answer to it (the frame carrying its request id). An error answer comes back as a frame of type `error`.
     */
    fun request(type: String, data: String = "", sessionId: String? = null, timeoutMs: Long = 45_000): Frame
}

data class ToolResult(val text: String, val isError: Boolean = false)

interface Tools {
    fun register(tool: DaemonTool)

    /**
     * SCRIPT-4 (1.27.0, additive): every tool the daemon serves to agents right now (the live registry, built-ins included), by name.
     * Generated from the registry, never listed by hand: a tool registered later shows up here at once.
     */
    fun catalog(): List<ToolInfo> = emptyList()

    /**
     * SCRIPT-4 (1.27.0, additive): runs tool [name] exactly as an agent's `plugin_tool_call` would, for [as]: the same policy action
     * gate and plugin frame gates, the tool's own timeout and the same result. No role check here (D18). A tool that needs a frame
     * on the caller's own connection answers an error. Official / core only.
     */
    fun invoke(name: String, arguments: JsonObject, `as`: ToolInvoker): ToolResult = ToolResult("this daemon has no tool registry", isError = true)
}

/** One entry of [Tools.catalog]. */
data class ToolInfo(val name: String, val description: String, val inputSchema: JsonObject, val readOnly: Boolean = false)

/** Who a [Tools.invoke] call runs as: the identity of the agent on whose behalf it runs, and a free label for logs (`script:<name>`). */
data class ToolInvoker(val caller: Caller, val sessionId: String? = null, val role: String? = null, val projectId: String? = null, val label: String = "")

// ---- frames and routes ----------------------------------------------------------------------------------------------

interface Frames {
    /** Answers client frames of [type] (declared in `daemon.frames`); return null for no direct answer. */
    fun handle(type: String, handler: (Frame, Caller) -> Frame?)

    /**
     * DP-5h-2 (1.11.0, additive): register the frame table of one owner (the DP-5d board frames) with typed envelopes: the same type
     * strings, the same gates (policy, owner, plugin) in the same order, and the `direct` flag (the request carried a `requestId`,
     * BUG-MCP-RACE-1) the table's handlers take. With no plugin registering a board family type the daemon answers
     * `<type> refused: this daemon has no board`, as before.
     */
    val table: FrameBinding get() = FrameBinding { types, handler -> for (t in types) handle(t) { f, _ -> handler(f, f.requestId != null) } }
}

/** DP-5h-2: register a handler for every one of [types]; [handler] gets the frame and whether it is `direct`. */
fun interface FrameBinding {
    fun register(types: List<String>, handler: (frame: Frame, direct: Boolean) -> Frame?)
    fun register(vararg types: String, handler: (frame: Frame, direct: Boolean) -> Frame?) = register(types.toList(), handler)
}

data class HttpCall(
    val method: String,
    val path: String,
    val query: Map<String, String> = emptyMap(),
    val headers: Map<String, String> = emptyMap(),
    val body: String = "",
    val remoteAddress: String? = null,
    /** 1.29.0 (TRIG-2a): the values of a template route's `{name}` segments (`POST /hooks/{id}` -> `id`); empty on an exact route. */
    val pathParams: Map<String, String> = emptyMap(),
)

data class HttpReply(
    val status: Int = 200,
    val body: String = "",
    val contentType: String = "text/plain; charset=utf-8",
    val headers: Map<String, String> = emptyMap(),
)

interface Routes {
    /**
     * Answers `[method] [path]`. A relative [path] (`hello`) answers at `/plugins/<moduleId>/hello`; an absolute one must be
     * declared in `daemon.routes` (official/core only); an absolute path may hold `{name}` segments (a template, matched per segment,
     * values in [HttpCall.pathParams]; an exact route wins). Behind the access policy unless declared in `daemon.publicRoutes`.
     */
    fun handle(method: String, path: String, handler: (HttpCall) -> HttpReply)
}

// ---- hooks ----------------------------------------------------------------------------------------------------------

/** Every client frame, after FrameGate and the active preset's own refusal. Null = pass. Fail closed. */
fun interface FrameGateHook {
    fun refusal(frame: Frame, caller: Caller): String?
}

/** Every HTTP request before routing. Null = pass, else the 403 text. Fail closed. */
fun interface HttpGateHook {
    fun refusal(method: String, path: String, webAsset: Boolean): String?
}

/** Extra guest bearer tokens admitted NOW (each 32+ chars, never the owner/agent token). Official/core only. Fail open. */
fun interface AdmissionHook {
    fun guestTokens(): List<String>
}

/** A mail send (the `mail_send` frame and `POST /mail/send`). Null = pass. Fail closed. */
fun interface MailGateHook {
    fun refusal(from: String, to: String, allowCrossProject: Boolean): String?
}

/** 1.30.0 (MAIL-HOOK-1): a mail accepted for delivery, shown BEFORE the recipient is woken. [recipientSessionId] / [recipientRole] are null when the address does not resolve to a live session. */
data class IncomingMail(
    val id: String, val from: String, val to: String, val recipientSessionId: String?, val recipientRole: String?,
    val subject: String, val body: String, val threadId: String?, val projectId: String? = null,
    /** XPROJ-1 (additive): the sender's project (id, name) when the sender is a session of ANOTHER project than the recipient's; null otherwise. */
    val fromProjectId: String? = null, val fromProjectName: String? = null,
)

/** 1.30.0: what a hook decided for one mail. [handled] = marked read for the recipient, nobody woken. [text] (if not handled) replaces the delivered text for that mail. */
data class MailVerdict(val handled: Boolean = false, val text: String? = null)

/** 1.30.0 (MAIL-HOOK-1): runs before delivery; null = deliver as today. Fail open (a throwing or slow hook reads as null: a mail is never dropped); official / core only. */
fun interface IncomingMailHook {
    fun incoming(mail: IncomingMail): MailVerdict?

    /**
     * SCRIPT-VIS-1b (additive): the host dropped this hook's answer for mail [mailId] (the call timed out, ran over its CPU limit or threw), so the
     * mail was delivered as if the hook had passed. Lets the hook correct what it recorded about its own verdict. Never throws into the host.
     */
    fun lost(mailId: String, reason: String) {}
}

/** Every new session, whatever path creates it. Null = pass. Fail closed. */
fun interface SpawnGateHook {
    fun refusal(role: String?, projectId: String?): String?
}

/** Extra `ready` keys and the pinned notice. Fail open. */
interface ReadyHook {
    fun fields(): Map<String, String> = emptyMap()
    fun notice(): String? = null
}

/** 1.29.0 (TRIG-2a): extra fields a plugin adds to the daemon health/status answer (`pluginStatus`). Fail open; official / core only. */
fun interface StatusHook {
    fun fields(): Map<String, String>
}

/** A client turn that reached an agent (`prompt`, `resume`, `mail_send`). Fail open. */
fun interface TurnHook {
    fun accepted(frame: Frame, caller: Caller)
}

/** A session event: [kind] `created`, `state` or `closed`. Fail open. */
fun interface SessionHook {
    fun changed(kind: String, session: SessionInfo)
}

/** POLICY-1's provider, through the plugin mechanism. Fail open (no presets). */
fun interface PresetProviderHook {
    fun presets(): List<PolicyPreset>
}

/**
 * DP-10b (1.22.0, additive): the stored "allow always" rules of tool approval (policy data a plugin owns). Core asks [match] before it
 * opens an approval prompt and calls [remember] when the owner answers allow_always. No hook registered = no stored rules: every
 * prompt asks and allow_always lasts the session. Fail open: a throwing hook reads as no rule.
 */
interface ApprovalRulesHook {
    /** The rule of [projectId] that allows [tool] called with [input], or null. */
    fun match(projectId: String?, tool: String, input: String): ApprovalRuleMatch?

    /** Store the rule allow_always makes for [tool] / [input] in [projectId]; false when no rule can be made or kept (core then grants the tool for the session). */
    fun remember(projectId: String?, tool: String, input: String): Boolean
}

/** The rule that matched: its [tool] and, for a shell tool, the command [prefix]. */
data class ApprovalRuleMatch(val tool: String, val prefix: String? = null)

/**
 * ATTN-2a (1.24.0, additive): the frames a plugin replays to ONE client right after it connects (e.g. the open owner questions).
 * [sessionId] is the connection's session when it has one. Fail open: a throwing hook sends nothing.
 */
fun interface ConnectedHook {
    fun frames(sessionId: String?): List<Frame>
}

interface Hooks {
    /** ATTN-2a (1.24.0): offer the connect-replay hook; official / core only. Default: ignored. */
    fun connected(hook: ConnectedHook) {}
    /** TRIG-2a (1.29.0): contribute fields to health/status; official / core only. Default: ignored. */
    fun status(hook: StatusHook) {}
    /** DP-10b (1.22.0): offer the approval-rule store; official/core only. Default: ignored. */
    fun approvalRules(hook: ApprovalRulesHook) {}

    fun frameGate(hook: FrameGateHook)
    fun httpGate(hook: HttpGateHook)
    fun admission(hook: AdmissionHook)
    fun mailGate(hook: MailGateHook)
    /** MAIL-HOOK-1 (1.30.0): offer the pre-delivery mail hook; official / core only. Default: ignored. */
    fun incomingMail(hook: IncomingMailHook) {}
    fun spawnGate(hook: SpawnGateHook)
    fun ready(hook: ReadyHook)
    fun turns(hook: TurnHook)
    fun sessions(hook: SessionHook)
    fun policyProvider(hook: PresetProviderHook)

    /** ORCH-1: offer a binding for `orchestrator.provider = <providerId>`; the daemon selects it at start when that setting names it. */
    fun orchestratorBinding(providerId: String, binding: com.superiorsoftllc.infantry.daemon.api.orchestrator.OrchestratorBinding)

    /**
     * RUNNER-POOL-2 (1.43.0, additive): offer a runner binding under [bindingId] (declare `runnerBinding` in `daemon.hooks`); the daemon
     * keeps it on the runner pool at start. Placement onto it arrives with RUNNER-POOL-3; until then it changes nothing. Default: ignored.
     */
    fun runnerBinding(bindingId: String, binding: com.superiorsoftllc.infantry.daemon.api.orchestrator.RunnerBinding) {}

    /**
     * RUNNER-POOL-6a (1.44.0, additive): offer the runner link's transport under [transportId] (declare `runnerLinkTransport` in
     * `daemon.hooks`); the daemon serves and calls the link through the first one offered. Default: ignored.
     */
    fun runnerLinkTransport(transportId: String, transport: com.superiorsoftllc.infantry.daemon.api.runners.RunnerLinkTransport) {}
}
