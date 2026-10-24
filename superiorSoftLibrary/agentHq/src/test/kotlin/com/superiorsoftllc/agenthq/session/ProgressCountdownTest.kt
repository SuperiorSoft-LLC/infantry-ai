package com.superiorsoftllc.agenthq.session

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ProgressCountdownTest {
    private val min = 60_000L

    private fun status(label: String, estimateMs: Long? = null) = ProgressReport("status", label = label, estimateMs = estimateMs)

    @Test
    fun workerEstimateCountsDownThenReportsOverrun() {
        val p = ProgressReducer.apply(null, status("compile", 4 * min), 0)!!
        assertEquals(SessionProgress.SOURCE_WORKER, p.estimateSource)
        val half = ProgressReducer.eta(p, 2 * min)
        assertEquals(50.0, half.percent)
        assertEquals("~2m left", ProgressReducer.countdownText(half))
        val over = ProgressReducer.eta(p, 5 * min)
        assertEquals(99.0, over.percent)
        assertNull(over.remainingMs)
        assertEquals("over estimate by 1m", ProgressReducer.countdownText(over))
    }

    @Test
    fun estimateSurvivesALaterReportOfTheSameLabel() {
        val a = ProgressReducer.apply(null, status("compile", 4 * min), 0)!!
        val b = ProgressReducer.apply(a, status("compile"), min)!!
        assertEquals(4 * min, b.estimateMs)
        assertEquals(0L, b.labelStartedAt)
        val c = ProgressReducer.apply(b, status("test"), 2 * min)!!
        assertNull(c.estimateMs)
        assertEquals(2 * min, c.labelStartedAt)
    }

    @Test
    fun learnedMedianIsUsedWhenTheWorkerGivesNone() {
        val p = ProgressReducer.apply(null, status("gradle :a:test"), 0, learned = { if (it == "gradle :a:test") 3 * min else null })!!
        assertEquals(SessionProgress.SOURCE_LEARNED, p.estimateSource)
        assertEquals(3 * min, p.estimateMs)
        // the worker's own number still wins over the learned one
        val w = ProgressReducer.apply(null, status("gradle :a:test", min), 0, learned = { 3 * min })!!
        assertEquals(SessionProgress.SOURCE_WORKER, w.estimateSource)
        assertEquals(min, w.estimateMs)
    }

    @Test
    fun firstRunOfALabelHasNoEstimateAndHidesElapsed() {
        val p = ProgressReducer.apply(null, status("gradle :a:test"), 0)!!
        val eta = ProgressReducer.eta(p, 10 * min)
        assertTrue(eta.noEstimate)
        val line = ProgressReducer.line(p, 10 * min)
        assertTrue("no estimate yet" in line, line)
        assertTrue("last output 10m00s ago" in line, line)
        assertFalse("elapsed" in line, line)
        val beat = ProgressReducer.apply(p, ProgressReport("output"), 10 * min - 5_000)!!
        assertTrue("last output 5s ago" in ProgressReducer.line(beat, 10 * min), ProgressReducer.line(beat, 10 * min))
        assertEquals(0L, beat.updatedAt)
    }

    @Test
    fun counterPercentBeatsATimeEstimate() {
        val s = ProgressReducer.apply(null, status("tests", 10 * min), 0)!!
        val c = ProgressReducer.apply(s, ProgressReport("count", done = 25, total = 100, label = "tests"), 2 * min)!!
        val eta = ProgressReducer.eta(c, 2 * min)
        assertEquals(25.0, eta.percent)
        assertEquals(6 * min, eta.remainingMs) // 2m for 25% => 6m more
        assertTrue(ProgressReducer.line(c, 2 * min).contains("25% 25/100 · ~6m left"), ProgressReducer.line(c, 2 * min))
    }

    @Test
    fun finishedLabelIsTaughtOnlyWhenItEndedWell() {
        val a = ProgressReducer.apply(null, status("compile"), 0)!!
        val b = ProgressReducer.apply(a, status("test"), 90_000)!!
        assertEquals("compile" to 90_000L, ProgressReducer.finished(a, b, 90_000))
        assertNull(ProgressReducer.finished(b, ProgressReducer.apply(b, status("test"), 91_000), 91_000))
        val done = ProgressReducer.apply(b, ProgressReport("done"), 190_000)
        assertEquals("test" to 100_000L, ProgressReducer.finished(b, done, 190_000))
        assertNull(ProgressReducer.finished(b, ProgressReducer.apply(b, ProgressReport("fail"), 190_000), 190_000))
        val quick = ProgressReducer.apply(b, status("x"), 90_500)
        assertNull(ProgressReducer.finished(b, quick, 90_500))
    }

    @Test
    fun stepsKeyOnTheRunningStep() {
        val plan = ProgressReducer.apply(null, ProgressReport("plan", steps = listOf("build", "test")), 0)!!
        assertEquals("build", ProgressReducer.key(plan))
        val next = ProgressReducer.apply(plan, ProgressReport("step"), 60_000, learned = { if (it == "test") 5 * min else null })!!
        assertEquals("test", ProgressReducer.key(next))
        assertEquals(5 * min, next.estimateMs)
        assertEquals("build" to 60_000L, ProgressReducer.finished(plan, next, 60_000))
    }

    @Test
    fun estimatesKeepTwentyAndTheMedianAndPersist() {
        val file = File.createTempFile("est", ".json").apply { delete(); deleteOnExit() }
        val e = FileProgressEstimates(file)
        assertNull(e.estimate("p", "l"))
        for (i in 1..25) e.record("p", "l", i * 1000L)
        assertEquals(15_500L, e.estimate("p", "l")) // median of the last 20: 6..25 s
        assertNull(e.estimate("other", "l"))
        val again = FileProgressEstimates(file)
        assertEquals(15_500L, again.estimate("p", "l"))
        assertNotNull(file.takeIf { it.isFile })
    }
}
