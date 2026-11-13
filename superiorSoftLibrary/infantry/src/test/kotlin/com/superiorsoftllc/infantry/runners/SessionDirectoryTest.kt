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
import kotlin.test.assertNull

/**
 * RUNNER-UNIFY-4: the one session directory serves a runner session as the runner's own `session` frame says (its container working
 * dir, harness, state), with only the orchestrator's facts laid over it; off, or before the runner's first record, the mirror's.
 */
class SessionDirectoryTest {
    private val json = Json { encodeDefaults = false }

    private fun runnerRecord(id: String, state: SessionState = SessionState.BUSY) = SessionRecord(
        id = id, provider = "claude", model = "sonnet", workingDir = "/work/repo/.worktrees/t1", repoRoot = "/work/repo",
        worktreePath = "/work/repo/.worktrees/t1", worktreeBranch = "ticket/t1", spawnedAt = 1, lastHeartbeat = 2, state = state,
        providerSessionId = "conv-1", runCount = 3,
    )

    private fun frame(r: SessionRecord) = json.encodeToString(SessionRecordSerializer, r)

    private fun fixture(on: Boolean, store: java.io.File? = null): Pair<SessionDirectory, RemoteSessionMirror> {
        val mirror = RemoteSessionMirror(sessionIds = { listOf("r1") }, clock = { 5 })
        mirror.placed("r1", RemoteSessionMirror.Placed("claude", "sonnet", "C:\\checkout", "ticket/t1", "t1", projectId = "p1", agentId = "a1", role = "backend"))
        val local = SessionRecord(id = "l1", provider = "claude", workingDir = "C:\\checkout", spawnedAt = 1, lastHeartbeat = 1, state = SessionState.IDLE)
        val dir = SessionDirectory(
            local = { listOf(local) }, localGet = { id -> local.takeIf { it.id == id } }, placed = { listOf("r1") },
            legacy = mirror::get, placement = mirror::placement, enabled = { on }, store = store,
        )
        return dir to mirror
    }

    @Test fun `with the flag on a runner session reads the runner's own record, the orchestrator's facts laid over it`() {
        val (dir, _) = fixture(on = true)
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

    @Test fun `off, or before the runner's first record, the mirror's record stands`() {
        val (off, _) = fixture(on = false)
        off.accept("infantry-runner", "r1", frame(runnerRecord("r1")))
        assertEquals("C:\\checkout", off.get("r1")!!.workingDir, "flag off = exactly the old record")
        val (on, _) = fixture(on = true)
        assertEquals(SessionState.STARTING, on.get("r1")!!.state, "no frame yet = the mirror's record")
    }

    @Test fun `a frame for another session or one that does not decode is ignored`() {
        val (dir, _) = fixture(on = true)
        dir.accept("infantry-runner", "r1", frame(runnerRecord("other")))
        dir.accept("infantry-runner", "r1", "not json")
        assertNull(dir.received("r1"))
    }

    @Test fun `an end this daemon recorded wins over a runner that went quiet`() {
        val (dir, mirror) = fixture(on = true)
        dir.accept("infantry-runner", "r1", frame(runnerRecord("r1")))
        mirror.status("r1", SessionState.EXITED.name)
        assertEquals(SessionState.EXITED, dir.get("r1")!!.state)
    }

    @Test fun `received records survive a restart and leave on forget`() {
        val file = Files.createTempDirectory("dir").resolve("runner-directory.json").toFile()
        val (before, _) = fixture(on = true, store = file)
        before.accept("infantry-runner", "r1", frame(runnerRecord("r1")))
        val (after, _) = fixture(on = true, store = file)
        assertEquals("/work/repo/.worktrees/t1", after.get("r1")!!.workingDir)
        after.forget("r1")
        val (again, _) = fixture(on = true, store = file)
        assertNull(again.received("r1"))
    }
}
