// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.daemon.api.processes

/*
 * STACK-NEUTRAL-2 (DaemonApi 1.8.0, additive): the HOST process services a process-watch plugin is built on, and the per-launch
 * environment seam. Core keeps the mechanisms (the OS process table, the kill that re-checks the start instant, the session
 * roots, the close event, the gate lock); the policy (what is rogue, what is spared, the ledger, CPU, the build env) is a plugin.
 */

/** One live OS process. [startedAt] is the OS start instant in epoch millis; null = the OS would not say. */
data class ProcInfo(val pid: Long, val parentPid: Long?, val command: String, val startedAt: Long?)

/** One process's memory as the OS lists it. */
data class ProcMem(val pid: Long, val image: String, val mb: Long)

/** What a [Processes.kill] came to. */
sealed interface KillResult {
    data class Killed(val pid: Long, val tree: Boolean) : KillResult
    data class Refused(val reason: String) : KillResult
}

/**
 * A session as the watch sees it. [rootPids] = its kept harness pid and its running child pid; [keepPids] = what a close must
 * spare (its `bg run --keep` wrappers and a harness the host still holds); [bgJobPids] = every registered bg job wrapper.
 * [lastActivityAt] = its last output, else its last heartbeat.
 */
data class WatchedSession(
    val id: String,
    /** The state's name (`IDLE`, `BUSY`, `EXITED`, `FAILED`, `ORPHANED`, ...). */
    val state: String,
    val busy: Boolean,
    val hasBgJobs: Boolean,
    val lastActivityAt: Long,
    val rootPids: List<Long>,
    val worktreePath: String? = null,
    val ticketId: String? = null,
    val keepPids: Set<Long> = emptySet(),
    val bgJobPids: List<Long> = emptyList(),
) {
    /** The session can run nothing more (exited, failed or orphaned). */
    val ended: Boolean get() = state == "EXITED" || state == "FAILED" || state == "ORPHANED"
}

/** One session closing (kill, remove, restart, a failed turn): [keepPids] and everything under them must survive. */
data class SessionClosed(val sessionId: String, val at: Long, val keepPids: Set<Long>, val reason: String)

/** The machine-wide gate lock's owner (`owner.txt`, else the `pid` file). */
data class GateOwner(val pid: Long?, val ticket: String?, val session: String?) {
    val named: Boolean get() = ticket != null || session != null
}

interface GateLockView {
    /** The lock's owner, or null when there is no lock. */
    fun read(): GateOwner?
    fun holderAlive(owner: GateOwner): Boolean

    /** Releases the lock when [GateOwner] names this session / ticket and its holder is gone (or in [killedPids]); the log line, else null. */
    fun releaseIfOwnedBy(sessionId: String, ticketId: String?, killedPids: Set<Long> = emptySet()): String?
}

/** An env template of a tool profile (`${maxWorkers}` / `${maxParallelForks}` in [value]). */
data class ProcessEnvTemplate(val name: String, val value: String, val onlyIf: String? = null, val join: String = " ", val inherit: Boolean = true)

/** What the watch reads of a tool profile (data, never code: which processes are build daemons, shared, heavy, and what env a launch gets). */
data class ProcessToolProfile(
    val name: String,
    val daemons: List<String> = emptyList(),
    val sharedDaemon: Boolean = false,
    val clients: List<String> = emptyList(),
    val heavyImages: List<String> = emptyList(),
    val heavyCommands: List<String> = emptyList(),
    val env: List<ProcessEnvTemplate> = emptyList(),
    /** PROC-LEAK-1 (1.37.0): the argv that stops [daemons] gracefully, run in the session's worktree; empty = none. */
    val stop: List<String> = emptyList(),
    /** PROC-LEAK-1 (1.37.0): command-line regexes of a short-lived client that is reported stuck when it hangs. */
    val stuck: List<String> = emptyList(),
    /** PROC-LEAK-2 (1.39.0): where one of [daemons] names the directory it last built in; null = the tool says nothing. */
    val daemonDir: ProcessDaemonDir? = null,
)

/**
 * PROC-LEAK-2 (1.39.0): how to learn which project a detached daemon serves when its client is gone. [paths] are file templates
 * (`${pid}`, `${home}`, `${env:NAME}`; a `*` stands for one directory level; a template naming an unset variable is skipped);
 * [match] is a regex over that file's lines whose group 1 is the directory, the last match winning (the daemon's latest build).
 */
data class ProcessDaemonDir(val paths: List<String> = emptyList(), val match: String = "")

/**
 * The host process service. Official / core tier only; a community plugin needs the grant `daemon.processes` and never gets [kill]
 * (it throws). Every call is a read of the OS or the daemon, except [kill] and [gateLock]'s release.
 */
interface Processes {
    /** Every live process (parent pid known where the OS says). Empty when unsupported. */
    fun snapshot(): List<ProcInfo>

    /** The live process [pid] itself, or null. */
    fun info(pid: Long): ProcInfo?

    /** The live processes under [rootPid]. */
    fun descendants(rootPid: Long): List<ProcInfo>

    /** The start instant (epoch millis) of the LIVE process [pid]; null when it is gone or the OS will not say. */
    fun startInstant(pid: Long): Long?

    /** Every process with its resident memory. Costs a subprocess: call only on demand. */
    fun memory(): List<ProcMem>
    fun freeMb(): Long?
    fun totalMb(): Long?

    /** Cumulative CPU time of [pid] in nanoseconds, null when gone or unknown. */
    fun cpuNanos(pid: Long): Long?

    /**
     * Kills [pid] (its tree, or [tree] false = itself alone) after re-checking its start instant against [startedAt]. Refused for the
     * daemon, its ancestors and anything run by the daemon's own runtime ([isProtected]); the host logs who asked.
     */
    fun kill(pid: Long, startedAt: Long, tree: Boolean = true): KillResult

    /** The id of this daemon's state dir (its InstanceId): a ledger file stamped with another one is never trusted. Null = unknown. */
    val ownerId: String?

    /** The daemon, its ancestors and the app's own runtime: never recorded, listed or killed. */
    fun isProtected(p: ProcInfo): Boolean

    /** Every session with its roots, state, worktree, ticket and keep set, as of now. */
    fun sessions(): List<WatchedSession>

    /**
     * PROC-LEAK-1 (1.37.0): the live processes in session [sessionId]'s OS container (a Windows Job Object its harness was put
     * in at launch): every process the session started, its parent alive or not. Empty where the OS has no container.
     */
    fun sessionMembers(sessionId: String): List<ProcInfo> = emptyList()

    /** PROC-LEAK-1 (1.37.0): what [sessionMembers] reads: `job object`, or `none`. */
    val containment: String get() = "none"

    /** Session id to its root pids (kept harness pid + child pid); sessions with none are left out. */
    fun sessionRoots(): Map<String, List<Long>> = sessions().associate { it.id to it.rootPids }.filterValues { it.isNotEmpty() }

    /** Told once per closing session (never throws into the caller). */
    fun onSessionClosed(listener: (SessionClosed) -> Unit): AutoCloseable

    /** Told when the daemon shuts down, after its sessions stopped (the last-moment sweep of ended sessions' processes). */
    fun onDaemonShutdown(listener: () -> Unit): AutoCloseable

    val gateLock: GateLockView

    /** The tool profiles in force (the merged defaults and the `watch.toolProfiles` setting). */
    fun toolProfiles(): List<ProcessToolProfile>

    /** The tool profiles in force as (env name, value): what the runner a session starts reads (`INFANTRY_TOOL_PROFILES`). */
    fun toolProfilesEnv(): Pair<String, String>

    /** The watch shim directory (first on PATH of a watched session), null = none could be written. */
    fun shimDir(): String?

    /** The daemon's own value of environment variable [name] (a template's `inherit`), null when unset. */
    fun inheritedEnv(name: String): String?

    /** Answers the `processes` / `processes_reap` frames: one handler at a time; none = "this daemon has no process watch". */
    fun serve(handler: ProcessesHandler): AutoCloseable

    /** Pushes a `process_pressure` frame (the [ProcessReport] JSON) to every client. */
    fun publishPressure(reportJson: String)

    /** Raises a ceiling attention item (`outcome` names the rule) for the owner. */
    fun raiseAlert(outcome: String, detail: String)
}

/** The plugin's answer to a `processes` frame. Throws [IllegalArgumentException] for an unknown op. */
fun interface ProcessesHandler {
    fun answer(request: ProcessesRequest): ProcessesAnswer
}

/** The per-launch facts a [LaunchEnv] contributor may read. */
data class LaunchInfo(val sessionId: String? = null, val role: String? = null, val projectId: String? = null)

interface LaunchEnv {
    /**
     * [contributor] is called per session launch; its map is merged in registration order (a later contributor wins a name). A
     * contributor that throws is skipped (fail open), logged once. Close the handle to stop contributing.
     */
    fun contribute(name: String, contributor: (LaunchInfo) -> Map<String, String>): AutoCloseable
}

/** Read access to the settings a plugin's own limits live in: `limits.*`, `watch.*` and `processes.*` keys only. */
interface PluginSettings {
    /** The effective numeric value (stored setting, env, default) of limit [key], null when no such limit. */
    fun limit(key: String): Long?

    /** The stored value of [key] as JSON text, null when unset. */
    fun stored(key: String): String?

    /** CMD-1 (1.31.0, additive): the sentence a refusal of limit [key] ends with (which setting, where to change it); empty when unknown. */
    fun hint(key: String): String = ""
}

object NoProcesses : Processes {
    override fun snapshot() = emptyList<ProcInfo>()
    override fun info(pid: Long): ProcInfo? = null
    override fun descendants(rootPid: Long) = emptyList<ProcInfo>()
    override fun startInstant(pid: Long): Long? = null
    override fun memory() = emptyList<ProcMem>()
    override fun freeMb(): Long? = null
    override fun totalMb(): Long? = null
    override fun cpuNanos(pid: Long): Long? = null
    override fun kill(pid: Long, startedAt: Long, tree: Boolean): KillResult = KillResult.Refused("this daemon has no process service")
    override val ownerId: String? = null
    override fun isProtected(p: ProcInfo) = true
    override fun sessions() = emptyList<WatchedSession>()
    override fun onSessionClosed(listener: (SessionClosed) -> Unit) = AutoCloseable {}
    override fun onDaemonShutdown(listener: () -> Unit) = AutoCloseable {}
    override val gateLock = object : GateLockView {
        override fun read(): GateOwner? = null
        override fun holderAlive(owner: GateOwner) = false
        override fun releaseIfOwnedBy(sessionId: String, ticketId: String?, killedPids: Set<Long>): String? = null
    }
    override fun toolProfiles() = emptyList<ProcessToolProfile>()
    override fun toolProfilesEnv(): Pair<String, String> = "INFANTRY_TOOL_PROFILES" to "[]"
    override fun shimDir(): String? = null
    override fun inheritedEnv(name: String): String? = null
    override fun serve(handler: ProcessesHandler) = AutoCloseable {}
    override fun publishPressure(reportJson: String) {}
    override fun raiseAlert(outcome: String, detail: String) {}
}

object NoLaunchEnv : LaunchEnv {
    override fun contribute(name: String, contributor: (LaunchInfo) -> Map<String, String>) = AutoCloseable {}
}

object NoPluginSettings : PluginSettings {
    override fun limit(key: String): Long? = null
    override fun stored(key: String): String? = null
}
