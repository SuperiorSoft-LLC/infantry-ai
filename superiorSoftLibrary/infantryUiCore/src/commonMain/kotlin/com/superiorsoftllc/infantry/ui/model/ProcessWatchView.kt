// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.ui.model

import com.superiorsoftllc.infantry.ui.protocol.ProcessReport
import com.superiorsoftllc.infantry.ui.protocol.TrackedProcess
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull

/**
 * ROGUE-1 (contract 1.120.0): what the status-bar badge and the processes panel show. Daemon state only: the report is the
 * daemon's last `processes` answer or `process_pressure` push, and auto-reap is the daemon's `processes.autoReap` setting.
 */
object ProcessWatchView {
    const val SINCE_MAJOR = 1
    const val SINCE_MINOR = 120

    /** The daemon's key (infantry `SettingsService.PROCESSES_AUTO_REAP`). */
    const val AUTO_REAP_KEY = "processes.autoReap"

    /** How often the UI asks for a fresh report while connected (one cheap frame; tasklist runs on the daemon). */
    const val POLL_MS = 60_000L

    /**
     * The badge's short text, or null before the first report. Always shown once read (so the panel and its auto-reap
     * toggle stay one click away): `RAM 12.3 GB free` at rest, plus `N rogue processes` when there are any.
     */
    fun badge(report: ProcessReport?): String? {
        report ?: return null
        val rogue = report.rogue.size
        return buildList {
            add("RAM ${report.freeMb?.let { gb(it) } ?: "?"} free")
            report.agentCpuPercent?.let { add("agents CPU ${pct(it)}") }
            if (rogue > 0) add("$rogue rogue process${if (rogue == 1) "" else "es"}")
        }.joinToString(" · ")
    }

    /** Amber when anything is wrong (under the floor, or rogue processes); muted otherwise. */
    fun alarming(report: ProcessReport?): Boolean = report != null && (report.underPressure || report.rogue.isNotEmpty() || cpuHigh(report))

    /** One row of the panel: `pid 123 · 1.2 GB · 1h30m · session abcdef12 · java GradleDaemon — session removed`. */
    fun row(p: TrackedProcess, now: Long): String = buildString {
        append("pid ").append(p.pid)
        append(" · ").append(p.memoryMb?.let { mb(it) } ?: "? MB")
        p.cpuPercent?.let { append(" \u00b7 CPU ").append(pct(it)) }
        p.startedAt?.let { append(" · ").append(age(now - it)) }
        p.sessionId?.let { append(" · session ").append(it.take(8)) }
        append(" · ").append(p.command)
        p.reason?.let { append(" — ").append(it) }
    }

    /** Contract 1.132.0: agent CPU has stayed at or above the alert threshold (no alarm while the alert is off: 0 minutes). */
    fun cpuHigh(report: ProcessReport): Boolean = report.cpuAlertMinutes > 0 && report.cpuHighSince != null

    /** `45%`; one decimal under 10 (`2.5%`). */
    fun pct(v: Double): String {
        val tenths = kotlin.math.round(v * 10).toLong()
        return if (tenths >= 100) "${(tenths + 5) / 10}%" else "${tenths / 10}.${tenths % 10}%"
    }

    fun mb(v: Long): String = if (v >= 1024) gb(v) else "$v MB"

    fun gb(v: Long): String {
        val tenths = (v * 10 + 512) / 1024
        return "${tenths / 10}.${tenths % 10} GB"
    }

    fun age(ms: Long): String {
        val m = (ms / 60_000).coerceAtLeast(0)
        return when {
            m < 60 -> "${m}m"
            m < 1440 -> "${m / 60}h${m % 60}m"
            else -> "${m / 1440}d${(m % 1440) / 60}h"
        }
    }

    /** `processes.autoReap` in a `settings` frame, or null when the frame does not carry it. */
    fun autoReapFromSettingsFrame(data: String): Boolean? {
        val obj = runCatching { Json.parseToJsonElement(data) as? JsonObject }.getOrNull() ?: return null
        val v = obj[AUTO_REAP_KEY] as? JsonPrimitive ?: return null
        return if (v.isString) null else v.booleanOrNull
    }
}
