package com.superiorsoftllc.agenthq.session

import com.superiorsoftllc.agenthq.provider.StubProvider
import com.superiorsoftllc.agenthq.runner.ScriptedRunner
import com.superiorsoftllc.agenthq.runner.StubScript
import java.io.File
import java.util.Collections
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * BUG-M2-11b-1 RED (docs/AGENTHQ-SESSION-HOST-PLAN.md section 11): a one-shot session (the stub, no persistent mode) goes
 * through the same SessionHost as every harness. Two prompts give exactly ONE `* session started` chat line, none of the
 * per-turn plumbing lines in chat (they are in Activity), and no raw `exit` frame, because no hosted process ended unasked.
 * Before the host, every turn printed `starting …` and `process started …` and sent `exit 0`.
 */
class SessionOneBootLineTest {

    private val events: MutableList<SessionEvent> = Collections.synchronizedList(mutableListOf())

    private fun manager(): SessionManager {
        val script = StubScript(stdoutLines = listOf("""{"type":"step_start","sessionID":"prov-sess-1"}""", """{"type":"text","text":"hi"}"""))
        val file = File.createTempFile("agenthq-one-boot", ".json").also { it.delete() }
        return SessionManager(StubProvider(script, delayMs = 0), ScriptedRunner(script, delayMs = 0), JsonSessionStore(file))
            .also { it.eventSink = { e -> events += e } }
    }

    private fun awaitFinished(count: Int) {
        val deadline = System.currentTimeMillis() + 5_000
        while (synchronized(events) { events.count { it is SessionEvent.Exit } } < count) {
            if (System.currentTimeMillis() > deadline) fail("turn never finished: $events")
            Thread.sleep(10)
        }
    }

    private fun chat(): List<String> = synchronized(events) { events.filterIsInstance<SessionEvent.Stderr>().map { it.line } }
    private fun activity(): List<String> = synchronized(events) { events.filterIsInstance<SessionEvent.Activity>().map { it.detail } }

    @Test
    fun twoPromptsOnAOneShotSession_showOneBootLine_noPlumbingInChat_noExitFrame() {
        val manager = manager()
        val first = manager.startNew("hello")
        awaitFinished(1)
        manager.resume(first.id, "again")
        awaitFinished(2)

        val chat = chat()
        assertEquals(listOf("[agenthq] * session started (stub · default)"), chat.filter { it.contains("*") }, "$chat")
        assertTrue(chat.none { it.startsWith("[agenthq] starting ") || it.startsWith("[agenthq] process started") }, "$chat")
        assertTrue(chat.none { it.contains("resuming provider session") || it.contains("Resuming session") }, "$chat")

        val rawExits = synchronized(events) { events.filterIsInstance<SessionEvent.Exit>().filter { it.processExited && it.code != null } }
        assertEquals(emptyList(), rawExits, "a one-shot turn's end is `finished`, never a raw `exit` frame")

        val activity = activity()
        assertEquals(2, activity.count { it.startsWith("starting stub model default in ") }, "$activity")
        assertEquals(2, activity.count { it.startsWith("process started") }, "$activity")
        assertTrue(activity.any { it == "resuming provider session prov-sess-1" }, "$activity")
        assertTrue(activity.any { it.startsWith("Resuming session") }, "$activity")
    }
}
