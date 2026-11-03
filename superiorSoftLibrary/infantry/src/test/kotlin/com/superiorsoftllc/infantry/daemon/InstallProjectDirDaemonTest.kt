package com.superiorsoftllc.infantry.daemon

import com.superiorsoftllc.infantry.orchestration.GatedRunner
import com.superiorsoftllc.infantry.protocol.Envelope
import com.superiorsoftllc.infantry.protocol.TicketStatus
import com.superiorsoftllc.infantry.worktree.testGit
import com.superiorsoftllc.infantry.worktree.newGitRepo
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * INSTALL-CWD-1: the installed app has no `INFANTRY_REPO`, and its cwd is wherever the launcher was started (a
 * Start-menu launch: not a project at all). A session spawned for project P must run in P's own directory.
 *
 * Every test runs a REAL daemon with no repo, whose working dir is a temp folder that is NOT the project.
 */
class InstallProjectDirDaemonTest {
    private val json = Json { ignoreUnknownKeys = true }
    private val cleanups = mutableListOf<() -> Unit>()

    @AfterTest
    fun cleanUp() {
        cleanups.asReversed().forEach { runCatching { it() } }
        cleanups.clear()
    }

    private fun launch(projectDirs: Boolean = true): DaemonFixture {
        val state = Files.createTempDirectory("infantry-install-cwd").toFile()
        val launcherCwd = File(state, "launcher-cwd").apply { mkdirs() }
        val fixture = DaemonFixture.launch(
            stateDir = state, repo = null, runner = GatedRunner(), context = FakeTicketContext("HANG"),
            workingDir = launcherCwd, projectDirs = projectDirs,
        )
        cleanups += { fixture.stop(); fixture.deleteState() }
        fixture.client.await(Envelope.READY)
        fixture.client.saveProfile("""{"id":"p1","name":"p1","role":"backend"}""")
        return fixture
    }

    private fun DaemonFixture.createProject(name: String, directory: File?): String {
        val dir = directory?.let { ""","directory":${JsonPrimitive(it.absolutePath)}""" } ?: ""
        send(Envelope.PROJECT_SAVE, """{"name":"$name"$dir}""")
        val frame = client.awaitWhere("project $name") { env ->
            env.type == Envelope.PROJECT && json.parseToJsonElement(env.data).jsonObject["name"]?.jsonPrimitive?.content == name
        }
        return json.parseToJsonElement(frame.data).jsonObject["id"]!!.jsonPrimitive.content
    }

    private fun DaemonFixture.awaitSession() = run {
        val deadline = System.currentTimeMillis() + 5_000 // `new` has no ack frame; wait for the bind
        while (daemon.manager.list().isEmpty() && System.currentTimeMillis() < deadline) Thread.sleep(20)
        daemon.manager.list().single()
    }

    /** REPRO: today the session runs in the launcher's cwd, not in R. */
    @Test
    fun aSessionSpawnedForAProjectRunsInThatProjectsDirectory() {
        val r = newGitRepo("infantry-install-cwd-r").also { cleanups += { it.deleteRecursively() } }
        launch().run {
            val p = createProject("P", r)
            send(Envelope(Envelope.NEW, """{"agentId":"p1","projectId":"$p"}"""))
            val session = awaitSession()
            assertEquals(p, session.projectId)
            assertEquals(r.canonicalPath, File(session.workingDir).canonicalPath, "the session must run in project P's directory")
        }
    }

    private fun git(dir: File, vararg args: String): String {
        val result = testGit().run(dir, args.toList())
        check(result.ok) { "git ${args.joinToString(" ")} failed: ${result.err()}" }
        return result.out().trim()
    }

    /** A ticket start cuts its worktree from R, at the project's own base branch (BASE-1b), and runs there. */
    @Test
    fun aTicketStartCutsItsWorktreeFromTheProjectsRepoAtItsBaseBranch() {
        val r = newGitRepo("infantry-install-cwd-t").also { cleanups += { it.deleteRecursively() } }
        val head = git(r, "rev-parse", "HEAD")
        git(r, "checkout", "-q", "-b", "develop")
        File(r, "dev.txt").writeText("dev\n")
        git(r, "add", "-A")
        git(r, "commit", "-q", "-m", "on develop")
        val developSha = git(r, "rev-parse", "HEAD")
        git(r, "checkout", "-q", "-")
        assertTrue(head != developSha)
        launch().run {
            val p = createProject("P", r)
            val from = client.mark()
            send(Envelope.PROJECT_BASE, """{"projectId":"$p","branch":"develop"}""")
            val based = client.awaitAfter(from, "project_base") { it.type == Envelope.PROJECT || it.type == Envelope.ERROR }
            assertEquals(Envelope.PROJECT, based.type, "project_base develop must be accepted in R: ${based.data}")

            val ticket = client.saveTicket("""{"title":"cwd","agentId":"p1","projectId":"$p"}""")
            val started = client.moveTicket(ticket.id, TicketStatus.IN_PROGRESS)
            assertEquals("started", started.start?.reason, "the start must succeed: ${started.start}")
            val branch = assertNotNull(started.branch, "a worktree branch must be cut")
            assertEquals(developSha, started.base, "cut from the project's base branch")
            assertEquals(developSha, git(r, "rev-parse", "refs/heads/$branch"), "git: the ticket branch lives in R")
            val session = daemon.manager.list().single()
            assertTrue(
                File(session.workingDir).canonicalPath.startsWith(r.canonicalPath + File.separator),
                "the worker runs in a worktree of R: ${session.workingDir}",
            )
        }
    }

    @Test
    fun aSpawnForAProjectWithNoDirectoryIsRefused() {
        launch().run {
            val p = createProject("NoDir", null)
            val from = client.mark()
            send(Envelope(Envelope.NEW, """{"agentId":"p1","projectId":"$p"}"""))
            val refused = client.awaitAfter(from, "the new refusal") { it.type == Envelope.ERROR }
            assertTrue(refused.data.contains("project $p has no directory"), refused.data)
            assertTrue(daemon.manager.list().isEmpty(), "no session in the launcher's cwd")
        }
    }

    @Test
    fun aTicketStartForAProjectWithNoDirectoryIsRefused() {
        launch().run {
            val p = createProject("NoDir", null)
            val ticket = client.saveTicket("""{"title":"nodir","agentId":"p1","projectId":"$p"}""")
            val started = client.moveTicket(ticket.id, TicketStatus.IN_PROGRESS)
            assertEquals("start_refused", started.start?.reason, "${started.start}")
            assertTrue(started.start?.detail.orEmpty().contains("project $p has no directory"), "${started.start}")
            assertTrue(daemon.manager.list().isEmpty(), "no session in the launcher's cwd")
        }
    }

    /** SESSION-CWD-1: the dev daemon (setting off) runs a session naming a project in that project's directory. */
    @Test
    fun withProjectDirsOffASpawnNamingAProjectRunsInItsDirectory() {
        val r = Files.createTempDirectory("infantry-session-cwd-nogit").toFile().also { cleanups += { it.deleteRecursively() } }
        launch(projectDirs = false).run {
            val p = createProject("P", r)
            send(Envelope(Envelope.NEW, """{"agentId":"p1","projectId":"$p"}"""))
            assertEquals(r.canonicalPath, File(awaitSession().workingDir).canonicalPath)
        }
    }

    /** No project named = today's behaviour: the daemon's own directory. */
    @Test
    fun withProjectDirsOffASpawnNamingNoProjectStillRunsInTheDaemonsDirectory() {
        launch(projectDirs = false).run {
            send(Envelope(Envelope.NEW, """{"agentId":"p1"}"""))
            assertEquals(File(stateDir, "launcher-cwd").canonicalPath, File(awaitSession().workingDir).canonicalPath)
        }
    }
}
