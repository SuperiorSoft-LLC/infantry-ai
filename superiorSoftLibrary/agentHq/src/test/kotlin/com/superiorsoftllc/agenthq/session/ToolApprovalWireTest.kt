package com.superiorsoftllc.agenthq.session

import com.superiorsoftllc.agenthq.daemon.DaemonFixture
import com.superiorsoftllc.agenthq.harness.ToolApproval
import com.superiorsoftllc.agenthq.protocol.Envelope
import com.superiorsoftllc.agenthq.protocol.EventVocabulary
import com.superiorsoftllc.agenthq.protocol.ToolApprovalInfo
import com.superiorsoftllc.agenthq.protocol.ToolApprovalSet
import kotlinx.serialization.json.Json
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * BUG-PERM-1 (contract 1.76.0) over the real daemon socket: `tool_approval_set` stores the global value, it is what the
 * next run resolves, `tool_approval` reads it back, a null mode clears it, and an unknown mode is refused.
 */
class ToolApprovalWireTest {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val fixtures = mutableListOf<DaemonFixture>()

    @AfterTest
    fun stopAll() {
        fixtures.asReversed().forEach { runCatching { it.stop() }; runCatching { it.deleteState() } }
    }

    private fun info(fixture: DaemonFixture, occurrence: Int): ToolApprovalInfo =
        json.decodeFromString(ToolApprovalInfo.serializer(), fixture.client.await(Envelope.TOOL_APPROVAL, occurrence).data)

    private fun set(fixture: DaemonFixture, mode: String?) =
        fixture.send(Envelope.TOOL_APPROVAL_SET, json.encodeToString(ToolApprovalSet.serializer(), ToolApprovalSet(mode)))

    @Test
    fun theContractIs1_78_0() {
        assertEquals("1.99.0", EventVocabulary.VERSION)
    }

    @Test
    fun setReadAndClearRoundTripsOverTheWireAndReachesTheNextRun() {
        val fixture = DaemonFixture.launch().also { fixtures += it }
        fixture.client.await(Envelope.READY)

        fixture.send(Envelope.TOOL_APPROVAL, "")
        val initial = info(fixture, 0)
        assertNull(initial.mode)
        assertEquals(listOf("deny", "allowEdits", "allowAll", "ask"), initial.choices)

        set(fixture, "allowAll")
        val stored = info(fixture, 1)
        assertEquals("allowAll", stored.mode)
        assertEquals("allowAll", stored.effective)
        assertEquals("setting", stored.source)
        assertEquals(ToolApproval.ALLOW_ALL, ToolApprovals.current().mode, "the next run resolves the stored value")
        assertEquals(ToolApproval.DENY, ToolApprovals.current(ToolApproval.DENY).mode, "a session override still wins")

        // Read back with a fresh request: it was stored, not only echoed.
        fixture.send(Envelope.TOOL_APPROVAL, "")
        assertEquals("allowAll", info(fixture, 2).mode)

        set(fixture, null)
        val cleared = info(fixture, 3)
        assertNull(cleared.mode)
        assertEquals(ToolApprovals.resolve(null, null).mode.wire, cleared.effective, "after a clear only the env var can decide")
    }

    @Test
    fun anUnknownModeIsRefusedAndNothingIsStored() {
        val fixture = DaemonFixture.launch().also { fixtures += it }
        fixture.client.await(Envelope.READY)

        set(fixture, "bypassPermissions")
        fixture.client.awaitWhere("tool_approval_set refusal") {
            it.type == Envelope.ERROR && "tool_approval_set refused" in it.data && "unknown tool approval 'bypassPermissions'" in it.data
        }

        fixture.send(Envelope.TOOL_APPROVAL, "")
        assertNull(info(fixture, 0).mode, "a refused mode is never stored")
    }
}
