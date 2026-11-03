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
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** DEMO-RESTRICT (ticket f9e181af): `limits.models.allowed` (stored > env INFANTRY_ALLOWED_MODELS > none) and maxSessions. */
class AllowedModelsSettingTest {
    @get:org.junit.Rule
    val timeout: org.junit.rules.Timeout = org.junit.rules.Timeout.seconds(60)

    private val dir: File = Files.createTempDirectory("infantry-allowed").toFile()
    private val store = SqlInfantryStore.open(File(dir, "infantry.db").absolutePath)
    private var env = mapOf<String, String>()
    private val settings = SettingsService(store, env = { env[it] })

    @AfterTest
    fun tearDown() {
        store.close()
        dir.deleteRecursively()
    }

    @Test
    fun default_noRestriction_andNoSessionCap() {
        assertFalse(settings.allowedModels().restricts)
        assertEquals(JsonArray(emptyList()), settings.all()[ModelAllowlist.SETTING])
        assertEquals(0L, settings.limit(LimitRegistry.MAX_SESSIONS))
        assertEquals(Int.MAX_VALUE, settings.limitCap(LimitRegistry.MAX_SESSIONS))
    }

    @Test
    fun env_thenStored_storedEmptyLiftsIt() {
        env = mapOf("INFANTRY_ALLOWED_MODELS" to "opencode-go/beam-free, opencode-go/*-free")
        assertEquals(listOf("opencode-go/beam-free", "opencode-go/*-free"), settings.allowedModels().patterns)
        settings.set(ModelAllowlist.SETTING, JsonArray(listOf(JsonPrimitive("a/b"))))
        assertEquals(listOf("a/b"), settings.allowedModels().patterns)
        settings.set(ModelAllowlist.SETTING, JsonPrimitive("c/d, e/f"))
        assertEquals(listOf("c/d", "e/f"), settings.allowedModels().patterns)
        settings.set(ModelAllowlist.SETTING, JsonArray(emptyList()))
        assertFalse(settings.allowedModels().restricts, "a stored [] beats the env")
        assertFailsWith<IllegalArgumentException> { settings.set(ModelAllowlist.SETTING, JsonPrimitive(3)) }
    }

    @Test
    fun maxSessions_fromEnv_andShownInTheCatalog() {
        env = mapOf("INFANTRY_LIMIT_CONCURRENCY_MAX_SESSIONS" to "2")
        assertEquals(2, settings.limitCap(LimitRegistry.MAX_SESSIONS))
        val catalog = settings.limitCatalog()
        val keys = (catalog["limits"] as JsonArray).map { ((it as JsonObject)["key"] as JsonPrimitive).content }
        assertTrue("limits.concurrency.maxSessions" in keys)
        assertTrue(catalog.containsKey("allowedModels"))
    }

    @Test
    fun roles_defaultAll_envThenStored() {
        assertFalse(settings.allowedRoles().restricts)
        env = mapOf("INFANTRY_ALLOWED_ROLES" to "pm, backend")
        assertEquals(listOf("pm", "backend"), settings.allowedRoles().patterns)
        assertTrue(settings.allowedRoles().allows("PM"))
        assertFalse(settings.allowedRoles().allows("frontend"))
        settings.set("limits.roles.allowed", JsonArray(listOf(JsonPrimitive("QA"))))
        assertEquals(listOf("qa"), settings.allowedRoles().patterns)
        assertEquals(JsonArray(listOf(JsonPrimitive("qa"))), settings.all()["limits.roles.allowed"])
        settings.set("limits.roles.allowed", JsonArray(emptyList()))
        assertFalse(settings.allowedRoles().restricts)
    }

    @Test
    fun keepAlive_envBetweenStoredAndDefault() {
        assertEquals(30, settings.keepAliveMinutes())
        env = mapOf("INFANTRY_SESSION_KEEP_ALIVE_MINUTES" to "2")
        assertEquals(2, settings.keepAliveMinutes())
        env = mapOf("INFANTRY_SESSION_KEEP_ALIVE_MINUTES" to "nope")
        assertEquals(30, settings.keepAliveMinutes(), "a bad env value is ignored")
        env = mapOf("INFANTRY_SESSION_KEEP_ALIVE_MINUTES" to "2")
        settings.set("session.keepAliveMinutes", JsonPrimitive(5))
        assertEquals(5, settings.keepAliveMinutes(), "the stored setting wins")
    }
}
