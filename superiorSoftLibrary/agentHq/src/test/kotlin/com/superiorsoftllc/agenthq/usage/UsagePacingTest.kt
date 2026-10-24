package com.superiorsoftllc.agenthq.usage

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
        val rule = PacingRule.DEFAULT
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
}
