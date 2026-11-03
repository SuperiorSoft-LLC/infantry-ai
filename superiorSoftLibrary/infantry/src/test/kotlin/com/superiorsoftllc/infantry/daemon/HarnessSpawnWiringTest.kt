// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.daemon

import com.superiorsoftllc.infantry.harness.CliHarness
import com.superiorsoftllc.infantry.harness.HARNESS_CONTRACT_VERSION
import com.superiorsoftllc.infantry.harness.HarnessCapability
import com.superiorsoftllc.infantry.harness.HarnessEvent
import com.superiorsoftllc.infantry.harness.HarnessPlugin
import com.superiorsoftllc.infantry.harness.HarnessRegistrar
import com.superiorsoftllc.infantry.harness.HarnessRegistry
import com.superiorsoftllc.infantry.harness.HarnessTranslator
import com.superiorsoftllc.infantry.protocol.Envelope
import com.superiorsoftllc.infantry.protocol.ProviderInfo
import com.superiorsoftllc.infantry.runner.PipedProcessRunner
import com.superiorsoftllc.infantry.runner.ProcessSpec
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * WIRE-4: a spawn naming a harness-only id (listed by `providers`, absent from INFANTRY_PROVIDERS) starts a
 * session. Real in-process daemon, real WebSocket, stub harness whose child is `java -version`.
 */
class HarnessSpawnWiringTest {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private class NoisyTranslator : HarnessTranslator {
        override fun translate(line: String): List<HarnessEvent> = listOf(HarnessEvent.TextDelta(line))
        override fun terminal(exitCode: Int, stderrTail: String): HarnessEvent = HarnessEvent.Completion()
    }

    private class StubCliHarness(override val id: String) : CliHarness(
        PipedProcessRunner(),
        { ProcessSpec(listOf(System.getProperty("java.home") + "/bin/java", "-version")) },
        { NoisyTranslator() },
        cliName = id,
    ) {
        override val capabilities = setOf(HarnessCapability.MODEL_SELECTION)
    }

    private fun registry(): HarnessRegistry = HarnessRegistry().also { r ->
        r.register(object : HarnessPlugin {
            override val pluginId = "stub-claude"
            override val contractVersion = HARNESS_CONTRACT_VERSION
            override fun register(registrar: HarnessRegistrar) = registrar.register(StubCliHarness("claude"))
        })
    }

    @Test
    fun theNewAgentPickerListsClaudeAndASpawnOfItStartsASession() {
        val fixture = DaemonFixture.launch(harnesses = registry())
        try {
            fixture.client.await(Envelope.READY)
            fixture.send(Envelope(Envelope.PROVIDERS))
            val ids = json.decodeFromString(
                ListSerializer(ProviderInfo.serializer()), fixture.client.await(Envelope.PROVIDERS).data,
            ).map { it.id }
            assertTrue("claude" in ids, "the picker must list claude, got $ids")

            fixture.send(Envelope(Envelope.NEW, """{"provider":"claude"}"""))
            fixture.send(Envelope(Envelope.PROMPT, "hello"))
            fixture.await(what = "a session on claude") {
                fixture.daemon.manager.list().singleOrNull()?.provider == "claude"
            }
            assertEquals("claude", fixture.daemon.manager.list().single().provider)
            assertTrue(fixture.client.of(Envelope.ERROR).isEmpty(), "errors: ${fixture.client.of(Envelope.ERROR)}")
        } finally {
            fixture.stop()
            fixture.deleteState()
        }
    }

    @Test
    fun aConfiguredProviderStillWinsAndAnUnknownIdIsStillRefused() {
        val fixture = DaemonFixture.launch(harnesses = registry())
        try {
            fixture.client.await(Envelope.READY)
            fixture.send(Envelope(Envelope.NEW, """{"provider":"stub"}"""))
            fixture.send(Envelope(Envelope.NEW, """{"provider":"nope"}"""))
            fixture.client.awaitWhere("a refusal of nope") { it.type == Envelope.ERROR && it.data.contains("nope") }
        } finally {
            fixture.stop()
            fixture.deleteState()
        }
    }
}
