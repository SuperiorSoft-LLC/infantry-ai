// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.plugins.official.processes.daemon

import com.superiorsoftllc.infantry.daemon.api.processes.WatchedSession
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * PROC-REAP-COPY-1 (ticket a153c7bc): a daemon started on a COPY of another daemon's state dir reaped the real processes its
 * copied `sessions.json` named (an ORPHANED session's childPid: a worker's MCP bridge and two helpers). The host now hands the
 * watch such a session with no roots and `owned = false` (core's SessionRecord.ownedRootPids); the watch must then track,
 * adopt and reap nothing of it. Core's side of the rule is covered by OwnedRootPidsTest in the infantry module.
 */
class ProcessReapCopyTest {
    private val now = 10_000_000L
    private val os = FakeOs()
    private val bridge = 700_001L
    private val helperA = 700_002L
    private val helperB = 700_003L
    private val gradle = 700_010L
    private val worktree = "C:/work/.worktrees/ticket-x"

    private fun realWorkerTree() {
        os.start(bridge, null, "node mcp-bridge.js", 1_000)
        os.start(helperA, bridge, "node helper-a.js", 1_100)
        os.start(helperB, bridge, "node helper-b.js", 1_200)
    }

    private fun orphaned(roots: List<Long>, owned: Boolean) =
        WatchedSession("0fefdaaa", "ORPHANED", false, false, now, roots, worktree, owned = owned)

    private fun watch() = ProcessWatch(ProcessLedger(os, clock = { now }), os, { ProcessWatchConfig(stopGraceSeconds = 0) }, { now }, log = {})

    @Test fun `before the fix - an orphaned copied session rooted at its recorded pid gets the real tree reaped`() {
        realWorkerTree()
        os.sessionList = { listOf(orphaned(listOf(bridge), owned = true)) } // what the old host mapping handed over
        val w = watch()
        w.tick()
        w.reapEnded("daemon start")
        assertEquals(setOf(bridge, helperA, helperB), os.killed.toSet(), "the bug: the copy reaps what its records name")
    }

    @Test fun `a copied orphaned session - nothing tracked, nothing killed`() {
        realWorkerTree()
        os.sessionList = { listOf(orphaned(emptyList(), owned = false)) }
        val w = watch()
        w.tick()
        assertTrue(w.reapEnded("daemon start").isEmpty())
        assertTrue(os.killed.isEmpty(), "killed: ${os.killed}")
        assertTrue(w.report().tracked.isEmpty(), "nothing of the copy's sessions is recorded")
        assertTrue(os.procs.keys.containsAll(listOf(bridge, helperA, helperB)), "all three survive")
    }

    @Test fun `a copied session's worktree adopts no orphan build daemon`() {
        os.start(gradle, null, "java org.gradle.launcher.daemon.bootstrap.GradleDaemon $worktree/build", 2_000)
        os.sessionList = { listOf(orphaned(emptyList(), owned = false)) }
        val w = watch()
        w.tick()
        w.reapEnded("daemon start")
        assertTrue(w.report().tracked.none { it.pid == gradle }, "not adopted")
        assertTrue(gradle in os.procs, "the real build daemon survives")
    }

    @Test fun `the same orphan build daemon IS adopted for a session this daemon ran`() {
        os.start(gradle, null, "java org.gradle.launcher.daemon.bootstrap.GradleDaemon $worktree/build", 2_000)
        os.sessionList = { listOf(orphaned(emptyList(), owned = true)) }
        val w = watch()
        w.tick()
        assertTrue(w.report().tracked.any { it.pid == gradle }, "adopted by worktree path, as before")
    }
}
