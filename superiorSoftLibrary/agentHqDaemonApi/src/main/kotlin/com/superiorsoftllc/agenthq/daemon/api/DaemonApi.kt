package com.superiorsoftllc.agenthq.daemon.api

import com.superiorsoftllc.agenthq.plugin.api.PolicyPreset
import kotlinx.serialization.json.JsonObject
import java.io.File

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

    val projects: Projects
    val sessions: Sessions
    val policy: Policy
    val events: Events
    val tools: Tools
    val frames: Frames
    val routes: Routes
    val hooks: Hooks

    /** DP-4a (1.2.0, additive): tickets, epics and the board (docs/DAEMON-BOARD-API.md). A daemon without a board answers Unavailable. */
    val tickets: com.superiorsoftllc.agenthq.daemon.api.board.Tickets get() = com.superiorsoftllc.agenthq.daemon.api.board.NoBoard.tickets
    val epics: com.superiorsoftllc.agenthq.daemon.api.board.Epics get() = com.superiorsoftllc.agenthq.daemon.api.board.NoBoard.epics
    /** DP-5h-3a (1.12.0, additive): the host side of a board plugin's board (settings, project tables, pacing, push). A daemon without one answers [com.superiorsoftllc.agenthq.daemon.api.board.NoBoardRuntime]. */
    val boardRuntime: com.superiorsoftllc.agenthq.daemon.api.board.BoardRuntime get() = com.superiorsoftllc.agenthq.daemon.api.board.NoBoardRuntime
    val board: com.superiorsoftllc.agenthq.daemon.api.board.Board get() = com.superiorsoftllc.agenthq.daemon.api.board.NoBoard.board

    /** AGENT-REGISTRY-1 (1.5.0, additive): the agent registry (docs/AGENT-REGISTRY.md). A daemon without one lists nothing. */
    val agents: com.superiorsoftllc.agenthq.daemon.api.agents.Agents get() = com.superiorsoftllc.agenthq.daemon.api.agents.NoAgents

    /**
     * RUNTIME-LOAD-5 (1.6.0, additive): the sessions READ model, host-scoped (docs/RUNTIME-LOAD.md section 11). A daemon without
     * one answers Unavailable. Community plugins need the grants `sessions.read` / `sessions.transcript`.
     */
    val sessionReads: com.superiorsoftllc.agenthq.daemon.api.sessions.SessionReads get() = com.superiorsoftllc.agenthq.daemon.api.sessions.NoSessionReads

    /**
     * RUNTIME-LOAD-6 (1.7.0, additive): the HOST spawn service (docs/RUNTIME-LOAD.md section 12): spawn, ticket start and kill
     * guardrails keyed by the calling session, so every client and plugin is held to one state. A daemon without one answers
     * Unavailable. Community plugins need the grant `sessions.spawn`.
     */
    val spawns: com.superiorsoftllc.agenthq.daemon.api.spawns.Spawns get() = com.superiorsoftllc.agenthq.daemon.api.spawns.NoSpawns

    /**
     * STACK-NEUTRAL-2 (1.8.0, additive): the host process services (docs/STACK-NEUTRAL.md section 6): the OS process table, the
     * start-instant-checked kill, the session roots, the session-closed event and the gate lock. Official / core tier; a community
     * plugin needs the grant `daemon.processes` and never gets `kill`. A daemon without one lists nothing and kills nothing.
     */
    val processes: com.superiorsoftllc.agenthq.daemon.api.processes.Processes get() = com.superiorsoftllc.agenthq.daemon.api.processes.NoProcesses

    /** STACK-NEUTRAL-2 (1.8.0, additive): contribute environment variables to every session launch. Fail open. */
    val launchEnv: com.superiorsoftllc.agenthq.daemon.api.processes.LaunchEnv get() = com.superiorsoftllc.agenthq.daemon.api.processes.NoLaunchEnv

    /** STACK-NEUTRAL-2 (1.8.0, additive): read access to `limits.*`, `watch.*` and `processes.*` settings. */
    val settings: com.superiorsoftllc.agenthq.daemon.api.processes.PluginSettings get() = com.superiorsoftllc.agenthq.daemon.api.processes.NoPluginSettings

    /**
     * PACE-TREND-1 (1.10.0, additive): the plan meters, read-only (daemon.api.usage.MeterReading). A community plugin needs the
     * grant `usage.read`. A daemon without meters reads nothing.
     */
    val meters: com.superiorsoftllc.agenthq.daemon.api.usage.Meters get() = com.superiorsoftllc.agenthq.daemon.api.usage.NoMeters

    /** DAEMON-STORAGE-1 (1.15.0, additive): plugin-owned tables over the daemon's SQLite (daemon.api.storage). Community needs the grant `storage`. */
    val storage: com.superiorsoftllc.agenthq.daemon.api.storage.PluginStorage get() = com.superiorsoftllc.agenthq.daemon.api.storage.NoPluginStorage

    /** AR-5 (1.16.0, additive): the typed optional service registry (provide / find by interface). Community needs the grant `services.provide` to publish. */
    val services: ServicesApi get() = NoServices

    /** DP-7e (1.19.0, additive): the mail port (notify one session or every live session of a role). Community needs the grant `sessions.notify`. */
    val sessionNotifier: com.superiorsoftllc.agenthq.daemon.api.sessions.SessionNotifier get() = com.superiorsoftllc.agenthq.daemon.api.sessions.NoSessionNotifier

    /**
     * DP-10a (1.21.0, additive): the project-scoped file surface `<project directory>/.agenthq/` (daemon.api.files.ProjectFiles).
     * Official / core tier, or a community module with the grant `files`; an unknown project or one without a directory is refused
     * with [IllegalArgumentException]. A daemon without one refuses with [IllegalStateException].
     */
    fun files(projectId: String): com.superiorsoftllc.agenthq.daemon.api.files.ProjectFiles = com.superiorsoftllc.agenthq.daemon.api.files.NoProjectFiles

    companion object {
        // 1.24.0 (ATTN-2a, additive): `events.broadcast`, `hooks.connected` (ConnectedHook), daemon.api.attention.QuestionsOpen. The session-closed reason is `processes.onSessionClosed` (1.8.0).
        // 1.23.0 (TICKET-COMMENTS-2, additive): no new member; Infantry_Board's daemon half gains agenthq_ticket_comment / agenthq_ticket_comments,
        // notify on ticket_create / ticket_update and createdBy / notify / openComments on ticket_get, over `storage`, `agents`, `sessionNotifier`.
        // 1.22.0 (DP-10b, additive): `hooks.approvalRules` (ApprovalRulesHook): the stored "allow always" rules of tool approval.
        // 1.21.0 (DP-10a, additive): `files(projectId)` (daemon.api.files.ProjectFiles), scoped to `<project>/.agenthq/`.
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
        // 1.14.0 (DP-5h-3e-b, additive): BoardRuntime.attachCore / detachCore and BoardCoreAccess (core consumers reach the plugin-built board).
        const val VERSION = "1.24.0"
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
)

// ---- data contract ------------------------------------------------------------------------------------------------

data class ProjectInfo(val id: String, val name: String, val directory: String?)

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
}

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
     * [com.superiorsoftllc.agenthq.daemon.api.board.TicketEvents]). [consumer] names this subscription's cursor within the plugin.
     * A community plugin needs the `tickets.read` grant (a missing grant throws, and the host disables the plugin).
     */
    fun onTicket(consumer: String, listener: (com.superiorsoftllc.agenthq.daemon.api.board.TicketEvent) -> Unit): AutoCloseable = AutoCloseable {}

    /** DP-7d (1.18.0, additive): told with the session id once per session that is killed or removed (a plugin drops that session's own data). */
    fun onSessionRemoved(listener: (sessionId: String) -> Unit): AutoCloseable = AutoCloseable {}

    /**
     * ATTN-2a (1.24.0, additive): pushes one frame of [type] with payload [data] to every connected client (the way core pushes
     * `attention` / `question`). Official / core only; a community plugin's call throws [IllegalStateException]. Best effort: a
     * daemon without clients ignores it.
     */
    fun broadcast(type: String, data: String) {}
}

// ---- tools ----------------------------------------------------------------------------------------------------------

/**
 * A daemon tool: a named callable with a JSON schema, listed to clients (`plugin_tools`) and to agents (`agenthq mcp serve`).
 * Its policy action is the one module.json declares for [name]; a call is refused when the active preset refuses that action.
 */
interface DaemonTool {
    /** Declared in module.json `daemon.tools`; core/official may use `agenthq_`, community only its own vendor prefix. */
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
     * RUNTIME-LOAD-3 (1.1.0, additive): the session (`AGENTHQ_SESSION_ID`) and role (`AGENTHQ_ROLE`) of the MCP process that made the
     * call, as it reported them; null = none, i.e. a person running `agenthq mcp serve`. A client's own claim, like the environment
     * it read: use it only to narrow what a call may do (the roles tools' agent-caller rule), never to widen it.
     */
    val clientSessionId: String? get() = null
    val clientRole: String? get() = null
    /**
     * RUNTIME-LOAD-4 (1.4.0, additive): the project the MCP process works in when a call names none (its `--project`, its
     * `.agenthq/mcp.json`, else its `AGENTHQ_PROJECT_ID`); null = none. A client's own default, never a grant: the host still
     * scopes whatever a tool does with it.
     */
    val clientProjectId: String? get() = null
    /**
     * RUNTIME-LOAD-7 (1.9.0, additive): the calling MCP process's own guardrail values (`--max-depth`, `--max-spawns`, `--mcp-depth`,
     * one `clientKey` per process); null = it sent none. A claim of the client: hand it to `api.spawns`, which only narrows with it.
     */
    val clientNarrowing: com.superiorsoftllc.agenthq.daemon.api.spawns.SpawnNarrowing? get() = null
    /**
     * Sends frame [type] on the CALLER's own connection, through every gate the caller's own frames pass, and returns the
     * answer to it (the frame carrying its request id). An error answer comes back as a frame of type `error`.
     */
    fun request(type: String, data: String = "", sessionId: String? = null, timeoutMs: Long = 45_000): Frame
}

data class ToolResult(val text: String, val isError: Boolean = false)

interface Tools {
    fun register(tool: DaemonTool)
}

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
     * declared in `daemon.routes` (official/core only). Behind the access policy unless declared in `daemon.publicRoutes`.
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

/** Every new session, whatever path creates it. Null = pass. Fail closed. */
fun interface SpawnGateHook {
    fun refusal(role: String?, projectId: String?): String?
}

/** Extra `ready` keys and the pinned notice. Fail open. */
interface ReadyHook {
    fun fields(): Map<String, String> = emptyMap()
    fun notice(): String? = null
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
    /** DP-10b (1.22.0): offer the approval-rule store; official/core only. Default: ignored. */
    fun approvalRules(hook: ApprovalRulesHook) {}

    fun frameGate(hook: FrameGateHook)
    fun httpGate(hook: HttpGateHook)
    fun admission(hook: AdmissionHook)
    fun mailGate(hook: MailGateHook)
    fun spawnGate(hook: SpawnGateHook)
    fun ready(hook: ReadyHook)
    fun turns(hook: TurnHook)
    fun sessions(hook: SessionHook)
    fun policyProvider(hook: PresetProviderHook)

    /** ORCH-1: offer a binding for `orchestrator.provider = <providerId>`; the daemon selects it at start when that setting names it. */
    fun orchestratorBinding(providerId: String, binding: com.superiorsoftllc.agenthq.daemon.api.orchestrator.OrchestratorBinding)
}
