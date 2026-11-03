// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.daemon

import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import com.superiorsoftllc.infantry.orchestration.GatedRunner
import com.superiorsoftllc.infantry.protocol.Envelope
import com.superiorsoftllc.infantry.protocol.EventVocabulary
import com.superiorsoftllc.infantry.protocol.TicketStatus
import com.superiorsoftllc.infantry.runner.ScriptedRunner
import com.superiorsoftllc.infantry.runner.StubScript
import com.superiorsoftllc.infantry.usage.BudgetSnapshot
import com.superiorsoftllc.infantry.usage.OperatorBudget
import com.superiorsoftllc.infantry.usage.ProviderState
import com.superiorsoftllc.infantry.usage.ProviderStatus
import com.superiorsoftllc.infantry.usage.RunUsage
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Story 9.7b wiring, contract 1.13.0: observed usage per run, observed limit state per provider, the
 * operator budget and the launch gate -- on the real daemon.
 *
 * - a run whose stream reports usage (`step_finish` tokens) ends with a sequenced `usage` frame and a
 *   `run_usage` row; a run with NO usage signal pushes nothing (no signal is not zero) but still counts
 *   as a run against the budget;
 * - a `rate_limit_event` becomes that provider's `provider_state`, pushed, stored and listed by
 *   `provider_states`; a provider never observed is `unknown`, never 0%;
 * - the launch gate refuses an UNATTENDED start (a drained queue) over a spent budget and raises
 *   `launch_blocked`; a HUMAN start is only warned, and runs.
 */
class UsageDaemonTest {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val tokens = """{"type":"step_finish","part":{"tokens":{"input":100,"output":20,"reasoning":5,"cache":{"read":7,"write":3}},"cost":0.01}}"""

    @Test
    fun aRunThatReportsUsageEndsWithAUsageFrameARowAndABudgetCount() = withDaemon(
        StubScript(stdoutLines = listOf("""{"type":"step_start","sessionID":"u-1"}""", """{"type":"text","text":"ok"}""", tokens, tokens)),
    ) { fixture ->
        fixture.send(Envelope(Envelope.PROMPT, "count me"))
        val frame = fixture.client.awaitWhere("the usage frame", 10_000) { it.type == Envelope.USAGE }
        val sessionId = assertNotNull(frame.sessionId, "usage belongs to the session whose run ended")
        assertNotNull(frame.sequence, "usage is on the session stream, so a reopened window replays it")
        val usage = json.decodeFromString(RunUsage.serializer(), frame.data)
        assertEquals(RunUsage("$sessionId:1", "stub", "default", 200, 40, 10, 14, 6, 0.02), usage)
        assertEquals(listOf(usage), fixture.daemon.store.usage.forSession(sessionId))
        assertEquals(BudgetSnapshot(1, 250), fixture.daemon.budget.snapshot("stub"))
    }

    @Test
    fun aRunWithNoUsageSignalPushesNoUsageButStillCountsAsARun() = withDaemon(StubScript.default()) { fixture ->
        fixture.send(Envelope(Envelope.PROMPT, "silent about tokens"))
        val finished = fixture.client.await(Envelope.FINISHED)
        fixture.await(5_000, "the run counted") { fixture.daemon.budget.snapshot("stub").runs == 1 }
        assertTrue(fixture.client.of(Envelope.USAGE).isEmpty(), "no step_finish tokens: no usage frame, never zeros")
        assertEquals(BudgetSnapshot(1, 0), fixture.daemon.budget.snapshot("stub"))
        assertEquals(emptyList(), fixture.daemon.store.usage.forSession(finished.sessionId!!))
    }

    @Test
    fun aRateLimitEventBecomesTheProviderStatePushedListedAndKeptAcrossARestart() {
        val state = Files.createTempDirectory("infantry-usage-test").toFile()
        val limited = StubScript(
            stdoutLines = listOf(
                """{"type":"step_start","sessionID":"u-2"}""",
                """{"type":"rate_limit_event","rate_limit_info":{"status":"allowed","rateLimitType":"five_hour","utilization":0.42,"resetsAt":1791700000}}""",
                """{"type":"text","text":"ok"}""",
            ),
        )
        val first = DaemonFixture.launch(limited, stateDir = state)
        try {
            first.client.await(Envelope.READY)
            first.send(Envelope(Envelope.PROMPT, "hit a limit"))
            val pushed = first.client.await(Envelope.PROVIDER_STATE)
            assertTrue(pushed.data.contains("\"status\":\"unknown\""), "wire status is lower case: ${pushed.data}")
            val observed = json.decodeFromString(ProviderState.serializer(), pushed.data)
            assertEquals("stub", observed.provider)
            assertEquals(ProviderStatus.UNKNOWN, observed.status, "never exhausted without a classifier")
            assertEquals("five_hour", observed.window)
            assertEquals(0.42, observed.utilization)
            assertEquals(1791700000L, observed.resetsAt)
            assertEquals("stream", observed.source)

            first.send(Envelope(Envelope.PROVIDER_STATES))
            assertEquals(listOf(observed), states(first.client.await(Envelope.PROVIDER_STATES)))
        } finally {
            first.stop()
        }
        val second = DaemonFixture.launch(StubScript.default(), stateDir = state)
        try {
            second.client.await(Envelope.READY)
            second.send(Envelope(Envelope.PROVIDER_STATES))
            val restored = states(second.client.await(Envelope.PROVIDER_STATES)).single()
            assertEquals(0.42, restored.utilization, "the last observation survives a restart")
            assertEquals("stream", restored.source)
        } finally {
            second.stop()
            second.deleteState()
        }
    }

    @Test
    fun everyProviderIsUnknownUntilSomethingIsObserved() = withDaemon(StubScript.default()) { fixture ->
        fixture.send(Envelope(Envelope.PROVIDER_STATES))
        val listed = states(fixture.client.await(Envelope.PROVIDER_STATES)).single()
        assertEquals(ProviderState("stub", ProviderStatus.UNKNOWN), listed)
        assertNull(listed.utilization, "no signal is not 0%")
        assertEquals("unknown", fixture.health()["providerStatus"]!!.jsonObject["stub"]!!.jsonPrimitive.content)
    }

    @Test
    fun anUnattendedQueueDrainOverASpentBudgetIsRefusedAndRaisesLaunchBlocked() {
        val fixture = DaemonFixture.launch(
            runner = GatedRunner(),
            context = FakeTicketContext("HANG"),
            budget = OperatorBudget(maxRunsPerDay = 1, maxTokensPerDay = null),
        )
        try {
            fixture.client.await(Envelope.READY)
            fixture.client.saveProfile("""{"id":"backend","name":"Backend","role":"backend"}""")
            val a = fixture.client.saveTicket("""{"title":"A","agentId":"backend"}""")
            val startedA = fixture.client.moveTicket(a.id, TicketStatus.IN_PROGRESS)
            assertTrue(startedA.start!!.ok, "a human start within budget runs: ${startedA.start}")
            val b = fixture.client.saveTicket("""{"title":"B","agentId":"backend"}""")
            assertEquals("queued", fixture.client.moveTicket(b.id, TicketStatus.IN_PROGRESS).start!!.reason)

            // A ends: that run spends the 1-run budget, THEN the lane drains B -- an unattended start.
            fixture.send(Envelope.KILL, "${startedA.sessionId} force")
            val blocked = fixture.client.awaitWhere("the launch_blocked attention", 10_000) { frame ->
                frame.type == Envelope.ATTENTION &&
                    kotlinx.serialization.json.Json.parseToJsonElement(frame.data).jsonObject.let { it["kind"]?.jsonPrimitive?.content == "launch_blocked" && it["ticketId"]?.jsonPrimitive?.content == b.id }
            }
            assertTrue(blocked.data.contains("budget"), blocked.data)
            assertEquals(1, fixture.daemon.manager.list().size, "the refused drain spawned nothing")
            assertEquals(TicketStatus.TODO, fixture.client.board().single { it.id == b.id }.status)
        } finally {
            fixture.stop()
            fixture.deleteState()
        }
    }

    @Test
    fun aHumanStartOverASpentBudgetIsWarnedAndStillRuns() {
        val script = StubScript.default()
        val fixture = DaemonFixture.launch(script, runner = ScriptedRunner(script, 0), budget = OperatorBudget(maxRunsPerDay = 1, maxTokensPerDay = null))
        try {
            fixture.client.await(Envelope.READY)
            fixture.send(Envelope(Envelope.PROMPT, "first"))
            fixture.client.await(Envelope.FINISHED)
            fixture.await(5_000, "the first run counted") { fixture.daemon.budget.snapshot("stub").runs == 1 }

            fixture.send(Envelope(Envelope.NEW))
            fixture.send(Envelope(Envelope.PROMPT, "second, over budget"))
            val warning = fixture.client.awaitWhere("the launch warning", 10_000) { it.type == Envelope.ERROR && it.data.startsWith("launch warning") }
            assertTrue(warning.data.contains("starts anyway"), warning.data)
            fixture.client.await(Envelope.FINISHED, occurrence = 1)
            assertEquals(2, fixture.daemon.manager.list().size, "a human start is warned, never refused")
            assertTrue(fixture.daemon.attention.list().none { it.kind == "launch_blocked" })
        } finally {
            fixture.stop()
            fixture.deleteState()
        }
    }

    private fun states(frame: Envelope): List<ProviderState> =
        json.decodeFromString(ListSerializer(ProviderState.serializer()), frame.data)

    private fun withDaemon(script: StubScript, block: (DaemonFixture) -> Unit) {
        val fixture = DaemonFixture.launch(script, runner = ScriptedRunner(script, 0))
        try {
            fixture.client.await(Envelope.READY)
            block(fixture)
        } finally {
            fixture.stop()
            fixture.deleteState()
        }
    }
}
