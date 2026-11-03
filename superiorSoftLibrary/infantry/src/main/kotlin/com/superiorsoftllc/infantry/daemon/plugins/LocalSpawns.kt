package com.superiorsoftllc.infantry.daemon.plugins

import com.superiorsoftllc.infantry.cli.TicketStarter
import com.superiorsoftllc.infantry.cli.mcp.ProjectScope
import com.superiorsoftllc.infantry.daemon.SpawnGuard
import com.superiorsoftllc.infantry.daemon.api.Caller
import com.superiorsoftllc.infantry.daemon.api.board.BoardResult
import com.superiorsoftllc.infantry.daemon.api.board.Tickets
import com.superiorsoftllc.infantry.daemon.api.sessions.SessionView
import com.superiorsoftllc.infantry.daemon.api.sessions.SessionsResult
import com.superiorsoftllc.infantry.daemon.api.spawns.Admission
import com.superiorsoftllc.infantry.daemon.api.spawns.GRANT_SPAWNS
import com.superiorsoftllc.infantry.daemon.api.spawns.SpawnKind
import com.superiorsoftllc.infantry.daemon.api.spawns.SpawnNarrowing
import com.superiorsoftllc.infantry.daemon.api.spawns.SpawnRequest
import com.superiorsoftllc.infantry.daemon.api.spawns.Spawned
import com.superiorsoftllc.infantry.daemon.api.spawns.Spawns
import com.superiorsoftllc.infantry.daemon.api.spawns.TicketStartOutcome
import com.superiorsoftllc.infantry.protocol.Envelope
import com.superiorsoftllc.infantry.protocol.TicketStart
import com.superiorsoftllc.infantry.protocol.TicketStatus
import com.superiorsoftllc.infantry.session.SessionRecord
import com.superiorsoftllc.infantry.session.TicketWorkerHealth
import com.superiorsoftllc.infantry.settings.LimitRegistry
import java.util.concurrent.ConcurrentHashMap

/**
 * RUNTIME-LOAD-6 (docs/RUNTIME-LOAD.md section 12): the LOCAL binding of [Spawns], the guardrails `infantry mcp serve` kept in its
 * own process (`McpGuardrails`, `McpLineage`, deleted in RUNTIME-LOAD-7), now held by the daemon for every client and plugin:
 *
 *  - depth = the caller's place in the [SpawnGuard] lineage (persisted `spawnerSessionId` chain), at least the client's claimed depth;
 *  - open spawns = the sessions whose spawner is the caller and that hold a slot ([SpawnGuard.holds], not killed unless live again);
 *    an owner connection without a session is counted per [SpawnNarrowing.clientKey], in memory;
 *  - limits = `limits.concurrency.maxDepth` / `mcpMaxSpawns`, narrowed (never widened) by the client's values;
 *  - kill scope = contract 1.114.0's rule, as `McpWriteTools.killRefusal` worded it.
 *
 * The caller is the daemon's record of [Caller.activeSession]; fail closed as the read model is (an agent connection without a
 * session, or a session the daemon does not know, is refused). [spawn] and [startTicket] check and act under ONE lock, inside the
 * pod guard's own ([SpawnGuard.admit]; the ticket start's move is charged by `daemon.TicketStartCharge` behind [tickets]).
 */
class LocalSpawns(
    private val sessions: () -> List<SessionRecord>,
    private val guard: SpawnGuard,
    /** A limit's value in force ([Int.MAX_VALUE] = unlimited): `SettingsService.limitCap`. */
    private val limit: (com.superiorsoftllc.infantry.settings.LimitDef) -> Int,
    /** The host-scoped board API: the ticket is found in the caller's scope and started through the board's own (charged) start. */
    private val tickets: () -> Tickets,
    /** Create a session as the `new` frame binds one (`bindSessionOnSpawn`); throws [IllegalArgumentException] on refusal. */
    private val create: (SpawnRequest) -> SessionRecord,
    /** Run [SpawnRequest.task] as the session's first turn; throws on refusal. */
    private val firstTask: (SessionRecord, String) -> Unit,
    private val setSpawner: (sessionId: String, spawnerId: String?) -> Unit,
    /** The active preset's refusal of a frame of this type (`new`, `kill`), or null: each operation is gated like its frame. */
    private val gate: (type: String) -> String? = { null },
) : Spawns {
    private val lock = Any()

    /** DP-5h-2: the guard's admit / charge behind the minimal port. */
    override val charge: com.superiorsoftllc.infantry.daemon.api.spawns.SpawnCharge = object : com.superiorsoftllc.infantry.daemon.api.spawns.SpawnCharge {
        override fun isSession(sessionId: String) = sessions().any { it.id == sessionId }
        override fun <T> admitIf(origin: String?, charged: Boolean, create: (placed: Boolean) -> T): T =
            guard.admitIf(origin, charged) { placement -> create(placement != null) }
        override fun record(sessionId: String, origin: String?) = guard.record(sessionId, origin)
        override fun setSpawner(sessionId: String, spawnerId: String?) = this@LocalSpawns.setSpawner(sessionId, spawnerId)
    }

    /** The spawns of owner connections without a session, per client key (one `infantry mcp serve` process each). */
    private val ownerSpawns = ConcurrentHashMap<String, MutableSet<String>>()

    private class Who(val id: String?, val record: SessionRecord?)

    private fun who(by: Caller, all: List<SessionRecord>, what: String): Pair<Who?, String?> {
        val id = by.activeSession?.trim()?.takeIf { it.isNotEmpty() }
        if (id == null) return if (by.agent && !by.owner) null to "an agent connection needs a session to $what" else Who(null, null) to null
        val record = all.firstOrNull { it.id == id } ?: return null to "session '${id.take(80)}' is unknown to this daemon; it cannot $what"
        return Who(id, record) to null
    }

    override fun admit(by: Caller, narrowing: SpawnNarrowing, kind: SpawnKind): SessionsResult<Admission> {
        val all = sessions()
        val (w, why) = who(by, all, "spawn")
        return if (w == null) SessionsResult.Refused(why!!) else admission(w, narrowing, kind, all)
    }

    override fun spawn(request: SpawnRequest, by: Caller, narrowing: SpawnNarrowing): SessionsResult<Spawned> {
        gate(Envelope.NEW)?.let { return SessionsResult.Refused(it) }
        if (request.role == null && request.agentId == null) return SessionsResult.Refused("a spawn needs a role or an agentId (or both)")
        val (created, admitted) = synchronized(lock) {
            val all = sessions()
            val (w, why) = who(by, all, "spawn")
            if (w == null) return SessionsResult.Refused(why!!)
            val a = when (val r = admission(w, narrowing, SpawnKind.SESSION, all)) {
                is SessionsResult.Ok -> r.value
                else -> return r.map()
            }
            val session = try {
                guard.admit(w.id) { placement ->
                    create(request).also { s ->
                        if (w.id != null) {
                            if (placement != null) guard.record(s.id, w.id)
                            setSpawner(s.id, w.id)
                        } else {
                            ownerSet(narrowing).add(s.id)
                        }
                    }
                }
            } catch (e: IllegalArgumentException) {
                return SessionsResult.Refused(e.message ?: "the spawn was refused")
            }
            session to a
        }
        var taskRefused: String? = null
        request.task?.let { task -> runCatching { firstTask(created, task) }.onFailure { taskRefused = it.message ?: it::class.simpleName } }
        val now = sessions().firstOrNull { it.id == created.id } ?: created
        return SessionsResult.Ok(
            Spawned(SessionViews.of(now), admitted.depth, admitted.open.size + 1, admitted.maxSpawns, request.task != null && taskRefused == null, taskRefused),
        )
    }

    override fun startTicket(ref: String, by: Caller, narrowing: SpawnNarrowing): SessionsResult<TicketStartOutcome> {
        val (w, why) = who(by, sessions(), "start a ticket")
        if (w == null) return SessionsResult.Refused(why!!)
        val ticket = when (val r = tickets().get(ref, by)) {
            is BoardResult.Ok -> r.value
            is BoardResult.Refused -> return SessionsResult.Refused(r.text)
            is BoardResult.Unavailable -> return SessionsResult.Unavailable(r.reason)
        }
        val worker = ticket.sessionId?.let { id -> sessions().firstOrNull { it.id == id } }
        // GAP-006b: an in-progress ticket whose worker cannot carry it is started again, so it is held to the limits too.
        val runsWorker = ticket.status != TicketStatus.IN_PROGRESS || TicketWorkerHealth.problem(worker, ticket.sessionId) != null
        if (!runsWorker) return SessionsResult.Ok(TicketStartOutcome(TicketStartOutcome.ALREADY_IN_PROGRESS, ticket, worker?.let(SessionViews::of)))
        val (started, a) = synchronized(lock) {
            val a = when (val r = admission(w, narrowing, SpawnKind.TICKET_WORKER, sessions())) {
                is SessionsResult.Ok -> r.value
                else -> return r.map()
            }
            val start = when (val r = tickets().start(ticket.id, by)) {
                is BoardResult.Ok -> r.value
                is BoardResult.Refused -> return SessionsResult.Refused(r.text)
                is BoardResult.Unavailable -> return SessionsResult.Unavailable(r.reason)
            }
            start to a
        }
        val answered = started.ticket
        val runner = answered.sessionId?.let { id -> sessions().firstOrNull { it.id == id } }?.let(SessionViews::of)
        fun outcome(kind: String, detail: String? = started.detail) = TicketStartOutcome(kind, answered, runner, detail = detail, depth = a.depth, maxSpawns = a.maxSpawns, openSpawns = a.open.size)
        val result = when {
            started.ok -> {
                if (w.id == null && runner != null) ownerSet(narrowing).add(runner.id)
                val (note, warning) = TicketStarter.startedDetail(started.detail)
                outcome(TicketStartOutcome.STARTED, null).copy(
                    startedBy = w.id, note = note, warning = warning, openSpawns = a.open.size + if (runner != null) 1 else 0,
                )
            }
            started.reason == TicketStart.ALREADY_IN_PROGRESS -> outcome(TicketStartOutcome.ALREADY_IN_PROGRESS)
            started.reason == TicketStartOutcome.QUEUED -> outcome(TicketStartOutcome.QUEUED).copy(lane = started.detail)
            started.reason == TicketStartOutcome.HELD -> outcome(TicketStartOutcome.HELD)
            else -> outcome(TicketStartOutcome.REFUSED, started.detail ?: started.reason)
        }
        return SessionsResult.Ok(result)
    }

    /** Sessions a caller was cleared to kill: once killed they stay released if revived to rest (BUG-KILL-IDLE-1), since a new run clears `killOutcome`. */
    private val released: MutableSet<String> = java.util.concurrent.ConcurrentHashMap.newKeySet()

    override fun mayKill(ref: String, by: Caller): SessionsResult<SessionView> {
        gate(Envelope.KILL)?.let { return SessionsResult.Refused(it) }
        val all = sessions()
        val (w, why) = who(by, all, "kill a session")
        if (w == null) return SessionsResult.Refused(why!!)
        val session = when (val r = resolve(all, ref)) {
            is SessionsResult.Ok -> r.value
            else -> return r.map()
        }
        killRefusal(session, w)?.let { return SessionsResult.Refused(it) }
        released.add(session.id)
        return SessionsResult.Ok(SessionViews.of(session))
    }

    override fun target(ref: String, by: Caller): SessionsResult<SessionView> {
        val all = sessions()
        val (w, why) = who(by, all, "act on a session")
        if (w == null) return SessionsResult.Refused(why!!)
        return when (val r = resolve(all, ref)) {
            is SessionsResult.Ok -> SessionsResult.Ok(SessionViews.of(r.value))
            else -> r.map()
        }
    }

    // ---- the rules ---------------------------------------------------------------------------------------------------

    private fun admission(w: Who, narrowing: SpawnNarrowing, kind: SpawnKind, all: List<SessionRecord>): SessionsResult<Admission> {
        val here = maxOf(w.id?.let { guard.placementOf(it)?.depth } ?: 0, narrowing.depth?.coerceAtLeast(0) ?: 0)
        val child = here + 1
        val maxDepth = narrow(limit(LimitRegistry.MAX_DEPTH), narrowing.maxDepth)
        val subject = if (w.id == null) "This connection" else "This session"
        if (child > maxDepth) {
            val tail = when (kind) {
                SpawnKind.SESSION -> "so the session would be at depth $child, above the maximum of ${shown(maxDepth)} " +
                    "(${LimitRegistry.MAX_DEPTH.key}; this connection: --max-depth). A session spawned through MCP may not spawn further than that. " +
                    LimitRegistry.MAX_DEPTH.hint()
                SpawnKind.TICKET_WORKER -> "so the ticket's worker would be at depth $child, above the maximum of ${shown(maxDepth)} " +
                    "(${LimitRegistry.MAX_DEPTH.key}; this connection: --max-depth). ${LimitRegistry.MAX_DEPTH.hint()} Leave the ticket in todo " +
                    "for a person or a shallower agent to start."
            }
            return SessionsResult.Refused("depth limit. $subject runs at depth $here, $tail")
        }
        val open = open(w, narrowing, all)
        val maxSpawns = narrow(limit(LimitRegistry.MCP_MAX_SPAWNS), narrowing.maxSpawns)
        if (open.size >= maxSpawns) {
            return SessionsResult.Refused(
                "concurrency limit. $subject already has ${open.size} open session(s) of a maximum of ${shown(maxSpawns)} " +
                    "(${LimitRegistry.MCP_MAX_SPAWNS.key}; this connection: --max-spawns): ${open.joinToString(", ")}. " +
                    "${LimitRegistry.MCP_MAX_SPAWNS.hint()} Kill one with infantry_kill, or wait for one to end, then retry.",
            )
        }
        // The pod cap (BUG-G2d) under the guard's own rule: checked here so a refusal names it before anything starts.
        try {
            guard.check(w.id, narrowDepth = maxDepth)
        } catch (e: IllegalArgumentException) {
            return SessionsResult.Refused(e.message ?: "spawn refused")
        }
        return SessionsResult.Ok(Admission(child, maxDepth, open, maxSpawns))
    }

    /** The caller's spawns that hold a slot: not ended, not released, and not killed unless running again. */
    private fun open(w: Who, narrowing: SpawnNarrowing, all: List<SessionRecord>): List<String> {
        val byId = all.associateBy { it.id }
        val mine = if (w.id != null) all.filter { it.spawnerSessionId == w.id } else ownerSet(narrowing).mapNotNull { byId[it] }
        val holding = mine.filter { guard.holds(it) && (it.live || (it.killOutcome == null && it.id !in released)) }.map { it.id }
        if (w.id == null) ownerSet(narrowing).retainAll(holding.toSet())
        return holding
    }

    /** Contract 1.114.0, as `McpWriteTools.killRefusal` worded it ("this session" for the caller's own). */
    private fun killRefusal(session: SessionRecord, w: Who): String? {
        val callerId = w.id ?: return null
        if (session.id == callerId) {
            return "session ${session.id} is this session itself; an agent may not kill its own session. " +
                "End your turn instead, or ask the owner to stop it."
        }
        if (session.spawnerSessionId == callerId) return null
        if (ProjectScope.isPrivileged(w.record, true)) {
            val own = w.record?.projectId
            if (own == null || session.projectId == own) return null
            return "session ${session.id} belongs to project '${session.projectId ?: "none"}', not this session's project '$own'; " +
                "a pm may only kill sessions in its own project. Ask a pm of that project to do it."
        }
        return "session ${session.id} was not spawned by this session ($callerId); a ${w.record?.role ?: "worker"} may only kill sessions it " +
            "spawned itself. Ask the pm to do it: infantry send pm \"please remove session ${session.id}\""
    }

    private fun resolve(all: List<SessionRecord>, ref: String): SessionsResult<SessionRecord> {
        val requested = ref.trim()
        if (requested.isEmpty()) return SessionsResult.Missing("sessionId is required")
        val matches = all.filter { it.id == requested }.ifEmpty { all.filter { it.id.startsWith(requested) } }
        return when (matches.size) {
            1 -> SessionsResult.Ok(matches.single())
            0 -> SessionsResult.Missing("no session '$requested' on this daemon")
            else -> SessionsResult.Missing("session id '$requested' is ambiguous: ${matches.joinToString(", ") { it.id }}")
        }
    }

    private fun ownerSet(narrowing: SpawnNarrowing): MutableSet<String> =
        ownerSpawns.computeIfAbsent(narrowing.clientKey?.trim().orEmpty()) { ConcurrentHashMap.newKeySet() }

    private companion object {
        /** The client's value only narrows: the smaller of the two; null = the daemon's. */
        fun narrow(daemon: Int, client: Int?): Int = client?.let { minOf(daemon, it.coerceAtLeast(0)) } ?: daemon

        fun shown(limit: Int): String = if (limit == Int.MAX_VALUE) "unlimited" else limit.toString()

        /** A non-Ok result as any other type (Ok never reaches here). */
        @Suppress("UNCHECKED_CAST")
        fun <T> SessionsResult<*>.map(): SessionsResult<T> = this as SessionsResult<T>
    }
}

/** RUNTIME-LOAD-6: [Spawns] behind a plugin's grants (bundled / official trusted; community needs `sessions.spawn`, default deny). */
internal class GrantedSpawns(private val spawns: () -> Spawns, private val allowed: (String) -> Boolean) : Spawns {
    override val charge get() = if (allowed(GRANT_SPAWNS)) spawns().charge else com.superiorsoftllc.infantry.daemon.api.spawns.NoSpawnCharge
    private inline fun <T> need(call: () -> SessionsResult<T>): SessionsResult<T> =
        if (allowed(GRANT_SPAWNS)) call() else SessionsResult.Refused("missing grant '$GRANT_SPAWNS' in modules.json")

    override fun admit(by: Caller, narrowing: SpawnNarrowing, kind: SpawnKind) = need { spawns().admit(by, narrowing, kind) }
    override fun spawn(request: SpawnRequest, by: Caller, narrowing: SpawnNarrowing) = need { spawns().spawn(request, by, narrowing) }
    override fun startTicket(ref: String, by: Caller, narrowing: SpawnNarrowing) = need { spawns().startTicket(ref, by, narrowing) }
    override fun mayKill(ref: String, by: Caller) = need { spawns().mayKill(ref, by) }
    override fun target(ref: String, by: Caller) = need { spawns().target(ref, by) }
}
