package com.superiorsoftllc.agenthq.daemon

import com.superiorsoftllc.agenthq.epics.Epic
import com.superiorsoftllc.agenthq.orchestration.GatedRunner
import com.superiorsoftllc.agenthq.protocol.Envelope
import com.superiorsoftllc.agenthq.protocol.Project
import com.superiorsoftllc.agenthq.protocol.Ticket
import com.superiorsoftllc.agenthq.protocol.TicketStatus
import com.superiorsoftllc.agenthq.worktree.TicketBaseRef
import com.superiorsoftllc.agenthq.worktree.newGitRepo
import com.superiorsoftllc.agenthq.worktree.testGit
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * BASE-1c: which commit a ticket start cuts its `ticket/` branch from, per EPIC over the project.
 *
 * Every test runs a REAL daemon over a throwaway git repo under the system temp dir, and reads the answer from the
 * ticket the daemon recorded AND from git (the ticket branch's own commit), never only from a printed note.
 */
class EpicBaseBranchDaemonTest {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val cleanups = mutableListOf<() -> Unit>()

    @AfterTest
    fun cleanUp() {
        cleanups.asReversed().forEach { runCatching { it() } }
        cleanups.clear()
    }

    /** A throwaway repo whose HEAD is its own: a commit naming [prefix] on top, so no two repos share a HEAD sha. */
    private fun repo(prefix: String): File = newGitRepo(prefix).also { dir ->
        cleanups += { dir.deleteRecursively() }
        File(dir, "who.txt").writeText("$prefix\n")
        git(dir, "add", "-A")
        git(dir, "commit", "-q", "-m", prefix)
    }

    private fun git(dir: File, vararg args: String): String {
        val result = testGit().run(dir, args.toList())
        check(result.ok) { "git ${args.joinToString(" ")} failed: ${result.err()}" }
        return result.out().trim()
    }

    /** A commit on a new branch [name] of [dir] that HEAD does not have; [dir]'s checkout stays where it was. */
    private fun commitOnBranch(dir: File, name: String): String {
        val head = git(dir, "rev-parse", "--abbrev-ref", "HEAD")
        git(dir, "checkout", "-q", "-b", name)
        File(dir, "$name.txt").writeText("$name\n")
        git(dir, "add", "-A")
        git(dir, "commit", "-q", "-m", "on $name")
        val sha = git(dir, "rev-parse", "HEAD")
        git(dir, "checkout", "-q", head)
        return sha
    }

    /** A real daemon cutting in [repo]. [stateDir] given = the caller owns it (a restart reuses it). */
    private fun launch(repo: File, baseRef: TicketBaseRef, stateDir: File? = null): DaemonFixture {
        val state = stateDir ?: java.nio.file.Files.createTempDirectory("agenthq-epicbase-state").toFile()
        val fixture = DaemonFixture.launch(
            stateDir = state, repo = repo, runner = GatedRunner(), context = FakeTicketContext("HANG"), baseRef = baseRef,
            workingDir = File(state, "workdir").apply { mkdirs() },
        )
        cleanups += { fixture.stop(); if (stateDir == null) fixture.deleteState() }
        fixture.client.await(Envelope.READY)
        // One profile per ticket a test starts: each start HANGs and keeps its profile busy.
        listOf("p1", "p2", "p3").forEach { fixture.client.saveProfile("""{"id":"$it","name":"$it","role":"backend"}""") }
        return fixture
    }

    /** Sets [projectId]'s base branch (`project_base`, BASE-1a) and asserts the daemon stored it. */
    private fun DaemonFixture.setBase(projectId: String, branch: String) {
        val from = client.mark()
        send(Envelope.PROJECT_BASE, buildJsonObject {
            put("projectId", JsonPrimitive(projectId))
            put("branch", JsonPrimitive(branch))
        }.toString())
        val answer = client.awaitAfter(from, "the project_base answer") { it.type == Envelope.PROJECT || it.type == Envelope.ERROR }
        assertEquals(Envelope.PROJECT, answer.type, "project_base $branch must be accepted: ${answer.data}")
        assertEquals(branch, json.decodeFromString(Project.serializer(), answer.data).baseBranch)
    }

    /** `epic_base`: the `epic` frame answering it, or the `error` frame refusing it. */
    private fun DaemonFixture.epicBase(epicId: String, branch: String? = null, clear: Boolean = false): Envelope {
        val from = client.mark()
        val payload = buildJsonObject {
            put("epicId", JsonPrimitive(epicId))
            branch?.let { put("branch", JsonPrimitive(it)) }
            if (clear) put("clear", JsonPrimitive(true))
        }
        send(Envelope.EPIC_BASE, payload.toString())
        return client.awaitAfter(from, "the epic_base answer") { it.type == Envelope.EPIC || it.type == Envelope.ERROR }
    }

    private fun Envelope.epicBaseBranch(): String = json.decodeFromString(Epic.serializer(), data).baseBranch

    /** Sets epic [epicId]'s base branch and asserts the daemon stored it. */
    private fun DaemonFixture.setEpicBase(epicId: String, branch: String) {
        val answer = epicBase(epicId, branch)
        assertEquals(Envelope.EPIC, answer.type, "epic_base $branch must be accepted: ${answer.data}")
        assertEquals(branch, answer.epicBaseBranch())
    }

    private fun DaemonFixture.createProject(name: String): String {
        send(Envelope.PROJECT_SAVE, """{"name":"$name"}""")
        val frame = client.awaitWhere("project $name") { env ->
            env.type == Envelope.PROJECT && json.parseToJsonElement(env.data).jsonObject["name"]?.jsonPrimitive?.content == name
        }
        return json.parseToJsonElement(frame.data).jsonObject["id"]!!.jsonPrimitive.content
    }

    private fun DaemonFixture.createEpic(projectId: String, name: String): String {
        val from = client.mark()
        send(Envelope.EPIC_SAVE, """{"projectId":"$projectId","name":"$name"}""")
        val frame = client.awaitAfter(from, "epic $name") { it.type == Envelope.EPIC }
        return json.parseToJsonElement(frame.data).jsonObject["id"]!!.jsonPrimitive.content
    }

    /** Files a ticket in [projectId] (and in [epicId] when given) and starts it; the start must succeed. */
    private fun DaemonFixture.startIn(title: String, projectId: String, epicId: String?, agentId: String): Ticket {
        val epic = epicId?.let { ""","epicId":"$it"""" } ?: ""
        val ticket = client.saveTicket("""{"title":"$title","agentId":"$agentId","projectId":"$projectId"$epic}""")
        assertEquals(epicId, ticket.epicId, "the ticket is filed under the epic")
        val started = client.moveTicket(ticket.id, TicketStatus.IN_PROGRESS)
        assertEquals("started", started.start?.reason, "the start must succeed: ${started.start}")
        return started
    }

    /** The commit the ticket's branch was cut at: its tip, since a hanging start commits nothing. */
    private fun tipOf(repo: File, ticket: Ticket): String = git(repo, "rev-parse", "refs/heads/${ticket.branch}")

    // --- Step 1 REPRO, now the BASE-1c behaviour -----------------------------------------------------------------

    /**
     * STEP 1 REPRO (before BASE-1c it asserted the PROJECT base: a ticket in epic E cut from develop because an epic
     * had no setting). Now: project base `develop`, epic E's base `release`. A ticket in E cuts from release, one in the
     * same project outside E from develop, and once E's setting is cleared the next ticket in E cuts from develop.
     */
    @Test
    fun aTicketInAnEpicCutsFromTheEpicBase_outsideItFromTheProjectBase_andAfterAClearFromTheProjectBase() {
        val daemonRepo = repo("agenthq-epicbase-repro")
        val developSha = commitOnBranch(daemonRepo, "develop")
        val releaseSha = commitOnBranch(daemonRepo, "release")
        assertTrue(developSha != releaseSha)
        launch(daemonRepo, TicketBaseRef.fixed(TicketBaseRef.HEAD)).run {
            val p = createProject("P")
            setBase(p, "develop")
            val e = createEpic(p, "E")
            assertEquals("", epicBase(e).epicBaseBranch(), "a fresh epic has no base branch")
            setEpicBase(e, "release")

            val inE = startIn("in E", p, e, "p1")
            assertEquals(releaseSha, inE.base, "a ticket in E records release's commit")
            assertEquals(releaseSha, tipOf(daemonRepo, inE), "git: its branch sits on release's commit")
            assertEquals("cut from release ${releaseSha.take(8)} (epic $e)", inE.start?.detail?.replace(Regex("; no Android SDK found.*?in this worktree"), ""), "the note names the epic")

            val outside = startIn("outside E", p, null, "p2")
            assertEquals(developSha, outside.base, "a ticket outside E cuts from the project's develop")
            assertEquals(developSha, tipOf(daemonRepo, outside))
            assertEquals("cut from develop ${developSha.take(8)} (project base)", outside.start?.detail?.replace(Regex("; no Android SDK found.*?in this worktree"), ""))

            val cleared = epicBase(e, clear = true)
            assertEquals(Envelope.EPIC, cleared.type, cleared.data)
            assertEquals("", cleared.epicBaseBranch())
            val after = startIn("in E after clear", p, e, "p3")
            assertEquals(developSha, after.base, "after the clear the next ticket in E cuts from develop")
            assertEquals(developSha, tipOf(daemonRepo, after))
            assertEquals("cut from develop ${developSha.take(8)} (project base)", after.start?.detail?.replace(Regex("; no Android SDK found.*?in this worktree"), ""))
            assertEquals(releaseSha, tipOf(daemonRepo, inE), "the ticket started earlier keeps its branch on release")
        }
    }

    /** Precedence through the daemon with all four levels present: epic > project > AGENTHQ_BASE_BRANCH > HEAD. */
    @Test
    fun theEpicBeatsTheProjectWhichBeatsTheEnvBaseWhichBeatsHead() {
        val daemonRepo = repo("agenthq-epicbase-prec")
        val head = git(daemonRepo, "rev-parse", "HEAD")
        val developSha = commitOnBranch(daemonRepo, "develop")
        val envSha = commitOnBranch(daemonRepo, "staging")
        val releaseSha = commitOnBranch(daemonRepo, "release")
        assertEquals(4, setOf(head, developSha, envSha, releaseSha).size, "four distinct commits, or this proves nothing")

        launch(daemonRepo, TicketBaseRef.fromEnv { if (it == TicketBaseRef.ENV) "staging" else null }).run {
            val p = createProject("P")
            val q = createProject("Q")
            setBase(p, "develop")
            val e = createEpic(p, "E")
            setEpicBase(e, "release")
            assertEquals(releaseSha, startIn("epic", p, e, "p1").base, "1. the epic's base beats the project's and the env's")
            assertEquals(developSha, startIn("project", p, null, "p2").base, "2. the project's base beats the env's")
            assertEquals(envSha, startIn("env", q, null, "p3").base, "3. AGENTHQ_BASE_BRANCH when no setting applies")
        }
        launch(daemonRepo, TicketBaseRef.fixed(TicketBaseRef.HEAD)).run {
            val q = createProject("Q")
            assertEquals(head, startIn("head", q, null, "p1").base, "4. HEAD when nothing is set")
        }
    }

    /** A branch that does not resolve is refused with the reason, and nothing is written; an unknown epic too. */
    @Test
    fun aBranchThatDoesNotResolveIsRefusedWithTheReasonAndNothingIsStored() {
        val daemonRepo = repo("agenthq-epicbase-refuse")
        commitOnBranch(daemonRepo, "release")
        launch(daemonRepo, TicketBaseRef.fixed(TicketBaseRef.HEAD)).run {
            val p = createProject("P")
            val e = createEpic(p, "E")
            setEpicBase(e, "release")
            val refused = epicBase(e, "no-such-branch")
            assertEquals(Envelope.ERROR, refused.type)
            assertTrue(refused.data.startsWith("epic_base refused: "), refused.data)
            assertTrue(refused.data.contains("base branch 'no-such-branch' does not resolve to a commit"), refused.data)
            assertEquals("release", epicBase(e).epicBaseBranch(), "the refused set wrote nothing")

            val dash = epicBase(e, "-x")
            assertEquals(Envelope.ERROR, dash.type)
            assertTrue(dash.data.contains("must not start with '-'"), dash.data)
            val unknown = epicBase("epic-nope", "release")
            assertEquals(Envelope.ERROR, unknown.type)
            assertTrue(unknown.data.contains("unknown epic 'epic-nope'"), unknown.data)
            val both = epicBase(e, "release", clear = true)
            assertTrue(both.type == Envelope.ERROR && both.data.contains("not both"), both.data)
        }
    }

    /** The setting is durable: it survives a daemon restart over the same state. */
    @Test
    fun theEpicBaseSurvivesARestart() {
        val daemonRepo = repo("agenthq-epicbase-restart")
        val releaseSha = commitOnBranch(daemonRepo, "release")
        val state = java.nio.file.Files.createTempDirectory("agenthq-epicbase-restart-state").toFile()
        cleanups += { state.deleteRecursively() }
        val first = launch(daemonRepo, TicketBaseRef.fixed(TicketBaseRef.HEAD), stateDir = state)
        val p = first.createProject("P")
        val e = first.createEpic(p, "E")
        first.setEpicBase(e, "release")
        first.stop()

        launch(daemonRepo, TicketBaseRef.fixed(TicketBaseRef.HEAD), stateDir = state).run {
            assertEquals("release", epicBase(e).epicBaseBranch(), "read back after a restart")
            assertEquals(releaseSha, startIn("after restart", p, e, "p1").base)
        }
    }
}
