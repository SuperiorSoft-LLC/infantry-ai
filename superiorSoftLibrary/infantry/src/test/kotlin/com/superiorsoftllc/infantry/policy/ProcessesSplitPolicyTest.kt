package com.superiorsoftllc.infantry.policy

import com.superiorsoftllc.infantry.cli.ProcessesCli
import com.superiorsoftllc.infantry.plugin.api.ActionKind
import com.superiorsoftllc.infantry.plugin.api.PolicyPreset
import com.superiorsoftllc.infantry.protocol.Envelope
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * POLICY-1 gap 6 (contract 1.143.0): `processes` lists and `processes_reap` kills, so a read-only policy can list host
 * processes and still cannot kill one. A legacy `processes` whose op is not `list` (a client below 1.143.0) is gated as the
 * reap, so the split opens no way around the policy.
 */
class ProcessesSplitPolicyTest {
    private val readOnly = PolicyPreset(id = "read-only", disable = listOf("*"), allow = listOf("kind:read"), reason = "read only")

    @Test fun `listing is a read and reaping is destructive, each its own frame`() {
        assertEquals("host.processes.read" to ActionKind.READ, ActionCatalog.forFrame(Envelope.PROCESSES)!!.let { it.id to it.kind })
        assertEquals("host.processes.reap" to ActionKind.DESTRUCTIVE, ActionCatalog.forFrame(Envelope.PROCESSES_REAP)!!.let { it.id to it.kind })
    }

    @Test fun `a read-only policy lists processes and refuses every reap, the legacy form included`() {
        assertNull(PolicyRuntime.actionRefusal(readOnly, Envelope(Envelope.PROCESSES, """{"op":"list"}""")))
        assertNull(PolicyRuntime.actionRefusal(readOnly, Envelope(Envelope.PROCESSES, "")), "a blank payload is a list")
        assertNotNull(PolicyRuntime.actionRefusal(readOnly, Envelope(Envelope.PROCESSES_REAP, """{"op":"reap","pid":42}""")))
        assertNotNull(PolicyRuntime.actionRefusal(readOnly, Envelope(Envelope.PROCESSES, """{"op":"reap","pid":42}""")), "legacy reap")
        assertNotNull(PolicyRuntime.actionRefusal(readOnly, Envelope(Envelope.PROCESSES, """{"op":"reap_rogue"}""")), "legacy reap all")
        assertNotNull(PolicyRuntime.actionRefusal(readOnly, Envelope(Envelope.PROCESSES, "not json")), "an unreadable payload is gated as a reap")
        assertEquals("host.processes.reap", ActionCatalog.forEnvelope(Envelope(Envelope.PROCESSES, """{"op":"reap","pid":1}"""))?.id)
    }

    @Test fun `production refuses neither`() {
        assertNull(PolicyRuntime.actionRefusal(PolicyPreset.Production, Envelope(Envelope.PROCESSES_REAP, """{"op":"reap","pid":42}""")))
    }

    @Test fun `the CLI and MCP tools send a reap as processes_reap and a list as processes`() {
        assertEquals(Envelope.PROCESSES, ProcessesCli.frameType(ProcessesCli.requestFor(emptyList(), false)))
        assertEquals(Envelope.PROCESSES_REAP, ProcessesCli.frameType(ProcessesCli.requestFor(listOf("reap", "42"), false)))
        assertEquals(Envelope.PROCESSES_REAP, ProcessesCli.frameType(ProcessesCli.requestFor(listOf("reap"), true)))
    }
}
