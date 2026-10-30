package com.superiorsoftllc.agenthq.usage

import com.superiorsoftllc.agenthq.daemon.api.usage.*

import com.superiorsoftllc.agenthq.persistence.SqlAgentHqStore
import com.superiorsoftllc.agenthq.settings.SettingsService
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** USAGE-PACE (contract 1.119.0 / 1.121.0): meter mapping, the rule, manual entries, overrides, fail open. */
class UsagePacingTest {
    private val now = 1_800_000_000_000L
    private val hour = 3_600_000L
    private val dir: File = Files.createTempDirectory("agenthq-usage").toFile()
    private val store = SqlAgentHqStore.open(File(dir, "agenthq.db").absolutePath)
    private val settings = SettingsService(store)

    @AfterTest
    fun tearDown() {
        store.close()
        dir.deleteRecursively()
    }

    private fun live(provider: String, vararg pct: Double) = ProviderLimits(
        provider, "ok",
        pct.mapIndexed { i, p -> LimitWindow(provider, "w$i", 300, p, now + hour, now, "live", 4.0) }, null, now,
    )

    private fun pacing(limits: List<ProviderLimits>, workers: List<String>) = UsagePacing(
        limits = { limits }, rule = settings::usagePacing, manual = settings::usageManual,
        overrides = settings::usageOverrides, workerMeters = { workers }, clock = { now },
    )

    @Test
    fun meterFollowsTheModelsPlanNotTheHarness() {
        assertEquals("claude", UsageMeters.meterFor("claude", null))
        assertEquals("claude", UsageMeters.meterFor("pi", "anthropic/claude-opus-5-5"))
        assertEquals("opencode-go", UsageMeters.meterFor("opencode", "opencode-go/kimi"))
        assertEquals("pi", UsageMeters.meterFor("pi", "openai/gpt"))
    }

    @Test
    fun defaultRuleTiers_highestWindowCounts() {
        settings.applyUsage(UsageSet(rule = UsageText.parseRule("60:3,80:2,1")), now)
        val rule = settings.usagePacing()
        assertEquals(3, rule.allowance(59.9))
        assertEquals(2, rule.allowance(60.0))
        assertEquals(1, rule.allowance(95.0))
        assertNull(rule.allowance(null), "unknown usage = no limit (fail open)")
        val m = pacing(listOf(live("claude", 20.0, 81.0)), listOf("claude")).report().meters.single()
        assertEquals(81.0, m.percent)
        assertEquals(1, m.allowance)
        assertEquals(1, m.running)
        assertFalse(m.canStart)
        assertIs<PacingVerdict.Over>(pacing(listOf(live("claude", 81.0)), listOf("claude")).check("claude"))
    }

    private fun win(label: String, pct: Double, elapsed: Double, delta: Double = 0.0, minutes: Int = 10_080) =
        LimitWindow("claude", label, minutes, pct, now + ((1 - elapsed) * minutes * 60_000).toLong(), now, "live", delta)

    private fun meter(vararg w: LimitWindow, running: Int = 0) =
        pacing(listOf(ProviderLimits("claude", "ok", w.toList(), null, now)), List(running) { "claude" }).report().meters.single()

    @Test
    fun onPace_allowsTheMax_andShowsTheProjection() {
        val m = meter(win("Current week", 32.0, 0.32, 0.2), running = 3)
        assertEquals(5, m.allowance)
        assertEquals(100.0, m.projectedPercent!!, 0.01)
        assertTrue(m.canStart)
        assertTrue(UsageText.render(pacing(emptyList(), emptyList()).report()).contains("pace"))
        assertTrue(UsageText.render(UsageReport(listOf(m), PacingRule.DEFAULT, now)).contains("projected 100"))
    }

    @Test
    fun behindPace_allowsTheMax() {
        assertEquals(5, meter(win("Current week", 20.0, 0.5, -30.0)).allowance)
    }

    @Test
    fun aheadOfPace_isReducedSmoothly_neverBelowOne() {
        assertEquals(3, meter(win("Current week", 21.5, 0.2, 3.0)).allowance) // projected 107.5
        assertEquals(1, meter(win("Current week", 60.0, 0.45, 15.0)).allowance) // projected 133
        assertEquals(1, meter(win("Current week", 70.0, 0.3, 40.0)).allowance)
    }

    @Test
    fun nearTheLimit_isOne_evenWhenProjectionIsFine() {
        assertEquals(1, meter(win("Current week", 96.0, 1.0, -1.0)).allowance)
        assertEquals(1, meter(win("Current week", 91.0, 0.95, 1.0)).allowance) // >=90 and ahead
        assertEquals(5, meter(win("Current week", 91.0, 0.95, -1.0)).allowance) // 95.8 projected, behind pace
    }

    @Test
    fun windowsCombineAsTheTighter() {
        val m = meter(win("5h limit", 10.0, 0.5, -40.0, 300), win("Monthly limit", 92.0, 0.5, 42.0, 43_200))
        assertEquals(1, m.allowance)
        assertEquals("Monthly limit", m.window)
    }

    @Test
    fun firstMinutesOfAWindow_useAFloor() {
        val m = meter(win("5h limit", 1.0, 0.0, 1.0, 300))
        assertEquals(10.0, m.projectedPercent!!, 0.01) // 1% / floor 0.10, no divide by zero
        assertEquals(5, m.allowance)
        val past = meter(win("5h limit", 1.0, -0.5, 1.0, 300)) // resetsAt beyond the window
        assertEquals(10.0, past.projectedPercent!!, 0.01)
    }

    @Test
    fun overrideBeatsThePaceRule() {
        settings.applyUsage(UsageSet(meter = "claude", overrideWorkers = 7, overrideUntil = now + hour), now)
        val m = meter(win("Current week", 96.0, 0.5, 40.0), running = 4)
        assertEquals(1, m.ruleAllowance)
        assertEquals(7, m.allowance)
        assertTrue(m.canStart)
    }

    @Test
    fun refusalShowsTheProjection_andPaceRuleTextParses() {
        val v = pacing(listOf(ProviderLimits("claude", "ok", listOf(win("Current week", 60.0, 0.45, 15.0)), null, now)), listOf("claude"))
            .check("claude")
        assertTrue((v as PacingVerdict.Over).reason.contains("projected 133.3% at reset"))
        settings.applyUsage(UsageSet(rule = UsageText.parseRule("pace:8")), now)
        assertEquals(8, settings.usagePacing().maxWorkers)
        assertEquals(PacingRule.MODE_PACE, settings.usagePacing().mode)
    }

    @Test
    fun unknownUsageFailsOpenWithAWarning() {
        val m = pacing(listOf(ProviderLimits.unknown("claude", now, LimitReason.NOT_READ_YET)), List(9) { "claude" }).report().meters.single()
        assertNull(m.allowance)
        assertTrue(m.canStart)
        assertNotNull(m.warning)
    }

    @Test
    fun manualPercentFeedsAMeterWithNoLiveRead_andClears() {
        settings.applyUsage(UsageSet(meter = "OpenCode-Go", manualPercent = 70.0, manualResetsAt = now + hour), now)
        val m = pacing(emptyList(), emptyList()).report().meters.single()
        assertEquals("opencode-go", m.meter)
        assertEquals("manual", m.source)
        assertEquals(2, m.allowance)
        settings.applyUsage(UsageSet(meter = "opencode-go", clearManual = true), now)
        assertTrue(pacing(emptyList(), emptyList()).report().meters.isEmpty())
    }

    @Test
    fun overrideBeatsTheRuleUntilItLapses() {
        settings.applyUsage(UsageSet(meter = "claude", overrideWorkers = 5, overrideUntil = now + hour), now)
        val m = pacing(listOf(live("claude", 90.0)), List(4) { "claude" }).report().meters.single()
        assertEquals(1, m.ruleAllowance)
        assertEquals(5, m.allowance)
        assertTrue(m.canStart)
        val later = UsagePacing({ listOf(live("claude", 90.0)) }, settings::usagePacing, settings::usageManual, settings::usageOverrides, { List(4) { "claude" } }, { now + 2 * hour })
        assertEquals(1, later.report().meters.single().allowance, "a lapsed override is dropped")
    }

    @Test
    fun ruleTextParses_andABadRequestChangesNothing() {
        settings.applyUsage(UsageSet(rule = UsageText.parseRule("50:4, 90%:1, 0")), now)
        assertEquals(listOf(PacingTier(50.0, 4), PacingTier(90.0, 1), PacingTier(null, 0)), settings.usagePacing().tiers)
        assertFailsWith<IllegalArgumentException> { UsageText.parseRule("80:2,60:3,1") }
        assertFailsWith<IllegalArgumentException> { UsageText.parseRule("60:3") }
        assertFailsWith<IllegalArgumentException> {
            settings.applyUsage(UsageSet(meter = "claude", manualPercent = 120.0), now)
        }
        assertTrue(settings.usageManual().isEmpty())
        assertFailsWith<IllegalArgumentException> { settings.applyUsage(UsageSet(), now) }
        assertFalse(UsageText.parseRule("off", settings.usagePacing()).toString().contains("\"enabled\":true"))
    }

    @Test fun `the API ceiling default is the registry default`() {
        kotlin.test.assertEquals(com.superiorsoftllc.agenthq.settings.LimitRegistry.PACING_CEILING.default.toInt(), PacingRule.PACING_CEILING_DEFAULT)
    }
}
