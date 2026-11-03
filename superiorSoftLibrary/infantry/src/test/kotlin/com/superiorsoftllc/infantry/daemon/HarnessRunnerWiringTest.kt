// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.daemon

import com.superiorsoftllc.infantry.harness.ServiceLoaderPluginSource
import com.superiorsoftllc.infantry.harness.descriptor.EchoCliFixture
import com.superiorsoftllc.infantry.protocol.Envelope
import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * WIRE-5: a harness-only spawn runs its REAL child even when the daemon default is the stub. No runner override:
 * [DaemonFixture.launch] keeps the stub's in-memory transport as the default, exactly like INFANTRY_PROVIDER=stub.
 */
class HarnessRunnerWiringTest {
    @Test
    fun aHarnessOnlySpawnRunsItsChildUnderAStubDefault() {
        val dir = EchoCliFixture.tempDir("wire5")
        val source = EchoCliFixture.writeSource(dir)
        val harnessesDir = File(dir, "harnesses").also { it.mkdirs() }
        File(harnessesDir, "echo.json").writeText(EchoCliFixture.descriptorJson("echo-cli", source))

        val fixture = DaemonFixture.launch(
            harnesses = loadHarnesses(ServiceLoaderPluginSource(), descriptorDir = harnessesDir),
        )
        try {
            fixture.client.await(Envelope.READY)
            fixture.send(Envelope(Envelope.NEW, """{"provider":"echo-cli"}"""))
            fixture.send(Envelope(Envelope.PROMPT, "wire5 ping"))
            val frame = fixture.client.awaitWhere("the child's echo on a STDOUT frame", timeoutMs = 60_000) {
                it.type == Envelope.STDOUT && it.data.contains("echo: wire5 ping")
            }
            assertTrue(frame.data.contains("echo: wire5 ping"))
        } finally {
            fixture.stop()
            fixture.deleteState()
        }
    }
}
