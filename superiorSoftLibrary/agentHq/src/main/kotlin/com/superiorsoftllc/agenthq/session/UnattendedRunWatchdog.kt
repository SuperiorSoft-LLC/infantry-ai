package com.superiorsoftllc.agenthq.session

/** Threshold decision for an unattended run. Human-started sessions are deliberately excluded. */
internal object UnattendedRunWatchdog {
    data class Run(val startedAt: Long, val lastHeartbeat: Long, val unattended: Boolean)

    fun expiry(run: Run, now: Long, deadlineMs: Long, stallMs: Long): WatchdogExpiry? {
        if (!run.unattended) return null
        if (now - run.startedAt >= deadlineMs) return WatchdogExpiry.DEADLINE
        if (now - run.lastHeartbeat >= stallMs) return WatchdogExpiry.STALLED
        return null
    }
}

enum class WatchdogExpiry { DEADLINE, STALLED }
