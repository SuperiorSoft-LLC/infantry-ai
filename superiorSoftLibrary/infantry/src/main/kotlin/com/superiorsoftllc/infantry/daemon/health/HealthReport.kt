// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.daemon.health

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/** DAEMON-HEALTH-1: one connected MCP bridge (a session's `infantry mcp` process) as the daemon sees it. */
@Serializable
data class McpBridgeRow(val sessionId: String, val connected: Boolean, val connectedAt: Long, val lastSeen: Long, val reconnects: Int = 0)

/** A plugin's row: [status] is `loaded`, `refused: why` or `failed: why` (a plugin PluginGuard disabled keeps its reason). */
@Serializable
data class PluginRow(val id: String, val status: String)

/** What an agent would otherwise probe for with shell commands. All of it is read by the daemon, none by the agent's shell. */
@Serializable
data class EnvironmentFacts(
    val os: String,
    val arch: String,
    val shells: String,
    val projectPath: String? = null,
    val worktreePath: String? = null,
    val gitBranch: String? = null,
    val jdk: String,
    val gradle: String? = null,
    val ownerPorts: List<Int>,
    val testPortRange: String,
    val harnesses: List<String> = emptyList(),
    val stateDir: String,
    /** CLI-PATH-1: whether `infantry` is on this machine's PATH, where that entry is and whether it still resolves. */
    val cliPath: String? = null,
)

@Serializable
data class DaemonRow(
    val up: Boolean,
    val pid: Long? = null,
    val port: Int? = null,
    val build: String? = null,
    val startedAt: Long? = null,
    val uptimeMs: Long? = null,
    val lastSeen: Long? = null,
    val lastRestartAt: Long? = null,
    val lastRestartCause: String? = null,
    /** `live` = answered by the running daemon, `files` = read from the state dir because it did not answer. */
    val source: String = "live",
)

@Serializable
data class HealthReport(
    val at: Long,
    val daemon: DaemonRow,
    val plugins: List<PluginRow> = emptyList(),
    /** Rows plugins contribute through the existing `status` hook (key -> text). */
    val pluginRows: Map<String, String> = emptyMap(),
    val mcp: List<McpBridgeRow> = emptyList(),
    val hooks: Int? = null,
    val hookFailures: Int? = null,
    val freeMemoryMb: Long? = null,
    val freeDiskMb: Long? = null,
    val environment: EnvironmentFacts,
    val events: List<HealthEvent> = emptyList(),
) {
    fun toJson(): String = json.encodeToString(serializer(), this)

    companion object {
        private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; prettyPrint = true }
        fun parse(text: String): HealthReport = json.decodeFromString(serializer(), text)
    }
}

/** DAEMON-HEALTH-1: the owner's own ports and the free test range, stated once so no agent probes for them. */
object HealthPorts {
    /** The owner's daemon and UI (8787, 8788) and the PM's MCP pair (8797, 8798): off limits to every agent and test. */
    val OWNER = listOf(8787, 8788, 8797, 8798)
    const val TEST_RANGE = "18900-18999"
}

/** The environment facts that need no daemon object: callers add the session-specific ones (paths, branch, harnesses). */
object EnvironmentProbe {
    fun shells(os: String): String =
        if (os.startsWith("Windows", ignoreCase = true))
            "PowerShell (pwsh 7: && and || work; no head/tail/grep/rm -rf) and Git Bash (POSIX; /dev/null; forward slashes); each tool call is one shell, state does not persist"
        else "bash/zsh (POSIX)"

    /** The Gradle version the wrapper in [projectPath] pins, read from `gradle/wrapper/gradle-wrapper.properties`. */
    fun gradle(projectPath: String?): String? = projectPath?.let { p ->
        runCatching {
            File(p, "gradle/wrapper/gradle-wrapper.properties").takeIf { it.isFile }?.readLines()
                ?.firstOrNull { it.startsWith("distributionUrl") }?.let { Regex("gradle-([0-9][^-]*)-").find(it)?.groupValues?.get(1) }
        }.getOrNull()
    }

    fun facts(stateDir: File, projectPath: String?, worktreePath: String?, gitBranch: String?, harnesses: List<String>): EnvironmentFacts {
        val os = System.getProperty("os.name", "?") + " " + System.getProperty("os.version", "")
        return EnvironmentFacts(
            os = os.trim(), arch = System.getProperty("os.arch", "?"), shells = shells(os),
            projectPath = projectPath, worktreePath = worktreePath, gitBranch = gitBranch,
            jdk = System.getProperty("java.version", "?"), gradle = gradle(worktreePath ?: projectPath),
            ownerPorts = HealthPorts.OWNER, testPortRange = HealthPorts.TEST_RANGE, harnesses = harnesses, stateDir = stateDir.path,
            cliPath = cliPathLine(),
        )
    }

    /**
     * CLI-PATH-1: the PATH status line the health report carries, so `infantry health` (and every agent reading it)
     * says whether `infantry` runs from any terminal instead of probing. Read-only: it never writes a PATH.
     */
    fun cliPathLine(
        launcherDir: File? = com.superiorsoftllc.infantry.daemon.InfantryBin.resolve(),
        getenv: (String) -> String? = com.superiorsoftllc.infantry.session.InfantryEnv::get,
    ): String? = launcherDir?.let { dir ->
        runCatching { com.superiorsoftllc.infantry.install.CliPath.status(com.superiorsoftllc.infantry.daemon.CliPathAuto.store(getenv), dir).line() }.getOrNull()
    }

    /** DAEMON-HEALTH-1: the one-line form injected into a session's start brief. */
    fun briefLine(e: EnvironmentFacts): String =
        "Environment: ${e.os} ${e.arch}; shells: ${e.shells.substringBefore(" (")}/Bash; project ${e.projectPath ?: e.worktreePath ?: "unknown"}; " +
            "off-limits ports ${e.ownerPorts.joinToString(",")} (test daemons use ${e.testPortRange}). Run `infantry health` instead of probing."
}

/** DAEMON-HEALTH-1: the human text, one source for the CLI, the MCP tool and the Lua call. */
object HealthText {
    fun render(r: HealthReport): String = buildString {
        val d = r.daemon
        appendLine(if (d.up) "daemon: UP (${d.source})" else "daemon: DOWN (${d.source}${d.lastSeen?.let { ", last seen ${age(r.at - it)} ago" } ?: ""})")
        d.pid?.let { appendLine("  pid $it, port ${d.port ?: "?"}, build ${d.build ?: "?"}") }
        d.uptimeMs?.let { appendLine("  uptime ${age(it)}") }
        if (d.lastRestartCause != null) appendLine("  last restart: ${d.lastRestartCause}${d.lastRestartAt?.let { " (${age(r.at - it)} ago)" } ?: ""}")
        val bad = r.plugins.filter { it.status != "loaded" }
        appendLine("plugins: ${r.plugins.count { it.status == "loaded" }} loaded, ${bad.size} not")
        bad.forEach { appendLine("  ${it.id}: ${it.status}") }
        r.pluginRows.forEach { (k, v) -> appendLine("  [$k] $v") }
        appendLine("mcp bridges: ${r.mcp.count { it.connected }} connected, ${r.mcp.count { !it.connected }} dropped")
        r.mcp.forEach { appendLine("  ${it.sessionId}: ${if (it.connected) "connected" else "DROPPED"}, last seen ${age(r.at - it.lastSeen)} ago, ${it.reconnects} reconnect(s)") }
        if (r.hooks != null) appendLine("hooks: ${r.hooks}${r.hookFailures?.let { ", $it with failures" } ?: ""}")
        if (r.freeMemoryMb != null || r.freeDiskMb != null) appendLine("free: memory ${r.freeMemoryMb ?: "?"} MB, disk ${r.freeDiskMb ?: "?"} MB")
        val e = r.environment
        appendLine("environment:")
        appendLine("  os ${e.os} ${e.arch}; jdk ${e.jdk}; gradle ${e.gradle ?: "?"}")
        appendLine("  shells: ${e.shells}")
        appendLine("  project ${e.projectPath ?: "-"}; worktree ${e.worktreePath ?: "-"}; branch ${e.gitBranch ?: "-"}")
        appendLine("  OWNER ports (off limits): ${e.ownerPorts.joinToString(", ")}; free for test daemons: ${e.testPortRange}")
        appendLine("  harnesses: ${e.harnesses.joinToString(", ").ifBlank { "none" }}")
        appendLine("  state dir: ${e.stateDir}")
        e.cliPath?.let { appendLine("  CLI: $it") }
        if (r.events.isNotEmpty()) {
            appendLine("recent restarts / disconnects:")
            r.events.forEach { appendLine("  ${java.time.Instant.ofEpochMilli(it.at)} ${it.kind}${it.cause?.let { c -> " ($c)" } ?: ""}${it.detail?.let { x -> " $x" } ?: ""}") }
        }
    }.trimEnd()

    fun age(ms: Long): String {
        val s = (ms / MS_PER_SECOND).coerceAtLeast(0)
        return when {
            s < SECONDS_PER_MINUTE -> "${s}s"
            s < SECONDS_PER_HOUR -> "${s / SECONDS_PER_MINUTE}m"
            s < SECONDS_PER_DAY -> "${s / SECONDS_PER_HOUR}h ${s % SECONDS_PER_HOUR / SECONDS_PER_MINUTE}m"
            else -> "${s / SECONDS_PER_DAY}d"
        }
    }
    private const val MS_PER_SECOND = 1000L
    private const val SECONDS_PER_MINUTE = 60L
    private const val SECONDS_PER_HOUR = 3600L
    private const val SECONDS_PER_DAY = 86_400L
}

/** DAEMON-HEALTH-1: tracks MCP bridge connections so the report and the log can say which dropped and when. */
class McpBridgeTracker(private val journal: HealthJournal?, private val now: () -> Long = System::currentTimeMillis) {
    private val rows = java.util.concurrent.ConcurrentHashMap<String, McpBridgeRow>()

    fun connected(sessionId: String) {
        val prev = rows[sessionId]
        val t = now()
        rows[sessionId] = McpBridgeRow(sessionId, true, t, t, (prev?.reconnects ?: -1) + 1)
        journal?.record("mcp_connect", if (prev != null) "reconnect" else null, "session $sessionId")
    }

    fun seen(sessionId: String) { rows.computeIfPresent(sessionId) { _, r -> r.copy(lastSeen = now()) } }

    fun disconnected(sessionId: String, cause: String?) {
        val t = now()
        rows.computeIfPresent(sessionId) { _, r -> r.copy(connected = false, lastSeen = t) }
        journal?.record("mcp_disconnect", cause, "session $sessionId")
    }

    fun rows(): List<McpBridgeRow> = rows.values.sortedBy { it.sessionId }
}
