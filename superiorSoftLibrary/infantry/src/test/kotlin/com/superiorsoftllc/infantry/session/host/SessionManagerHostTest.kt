package com.superiorsoftllc.infantry.session.host

import com.superiorsoftllc.infantry.session.JsonSessionStore
import com.superiorsoftllc.infantry.session.SessionEvent
import com.superiorsoftllc.infantry.session.SessionManager
import com.superiorsoftllc.infantry.session.SessionState
import java.io.File
import java.util.Collections
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * BUG-M2-11b-1 acceptance, at runtime through the daemon's [SessionManager]: a harness with a persistent mode
 * (`fake-harness`, not opencode) gets ONE process for two prompts, the close stops it, a crash restarts it once on the
 * next prompt, and the chat shows one boot line per launch. SessionManager names no harness to make this happen.
 */
class SessionManagerHostTest {
    private val adapter = FakeHarnessAdapter(autoComplete = true)
    private val events: MutableList<SessionEvent> = Collections.synchronizedList(mutableListOf())
    private val runner = CountingRunner()

    private val manager = SessionManager(
        FakeHarnessProvider(adapter),
        runner,
        JsonSessionStore(File.createTempFile("infantry-host", ".json").also { it.delete() }),
        killGraceMs = 50,
        lineRunner = runner,
    ).also { it.eventSink = { e -> events += e } }

    private fun chat() = synchronized(events) { events.filterIsInstance<SessionEvent.Stderr>().map { it.line } }
    private fun boots() = chat().filter { it.contains("*") }

    @Test
    fun twoPrompts_oneSpawn_closeStopsIt_crashRestartsOnce_oneBootLinePerLaunch() {
        val session = manager.startNew("one", model = "m1")
        manager.resume(session.id, "two")

        assertEquals(1, adapter.launches.size, "two prompts, one process")
        assertEquals(0, runner.starts.get(), "no one-shot child either")
        assertEquals(listOf("one", "two"), adapter.turns.map { it.prompt })
        assertEquals(listOf("[infantry] * session started (fake-harness · m1)"), boots())
        assertEquals(SessionState.IDLE, manager.get(session.id)!!.state)
        assertTrue(synchronized(events) { events.filterIsInstance<SessionEvent.Exit>().none { it.processExited } }, "no `exit` per turn")

        adapter.current.die(1)
        assertEquals(1, synchronized(events) { events.filterIsInstance<SessionEvent.ProcessExit>().size }, "the crash is one `exit`")
        assertEquals(1, adapter.launches.size, "no restart without a prompt")

        manager.resume(session.id, "three")
        assertEquals(2, adapter.launches.size, "the next prompt restarts it once")
        assertEquals("fake-provider-session", adapter.launches.last().resumeSessionId, "and resumes the provider session")
        assertEquals(
            listOf("[infantry] * session started (fake-harness · m1)", "[infantry] * session restarted after a crash (fake-harness · m1)"),
            boots(),
        )

        val kept = adapter.current
        manager.kill(session.id)
        assertFalse(kept.isAlive, "closing the session stops its process")
        assertEquals(1, adapter.closes)
        assertEquals(2, adapter.launches.size)
    }

    /** BUG-M2-11b-2: moved from OpenCodeServeSessionTest; deleting a session at rest stops the process kept for it. */
    @Test
    fun deletingASessionAtRest_stopsItsKeptProcess() {
        val session = manager.startNew("one")
        val kept = adapter.current
        assertTrue(kept.isAlive, "kept between turns")
        manager.delete(session.id)
        assertFalse(kept.isAlive, "deleting the session stops its process")
        assertEquals(1, adapter.launches.size)
    }

    @Test
    fun daemonShutdown_stopsTheKeptProcess() {
        manager.startNew("one")
        val kept = adapter.current
        assertTrue(kept.isAlive)
        manager.shutdown()
        assertFalse(kept.isAlive)
    }
}
