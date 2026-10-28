package com.superiorsoftllc.agenthq.session

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AutoProgressTest {
    private val min = 60_000L

    private fun signals(now: Long, history: List<Long> = listOf(10 * min, 12 * min, 14 * min), staleMs: Long = 5 * min, lastToolAt: Long? = now) =
        AutoProgress.Signals(startedAt = 0, now = now, toolCalls = 7, lastToolAt = lastToolAt, commits = 2, history = history, staleMs = staleMs)

    @Test
    fun medianOfSameWeightHistoryIsTheEstimate_marked() {
        val p = AutoProgress.derive(signals(4 * min))
        assertEquals(12 * min, p.estimateMs)
        assertEquals("history", p.estimateSource)
        assertEquals("auto", p.origin)
        assertEquals(true, p.estimated)
        val eta = ProgressReducer.eta(p, 4 * min)
        assertEquals(8 * min, eta.remainingMs)
        assertEquals("~8m left", ProgressReducer.countdownText(eta))
        assertTrue(p.label!!.contains("7 tool calls") && p.label!!.contains("2 commits"))
    }

    @Test
    fun tooLittleHistoryGivesNoNumber() {
        val p = AutoProgress.derive(signals(4 * min, history = listOf(10 * min, 12 * min)))
        assertNull(p.estimateMs)
        assertEquals("no estimate yet", ProgressReducer.countdownText(ProgressReducer.eta(p, 4 * min)))
    }

    @Test
    fun pastTheEstimateReadsOver() {
        val p = AutoProgress.derive(signals(15 * min))
        assertEquals("over estimate by 3m", ProgressReducer.countdownText(ProgressReducer.eta(p, 15 * min)))
    }

    @Test
    fun staleAfterTheConfiguredQuietTime_andNeverWhenZero() {
        assertNull(AutoProgress.derive(signals(10 * min, lastToolAt = 6 * min)).stale)
        assertEquals(true, AutoProgress.derive(signals(10 * min, lastToolAt = 4 * min)).stale)
        assertNull(AutoProgress.derive(signals(10 * min, lastToolAt = 4 * min, staleMs = 0)).stale)
    }

    @Test
    fun workerProgressWinsOverTheDerivedBar_onlyStaleIsAdded() {
        val own = SessionProgress(mode = "percent", percent = 40.0, startedAt = 0, updatedAt = 0)
        val s = signals(10 * min, lastToolAt = 0)
        val shown = AutoProgress.withStale(own, s)
        assertEquals(40.0, shown.percent)
        assertNull(shown.origin)
        assertEquals(true, shown.stale)
    }

    @Test
    fun overlayDerivesOnlyForABusyTicketSession_andIgnoresAStoredAutoBar() {
        val base = SessionRecord(id = "s", provider = "pi", workingDir = ".", spawnedAt = 0, lastHeartbeat = 0, state = SessionState.BUSY, ticketId = "t")
        assertEquals("auto", AutoProgress.overlay(base, 3, 1L, 1L, 2L)?.origin)
        assertNull(AutoProgress.overlay(base.copy(ticketId = null), 3, 1L, 1L, 2L))
        assertNull(AutoProgress.overlay(base.copy(state = SessionState.IDLE), 3, 1L, 1L, 2L))
        val stored = base.copy(state = SessionState.IDLE, progress = AutoProgress.derive(signals(2)))
        assertNull(AutoProgress.overlay(stored, 3, 1L, 1L, 2L))
    }

    @Test
    fun activityLogCountsToolCallsOnly() {
        SessionActivityLog.clear()
        SessionActivityLog.output("a", "Bash: ls", 1, tool = true)
        SessionActivityLog.output("a", "replying", 2)
        SessionActivityLog.output("a", "Read: x", 3, tool = true)
        assertEquals(2, SessionActivityLog.toolCalls("a"))
        SessionActivityLog.clear()
    }
}
