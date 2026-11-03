package com.superiorsoftllc.infantry.settings

import com.superiorsoftllc.infantry.persistence.SqlInfantryStore
import kotlinx.serialization.json.JsonPrimitive
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * BUG-M2-11b-2 (docs/INFANTRY-SESSION-HOST-PLAN.md section 8): `session.keepAliveMinutes`, the one lifecycle setting,
 * through the existing settings_get / settings_set. int 0..1440, default 30; 0 = every harness one-shot.
 */
class KeepAliveSettingTest {
    private val dir: File = Files.createTempDirectory("infantry-keepalive").toFile()
    private val store = SqlInfantryStore.open(File(dir, "infantry.db").absolutePath)
    private val settings = SettingsService(store)

    @AfterTest
    fun tearDown() {
        store.close()
        dir.deleteRecursively()
    }

    @Test
    fun defaultsTo30_storesAnIntInRange_refusesEverythingElse() {
        assertEquals(JsonPrimitive(30), settings.all()["session.keepAliveMinutes"], "the default")
        assertEquals(30, settings.keepAliveMinutes())

        assertEquals(JsonPrimitive(0), settings.set("session.keepAliveMinutes", JsonPrimitive(0))["session.keepAliveMinutes"])
        assertEquals(0, settings.keepAliveMinutes(), "0 = keep-alive off")
        settings.set("session.keepAliveMinutes", JsonPrimitive(1440))
        assertEquals(1440, SettingsService(store).keepAliveMinutes(), "durable, read back by a fresh service")

        for (bad in listOf(JsonPrimitive(-1), JsonPrimitive(1441), JsonPrimitive("30"), JsonPrimitive(2.5), JsonPrimitive(true))) {
            assertFailsWith<IllegalArgumentException>("$bad") { settings.set("session.keepAliveMinutes", bad) }
        }
        assertEquals(1440, settings.keepAliveMinutes(), "a refused value changes nothing")
    }

    /** BUG-M2-11f: a stored keep-alive value is announced, so the daemon re-evaluates kept processes at once. */
    @Test
    fun storingKeepAlive_tellsTheListener_otherKeysAndRefusalsDoNot() {
        var told = 0
        settings.onKeepAliveChanged = { told++ }

        settings.set("session.keepAliveMinutes", JsonPrimitive(5))
        assertEquals(1, told)
        assertFailsWith<IllegalArgumentException> { settings.set("session.keepAliveMinutes", JsonPrimitive(-1)) }
        settings.set("context.includeSkillsContext", JsonPrimitive(false))
        assertEquals(1, told, "a refused value and another key say nothing")
    }
}
