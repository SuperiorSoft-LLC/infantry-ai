package com.superiorsoftllc.infantry.session

import com.superiorsoftllc.infantry.harness.AgentProvider
import com.superiorsoftllc.infantry.harness.RunRequest
import com.superiorsoftllc.infantry.runner.ProcessListener
import com.superiorsoftllc.infantry.runner.ProcessRunner
import com.superiorsoftllc.infantry.runner.ProcessSpec
import com.superiorsoftllc.infantry.runner.RunningProcess
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Links 3-4 of d52bbf40: the session-store replacement window propagates to the SessionManager
 * NOTE: line numbers below are pinned to SessionManager.kt; they move when code is inserted above them (interrupt slice moved them: 373->376, 589->643, 642->696; BRANCH-PER-TASK 2 moved them again: 376->385, 643->652, 696->705; WIRE-4 +1: 385->386, 652->653, 705->706; CP-4e spawn box moved resume 386->392, kill 653->692, delete 706->745).
 * symptom -- "unknown session <id>" thrown by [SessionManager.resume] at :392.
 *
 * WHY THIS FILE EXISTS (a NEW file — nothing here touches SessionProcessIntegrationTest.kt or
 * SessionLifecycleTest.kt, so fc7942c8 is free to edit its line 83):
 *
 * The store-level tests ([JsonSessionStoreTest]) prove the window exists and the FIXED [load]
 * recovers from `.tmp`. But they assert on `load()`'s return value, never on `resume()`. The
 * lifecycle test ([SessionLifecycleTest.restart_whenThePreviousWriteWasInterrupted_stillResumesTheSession])
 * DOES call `resume()`, but it asserts `report.exited == 1` first — which catches the failure at
 * the store level BEFORE `resume()` is reached. That assertion is why the chain stopped short of
 * the symptom: on the pre-fix store the test fails at `report.exited`, not at `resume():392`.
 *
 * The two tests below go straight to `resume()`:
 *
 *  - [resume_isNotUnknownSessionWhenLoadAndReconcileRecoversTheTmpReplacement] is DETERMINISTIC:
 *    the window is constructed by renaming the committed file to `.tmp` (no timing dependency).
 *    On the FIXED store, `load()` recovers the session from `.tmp`; `loadAndReconcile()` puts it
 *    in the map, and `resume():392` finds it. On the PRE-FIX store (no `.tmp` recovery in `load()`),
 *    `load()` returns emptyList(), the session is absent, and `resume()` throws
 *    `IllegalStateException("unknown session <id>")` from `:392`.
 *
 *  - [resume_throwsUnknownSession_onlyFromResumeFrame] is a constant, isolated proof that the
 *    throw site is `resume():392` — NOT `kill():692` (same string, same exception type) or
 *    `delete():745` (different type, single-quoted). It asserts on the stack frame, not the string.
 */
class SessionStoreWindowResumeTest {

    // ----- fakes (mirror SessionLifecycleTest's fakes, kept private so no cross-test coupling) -----

    private class WindowProvider : AgentProvider {
        override val id = "fake"
        override fun spec(request: RunRequest) =
            ProcessSpec(listOf("fake", request.resumeSessionId ?: "FRESH"))

        override fun sessionIdFromLine(line: String): String? =
            if (line.contains("session_id")) "prov-123" else null
    }

    private class WindowProcess(private val listener: ProcessListener) : RunningProcess {
        @Volatile private var alive = true
        override val pid = 4242L
        override val isAlive: Boolean get() = alive
        override fun writeInput(text: String) = Unit
        override fun closeInput() = Unit
        override fun killTree() {
            alive = false
            listener.onExit(1)
        }
        override fun awaitExit(): Int = 0
    }

    private class WindowRunner : ProcessRunner {
        override val id = "fake"
        val specs = mutableListOf<ProcessSpec>()
        override fun start(spec: ProcessSpec, listener: ProcessListener): RunningProcess {
            specs += spec
            val p = WindowProcess(listener)
            listener.onStdout("{\"session_id\":\"prov-123\"}")
            return p
        }
    }

    private fun tempStore(): Pair<JsonSessionStore, File> {
        val file = File.createTempFile("infantry-sessions", ".json")
        file.delete()
        return JsonSessionStore(file) to file
    }

    // ----- tests -----

    /**
     * DET deterministic reproduction of links 3-4.
     *
     * The committed snapshot is renamed to `.tmp` (the exact on-disk state a non-atomic
     * `Files.move(REPLACE_EXISTING)` leaves behind on Windows: DeleteFile then CopyFileEx).
     * `sessions.json` does not exist; `.tmp` holds the complete snapshot with `providerSessionId`.
     *
     * On the FIXED store, `load()` checks `.tmp` and recovers the session; `loadAndReconcile()`
     * puts it in the in-memory map, and `resume()` at :392 finds it. On the PRE-FIX store,
     * `load()` returns `emptyList()` and `resume()` throws `IllegalStateException("unknown session")`
     * from :392.
     *
     * This test PASSES on the fix and FAILS without it — the mutation gate for d52bbf40.
     *
     * NOTE: deliberately NO assertion on `report.exited`. The existing lifecycle test stops there
     * and never reaches `resume():392`. This one does.
     */
    @Test
    fun resume_isNotUnknownSessionWhenLoadAndReconcileRecoversTheTmpReplacement() {
        // 1) First manager: create a session with a provider session id, persisted to disk.
        val (_, file) = tempStore()
        val managerA = SessionManager(WindowProvider(), WindowRunner(), JsonSessionStore(file), killGraceMs = 50)
        val session = managerA.startNew("hello")
        val sessionId = session.id

        // Precondition: the provider session id reached disk before the window is simulated.
        assert(file.readText().contains("prov-123")) {
            "provider session id must be persisted before the window is simulated"
        }

        // 2) Simulate the interrupted replacement: the store file is gone, its complete replacement
        //    lives in `.tmp` — exactly the state a non-atomic move leaves behind on Windows.
        val tmp = File(file.parentFile, file.name + ".tmp")
        assertTrue(file.renameTo(tmp), "the store file must exist to be moved aside")

        // Prove the window state was actually set up (non-vacuous: if the file is still there,
        // loadAndReconcile would read it directly and the test would prove nothing about .tmp).
        assertFalse(file.isFile, "the store file must be ABSENT during the simulated window")
        assertTrue(tmp.isFile, "the replacement must be present in .tmp")

        // 3) New daemon over the same store path. NO assertion on the reconcile report — the
        //    existing tests stop there; this test goes straight to the symptom.
        val managerB = SessionManager(
            WindowProvider(), WindowRunner(), JsonSessionStore(file),
            killGraceMs = 50, pidProbe = { _: Long -> false },
        )
        // loadAndReconcile calls store.load() ONCE (:200). On the fixed store it recovers .tmp;
        // on the pre-fix store it returns emptyList() and the map stays empty.
        managerB.loadAndReconcile()

        // 4) resume() reads the in-memory map ONLY (:392). If loadAndReconcile() missed the store
        //    (load() returned empty), the session is absent and the throw is "unknown session"
        //    from resume() at :392 — NOT from kill() at :692, which produces the same string.
        val resumed = managerB.resume(sessionId, "")
        assertEquals(
            1,
            resumed.resumeCount,
            "the session must have been recovered from .tmp to be resumable — if load() missed " +
                "the window, resume() throws 'unknown session' from :392 before reaching this line",
        )
        assertEquals(
            "prov-123",
            managerB.get(sessionId)!!.providerSessionId,
            "the provider session id must survive the .tmp recovery",
        )
    }

    /**
     * Constant, isolated proof of the throw site. When [loadAndReconcile] populates an empty map
     * (the window), `resume()` throws from :392 — not from `kill()` at :692 or `delete()` at :745,
     * which throw the same "unknown session" string but at different lines and (for delete) a
     * different exception type.
     *
     * Asserts on the stack frame, not the message — per the PM's requirement. This test always
     * throws; it documents that the three throw sites must never be confused.
     */
    @Test
    fun resume_throwsUnknownSession_onlyFromResumeFrame() {
        // A store that always returns empty — a deterministic stand-in for the window where
        // a reader lands with zero sessions (the pre-fix load() behavior when the file is absent).
        val emptyStore = object : SessionStore {
            override fun load(): List<SessionRecord> = emptyList()
            override fun saveAll(sessions: Collection<SessionRecord>) {}
        }
        val manager = SessionManager(WindowProvider(), WindowRunner(), emptyStore, killGraceMs = 50)
        // loadAndReconcile populates the in-memory map from store.load() — which is empty here.
        manager.loadAndReconcile()

        // The session is absent from the map. resume() must throw "unknown session" from :392.
        val error = assertFailsWith<IllegalStateException> {
            manager.resume("absent-session", "")
        }

        // The message must be the symptom string.
        assertTrue(
            error.message?.contains("unknown session") == true,
            "expected the 'unknown session' symptom, got: ${error.message}",
        )

        // The throw must come from resume() at :392, NOT kill() at :692 (same string, same type).
        val resumeFrame = error.stackTrace.find { frame ->
            frame.className.contains("SessionManager") && frame.methodName == "resume"
        }
        assertNotNull(
            resumeFrame,
            "the throw must originate from SessionManager.resume(), not kill() at :692 or " +
                "delete() at :745 (kill throws the same string). Stack:\n" +
                error.stackTrace.take(8).joinToString("\n")
        )
        // TICKET-HANDOVER split resume() into the hold check and resumeHeld(), its body: the `unknown session` error
        // now lives in resumeHeld(), reached only through resume() here. The THROW frame (the innermost SessionManager
        // frame) must be that error, so kill() / delete() (same string) can still never be mistaken for it.
        val throwFrame = error.stackTrace.first { it.className.contains("SessionManager") }
        assertEquals(
            "resumeHeld",
            throwFrame.methodName,
            "the throw must be resume()'s body, resumeHeld(), not kill() or delete(). Stack:\n" +
                error.stackTrace.take(8).joinToString("\n"),
        )
        // The line is read from the source (resumeHeld()'s own `unknown session` error), not hard-coded: a pinned number
        // broke on every unrelated edit above resume() (TRAITS-T4 added the instance chokepoint there).
        val source = java.io.File("../infantryCore/src/main/kotlin/com/superiorsoftllc/infantry/session/SessionManager.kt").readLines()
        val resumeStart = source.indexOfFirst { it.contains("fun resumeHeld(sessionId: String") }
        val resumeThrowLine = source.drop(resumeStart).indexOfFirst { it.contains("error(\"unknown session") } + resumeStart + 1
        assertTrue(resumeStart >= 0 && resumeThrowLine > resumeStart, "resumeHeld()'s unknown-session line not found in the source")
        assertEquals(
            resumeThrowLine,
            throwFrame.lineNumber,
            "the throw must be from resume():$resumeThrowLine specifically, not kill(). Stack:\n" +
                error.stackTrace.take(8).joinToString("\n")
        )
    }
}
