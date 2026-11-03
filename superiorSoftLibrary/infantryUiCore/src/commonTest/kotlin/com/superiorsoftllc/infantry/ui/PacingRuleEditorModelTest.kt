package com.superiorsoftllc.infantry.ui

import com.superiorsoftllc.infantry.ui.model.PacingView
import com.superiorsoftllc.infantry.ui.protocol.MeterUsage
import com.superiorsoftllc.infantry.ui.protocol.PacingRule
import com.superiorsoftllc.infantry.ui.protocol.PacingTier
import com.superiorsoftllc.infantry.ui.protocol.UsageOverride
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** USAGE-PACE follow-up: the rule editor keeps the pace mode and its keys, and the meter shows its projection. */
class PacingRuleEditorModelTest {
    private val tiers = listOf(PacingTier(60.0, 3), PacingTier(80.0, 2), PacingTier(null, 1))
    private val pace = PacingRule(true, tiers, PacingRule.MODE_PACE, 5, 100.0, 95.0)

    @Test
    fun saving_a_pace_rule_keeps_its_mode_and_takes_the_edited_keys() {
        val r = PacingView.buildRule(pace, true, "60:3, 80:2, 1", PacingRule.MODE_PACE, "7", "90", "97.5").getOrThrow()
        assertEquals(PacingRule(true, tiers, PacingRule.MODE_PACE, 7, 90.0, 97.5), r)
    }

    @Test
    fun switching_to_bands_keeps_the_pace_keys_for_later() {
        val r = PacingView.buildRule(pace, false, "50:2, 1", PacingRule.MODE_BANDS, "5", "100", "95").getOrThrow()
        assertEquals(PacingRule.MODE_BANDS, r.mode)
        assertEquals(5, r.maxWorkers)
        assertFalse(r.enabled)
        assertEquals(listOf(PacingTier(50.0, 2), PacingTier(null, 1)), r.tiers)
    }

    @Test
    fun an_older_daemon_without_modes_is_edited_as_tiers_only() {
        val old = PacingRule(true, tiers)
        val r = PacingView.buildRule(old, true, "70:4, 1", null, "", "", "").getOrThrow()
        assertNull(r.mode)
        assertNull(r.maxWorkers)
        assertFalse(PacingView.hasModes(old))
    }

    @Test
    fun bad_pace_keys_name_the_problem() {
        assertTrue(PacingView.buildRule(pace, true, "1", PacingRule.MODE_PACE, "0", "100", "95").exceptionOrNull()!!.message!!.contains("max workers"))
        assertTrue(PacingView.buildRule(pace, true, "1", PacingRule.MODE_PACE, "5", "x", "95").exceptionOrNull()!!.message!!.contains("ok projection"))
        assertTrue(PacingView.buildRule(pace, true, "1", PacingRule.MODE_PACE, "5", "100", "120").exceptionOrNull()!!.message!!.contains("hard stop"))
    }

    @Test
    fun rule_text_names_the_mode() {
        assertEquals("pace · up to 5 ticket workers while projected <= 100% at reset · 1 from 95% used", PacingView.ruleText(pace))
        assertEquals("<60% -> 3 · <80% -> 2 · else -> 1 ticket workers", PacingView.ruleText(pace.copy(mode = PacingRule.MODE_BANDS)))
        assertEquals("off", PacingView.ruleText(pace.copy(enabled = false)))
    }

    @Test
    fun the_projection_and_the_reason_read_as_words() {
        val m = MeterUsage("claude", percent = 40.0, projectedPercent = 133.33, reasoning = "projected 133.3% at reset")
        assertEquals("projected 133.3% at reset", PacingView.projectedText(m))
        assertTrue(PacingView.projectedOver(m, pace))
        assertNull(PacingView.reasonText(m))
        assertNull(PacingView.projectedText(m.copy(projectedPercent = null)))
        val hard = m.copy(reasoning = "at 96% (hard stop 95%)")
        assertEquals("rule: at 96% (hard stop 95%)", PacingView.reasonText(hard))
        assertNull(PacingView.reasonText(hard.copy(override = UsageOverride(3, 1))))
    }
}
