package com.superiorsoftllc.agenthq.settings

import com.superiorsoftllc.agenthq.persistence.SqlAgentHqStore
import com.superiorsoftllc.agenthq.runner.BuildLimits
import com.superiorsoftllc.agenthq.runner.BuildPriority
import com.superiorsoftllc.agenthq.session.CpuAlertConfig
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
    private val dir: File = Files.createTempDirectory("agenthq-buildlimits").toFile()
    private val store = SqlAgentHqStore.open(File(dir, "agenthq.db").absolutePath)
    private val settings = SettingsService(store)

    @AfterTest
    fun tearDown() {
        store.close()
        dir.deleteRecursively()
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
