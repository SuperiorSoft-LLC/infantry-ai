// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.session

/** Threshold decision for an unattended run. Human-started sessions are deliberately excluded. */
internal object UnattendedRunWatchdog {
    data class Run(val startedAt: Long, val lastHeartbeat: Long, val unattended: Boolean)

    /** [alive]: something the session waits on is visibly running (a registered bg job, a live gate run): silence is not a stall. */
    /** Ticket 7e9bed2a: a watchdog kill at [firedAt] that left the run registered is retried (forced) once, [retryMs] later. */
    fun retryDue(firedAt: Long, now: Long, retryMs: Long, alreadyRetried: Boolean): Boolean = firedAt > 0 && !alreadyRetried && now - firedAt >= retryMs

    fun expiry(run: Run, now: Long, deadlineMs: Long, stallMs: Long, alive: Boolean = false): WatchdogExpiry? {
        if (!run.unattended) return null
        if (now - run.startedAt >= deadlineMs) return WatchdogExpiry.DEADLINE
        if (!alive && now - run.lastHeartbeat >= stallMs) return WatchdogExpiry.STALLED
        return null
    }
}

enum class WatchdogExpiry { DEADLINE, STALLED }
