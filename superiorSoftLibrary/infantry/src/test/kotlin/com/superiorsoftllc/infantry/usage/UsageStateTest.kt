// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.usage

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

private fun fixture(name: String): List<String> =
    checkNotNull(UsageStateTest::class.java.getResourceAsStream("/usage/$name")) { "fixture missing" }
        .bufferedReader().readLines()

class UsageStateTest {
    @Test
    fun `luna fixture yields full per-run usage with cost`() {
        val u = RunUsageParser.parse("run-1", "opencode", "gpt-6-luna", fixture("opencode-go-gpt-6-luna-step-finish.jsonl").asSequence())
        assertEquals(RunUsage("run-1", "opencode", "gpt-6-luna", 3, 14, 0, 0, 74_187, 0.009280675), u)
    }

    @Test
    fun `step 5 fixture keeps reasoning cache read and zero cost`() {
        val u = RunUsageParser.parse("r", "opencode", "step-5", fixture("opencode-go-step-finish.jsonl").asSequence())!!
        assertEquals(93_798L, u.input); assertEquals(1L, u.reasoning); assertEquals(1_664L, u.cacheRead); assertEquals(0.0, u.costUsd)
    }

    @Test
    fun `no step_finish means null never zero and cost stays null when absent`() {
        assertNull(RunUsageParser.parse("r", "p", "m", sequenceOf("noise", "{\"type\":\"text\"}")))
        val noCost = """{"type":"step_finish","part":{"tokens":{"input":5,"output":2,"reasoning":0,"cache":{"write":0,"read":0}}}}"""
        assertNull(RunUsageParser.parse("r", "p", "m", sequenceOf(noCost))!!.costUsd)
    }

    @Test
    fun `usage frame serializes with usage type and additive fields`() {
        val json = Json.encodeToString(UsageFrame.serializer(), UsageFrame(RunUsage("r", "p", "m", 1, 2, 3, 4, 5, null)))
        assertTrue(json.contains("\"type\":\"usage\"")); assertTrue(json.contains("\"cacheRead\":4"))
    }

    @Test
    fun `provider with no signal is unknown never zero percent`() {
        val tracker = ProviderStateTracker(clock = { 10L })
        val s = tracker.state("claude")
        assertEquals(ProviderStatus.UNKNOWN, s.status); assertNull(s.utilization); assertNull(s.resetsAt)
    }

    @Test
    fun `rate_limit_event is recorded but never classified as exhausted without a captured sample`() {
        var pushed: ProviderState? = null
        val tracker = ProviderStateTracker(clock = { 77L }, sink = { pushed = it })
        val line = """{"type":"rate_limit_event","rate_limit_info":{"status":"rejected","resetsAt":1800,"rateLimitType":"five_hour","utilization":1.0}}"""
        val s = tracker.observeStreamLine("claude", line)!!
        assertEquals(ProviderStatus.UNKNOWN, s.status)
        assertEquals(0.0 + 1.0, s.utilization); assertEquals(1800L, s.resetsAt); assertEquals("five_hour", s.window)
        assertEquals(77L, s.observedAt); assertEquals("stream", s.source); assertEquals(s, pushed)
        assertNull(tracker.observeStreamLine("claude", """{"type":"text"}"""))
    }

    @Test
    fun `a stalled run is stalled never exhausted`() {
        val tracker = ProviderStateTracker(clock = { 5L })
        assertEquals(ProviderStatus.STALLED, tracker.markStalled("opencode").status)
    }

    @Test
    fun `exhaustion needs an explicitly supplied classifier`() {
        val tracker = ProviderStateTracker(clock = { 1L }, exhaustion = { it.contains("rejected") })
        val line = """{"type":"rate_limit_event","rate_limit_info":{"status":"rejected"}}"""
        assertEquals(ProviderStatus.EXHAUSTED, tracker.observeStreamLine("claude", line)!!.status)
    }

    @Test
    fun `budget counts runs and tokens per provider per utc day and resets next day`() {
        var now = 0L
        val budget = OperatorBudget(maxRunsPerDay = 2, maxTokensPerDay = 100, clock = { now })
        assertTrue(!budget.isSpent("p"))
        budget.record(RunUsage("a", "p", "m", 40, 10, 0, 0, 0, null))
        assertTrue(!budget.isSpent("p"))
        budget.record(RunUsage("b", "p", "m", 40, 0, 0, 0, 0, null))
        assertTrue(budget.isSpent("p")) // 2 runs
        assertTrue(!budget.isSpent("other"))
        now = 86_400_000L
        assertTrue(!budget.isSpent("p"))
        assertEquals(0, budget.snapshot("p").runs)
    }

    @Test
    fun `token budget spends on tokens alone`() {
        val budget = OperatorBudget(maxRunsPerDay = null, maxTokensPerDay = 50, clock = { 0L })
        budget.record(RunUsage("a", "p", "m", 30, 20, 0, 0, 0, null))
        assertTrue(budget.isSpent("p"))
    }


    private fun gate(exhausted: Boolean, spent: Boolean, repo: com.superiorsoftllc.infantry.daemon.MemAttention): LaunchGate {
        val tracker = ProviderStateTracker(clock = { 1L }, exhaustion = { true })
        if (exhausted) tracker.observeStreamLine("p", """{"type":"rate_limit_event","rate_limit_info":{}}""")
        val budget = OperatorBudget(maxRunsPerDay = 1, maxTokensPerDay = null, clock = { 1L })
        if (spent) budget.record(RunUsage("x", "p", "m", 1, 1, 0, 0, 0, null))
        return LaunchGate(tracker, budget, repo)
    }

    @Test
    fun `unattended start is refused when exhausted or budget spent and raises attention`() {
        for ((e, s) in listOf(true to false, false to true)) {
            val repo = com.superiorsoftllc.infantry.daemon.MemAttention()
            val d = gate(e, s, repo).check("p", LaunchOrigin.UNATTENDED, ticketId = "t1")
            assertTrue(d is GateDecision.Refused)
            assertEquals("refused_launch_gate", (d as GateDecision.Refused).disposition)
            assertEquals("launch_blocked", repo.rows.single().kind)
            assertEquals("t1", repo.rows.single().ticketId)
        }
    }

    @Test
    fun `human start warns and still runs, no attention`() {
        val repo = com.superiorsoftllc.infantry.daemon.MemAttention()
        val d = gate(true, true, repo).check("p", LaunchOrigin.HUMAN)
        assertTrue(d is GateDecision.Warn)
        assertTrue(repo.rows.isEmpty())
    }

    @Test
    fun `unknown state and unspent budget allow both origins`() {
        val repo = com.superiorsoftllc.infantry.daemon.MemAttention()
        val g = gate(false, false, repo)
        assertEquals(GateDecision.Allow, g.check("p", LaunchOrigin.UNATTENDED))
        assertEquals(GateDecision.Allow, g.check("p", LaunchOrigin.HUMAN))
    }
}
