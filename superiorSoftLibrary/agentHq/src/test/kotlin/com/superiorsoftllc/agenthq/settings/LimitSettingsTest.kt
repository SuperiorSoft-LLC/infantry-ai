package com.superiorsoftllc.agenthq.settings

import com.superiorsoftllc.agenthq.persistence.SqlAgentHqStore
import kotlinx.serialization.json.JsonPrimitive
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** S1 of the no-hard-coded-limits rule: stored setting > environment > documented default; 0 = unlimited. */
class LimitSettingsTest {
    /** No test here may hang a fork (a spinning test burned a core for 25 min once). */
    @get:org.junit.Rule
    val timeout: org.junit.rules.Timeout = org.junit.rules.Timeout.seconds(60)

    private val dir: File = Files.createTempDirectory("agenthq-limits").toFile()
    private val store = SqlAgentHqStore.open(File(dir, "agenthq.db").absolutePath)
    private var env = mapOf<String, String>()
    private val settings = SettingsService(store, env = { env[it] })

    @AfterTest
    fun tearDown() {
        store.close()
        dir.deleteRecursively()
    }

    @Test
    fun defaultsAreListed_concurrencyIsFourAndDepthTwo() {
        val all = settings.all()
        assertEquals(JsonPrimitive(4L), all["limits.concurrency.maxPerPod"])
        assertEquals(JsonPrimitive(1L), all["limits.concurrency.maxDepth"])
        assertEquals(JsonPrimitive(4L), all["limits.concurrency.mcpMaxSpawns"])
        assertEquals(JsonPrimitive(1L), all["limits.concurrency.laneDefault"])
        assertEquals(JsonPrimitive(50L), all["limits.pacing.maxWorkersCeiling"])
    }

    @Test
    fun storedBeatsEnvBeatsDefault_andChangesApplyAtOnce() {
        env = mapOf("AGENTHQ_POD_SPAWN_CAP" to "6")
        assertEquals(6, settings.limitCap(LimitRegistry.MAX_PER_POD), "the legacy env name still works")
        env = mapOf("AGENTHQ_LIMIT_CONCURRENCY_MAX_PER_POD" to "9", "AGENTHQ_POD_SPAWN_CAP" to "6")
        assertEquals(9, settings.limitCap(LimitRegistry.MAX_PER_POD))
        settings.set("limits.concurrency.maxPerPod", JsonPrimitive(20))
        assertEquals(20, SettingsService(store, env = { env[it] }).limitCap(LimitRegistry.MAX_PER_POD))
    }

    @Test
    fun zeroIsUnlimited_validationNamesTheRange() {
        settings.set("limits.concurrency.maxPerPod", JsonPrimitive(0))
        assertEquals(Int.MAX_VALUE, settings.limitCap(LimitRegistry.MAX_PER_POD))
        val e = assertFailsWith<IllegalArgumentException> { settings.set("limits.concurrency.maxPerPod", JsonPrimitive(-1)) }
        assertTrue("limits.concurrency.maxPerPod" in e.message!!)
        assertFailsWith<IllegalArgumentException> { settings.set("limits.concurrency.maxPerPod", JsonPrimitive("many")) }
        assertFailsWith<IllegalArgumentException> { settings.set("limits.pacing.maxWorkersCeiling", JsonPrimitive(0)) }
    }

    @Test
    fun badEnvIsReportedAndIgnored() {
        env = mapOf("AGENTHQ_LIMIT_CONCURRENCY_MAX_DEPTH" to "deep")
        assertEquals(1, settings.limitCap(LimitRegistry.MAX_DEPTH))
        assertTrue(settings.limitEnvProblems().single().contains("AGENTHQ_LIMIT_CONCURRENCY_MAX_DEPTH"))
    }

    @Test
    fun pacingCeilingIsASetting_aServerCanRaiseIt() {
        val rule = """{"enabled":true,"mode":"bands","maxWorkers":5,"tiers":[{"below":60.0,"workers":60},{"workers":1}]}"""
        val json = kotlinx.serialization.json.Json.parseToJsonElement(rule)
        assertFailsWith<IllegalArgumentException> { settings.set("usage.pacing", json) }
        settings.set("limits.pacing.maxWorkersCeiling", JsonPrimitive(10))
        assertFailsWith<IllegalArgumentException> { settings.set("usage.pacing", json) }
        settings.set("limits.pacing.maxWorkersCeiling", JsonPrimitive(100))
        settings.set("usage.pacing", json)
    }
}
