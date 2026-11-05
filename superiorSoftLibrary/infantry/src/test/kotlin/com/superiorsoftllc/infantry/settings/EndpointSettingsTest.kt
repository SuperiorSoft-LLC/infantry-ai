// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.settings

import com.superiorsoftllc.infantry.persistence.SqlInfantryStore
import kotlinx.serialization.json.JsonPrimitive
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** DOMAIN-ENV-1: every product URL resolves env var > saved setting > built-in default. */
class EndpointSettingsTest {
    private val dir: File = Files.createTempDirectory("infantry-endpoints").toFile()
    private val store = SqlInfantryStore.open(File(dir, "infantry.db").absolutePath)
    private val env = mutableMapOf<String, String>()
    private val settings = SettingsService(store) { env[it] }

    @AfterTest
    fun tearDown() {
        store.close()
        dir.deleteRecursively()
    }

    @Test
    fun envBeatsSettingBeatsDefault() {
        val site = EndpointRegistry.SITE
        assertEquals(JsonPrimitive(site.default), settings.all()[site.key], "the default with nothing set")

        settings.set(site.key, JsonPrimitive("https://saved.example.com/"))
        assertEquals("https://saved.example.com", settings.endpoint(site), "the saved setting, trailing slash dropped")

        env[site.env] = "https://env.example.com"
        assertEquals("https://env.example.com", settings.endpoint(site), "the env var wins over the saved setting")
        assertEquals("env", EndpointRegistry.source(site, { env[it] }, { "https://saved.example.com" }))

        env[site.env] = "   "
        assertEquals("https://saved.example.com", settings.endpoint(site), "a blank env var counts as unset")

        settings.set(site.key, JsonPrimitive(""))
        assertEquals(site.default, settings.endpoint(site), "\"\" resets the setting to the default")
    }

    @Test
    fun badValuesAreRefusedOnSaveAndSkippedFromEnv() {
        for ((def, bad) in listOf(
            EndpointRegistry.SITE to "ftp://x", EndpointRegistry.DOCS to "not a url",
            EndpointRegistry.RELEASES_REPO to "just-a-name", EndpointRegistry.DOCKER_IMAGE to "Has Spaces",
        )) {
            assertFailsWith<IllegalArgumentException>("${def.key}=$bad") { settings.set(def.key, JsonPrimitive(bad)) }
        }
        assertFailsWith<IllegalArgumentException>("a number is not a URL") { settings.set(EndpointRegistry.SITE.key, JsonPrimitive(3)) }

        env[EndpointRegistry.RELEASES_REPO.env] = "nope"
        assertEquals(EndpointRegistry.RELEASES_REPO.default, settings.endpoint(EndpointRegistry.RELEASES_REPO), "a bad env value is ignored")
        assertTrue(settings.limitEnvProblems().any { EndpointRegistry.RELEASES_REPO.env in it }, "and reported at start")

        settings.set(EndpointRegistry.RELEASES_REPO.key, JsonPrimitive("acme/releases"))
        assertEquals("acme/releases", settings.endpoint(EndpointRegistry.RELEASES_REPO), "a bad env value falls through to the setting")
        env[EndpointRegistry.DOCKER_IMAGE.env] = "registry.example.com/acme/infantry"
        assertEquals("registry.example.com/acme/infantry", settings.endpoint(EndpointRegistry.DOCKER_IMAGE))
    }

    @Test
    fun everyDefaultFitsItsKindAndKeysAndEnvNamesAreUnique() {
        for (def in EndpointRegistry.ALL) {
            assertTrue(def.default.isEmpty() || def.kind.accepts(def.default), "${def.key} default '${def.default}'")
            assertTrue(def.env.startsWith("INFANTRY_"), def.env)
            assertTrue(def.key in settings.all(), "${def.key} is listed by settings")
        }
        assertEquals(EndpointRegistry.ALL.size, EndpointRegistry.ALL.map { it.key }.toSet().size)
        assertEquals(EndpointRegistry.ALL.size, EndpointRegistry.ALL.map { it.env }.toSet().size)
    }
}
