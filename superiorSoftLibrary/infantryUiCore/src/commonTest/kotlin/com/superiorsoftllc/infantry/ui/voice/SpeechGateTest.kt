package com.superiorsoftllc.infantry.ui.voice

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SpeechGateTest {
    private fun n(id: String, say: String = "say $id", level: String = "needs-owner", project: String? = "p1") =
        SpokenNotice(id, 0, say, level, project, "Alpha")

    private fun decide(g: SpeechGate, n: SpokenNotice, s: VoiceSettings = VoiceSettings(), now: Long = 0, hour: Int = 12, focused: Boolean = false) =
        g.decide(n, s, now, hour, focused)

    @Test fun speaksOnceThenDedupesByIdAndByText() {
        val g = SpeechGate()
        assertEquals(SpeechDecision.SPEAK, decide(g, n("a", "gate failed")))
        assertEquals(SpeechDecision.SKIP_DUPLICATE, decide(g, n("a", "other"), now = 1))
        assertEquals(SpeechDecision.SKIP_DUPLICATE, decide(g, n("b", "Gate failed "), now = 2))
        assertEquals(SpeechDecision.SPEAK, decide(g, n("c", "gate failed", project = "p2"), now = 3))
        assertEquals(SpeechDecision.SPEAK, decide(g, n("d", "gate failed"), now = 11 * 60_000L))
    }

    @Test fun capsPerMinuteButUrgentIsExemptAndInterrupts() {
        val g = SpeechGate(maxPerMinute = 2)
        assertTrue(decide(g, n("1"), now = 0).speaks)
        assertTrue(decide(g, n("2"), now = 1).speaks)
        assertEquals(SpeechDecision.SKIP_RATE, decide(g, n("3"), now = 2))
        assertEquals(SpeechDecision.SPEAK_INTERRUPT, decide(g, n("4", level = "urgent"), now = 3))
        assertEquals(SpeechDecision.SPEAK, decide(g, n("5"), now = 60_001))
    }

    @Test fun levelFilterHonoursTheMinimum() {
        val s = VoiceSettings(minLevel = VoiceLevel.NEEDS_OWNER)
        assertEquals(SpeechDecision.SKIP_LEVEL, decide(SpeechGate(), n("1", level = "info"), s))
        assertTrue(decide(SpeechGate(), n("2", level = "needs-owner"), s).speaks)
        assertTrue(decide(SpeechGate(), n("3", level = "urgent"), s).speaks)
        assertEquals(SpeechDecision.SKIP_LEVEL, decide(SpeechGate(), n("4", level = "bogus"), s))
    }

    @Test fun disabledQuietHoursAndFocus() {
        assertEquals(SpeechDecision.SKIP_DISABLED, decide(SpeechGate(), n("1"), VoiceSettings(enabled = false)))
        val quiet = VoiceSettings(quietHoursEnabled = true, quietStartHour = 22, quietEndHour = 8)
        assertEquals(SpeechDecision.SKIP_QUIET, decide(SpeechGate(), n("2"), quiet, hour = 23))
        assertEquals(SpeechDecision.SKIP_QUIET, decide(SpeechGate(), n("3"), quiet, hour = 7))
        assertTrue(decide(SpeechGate(), n("4"), quiet, hour = 8).speaks)
        val unfocused = VoiceSettings(onlyWhenUnfocused = true)
        assertEquals(SpeechDecision.SKIP_FOCUSED, decide(SpeechGate(), n("5"), unfocused, focused = true))
        assertTrue(decide(SpeechGate(), n("6"), unfocused, focused = false).speaks)
    }

    @Test fun quietRangeEdges() {
        assertFalse(inQuietHours(5, 9, 9))
        assertTrue(inQuietHours(9, 9, 17))
        assertFalse(inQuietHours(17, 9, 17))
    }

    @Test fun readsTheProjectFirstAndNothingElse() {
        assertEquals("Alpha. need restart", spokenText(n("1", "need restart")))
        assertEquals("need restart", spokenText(SpokenNotice("1", say = "need restart")))
    }

    @Test fun decodesTheDaemonFrameAndIgnoresUnknownKeys() {
        val json = Json { ignoreUnknownKeys = true }
        val got = json.decodeFromString(
            SpokenNotice.serializer(),
            """{"id":"x","at":5,"say":"hi","level":"urgent","projectId":"p","projectName":"Alpha","ticketId":"t","future":1}""",
        )
        assertEquals(SpokenNotice("x", 5, "hi", "urgent", "p", "Alpha", "t"), got)
        assertEquals(VoiceLevel.URGENT, VoiceLevel.fromWire(got.level))
    }
}
