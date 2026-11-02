package com.superiorsoftllc.agenthq.usage

import com.superiorsoftllc.agenthq.daemon.api.usage.MeterReading
import com.superiorsoftllc.agenthq.daemon.api.usage.MeterRequest
import com.superiorsoftllc.agenthq.daemon.api.usage.Meters

/**
 * PACE-TREND-1: the daemon's [Meters] over [UsagePacing]: every live window of every meter as one [MeterReading], with the
 * pacing rule's own projection at reset ([com.superiorsoftllc.agenthq.daemon.api.usage.PacingRule.judge]). Read-only; it reads
 * the cached live read, never the provider.
 */
class PacingMeters(
    private val pacing: UsagePacing,
    /** USAGE-FIT: the recorded model requests since a time (the usage meter, read-only); none by default. */
    private val requestSource: (Long) -> List<MeterRequest> = { emptyList() },
    private val clock: () -> Long = System::currentTimeMillis,
) : Meters {
    override fun requests(sinceMs: Long): List<MeterRequest> = requestSource(sinceMs)

    override fun read(): List<MeterReading> {
        val report = pacing.report()
        val now = clock()
        // The projection is the pace formula whatever mode the owner set for the worker allowance.
        val paceRule = report.rule.copy(mode = com.superiorsoftllc.agenthq.daemon.api.usage.PacingRule.MODE_PACE)
        return report.meters.flatMap { m ->
            m.windows.map { w ->
                MeterReading(
                    provider = m.meter, window = w.label, windowMinutes = w.windowMinutes, percent = w.percent,
                    resetsAt = w.resetsAt, observedAt = w.observedAt, paceDelta = w.paceDelta,
                    projectedPercent = paceRule.judge(w, now)?.projected, source = w.source,
                )
            }
        }
    }
}
