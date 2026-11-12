// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.daemon.api.spawns

import com.superiorsoftllc.infantry.daemon.api.Caller
import com.superiorsoftllc.infantry.daemon.api.board.TicketInfo
import com.superiorsoftllc.infantry.daemon.api.sessions.SessionView
import com.superiorsoftllc.infantry.daemon.api.sessions.SessionsResult

/**
 * RUNTIME-LOAD-6 (DaemonApi 1.7.0, additive): the HOST spawn service (docs/RUNTIME-LOAD.md section 12).
 *
 * The guardrails of the agent spawn / ticket start / kill tools used to live in each `infantry mcp serve` process (its depth lineage
 * file, the set of sessions it opened, its kill flag). They live here now, keyed by the CALLING session ([Caller.activeSession], which
 * a tool sets from `ToolCall.clientSessionId`), so every client and every plugin is held to ONE state:
 *
 *  - DEPTH: a session's depth is the length of its `spawnerSessionId` chain (persisted with the session, so it survives a restart). A
 *    session nobody spawned from inside a session is a root (depth 0); the caller's spawn sits at the caller's depth + 1, refused above
 *    `limits.concurrency.maxDepth`.
 *  - CONCURRENCY: the caller's open spawns are the sessions whose spawner is the caller and that are running or not ended, refused at
 *    `limits.concurrency.mcpMaxSpawns`. An owner connection with no session has no spawner id: its spawns are counted by the client's
 *    [SpawnNarrowing.clientKey] (one per `infantry mcp serve` process, in memory), as the process counted them before.
 *  - KILL SCOPE: [mayKill], the rule of contract 1.114.0 (own spawns; a pm its own project; never itself; an owner without a session any).
 *
 * The client's own values ([SpawnNarrowing]) only NARROW: the limits are the minimum of the daemon setting and the client's, the depth
 * the maximum of the host's and the client's. Nothing a plugin passes can widen them.
 *
 * [spawn] and [startTicket] check and act under ONE lock, so two calls cannot both take the last slot. The daemon's pod guard (BUG-G2d,
 * `limits.concurrency.maxPerPod`) applies under them as it does to every session-originated spawn, ticket starts included.
 *
 * Refusals ([SessionsResult.Refused]) carry the bare reason (`depth limit. This session runs at depth ...`, the texts the CLI's MCP
 * server worded with "this MCP server"); a tool prefixes `<tool> refused: `. A created session whose task the daemon refused is
 * Ok with [Spawned.taskSent] false and the reason in [Spawned.taskRefused]. Bundled / official plugins are trusted; a community plugin needs [GRANT_SPAWNS] (default deny).
 */
interface Spawns {
    /**
     * DP-5h-2 (1.11.0, additive): the charge a session-originated ticket start pays (the SpawnGuard behaviour behind a minimal
     * port). A daemon without one charges nothing ([NoSpawnCharge]).
     */
    val charge: SpawnCharge get() = NoSpawnCharge

    /** The limits [by] would be checked against now, or Refused (depth / concurrency) for a spawn of [kind]. Reads only. */
    fun admit(by: Caller, narrowing: SpawnNarrowing = SpawnNarrowing.NONE, kind: SpawnKind = SpawnKind.SESSION): SessionsResult<Admission>

    /** A new session for [request], created as `new` creates one, its spawner the caller: admit + create + record under one lock. */
    fun spawn(request: SpawnRequest, by: Caller, narrowing: SpawnNarrowing = SpawnNarrowing.NONE): SessionsResult<Spawned>

    /**
     * Start ONE board ticket ([ref]: full id or a unique prefix / suffix, in the caller's scope) through the board's own start, the
     * caller recorded as its starter and its worker's spawner. A start that would run a worker is admitted first, under the same lock;
     * a ticket already in progress with a worker that can carry it runs nothing and passes.
     */
    fun startTicket(ref: String, by: Caller, narrowing: SpawnNarrowing = SpawnNarrowing.NONE): SessionsResult<TicketStartOutcome>

    /** The ONE session [ref] names (full id, else a unique prefix) when [by] may kill (or force-remove) it; Refused with the rule's why. */
    fun mayKill(ref: String, by: Caller): SessionsResult<SessionView>

    /** The ONE session [ref] names (full id, else a unique prefix), across the daemon: what prompt / restart / remove act on. */
    fun target(ref: String, by: Caller): SessionsResult<SessionView>
}

/** What a spawn is for: the refusal texts name the new session, or the ticket's worker. */
enum class SpawnKind { SESSION, TICKET_WORKER }

/**
 * The calling client's own guardrail values (`infantry mcp serve --max-depth / --max-spawns / --mcp-depth`, `.infantry/mcp.json`). A
 * claim of the client: the host only ever narrows with it (min of the limits, max of the depth). [clientKey] names one client process,
 * so an owner connection with no session still has its open spawns counted.
 */
data class SpawnNarrowing(
    val maxDepth: Int? = null,
    val maxSpawns: Int? = null,
    val depth: Int? = null,
    val clientKey: String? = null,
) {
    companion object {
        val NONE = SpawnNarrowing()
    }
}

/** The limits in force for one call: the depth the new session would get, the caller's open spawns, and the two maxima. */
data class Admission(
    val depth: Int,
    val maxDepth: Int,
    /** The caller's spawns that hold a slot now. */
    val open: List<String>,
    val maxSpawns: Int,
)

/** A session to create, as the `new` frame's options. */
data class SpawnRequest(
    val role: String? = null,
    val agentId: String? = null,
    val projectId: String? = null,
    /** Tokens; null = none. */
    val contextLimit: Int? = null,
    /** EFFORT-1: the session's reasoning effort level (low / medium / high / xhigh / max); null = the harness default. */
    val effort: String? = null,
    /** A first prompt, run as the session's first turn (persona, identity and notes around it); null = none. */
    val task: String? = null,
    /** RUNNER-AGENT-1: where the session runs (`local` | a runner name | `any`), over the agent profile's runner; null = unset. */
    val runner: String? = null,
    /**
     * SPAWN-PARAMS-1 (1.56.0, additive): the harness (provider id) and model the session runs, as the app's Start box picks them
     * (`new` frame `provider` / `model`); null = the agent profile / role binding / routing rule, as for the app.
     */
    val provider: String? = null,
    val model: String? = null,
    /** SPAWN-PARAMS-1 (1.56.0, additive): the session's own Tool approval (`ask` | `deny` | `allowEdits` | `allowAll`); null = the global setting. */
    val toolApproval: String? = null,
)

/** The session [spawn] created, at [depth], and the caller's open spawns counting it. */
data class Spawned(val session: SessionView, val depth: Int, val openSpawns: Int, val maxSpawns: Int, val taskSent: Boolean = false, val taskRefused: String? = null)

/** What a ticket start did. [outcome] is one of [STARTED], [ALREADY_IN_PROGRESS], [QUEUED], [HELD], [REFUSED]. */
data class TicketStartOutcome(
    val outcome: String,
    val ticket: TicketInfo,
    /** The ticket's worker as the daemon lists it (started, or already running it); null when none. */
    val worker: SessionView? = null,
    /** The session the daemon recorded as the starter (the caller's own, when the daemon knows it). */
    val startedBy: String? = null,
    /** A launch warning (advice; the run started anyway). */
    val warning: String? = null,
    /** What the board cut the worker's branch from (`cut from <ref> <sha>`), when it cut one. */
    val note: String? = null,
    /** Held: the prerequisites; refused: why. */
    val detail: String? = null,
    /** Queued: the lane every agent of which is busy. */
    val lane: String? = null,
    val depth: Int = 0,
    val openSpawns: Int = 0,
    val maxSpawns: Int = 0,
) {
    companion object {
        const val STARTED = "started"
        const val ALREADY_IN_PROGRESS = "already_in_progress"
        const val QUEUED = "queued"
        const val HELD = "held"
        const val REFUSED = "refused"
    }
}

/** Spawn, start tickets and kill through [Spawns]. */
const val GRANT_SPAWNS = "sessions.spawn"

/** A daemon without a spawn service: every call Unavailable. */
object NoSpawns : Spawns {
    private val none = SessionsResult.Unavailable("this daemon has no spawn service")
    override fun admit(by: Caller, narrowing: SpawnNarrowing, kind: SpawnKind) = none
    override fun spawn(request: SpawnRequest, by: Caller, narrowing: SpawnNarrowing) = none
    override fun startTicket(ref: String, by: Caller, narrowing: SpawnNarrowing) = none
    override fun mayKill(ref: String, by: Caller) = none
    override fun target(ref: String, by: Caller) = none
}

/**
 * DP-5h-2 (DaemonApi 1.11.0, additive): the spawn guard's admit / charge semantics for a plugin that starts workers itself (a board
 * moving a ticket to In Progress). [admitIf] checks, then runs [create] under the guard's lock, so the slot checked is the slot
 * taken; the guard's refusal (depth limit, pod cap) is thrown as [IllegalArgumentException] subtype and nothing is created.
 */
interface SpawnCharge {
    /** Whether [sessionId] is a session this daemon holds (only such an origin is charged). */
    fun isSession(sessionId: String): Boolean

    /**
     * When [charged] and [origin] is a held session: check depth and pod cap, else throw. Then run [create] under the lock with
     * `placed` true when the start is charged to [origin]'s pod (the worker must then be [record]ed).
     */
    fun <T> admitIf(origin: String?, charged: Boolean, create: (placed: Boolean) -> T): T

    /** [sessionId] was started from inside [origin]: it joins [origin]'s pod one level down. */
    fun record(sessionId: String, origin: String?)

    /** Record [spawnerId] as [sessionId]'s spawner (contract 1.114.0's kill rule follows). */
    fun setSpawner(sessionId: String, spawnerId: String?)
}

/** A daemon without a spawn guard: nothing is charged, [create] simply runs. */
object NoSpawnCharge : SpawnCharge {
    override fun isSession(sessionId: String) = false
    override fun <T> admitIf(origin: String?, charged: Boolean, create: (placed: Boolean) -> T): T = create(false)
    override fun record(sessionId: String, origin: String?) {}
    override fun setSpawner(sessionId: String, spawnerId: String?) {}
}
