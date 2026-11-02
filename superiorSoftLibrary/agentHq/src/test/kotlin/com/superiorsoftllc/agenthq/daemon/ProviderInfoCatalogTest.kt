package com.superiorsoftllc.agenthq.daemon

import com.superiorsoftllc.agenthq.protocol.ProviderInfo
import com.superiorsoftllc.agenthq.harness.AgentProvider
import com.superiorsoftllc.agenthq.harness.RunRequest
import com.superiorsoftllc.agenthq.runner.ProcessSpec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * AGENT-UX-10: the `providers` frame carries each provider's model list -- AGENT-UX-7's canonical
 * `knownModels()` seam -- so a client's model picker is driven by the wire, nothing the UI could get
 * from a static list.
 */
class ProviderInfoCatalogTest {

    private class Fake(
        override val id: String,
        private val model: String? = null,
        private val known: List<String>? = null,
    ) : AgentProvider {
        override fun spec(request: RunRequest) = ProcessSpec(listOf("noop"))
        override fun defaultModel() = model
        override fun knownModels() = known
    }

    @Test
    fun `the provider info carries the model list the provider reports`() {
        val info = providerInfo(
            provider = Fake(
                "opencode",
                model = "opencode/space-bunny-free",
                known = listOf("opencode/space-bunny-free", "opencode-go/gpt-6-luna"),
            ),
            isDefault = true,
        )
        assertEquals("opencode", info.id)
        assertTrue(info.default)
        assertEquals("opencode/space-bunny-free", info.defaultModel)
        assertEquals(listOf("opencode/space-bunny-free", "opencode-go/gpt-6-luna"), info.models)
    }

    @Test
    fun `a provider that cannot list models carries no models`() {
        val info = providerInfo(Fake("claude", model = "claude/sonnet"), isDefault = false)
        assertEquals(ProviderInfo(id = "claude", default = false, defaultModel = "claude/sonnet", contextLimit = "unsupported", contextLimitHow = "this harness declares no context limit mapping", effort = "unsupported", effortHow = "this harness declares no effort mapping"), info)
        assertNull(info.models)
    }
}
