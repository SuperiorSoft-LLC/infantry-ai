// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.runners

import com.superiorsoftllc.infantry.session.SessionState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * RUNNER-UNIFY-6: the placement index replaces the mirror and keeps its file. "Sessions are never lost": a `runner-sessions.json`
 * written by a daemon from before RUNNER-UNIFY-6 loads with every session, its runner, its start pins, its last state and its stream
 * resume point, and each one is listed by the session directory again.
 */
class RunnerPlacementsTest {
    /** A file exactly as the pre-RUNNER-UNIFY-6 mirror wrote it (encodeDefaults = false, every field it ever had). */
    private val legacyFile = """
        [{"sessionId":"s-ticket","runner":"infantry-runner","provider":"claude","model":"sonnet","repoRoot":"C:\\work\\repo",
          "branch":"ticket/t1-x","ticketId":"t1","runnerWorktree":"/work/clones/repo/.worktrees/t1","projectId":"p1","agentId":"a1",
          "role":"fullstack","state":"IDLE","firstSeen":1700000000000,"taken":42},
         {"sessionId":"s-spawn","runner":"infantry-runner-test","provider":"opencode","projectId":"p1","role":"backend",
          "state":"FAILED","failedWhy":"not signed in","taken":7,"briefOwed":true},
         {"sessionId":"s-bare","runner":"infantry-runner"}]
    """.trimIndent()

    @Test
    fun `a pre-unify runner-sessions file loads with every session and the directory lists each one`() {
        val file = kotlin.io.path.createTempDirectory("placements").toFile().resolve("runner-sessions.json").also { it.writeText(legacyFile) }
        val placements = RunnerPlacements(store = file)
        val index = placements.restore()
        assertEquals(mapOf("s-ticket" to "infantry-runner", "s-spawn" to "infantry-runner-test", "s-bare" to "infantry-runner"), index)
        assertEquals(42L, placements.taken("s-ticket"), "the stream resumes where it stopped: no frame twice, none skipped")
        assertEquals(7L, placements.taken("s-spawn"))
        assertTrue(placements.owesBrief("s-spawn"))

        val dir = SessionDirectory(local = { emptyList() }, localGet = { null }, placed = { index.keys.sorted() }, placements = placements)
        assertEquals(listOf("s-bare", "s-spawn", "s-ticket"), dir.list().map { it.id }, "every session is listed again")
        val t = dir.get("s-ticket")!!
        assertEquals("p1", t.projectId); assertEquals("t1", t.ticketId); assertEquals("ticket/t1-x", t.worktreeBranch)
        assertEquals(SessionState.IDLE, t.state); assertEquals(1700000000000, t.spawnedAt)
        assertEquals("/work/clones/repo/.worktrees/t1", t.workingDir, "the runner's worktree, not the orchestrator checkout")
        val f = dir.get("s-spawn")!!
        assertEquals(SessionState.FAILED, f.state); assertEquals("not signed in", f.lastError)
        assertEquals(SessionState.STARTING, dir.get("s-bare")!!.state)

        // Written back in the same format: a second restart reads the same index.
        placements.taken("s-ticket", 43L)
        val again = RunnerPlacements(store = file)
        assertEquals(index, again.restore())
        assertEquals(43L, again.taken("s-ticket"))
    }

    @Test
    fun `a file that cannot be read is kept aside, never overwritten by an empty index`() {
        val dir = kotlin.io.path.createTempDirectory("placements-bad").toFile()
        val file = dir.resolve("runner-sessions.json").also { it.writeText("{ not json") }
        val logs = mutableListOf<String>()
        val placements = RunnerPlacements(store = file, placedOn = { "r" })
        assertEquals(emptyMap(), placements.restore { logs += it })
        assertEquals("{ not json", dir.resolve("runner-sessions.json.unreadable").readText())
        assertTrue(logs.single().contains("unreadable"), logs.toString())
    }

    @Test
    fun `a restart keeps each placed session, its runner, state and stream position and forget drops it`() {
        val file = kotlin.io.path.createTempDirectory("rop1").toFile().resolve("runner-sessions.json")
        val before = RunnerPlacements(store = file, placedOn = { "infantry-runner" })
        before.placed("s1", RunnerPlacements.Placed("opencode", "m", null, null, projectId = "p1"))
        before.status("s1", "IDLE")
        before.taken("s1", 3L)
        val after = RunnerPlacements(store = file)
        assertEquals(mapOf("s1" to "infantry-runner"), after.restore())
        assertEquals(3L, after.taken("s1"))
        assertEquals("p1", after.placement("s1")?.projectId)
        assertEquals(SessionState.IDLE, after.known("s1").state)
        after.forget("s1")
        assertEquals(emptyMap(), RunnerPlacements(store = file).restore())
    }

    @Test
    fun `a state for a session that is not placed here is ignored`() {
        val placements = RunnerPlacements(placedOn = { null })
        placements.status("local-1", SessionState.EXITED.name)
        assertEquals(null, placements.known("local-1").state)
    }
}
