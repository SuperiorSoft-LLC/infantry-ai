// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.runner

import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * PROC-LEAK-1 (ticket 55312934): a session's Job Object keeps a DETACHED grandchild whose parent already exited, so the
 * process watch can still name (and at the session's end kill, by pid) what a tree walk loses.
 */
class SessionContainersTest {
    private val windows = System.getProperty("os.name").orEmpty().lowercase().contains("win")

    @Test fun `off Windows there is no container and nothing is claimed`() {
        if (windows) return
        val c = SessionContainers.forHost("t")
        assertEquals("none", c.kind)
        assertFalse(c.assign("s", ProcessHandle.current().pid()))
        assertTrue(c.members("s").isEmpty())
    }

    @Test fun `a shell's detached child stays in the session's job after the shell exits, and is gone once killed by pid`() {
        if (!windows) return
        val c = SessionContainers.forHost("test-" + UUID.randomUUID().toString().take(8))
        assertEquals("job object", c.kind)
        val key = "session-" + UUID.randomUUID().toString().take(8)
        // The shell waits ~1 s (so it is in the job before it forks), starts a detached long ping, and exits.
        val shell = ProcessBuilder("cmd", "/c", "ping -n 2 127.0.0.1 >nul & start \"\" /b ping -n 120 127.0.0.1 >nul")
            .redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start()
        var detached: List<ProcessHandle> = emptyList()
        try {
            assertTrue(c.assign(key, shell.pid()), "the shell joins the session's job")
            assertTrue(shell.waitFor(30, TimeUnit.SECONDS), "the shell exits")
            val members = c.members(key)
            detached = members.mapNotNull { ProcessHandle.of(it).orElse(null) }
            val orphan = detached.firstOrNull { h -> h.info().command().orElse("").lowercase().endsWith("ping.exe") }
            assertTrue(orphan != null, "the detached child is still the session's: $members")
            assertFalse(orphan.parent().isPresent && orphan.parent().get().isAlive && orphan.parent().get().pid() == shell.pid(), "its parent is gone")
            assertFalse(ProcessHandle.current().pid() in members, "the daemon itself is never a member")

            // The session ends: its members are killed by pid (the process watch does this through its ledger).
            detached.forEach { it.destroyForcibly() }
            detached.forEach { it.onExit().get(10, TimeUnit.SECONDS) }
            assertTrue(detached.none { it.isAlive }, "the detached child is gone")
            assertTrue(c.members(key).isEmpty(), "an empty job lets go of its handle")
        } finally {
            detached.forEach { runCatching { it.destroyForcibly() } }
            shell.destroyForcibly()
            c.release(key)
        }
    }
}
