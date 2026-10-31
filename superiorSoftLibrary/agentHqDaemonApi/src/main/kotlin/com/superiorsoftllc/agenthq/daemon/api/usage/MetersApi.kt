package com.superiorsoftllc.agenthq.daemon.api.usage

/*
 * PACE-TREND-1 (DaemonApi 1.10.0, additive): the plan METERS as a read-only host service. A plugin reads the CURRENT reading of
 * every provider window (the provider's own number, the authority for pacing) and keeps whatever history it wants; core keeps
 * none. A community plugin needs the grant `usage.read`. The same numbers `agenthq_usage` shows; reading never polls the
 * provider (it reads the daemon's cached live read).
 */

/** One provider window as read now. [paceDelta] and [projectedPercent] are null when the percent or reset time is unknown. */
data class MeterReading(
    /** The meter (`claude`, `codex`, `opencode-go`, ...): the provider's plan a run spends. */
    val provider: String,
    /** "5h limit", "Weekly limit", "Current session", ... */
    val window: String,
    val windowMinutes: Int,
    /** Percent of the window used, 0..100+; null = unknown (such a reading is never recorded). */
    val percent: Double?,
    /** Epoch millis the window resets; null = unknown. */
    val resetsAt: Long?,
    /** Epoch millis of the live read these numbers come from. */
    val observedAt: Long,
    /** Points ahead (+) or under (-) an even burn through the window. */
    val paceDelta: Double?,
    /** Percent at reset if the average burn since the window start continues (the pacing rule's own projection). */
    val projectedPercent: Double?,
    /** `live` (the provider's usage endpoint). */
    val source: String = "live",
)

interface Meters {
    /** Every live window of every meter, now; empty when nothing is readable. */
    fun read(): List<MeterReading>

    /** USAGE-FIT (1.20.0): the recorded model requests with `ts >= sinceMs`, oldest first; empty when the daemon keeps none. Read-only. */
    fun requests(sinceMs: Long): List<MeterRequest> = emptyList()
}

/** A daemon without meters: reads nothing. */
object NoMeters : Meters {
    override fun read(): List<MeterReading> = emptyList()
}

/**
 * USAGE-FIT (DaemonApi 1.20.0, additive): one recorded model request, read-only, from the daemon's usage meter. A field the
 * harness did not report is null (a missing output is normal, never zero).
 */
data class MeterRequest(
    val ts: Long,
    val sessionId: String,
    val role: String,
    val harness: String,
    val model: String?,
    val input: Long?,
    val output: Long?,
    val cacheRead: Long?,
    val cacheWrite: Long?,
)
