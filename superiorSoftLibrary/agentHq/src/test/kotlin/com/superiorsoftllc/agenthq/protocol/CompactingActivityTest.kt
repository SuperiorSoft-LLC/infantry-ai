package com.superiorsoftllc.agenthq.protocol

import com.superiorsoftllc.agenthq.session.SessionState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Compaction reads as its own run phase: `thinking -> compacting -> thinking`, and an interrupted compaction ends the run. */
class CompactingActivityTest {
    private val id = "s1"
    private fun tracker() = RunActivityTracker().also { it.turnStarted(id); it.sessionState(id, SessionState.BUSY) }

    private fun feed(t: RunActivityTracker, raw: String) = EventNormalizer.normalise(raw).mapNotNull { t.line(id, it) }.lastOrNull()?.state

    @Test fun claudeCompactingStatusThenBoundary() {
        val t = tracker()
        assertEquals(RunPhase.COMPACTING, feed(t, """{"type":"system","subtype":"status","status":"compacting"}"""))
        assertEquals(RunPhase.THINKING, feed(t, """{"type":"system","subtype":"compact_boundary"}"""))
    }

    @Test fun claudeStatusNullEndsCompaction() {
        val t = tracker()
        feed(t, """{"type":"system","subtype":"status","status":"compacting"}""")
        assertEquals(RunPhase.THINKING, feed(t, """{"type":"system","subtype":"status","status":null}"""))
    }

    @Test fun piCompactionStartEnd() {
        val t = tracker()
        assertEquals(RunPhase.COMPACTING, feed(t, """{"type":"compaction_start","reason":"threshold"}"""))
        assertEquals(RunPhase.THINKING, feed(t, """{"type":"compaction_end","aborted":false}"""))
    }

    @Test fun compactionStaysInFlightAndInterruptedRunEnds() {
        val t = tracker()
        feed(t, """{"type":"compaction_start"}""")
        // Still in flight: a BUSY re-report must not drop the phase.
        assertNull(t.sessionState(id, SessionState.BUSY))
        assertEquals(RunPhase.COMPACTING, t.current(id).state)
        // Killed mid-compaction: the turn ends and the run settles to idle.
        assertEquals(RunPhase.FINISHED, t.turnEnded(id, failed = false).first().state)
        assertEquals(RunPhase.IDLE, t.sessionState(id, SessionState.IDLE)?.state ?: t.current(id).state)
    }

    @Test fun endWithoutStartIsHarmless() {
        val t = tracker()
        assertNull(feed(t, """{"type":"system","subtype":"compact_boundary"}"""))
        assertEquals(RunPhase.THINKING, t.current(id).state)
    }
}

class CompactingSessionRecordTest {
    @Test fun recordCarriesCompactingWhileBusyOnly() {
        val t = RunActivityTracker().also { it.turnStarted("rp1"); it.sessionState("rp1", SessionState.BUSY) }
        val rec = com.superiorsoftllc.agenthq.session.SessionRecord("rp1", "stub", "/w", 1, 1, SessionState.BUSY)
        val enc = { kotlinx.serialization.json.Json.encodeToString(com.superiorsoftllc.agenthq.session.SessionRecordSerializer, rec) }
        assertEquals(false, enc().contains("runPhase"))
        t.line("rp1", EventNormalizer.Line.CompactionStart)
        assertEquals(true, enc().contains("\"runPhase\":\"compacting\""))
        t.line("rp1", EventNormalizer.Line.CompactionEnd)
        assertEquals(false, enc().contains("runPhase"))
        t.forget("rp1")
    }
}
