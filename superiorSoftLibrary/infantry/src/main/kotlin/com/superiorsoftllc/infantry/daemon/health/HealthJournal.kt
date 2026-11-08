// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.daemon.health

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/** DAEMON-HEALTH-1: one line of the restart / disconnect log. [kind] is open (`daemon_start`, `daemon_stop`, `mcp_connect`, `mcp_disconnect`, ...). */
@Serializable
data class HealthEvent(val at: Long, val kind: String, val cause: String? = null, val detail: String? = null)

/** DAEMON-HEALTH-1: the daemon's own record of itself, rewritten on start, on every heartbeat and on a clean stop. */
@Serializable
data class HealthStatus(
    val pid: Long,
    val port: Int,
    val build: String,
    val startedAt: Long,
    val lastSeen: Long,
    val running: Boolean = true,
    /** Why the previous run ended, as decided at THIS start (clean stop, crash, update); null on a first start. */
    val lastRestartCause: String? = null,
    val lastRestartAt: Long? = null,
    val stopReason: String? = null,
)

/**
 * DAEMON-HEALTH-1: a bounded, append-only restart / disconnect log (`<dir>/events.jsonl`) and the current-run record
 * (`<dir>/status.json`), both in the state dir. They exist so `infantry health` answers from files even when the daemon
 * is down and the MCP bridge is broken, and so "why did the tools disconnect at 12:40" has an answer from data.
 * [maxEvents] is the named limit `limits.health.eventLogMax`; the file is trimmed to it, oldest first.
 */
class HealthJournal(
    private val dir: File,
    private val maxEvents: () -> Int,
    private val now: () -> Long = System::currentTimeMillis,
    private val pidAlive: (Long) -> Boolean = { pid -> ProcessHandle.of(pid).map { it.isAlive }.orElse(false) },
) {
    private val lock = Any()
    private val eventsFile get() = File(dir, EVENTS)
    private val statusFile get() = File(dir, STATUS)

    /** The current-run record, or null when none was ever written or it is unreadable. */
    fun status(): HealthStatus? = read(dir)

    /** The last [n] events, oldest first. */
    fun tail(n: Int): List<HealthEvent> = readEvents(dir, n)

    /**
     * A new run starts: the previous record says WHY the last run ended. A record still marked running whose pid is
     * gone, or is not this process, means no clean stop was recorded (a crash or a kill); a clean stop keeps its
     * reason (`clean stop`, `update`, ...). Writes the new record and a `daemon_start` event.
     */
    fun begin(pid: Long, port: Int, build: String): HealthStatus = synchronized(lock) {
        val previous = status()
        val cause = when {
            previous == null -> null
            !previous.running -> previous.stopReason ?: "clean stop"
            previous.pid != pid && !pidAlive(previous.pid) -> "crash or kill (no clean stop recorded)"
            else -> "restart over a live record (pid ${previous.pid})"
        }
        val t = now()
        val s = HealthStatus(pid, port, build, startedAt = t, lastSeen = t, lastRestartCause = cause, lastRestartAt = if (previous != null) t else null)
        write(s)
        append(HealthEvent(t, "daemon_start", cause, "pid $pid port $port build $build"))
        s
    }

    /** Called on a timer: proves the daemon is alive now. */
    fun heartbeat() = synchronized(lock) { status()?.takeIf { it.running }?.let { write(it.copy(lastSeen = now())) } }

    /** A clean stop: [reason] is what the next start reports as the cause. */
    fun end(reason: String) = synchronized(lock) {
        val t = now()
        status()?.let { write(it.copy(running = false, lastSeen = t, stopReason = reason)) }
        append(HealthEvent(t, "daemon_stop", reason))
    }

    /** Record one event (an MCP bridge connecting or dropping, a plugin disabled, ...). */
    fun record(kind: String, cause: String? = null, detail: String? = null) = synchronized(lock) { append(HealthEvent(now(), kind, cause, detail)) }

    private fun write(s: HealthStatus) {
        dir.mkdirs()
        val tmp = File(dir, "$STATUS.tmp")
        tmp.writeText(json.encodeToString(HealthStatus.serializer(), s), Charsets.UTF_8)
        java.nio.file.Files.move(tmp.toPath(), statusFile.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
    }

    private fun append(e: HealthEvent) {
        dir.mkdirs()
        eventsFile.appendText(json.encodeToString(HealthEvent.serializer(), e) + "\n", Charsets.UTF_8)
        val limit = maxEvents().coerceAtLeast(1)
        val lines = eventsFile.readLines(Charsets.UTF_8)
        if (lines.size > limit * 2) eventsFile.writeText(lines.takeLast(limit).joinToString("\n", postfix = "\n"), Charsets.UTF_8)
    }

    companion object {
        const val DIR = "health"
        const val EVENTS = "events.jsonl"
        const val STATUS = "status.json"
        private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

        fun dirIn(stateDir: File) = File(stateDir, DIR)

        /** Offline read, used by the CLI when the daemon does not answer. */
        fun read(dir: File): HealthStatus? = runCatching {
            File(dir, STATUS).takeIf { it.isFile }?.let { json.decodeFromString(HealthStatus.serializer(), it.readText(Charsets.UTF_8)) }
        }.getOrNull()

        fun readEvents(dir: File, n: Int): List<HealthEvent> = runCatching {
            File(dir, EVENTS).takeIf { it.isFile }?.readLines(Charsets.UTF_8).orEmpty().takeLast(n.coerceAtLeast(0))
                .mapNotNull { runCatching { json.decodeFromString(HealthEvent.serializer(), it) }.getOrNull() }
        }.getOrDefault(emptyList())
    }
}
