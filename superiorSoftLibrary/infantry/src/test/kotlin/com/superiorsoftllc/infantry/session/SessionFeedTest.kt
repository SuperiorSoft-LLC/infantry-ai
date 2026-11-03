package com.superiorsoftllc.infantry.session

import com.superiorsoftllc.infantry.mailbox.NudgeGate
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SessionFeedTest {
    private val events = mutableListOf<Pair<String, String>>()
    private var now = 1_000_000L

    init { SessionFeed.clear(); SessionFeed.sink = { n, p -> events += n to p }; SessionFeed.clock = { now } }

    @AfterTest fun reset() = SessionFeed.clear()

    @Test fun `text deltas join into lines and are readable capped`() {
        SessionFeed.text("s1", "hel"); SessionFeed.text("s1", "lo\nwor"); SessionFeed.tool("s1", "Bash: ls"); SessionFeed.error("s1", "boom")
        assertEquals(listOf("hello", "[tool] Bash: ls", "[error] boom", "wor").sorted(), SessionFeed.recent("s1", 10).sorted())
        assertEquals(2, SessionFeed.recent("s1", 2).size)
        repeat(300) { SessionFeed.tool("s2", "t$it") }
        assertEquals(SessionFeed.MAX_LINES, SessionFeed.recent("s2", 10_000).size)
        assertTrue(SessionFeed.recent("none", 5).isEmpty())
    }

    @Test fun `events are named and rate limited per session`() {
        SessionFeed.tool("s1", "a"); SessionFeed.tool("s1", "b")
        assertEquals(listOf("session.tool_call"), events.map { it.first })
        now += 600; SessionFeed.tool("s1", "c")
        SessionFeed.tool("s9", "x")
        assertEquals(3, events.size)
        SessionFeed.error("s1", "bad"); SessionFeed.text("s1", "hi\n")
        assertEquals(listOf("session.tool_call", "session.tool_call", "session.tool_call", "session.error", "session.output"), events.map { it.first })
    }

    @Test fun `a failing sink never breaks the stream`() {
        SessionFeed.sink = { _, _ -> error("down") }
        SessionFeed.tool("s1", "a")
        assertEquals(listOf("[tool] a"), SessionFeed.recent("s1", 5))
    }

    @Test fun `forget drops the ring`() {
        SessionFeed.tool("s1", "a"); SessionFeed.forget("s1")
        assertTrue(SessionFeed.recent("s1", 5).isEmpty())
    }

    @Test fun `a hook can suppress a built-in notice for a session until it expires`() {
        var t = 0L
        val emitted = mutableListOf<String>()
        val gate = NudgeGate({ n, _ -> emitted += n }, { false }, clock = { t })
        assertFalse(gate.handled(NudgeGate.WORKER_IDLE, mapOf("sessionId" to "s1")))
        gate.suppress(NudgeGate.WORKER_IDLE, "s1", 5_000)
        assertTrue(gate.handled(NudgeGate.WORKER_IDLE, mapOf("sessionId" to "s1")))
        assertFalse(gate.handled(NudgeGate.WORKER_IDLE, mapOf("sessionId" to "s2")))
        assertFalse(gate.handled(NudgeGate.BG_DONE, mapOf("sessionId" to "s1")))
        t = 6_000
        assertFalse(gate.handled(NudgeGate.WORKER_IDLE, mapOf("sessionId" to "s1")))
        assertEquals(5, emitted.size) // the event fires every time, suppressed or not
    }
}
