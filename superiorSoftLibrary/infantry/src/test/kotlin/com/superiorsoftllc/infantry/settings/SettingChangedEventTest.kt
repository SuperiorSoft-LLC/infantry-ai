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
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** SETTING-EVENT-1: every setting write, at any scope, tells `onChanged` once with old/new; a secret key carries no values. */
class SettingChangedEventTest {
    private val dir: File = Files.createTempDirectory("infantry-setting-event").toFile()
    private val store = SqlInfantryStore.open(File(dir, "infantry.db").absolutePath)
    private val settings = SettingsService(store)
    private val scoped = ScopedSettings(store, settings)
    private val told = mutableListOf<SettingChange>()
    private val key = LimitRegistry.WORKERS_MAX_PARALLEL.key

    init { settings.onChanged = { told += it } }

    @AfterTest
    fun tearDown() { store.close(); dir.deleteRecursively() }

    @Test
    fun appAndProjectWritesFireWithOldAndNew_aRefusedWriteDoesNot() {
        settings.set(key, JsonPrimitive(1))
        SettingsService.actor.set("s1")
        try { settings.set(key, JsonPrimitive(2)) } finally { SettingsService.actor.remove() }
        scoped.set(key, JsonPrimitive(3), SettingScope.PROJECT, SettingContext("p1", null, null))
        assertFailsWith<IllegalArgumentException> { settings.set(key, JsonPrimitive("many")) }
        assertEquals(3, told.size, "a refused write fires nothing")
        assertNull(told[0].oldValue); assertEquals(JsonPrimitive(1), told[0].newValue); assertEquals("app", told[0].scope)
        assertEquals(JsonPrimitive(1), told[1].oldValue); assertEquals(JsonPrimitive(2), told[1].newValue); assertEquals("s1", told[1].changedBy)
        assertEquals("project", told[2].scope); assertEquals("p1", told[2].projectId); assertEquals(JsonPrimitive(3), told[2].newValue)
        assertTrue(told.none { it.redacted })
    }

    @Test
    fun aSecretKeyCarriesNoValue() {
        settings.notifyChanged("plugin.apiToken", JsonPrimitive("old"), JsonPrimitive("hunter2"), "app")
        val c = told.single()
        assertTrue(c.redacted); assertNull(c.oldValue); assertNull(c.newValue)
        assertTrue(SettingsService.isSecretKey("Foo.password") && !SettingsService.isSecretKey(key))
    }
}
