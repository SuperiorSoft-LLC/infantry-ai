// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.daemon

import com.superiorsoftllc.infantry.harness.HarnessRegistry
import com.superiorsoftllc.infantry.harness.ServiceLoaderPluginSource
import com.superiorsoftllc.infantry.harness.descriptor.EchoCliFixture
import com.superiorsoftllc.infantry.protocol.Envelope
import com.superiorsoftllc.infantry.protocol.ProviderInfo
import com.superiorsoftllc.infantry.runner.PipedProcessRunner
import com.superiorsoftllc.infantry.session.SessionState
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * HARNESS-DESC-1 VISIBLE: a JSON descriptor for an echo-style CLI shows in the `providers` frame, and a prompt sent
 * to a session on it runs through the real child. Real in-process daemon, real WebSocket, no code plugin for the CLI.
 */
class DescriptorHarnessDaemonTest {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private fun registryFromDescriptors(dir: File): HarnessRegistry =
        loadHarnesses(ServiceLoaderPluginSource(), descriptorDir = dir)

    @Test
    fun theDescriptorShowsInTheProvidersFrameAndAPromptRunsThroughIt() {
        val dir = EchoCliFixture.tempDir("daemon")
        val source = EchoCliFixture.writeSource(dir)
        val harnessesDir = File(dir, "harnesses").also { it.mkdirs() }
        File(harnessesDir, "echo.json").writeText(EchoCliFixture.descriptorJson("echo-cli", source))

        // A REAL runner: the fixture's default (the stub's in-memory transport) would also run a harness-only spawn, and
        // answer with the stub's scripted lines instead of starting the child (see HarnessSpawnWiringTest, which only
        // checks that the session exists).
        val fixture = DaemonFixture.launch(runner = PipedProcessRunner(), harnesses = registryFromDescriptors(harnessesDir))
        try {
            fixture.client.await(Envelope.READY)
            fixture.send(Envelope(Envelope.PROVIDERS))
            val ids = json.decodeFromString(
                ListSerializer(ProviderInfo.serializer()), fixture.client.await(Envelope.PROVIDERS).data,
            ).map { it.id }
            assertTrue("echo-cli" in ids, "the descriptor harness must be listed, got $ids")
            assertTrue("claude" in ids && "pi" in ids, "code plugins stay listed beside it, got $ids")

            fixture.send(Envelope(Envelope.NEW, """{"provider":"echo-cli"}"""))
            fixture.send(Envelope(Envelope.PROMPT, "ping from the daemon"))

            fixture.client.awaitWhere("the echoed prompt on stdout", timeoutMs = 60_000) {
                it.type == Envelope.STDOUT && it.data.contains("echo: ping from the daemon")
            }
            // The descriptor's `session` mapping reached the session record through HarnessProvider.sessionIdFromLine.
            fixture.await(timeoutMs = 60_000, what = "the provider session id from the descriptor mapping") {
                fixture.daemon.manager.list().singleOrNull()?.providerSessionId == "echo-session-1"
            }
            fixture.await(timeoutMs = 60_000, what = "the run to finish") {
                fixture.daemon.manager.list().single().state != SessionState.BUSY &&
                    fixture.daemon.manager.list().single().state != SessionState.STARTING
            }
            assertEquals("echo-cli", fixture.daemon.manager.list().single().provider)
            assertTrue(fixture.client.of(Envelope.ERROR).isEmpty(), "errors: ${fixture.client.of(Envelope.ERROR)}")
        } finally {
            fixture.stop()
            fixture.deleteState()
        }
    }
}
