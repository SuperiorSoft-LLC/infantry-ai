// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.session.host

import com.superiorsoftllc.infantry.daemon.api.processes.ProcInfo
import com.superiorsoftllc.infantry.session.ProcessGuard
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** DISCONNECT-1 (b): a tree kill never reaches the daemon, its ancestors (the app), the app's runtime, or a process older than the root. */
class TreeKillRuleTest {
    private val rootStart = 1_000_000L

    @Test fun `the daemon and its ancestors are spared`() {
        ProcessGuard.protectedPids.forEach { pid ->
            assertTrue(TreeKillRule.spare(ProcInfo(pid, null, "x", rootStart + 5), rootStart), "pid $pid")
        }
    }

    @Test fun `a java --add-opens command from the app's own runtime is spared`() {
        val home = System.getProperty("java.home")
        assertTrue(TreeKillRule.spare(ProcInfo(999_999_001, null, "\"$home/bin/java.exe\" --add-opens java.base/java.lang=ALL-UNNAMED -jar x.jar", rootStart + 5), rootStart))
    }

    @Test fun `a process that started before the root cannot be its child and is spared`() {
        assertTrue(TreeKillRule.spare(ProcInfo(999_999_002, null, "node server.js", rootStart - 1), rootStart))
    }

    @Test fun `a real child of the root, started after it, is not spared`() {
        assertFalse(TreeKillRule.spare(ProcInfo(999_999_003, null, "gradle worker", rootStart + 10), rootStart))
    }

    @Test fun `killTree with a wrong start instant touches nothing`() {
        // The current JVM is alive; a mismatched start instant must return before any kill (the test would not survive otherwise).
        OsProcessTable.killTree(ProcessHandle.current().pid(), 1L)
        assertTrue(ProcessHandle.current().isAlive)
    }
}
