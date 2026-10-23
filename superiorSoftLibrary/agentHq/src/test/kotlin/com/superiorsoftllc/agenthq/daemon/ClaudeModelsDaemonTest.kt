package com.superiorsoftllc.agenthq.daemon

import com.superiorsoftllc.agenthq.harness.HarnessRegistry
import com.superiorsoftllc.agenthq.harness.claude.ClaudeHarnessPlugin
import com.superiorsoftllc.agenthq.harness.claude.ClaudeModelCatalog
import com.superiorsoftllc.agenthq.harness.pi.PiHarnessPlugin
import com.superiorsoftllc.agenthq.protocol.Envelope
import com.superiorsoftllc.agenthq.protocol.ProviderModels
import com.superiorsoftllc.agenthq.provider.HarnessModelCatalogs
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * PICKER-ALL-1: a REAL daemon with the Claude and Pi plugins registered and NO claude provider configured answers
 * the `models` frame with the built-in Claude Code list, and a harness that has no source (Pi here, its source left
 * out so no `pi` is spawned) gets one noted empty row, so the picker's free-text field explains itself.
 */
class ClaudeModelsDaemonTest {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    @Test
    fun theModelsFrameCarriesClaudeCodeRowsAndANotedRowForAHarnessWithNoCatalog() {
        val registry = HarnessRegistry().also { it.register(ClaudeHarnessPlugin()); it.register(PiHarnessPlugin()) }
        val fixture = DaemonFixture.launch(harnesses = registry, modelCatalogs = HarnessModelCatalogs(mapOf("claude" to ClaudeModelCatalog())))
        val all = try {
            fixture.client.await(Envelope.READY)
            fixture.send(Envelope(Envelope.MODELS))
            json.decodeFromString(ListSerializer(ProviderModels.serializer()), fixture.client.await(Envelope.MODELS).data)
        } finally {
            fixture.stop()
            fixture.deleteState()
        }
        val claude = all.single { it.provider == "claude" }
        assertEquals("claude", claude.group)
        assertEquals(ClaudeModelCatalog.MODELS.map { it.id }, claude.models)
        val pi = all.single { it.provider == "pi" }
        assertTrue(pi.models.isEmpty())
        assertEquals(HarnessModelCatalogs.NO_CATALOG_NOTE, pi.note)
        assertTrue(all.any { it.provider == "stub" }, "the configured provider is still listed: $all")
    }
}
