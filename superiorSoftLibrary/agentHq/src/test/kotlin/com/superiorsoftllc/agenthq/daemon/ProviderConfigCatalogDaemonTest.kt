package com.superiorsoftllc.agenthq.daemon

import com.superiorsoftllc.agenthq.protocol.Envelope
import com.superiorsoftllc.agenthq.protocol.ProviderInfo
import com.superiorsoftllc.agenthq.protocol.ProviderModels
import com.superiorsoftllc.agenthq.provider.ClaudeCodeProvider
import com.superiorsoftllc.agenthq.provider.ConfigFirstModels
import com.superiorsoftllc.agenthq.provider.OpenCodeProvider
import com.superiorsoftllc.agenthq.provider.providerconfig.ClaudeConfigReader
import com.superiorsoftllc.agenthq.provider.providerconfig.ConfigFixture
import com.superiorsoftllc.agenthq.provider.providerconfig.OpenCodeConfigReader
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * CP-1(c): a REAL daemon whose opencode and claude providers read FIXTURE config files answers the
 * `models` and `providers` frames from those files -- nothing from the real home, no CLI spawned.
 */
class ProviderConfigCatalogDaemonTest {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private fun <T> withConfiguredDaemon(block: (DaemonFixture) -> T): T {
        ConfigFixture().use { fx ->
            fx.seedOpencode()
            fx.write(fx.claudeSettings, ConfigFixture.CLAUDE_SETTINGS)
            val opencode = OpenCodeProvider(
                ConfigFirstModels(OpenCodeConfigReader(fx.opencodeLocations()), cli = { error("opencode CLI must not run") }),
            )
            val claude = ClaudeCodeProvider(ClaudeConfigReader(fx.claudeLocations())::read)
            val daemon = DaemonFixture.launch(additionalProviders = listOf(opencode, claude))
            try {
                daemon.client.await(Envelope.READY)
                return block(daemon)
            } finally {
                daemon.stop()
                daemon.deleteState()
            }
        }
    }

    @Test
    fun `the models frame lists the models in the opencode config and the claude settings`() = withConfiguredDaemon { d ->
        d.send(Envelope(Envelope.MODELS))
        val all = json.decodeFromString(ListSerializer(ProviderModels.serializer()), d.client.await(Envelope.MODELS).data)

        val go = all.single { it.provider == "opencode" && it.group == "opencode-go" }
        assertEquals(listOf("opencode-go/glm-go-2", "opencode-go/kimi-go-1"), go.models)
        assertEquals(listOf("homebrew/brew-1", "homebrew/brew-2"), all.single { it.provider == "opencode" && it.group == "homebrew" }.models)
        val claude = all.single { it.provider == "claude" }
        assertTrue("claude-fixture-9" in claude.models && "claude-fixture-8" in claude.models && "opus" in claude.models, "${claude.models}")
    }

    @Test
    fun `the providers frame carries both lists so the profile picker can show them`() = withConfiguredDaemon { d ->
        d.send(Envelope(Envelope.PROVIDERS))
        val infos = json.decodeFromString(ListSerializer(ProviderInfo.serializer()), d.client.await(Envelope.PROVIDERS).data)
        assertTrue("opencode-go/kimi-go-1" in infos.single { it.id == "opencode" }.models.orEmpty())
        assertTrue("claude-fixture-9" in infos.single { it.id == "claude" }.models.orEmpty())
    }

    @Test
    fun `malformed config files never put file text into the models or providers frames`() {
        ConfigFixture().use { fx ->
            fx.seedOpencode()
            val poison = """{"opencode-go": {"type": "api", "key": sk-LEAKTEST-123}"""
            fx.write(fx.opencodeAuth, poison)
            fx.write(fx.claudeSettings, poison)
            val opencode = OpenCodeProvider(
                ConfigFirstModels(OpenCodeConfigReader(fx.opencodeLocations()), cli = { error("opencode CLI must not run") }),
            )
            val claude = ClaudeCodeProvider(ClaudeConfigReader(fx.claudeLocations())::read)
            val d = DaemonFixture.launch(additionalProviders = listOf(opencode, claude))
            try {
                d.client.await(Envelope.READY)
                d.send(Envelope(Envelope.MODELS))
                d.send(Envelope(Envelope.PROVIDERS))
                val models = d.client.await(Envelope.MODELS).data
                val providers = d.client.await(Envelope.PROVIDERS).data
                assertTrue("auth.json is unreadable: not valid JSON" in models, models)
                listOf("LEAKTEST", "sk-", "123", "\"key\"", "Unexpected").forEach {
                    assertTrue(it !in models, "models frame leaked '$it': $models")
                    assertTrue(it !in providers, "providers frame leaked '$it': $providers")
                }
            } finally {
                d.stop()
                d.deleteState()
            }
        }
    }
}
