// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
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
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * RESUME-COPY-1: a daemon on a COPIED state dir (its instance id minted at this start) never resumes, prompts or attaches the
 * sessions the original daemon ran, on its first start or any later one: no harness process is spawned for them. A dir that kept
 * its id still resumes its own older (unowned) records, and a paused runner holds at start.
 */
class ResumeCopyTest {

    private class FakeProvider : AgentProvider {
        override val id = "fake"
        override fun spec(request: RunRequest) = ProcessSpec(listOf("fake", request.resumeSessionId ?: "FRESH"))
        override fun sessionIdFromLine(line: String) = if (line.contains("session_id")) "prov-123" else null
    }

    private class FakeProcess(private val listener: ProcessListener) : RunningProcess {
        @Volatile private var alive = true
        override val pid = 4242L
        override val isAlive: Boolean get() = alive
        override fun writeInput(text: String) = Unit
        override fun closeInput() = Unit
        override fun killTree() { alive = false; listener.onExit(1) }
        override fun awaitExit(): Int = 0
    }

    private class FakeRunner : ProcessRunner {
        override val id = "fake"
        val specs = mutableListOf<ProcessSpec>()
        override fun start(spec: ProcessSpec, listener: ProcessListener): RunningProcess {
            specs += spec
            return FakeProcess(listener).also { listener.onStdout("{\"session_id\":\"prov-123\"}") }
        }
    }

    private fun storeFile(): File = File.createTempFile("infantry-resume-copy", ".json").also { it.delete() }

    private fun daemon(file: File, runner: FakeRunner, id: String?, minted: Boolean) = SessionManager(
        FakeProvider(), runner, JsonSessionStore(file), killGraceMs = 50, pidProbe = { _: Long -> false },
        instanceId = id, instanceMinted = minted,
    )

    private fun rewrite(file: File, change: (SessionRecord) -> SessionRecord) {
        val store = JsonSessionStore(file)
        store.saveAll(store.load().map(change))
    }

    /** The owner's session, mid-turn in the store when it is copied (owner and pid cleared, as the E2E seed did). */
    private fun ownersBusySession(file: File): SessionRecord {
        val s = SessionManager(FakeProvider(), FakeRunner(), JsonSessionStore(file), killGraceMs = 50).startNew("long turn")
        assertEquals(SessionState.BUSY, s.state)
        rewrite(file) { it.copy(ownerId = null, childPid = null) }
        return s
    }

    @Test
    fun copiedDir_neverResumesTheOriginalsSession_onFirstOrLaterStart() {
        val file = storeFile()
        val session = ownersBusySession(file)

        val first = FakeRunner()
        val copy = daemon(file, first, "copy-id", minted = true)
        val report = copy.loadAndReconcile()
        assertTrue(report.cutTurns.isEmpty(), "a copied session is no cut turn of this daemon: $report")
        assertEquals(listOf(session.id), report.copied)
        assertTrue(copy.isCopied(copy.get(session.id)!!))
        assertEquals(COPIED_OWNER, JsonSessionStore(file).load().single().ownerId, "the mark is persisted")
        assertFailsWith<IllegalStateException> { copy.resume(session.id, "Continue where you left off.") }
        assertFailsWith<IllegalStateException> { copy.attach(session.id) }
        assertTrue(first.specs.isEmpty(), "no harness was spawned: ${first.specs}")

        // The copy restarts on the same dir: its id is now its own (not minted), and the mark still holds.
        val second = FakeRunner()
        val again = daemon(file, second, "copy-id", minted = false)
        val report2 = again.loadAndReconcile()
        assertTrue(report2.cutTurns.isEmpty(), report2.toString())
        assertFailsWith<IllegalStateException> { again.resume(session.id, "Continue where you left off.") }
        assertTrue(second.specs.isEmpty(), "no harness after the restart either: ${second.specs}")
        assertEquals(listOf(session.id), again.list().map { it.id }, "the session list is unchanged")
    }

    @Test
    fun sessionOwnedByAnotherInstance_isCopied_evenWhenTheIdWasKept() {
        val file = storeFile()
        val session = ownersBusySession(file)
        rewrite(file) { it.copy(ownerId = "original-id") }
        val runner = FakeRunner()
        val copy = daemon(file, runner, "copy-id", minted = false)
        assertTrue(copy.loadAndReconcile().cutTurns.isEmpty())
        assertFailsWith<IllegalStateException> { copy.resume(session.id, "go") }
        assertTrue(runner.specs.isEmpty())
    }

    @Test
    fun ownDir_withAnOlderUnownedRecord_stillResumesIt() {
        val file = storeFile()
        val session = ownersBusySession(file)
        val runner = FakeRunner()
        val mine = daemon(file, runner, "my-id", minted = false)
        val report = mine.loadAndReconcile()
        assertEquals(listOf(session.id), report.cutTurns)
        assertTrue(report.copied.isEmpty())
        assertFalse(mine.isCopied(mine.get(session.id)!!))
        mine.resume(session.id, "Continue where you left off.")
        assertEquals(listOf("fake", "prov-123"), runner.specs.last().command, "it continues its own conversation")
    }

    @Test
    fun pausedRunner_holdsAtStart_cutTurnsAreNotAnnounced() {
        val report = ReconcileReport(1, 1, 0, cutTurns = listOf("s1"), orphanedIds = listOf("s2"))
        val held = DaemonStartedEvent.announced(report, runnerPaused = true)
        assertTrue(held.cutTurns.isEmpty())
        assertEquals(listOf("s2"), held.orphanedIds, "orphans are only named, never prompted")
        assertEquals(report, DaemonStartedEvent.announced(report, runnerPaused = false))
    }
}
