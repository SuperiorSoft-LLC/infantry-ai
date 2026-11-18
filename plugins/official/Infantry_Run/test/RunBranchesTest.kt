// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.plugins.official.run

import com.superiorsoftllc.infantry.ui.model.CommandSpecView
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** PREVIEW-BRANCH-1: the Run tab's branch-command rules and the `run_branches` / `run_start` shapes. */
class RunBranchesTest {
    @Test fun `a command naming worktree or branch starts on a branch`() {
        assertTrue(RunBranches.isBranchCommand(CommandSpecView("p", "Preview app", cwd = "{worktree}", argv = listOf("gradlew"))))
        assertTrue(RunBranches.isBranchCommand(CommandSpecView("p", "P", argv = listOf("x", "-Pb={branch}"))))
        assertFalse(RunBranches.isBranchCommand(CommandSpecView("b", "Build", cwd = "sub", argv = listOf("{root}/x"))))
    }

    @Test fun `run_branches parses, and anything else is not that answer`() {
        val rows = RunBranches.parse("""{"project":"p1","branches":[{"name":"main"},{"name":"ticket/x","worktree":"C:/w/x"}]}""")!!
        assertEquals(listOf(BranchRow("main"), BranchRow("ticket/x", "C:/w/x")), rows)
        assertNull(RunBranches.parse("""{"nope":1}"""))
        assertNull(RunBranches.parse("not json"))
    }

    @Test fun `the start body names the command, the project and the branch`() {
        assertEquals("""{"commandId":"p","project":"p1","branch":"ticket/x"}""", RunBranches.startBody("p", "p1", "ticket/x"))
        assertEquals("""{"commandId":"p","branch":"main"}""", RunBranches.startBody("p", null, "main"))
    }
}
