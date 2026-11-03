package com.superiorsoftllc.infantry.ui

import com.superiorsoftllc.infantry.ui.model.AppState
import com.superiorsoftllc.infantry.ui.model.LimitsSetting
import com.superiorsoftllc.infantry.ui.model.reduce
import com.superiorsoftllc.infantry.ui.protocol.Envelope
import com.superiorsoftllc.infantry.ui.protocol.WireRequests
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** No-hard-coded-limits (contract 1.133.0): the Limits section's model reads the catalog and follows `settings`. */
class LimitsSettingModelTest {
    private val catalog = """{"limits":[
        {"key":"limits.concurrency.maxPerPod","value":4,"default":4,"unit":"sessions","description":"pod cap","min":0,"zeroMeans":"unlimited","env":"INFANTRY_LIMIT_CONCURRENCY_MAX_PER_POD"},
        {"key":"limits.cpu.alertPercent","value":90,"default":90,"unit":"%","description":"cpu","min":1,"max":100,"env":"INFANTRY_LIMIT_CPU_ALERT_PERCENT"}
    ]}"""

    @Test
    fun theCatalogIsReadAndGrouped() {
        val s = reduce(AppState(), Envelope(Envelope.LIMIT_CATALOG, catalog), 1)
        val limits = s.limitCatalog!!
        assertEquals(listOf("concurrency", "cpu"), limits.map { it.group })
        assertEquals("maxPerPod", limits[0].name)
        assertEquals("unlimited", limits[0].zeroMeans)
        assertNull(limits[1].zeroMeans)
        assertEquals(100L, limits[1].max)
        assertEquals("1..100 %; default 90; env INFANTRY_LIMIT_CPU_ALERT_PERCENT", limits[1].rangeText())
    }

    @Test
    fun aSettingsAnswerUpdatesTheValues_otherFramesChangeNothing() {
        val s = reduce(AppState(), Envelope(Envelope.LIMIT_CATALOG, catalog), 1)
        val next = reduce(s, Envelope(Envelope.SETTINGS, """{"limits.concurrency.maxPerPod":12,"session.keepAliveMinutes":30}"""), 1)
        assertEquals(12L, next.limitCatalog!![0].value)
        assertEquals(90L, next.limitCatalog!![1].value)
        assertNull(reduce(AppState(), Envelope(Envelope.SETTINGS, "{}"), 1).limitCatalog, "no catalog until the daemon sends one")
    }

    @Test
    fun aTypedValueMustBeAWholeNumberInRange() {
        val cpu = LimitsSetting.fromCatalogFrame(catalog)!![1]
        assertEquals(75L, LimitsSetting.parse(cpu, " 75 "))
        assertNull(LimitsSetting.parse(cpu, "0"))
        assertNull(LimitsSetting.parse(cpu, "101"))
        assertNull(LimitsSetting.parse(cpu, "ten"))
        assertEquals(Envelope.LIMIT_CATALOG, WireRequests.limitCatalog().type)
    }
}
