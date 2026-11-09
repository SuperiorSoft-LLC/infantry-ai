// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.daemon

import com.superiorsoftllc.infantry.protocol.Envelope
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/** RUNNER-SETTINGS-OWNER-1: the runner trust keys are writable only with the owner credential; reads stay open. */
class RunnerSettingsOwnerGateTest {
    private val keys = listOf("runner.projects", "runner.harnesses", "limits.runners.pairCodeMin", "runners.enabled",
        "runners.advertiseHosts", "runners.fingerprint", "Runner.Projects")

    @Test fun `a non-owner (wrong token or agent) is refused on every runner trust key`() {
        for (k in keys) assertNotNull(FrameGate.settingRefusal(Envelope.SETTINGS_SET, k, owner = false), k)
    }

    @Test fun `the owner may write them`() {
        for (k in keys) assertNull(FrameGate.settingRefusal(Envelope.SETTINGS_SET, k, owner = true), k)
    }

    @Test fun `other keys and reads are unaffected`() {
        assertNull(FrameGate.settingRefusal(Envelope.SETTINGS_SET, "limits.contextCap", owner = false))
        assertNull(FrameGate.settingRefusal(Envelope.SETTINGS_GET, "runner.projects", owner = false))
    }
}
