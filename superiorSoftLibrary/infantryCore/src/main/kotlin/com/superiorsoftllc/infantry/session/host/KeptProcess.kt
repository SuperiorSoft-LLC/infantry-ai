// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.session.host

import com.superiorsoftllc.infantry.daemon.api.processes.ProcInfo
import com.superiorsoftllc.infantry.session.ProcessGuard
import kotlinx.serialization.Serializable

/*
 * BUG-M2-11f (docs/INFANTRY-SESSION-HOST-PLAN.md section 11, "Reaping"): the probe proved that `opencode serve` outlives
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
    /** The [com.superiorsoftllc.infantry.daemon.InstanceId] of the state dir that kept it; null (older file) = never reaped. */
    val ownerId: String? = null,
)

/** What lifecycle bookkeeping needs from the OS. Injected so a test can stage a reused pid. */
interface ProcessTable {
    /** The start instant (epoch millis) of the LIVE process [pid]; null when it is gone or the OS will not say. */
    fun startInstant(pid: Long): Long?

    /** True when [pid]'s parent is alive: another owner still holds it, so it is no dead predecessor's orphan. */
    fun hasLiveParent(pid: Long): Boolean = false

    /**
     * Tree-kill [pid]. Called only after the caller matched [startedAt]; an implementation re-checks it on the handle it
     * kills through, so the pid cannot be recycled in between.
     */
    fun killTree(pid: Long, startedAt: Long)

    /** Kill [pid] alone, its descendants untouched (they may be kept). Same start-instant re-check as [killTree]. */
    fun killOne(pid: Long, startedAt: Long) = killTree(pid, startedAt)
}

/** The real one: [ProcessHandle], which also works for a process this JVM did not start (a dead daemon's child). */
object OsProcessTable : ProcessTable {
    // A parent the OS cannot name counts as "no live parent" only when the process itself is gone; otherwise doubt = true.
    override fun hasLiveParent(pid: Long): Boolean =
        ProcessHandle.of(pid).map { h -> h.parent().map { it.isAlive }.orElse(false) }.orElse(true)

    override fun startInstant(pid: Long): Long? =
        ProcessHandle.of(pid).filter { it.isAlive }.flatMap { it.info().startInstant() }.map { it.toEpochMilli() }.orElse(null)

    override fun killTree(pid: Long, startedAt: Long) {
        // One handle for the check and the kill: an open handle keeps the OS from reusing the pid meanwhile.
        val handle = ProcessHandle.of(pid).orElse(null) ?: return
        if (handle.info().startInstant().map { it.toEpochMilli() }.orElse(null) != startedAt) return
        // DISCONNECT-1: only what is provably under the root dies with it; never the daemon, the app, or its own runtime.
        handle.descendants().forEach { d ->
            val member = ProcInfo(d.pid(), d.parent().map { it.pid() }.orElse(null), d.info().command().orElse(""), d.info().startInstant().map { it.toEpochMilli() }.orElse(null))
            if (TreeKillRule.spare(member, startedAt)) return@forEach
            runCatching { d.destroyForcibly() }
        }
        runCatching { handle.destroyForcibly() }
    }

    override fun killOne(pid: Long, startedAt: Long) {
        val handle = ProcessHandle.of(pid).orElse(null) ?: return
        if (handle.info().startInstant().map { it.toEpochMilli() }.orElse(null) != startedAt) return
        runCatching { handle.destroyForcibly() }
    }
}

/**
 * DISCONNECT-1: which members of a process tree a tree kill must NOT take. The root was proven by pid + start instant; a
 * descendant is spared when it is the daemon or one of its ancestors (the app), runs from the app's own runtime
 * (`java.home`: the CLI, MCP servers), or started before the root (it cannot be the root's child: a recycled or re-parented pid).
 */
object TreeKillRule {
    fun spare(member: ProcInfo, rootStartedAt: Long): Boolean =
        ProcessGuard.isProtected(member) || (member.startedAt != null && member.startedAt!! < rootStartedAt)
}
