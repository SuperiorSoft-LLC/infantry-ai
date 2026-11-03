// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.settings

import com.superiorsoftllc.infantry.persistence.SqlInfantryStore
import com.superiorsoftllc.infantry.runner.BuildLimits
import com.superiorsoftllc.infantry.runner.BuildPriority
import com.superiorsoftllc.infantry.daemon.api.processes.CpuAlertConfig
import kotlinx.serialization.json.JsonPrimitive
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** Ticket 66153313: `limits.build.*` and `limits.cpu.*` have documented defaults, are validated, and survive a reopen. */
class BuildLimitsSettingTest {
    private val dir: File = Files.createTempDirectory("infantry-buildlimits").toFile()
    private val store = SqlInfantryStore.open(File(dir, "infantry.db").absolutePath)
    private val settings = SettingsService(store)

    @AfterTest
    fun tearDown() {
        store.close()
        dir.deleteRecursively()
    }

    /** STACK-NEUTRAL-1: the watch tooling is opt-in; an existing install is migrated to on once, a fresh one stays off. */
    @Test
    fun watchEnabledMigratesOnceByInstallAge() {
        val fresh = SettingsService(store, env = { null })
        assertEquals(false, fresh.watchEnabled(), "unset = off")
        assertEquals(0L, fresh.migrateWatchEnabled { false })
        assertEquals(null, fresh.migrateWatchEnabled { true }, "stored: the migration never runs again")
        assertEquals(false, fresh.watchEnabled())

        val dir2 = Files.createTempDirectory("infantry-watch-mig").toFile()
        SqlInfantryStore.open(File(dir2, "infantry.db").absolutePath).use { s2 ->
            val existing = SettingsService(s2, env = { null })
            assertEquals(1L, existing.migrateWatchEnabled { true })
            assertEquals(true, existing.watchEnabled())
            existing.set("limits.watch.enabled", JsonPrimitive(0))
            assertEquals(false, existing.watchEnabled(), "the owner can switch it off")
            assertEquals(null, existing.migrateWatchEnabled { true })
        }
        dir2.deleteRecursively()

        val dir3 = Files.createTempDirectory("infantry-watch-env").toFile()
        SqlInfantryStore.open(File(dir3, "infantry.db").absolutePath).use { s3 ->
            val viaEnv = SettingsService(s3, env = mapOf("INFANTRY_LIMIT_WATCH_ENABLED" to "1")::get)
            assertEquals(null, viaEnv.migrateWatchEnabled { false }, "an env value wins; nothing is stored")
            assertEquals(true, viaEnv.watchEnabled())
        }
        dir3.deleteRecursively()
    }

    @Test
    fun defaultsAreListed() {
        assertEquals(BuildLimits(BuildPriority.BELOW_NORMAL, BuildLimits.defaultMaxWorkers(), 0), settings.buildLimits())
        assertEquals(CpuAlertConfig(90, 10), settings.cpuAlert())
        val all = settings.all()
        assertEquals(JsonPrimitive("belownormal"), all[SettingsService.LIMITS_BUILD_PRIORITY])
        assertEquals(JsonPrimitive(BuildLimits.defaultMaxWorkers()), all[SettingsService.LIMITS_BUILD_MAX_WORKERS])
        assertEquals(JsonPrimitive(0), all[SettingsService.LIMITS_BUILD_MAX_PARALLEL_FORKS])
        assertEquals(JsonPrimitive(90), all[SettingsService.LIMITS_CPU_ALERT_PERCENT])
        assertEquals(JsonPrimitive(10), all[SettingsService.LIMITS_CPU_ALERT_MINUTES])
    }

    @Test
    fun valuesAreValidatedAndDurable() {
        settings.set(SettingsService.LIMITS_BUILD_PRIORITY, JsonPrimitive("Idle"))
        settings.set(SettingsService.LIMITS_BUILD_MAX_WORKERS, JsonPrimitive(0))
        settings.set(SettingsService.LIMITS_BUILD_MAX_PARALLEL_FORKS, JsonPrimitive(3))
        settings.set(SettingsService.LIMITS_CPU_ALERT_PERCENT, JsonPrimitive(75))
        settings.set(SettingsService.LIMITS_CPU_ALERT_MINUTES, JsonPrimitive(0))
        val reopened = SettingsService(store)
        assertEquals(BuildLimits(BuildPriority.IDLE, 0, 3), reopened.buildLimits())
        assertEquals(CpuAlertConfig(75, 0), reopened.cpuAlert())
        assertEquals(JsonPrimitive("idle"), reopened.all()[SettingsService.LIMITS_BUILD_PRIORITY], "stored canonical")

        val bad = assertFailsWith<IllegalArgumentException> { settings.set(SettingsService.LIMITS_BUILD_PRIORITY, JsonPrimitive("high")) }
        assertTrue("normal" in bad.message.orEmpty() && "belownormal" in bad.message.orEmpty(), "the refusal names the choices")
        assertFailsWith<IllegalArgumentException> { settings.set(SettingsService.LIMITS_BUILD_PRIORITY, JsonPrimitive(1)) }
        assertFailsWith<IllegalArgumentException> { settings.set(SettingsService.LIMITS_BUILD_MAX_WORKERS, JsonPrimitive(-1)) }
        assertFailsWith<IllegalArgumentException> { settings.set(SettingsService.LIMITS_BUILD_MAX_WORKERS, JsonPrimitive("4")) }
        assertFailsWith<IllegalArgumentException> { settings.set(SettingsService.LIMITS_CPU_ALERT_PERCENT, JsonPrimitive(0)) }
        assertFailsWith<IllegalArgumentException> { settings.set(SettingsService.LIMITS_CPU_ALERT_PERCENT, JsonPrimitive(101)) }
        assertEquals(BuildLimits(BuildPriority.IDLE, 0, 3), settings.buildLimits(), "a refused value changes nothing")
    }
}
