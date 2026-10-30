package com.superiorsoftllc.agenthq.daemon.api.processes

import kotlinx.serialization.Serializable

/* ROGUE-1 wire models of the `processes` frame (moved from `session` by DAEMON-PLUGINS-3 so a daemon plugin can read the answer). */

/** One process as the detector reports it. */
@Serializable
data class TrackedProcess(
    val pid: Long,
    /** Command line (tracked) or image name (unattributed), cut to 80 characters. */
    val command: String,
    /** The owning session; null = unattributed. */
    val sessionId: String? = null,
    val startedAt: Long? = null,
    /** Resident memory in MB; null when the OS would not say. */
    val memoryMb: Long? = null,
    val rogue: Boolean = false,
    /** Why it is rogue: `session removed`, `session ended (EXITED)`, `session idle 42 min`. */
    val reason: String? = null,
    /** True when the owner was inferred (ancestry or a worktree path in the command line), not seen as a direct child. */
    val inferred: Boolean = false,
    /** Ticket 66153313: this process's share of ALL logical cores (0-100) over the last watch pass; null until two samples. */
    val cpuPercent: Double? = null,
)

@Serializable
data class ProcessReport(
    val freeMb: Long? = null,
    val totalMb: Long? = null,
    val floorMb: Long,
    val underPressure: Boolean = false,
    val autoReap: Boolean = false,
    /** Every live ledger entry: provably ours. */
    val tracked: List<TrackedProcess> = emptyList(),
    /** Heavy runtimes we cannot prove are ours, largest first. Read-only. */
    val unattributed: List<TrackedProcess> = emptyList(),
    /** Ticket 66153313: the sum of [TrackedProcess.cpuPercent] over [tracked] (share of all cores); null until sampled. */
    val agentCpuPercent: Double? = null,
    /** The sustained-CPU rule in force (settings `limits.cpu.alertPercent` / `limits.cpu.alertMinutes`; 0 min = off). */
    val cpuAlertPercent: Int = CpuAlertConfig().percent,
    val cpuAlertMinutes: Int = CpuAlertConfig().minutes,
    /** Since when (epoch ms) agent CPU has stayed at or above [cpuAlertPercent]; null while it is below. */
    val cpuHighSince: Long? = null,
) {
    /** Tracked rogue ones (reapable) and unattributed orphan daemons (shown, never reaped). */
    val rogue: List<TrackedProcess> get() = (tracked + unattributed).filter { it.rogue }

    /** The largest processes, tracked and not, for a pressure warning. */
    fun topOffenders(n: Int = 5): List<TrackedProcess> =
        (tracked + unattributed).filter { it.memoryMb != null }.sortedByDescending { it.memoryMb }.take(n)
}

/** Ticket 66153313: raise an attention item when agent processes hold [percent] % of all cores for [minutes] (0 = off). */
@Serializable
data class CpuAlertConfig(val percent: Int = 90, val minutes: Int = 10)

/** Contract 1.120.0: one `processes` request. [op] = `list` (default), `reap` (needs [pid]) or `reap_rogue`. */
@Serializable
data class ProcessesRequest(val op: String = "list", val pid: Long? = null)

/** The `processes` answer: the report after the op, what it killed, and why a `reap` was refused (null = not refused). */
@Serializable
data class ProcessesAnswer(
    val report: ProcessReport,
    val reaped: List<TrackedProcess> = emptyList(),
    val refused: String? = null,
)
