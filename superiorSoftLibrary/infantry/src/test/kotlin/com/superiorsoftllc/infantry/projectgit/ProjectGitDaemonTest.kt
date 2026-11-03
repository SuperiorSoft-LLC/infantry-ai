// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.projectgit

import com.superiorsoftllc.infantry.daemon.DaemonFixture
import com.superiorsoftllc.infantry.protocol.Envelope
import com.superiorsoftllc.infantry.protocol.Project
import com.superiorsoftllc.infantry.protocol.ProjectSave
import com.superiorsoftllc.infantry.worktree.ChangeKind
import com.superiorsoftllc.infantry.worktree.newGitRepo
import com.superiorsoftllc.infantry.worktree.testGit
import kotlinx.serialization.json.Json
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * CP-2 slice (b) on the WIRE: a project saved with a `directory` is read back through the three
 * `project_git_*` frames by the REAL daemon, over a real WebSocket, against a real temp git repo.
 */
class ProjectGitDaemonTest {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val timeoutMs = 6_000L

    private fun withDaemon(block: (DaemonFixture, File) -> Unit) {
        val repo = newGitRepo("infantry-cp2-daemon")
        val fixture = DaemonFixture.launch()
        try {
            fixture.client.await(Envelope.READY)
            block(fixture, repo)
        } finally {
            fixture.stop()
            fixture.deleteState()
            runCatching { repo.deleteRecursively() }
        }
    }

    private fun DaemonFixture.createProject(directory: String): Project {
        send(Envelope.PROJECT_SAVE, json.encodeToString(ProjectSave.serializer(), ProjectSave(name = "Git project", directory = directory)))
        val frame = client.awaitWhere("project frame", timeoutMs) { it.type == Envelope.PROJECT && it.data.contains("Git project") }
        return json.decodeFromString(Project.serializer(), frame.data)
    }

    @Test
    fun statusDiffAndLogAnswerForTheProjectsDirectory() = withDaemon { fixture, repo ->
        File(repo, "README.md").writeText("hello\nedited by the daemon test\n")
        File(repo, "fresh.txt").writeText("fresh\n")
        val project = fixture.createProject(repo.absolutePath)

        fixture.send(Envelope.PROJECT_GIT_STATUS, project.id)
        val statusFrame = fixture.client.awaitWhere("project_git answer", timeoutMs) { it.type == Envelope.PROJECT_GIT }
        val status = json.decodeFromString(ProjectGitStatus.serializer(), statusFrame.data)
        assertTrue(status.available, status.reason)
        assertEquals(project.id, status.projectId)
        assertEquals(
            setOf("README.md" to ChangeKind.MODIFIED, "fresh.txt" to ChangeKind.UNTRACKED),
            status.changes.map { it.path to it.status }.toSet(),
        )

        fixture.send(
            Envelope.PROJECT_GIT_DIFF,
            json.encodeToString(ProjectGitDiffRequest.serializer(), ProjectGitDiffRequest(project.id, "README.md")),
        )
        val patchFrame = fixture.client.awaitWhere("project_git_patch answer", timeoutMs) { it.type == Envelope.PROJECT_GIT_PATCH }
        val patch = json.decodeFromString(ProjectGitPatch.serializer(), patchFrame.data)
        assertTrue(patch.available, patch.reason)
        assertTrue(patch.patch.contains("+edited by the daemon test"), patch.patch)

        fixture.send(
            Envelope.PROJECT_GIT_LOG,
            json.encodeToString(ProjectGitLogRequest.serializer(), ProjectGitLogRequest(project.id, limit = 5)),
        )
        val historyFrame = fixture.client.awaitWhere("project_git_history answer", timeoutMs) { it.type == Envelope.PROJECT_GIT_HISTORY }
        val history = json.decodeFromString(ProjectGitHistory.serializer(), historyFrame.data)
        assertEquals(listOf("init"), history.commits.map { it.subject })
    }

    @Test
    fun aProjectWithNoDirectoryAnswersUnavailableNotClean() = withDaemon { fixture, _ ->
        fixture.send(Envelope.PROJECT_SAVE, """{"name":"No dir"}""")
        val created = fixture.client.awaitWhere("project frame", timeoutMs) { it.type == Envelope.PROJECT && it.data.contains("No dir") }
        val project = json.decodeFromString(Project.serializer(), created.data)

        fixture.send(Envelope.PROJECT_GIT_STATUS, project.id)
        val frame = fixture.client.awaitWhere("project_git answer", timeoutMs) { it.type == Envelope.PROJECT_GIT }
        val status = json.decodeFromString(ProjectGitStatus.serializer(), frame.data)

        assertEquals(false, status.available)
        assertTrue(status.reason.contains("no directory", ignoreCase = true), status.reason)
    }

    @Test
    fun anUnknownProjectAndAnEscapingPathAreRefused() = withDaemon { fixture, repo ->
        fixture.send(Envelope.PROJECT_GIT_STATUS, "project-does-not-exist")
        val unknown = fixture.client.awaitWhere("status refusal", timeoutMs) {
            it.type == Envelope.ERROR && it.data.startsWith("project_git_status refused")
        }
        assertTrue(unknown.data.contains("no project"), unknown.data)

        val project = fixture.createProject(repo.absolutePath)
        fixture.send(
            Envelope.PROJECT_GIT_DIFF,
            json.encodeToString(ProjectGitDiffRequest.serializer(), ProjectGitDiffRequest(project.id, "../secret.txt")),
        )
        fixture.client.awaitWhere("diff refusal", timeoutMs) {
            it.type == Envelope.ERROR && it.data.startsWith("project_git_diff refused")
        }
    }

    /** BRANCH-VIEW-1 (contract 1.65.0): the three branch frames over the real wire. */
    @Test
    fun branchListChangesAndDiffAnswerOverTheWire() = withDaemon { fixture, repo ->
        val git = com.superiorsoftllc.infantry.worktree.testGit()
        fun g(vararg a: String) = assertTrue(git.run(repo, a.toList()).ok, a.joinToString(" "))
        g("branch", "-M", "main"); g("checkout", "-q", "-b", "feature")
        File(repo, "feature.txt").writeText("feature line\n"); g("add", "-A"); g("commit", "-q", "-m", "feature")
        g("checkout", "-q", "main")
        File(repo, "dirty.txt").writeText("working tree only\n")
        val project = fixture.createProject(repo.absolutePath)

        fixture.send(Envelope.PROJECT_GIT_BRANCHES, json.encodeToString(ProjectGitBranchesRequest.serializer(), ProjectGitBranchesRequest(project.id)))
        val listFrame = fixture.client.awaitWhere("branch list", timeoutMs) { it.type == Envelope.PROJECT_GIT_BRANCH_LIST }
        val list = json.decodeFromString(ProjectGitBranchList.serializer(), listFrame.data)
        assertTrue(list.available, list.reason)
        assertEquals("main", list.current)
        assertEquals(setOf("main", "feature"), list.branches.map { it.name }.toSet())

        fixture.send(
            Envelope.PROJECT_GIT_BRANCH_CHANGES,
            json.encodeToString(ProjectGitBranchChangesRequest.serializer(), ProjectGitBranchChangesRequest(project.id, "feature", "main")),
        )
        val statFrame = fixture.client.awaitWhere("branch diffstat", timeoutMs) { it.type == Envelope.PROJECT_GIT_BRANCH_DIFFSTAT }
        val stat = json.decodeFromString(ProjectGitBranchDiffstat.serializer(), statFrame.data)
        assertTrue(stat.available, stat.reason)
        assertEquals(listOf("feature.txt" to ChangeKind.ADDED), stat.changes.map { it.path to it.status })

        fixture.send(
            Envelope.PROJECT_GIT_BRANCH_DIFF,
            json.encodeToString(ProjectGitBranchDiffRequest.serializer(), ProjectGitBranchDiffRequest(project.id, "feature", "main", "feature.txt")),
        )
        val patchFrame = fixture.client.awaitWhere("branch patch", timeoutMs) { it.type == Envelope.PROJECT_GIT_PATCH && it.data.contains("\"branch\":\"feature\"") }
        val patch = json.decodeFromString(ProjectGitPatch.serializer(), patchFrame.data)
        assertTrue(patch.patch.contains("+feature line"), patch.patch)

        fixture.send(
            Envelope.PROJECT_GIT_BRANCH_CHANGES,
            json.encodeToString(ProjectGitBranchChangesRequest.serializer(), ProjectGitBranchChangesRequest(project.id, "--upload-pack=x")),
        )
        fixture.client.awaitWhere("hostile branch refusal", timeoutMs) {
            it.type == Envelope.ERROR && it.data.startsWith("project_git_branch_changes refused")
        }
    }

    /** BRANCH-SWITCH-1 (1.74.0): a switch answers its result AND pushes the project's fresh status; a refusal leaves HEAD. */
    @Test
    fun aCheckoutAnswersItsResultAndPushesFreshStatus() = withDaemon { fixture, repo ->
        val git = testGit()
        assertTrue(git.run(repo, listOf("branch", "-M", "main")).ok)
        assertTrue(git.run(repo, listOf("branch", "feature")).ok)
        val project = fixture.createProject(repo.absolutePath)

        fixture.send(
            Envelope.PROJECT_GIT_CHECKOUT,
            json.encodeToString(ProjectGitCheckoutRequest.serializer(), ProjectGitCheckoutRequest(project.id, "feature")),
        )
        val resultFrame = fixture.client.awaitWhere("checkout result", timeoutMs) { it.type == Envelope.PROJECT_GIT_CHECKOUT_RESULT }
        val result = json.decodeFromString(ProjectGitCheckoutResult.serializer(), resultFrame.data)
        assertTrue(result.switched, result.reason)
        assertEquals("feature", result.current)
        // Nobody asked for status: this frame is the push that follows a switch.
        val pushed = fixture.client.awaitWhere("pushed status", timeoutMs) {
            it.type == Envelope.PROJECT_GIT && it.data.contains("\"branch\":\"feature\"")
        }
        assertEquals(project.id, json.decodeFromString(ProjectGitStatus.serializer(), pushed.data).projectId)

        File(repo, "README.md").writeText("dirty\n")
        fixture.send(
            Envelope.PROJECT_GIT_CHECKOUT,
            json.encodeToString(ProjectGitCheckoutRequest.serializer(), ProjectGitCheckoutRequest(project.id, "main")),
        )
        val refusedFrame = fixture.client.awaitWhere("dirty refusal", timeoutMs) {
            it.type == Envelope.PROJECT_GIT_CHECKOUT_RESULT && it.data.contains("\"code\":\"dirty\"")
        }
        assertEquals(1, json.decodeFromString(ProjectGitCheckoutResult.serializer(), refusedFrame.data).dirtyCount)
        assertEquals("feature", git.run(repo, listOf("symbolic-ref", "--short", "HEAD")).out(), "a refusal leaves HEAD where it was")
    }
}
