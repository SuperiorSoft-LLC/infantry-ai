// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.ui

import com.superiorsoftllc.infantry.ui.protocol.Envelope
import com.superiorsoftllc.infantry.ui.protocol.WireRequests
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * POLICY-1 gap 6 (contract 1.143.0): the client lists with `processes` and reaps with `processes_reap`, so a read-only
 * policy lets the badge list while it refuses the reap. On a daemon below 1.143.0 a reap goes the old way (`processes`).
 */
class ProcessesReapFrameTest {
    @Test fun `a list is processes, a reap is processes_reap`() {
        assertEquals(Envelope.PROCESSES, WireRequests.processes().type)
        assertEquals(Envelope.PROCESSES_REAP, WireRequests.processes("reap", 42).type)
        assertEquals(Envelope.PROCESSES_REAP, WireRequests.processes("reap_rogue").type)
    }

    @Test fun `on an older daemon a reap is still sent as processes`() {
        assertEquals(Envelope.PROCESSES, WireRequests.processes("reap", 42, splitReap = false).type)
        assertEquals(Envelope.PROCESSES, WireRequests.processes("list", splitReap = false).type)
    }
}
