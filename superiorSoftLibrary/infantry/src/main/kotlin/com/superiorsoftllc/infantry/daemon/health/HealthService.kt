// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.daemon.health

import java.io.File

/** What the report needs from the running daemon, as lambdas so core code stays free of plugin and project names. */
class HealthSources(
    val stateDir: File,
    val port: () -> Int,
    val build: String,
    /** `plugin id -> loaded | refused: why | failed: why`. */
    val plugins: () -> Map<String, String>,
    /** Rows plugins contribute through the generic `status` hook. */
    val pluginRows: () -> Map<String, String>,
    val hooks: () -> Pair<Int, Int>?,
    val harnesses: () -> List<String>,
    /** The project path, worktree path and branch of the session, else of the project the call is for (null = none applies). */
    val sessionPaths: (sessionId: String?, projectId: String?) -> Triple<String?, String?, String?>,
    val eventTail: Int,
)

/** DAEMON-HEALTH-1: builds the live [HealthReport]; the single source for the CLI, the MCP tool and the Lua call. */
class HealthService(
    val journal: HealthJournal,
    val bridges: McpBridgeTracker,
    private val src: HealthSources,
    private val now: () -> Long = System::currentTimeMillis,
) {
    fun report(sessionId: String? = null, projectId: String? = null): HealthReport {
        val t = now()
        val st = journal.status()
        val (project, worktree, branch) = src.sessionPaths(sessionId, projectId)
        val rows = src.pluginRows()
        val hooks = src.hooks() ?: rows["hooks"]?.toIntOrNull()?.let { it to (rows["hookFailures"]?.toIntOrNull() ?: 0) }
        return HealthReport(
            at = t,
            daemon = DaemonRow(
                up = true, pid = ProcessHandle.current().pid(), port = src.port(), build = src.build,
                startedAt = st?.startedAt, uptimeMs = st?.startedAt?.let { t - it }, lastSeen = t,
                lastRestartAt = st?.lastRestartAt, lastRestartCause = st?.lastRestartCause,
            ),
            plugins = src.plugins().map { (id, status) -> PluginRow(id, if (status == "loaded" || status == "started") "loaded" else status) }.sortedBy { it.id },
            pluginRows = rows - "hooks" - "hookFailures",
            mcp = bridges.rows(),
            hooks = hooks?.first, hookFailures = hooks?.second,
            freeMemoryMb = Runtime.getRuntime().maxMemory().let { (it - (Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory())) / BYTES_PER_MB },
            freeDiskMb = src.stateDir.usableSpace / BYTES_PER_MB,
            environment = EnvironmentProbe.facts(src.stateDir, project, worktree, branch, src.harnesses()),
            events = journal.tail(src.eventTail),
        )
    }

    private companion object { const val BYTES_PER_MB = 1_048_576L }
}

/**
 * DAEMON-HEALTH-1: the one process-wide handle, set when a daemon starts. A daemon is one per state dir and one per JVM in
 * production; a test that starts several sets it again.
 */
object DaemonHealth {
    @Volatile var service: HealthService? = null

    /** DAEMON-HEALTH-2: the start-brief environment line for [projectId], or null when no health service runs. */
    fun briefLine(projectId: String?): String? =
        service?.report(null, projectId)?.environment?.let(EnvironmentProbe::briefLine)

    /** The MCP bridge for [sessionId] made a call on a connection: it is alive now. */
    fun bridgeSeen(sessionId: String?, connection: MutableSet<String>) {
        val s = service ?: return
        val id = sessionId?.trim()?.takeIf { it.isNotEmpty() } ?: return
        if (connection.add(id)) s.bridges.connected(id) else s.bridges.seen(id)
    }

    /** A connection closed: every bridge that had called on it dropped, with [cause] when the socket gave one. */
    fun connectionClosed(connection: Set<String>, cause: String?) {
        val s = service ?: return
        connection.forEach { s.bridges.disconnected(it, cause) }
    }
}

/** DAEMON-HEALTH-1: the health report built from the files in the state dir alone, for a daemon that does not answer. */
object OfflineHealth {
    fun report(stateDir: File, alive: (Long) -> Boolean, now: Long = System.currentTimeMillis(), eventTail: Int): HealthReport {
        val dir = HealthJournal.dirIn(stateDir)
        val st = HealthJournal.read(dir)
        val up = st != null && st.running && alive(st.pid)
        return HealthReport(
            at = now,
            daemon = DaemonRow(
                up = up, pid = st?.pid, port = st?.port, build = st?.build, startedAt = st?.startedAt,
                uptimeMs = if (up) st?.startedAt?.let { now - it } else null, lastSeen = st?.lastSeen,
                lastRestartAt = st?.lastRestartAt,
                lastRestartCause = if (st == null) "no health record in $stateDir (the daemon never started here, or predates DAEMON-HEALTH-1)"
                else if (!st.running) "clean stop: ${st.stopReason ?: "no reason"}" else if (!up) "died without a clean stop" else st.lastRestartCause,
                source = "files",
            ),
            environment = EnvironmentProbe.facts(stateDir, null, null, null, emptyList()),
            events = HealthJournal.readEvents(dir, eventTail),
        )
    }
}
