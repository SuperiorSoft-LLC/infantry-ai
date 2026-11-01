package com.superiorsoftllc.agenthq.plugins.official.scripting.daemon

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** SCRIPT-VIS-1b: persistence, the chat contribution payload, the lost verdict, and a throwing listener. */
class ScriptRunLogVisTest {
    private class MemStore : RunStore {
        val rows = LinkedHashMap<Pair<String, Long>, RunRecord>()
        override fun save(rec: RunRecord) { rows[rec.project to rec.id] = rec }
        override fun trim(project: String, keep: Int) {
            val ids = rows.keys.filter { it.first == project }.map { it.second }.sortedDescending().drop(keep)
            ids.forEach { rows.remove(project to it) }
        }
        override fun load() = rows.values.sortedBy { it.id }
    }

    @Test fun recordsSurviveARestartAndStayBounded() {
        val store = MemStore()
        val a = ScriptRunLog({ 2 }, store = store)
        repeat(3) { a.record("p", "pong", "mail", "s1", null, "handled", "handled #$it", ref = "m$it") }
        val b = ScriptRunLog({ 2 }, store = store)
        val items = b.timelineItems()
        assertEquals(2, items.size)
        assertTrue(items.last().toString().contains("handled #2"))
    }

    @Test fun timelineItemCarriesAutomationLabelAndSession() {
        val rec = ScriptRunLog().record("p", "pong", "mail", "s1", null, "handled", "handled x")
        val j = assertNotNull(rec.timelineJson()).toString()
        assertTrue(j.contains("\"sessionId\":\"s1\"") && j.contains("AUTOMATION · pong") && j.contains("\"source\":\"automation\""))
        assertNull(ScriptRunLog().record("p", "pong", "mail", null, null, "handled", "x").timelineJson())
    }

    @Test fun lostVerdictAmendsTheRecordAndPushesIt() {
        val log = ScriptRunLog()
        val pushed = ArrayList<RunRecord>()
        log.onRecord = { pushed += it }
        log.record("p", "pong", "mail", "s1", null, "handled", "handled x", ref = "m1")
        log.verdictLost("m1", "timeout")
        val last = pushed.last()
        assertEquals("verdict-lost", last.outcome)
        assertFalse(last.ok)
        assertTrue(last.error!!.contains("timeout"))
    }

    @Test fun throwingListenerNeverFailsARecord() {
        val log = ScriptRunLog()
        log.onRecord = { error("boom") }
        assertNotNull(log.record("p", "pong", "mail", "s1", null, "handled", "x"))
    }

    @Test fun statsItemCountsTurnsSavedAndWakesByReasonAndIsPushed() {
        val log = ScriptRunLog()
        val pushed = ArrayList<String>()
        log.onStats = { pushed += it.toString() }
        assertNull(log.statsItem("s1"), "no wake yet = no item")
        log.record("p", "pong", "mail", "s1", null, "handled", "x")
        log.wake("s1", ScriptRunLog.HANDLED, "m1", "p"); log.wake("s1", ScriptRunLog.HANDLED, "m2", "p"); log.wake("s1", "owner", null, "p")
        val j = assertNotNull(log.statsItem("s1")).toString()
        assertTrue(j.contains("\"source\":\"automation-stats\"") && j.contains("\"turnsSaved\":\"2\"") && j.contains("\"wakes\":\"1\"") && j.contains("owner:1") && j.contains("\"lastWake\":\"owner\""), j)
        assertTrue(j.contains("\"projectTurnsSaved\":\"2\""), j)
        assertEquals(3, pushed.size)
        assertTrue(log.timelineItems().any { it.toString().contains("stats-s1") }, "a timeline_list after a reconnect carries the stats too")
    }

    @Test fun throwingStatsListenerNeverFailsAWake() {
        val log = ScriptRunLog()
        log.onStats = { error("boom") }
        log.wake("s1", "owner", null)
        assertEquals("1", log.sessionStats("s1")["wakes"].toString())
    }
}
