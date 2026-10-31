package com.superiorsoftllc.agenthq.daemon

import com.superiorsoftllc.agenthq.daemon.api.board.BoardCoreAccess
import com.superiorsoftllc.agenthq.daemon.api.board.BoardCoreTicket
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** DP-5h-3e-b: core's ports over a plugin-attached [BoardCoreAccess] resolve at call time and answer "no board" once detached. */
class PluginBoardPortsTest {
    private class Fake : BoardCoreAccess {
        var cleared = 0
        override fun ticket(id: String) = if (id == "t1") BoardCoreTicket("s1", "p1", "in_progress") else null
        override fun starterOf(workerSessionId: String) = "starter-$workerSessionId"
        override fun titlesBySession() = mapOf("s1" to "Title")
        override fun brief(sessionId: String) = "brief-$sessionId"
        override fun cutRepo(projectId: String?) = null
        override fun epicBaseBranchOf(epicId: String) = "base-$epicId"
        override fun ticketIdsByEpic() = mapOf("e1" to listOf("t1"))
        override fun epicJson(epicId: String) = null
        override fun laneCapsChanged(projectId: String) {}
        override fun clearQueueMarks(): Int { cleared++; return 2 }
        override fun sweepWorkers(graceMs: Long, flag: (String, String?, String) -> Unit): List<String> { flag("t1", "s1", "d"); return listOf("t1") }
        override fun getTicketJson(id: String) = error("unused")
        override fun moveTicketJson(moveJson: String, startedBy: String?) = error("unused")
        override fun stopQueueDrain() {}
        override fun invoke(op: String, payload: String, startedBy: String?): String = ""
        override fun close() {}
    }

    @Test
    fun attachedAccessAnswersAndDetachedPortAnswersNoBoard() {
        val ref = BoardPortRef()
        assertNull(ref.ticket("t1"))
        assertFalse(ref.attached)
        val fake = Fake()
        ref.attach(PluginBoardPorts(fake))
        assertEquals(BoardTicketRef("s1", "p1", "in_progress"), ref.ticket("t1"))
        assertNull(ref.ticket("nope"))
        assertEquals("starter-w", ref.starterOf("w"))
        assertEquals(mapOf("s1" to "Title"), ref.titlesBySession())
        assertEquals("brief-s1", ref.brief("s1"))
        ref.clearQueueMarks()
        assertEquals(1, fake.cleared)
        val flagged = mutableListOf<String>()
        assertEquals(listOf("t1"), ref.sweepWorkers(1) { id, _, _ -> flagged += id })
        assertEquals(listOf("t1"), flagged)
        ref.detach()
        assertTrue(ref.titlesBySession().isEmpty())
        assertNull(ref.brief("s1"))
    }
}
