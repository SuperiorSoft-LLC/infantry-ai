// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.daemon

import com.superiorsoftllc.infantry.protocol.Envelope
import com.superiorsoftllc.infantry.runner.ProcessListener
import com.superiorsoftllc.infantry.runner.ProcessRunner
import com.superiorsoftllc.infantry.runner.ProcessSpec
import com.superiorsoftllc.infantry.runner.RunningProcess
import com.superiorsoftllc.infantry.runner.ScriptedRunner
import com.superiorsoftllc.infantry.runner.StubScript
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * BUG-PORT-0: with `INFANTRY_PORT=0` the OS picks the port at bind time, so [DaemonPort.reserve] yields
 * `Choice(0, 0)`. A session the daemon spawns must still be told the port the daemon actually bound (the one its
 * `listening on` line names), both in `INFANTRY_URL` and in `INFANTRY_MAIL_BASE`, never port 0.
 */
class DaemonPortEphemeralTest {

    private class RecordingRunner(private val inner: ProcessRunner) : ProcessRunner {
        val specs: MutableList<ProcessSpec> = CopyOnWriteArrayList()
        override val id: String = "recording-${inner.id}"
        override fun start(spec: ProcessSpec, listener: ProcessListener): RunningProcess {
            specs += spec
            return inner.start(spec, listener)
        }
    }

    @Test
    fun anEphemeralPortDaemonHandsItsSessionsTheBoundPort() {
        val script = StubScript.default()
        val runner = RecordingRunner(ScriptedRunner(script, delayMs = 0))
        // port = 0 is what INFANTRY_PORT=0 means; mailBaseUrl = null is what an unset INFANTRY_MAIL_BASE means.
        val fixture = DaemonFixture.launch(script, runner = runner, port = 0, mailBaseUrl = null)
        try {
            val bound = fixture.port
            assertTrue(bound > 0, "the daemon bound a real port, not 0: $bound")
            fixture.client.await(Envelope.READY)
            fixture.client.send(Envelope(Envelope.PROMPT, "say something"))
            fixture.await(what = "the run was spawned") { runner.specs.isNotEmpty() }

            val env = runner.specs.first().env
            println("[BUG-PORT-0] bound=$bound INFANTRY_URL=${env[InfantryBin.URL_ENV]} INFANTRY_MAIL_BASE=${env[AccessPolicy.MAIL_BASE_ENV]}")
            // An operator's explicit INFANTRY_URL wins (BUG-G2c); otherwise it must name the bound port.
            val inherited = System.getenv(InfantryBin.URL_ENV)?.takeIf { it.isNotBlank() }
            assertEquals(inherited ?: "ws://127.0.0.1:$bound/ws", env[InfantryBin.URL_ENV], "INFANTRY_URL names the bound port")
            assertEquals("http://127.0.0.1:$bound", env[AccessPolicy.MAIL_BASE_ENV], "INFANTRY_MAIL_BASE names the bound port")
        } finally {
            fixture.stop()
            fixture.deleteState()
        }
    }
}
