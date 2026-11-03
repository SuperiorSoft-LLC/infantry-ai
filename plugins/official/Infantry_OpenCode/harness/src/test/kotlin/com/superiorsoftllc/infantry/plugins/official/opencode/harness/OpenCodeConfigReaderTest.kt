// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.plugins.official.opencode.harness

import java.io.File
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** CP-1(a): the opencode reader, driven ONLY by a fixture directory (no real home, no CLI). */
class OpenCodeConfigReaderTest {
    private val fx = ConfigFixture()

    @AfterTest
    fun cleanup() = fx.close()

    private fun seeded(env: Map<String, String> = emptyMap()): OpenCodeConfigReader {
        fx.seedOpencode()
        return OpenCodeConfigReader(fx.opencodeLocations(env))
    }

    @Test
    fun `a signed-in builtin provider lists its models and a deprecated one is dropped`() {
        val catalog = seeded().read()
        val go = catalog.providers.single { it.id == "opencode-go" }
        assertEquals("OpenCode Go", go.name)
        assertEquals(listOf("opencode-go/glm-go-2", "opencode-go/kimi-go-1"), go.models)
        assertFalse("opencode-go/old-go-0" in catalog.modelIds())
    }

    @Test
    fun `a custom provider written in the jsonc config is offered with comments and trailing commas parsed`() {
        val brew = seeded().read().providers.single { it.id == "homebrew" }
        assertEquals("Home Brew", brew.name)
        assertEquals(listOf("homebrew/brew-1", "homebrew/brew-2"), brew.models)
    }

    @Test
    fun `a provider that is neither signed in nor configured nor keyed is not offered`() {
        val ids = seeded().read().providers.map { it.id }
        assertFalse("acme" in ids, "acme has no auth entry and ACME_KEY is unset: $ids")
        assertFalse("opencode" in ids, "opencode is not signed in on this fixture: $ids")
    }

    @Test
    fun `an api key in the injected environment makes a builtin provider available`() {
        val ids = seeded(env = mapOf("ACME_KEY" to "x")).read().providers.map { it.id }
        assertTrue("acme" in ids, "$ids")
    }

    @Test
    fun `disabled_providers and enabled_providers are honoured`() {
        val reader = seeded()
        fx.write(fx.opencodeConfig, """{"disabled_providers":["opencode-go"],"provider":{"homebrew":{"models":{"b":{}}}}}""")
        assertEquals(listOf("homebrew"), reader.read().providers.map { it.id })

        fx.write(fx.opencodeConfig, """{"enabled_providers":["opencode-go"],"provider":{"homebrew":{"models":{"b":{}}}}}""")
        assertEquals(listOf("opencode-go"), reader.read().providers.map { it.id })
    }

    @Test
    fun `a provider whitelist and blacklist narrow its models`() {
        val reader = seeded()
        fx.write(fx.opencodeConfig, """{"provider":{"opencode-go":{"blacklist":["kimi-go-1"]}}}""")
        assertEquals(listOf("opencode-go/glm-go-2"), reader.read().providers.single { it.id == "opencode-go" }.models)

        fx.write(fx.opencodeConfig, """{"provider":{"opencode-go":{"whitelist":["kimi-go-1"]}}}""")
        assertEquals(listOf("opencode-go/kimi-go-1"), reader.read().providers.single { it.id == "opencode-go" }.models)
    }

    @Test
    fun `a model added to the config shows on the next read without a new reader`() {
        val reader = seeded()
        assertFalse("homebrew/brew-3" in reader.read().modelIds())
        fx.write(fx.opencodeConfig, """{"provider":{"homebrew":{"models":{"brew-1":{},"brew-3":{}}}}}""")
        assertTrue("homebrew/brew-3" in reader.read().modelIds())
    }

    @Test
    fun `the default model comes from the config`() {
        assertEquals("opencode-go/kimi-go-1", seeded().read().defaultModel)
    }

    @Test
    fun `a malformed config file is a visible note and the rest of the catalogue survives`() {
        val reader = seeded()
        fx.write(fx.opencodeConfig, """{ "provider": { "homebrew": """)
        val catalog = reader.read()
        assertTrue(catalog.notes.any { it.startsWith("opencode.jsonc is unreadable") }, "${catalog.notes}")
        assertTrue("opencode-go/kimi-go-1" in catalog.modelIds(), "the signed-in provider still lists: ${catalog.modelIds()}")
    }

    @Test
    fun `a missing model cache is noted and config-defined models still list`() {
        fx.write(fx.opencodeConfig, ConfigFixture.CONFIG_JSONC)
        val catalog = OpenCodeConfigReader(fx.opencodeLocations()).read()
        assertTrue(catalog.notes.any { "model cache" in it }, "${catalog.notes}")
        assertEquals(listOf("homebrew/brew-1", "homebrew/brew-2"), catalog.modelIds())
    }

    @Test
    fun `an empty home yields an empty catalogue, not an exception`() {
        val catalog = OpenCodeConfigReader(fx.opencodeLocations()).read()
        assertTrue(catalog.providers.isEmpty())
    }

    @Test
    fun `locations follow the XDG layout under an injected home and never the real one`() {
        val home = File(fx.root, "home")
        val loc = OpenCodeConfigLocations.fromEnvironment(env = emptyMap(), home = home)
        assertEquals(File(File(home, ".config"), "opencode/opencode.jsonc"), loc.configFiles[1])
        assertEquals(File(File(home, ".local/share"), "opencode/auth.json"), loc.authFile)
        assertEquals(File(File(home, ".cache"), "opencode/models.json"), loc.modelsFile)

        val xdg = OpenCodeConfigLocations.fromEnvironment(
            env = mapOf("XDG_CONFIG_HOME" to File(fx.root, "xc").path, "OPENCODE_CONFIG" to File(fx.root, "x.json").path),
            home = home,
        )
        assertEquals(File(File(fx.root, "xc"), "opencode/opencode.json"), xdg.configFiles[0])
        assertEquals(File(fx.root, "x.json"), xdg.configFiles.last())
    }
}
