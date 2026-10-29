package com.superiorsoftllc.agenthq.daemon

import com.superiorsoftllc.agenthq.protocol.ProviderInfo
import com.superiorsoftllc.agenthq.protocol.ProviderModels
import com.superiorsoftllc.agenthq.settings.ModelAllowlist
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** DEMO-RESTRICT (ticket f9e181af): the `models` / `providers` answers under the model allowlist. */
class AllowedCatalogTest {
    private val rows = listOf(
        ProviderModels("opencode", "opencode-go", "opencode-go", listOf("opencode-go/beam-free", "opencode-go/kimi-k3")),
        ProviderModels("opencode", "opencode", "opencode", listOf("opencode/big-pickle")),
        ProviderModels("claude", "anthropic", "Anthropic", listOf("claude-sonnet-5-5")),
        ProviderModels("pi", "", "pi", emptyList(), "pi --list-models listed no models", unavailable = true),
    )

    @Test
    fun noRestriction_unchanged() {
        assertEquals(rows, allowedCatalog(rows, ModelAllowlist.NONE))
        val infos = listOf(ProviderInfo("opencode", models = listOf("x")))
        assertEquals(infos, allowedProviders(infos, ModelAllowlist.NONE))
    }

    @Test
    fun onlyAllowedModelsListed_aHarnessWithNoneIsOneUnavailableRow() {
        val out = allowedCatalog(rows, ModelAllowlist.parse("opencode-go/beam-free"))
        assertEquals(listOf("opencode-go/beam-free"), out.filter { it.provider == "opencode" }.flatMap { it.models })
        assertEquals(1, out.count { it.provider == "opencode" }, "the emptied vendor row is dropped")
        val claude = out.single { it.provider == "claude" }
        assertTrue(claude.unavailable)
        assertTrue(claude.models.isEmpty())
        assertTrue("opencode-go/beam-free" in claude.note!!)
        val pi = out.single { it.provider == "pi" }
        assertTrue(pi.unavailable)
        assertTrue("listed no models" in pi.note!!, "the harness's own reason is kept")
    }

    @Test
    fun providers_narrowed_andAHarnessWithNoAllowedModelUnavailable() {
        val infos = listOf(
            ProviderInfo("opencode", default = true, defaultModel = "opencode-go/kimi-k3", models = listOf("opencode-go/beam-free", "opencode-go/kimi-k3")),
            ProviderInfo("claude", models = listOf("claude-sonnet-5-5")),
            ProviderInfo("pi", models = null),
        )
        val out = allowedProviders(infos, ModelAllowlist.parse("opencode-go/beam-free")).associateBy { it.id }
        assertEquals(listOf("opencode-go/beam-free"), out.getValue("opencode").models)
        assertNull(out.getValue("opencode").defaultModel, "a default that is not allowed is not offered")
        assertFalse(out.getValue("claude").available)
        assertTrue("not allowed" in out.getValue("claude").unavailableReason!! || "allowed" in out.getValue("claude").unavailableReason!!)
        assertTrue(out.getValue("pi").available, "a harness that lists nothing here is judged by the models frame")
    }
}
