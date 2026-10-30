package com.superiorsoftllc.agenthq.plugins.official.pace.daemon

import com.superiorsoftllc.agenthq.daemon.api.usage.MeterReading
import com.superiorsoftllc.agenthq.daemon.api.usage.Meters
import com.superiorsoftllc.agenthq.daemon.api.usage.NoMeters
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PaceTest {
    private val h = 3_600_000L
    private val weekMin = 7 * 24 * 60

    private fun tmp(): File = File.createTempFile("pace", ".jsonl").also { it.delete(); it.deleteOnExit() }

    private fun reading(t: Long, pct: Double, resets: Long?, pace: Double? = 0.0, proj: Double? = pct, win: String = "Weekly limit", provider: String = "claude") =
        MeterReading(provider, win, weekMin, pct, resets, t, pace, proj)

    private fun sample(t: Long, pct: Double, resets: Long? = null, pace: Double? = null, proj: Double? = null) =
        PaceSample("claude", "Weekly limit", t, pct, pace, proj, resets, weekMin)

    // ---- recording, dedupe, retention, round trip -------------------------------------------------------------------

    @Test fun `a reading becomes a sample and an identical one is not kept twice`() {
        val store = PaceStore(tmp())
        assertTrue(store.record(reading(1000, 10.0, 9_000_000)))
        assertFalse(store.record(reading(1000, 10.0, 9_000_000)), "same observedAt, percent and reset = a repeat")
        assertTrue(store.record(reading(2000, 10.0, 9_000_000)), "a newer read is kept even at the same percent")
        assertFalse(store.record(reading(1500, 11.0, 9_000_000)), "an older read than the newest sample is ignored")
        assertEquals(2, store.count())
    }

    @Test fun `an unknown percent is never recorded`() {
        val store = PaceStore(tmp())
        assertFalse(store.record(MeterReading("claude", "Weekly limit", weekMin, null, null, 1, null, null)))
        assertEquals(0, store.count())
    }

    @Test fun `history survives a restart (round trip)`() {
        val f = tmp()
        val a = PaceStore(f)
        a.record(reading(1000, 10.0, 5_000_000, pace = 1.5, proj = 12.0))
        a.record(reading(2000, 11.0, 5_000_000, pace = null, proj = null, win = "5h limit", provider = "codex"))
        val b = PaceStore(f)
        assertEquals(setOf("claude" to "Weekly limit", "codex" to "5h limit"), b.keys().toSet())
        val s = b.samples("claude", "Weekly limit").single()
        assertEquals(PaceSample("claude", "Weekly limit", 1000, 10.0, 1.5, 12.0, 5_000_000, weekMin), s)
        assertNull(b.samples("codex", "5h limit").single().paceDelta)
    }

    @Test fun `a torn line is skipped`() {
        val f = tmp()
        f.writeText("not json\n" + PaceStore.encode(sample(5, 1.0)) + "\n{\"p\":\n")
        assertEquals(1, PaceStore(f).count())
    }

    @Test fun `retention prunes old samples, 0 keeps all`() {
        val now = 100L * 24 * h
        val f = tmp()
        val store = PaceStore(f) { now }
        store.record(reading(now - 70L * 24 * h, 1.0, null))
        store.record(reading(now - 10L * 24 * h, 2.0, null))
        store.record(reading(now - 1L * h, 3.0, null))
        assertEquals(0, store.prune(0))
        assertEquals(3, store.count())
        assertEquals(1, store.prune(60))
        assertEquals(2, store.count())
        assertEquals(2, PaceStore(f).count(), "the file was rewritten")
    }

    @Test fun `the recorder samples the meters and prunes on start`() {
        val f = tmp()
        val now = 100L * 24 * h
        val store = PaceStore(f) { now }
        store.record(reading(now - 90L * 24 * h, 1.0, null))
        val meters = object : Meters { override fun read() = listOf(reading(now, 5.0, now + h)) }
        val rec = PaceRecorder(store, meters) { PaceSettings() }
        assertEquals(1, rec.pruneNow())
        assertEquals(1, rec.tick())
        assertEquals(0, rec.tick(), "the next tick with the same cached read adds nothing")
        assertEquals(0, PaceRecorder(store, NoMeters) { PaceSettings() }.tick())
    }

    @Test fun `a meter read that throws does not take the recorder down`() {
        val boom = object : Meters { override fun read(): List<MeterReading> = error("missing grant 'usage.read'") }
        assertEquals(0, PaceRecorder(PaceStore(tmp()), boom) { PaceSettings() }.tick())
    }

    // ---- reset boundaries --------------------------------------------------------------------------------------------

    @Test fun `a percent drop at a changed resetsAt splits the series and is not a fall`() {
        val r1 = 100L * h
        val r2 = r1 + 168 * h
        val series = listOf(
            sample(90 * h, 80.0, r1), sample(94 * h, 90.0, r1), sample(98 * h, 96.0, r1),
            sample(101 * h, 3.0, r2), sample(103 * h, 4.0, r2), sample(105 * h, 5.0, r2),
        )
        val segs = PaceMath.splitAtResets(series)
        assertEquals(listOf(3, 3), segs.map { it.size })
        assertEquals(3.0, PaceMath.active(series).first().percent)
    }

    @Test fun `a drop without a reset time still splits`() {
        val segs = PaceMath.splitAtResets(listOf(sample(1, 80.0), sample(2, 90.0), sample(3, 2.0), sample(4, 3.0)))
        assertEquals(listOf(2, 2), segs.map { it.size })
    }

    @Test fun `momentum fits the active window only so a reset never reads as FALLING`() {
        val r2 = 400L * h
        // old window ends high, new window starts low and creeps up; projected rises slowly in the new one
        val old = (0..5).map { sample(300 * h + it * h, 80.0 + it * 3, 350L * h, pace = 5.0, proj = 100.0) }
        val fresh = (0..4).map { sample(380 * h + it * h, 2.0 + it, r2, pace = 0.0 + it, proj = 20.0 + it * 4.0) }
        val m = PaceMath.momentum(old + fresh, 384 * h, 6.0)
        assertEquals(Momentum.RISING, m.momentum)
    }

    // ---- momentum ---------------------------------------------------------------------------------------------------

    private fun line(slope: Double, n: Int = 7): List<PaceSample> =
        (0 until n).map { sample(it * h, 50.0 + it * 0.1, 1000L * h, pace = 0.0, proj = 100.0 + slope * it) }

    @Test fun `projected slope classifies rising flat falling with the dead band`() {
        assertEquals(Momentum.RISING, PaceMath.momentum(line(2.0), 6 * h, 6.0).momentum)
        assertEquals(Momentum.FALLING, PaceMath.momentum(line(-2.0), 6 * h, 6.0).momentum)
        assertEquals(Momentum.FLAT, PaceMath.momentum(line(0.1), 6 * h, 6.0).momentum)
        val wide = PaceSettings(deadBand = 3.0)
        assertEquals(Momentum.FLAT, PaceMath.momentum(line(2.0), 6 * h, 6.0, wide).momentum)
        assertEquals(2.0, PaceMath.momentum(line(2.0), 6 * h, 6.0).projectedSlope!!, 1e-9)
    }

    @Test fun `momentum is unknown without enough samples`() {
        val m = PaceMath.momentum(line(2.0, n = 2), 1 * h, 6.0)
        assertEquals(Momentum.UNKNOWN, m.momentum)
        // three samples squeezed into 20 minutes do not span the 2h minimum
        val tight = (0..2).map { sample(it * 600_000L, 5.0, 1000L * h, proj = 5.0) }
        assertEquals(Momentum.UNKNOWN, PaceMath.momentum(tight, 1_200_000, 6.0).momentum)
    }

    @Test fun `slope of a straight line is exact`() {
        assertEquals(0.5, PaceMath.slope((0..4).map { it * h to 10.0 + 0.5 * it })!!, 1e-9)
        assertNull(PaceMath.slope(listOf(0L to 1.0)))
    }

    // ---- forecast: the worked example of docs/PACING.md -------------------------------------------------------------

    /** Weekly window (168 h) half gone (84 h), 53.5% used; the last 6 h burned 0.5 points per hour. */
    private fun worked(): Pair<List<PaceSample>, Long> {
        val windowStart = 0L
        val reset = 168 * h
        val now = 84 * h
        val series = (0..6).map { i ->
            val t = now - (6 - i) * h
            val pct = 50.5 + 0.5 * i
            val elapsed = t.toDouble() / (168 * h)
            sample(t, pct, reset, pace = pct - elapsed * 100, proj = pct / elapsed)
        }
        check(windowStart == 0L)
        return series to now
    }

    @Test fun `worked example - land at 107 at the current rate and 96 at the last 6h rate`() {
        val (series, now) = worked()
        val f = PaceMath.forecast(series, weekMin, now)
        assertTrue(f.ready)
        assertEquals(107.0, f.current!!.landingPercent, 1e-9)
        assertEquals(95.5, f.recent!!.landingPercent, 1e-9)
        assertEquals(0.5, f.recent!!.ratePerHour, 1e-9)
        // current rate 53.5 / 84h = 0.6369 per hour; (100 - 53.5) / 0.6369 = 73 h to cross
        assertEquals(3906.0 / 53.5, f.current!!.hoursToCross!!, 1e-6)
        assertNull(f.recent!!.hoursToCross)
        assertTrue(f.recent!!.staysUnder)
        assertEquals(84.0, f.hoursLeft!!, 1e-9)
        assertEquals(95.5, f.bandLow!!, 1e-9)
        assertEquals(107.0, f.bandHigh!!, 1e-9)
        // projection fell from 111.9 to 107 over the 6 h: the momentum is FALLING
        val m = PaceMath.momentum(series, now, 6.0)
        assertEquals(Momentum.FALLING, m.momentum)
        assertEquals(
            "at the current rate you land at 107%; at the last 6h rate, 96% (falling); at the current rate you cross 100% in 73.0h",
            PaceMath.sentence(f, m.momentum),
        )
    }

    @Test fun `a forecast that stays under says so`() {
        val now = 84 * h
        val series = (0..6).map { sample(now - (6 - it) * h, 20.0 + 0.1 * it, 168 * h) }
        val f = PaceMath.forecast(series, weekMin, now)
        assertTrue(PaceMath.sentence(f, Momentum.FLAT).endsWith("stays under 100%"))
        assertTrue(f.current!!.staysUnder && f.recent!!.staysUnder)
    }

    @Test fun `already over 100 crosses in 0 hours`() {
        val now = 84 * h
        val series = (0..6).map { sample(now - (6 - it) * h, 100.0 + 0.1 * it, 168 * h) }
        assertEquals(0.0, PaceMath.forecast(series, weekMin, now).current!!.hoursToCross)
    }

    @Test fun `no forecast without enough samples`() {
        val now = 84 * h
        val f = PaceMath.forecast(listOf(sample(now - h, 50.0, 168 * h), sample(now, 51.0, 168 * h)), weekMin, now)
        assertFalse(f.ready)
        assertNotNull(f.reason)
        assertTrue(PaceMath.sentence(f, Momentum.UNKNOWN).startsWith("not enough data yet"))
        assertFalse(PaceMath.forecast(emptyList(), weekMin, now).ready)
        assertFalse(PaceMath.forecast(listOf(sample(0, 1.0, null)), weekMin, now).ready, "no reset time = no forecast")
    }

    @Test fun `settings read from the module config with defaults`() {
        val s = PaceSettings.from(mapOf("pace.retentionDays" to "0", "pace.deadBand" to "2.5", "pace.minSamples" to "junk")::get)
        assertEquals(0, s.retentionDays)
        assertEquals(2.5, s.deadBand)
        assertEquals(3, s.minSamples)
        assertEquals(6.0, s.lookbackShortHours)
        assertEquals(PaceSettings(), PaceSettings.from { null })
    }

    // ---- report / frame ----------------------------------------------------------------------------------------------

    @Test fun `the report carries series, momentum, forecast and any provider`() {
        val (series, now) = worked()
        val store = PaceStore(tmp())
        series.forEach { s -> store.record(MeterReading("claude", s.window, weekMin, s.percent, s.resetsAt, s.observedAt, s.paceDelta, s.projectedPercent)) }
        store.record(MeterReading("brand-new-harness", "Monthly limit", 43200, 4.0, 1000 * h, now, 0.0, 4.0))
        val report = PaceReport.build(store, PaceSettings(), PaceQuery(), now)
        val all = report["series"]!!.jsonArray
        assertEquals(2, all.size, "a new harness gets a series with no per-provider code")
        val claude = all.map { it.jsonObject }.first { it["provider"]!!.jsonPrimitive.content == "claude" }
        assertEquals("FALLING", claude["momentum"]!!.jsonPrimitive.content)
        assertTrue(claude["forecast"]!!.jsonObject["ready"]!!.jsonPrimitive.content.toBoolean())
        assertEquals(7, claude["points"]!!.jsonArray.size)
        val one = PaceReport.build(store, PaceSettings(), PaceQuery(provider = "brand-new-harness"), now)["series"]!!.jsonArray
        assertEquals(1, one.size)
        assertFalse(one[0].jsonObject["forecast"]!!.jsonObject["ready"]!!.jsonPrimitive.content.toBoolean())
        assertTrue(PaceReport.build(PaceStore(tmp()), PaceSettings(), PaceQuery(), now)["text"]!!.jsonPrimitive.content.startsWith("no pacing history"))
    }

    @Test fun `since filters points and thinning keeps first and newest`() {
        val pts = (0..99).map { sample(it.toLong(), it.toDouble()) }
        val thin = PaceReport.thin(pts, 10)
        assertEquals(10, thin.size)
        assertEquals(0.0, thin.first().percent)
        assertEquals(99.0, thin.last().percent)
        val q = PaceQueries.parse("""{"provider":"claude","sinceHours":2}""", now = 10 * h)
        assertEquals(8 * h, q.sinceMs)
        assertEquals("claude", q.provider)
        assertEquals(PaceQuery(), PaceQueries.parse(""))
    }
}
