package com.superiorsoftllc.agenthq.daemon

import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** DP-5h-3e-a: the core board ports resolve the attached board at call time; with none attached they answer "no board". */
class BoardPortsTest {
    private class Fake : BoardPorts {
        val saved = CopyOnWriteArrayList<Pair<String, String>>()
        var cleared = 0
        override fun ticket(id: String) = if (id == "t1") BoardTicketRef("s1", "p1", "in_progress") else null
        override fun starterOf(workerSessionId: String) = if (workerSessionId == "s1") "boss" else null
        override fun titlesBySession() = mapOf("s1" to "Title")
        override fun brief(sessionId: String) = "brief:$sessionId"
        override fun startMoves(): StartMoves<Any> = error("not needed")
        override fun saveFilesRead(ticketId: String, line: String) { saved += ticketId to line }
        override fun clearQueueMarks() { cleared++ }
        override fun sweepWorkers(graceMs: Long, flag: (String, String?, String) -> Unit): List<String> {
            flag("t1", "s1", "unbriefed"); return listOf("t1")
        }
    }

    @Test fun `no board attached answers no board`() {
        val ref = BoardPortRef()
        assertFalse(ref.attached)
        assertNull(ref.ticket("t1")); assertNull(ref.starterOf("s1")); assertNull(ref.brief("s1"))
        assertTrue(ref.titlesBySession().isEmpty()); assertTrue(ref.sweepWorkers(1).isEmpty())
        ref.saveFilesRead("t1", "x"); ref.clearQueueMarks()
        assertEquals("this daemon has no board", assertFailsWith<IllegalStateException> { ref.startMoves() }.message)
    }

    @Test fun `an attached board is resolved at call time and detach returns to no board`() {
        val ref = BoardPortRef()
        val consumerBrief: (String) -> String? = { ref.brief(it) } // built before any board exists
        assertNull(consumerBrief("s1"))
        val fake = Fake()
        ref.attach(fake)
        assertEquals("brief:s1", consumerBrief("s1"))
        assertEquals(BoardTicketRef("s1", "p1", "in_progress"), ref.ticket("t1"))
        assertNull(ref.ticket("zz"))
        assertEquals("boss", ref.starterOf("s1"))
        assertEquals(mapOf("s1" to "Title"), ref.titlesBySession())
        ref.saveFilesRead("t1", "Files read: a"); ref.clearQueueMarks()
        assertEquals(listOf("t1" to "Files read: a"), fake.saved); assertEquals(1, fake.cleared)
        val flagged = mutableListOf<String>()
        assertEquals(listOf("t1"), ref.sweepWorkers(5) { t, _, d -> flagged += "$t:$d" })
        assertEquals(listOf("t1:unbriefed"), flagged)
        ref.detach()
        assertNull(consumerBrief("s1"))
    }

    @Test fun `worker sweep over a missing board does not fail`() {
        WorkerSweep(BoardPortRef(), { 1L }) { _, _, _ -> }.close()
    }
}
