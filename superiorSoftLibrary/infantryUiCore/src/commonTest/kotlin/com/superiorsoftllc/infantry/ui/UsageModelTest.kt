// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.ui

import com.superiorsoftllc.infantry.ui.model.AppState
import com.superiorsoftllc.infantry.ui.model.reduce
import com.superiorsoftllc.infantry.ui.protocol.Envelope
import com.superiorsoftllc.infantry.ui.protocol.ProviderState
import com.superiorsoftllc.infantry.ui.protocol.RunUsage
import com.superiorsoftllc.infantry.ui.protocol.SessionRecord
import com.superiorsoftllc.infantry.ui.protocol.SessionState
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Story 9.8c (contract 1.13.0): usage and limit state are rendered from frames only. Usage is per run and
 * arrives on the session stream; a provider never observed is `unknown`, which the UI must never draw as 0%.
 */
class UsageModelTest {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private fun feed(vararg e: Envelope, from: AppState = AppState()) = e.fold(from) { s, f -> reduce(s, f, 100) }
    private fun session(id: String) = Envelope(
        Envelope.SESSION,
        json.encodeToString(SessionRecord.serializer(), SessionRecord(id, "stub", "/w", 1, 1, SessionState.IDLE, "p")),
        id,
        1,
    )
    private fun usage(id: String, seq: Long, run: Int, input: Long) = Envelope(
        Envelope.USAGE,
        json.encodeToString(RunUsage.serializer(), RunUsage("$id:$run", "stub", "default", input, 10, 0, 0, 0, null)),
        id,
        seq,
    )

    @Test fun `usage attaches to its agent once per run and totals across runs`() {
        val s = feed(session("a"), usage("a", 2, 1, 100), usage("a", 2, 1, 100), usage("a", 3, 2, 50))
        val agent = s.agents.getValue("a")
        assertEquals(listOf("a:1", "a:2"), agent.usage.map { it.runId }, "a replayed usage frame is not counted twice")
        assertEquals(150L, agent.usageTotals!!.input)
        assertEquals(20L, agent.usageTotals!!.output)
        assertNull(feed(session("b")).agents.getValue("b").usageTotals, "no usage reported is not zero usage")
    }

    @Test fun `provider states come from the list and from pushes, and unknown stays unknown`() {
        val listed = listOf(ProviderState("stub", "unknown"), ProviderState("claude", "unknown"))
        val s = feed(Envelope(Envelope.PROVIDER_STATES, json.encodeToString(ListSerializer(ProviderState.serializer()), listed)))
        assertEquals(listOf("stub", "claude"), s.providerStates.keys.toList())
        assertNull(s.providerStates.getValue("stub").utilization)
        val pushed = ProviderState("stub", "unknown", window = "five_hour", utilization = 0.42, resetsAt = 1791700000, observedAt = 9, source = "stream")
        val after = feed(Envelope(Envelope.PROVIDER_STATE, json.encodeToString(ProviderState.serializer(), pushed)), from = s)
        assertEquals(pushed, after.providerStates.getValue("stub"))
    }

    @Test fun `a sequenced frame of a type this build does not know still advances the cursor`() {
        val s = feed(session("a"), Envelope("future_frame", "{}", "a", 2), Envelope(Envelope.TEXT_DELTA, "x", "a", 3))
        assertEquals(3L, s.agents.getValue("a").cursor.contiguous, "an unknown frame must not leave a hole that stalls replay")
    }
}
