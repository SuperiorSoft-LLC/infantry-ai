// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.runners

import com.superiorsoftllc.infantry.session.SessionRecord
import com.superiorsoftllc.infantry.session.SessionRecordSerializer
import com.superiorsoftllc.infantry.session.SessionState
import kotlinx.serialization.json.Json
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * RUNNER-UNIFY-4/6: the one session directory serves a runner session as the runner's own `session` frame says (its container working
 * dir, harness, state), with only the orchestrator's facts laid over it; before the runner's first record it is still listed, from
 * the placement index alone (never lost, never the orchestrator's checkout as its working dir).
 */
class SessionDirectoryTest {
    private val json = Json { encodeDefaults = false }

    private fun runnerRecord(id: String, state: SessionState = SessionState.BUSY) = SessionRecord(
        id = id, provider = "claude", model = "sonnet", workingDir = "/work/repo/.worktrees/t1", repoRoot = "/work/repo",
        worktreePath = "/work/repo/.worktrees/t1", worktreeBranch = "ticket/t1", spawnedAt = 1, lastHeartbeat = 2, state = state,
        providerSessionId = "conv-1", runCount = 3,
    )

    private fun frame(r: SessionRecord) = json.encodeToString(SessionRecordSerializer, r)

    private fun fixture(store: java.io.File? = null): Pair<SessionDirectory, RunnerPlacements> {
        val placements = RunnerPlacements(clock = { 5 }, placedOn = { "infantry-runner" })
        placements.placed("r1", RunnerPlacements.Placed("claude", "sonnet", "C:\\checkout", "ticket/t1", "t1", projectId = "p1", agentId = "a1", role = "backend"))
        val local = SessionRecord(id = "l1", provider = "claude", workingDir = "C:\\checkout", spawnedAt = 1, lastHeartbeat = 1, state = SessionState.IDLE)
        val dir = SessionDirectory(
            local = { listOf(local) }, localGet = { id -> local.takeIf { it.id == id } }, placed = { listOf("r1") },
            placements = placements, store = store,
        )
        return dir to placements
    }

    @Test fun `a runner session reads the runner's own record, the orchestrator's facts laid over it`() {
        val (dir, _) = fixture()
        dir.accept("infantry-runner", "r1", frame(runnerRecord("r1")))
        val r = dir.get("r1")!!
        assertEquals("/work/repo/.worktrees/t1", r.workingDir, "the container path, never the orchestrator's checkout")
        assertEquals("claude", r.provider)
        assertEquals(SessionState.BUSY, r.state)
        assertEquals(3, r.runCount, "the runner's real run count, not a synthesised 1")
        assertEquals("p1", r.projectId); assertEquals("t1", r.ticketId); assertEquals("backend", r.role)
        assertEquals("C:\\checkout", r.repoRoot, "the board row reads the commit tip in the orchestrator checkout")
        assertEquals(listOf("l1", "r1"), dir.list().map { it.id }, "one list: local first, then the runner's")
        assertEquals("C:\\checkout", dir.get("l1")!!.workingDir, "local sessions are unchanged")
    }

    @Test fun `before the runner's first record the session is listed from its placement, never with the orchestrator's checkout`() {
        val (dir, _) = fixture()
        val r = dir.get("r1")!!
        assertEquals(SessionState.STARTING, r.state)
        assertEquals("p1", r.projectId); assertEquals("backend", r.role); assertEquals("claude", r.provider)
        assertEquals("", r.workingDir, "no runner worktree reported yet: unknown, not C:\\checkout")
        assertEquals(listOf("l1", "r1"), dir.list().map { it.id })
    }

    @Test fun `a frame for another session or one that does not decode is ignored`() {
        val (dir, _) = fixture()
        dir.accept("infantry-runner", "r1", frame(runnerRecord("other")))
        dir.accept("infantry-runner", "r1", "not json")
        assertNull(dir.received("r1"))
    }

    @Test fun `an end this daemon recorded wins over a runner that went quiet`() {
        val (dir, placements) = fixture()
        dir.accept("infantry-runner", "r1", frame(runnerRecord("r1")))
        placements.status("r1", SessionState.EXITED.name)
        assertEquals(SessionState.EXITED, dir.get("r1")!!.state)
    }

    @Test fun `received records survive a restart and leave on forget`() {
        val file = Files.createTempDirectory("dir").resolve("runner-directory.json").toFile()
        val (before, _) = fixture(store = file)
        before.accept("infantry-runner", "r1", frame(runnerRecord("r1")))
        val (after, _) = fixture(store = file)
        assertEquals("/work/repo/.worktrees/t1", after.get("r1")!!.workingDir)
        after.forget("r1")
        val (again, _) = fixture(store = file)
        assertNull(again.received("r1"))
    }

    /**
     * RUNNER-ROLE-1: a runner spawn (bound at rest, no first turn) reads never-run until its first turn with the role brief is taken,
     * whether the directory serves its placement (before the runner's first frame) or the runner's own record; then never again, even
     * while the runner's record still says never-run. The owed flag survives a daemon restart; a ticket start never owes one.
     */
    @Test fun `a runner spawn reads never-run until its first turn with the brief is taken, then never again`() {
        val file = Files.createTempDirectory("dir-brief").resolve("runner-sessions.json").toFile()
        val placements = RunnerPlacements(clock = { 5 }, store = file, placedOn = { "infantry-runner-test" })
        placements.placed("r1", RunnerPlacements.Placed("opencode", null, null, null, projectId = "p1", role = "fullstack", briefOwed = true))
        placements.placed("t1", RunnerPlacements.Placed("opencode", null, null, "ticket/t1", "t1", role = "fullstack"))
        val dir = SessionDirectory(local = { emptyList() }, localGet = { null }, placed = { listOf("r1", "t1") }, placements = placements)
        assertTrue(dir.get("r1")!!.neverRun, "before the runner's first frame: never-run")
        assertEquals("fullstack", dir.get("r1")!!.role)
        assertFalse(dir.get("t1")!!.neverRun, "a ticket start's prompt already carried the brief")
        val atRest = SessionRecord(id = "r1", provider = "opencode", workingDir = "/work", spawnedAt = 1, lastHeartbeat = 1, state = SessionState.IDLE)
        dir.accept("infantry-runner-test", "r1", frame(atRest))
        assertTrue(dir.get("r1")!!.neverRun, "the runner's own at-rest record: never-run")

        val restarted = RunnerPlacements(clock = { 5 }, store = file)
        restarted.restore()
        assertTrue(restarted.owesBrief("r1"), "the owed brief survives a daemon restart")

        dir.briefed("r1")
        assertFalse(placements.owesBrief("r1"))
        assertFalse(dir.get("r1")!!.neverRun, "the runner's record not yet refreshed never briefs it twice")
        val reread = RunnerPlacements(clock = { 5 }, store = file)
        reread.restore()
        assertFalse(reread.owesBrief("r1"), "briefed is kept too")
    }
}
