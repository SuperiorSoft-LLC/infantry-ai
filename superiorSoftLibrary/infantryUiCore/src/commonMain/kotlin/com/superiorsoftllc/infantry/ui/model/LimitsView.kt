// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.ui.model

import com.superiorsoftllc.infantry.ui.protocol.LimitWindow
import com.superiorsoftllc.infantry.ui.protocol.ProviderLimits
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * U2 (contract 1.59.0): the words the plan-limit panel shows, kept pure so they are tested without a window.
 * Honesty rule, the daemon's: an unknown percent is "—" plus why, never a bar and never 0%.
 */
object LimitsView {
    /** Number of cells in a window's segmented bar; each one is 100 / [SEGMENTS] points. */
    const val SEGMENTS = 20

    fun providerName(provider: String): String = when (provider) {
        "claude" -> "Claude"
        "codex" -> "Codex"
        "opencode-go" -> "OpenCode Go"
        "opencode" -> "OpenCode"
        else -> provider
    }

    /** The daemon's fixed reason codes, as a person reads them. */
    fun reasonText(reason: String?): String = when {
        reason == null -> "unknown"
        reason == "no_credentials" -> "not signed in"
        reason == "expired" -> "expired"
        reason == "no_subscription" -> "no subscription"
        reason == "rate_limited" -> "rate limited"
        reason == "network" -> "network error"
        reason == "bad_response" -> "unreadable answer"
        reason == "source_failed" -> "read failed"
        reason == "live_sources_off" -> "live reads off"
        reason.startsWith("http_") -> "HTTP " + reason.removePrefix("http_")
        else -> reason
    }

    /** "42%", or "—" when unknown. */
    fun percentText(percent: Double?): String = percent?.let { "${it.coerceIn(0.0, 100.0).roundToInt()}%" } ?: "—"

    /** True when the window is used faster than an even pace (drawn orange/red); null pace counts as not ahead. */
    fun ahead(w: LimitWindow): Boolean = (w.paceDelta ?: 0.0) > 0.0

    /** "12 pts under pace", "10 pts ahead of pace", "on pace"; null when the pace is unknown. */
    fun paceText(delta: Double?): String? {
        delta ?: return null
        val pts = abs(delta).roundToInt()
        return when {
            pts == 0 -> "on pace"
            delta > 0 -> "$pts pts ahead of pace"
            else -> "$pts pts under pace"
        }
    }

    /** "Resets Oct 19 3:00 PM" in [zone]; null when the reset is unknown. */
    fun resetText(resetsAt: Long?, zone: TimeZone = TimeZone.currentSystemDefault()): String? {
        resetsAt ?: return null
        val t = Instant.fromEpochMilliseconds(resetsAt).toLocalDateTime(zone)
        val month = t.month.name.lowercase().replaceFirstChar { it.uppercase() }.take(3)
        return "Resets $month ${t.dayOfMonth} ${TimeFormat.clock12(t.hour, t.minute)}"
    }

    /** Cells filled for [percent]: any use shows at least one cell, so a sliver is never drawn as empty. */
    fun filledSegments(percent: Double): Int {
        val p = percent.coerceIn(0.0, 100.0)
        if (p <= 0.0) return 0
        return (p / 100.0 * SEGMENTS).roundToInt().coerceIn(1, SEGMENTS)
    }

    /** The bar's spoken description, e.g. "Current week: 81% used, ahead of pace". */
    fun barDescription(w: LimitWindow): String =
        "${w.label}: ${percentText(w.percent)} used, " + if (ahead(w)) "ahead of pace" else "under pace"

    /**
     * USAGE-CARD-1: the line a stale card shows over its last good reading, e.g.
     * "updated 14 min ago · rate limited, retrying at 12:40 PM"; null when [p] is not stale.
     */
    fun staleText(p: ProviderLimits, now: Long, zone: TimeZone = TimeZone.currentSystemDefault()): String? {
        if (!p.stale) return null
        val retry = p.retryAt?.takeIf { it > now }?.let {
            val t = Instant.fromEpochMilliseconds(it).toLocalDateTime(zone)
            ", retrying at ${TimeFormat.clock12(t.hour, t.minute)}"
        } ?: ", retrying soon"
        return "updated ${ageText(now - p.observedAt)} ago · ${reasonText(p.reason)}$retry"
    }

    /** "under a minute", "14 min", "2 h 5 min". */
    fun ageText(ms: Long): String {
        val min = (ms.coerceAtLeast(0) / 60_000).toInt()
        return when {
            min < 1 -> "under a minute"
            min < 60 -> "$min min"
            min % 60 == 0 -> "${min / 60} h"
            else -> "${min / 60} h ${min % 60} min"
        }
    }

    /** The freshest read among [limits], for "updated Xm ago"; null when nothing was read. */
    fun observedAt(limits: List<ProviderLimits>): Long? = limits.maxOfOrNull { it.observedAt }?.takeIf { it > 0 }
}
