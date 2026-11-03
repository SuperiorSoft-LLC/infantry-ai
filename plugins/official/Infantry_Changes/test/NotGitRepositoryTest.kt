// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.plugins.official.changes

import com.superiorsoftllc.infantry.ui.protocol.ProjectGitWorktreeList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** S4b: moved from :infantryUiCore's NoStuckThinkingAndNotGitTest with [isNotGitRepository] (Infantry_Changes). */
class NotGitRepositoryTest {
    @Test fun notAGitRepositoryIsRecognised() {
        val l = ProjectGitWorktreeList("p", available = false, reason = "the project directory is not a git repository: x")
        assertTrue(isNotGitRepository(l))
        assertFalse(isNotGitRepository(ProjectGitWorktreeList("p", available = false, reason = "git could not be run")))
        assertFalse(isNotGitRepository(null))
        assertEquals(false, isNotGitRepository(ProjectGitWorktreeList("p", available = true)))
    }
}
