package com.superiorsoftllc.infantry.tokens

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class UsageLedgerTest {

    private val ledger = InMemoryUsageLedger()

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
    fun `samples are kept in record order`() {
        ledger.record(sample(startedAtMs = 1_700_000_000_000L, endedAtMs = 1_700_000_060_000L))
        ledger.record(sample(startedAtMs = 1_700_000_060_000L, endedAtMs = 1_700_000_120_000L))
        ledger.record(sample(startedAtMs = 1_700_000_120_000L, endedAtMs = 1_700_000_180_000L))

        val all = ledger.samples("task-1")

        assertEquals(3, all.size)
        assertEquals(60_000L, all[0].wallClockMs)
        assertEquals(60_000L, all[1].wallClockMs)
        assertEquals(60_000L, all[2].wallClockMs)
    }

    @Test
    fun `totals sum several runs of one arm`() {
        ledger.record(sample(tokensIn = 1_000L, tokensOut = 2_000L, defects = 1))
        ledger.record(sample(tokensIn = 3_000L, tokensOut = 4_000L, defects = 2))
        ledger.record(sample(tokensIn = 5_000L, tokensOut = 6_000L, defects = 0))

        val comparison = ledger.compare("task-1")

        val totals = comparison.totals[Arm.MULTI_AGENT_ROOM]!!
        assertEquals(3, totals.runs)
        assertEquals(9_000L, totals.tokensIn)
        assertEquals(12_000L, totals.tokensOut)
        assertEquals(180_000L, totals.wallClockMs)
        assertEquals(3, totals.defects)
    }

    @Test
    fun `three arms compared side by side`() {
        ledger.record(sample(arm = Arm.MULTI_AGENT_ROOM, tokensIn = 1_000L, tokensOut = 2_000L))
        ledger.record(sample(arm = Arm.SINGLE_AGENT, tokensIn = 3_000L, tokensOut = 4_000L))
        ledger.record(sample(arm = Arm.LEAD_RELAY, tokensIn = 5_000L, tokensOut = 6_000L))

        val comparison = ledger.compare("task-1")

        assertEquals(3, comparison.totals.size)
        assertEquals(1_000L, comparison.totals[Arm.MULTI_AGENT_ROOM]!!.tokensIn)
        assertEquals(3_000L, comparison.totals[Arm.SINGLE_AGENT]!!.tokensIn)
        assertEquals(5_000L, comparison.totals[Arm.LEAD_RELAY]!!.tokensIn)
    }

    @Test
    fun `a missing arm is absent from the map, not zero-filled`() {
        ledger.record(sample(arm = Arm.MULTI_AGENT_ROOM))
        ledger.record(sample(arm = Arm.SINGLE_AGENT))

        val comparison = ledger.compare("task-1")

        assertTrue(comparison.totals.containsKey(Arm.MULTI_AGENT_ROOM))
        assertTrue(comparison.totals.containsKey(Arm.SINGLE_AGENT))
        assertTrue(!comparison.totals.containsKey(Arm.LEAD_RELAY))
    }

    @Test
    fun `another taskId's samples are excluded`() {
        ledger.record(sample(taskId = "task-1", tokensIn = 1_000L))
        ledger.record(sample(taskId = "task-2", tokensIn = 9_000L))

        val comparison = ledger.compare("task-1")

        assertEquals(1, comparison.totals[Arm.MULTI_AGENT_ROOM]!!.runs)
        assertEquals(1_000L, comparison.totals[Arm.MULTI_AGENT_ROOM]!!.tokensIn)
    }
}