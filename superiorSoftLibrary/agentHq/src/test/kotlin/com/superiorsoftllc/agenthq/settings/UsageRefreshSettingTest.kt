package com.superiorsoftllc.agenthq.settings

import com.superiorsoftllc.agenthq.persistence.SqlAgentHqStore
import kotlinx.serialization.json.JsonPrimitive
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class UsageRefreshSettingTest {
    private val dir: File = Files.createTempDirectory("agenthq-usagerefresh").toFile()
    private val store = SqlAgentHqStore.open(File(dir, "agenthq.db").absolutePath)
    private val settings = SettingsService(store)

    @AfterTest
    fun tearDown() { store.close(); dir.deleteRecursively() }

    @Test
    fun defaultsToFiveAndValidates() {
        assertEquals(5, settings.usageRefreshMinutes())
        assertEquals(JsonPrimitive(5), settings.all()[SettingsService.USAGE_REFRESH_MINUTES])
        settings.set(SettingsService.USAGE_REFRESH_MINUTES, JsonPrimitive(0))
        assertEquals(0, settings.usageRefreshMinutes())
        settings.set(SettingsService.USAGE_REFRESH_MINUTES, JsonPrimitive(15))
        assertFailsWith<IllegalArgumentException> { settings.set(SettingsService.USAGE_REFRESH_MINUTES, JsonPrimitive(-1)) }
        assertEquals(15, settings.usageRefreshMinutes())
    }
}
