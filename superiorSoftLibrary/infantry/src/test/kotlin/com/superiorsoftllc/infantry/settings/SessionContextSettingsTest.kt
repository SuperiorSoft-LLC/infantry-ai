// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.settings

import com.superiorsoftllc.infantry.persistence.SqlInfantryStore
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** CONTEXT-DEFAULT-2: the default-context and compaction-ceiling settings: defaults, overrides, catalog listing, resolution. */
class SessionContextSettingsTest {
    private val dir: File = Files.createTempDirectory("infantry-ctx").toFile()
    private val store = SqlInfantryStore.open(File(dir, "infantry.db").absolutePath)
    private val settings = SettingsService(store, env = { null })

    @AfterTest fun tearDown() { store.close(); dir.deleteRecursively() }

    @Test fun defaultsAre500kAndCeilingOff() {
        assertEquals(500_000L, settings.limit(LimitRegistry.SESSION_DEFAULT_CONTEXT_TOKENS))
        assertEquals(0L, settings.limit(LimitRegistry.SESSION_COMPACT_MAX_TOKENS))
    }

    @Test fun overridesPersistAndAreValidated() {
        settings.set("limits.session.defaultContextTokens", JsonPrimitive(300_000))
        settings.set("limits.session.compactMaxTokens", JsonPrimitive(200_000))
        val again = SettingsService(store, env = { null })
        assertEquals(300_000L, again.limit(LimitRegistry.SESSION_DEFAULT_CONTEXT_TOKENS))
        assertEquals(200_000L, again.limit(LimitRegistry.SESSION_COMPACT_MAX_TOKENS))
        assertFailsWith<IllegalArgumentException> { settings.set("limits.session.compactMaxTokens", JsonPrimitive(-1)) }
    }

    @Test fun bothSettingsAreInTheLimitCatalogTheUiListsAndInTheScopedSpecs() {
        val keys = (settings.limitCatalog()["limits"] as JsonArray).map { ((it as JsonObject)["key"] as JsonPrimitive).content }
        assertTrue("limits.session.defaultContextTokens" in keys)
        assertTrue("limits.session.compactMaxTokens" in keys)
        assertTrue("limits.session.defaultContextTokens" in SettingScopes.scopedKeys())
        assertTrue("limits.session.compactMaxTokens" in SettingScopes.scopedKeys())
    }

    @Test fun resolutionOrderPinThenWeightThenDefault() {
        assertEquals(120_000 to "ticket", ContextLimitResolver.resolve(120_000, WeightContextEntry(90_000), 500_000, null))
        assertEquals(90_000 to "weight", ContextLimitResolver.resolve(null, WeightContextEntry(90_000), 500_000, null))
        assertEquals(500_000 to "default", ContextLimitResolver.resolve(null, null, 500_000, null))
        assertNull(ContextLimitResolver.resolve(null, null, null, null), "default 0 = off: the harness's own window")
    }

    @Test fun ceilingClampsAndZeroMeansOff() {
        assertEquals(200_000 to "compactMax", ContextLimitResolver.resolve(null, null, 500_000, 200_000))
        assertEquals(200_000 to "compactMax", ContextLimitResolver.resolve(400_000, null, 500_000, 200_000))
        assertEquals(150_000 to "ticket", ContextLimitResolver.resolve(150_000, null, 500_000, 200_000), "under the ceiling: untouched")
        assertEquals(500_000 to "default", ContextLimitResolver.resolve(null, null, 500_000, null), "ceiling off")
    }
}
