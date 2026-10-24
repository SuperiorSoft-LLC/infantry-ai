package com.superiorsoftllc.agenthq.settings

import com.superiorsoftllc.agenthq.persistence.SqlAgentHqStore
import com.superiorsoftllc.agenthq.session.ProcessWatchConfig
import kotlinx.serialization.json.JsonPrimitive
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** ROGUE-1 (1.120.0): the `processes.*` settings; auto-reap is off until the owner turns it on. */
class ProcessWatchSettingTest {
    private val dir: File = Files.createTempDirectory("agenthq-procwatch").toFile()
    private val store = SqlAgentHqStore.open(File(dir, "agenthq.db").absolutePath)
    private val settings = SettingsService(store)

    @AfterTest
    fun tearDown() {
        store.close()
        dir.deleteRecursively()
    }

    @Test
    fun defaultsAreListed_valuesAreValidatedAndDurable() {
        assertEquals(ProcessWatchConfig(), settings.processWatchConfig())
        assertEquals(JsonPrimitive(false), settings.all()[SettingsService.PROCESSES_AUTO_REAP])
        assertEquals(JsonPrimitive(4096), settings.all()[SettingsService.PROCESSES_FLOOR_MB])

        settings.set(SettingsService.PROCESSES_AUTO_REAP, JsonPrimitive(true))
        settings.set(SettingsService.PROCESSES_IDLE_MINUTES, JsonPrimitive(10))
        settings.set(SettingsService.PROCESSES_HEAVY_MB, JsonPrimitive(800))
        settings.set(SettingsService.PROCESSES_FLOOR_MB, JsonPrimitive(2048))
        assertEquals(ProcessWatchConfig(10, 800, 2048, true), SettingsService(store).processWatchConfig())

        assertFailsWith<IllegalArgumentException> { settings.set(SettingsService.PROCESSES_AUTO_REAP, JsonPrimitive("yes")) }
        assertFailsWith<IllegalArgumentException> { settings.set(SettingsService.PROCESSES_IDLE_MINUTES, JsonPrimitive(0)) }
        assertFailsWith<IllegalArgumentException> { settings.set(SettingsService.PROCESSES_FLOOR_MB, JsonPrimitive(-1)) }
        assertEquals(10, settings.processWatchConfig().idleMinutes, "a refused value changes nothing")
    }
}
