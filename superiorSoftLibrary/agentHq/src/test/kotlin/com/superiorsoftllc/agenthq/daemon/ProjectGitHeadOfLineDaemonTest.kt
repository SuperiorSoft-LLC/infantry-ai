package com.superiorsoftllc.agenthq.daemon

import com.superiorsoftllc.agenthq.projectgit.ProjectGitStatus
import com.superiorsoftllc.agenthq.protocol.Envelope
import com.superiorsoftllc.agenthq.protocol.Project
import com.superiorsoftllc.agenthq.protocol.ProjectSave
import com.superiorsoftllc.agenthq.worktree.GitCli
import com.superiorsoftllc.agenthq.worktree.GitResult
import kotlinx.serialization.json.Json
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * BUG-CHANGES-BRANCH-2: `project_git_*` used to be answered inline on the connection's request loop, so one slow git
 * read (~12 s under load) held every later frame on that connection, chat included -- the same class as
 * BUG-ModelsHeadOfLine. The git reads now run off the loop: a slow git delays only its own reply.
 */
class ProjectGitHeadOfLineDaemonTest {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    @Test
    fun aSlowGitReadDoesNotBlockTheFramesSentAfterIt() {
        val release = CountDownLatch(1)
        val entered = CountDownLatch(1)
        val blocking = AtomicBoolean(false)
        // Stands in for git under load. Only blocks once armed, so the project setup never touches it; bounded so a
        // bug cannot hang CI. Answers "not a repository", which the service turns into an `available = false` status.
        val slowGit = GitCli { _, args ->
            if (blocking.get()) {
                entered.countDown()
                release.await(30, TimeUnit.SECONDS)
            }
            GitResult(args, 128, "", "fatal: not a git repository")
        }
        val fixture = DaemonFixture.launch(projectGitCli = slowGit)
        try {
            fixture.client.await(Envelope.READY)
            val dir = java.nio.file.Files.createTempDirectory("agenthq-pgit-hol").toFile()
            fixture.send(Envelope.PROJECT_SAVE, json.encodeToString(ProjectSave.serializer(), ProjectSave(name = "slow-git", directory = dir.absolutePath)))
            val project = json.decodeFromString(
                Project.serializer(),
                fixture.client.awaitWhere("project frame", 6_000) { it.type == Envelope.PROJECT && it.data.contains("slow-git") }.data,
            )

            blocking.set(true)
            fixture.send(Envelope.PROJECT_GIT_STATUS, project.id)
            assertTrue(entered.await(10, TimeUnit.SECONDS), "the daemon asked git")

            // The connection keeps answering while the git read is still running.
            fixture.send(Envelope.PROJECT_SAVE, """{"name":"after-git"}""")
            assertNotNull(
                runCatching {
                    fixture.client.awaitWhere("after-git project", 4_000) { it.type == Envelope.PROJECT && it.data.contains("after-git") }
                }.getOrNull(),
                "project_save waited behind a slow project_git_status",
            )
            assertNull(fixture.client.awaitOrNull(Envelope.PROJECT_GIT, timeoutMs = 200), "project_git answered before git returned")

            release.countDown()
            val status = json.decodeFromString(ProjectGitStatus.serializer(), fixture.client.await(Envelope.PROJECT_GIT).data)
            assertEquals(project.id, status.projectId)
            assertFalse(status.available)
            runCatching { dir.deleteRecursively() }
        } finally {
            release.countDown()
            fixture.stop()
            fixture.deleteState()
        }
    }
}
