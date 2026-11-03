// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.daemon

import com.superiorsoftllc.infantry.settings.LimitRegistry
import com.superiorsoftllc.infantry.session.InfantryEnv

import com.superiorsoftllc.infantry.session.SessionRecord
import com.superiorsoftllc.infantry.session.SessionState
import java.util.concurrent.ConcurrentHashMap

/** A session-originated spawn the [SpawnGuard] refused. The message starts with a fixed reason; nothing was created. */
class SpawnRefused(message: String) : IllegalArgumentException(message)

/**
 * BUG-G2d: the daemon's bound on the sessions a session may start.
 *
 * A spawn is SESSION-ORIGINATED when its frame names the session it comes from: the envelope's `sessionId` on
 * `pod_submit` and on `new` (contract 1.58.0). `infantry submit` and `infantry mcp serve`'s `infantry_spawn` put
 * `INFANTRY_SESSION_ID` there, which the daemon exports into every session. A frame that names no session (the
 * user at a terminal, the UI), or names one this daemon does not hold, is not charged: [check] admits it as it is.
 *
 * THE POD of a session is the tree it belongs to: a session nobody spawned from inside a session is a ROOT (depth
 * 0); a session spawned from inside session S belongs to S's pod at S's depth + 1. Two rules, checked BEFORE
 * anything is created:
 *  - DEPTH: the new session's depth may not exceed [maxDepth] (default 1: only a root may spawn).
 *  - POD CAP: the pod may hold at most [maxPerPod] live sessions spawned from inside it (default 4). Live = held
 *    by the daemon and not EXITED / FAILED / ORPHANED; an IDLE session still counts, because it is still there.
 *    A pod node not yet started counts too ([pendingOf]), so a multi-node pod cannot slip past the cap.
 *
 * RUNTIME-LOAD-6: the lineage is the sessions' persisted `spawnerSessionId` chain ([all]), with the in-memory map
 * as a fast path, so a session keeps its depth and its pod across a daemon restart. A spawner the daemon no longer
 * holds still counts as one level up (refusing on the safe side). It bounds a runaway cascade; it is not an access
 * control, since any client may name (or not name) a session.
 *
 * TICKET STARTS are charged too (RUNTIME-LOAD-6): a session-originated `ticket_move` to in_progress that would run a
 * worker goes through [admitIf], and the worker joins the starter's pod. A worker stops holding a slot once it is not
 * live and [released] says so (the daemon: its ticket left In Progress), so finished tickets never fill a pm's pod.
 *
 * CONCURRENCY. [admit] holds one lock across check-then-create, so two spawns from one pod cannot both take the
 * last slot. [record] takes NO lock: the orchestrator calls it from its worker thread while a connection may hold
 * the lock waiting on that same worker inside `submit`.
 */
class SpawnGuard(
    /** The daemon's sessions by id (`SessionManager::get`). */
    private val sessions: (String) -> SessionRecord?,
    maxPerPod: Int = DEFAULT_MAX_PER_POD,
    maxDepth: Int = DEFAULT_MAX_DEPTH,
    /** How many nodes of an orchestrator pod have not started a session yet; 0 for an unknown or finished pod. */
    private val pendingOf: (podId: String) -> Int = { 0 },
    /** The live pod cap / depth limit (settings `limits.concurrency.*`), read at every check; null = the fixed value above. */
    private val maxPerPodSource: (() -> Int)? = null,
    private val maxDepthSource: (() -> Int)? = null,
    /** RUNTIME-LOAD-6: every session the daemon holds, for the persisted lineage; empty = the in-memory lineage only. */
    private val all: () -> List<SessionRecord> = { emptyList() },
    /** RUNTIME-LOAD-6: a session that is not live and no longer holds a slot (a worker whose ticket left In Progress). */
    private val released: (SessionRecord) -> Boolean = { false },
) {
    init {
        require(maxPerPod >= 0) { "the pod spawn cap must be 0 or more, was $maxPerPod" }
        require(maxDepth >= 0) { "the spawn depth limit must be 0 or more, was $maxDepth" }
    }

    private val fixedPerPod = maxPerPod
    private val fixedDepth = maxDepth

    /** The pod cap in force now; [Int.MAX_VALUE] = unlimited. */
    val maxPerPod: Int get() = maxPerPodSource?.invoke() ?: fixedPerPod

    /** The depth limit in force now; [Int.MAX_VALUE] = unlimited. */
    val maxDepth: Int get() = maxDepthSource?.invoke() ?: fixedDepth

    /** Where a session sits: the root of its pod and its depth below it. */
    data class Placement(val root: String, val depth: Int)

    private val lineage = ConcurrentHashMap<String, Placement>()
    private val podRoots = ConcurrentHashMap<String, String>()
    private val admission = Any()

    /**
     * Where a spawn from [origin] would place its sessions, or null when [origin] is not a session this daemon holds
     * (not charged). Throws [SpawnRefused] when [requested] more sessions would break the depth rule or the pod cap.
     */
    fun check(origin: String?, requested: Int = 1, narrowDepth: Int? = null): Placement? {
        val from = origin?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        if (sessions(from) == null) return null
        val parent = placementOf(from) ?: Placement(root = from, depth = 0)
        val child = Placement(root = parent.root, depth = parent.depth + 1)
        val depthLimit = narrowDepth?.let { minOf(it, maxDepth) } ?: maxDepth
        val podCap = maxPerPod
        if (child.depth > depthLimit) {
            throw SpawnRefused(
                "$REFUSED_DEPTH. Session $from is at depth ${parent.depth} of pod ${parent.root}, so its spawn would be " +
                    "at depth ${child.depth}, above the maximum of ${shown(depthLimit)} (${LimitRegistry.MAX_DEPTH.key}): a session started from " +
                    "inside a session may not start more. Nothing was created; report back instead. ${LimitRegistry.MAX_DEPTH.hint()}",
            )
        }
        val held = liveIn(child.root)
        if (held.toLong() + requested > podCap) {
            throw SpawnRefused(
                "$REFUSED_CAP. Pod ${child.root} already holds $held session(s) started from inside it, of a " +
                    "maximum of ${shown(podCap)} (${LimitRegistry.MAX_PER_POD.key}); this spawn asks for $requested more. Nothing was " +
                    "created: kill one of them, or ask the user to start this work. ${LimitRegistry.MAX_PER_POD.hint()}",
            )
        }
        return child
    }

    /**
     * Check, then run [create] under the admission lock, so the slot checked is the slot taken. [create] receives the
     * placement (null = not charged) and must record what it starts: [record] for a session, [recordPod] for a pod.
     */
    fun <T> admit(origin: String?, requested: Int = 1, create: (Placement?) -> T): T =
        synchronized(admission) { create(check(origin, requested)) }

    /**
     * RUNTIME-LOAD-6: [admit] when [charged] (a ticket start that would run a worker), else [create] under the same lock
     * with no check, so a start that runs nothing is never refused and two starts still cannot race for the last slot.
     */
    fun <T> admitIf(origin: String?, charged: Boolean, create: (Placement?) -> T): T =
        synchronized(admission) { create(if (charged) check(origin) else null) }

    /** True when [record] holds a slot: not ended, and live or not [released]. */
    fun holds(record: SessionRecord): Boolean = record.state !in ENDED && (record.live || !released(record))

    /** [sessionId] was started from inside [origin]: it joins [origin]'s pod one level down. No-op when not charged. */
    fun record(sessionId: String, origin: String?) {
        val from = origin?.trim()?.takeIf { it.isNotEmpty() } ?: return
        if (sessions(from) == null && !lineage.containsKey(from)) return
        val parent = lineage[from] ?: Placement(root = from, depth = 0)
        lineage[sessionId] = Placement(root = parent.root, depth = parent.depth + 1)
    }

    /** Orchestrator pod [podId] was submitted from inside pod [root]: its unstarted nodes count against that pod. */
    fun recordPod(podId: String, root: String) {
        podRoots[podId] = root
    }

    /**
     * Where [sessionId] sits, or null when it is a root (or unknown): the in-memory lineage, else the persisted
     * `spawnerSessionId` chain (bounded, cycle-safe).
     */
    fun placementOf(sessionId: String): Placement? {
        lineage[sessionId]?.let { return it }
        var at = sessions(sessionId)?.spawnerSessionId?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        var depth = 1
        val seen = mutableSetOf(sessionId)
        while (depth < MAX_CHAIN && seen.add(at)) {
            lineage[at]?.let { return Placement(it.root, it.depth + depth) }
            val up = sessions(at)?.spawnerSessionId?.trim()?.takeIf { it.isNotEmpty() } ?: break
            at = up
            depth++
        }
        return Placement(root = at, depth = depth)
    }

    /**
     * Live sessions started from inside pod [root], plus the not-yet-started nodes of pods submitted from it. The
     * pending nodes are read FIRST: a node that starts between the two reads is then counted twice (a moment's
     * over-count, refusing on the safe side), never zero times.
     */
    fun liveIn(root: String): Int {
        val pending = podRoots.entries.filter { it.value == root }.sumOf { pendingOf(it.key) }
        val ids = lineage.keys + all().filter { it.spawnerSessionId != null }.map { it.id }
        val live = ids.count { id ->
            val record = sessions(id)
            record != null && holds(record) && placementOf(id)?.root == root
        }
        return live + pending
    }

    companion object {
        /** The defaults live in [LimitRegistry]; these only expose them. */
        val DEFAULT_MAX_PER_POD: Int get() = LimitRegistry.MAX_PER_POD.default.toInt()
        val DEFAULT_MAX_DEPTH: Int get() = LimitRegistry.MAX_DEPTH.default.toInt()

        private fun shown(limit: Int): String = if (limit == Int.MAX_VALUE) "unlimited" else limit.toString()

        /** The fixed reasons a refusal starts with. */
        const val REFUSED_CAP = "spawn refused: pod cap"
        const val REFUSED_DEPTH = "spawn refused: depth limit"

        /** The daemon's settings (read by `InfantryDaemon.build`). */
        const val MAX_PER_POD_ENV = "INFANTRY_POD_SPAWN_CAP"
        const val MAX_DEPTH_ENV = "INFANTRY_POD_SPAWN_DEPTH"

        private val ENDED = setOf(SessionState.EXITED, SessionState.FAILED, SessionState.ORPHANED)
        private const val MAX_CHAIN = 64

    }
}
