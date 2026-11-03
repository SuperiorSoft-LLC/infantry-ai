package com.superiorsoftllc.infantry.policy

import com.superiorsoftllc.infantry.plugin.api.PolicyPreset
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * GAP-AUDIT-1 (f): a seating failure after a runtime switch is not only logged: the seat listener reports it, the
 * listeners after it (each connection's `policy` push) send it in the wire, and the next switch clears it.
 */
class PolicySeatingErrorTest {
    private val readOnly = PolicyPreset("read-only", disable = listOf("*"), allow = listOf("kind:read"))
    private val state: File = Files.createTempDirectory("policy-seat").toFile().apply { deleteOnExit() }

    @Test fun `the seat listener's failure reaches the policy frame and clears on the next switch`() {
        val r = PolicyRuntime(listOf(PolicyProvider { listOf(readOnly) }), { null }, File(state, PolicyRuntime.SELECTION_FILE), state)
        var fail = true
        r.onChange { it.seatingFailed(if (fail) "seating failed: project dir missing" else null) }
        val pushed = mutableListOf<String?>()
        r.onChange { pushed += it.wire(owner = true).seatingError }
        assertNull(r.select("read-only"))
        assertEquals("seating failed: project dir missing", r.wire(owner = true).seatingError)
        assertEquals(listOf<String?>("seating failed: project dir missing"), pushed, "the push after the seat listener carries it")
        fail = false
        assertNull(r.select(PolicyPreset.PRODUCTION))
        assertNull(r.wire(owner = false).seatingError)
        assertEquals(listOf<String?>("seating failed: project dir missing", null), pushed)
    }

    @Test fun `the policy frame carries the frame catalog for the client's send gate, never the switch frames`() {
        val r = PolicyRuntime(listOf(PolicyProvider { listOf(readOnly) }), { null }, File(state, "p2"), state)
        assertEquals(emptyMap(), r.wire(owner = true).frames, "production sends no catalog")
        assertNull(r.select("read-only"))
        val wire = r.wire(owner = true)
        assertEquals(ActionCatalog.forFrame(com.superiorsoftllc.infantry.protocol.Envelope.TICKET_SAVE)?.id, wire.frames[com.superiorsoftllc.infantry.protocol.Envelope.TICKET_SAVE]?.action)
        assertTrue(ActionCatalog.SWITCH_FRAMES.none { it in wire.frames })
        assertEquals(r.frameRefusal(com.superiorsoftllc.infantry.protocol.Envelope(com.superiorsoftllc.infantry.protocol.Envelope.TICKET_SAVE, "{}"), null, owner = true),
            wire.refusesFrame(com.superiorsoftllc.infantry.protocol.Envelope.TICKET_SAVE), "client and daemon refuse with the same text")
    }
}
