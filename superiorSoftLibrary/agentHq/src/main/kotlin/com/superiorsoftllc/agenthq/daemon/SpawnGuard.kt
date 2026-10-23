package com.superiorsoftllc.agenthq.daemon

import com.superiorsoftllc.agenthq.session.SessionRecord
import com.superiorsoftllc.agenthq.session.SessionState
import java.util.concurrent.ConcurrentHashMap

/** A session-originated spawn the [SpawnGuard] refused. The message starts with a fixed reason; nothing was created. */
class SpawnRefused(message: String) : IllegalArgumentException(message)

/**
 * BUG-G2d: the daemon's bound on the sessions a session may start.
 *
 * A spawn is SESSION-ORIGINATED when its frame names the session it comes from: the envelope's `sessionId` on
 * `pod_submit` and on `new` (contract 1.58.0). `agenthq submit` and `agenthq mcp serve`'s `agenthq_spawn` put
 * `AGENTHQ_SESSION_ID` there, which the daemon exports into every session. A frame that names no session (the
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
 * The lineage is in memory, for the life of the daemon: after a restart every surviving session is a root again.
 * It bounds a runaway cascade within one daemon's life, which is where the G2 one happened; it is not an access
 * control, since any client may name (or not name) a session.
 *
 * CONCURRENCY. [admit] holds one lock across check-then-create, so two spawns from one pod cannot both take the
 * last slot. [record] takes NO lock: the orchestrator calls it from its worker thread while a connection may hold
 * the lock waiting on that same worker inside `submit`.
 */
class SpawnGuard(
    /** The daemon's sessions by id (`SessionManager::get`). */
    private val sessions: (String) -> SessionRecord?,
    val maxPerPod: Int = DEFAULT_MAX_PER_POD,
    val maxDepth: Int = DEFAULT_MAX_DEPTH,
    /** How many nodes of an orchestrator pod have not started a session yet; 0 for an unknown or finished pod. */
    private val pendingOf: (podId: String) -> Int = { 0 },
) {
    init {
        require(maxPerPod >= 0) { "the pod spawn cap must be 0 or more, was $maxPerPod" }
        require(maxDepth >= 0) { "the spawn depth limit must be 0 or more, was $maxDepth" }
    }

    /** Where a session sits: the root of its pod and its depth below it. */
    data class Placement(val root: String, val depth: Int)

    private val lineage = ConcurrentHashMap<String, Placement>()
    private val podRoots = ConcurrentHashMap<String, String>()
    private val admission = Any()

    /**
     * Where a spawn from [origin] would place its sessions, or null when [origin] is not a session this daemon holds
     * (not charged). Throws [SpawnRefused] when [requested] more sessions would break the depth rule or the pod cap.
     */
    fun check(origin: String?, requested: Int = 1): Placement? {
        val from = origin?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        if (sessions(from) == null) return null
        val parent = lineage[from] ?: Placement(root = from, depth = 0)
        val child = Placement(root = parent.root, depth = parent.depth + 1)
        if (child.depth > maxDepth) {
            throw SpawnRefused(
                "$REFUSED_DEPTH. Session $from is at depth ${parent.depth} of pod ${parent.root}, so its spawn would be " +
                    "at depth ${child.depth}, above the maximum of $maxDepth ($MAX_DEPTH_ENV): a session started from " +
                    "inside a session may not start more. Nothing was created; report back instead.",
            )
        }
        val held = liveIn(child.root)
        if (held + requested > maxPerPod) {
            throw SpawnRefused(
                "$REFUSED_CAP. Pod ${child.root} already holds $held session(s) started from inside it, of a " +
                    "maximum of $maxPerPod ($MAX_PER_POD_ENV); this spawn asks for $requested more. Nothing was " +
                    "created: kill one of them, or ask the user to start this work.",
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

    /** Where [sessionId] sits, or null when it is a root (or unknown). */
    fun placementOf(sessionId: String): Placement? = lineage[sessionId]

    /**
     * Live sessions started from inside pod [root], plus the not-yet-started nodes of pods submitted from it. The
     * pending nodes are read FIRST: a node that starts between the two reads is then counted twice (a moment's
     * over-count, refusing on the safe side), never zero times.
     */
    fun liveIn(root: String): Int {
        val pending = podRoots.entries.filter { it.value == root }.sumOf { pendingOf(it.key) }
        val live = lineage.entries.count { (id, at) ->
            at.root == root && sessions(id)?.state?.let { it !in ENDED } == true
        }
        return live + pending
    }

    companion object {
        const val DEFAULT_MAX_PER_POD = 4
        const val DEFAULT_MAX_DEPTH = 1

        /** The fixed reasons a refusal starts with. */
        const val REFUSED_CAP = "spawn refused: pod cap"
        const val REFUSED_DEPTH = "spawn refused: depth limit"

        /** The daemon's settings (read by `AgentHqDaemon.build`). */
        const val MAX_PER_POD_ENV = "AGENTHQ_POD_SPAWN_CAP"
        const val MAX_DEPTH_ENV = "AGENTHQ_POD_SPAWN_DEPTH"

        private val ENDED = setOf(SessionState.EXITED, SessionState.FAILED, SessionState.ORPHANED)

        /** [name] from [env] as a non-negative integer, else [default]; a malformed value is refused, never ignored. */
        fun limitFromEnv(name: String, default: Int, env: (String) -> String? = System::getenv): Int {
            val raw = env(name)?.trim()?.takeIf { it.isNotEmpty() } ?: return default
            return raw.toIntOrNull()?.takeIf { it >= 0 }
                ?: throw IllegalArgumentException("$name must be a whole number of 0 or more, was '$raw'")
        }
    }
}
