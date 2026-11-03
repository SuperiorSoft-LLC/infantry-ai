package com.superiorsoftllc.infantry.session

import kotlinx.serialization.Serializable

/**
 * PIPELINE-WATCHDOG: what the daemon believes an agent (session) is doing about the work it was asked for.
 * Derived by [com.superiorsoftllc.infantry.mailbox.AgentWatchdog] from the mailbox, the session's own activity and its
 * REPORT BACK status, never from the agent's word. Timestamps, not durations, so a quiet tick changes nothing on the wire.
 */
@Serializable
data class AgentWatch(
    /** A [WatchState] name. */
    val state: String,
    /** Epoch millis of the oldest unanswered item, null when nothing is pending. */
    val pendingSince: Long? = null,
    /** One line about that item (the mail subject or ticket), null when nothing is pending. */
    val pending: String? = null,
    /** Epoch millis of the last output or state change of the session. */
    val lastActivityAt: Long,
    /** Nudge mails sent for the current pending item. */
    val nudges: Int = 0,
)

enum class WatchState {
    /** A turn or background process is running and has produced events lately. */
    WORKING,
    /** Idle, the pending item is younger than the stall threshold, or the agent is blocked on someone else's reply. */
    WAITING,
    /** Idle with a pending item for longer than the stall threshold. */
    STALLED,
    /** Running but silent for longer than the quiet threshold. */
    QUIET,
    /** A ticket worker that finished without its report mail. */
    UNREPORTED,
    /** Idle and nothing was asked of it. */
    IDLE,
}

/**
 * Thresholds, all in minutes. Environment: `INFANTRY_WATCHDOG_STALL_MIN` (5), `INFANTRY_WATCHDOG_QUIET_MIN` (15),
 * `INFANTRY_WATCHDOG_NUDGE_MIN` (5, the gap between nudges and between escalation steps), `INFANTRY_WATCHDOG_NUDGE_CAP` (1),
 * `INFANTRY_WATCHDOG_PM_AFTER_NUDGES` (1), `INFANTRY_WATCHDOG_POLL_SEC` (30), `INFANTRY_WATCHDOG` (`0`/`off` disables it).
 */
data class WatchdogConfig(
    val enabled: Boolean = true,
    val stallMs: Long = 5 * MIN,
    val quietMs: Long = 15 * MIN,
    val nudgeIntervalMs: Long = 5 * MIN,
    val nudgeCap: Int = 1,
    /** Nudges that must go unanswered (each a full interval) before the PM is told. */
    val pmAfterNudges: Int = 1,
    val pollMs: Long = 30_000,
    /** A pending item older than this is history, not a stall. */
    val maxPendingAgeMs: Long = 6 * 60 * MIN,
) {
    companion object {
        const val MIN = 60_000L

        fun fromEnv(get: (String) -> String? = { infantrySetting(it) }): WatchdogConfig {
            val d = WatchdogConfig()
            fun min(name: String, default: Long) = get(name)?.toLongOrNull()?.takeIf { it > 0 }?.times(MIN) ?: default
            return WatchdogConfig(
                enabled = get("INFANTRY_WATCHDOG")?.lowercase() !in setOf("0", "off", "false"),
                stallMs = min("INFANTRY_WATCHDOG_STALL_MIN", d.stallMs),
                quietMs = min("INFANTRY_WATCHDOG_QUIET_MIN", d.quietMs),
                nudgeIntervalMs = min("INFANTRY_WATCHDOG_NUDGE_MIN", d.nudgeIntervalMs),
                nudgeCap = get("INFANTRY_WATCHDOG_NUDGE_CAP")?.toIntOrNull()?.takeIf { it > 0 } ?: d.nudgeCap,
                pmAfterNudges = get("INFANTRY_WATCHDOG_PM_AFTER_NUDGES")?.toIntOrNull()?.takeIf { it > 0 } ?: d.pmAfterNudges,
                pollMs = get("INFANTRY_WATCHDOG_POLL_SEC")?.toLongOrNull()?.takeIf { it > 0 }?.times(1000) ?: d.pollMs,
            )
        }
    }
}
