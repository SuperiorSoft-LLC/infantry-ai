// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.daemon.api.processes

/**
 * DAEMON-PLUGINS-3: the shared plain-text rendering of a `processes` answer, used by `infantry processes` and the
 * `infantry_processes` / `infantry_processes_reap` tools (byte-identical for both).
 */
object ProcessesText {
    /** Human text. [showAll] adds the healthy tracked processes and the unattributed ones. */
    fun render(a: ProcessesAnswer, showAll: Boolean, now: Long = System.currentTimeMillis()): String = buildString {
        a.refused?.let { appendLine("refused: $it") }
        for (p in a.reaped) appendLine("reaped pid ${p.pid} ${p.command}${p.reason?.let { " ($it)" } ?: ""}")
        appendReport(a.report, showAll, now)
    }

    private fun StringBuilder.appendReport(r: ProcessReport, showAll: Boolean, now: Long) {
        val free = r.freeMb?.let { "$it MB free" } ?: "free RAM unknown"
        val total = r.totalMb?.let { " of $it MB" } ?: ""
        val pressure = if (r.underPressure) " - UNDER the ${r.floorMb} MB floor" else ""
        appendLine("memory: $free$total$pressure; auto-reap ${if (r.autoReap) "on" else "off"}")
        val rogue = r.rogue
        appendLine("rogue (${rogue.size}):" + if (rogue.isEmpty()) " none" else "")
        rogue.forEach { appendLine("  " + line(it, now)) }
        if (rogue.isNotEmpty()) appendLine("  reap: infantry processes reap <pid>   or all: infantry processes reap --all")
        // PROC-LEAK-1: a hung client of a live session (a CLI call that never returned) is named even without --all.
        val stuck = r.tracked.filter { it.stuck != null && !it.rogue }
        if (stuck.isNotEmpty()) {
            appendLine("stuck (${stuck.size}):")
            stuck.forEach { appendLine("  " + line(it, now)) }
        }
        if (!showAll) {
            appendLine("(${r.tracked.count { !it.rogue }} healthy tracked, ${r.unattributed.count { !it.rogue }} unattributed: infantry processes --all)")
            return
        }
        val healthy = r.tracked.filter { !it.rogue }
        appendLine("tracked, healthy (${healthy.size}):" + if (healthy.isEmpty()) " none" else "")
        healthy.forEach { appendLine("  " + line(it, now)) }
        val others = r.unattributed.filter { !it.rogue }
        appendLine("not ours, never reaped (${others.size}):" + if (others.isEmpty()) " none" else "")
        others.forEach { appendLine("  " + line(it, now)) }
    }

    private fun line(p: TrackedProcess, now: Long): String = buildString {
        append("pid ").append(p.pid)
        append(" | ").append(p.memoryMb?.let { "$it MB" } ?: "? MB")
        p.startedAt?.let { append(" | ").append(age(now - it)) }
        append(" | session ").append(p.sessionId?.take(8) ?: "none").append(if (p.inferred) " (inferred)" else "")
        append(" | ").append(p.command)
        p.reason?.let { append(" | ").append(it) }
        p.stuck?.let { append(" | stuck: ").append(it) }
    }

    fun age(ms: Long): String {
        val m = (ms / 60_000).coerceAtLeast(0)
        return when {
            m < 60 -> "${m}m"
            m < 1440 -> "${m / 60}h${m % 60}m"
            else -> "${m / 1440}d${(m % 1440) / 60}h"
        }
    }
}
