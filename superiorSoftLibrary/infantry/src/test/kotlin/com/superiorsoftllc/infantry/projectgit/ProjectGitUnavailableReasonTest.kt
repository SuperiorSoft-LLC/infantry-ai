// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.projectgit

import com.superiorsoftllc.infantry.mvp.ProjectStore
import com.superiorsoftllc.infantry.protocol.Envelope
import com.superiorsoftllc.infantry.protocol.Project
import com.superiorsoftllc.infantry.protocol.ProjectSave
import com.superiorsoftllc.infantry.worktree.SystemGitCli
import com.superiorsoftllc.infantry.worktree.testGit
import kotlinx.serialization.json.Json
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * BUG-PGIT-LOAD: the project directory view showed no git information and no reason.
 *
 * Cause: when the daemon cannot START git (not on the daemon's PATH -- `ProcessBuilder.start` throws
 * `IOException: Cannot run program "git"`), the exception escaped [ProjectGitService] and the handler
 * answered a bare `error` frame (`project_git_status failed: ...`) that names no project. The client
 * cannot attach that to a pane, so the pane sat on "not read yet" forever. The service must answer
 * `available = false` WITH the reason instead, like every other read that could not happen.
 *
 * These run the REAL [SystemGitCli] with an executable that does not exist: the same failure the
 * daemon hits when git is not resolvable, not a fake that merely throws.
 */
class ProjectGitUnavailableReasonTest {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val missingGit = SystemGitCli(executable = "infantry-no-such-git-executable")
    private val made = mutableListOf<File>()

    @AfterTest
    fun cleanup() {
        made.forEach { runCatching { it.deleteRecursively() } }
    }

    private fun plainDir(): File = Files.createTempDirectory("infantry-pgit-load-").toFile().also { made += it }

    private fun project(dir: File) = Project(id = "p1", name = "P", directory = dir.absolutePath)

    @Test
    fun gitThatCannotBeStartedIsUnavailableWithAReasonNotAnException() {
        val service = ProjectGitService(missingGit)
        val p = project(plainDir())

        val status = service.status(p)
        assertFalse(status.available, "git could not run: the tree is UNKNOWN")
        assertTrue(status.reason.contains("git could not be run", ignoreCase = true), "status reason: '${status.reason}'")
        assertEquals("p1", status.projectId, "the answer names its project, so the client can put it in the pane")

        val history = service.log(p, 10)
        assertFalse(history.available)
        assertTrue(history.reason.contains("git could not be run", ignoreCase = true), "log reason: '${history.reason}'")

        val branches = service.branches(p)
        assertFalse(branches.available)
        assertTrue(branches.reason.contains("git could not be run", ignoreCase = true), "branches reason: '${branches.reason}'")
    }

    @Test
    fun theWireAnswersAProjectGitFrameNotAnAnonymousError() {
        val store = ProjectStore(idFactory = { "p1" })
        store.save(ProjectSave(name = "P", directory = plainDir().absolutePath))
        val handler = ProjectGitHandler(store, ProjectGitService(missingGit))

        val answer = handler.answer(Envelope(Envelope.PROJECT_GIT_STATUS, "p1"))

        assertEquals(Envelope.PROJECT_GIT, answer?.type, "answer: ${answer?.data}")
        val status = json.decodeFromString(ProjectGitStatus.serializer(), answer!!.data)
        assertEquals("p1", status.projectId)
        assertFalse(status.available)
        assertTrue(status.reason.contains("git could not be run", ignoreCase = true), "reason: '${status.reason}'")
    }

    @Test
    fun notARepositoryCarriesGitsOwnWords() {
        // e.g. "detected dubious ownership" on a directory owned by another account would otherwise read
        // as a bare "not a git repository", hiding the one line the user needs to act on.
        val status = ProjectGitService(testGit()).status(project(plainDir()))

        assertFalse(status.available)
        assertTrue(status.reason.contains("not a git repository", ignoreCase = true), "reason: '${status.reason}'")
        assertTrue(status.reason.contains("fatal:", ignoreCase = true), "git's stderr line is kept: '${status.reason}'")
    }
}
