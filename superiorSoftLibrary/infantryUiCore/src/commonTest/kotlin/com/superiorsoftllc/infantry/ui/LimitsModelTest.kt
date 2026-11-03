package com.superiorsoftllc.infantry.ui

import com.superiorsoftllc.infantry.ui.model.AppState
import com.superiorsoftllc.infantry.ui.model.LimitsView
import com.superiorsoftllc.infantry.ui.model.reduce
import com.superiorsoftllc.infantry.ui.protocol.Envelope
import com.superiorsoftllc.infantry.ui.protocol.LimitWindow
import kotlinx.datetime.TimeZone
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** U2 (contract 1.59.0): the `limits` frame decodes into the panel's state, and the panel's words are honest. */
class LimitsModelTest {
    private val frame = """
        [{"provider":"claude","status":"ok","reason":null,"observedAt":1000,"windows":[
          {"provider":"claude","label":"Current session","windowMinutes":300,"percent":42.0,"resetsAt":2000,"observedAt":1000,"source":"live","paceDelta":-12.3}]},
         {"provider":"opencode-go","status":"unknown","reason":"expired","observedAt":900,"windows":[],"later":"ignored"}]
    """.trimIndent()

    @Test fun `a limits frame replaces the plan limits, in the daemon's order`() {
        assertNull(AppState().planLimits, "never answered is not the same as no source")
        val s = reduce(AppState(), Envelope(Envelope.LIMITS, frame), 0)
        val limits = s.planLimits!!
        assertEquals(listOf("claude", "opencode-go"), limits.map { it.provider })
        assertEquals(42.0, limits[0].windows.single().percent)
        assertEquals(-12.3, limits[0].windows.single().paceDelta)
        assertEquals("expired", limits[1].reason)
        assertEquals(1000L, LimitsView.observedAt(limits))
        val again = reduce(s, Envelope(Envelope.LIMITS, "[]"), 0)
        assertEquals(emptyList(), again.planLimits, "each answer replaces the last one whole")
    }

    @Test fun `unknown is a dash and a reason, never zero`() {
        assertEquals("—", LimitsView.percentText(null))
        assertEquals("42%", LimitsView.percentText(42.4))
        assertEquals("not signed in", LimitsView.reasonText("no_credentials"))
        assertEquals("no subscription", LimitsView.reasonText("no_subscription"))
        assertEquals("expired", LimitsView.reasonText("expired"))
        assertEquals("HTTP 500", LimitsView.reasonText("http_500"))
        assertEquals(0, LimitsView.filledSegments(0.0))
        assertEquals(1, LimitsView.filledSegments(0.4), "a sliver of use still shows a cell")
        assertEquals(LimitsView.SEGMENTS, LimitsView.filledSegments(100.0))
    }

    @Test fun `pace reads ahead or under, rounded to whole points`() {
        assertEquals("12 pts under pace", LimitsView.paceText(-12.3))
        assertEquals("10 pts ahead of pace", LimitsView.paceText(9.5))
        assertEquals("on pace", LimitsView.paceText(0.2))
        assertNull(LimitsView.paceText(null))
        val w = LimitWindow("claude", "Current week", 10080, 81.0, 0, 0, "live", 9.5)
        assertTrue(LimitsView.ahead(w))
        assertFalse(LimitsView.ahead(w.copy(paceDelta = -1.0)))
        assertFalse(LimitsView.ahead(w.copy(paceDelta = null)), "an unknown pace is not drawn as ahead")
        assertEquals("Current week: 81% used, ahead of pace", LimitsView.barDescription(w))
    }

    @Test fun `the reset is a date and a time`() {
        // 2026-10-19T15:05:00Z
        assertEquals("Resets Oct 19 3:05 PM", LimitsView.resetText(1_792_422_300_000L, TimeZone.UTC))
        assertNull(LimitsView.resetText(null))
    }
}
