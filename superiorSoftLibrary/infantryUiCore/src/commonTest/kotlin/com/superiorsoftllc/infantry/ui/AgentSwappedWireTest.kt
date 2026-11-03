// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.ui

import com.superiorsoftllc.infantry.ui.protocol.AgentProfile
import com.superiorsoftllc.infantry.ui.protocol.AgentSwapped
import com.superiorsoftllc.infantry.ui.protocol.Envelope
import com.superiorsoftllc.infantry.ui.protocol.SwapExecutor
import com.superiorsoftllc.infantry.ui.protocol.SwapSession
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Contract 1.47.0 (role-first S3): the client decodes the `agent_swapped` push exactly as the daemon writes it. */
class AgentSwappedWireTest {
    @Test
    fun theDaemonsAgentSwappedBytesDecode() {
        assertEquals("agent_swapped", Envelope.AGENT_SWAPPED)
        assertEquals("agent_swap", Envelope.AGENT_SWAP)
        // The literal shape the daemon encodes (encodeDefaults: every field present, nulls written out).
        val data = """{"agent":{"id":"backend","name":"Backend","role":"backend","provider":"second","model":"m-2",""" +
            """"instructions":null,"projectId":"p1","effort":"high"},"from":{"provider":"stub","model":null,"effort":null},""" +
            """"session":{"outcome":"new","sessionId":"s-9"}}"""
        val swapped = AgentSwapped.decode(data)
        assertEquals(AgentProfile("backend", "Backend", "backend", "second", "m-2", null, "p1", "high"), swapped.agent)
        assertEquals(SwapExecutor("stub", null, null), swapped.from)
        assertEquals(SwapSession("new", "s-9"), swapped.session)
    }

    @Test
    fun anOutcomeWithNoSessionAndAnUnknownKeyStillDecode() {
        val swapped = AgentSwapped.decode(
            """{"agent":{"name":"QA","role":"qa"},"from":{"provider":"stub"},"session":{"outcome":"none"},"later":1}""",
        )
        assertEquals("none", swapped.session.outcome)
        assertNull(swapped.session.sessionId)
    }
}
