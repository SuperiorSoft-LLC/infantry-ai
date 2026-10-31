package com.superiorsoftllc.agenthq.daemon.plugins

import com.superiorsoftllc.agenthq.daemon.api.board.BOARD_API_VERSION
import com.superiorsoftllc.agenthq.daemon.api.board.BoardAgent
import com.superiorsoftllc.agenthq.daemon.api.board.BoardRoleInstance
import com.superiorsoftllc.agenthq.daemon.api.board.BoardTrait
import com.superiorsoftllc.agenthq.daemon.api.board.SessionDelivery
import com.superiorsoftllc.agenthq.daemon.api.board.BoardSessionEvent
import com.superiorsoftllc.agenthq.daemon.api.board.NoBoardHost
import com.superiorsoftllc.agenthq.persistence.SqlAgentHqStore
import com.superiorsoftllc.agenthq.session.SessionEvent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** DP-5f-2: the new BoardHost members on a LocalBoardHost with nothing wired, and the event fan-in. */
class LocalBoardHostPortsTest {
    private val opened = mutableListOf<AutoCloseable>()

    @kotlin.test.AfterTest fun close() = opened.forEach { runCatching { it.close() } }

    private fun host() = LocalBoardHost(store = SqlAgentHqStore.open(kotlin.io.path.createTempDirectory("bh").resolve("a.db").toString()).also { opened += it as AutoCloseable }, sessionManager = { null as com.superiorsoftllc.agenthq.session.SessionManager? })

    @Test fun `api version is 1_8_0`() = assertEquals("1.8.0", BOARD_API_VERSION)

    @Test fun `unwired members answer like NoBoardHost`() {
        val h = host()
        assertNull(h.agents.get("x"))
        assertTrue(h.agents.list().isEmpty())
        assertEquals(NoBoardHost.agents.executorFor(BoardAgent(name = "a", role = "r", model = "m"), null, null, "high").model, h.agents.executorFor(BoardAgent(name = "a", role = "r", model = "m"), null, null, "high").model)
        assertNull(h.runs.outcomeOf("s"))
        assertTrue(h.runs.isAttemptOver("IDLE"))
        assertEquals(false, h.runs.isAttemptOver("BUSY"))
        assertNull(h.context.knowledgeSection(null, null))
        assertNull(h.context.skillsSection(null))
        assertNull(h.images.absolute(null, "x"))
    }

    @Test fun `listeners get events in order and stop after close`() {
        val h = host()
        val a = mutableListOf<String>()
        val b = mutableListOf<String>()
        val subA = h.sessions.subscribe { a += (it as BoardSessionEvent.Exit).sessionId }
        h.sessions.subscribe { error("boom") }
        h.sessions.subscribe { b += (it as BoardSessionEvent.Exit).sessionId }
        h.publish(SessionEvent.Exit("s1", 0))
        h.publish(SessionEvent.Exit("s2", 1))
        subA.close()
        subA.close()
        h.publish(SessionEvent.Exit("s3", 0))
        assertEquals(listOf("s1", "s2"), a)
        assertEquals(listOf("s1", "s2", "s3"), b)
    }

    @Test fun `unwired 2b members answer like NoBoardHost`() {
        val h = host()
        assertTrue(h.sessions.providers().isEmpty())
        assertEquals("", h.sessions.providerId())
        assertEquals(false, h.sessions.hasProvider("x"))
        assertNull(h.sessions.providerFor("x"))
        assertNull(h.sessions.defaultModel())
        assertNull(h.sessions.defaultWorkingDir())
        assertEquals(0, h.sessions.workEvents("s"))
        assertNull(h.sessions.requireAllowedRole("dev"))
        assertNull(h.sessions.detail("s"))
        assertTrue(h.sessions.details().isEmpty())
        assertEquals(false, h.sessions.holdsSessionsLock())
        assertEquals(SessionDelivery.EMITTING_THREAD, h.sessions.delivery)
        assertNull(h.agents.instanceFor("a", "r"))
        val i = BoardRoleInstance("a", "r", "p", listOf(BoardTrait("t", 1, "n", "b", "report", "pm", listOf("f"), false)), listOf("s"))
        assertEquals(i, h.agents.withSkills(i, "t1"))
        assertTrue(h.events === h.events)
    }
}
