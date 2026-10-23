package com.superiorsoftllc.agenthq.session.host

import kotlinx.serialization.Serializable

/*
 * BUG-M2-11f (docs/AGENTHQ-SESSION-HOST-PLAN.md section 11, "Reaping"): the probe proved that `opencode serve` outlives
 * a hard-killed daemon AND keeps serving with its stdout/stderr pipes broken, so nothing but the next daemon can stop
 * it. The kept process's identity is therefore persisted, and a daemon start tree-kills it only when BOTH the pid and
 * its start instant still match: a pid alone can belong to an unrelated process by then.
 */

/**
 * A process the host keeps between turns, as `sessions.json` records it (an additive [SessionRecord] field).
 * [startedAt] is the OS start instant in epoch millis ([ProcessTable.startInstant]); null = the OS would not say, and
 * such a process is never reaped, because nothing proves it is still ours.
 */
@Serializable
data class KeptProcess(
    val pid: Long,
    val startedAt: Long? = null,
    /** The adapter's label, for the reap line. */
    val label: String,
)

/** What lifecycle bookkeeping needs from the OS. Injected so a test can stage a reused pid. */
interface ProcessTable {
    /** The start instant (epoch millis) of the LIVE process [pid]; null when it is gone or the OS will not say. */
    fun startInstant(pid: Long): Long?

    /**
     * Tree-kill [pid]. Called only after the caller matched [startedAt]; an implementation re-checks it on the handle it
     * kills through, so the pid cannot be recycled in between.
     */
    fun killTree(pid: Long, startedAt: Long)
}

/** The real one: [ProcessHandle], which also works for a process this JVM did not start (a dead daemon's child). */
object OsProcessTable : ProcessTable {
    override fun startInstant(pid: Long): Long? =
        ProcessHandle.of(pid).filter { it.isAlive }.flatMap { it.info().startInstant() }.map { it.toEpochMilli() }.orElse(null)

    override fun killTree(pid: Long, startedAt: Long) {
        // One handle for the check and the kill: an open handle keeps the OS from reusing the pid meanwhile.
        val handle = ProcessHandle.of(pid).orElse(null) ?: return
        if (handle.info().startInstant().map { it.toEpochMilli() }.orElse(null) != startedAt) return
        handle.descendants().forEach { runCatching { it.destroyForcibly() } }
        runCatching { handle.destroyForcibly() }
    }
}
