package com.superiorsoftllc.agenthq.harness

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** A third-party style harness written only against the published interface. */
internal class FakeHarness(
    override val capabilities: Set<HarnessCapability> = setOf(HarnessCapability.TOOL_CALLS),
    private val gate: CompletableDeferred<Unit>? = null,
) : Harness {
    override val id = "fake"
    override fun events(request: HarnessRequest): Flow<HarnessEvent> = flow {
        emit(HarnessEvent.TextDelta("he"))
        gate?.await()
        emit(HarnessEvent.TextDelta("llo"))
        emit(HarnessEvent.ToolCall("c1", "read", "{}"))
        emit(HarnessEvent.Error("boom"))
    }
}

class HarnessContractTest {
    private val req = HarnessRequest(prompt = "p", harnessId = "fake", project = "proj")

    @Test
    fun emitsTypedEventSequenceForTextToolCallAndError() = runBlocking {
        val events = FakeHarness().run(req).toList()
        assertEquals(
            listOf(
                HarnessEvent.TextDelta("he"), HarnessEvent.TextDelta("llo"),
                HarnessEvent.ToolCall("c1", "read", "{}"), HarnessEvent.Error("boom"),
            ),
            events,
        )
    }

    @Test
    fun firstEventArrivesBeforeTheRunCompletes() = runBlocking {
        val gate = CompletableDeferred<Unit>()
        val seen = mutableListOf<HarnessEvent>()
        withTimeout(2000) {
            FakeHarness(gate = gate).run(req).collect {
                seen += it
                if (seen.size == 1) gate.complete(Unit) // only released once event #1 was delivered
            }
        }
        assertEquals(4, seen.size)
    }

    @Test
    fun contractVersionIsDeclaredOnTheInterface() {
        assertEquals(HARNESS_CONTRACT_VERSION, FakeHarness().contractVersion)
        assertTrue(Regex("""\d+\.\d+\.\d+""").matches(FakeHarness().contractVersion))
    }

    @Test
    fun missingCapabilityFailsLoudlyNamingCapabilityAndHarness() {
        val e = assertFailsWith<MissingCapabilityException> { FakeHarness().run(req.copy(model = "m")) }
        assertEquals(HarnessCapability.MODEL_SELECTION, e.capability)
        assertEquals("fake", e.harnessId)
        assertTrue("MODEL_SELECTION" in e.message!! && "fake" in e.message!!)
        assertFailsWith<MissingCapabilityException> { FakeHarness().run(req.copy(sessionId = "s")) }
    }

    @Test
    fun aRequestNamingAnEffortNeedsTheEffortCapability() {
        val e = assertFailsWith<MissingCapabilityException> { FakeHarness().run(req.copy(effort = "high")) }
        assertEquals(HarnessCapability.EFFORT_SELECTION, e.capability)
    }
}
