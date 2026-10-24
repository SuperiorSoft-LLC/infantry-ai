package com.superiorsoftllc.agenthq.session

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Contract 1.117.0: the pure rule that folds a progress report into a session's progress, every mode. */
class ProgressReducerTest {
    private fun apply(prev: SessionProgress?, r: ProgressReport, now: Long = 1_000) = ProgressReducer.apply(prev, r, now)

    @Test fun `percent is clamped, labelled and keeps its start`() {
        val a = apply(null, ProgressReport("percent", percent = 40.0, label = "  compile  "), 1_000)!!
        assertEquals("percent", a.mode)
        assertEquals(40.0, a.percent)
        assertEquals("compile", a.label)
        assertEquals(1_000, a.startedAt)
        val b = apply(a, ProgressReport("percent", percent = 150.0), 2_000)!!
        assertEquals(100.0, b.percent)
        assertEquals("compile", b.label, "a report without a label keeps the running one")
        assertEquals(1_000, b.startedAt)
        assertEquals(2_000, b.updatedAt)
        assertEquals(0.0, apply(null, ProgressReport("percent", percent = -5.0))!!.percent)
        assertEquals(ProgressReducer.MAX_LABEL, apply(null, ProgressReport("percent", percent = 1.0, label = "x".repeat(500)))!!.label!!.length)
    }

    @Test fun `malformed reports are refused`() {
        assertFailsWith<IllegalArgumentException> { apply(null, ProgressReport("nope")) }
        assertFailsWith<IllegalArgumentException> { apply(null, ProgressReport("percent")) }
        assertFailsWith<IllegalArgumentException> { apply(null, ProgressReport("percent", percent = Double.NaN)) }
        assertFailsWith<IllegalArgumentException> { apply(null, ProgressReport("plan")) }
        assertFailsWith<IllegalArgumentException> { apply(null, ProgressReport("plan", count = 0)) }
        assertFailsWith<IllegalArgumentException> { apply(null, ProgressReport("bytes")) }
        assertFailsWith<IllegalArgumentException> { apply(null, ProgressReport("count", done = -1)) }
    }

    @Test fun `steps by name, unnamed, unplanned and overrun`() {
        val plan = apply(null, ProgressReport("plan", steps = listOf("build", "unit", "ui")))!!
        assertEquals(3, plan.stepsTotal)
        assertEquals(0, plan.stepsDone)
        assertEquals("build", plan.step)
        assertEquals(0.0, plan.percent)
        val s1 = apply(plan, ProgressReport("step"))!!
        assertEquals(1, s1.stepsDone)
        assertEquals("unit", s1.step)
        val s2 = apply(s1, ProgressReport("step", step = "ui"))!!
        assertEquals(3, s2.stepsDone, "naming a later step completes everything up to it")
        assertEquals(100.0, s2.percent)
        assertNull(s2.step)
        val extra = apply(s1, ProgressReport("step", step = "lint"))!!
        assertEquals(listOf("build", "lint", "unit", "ui"), extra.steps)
        assertEquals(4, extra.stepsTotal)
        assertEquals(2, extra.stepsDone)
        val over = apply(s2, ProgressReport("step"))!!
        assertEquals(4, over.stepsTotal, "more steps than planned grows the plan")
        assertEquals(4, over.stepsDone)
    }

    @Test fun `bare count plan`() {
        val plan = apply(null, ProgressReport("plan", count = 4, label = "gate"))!!
        assertEquals(4, plan.stepsTotal)
        assertTrue(plan.steps.isEmpty())
        val s = apply(apply(plan, ProgressReport("step"))!!, ProgressReport("step", step = "whatever"))!!
        assertEquals(2, s.stepsDone)
        assertEquals(50.0, s.percent)
        assertEquals("gate", s.label)
    }

    @Test fun `bytes derives an EWMA speed`() {
        val a = apply(null, ProgressReport("bytes", done = 0, total = 10_000), 0)!!
        assertNull(a.bytesPerSec, "one report has no speed")
        assertEquals(0.0, a.percent)
        val b = apply(a, ProgressReport("bytes", done = 1_000, total = 10_000), 1_000)!!
        assertEquals(1_000.0, b.bytesPerSec)
        val c = apply(b, ProgressReport("bytes", done = 4_000, total = 10_000), 2_000)!!
        assertEquals(1_000.0 * 0.7 + 3_000.0 * 0.3, c.bytesPerSec!!, 1e-9)
        assertEquals(40.0, c.percent)
        val sameTime = apply(c, ProgressReport("bytes", done = 5_000, total = 10_000), 2_000)!!
        assertEquals(c.bytesPerSec, sameTime.bytesPerSec, "no time passed: the speed is kept")
        val back = apply(c, ProgressReport("bytes", done = 100, total = 10_000), 3_000)!!
        assertEquals(c.bytesPerSec, back.bytesPerSec, "a counter going back keeps the speed")
        val unknown = apply(null, ProgressReport("bytes", done = 5, total = 0))!!
        assertNull(unknown.total)
        assertNull(unknown.percent)
    }

    @Test fun `count has no speed and a percent`() {
        val c = apply(null, ProgressReport("count", done = 30, total = 120, label = "tests"))!!
        assertEquals("count", c.mode)
        assertEquals(25.0, c.percent)
        assertNull(c.bytesPerSec)
        assertEquals(30L, c.done)
    }

    @Test fun `indeterminate has no number`() {
        val s = apply(null, ProgressReport("status", label = "waiting for the daemon"))!!
        assertEquals("indeterminate", s.mode)
        assertNull(s.percent)
        assertEquals("waiting for the daemon", s.label)
    }

    @Test fun `done and fail end a job and the next report restarts the clock`() {
        val c = apply(null, ProgressReport("count", done = 3, total = 10), 1_000)!!
        val d = apply(c, ProgressReport("done"), 5_000)!!
        assertEquals(SessionProgress.DONE, d.state)
        assertEquals(100.0, d.percent)
        assertEquals(10L, d.done)
        val steps = apply(apply(null, ProgressReport("plan", count = 3))!!, ProgressReport("done"))!!
        assertEquals(3, steps.stepsDone)
        assertNull(steps.step)
        val ind = apply(apply(null, ProgressReport("status", label = "x"))!!, ProgressReport("done"))!!
        assertNull(ind.percent, "an indeterminate job stays without a number when done")
        val f = apply(c, ProgressReport("fail", label = "exit 1"), 6_000)!!
        assertEquals(SessionProgress.FAILED, f.state)
        assertEquals("exit 1", f.label)
        assertEquals(30.0, f.percent, "a failure keeps where it stood")
        val fromNothing = apply(null, ProgressReport("fail"))!!
        assertEquals("indeterminate", fromNothing.mode)
        val next = apply(d, ProgressReport("percent", percent = 5.0), 9_000)!!
        assertEquals(9_000, next.startedAt)
        assertEquals(SessionProgress.RUNNING, next.state)
    }

    @Test fun `clear and get`() {
        val c = apply(null, ProgressReport("percent", percent = 1.0))!!
        assertNull(apply(c, ProgressReport("clear")))
        assertEquals(c, apply(c, ProgressReport("get")))
        assertNull(apply(null, ProgressReport("get")))
    }

    @Test fun `line and formatting`() {
        val p = apply(null, ProgressReport("plan", steps = listOf("a", "b", "c", "d", "e"), label = "gate"), 0)!!
            .let { apply(apply(it, ProgressReport("step"), 0)!!, ProgressReport("step"), 0)!! }
        assertEquals("40% step 3/5 c - gate (updated 12s ago)", ProgressReducer.line(p, 12_000))
        assertEquals("512 B", ProgressReducer.bytes(512))
        assertEquals("1.5 KB", ProgressReducer.bytes(1_536))
        assertEquals("2.0 MB", ProgressReducer.bytes(2L shl 20))
        assertEquals("1m05s", ProgressReducer.duration(65_000))
        assertEquals("1h03m", ProgressReducer.duration(3_780_000))
    }
}
