// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.session.host

import com.superiorsoftllc.infantry.harness.RunRequest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** CLI-PTY-5: "Resume session" launches the process on its resumed conversation and sends NO turn; the next message reuses it. */
class SessionHostAttachTest {
    private val adapter = FakeHarnessAdapter()
    private val provider = FakeHarnessProvider(adapter)
    private val events = RecordingEvents()
    private val runner = CountingRunner()
    private val host = SessionHost(
        runnerFor = { runner }, lineRunner = runner, policy = { LifecyclePolicy.DEFAULT }, events = events, killGraceMs = 50, abortGraceMs = 50,
    )
    private val env = mapOf("FP" to "a")

    @Test
    fun attach_launchesOnce_sendsNoTurn_andTheNextMessageReusesTheProcess() {
        assertTrue(host.attach("s1", provider, RunRequest("", resumeSessionId = "p1", model = "m1"), env))
        assertEquals(1, adapter.launches.size)
        assertEquals(0, adapter.turns.size, "no turn is sent on attach")
        assertEquals(emptyList(), events.of("end"), "an attach ends no turn")
        assertTrue(host.hasProcess("s1"))
        assertFalse(host.attach("s1", provider, RunRequest("", resumeSessionId = "p1", model = "m1"), env), "a live process is left as it is")
        assertEquals(1, adapter.launches.size)

        host.runTurn("s1", provider, RunRequest("hello", resumeSessionId = "p1", model = "m1"), env)
        assertEquals(1, adapter.launches.size, "the message goes into the attached process")
        assertEquals(listOf("hello"), adapter.turns.map { it.prompt })
    }
}
