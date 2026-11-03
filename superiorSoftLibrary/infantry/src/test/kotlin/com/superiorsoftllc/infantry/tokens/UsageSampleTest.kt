package com.superiorsoftllc.infantry.tokens

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class UsageSampleTest {

    private fun sample(
        taskId: String = "task-1",
        arm: Arm = Arm.MULTI_AGENT_ROOM,
        provider: String = "infantry",
        model: String = "stub",
        tokensIn: Long = 1_000L,
        tokensOut: Long = 2_000L,
        startedAtMs: Long = 1_700_000_000_000L,
        endedAtMs: Long = 1_700_000_060_000L,
        defects: Int = 0,
    ) = UsageSample(taskId, arm, provider, model, tokensIn, tokensOut, startedAtMs, endedAtMs, defects)

    @Test
    fun `the three arms are the epic's three ways to run one story`() {
        assertEquals(listOf(Arm.MULTI_AGENT_ROOM, Arm.SINGLE_AGENT, Arm.LEAD_RELAY), Arm.entries.toList())
    }

    @Test
    fun `wall clock is measured, never summed from the token counts`() {
        // A sample with no tokens at all still has a wall clock: the two measures cannot be
        // conflated, so a zero-token run that took ten minutes never reads as a zero-cost run.
        assertEquals(60_000L, sample(tokensIn = 0L, tokensOut = 0L).wallClockMs)
        assertEquals(60_000L, sample().wallClockMs)
        assertEquals(1L, sample(startedAtMs = 10L, endedAtMs = 11L).wallClockMs)
        // A run that ended the instant it started is legitimate, not a rounding error.
        assertEquals(0L, sample(startedAtMs = 500L, endedAtMs = 500L).wallClockMs)
    }

    @Test
    fun `negative token counts are refused`() {
        // A provider that reports -1 tokens is reporting a broken meter. Silently clamping it to 0
        // would hide the defect inside the cost ledger the whole epic is being judged on.
        assertFailsWith<IllegalArgumentException> { sample(tokensIn = -1L) }
        assertFailsWith<IllegalArgumentException> { sample(tokensOut = -1L) }
        assertEquals(60_000L, sample(tokensIn = 0L, tokensOut = 0L).wallClockMs)
    }

    @Test
    fun `a negative defect count is refused`() {
        // The defect count is added by a human reviewer after the fact. A negative count means the
        // reviewer was miscounted, and it would drag an arm's average below zero.
        assertFailsWith<IllegalArgumentException> { sample(defects = -1) }
        assertEquals(0, sample(defects = 0).defects)
    }

    @Test
    fun `a run that ended before it started is refused`() {
        // Otherwise wallClockMs goes negative and every arm comparison built on it is nonsense.
        assertFailsWith<IllegalArgumentException> { sample(startedAtMs = 1_700_000_060_000L, endedAtMs = 1_700_000_000_000L) }
        assertTrue(sample(startedAtMs = 7L, endedAtMs = 7L).wallClockMs >= 0L)
    }
}
