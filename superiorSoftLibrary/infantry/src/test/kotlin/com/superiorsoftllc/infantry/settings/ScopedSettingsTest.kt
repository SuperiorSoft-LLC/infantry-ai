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

/** SETTINGS-SCOPES-1: resolution order, allowed-scope refusal, explain, clearing and back-compat of the flat settings. */
class ScopedSettingsTest {
    private val dir: File = Files.createTempDirectory("infantry-scoped").toFile()
    private val store = SqlInfantryStore.open(File(dir, "infantry.db").absolutePath)
    private val settings = SettingsService(store)
    private val scoped = ScopedSettings(store, settings)
    private val key = SettingsService.RELATED_BRIEF
    private val ctx = SettingContext("p1", "backend", "s1")

    @AfterTest
    fun tearDown() {
        store.close()
        dir.deleteRecursively()
    }

    private fun scopeOf(c: SettingContext = ctx) = scoped.resolve(key, c).scope

    @Test
    fun eachScopeOverridesTheNext() {
        assertEquals("default", scopeOf())
        assertEquals(JsonPrimitive(true), scoped.resolve(key, ctx).value)

        settings.set(key, JsonPrimitive(false))
        assertEquals("app", scopeOf())
        assertEquals(JsonPrimitive(false), scoped.resolve(key, ctx).value)

        scoped.set(key, JsonPrimitive(true), SettingScope.PROJECT, ctx)
        assertEquals("project", scopeOf())
        assertEquals("app", scopeOf(SettingContext("other")), "another project still sees app")

        scoped.set(key, JsonPrimitive(false), SettingScope.ROLE, ctx)
        assertEquals("role", scopeOf())
        assertEquals("project", scopeOf(SettingContext("p1", "pm", "s9")), "another role in the project sees the project value")

        scoped.set(key, JsonPrimitive(true), SettingScope.SESSION, ctx)
        val winner = scoped.resolve(key, ctx)
        assertEquals("session", winner.scope)
        assertEquals(JsonPrimitive(true), winner.value)
        assertEquals(listOf("session", "role", "project", "app"), winner.layers.map { it.scope })

        scoped.set(key, null, SettingScope.SESSION, ctx)
        assertEquals("role", scopeOf(), "clearing a scope falls through to the next")
    }

    @Test
    fun appOnlyKeyRefusesOtherScopes_andNamesTheAllowedOnes() {
        val e = assertFailsWith<IllegalArgumentException> { scoped.set("limits.mail.wakeDebounceSeconds", JsonPrimitive(5), SettingScope.SESSION, ctx) }
        assertTrue("cannot be set at the session scope" in e.message!!, e.message)
        assertFailsWith<IllegalArgumentException> { scoped.set(SettingsService.USER_PORTRAIT, JsonPrimitive(""), SettingScope.PROJECT, ctx) }
        assertFailsWith<IllegalArgumentException> { scoped.set(key, JsonPrimitive("x"), SettingScope.PROJECT, ctx) }
        assertFailsWith<IllegalArgumentException> { scoped.set(key, JsonPrimitive(true), SettingScope.ROLE, SettingContext("p1")) }
        assertFailsWith<IllegalArgumentException> { scoped.resolve("no.such.key", ctx) }
        assertEquals("default", scopeOf(), "refusals store nothing")
    }

    @Test
    fun explainShowsWinnerDocsAndLayers() {
        scoped.set(key, JsonPrimitive(false), SettingScope.PROJECT, ctx)
        val text = scoped.explain(key, ctx)
        assertTrue(text.startsWith("$key = false  (from project)"), text)
        assertTrue("allowed scopes: session, role, project, app" in text, text)
        assertTrue("project p1 = false" in text, text)
        assertTrue("costlier value:" in text, text)
    }

    @Test
    fun noContextIsAppScope_andFlatSettingsAreUnchanged() {
        settings.set(SettingsService.KEEP_ALIVE_MINUTES, JsonPrimitive(7))
        scoped.set(SettingsService.KEEP_ALIVE_MINUTES, JsonPrimitive(99), SettingScope.PROJECT, SettingContext("p1"))
        assertEquals(7, settings.keepAliveMinutes(), "flat reader ignores scoped rows")
        assertEquals(JsonPrimitive(7), settings.all()[SettingsService.KEEP_ALIVE_MINUTES])
        assertEquals(JsonPrimitive(7), scoped.resolve(SettingsService.KEEP_ALIVE_MINUTES).value)
        assertEquals(JsonPrimitive(99), scoped.resolve(SettingsService.KEEP_ALIVE_MINUTES, SettingContext("p1")).value)
        assertFailsWith<IllegalArgumentException> { scoped.set(SettingsService.KEEP_ALIVE_MINUTES, JsonPrimitive(5000), SettingScope.SESSION, ctx) }
    }
}
