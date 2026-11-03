package com.superiorsoftllc.infantry.daemon

import com.superiorsoftllc.infantry.orchestration.GatedRunner
import com.superiorsoftllc.infantry.protocol.Envelope
import com.superiorsoftllc.infantry.protocol.Project
import com.superiorsoftllc.infantry.protocol.Ticket
import com.superiorsoftllc.infantry.protocol.TicketStatus
import com.superiorsoftllc.infantry.worktree.TicketBaseRef
import com.superiorsoftllc.infantry.worktree.newGitRepo
import com.superiorsoftllc.infantry.worktree.testGit
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * BASE-1a: which commit a ticket start cuts its `ticket/` branch from, per project.
 *
 * Every test runs a REAL daemon over a throwaway git repo under the system temp dir, and reads the answer from the
 * ticket the daemon recorded AND from git (the ticket branch's own commit), never only from a printed note.
 */
class ProjectBaseBranchDaemonTest {
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

    /**
     * A real daemon cutting in [repo] (null = the git checkout [workingDir] is in, the owner's daemon). [stateDir] given =
     * the caller owns it (a restart reuses it); otherwise the fixture's own temp dir, deleted after the test.
     */
    private fun launch(
        repo: File?,
        baseRef: TicketBaseRef,
        workingDir: File? = null,
        stateDir: File? = null,
    ): DaemonFixture {
        val state = stateDir ?: java.nio.file.Files.createTempDirectory("infantry-base-state").toFile()
        val fixture = DaemonFixture.launch(
            stateDir = state, repo = repo, runner = GatedRunner(), context = FakeTicketContext("HANG"), baseRef = baseRef,
            workingDir = workingDir ?: File(state, "workdir").apply { mkdirs() },
        )
        cleanups += { fixture.stop(); if (stateDir == null) fixture.deleteState() }
        fixture.client.await(Envelope.READY)
        // One profile per ticket a test starts: each start HANGs and keeps its profile busy.
        listOf("p1", "p2", "p3").forEach { fixture.client.saveProfile("""{"id":"$it","name":"$it","role":"backend"}""") }
        return fixture
    }

    /** `project_base`: the `project` frame answering it, or the `error` frame refusing it. */
    private fun DaemonFixture.projectBase(projectId: String, branch: String? = null, clear: Boolean = false): Envelope {
        val from = client.mark()
        val payload = kotlinx.serialization.json.buildJsonObject {
            put("projectId", kotlinx.serialization.json.JsonPrimitive(projectId))
            branch?.let { put("branch", kotlinx.serialization.json.JsonPrimitive(it)) }
            if (clear) put("clear", kotlinx.serialization.json.JsonPrimitive(true))
        }
        send(Envelope.PROJECT_BASE, payload.toString())
        return client.awaitAfter(from, "the project_base answer") { it.type == Envelope.PROJECT || it.type == Envelope.ERROR }
    }

    /** Sets [projectId]'s base branch and asserts the daemon stored it. */
    private fun DaemonFixture.setBase(projectId: String, branch: String) {
        val answer = projectBase(projectId, branch)
        assertEquals(Envelope.PROJECT, answer.type, "project_base $branch must be accepted: ${answer.data}")
        assertEquals(branch, json.decodeFromString(Project.serializer(), answer.data).baseBranch)
    }

    private fun Envelope.baseBranch(): String = json.decodeFromString(Project.serializer(), data).baseBranch

    private fun DaemonFixture.createProject(name: String, directory: File? = null): String {
        val dir = directory?.let { ""","directory":${kotlinx.serialization.json.JsonPrimitive(it.absolutePath)}""" } ?: ""
        send(Envelope.PROJECT_SAVE, """{"name":"$name"$dir}""")
        val frame = client.awaitWhere("project $name") { env ->
            env.type == Envelope.PROJECT && json.parseToJsonElement(env.data).jsonObject["name"]?.jsonPrimitive?.content == name
        }
        return json.parseToJsonElement(frame.data).jsonObject["id"]!!.jsonPrimitive.content
    }

    private fun DaemonFixture.start(title: String, projectId: String, agentId: String): Ticket {
        val ticket = client.saveTicket("""{"title":"$title","agentId":"$agentId","projectId":"$projectId"}""")
        val started = client.moveTicket(ticket.id, TicketStatus.IN_PROGRESS)
        assertEquals("started", started.start?.reason, "the start must succeed: ${started.start}")
        return started
    }

    /** The commit the ticket's branch was cut at: its tip, since a hanging start commits nothing. */
    private fun tipOf(repo: File, ticket: Ticket): String = git(repo, "rev-parse", "refs/heads/${ticket.branch}")

    // --- Step 1 REPRO (no project base set; unchanged by BASE-1a) -----------------------------------------------

    /**
     * STEP 1 (characterization, BEFORE BASE-1a's production change). Two projects whose directories are two DIFFERENT
     * checkouts: neither directory is where a cut happens. Both tickets cut from the DAEMON's repo HEAD (the one repo
     * the board knows), not from their project's own checkout; and `INFANTRY_BASE_BRANCH` overrides both.
     */
    @Test
    fun todayEveryProjectCutsFromTheDaemonReposHeadAndTheEnvBaseOverridesBoth() {
        val daemonRepo = repo("infantry-base-daemon")
        val repoA = repo("infantry-base-a")
        val repoB = repo("infantry-base-b")
        val daemonHead = git(daemonRepo, "rev-parse", "HEAD")
        val developSha = commitOnBranch(daemonRepo, "develop")
        assertTrue(git(repoA, "rev-parse", "HEAD") != daemonHead && git(repoB, "rev-parse", "HEAD") != daemonHead)

        launch(daemonRepo, TicketBaseRef.fixed(TicketBaseRef.HEAD)).run {
            val a = createProject("A", repoA)
            val b = createProject("B", repoB)
            val ta = start("ta", a, "p1")
            val tb = start("tb", b, "p2")
            assertEquals(daemonHead, ta.base, "project A cuts from the daemon repo's HEAD, not its own checkout")
            assertEquals(daemonHead, tb.base, "project B cuts from the daemon repo's HEAD, not its own checkout")
            assertEquals(daemonHead, tipOf(daemonRepo, ta))
            assertEquals(daemonHead, tipOf(daemonRepo, tb))
        }

        launch(daemonRepo, TicketBaseRef.fromEnv { if (it == TicketBaseRef.ENV) "develop" else null }).run {
            val a = createProject("A", repoA)
            val b = createProject("B", repoB)
            val ta = start("ta env", a, "p1")
            val tb = start("tb env", b, "p2")
            assertEquals(developSha, ta.base, "INFANTRY_BASE_BRANCH overrides HEAD for project A")
            assertEquals(developSha, tb.base, "INFANTRY_BASE_BRANCH overrides HEAD for project B")
            assertEquals("cut from develop ${developSha.take(8)} (INFANTRY_BASE_BRANCH)", ta.start?.detail?.replace(Regex("; no Android SDK found.*?in this worktree"), ""))
        }
    }

    // --- BASE-1a: the project's own base branch ---------------------------------------------------------------

    /** Acceptance: project A's base is `develop`, B has none. A cuts from develop's commit, B from HEAD. */
    @Test
    fun projectAWithDevelopCutsFromDevelopAndProjectBWithNoneCutsFromHead() {
        val daemonRepo = repo("infantry-base-ab")
        val head = git(daemonRepo, "rev-parse", "HEAD")
        val developSha = commitOnBranch(daemonRepo, "develop")
        launch(daemonRepo, TicketBaseRef.fixed(TicketBaseRef.HEAD)).run {
            val a = createProject("A", daemonRepo)
            val b = createProject("B")
            setBase(a, "develop")
            assertEquals("", projectBase(b).baseBranch(), "B has no base branch")

            val ta = start("ta", a, "p1")
            val tb = start("tb", b, "p2")
            assertEquals(developSha, ta.base, "A records develop's commit")
            assertEquals(developSha, tipOf(daemonRepo, ta), "git: A's ticket branch is at develop's commit")
            assertEquals("cut from develop ${developSha.take(8)} (project base)", ta.start?.detail?.replace(Regex("; no Android SDK found.*?in this worktree"), ""))
            assertEquals(head, tb.base, "B records HEAD's commit")
            assertEquals(head, tipOf(daemonRepo, tb), "git: B's ticket branch is at HEAD")
            assertEquals("cut from HEAD ${head.take(8)}", tb.start?.detail?.replace(Regex("; no Android SDK found.*?in this worktree"), ""))
        }
    }

    /** Precedence with all three present at once: project setting > INFANTRY_BASE_BRANCH > HEAD. */
    @Test
    fun theProjectSettingBeatsTheEnvBaseWhichBeatsHead() {
        val daemonRepo = repo("infantry-base-prec")
        val head = git(daemonRepo, "rev-parse", "HEAD")
        val developSha = commitOnBranch(daemonRepo, "develop")
        val envSha = commitOnBranch(daemonRepo, "release")
        assertEquals(3, setOf(head, developSha, envSha).size, "three distinct commits, or this proves nothing")

        launch(daemonRepo, TicketBaseRef.fromEnv { if (it == TicketBaseRef.ENV) "release" else null }).run {
            val a = createProject("A")
            val b = createProject("B")
            setBase(a, "develop")
            assertEquals(developSha, start("a1", a, "p1").base, "the project setting beats INFANTRY_BASE_BRANCH")
            assertEquals(envSha, start("b1", b, "p2").base, "with no setting, INFANTRY_BASE_BRANCH beats HEAD")
            assertEquals("", projectBase(a, clear = true).baseBranch(), "a clear removes the setting")
            assertEquals(envSha, start("a2", a, "p3").base, "a cleared project falls back to INFANTRY_BASE_BRANCH")
        }
        launch(daemonRepo, TicketBaseRef.fixed(TicketBaseRef.HEAD)).run {
            assertEquals(head, start("c1", createProject("C"), "p1").base, "with neither, HEAD")
        }
    }

    /** A started ticket keeps the base it recorded; the setting changes new cuts only. */
    @Test
    fun aBaseChangeAffectsNewCutsOnlyAndAStartedTicketKeepsItsRecordedBase() {
        val daemonRepo = repo("infantry-base-keep")
        val head = git(daemonRepo, "rev-parse", "HEAD")
        val developSha = commitOnBranch(daemonRepo, "develop")
        launch(daemonRepo, TicketBaseRef.fixed(TicketBaseRef.HEAD)).run {
            val a = createProject("A")
            val before = start("before", a, "p1")
            assertEquals(head, before.base)
            setBase(a, "develop")
            assertEquals(head, client.board().single { it.id == before.id }.base, "the started ticket keeps its base")
            assertEquals(head, tipOf(daemonRepo, before), "and its branch is untouched")
            assertEquals(developSha, start("after", a, "p2").base, "a new cut takes the new setting")
        }
    }

    /** A base that does not resolve, an option-shaped name, and a project in another repository are refused at set time. */
    @Test
    fun aBaseThatCannotBeCutFromIsRefusedAtSetTimeWithTheReasonAndNothingIsStored() {
        val daemonRepo = repo("infantry-base-refuse")
        val otherRepo = repo("infantry-base-other")
        commitOnBranch(otherRepo, "develop")
        launch(daemonRepo, TicketBaseRef.fixed(TicketBaseRef.HEAD)).run {
            val a = createProject("A", daemonRepo)
            val missing = projectBase(a, "no-such-branch")
            assertEquals(Envelope.ERROR, missing.type)
            assertEquals(
                "project_base refused: base branch 'no-such-branch' does not resolve to a commit in ${daemonRepo.path}",
                missing.data,
            )
            val option = projectBase(a, "--orphan")
            assertEquals(Envelope.ERROR, option.type)
            assertTrue(option.data.contains("must not start with '-'"), option.data)
            assertEquals("", projectBase(a).baseBranch(), "a refused set stores nothing")

            // The PM's condition: `develop` exists in the OTHER repo, but that repo is not where tickets are cut.
            val elsewhere = createProject("Elsewhere", otherRepo)
            val mismatch = projectBase(elsewhere, "develop")
            assertEquals(Envelope.ERROR, mismatch.type)
            assertTrue(mismatch.data.contains("is not in the repository ticket starts cut in"), mismatch.data)
            assertEquals("", projectBase(elsewhere).baseBranch())

            assertEquals(Envelope.ERROR, projectBase("project-nope", "develop").type, "an unknown project is refused")
            assertEquals(Envelope.ERROR, projectBase(a, "develop", clear = true).type, "a branch and a clear together are refused")
        }
    }

    /** The setting is the daemon's project record: it survives a restart over the same database. */
    @Test
    fun theBaseBranchSurvivesADaemonRestart() {
        val daemonRepo = repo("infantry-base-restart")
        val developSha = commitOnBranch(daemonRepo, "develop")
        val state = java.nio.file.Files.createTempDirectory("infantry-base-restart-state").toFile()
        cleanups += { state.deleteRecursively() }
        val first = launch(daemonRepo, TicketBaseRef.fixed(TicketBaseRef.HEAD), stateDir = state)
        val a = first.createProject("A")
        first.setBase(a, "develop")
        first.stop()

        launch(daemonRepo, TicketBaseRef.fixed(TicketBaseRef.HEAD), stateDir = state).run {
            assertEquals("develop", projectBase(a).baseBranch(), "read back after the restart")
            assertEquals(developSha, start("after restart", a, "p1").base)
        }
    }

    /**
     * The owner's layout (the gap BUG-REGRESS-1 reported): a DIRTY main checkout on its own branch, and a linked worktree
     * on `feature-agenthq-mvp` ahead of it, where the daemon runs (no INFANTRY_REPO) and the project lives. With the
     * project base = feature-agenthq-mvp, a start cuts from mvp's commit, never from the main checkout's HEAD.
     */
    @Test
    fun ownerLayoutDirtyMainCheckoutWithAnMvpWorktreeCutsFromTheMvpCommit() {
        val main = repo("infantry-base-owner")
        val mainHead = git(main, "rev-parse", "HEAD")
        val mvpDir = File(main.parentFile, "${main.name}-mvp")
        cleanups += { mvpDir.deleteRecursively() }
        git(main, "worktree", "add", "-q", "-b", "feature-agenthq-mvp", mvpDir.absolutePath)
        File(mvpDir, "mvp.txt").writeText("mvp\n")
        git(mvpDir, "add", "-A")
        git(mvpDir, "commit", "-q", "-m", "mvp work")
        val mvpSha = git(mvpDir, "rev-parse", "HEAD")
        File(main, "README.md").writeText("half-written\n")
        File(main, "scratch.txt").writeText("untracked\n")
        assertTrue(git(main, "status", "--porcelain").isNotEmpty(), "the main checkout must be dirty")
        assertTrue(mvpSha != mainHead)

        launch(repo = null, baseRef = TicketBaseRef.fixed(TicketBaseRef.HEAD), workingDir = mvpDir).run {
            val p = createProject("KotlinProject", mvpDir)
            setBase(p, "feature-agenthq-mvp")
            val t = start("owner task", p, "p1")
            assertEquals(TicketStatus.IN_PROGRESS, t.status)
            assertEquals(mvpSha, t.base, "cut from feature-agenthq-mvp's commit, not the main checkout's HEAD")
            assertEquals(mvpSha, tipOf(main, t))
            assertEquals("cut from feature-agenthq-mvp ${mvpSha.take(8)} (project base)", t.start?.detail?.replace(Regex("; no Android SDK found.*?in this worktree"), ""))
            assertEquals("half-written", File(main, "README.md").readText().trim(), "the main checkout's edit stays put")
        }
    }

    // --- BUG-BASE-087: the daemon's own chain (no HEAD fallback) ---------------------------------------------------

    /** The production daemon's chain: the project's base, then INFANTRY_BASE_BRANCH, then NOTHING (refused). */
    private fun productionBase(env: String? = null): TicketBaseRef =
        TicketBaseRef.fromEnv(orElse = TicketBaseRef.NONE) { if (it == TicketBaseRef.ENV) env else null }

    /**
     * Default base: with the project's base set, a start's branch holds the base branch's CURRENT tip (the tip moves after
     * the setting is saved), and the answer names the branch, the sha and the setting that chose it.
     */
    @Test
    fun theProjectBaseIsTheDefaultAndAStartCutsItsCurrentTipAndNamesBranchShaAndSetting() {
        val daemonRepo = repo("infantry-base-default")
        val head = git(daemonRepo, "rev-parse", "HEAD")
        commitOnBranch(daemonRepo, "feature-agenthq-mvp")
        launch(daemonRepo, productionBase()).run {
            val p = createProject("P", daemonRepo)
            setBase(p, "feature-agenthq-mvp")
            val tip = commitOnBranch(daemonRepo, "tip-mover").also {
                git(daemonRepo, "branch", "-f", "feature-agenthq-mvp", it)
            }
            val t = start("default", p, "p1")
            assertEquals(tip, t.base, "the branch's tip at start time, not at set time")
            assertEquals(tip, tipOf(daemonRepo, t), "git: the ticket branch contains the base's current tip")
            assertTrue(tip != head, "and not the main checkout's HEAD")
            assertEquals("cut from feature-agenthq-mvp ${tip.take(8)} (project base)", t.start?.detail?.replace(Regex("; no Android SDK found.*?in this worktree"), ""))
        }
    }

    /** Nothing configured (no epic, project or env base) is refused with the reason, and leaves no branch behind. */
    @Test
    fun aStartWithNoBaseConfiguredIsRefusedWithTheReasonInsteadOfCuttingFromHead() {
        val daemonRepo = repo("infantry-base-none")
        val branchesBefore = git(daemonRepo, "branch", "--list", "ticket/*")
        launch(daemonRepo, productionBase()).run {
            val p = createProject("P", daemonRepo)
            val ticket = client.saveTicket("""{"title":"no base","agentId":"p1","projectId":"$p"}""")
            val refused = client.moveTicket(ticket.id, TicketStatus.IN_PROGRESS)
            assertEquals(false, refused.start?.ok, "the start must be refused: ${refused.start}")
            assertEquals("start_refused", refused.start?.reason)
            assertEquals(
                "no base branch configured for project $p; set one with `infantry project base $p <branch>`, an epic " +
                    "base (`infantry epic base <epicId> <branch>`) or the daemon's INFANTRY_BASE_BRANCH" +
                    " (a headless daemon also needs INFANTRY_REPO=<git repo>)",
                refused.start?.detail,
            )
            val stored = client.board().single { it.id == ticket.id }
            assertEquals(TicketStatus.TODO, stored.status, "a refused start leaves the ticket in To Do")
            assertEquals(null, stored.branch, "and records no branch")
            assertEquals(branchesBefore, git(daemonRepo, "branch", "--list", "ticket/*"), "git: no ticket branch was cut")

            // Configuring the project's base is all it takes: the same ticket then starts from it.
            val developSha = commitOnBranch(daemonRepo, "develop")
            setBase(p, "develop")
            val started = client.moveTicket(ticket.id, TicketStatus.IN_PROGRESS)
            assertEquals("started", started.start?.reason, "${started.start}")
            assertEquals(developSha, started.base)
        }
        launch(daemonRepo, productionBase(env = "develop")).run {
            val q = createProject("Q", daemonRepo)
            val t = start("env base", q, "p1")
            assertEquals(git(daemonRepo, "rev-parse", "develop"), t.base, "INFANTRY_BASE_BRANCH still applies")
            assertTrue(t.start?.detail.orEmpty().endsWith("(INFANTRY_BASE_BRANCH)"), "${t.start}")
        }
    }

    /** A base accepted at set time that goes stale (its branch deleted) is refused at start with the reason. */
    @Test
    fun aConfiguredBaseWhoseBranchWasDeletedIsRefusedAtStart() {
        val daemonRepo = repo("infantry-base-stale")
        commitOnBranch(daemonRepo, "develop")
        launch(daemonRepo, productionBase()).run {
            val p = createProject("P", daemonRepo)
            setBase(p, "develop")
            git(daemonRepo, "branch", "-D", "develop")
            val ticket = client.saveTicket("""{"title":"stale base","agentId":"p1","projectId":"$p"}""")
            val refused = client.moveTicket(ticket.id, TicketStatus.IN_PROGRESS)
            assertEquals("start_refused", refused.start?.reason, "${refused.start}")
            assertEquals("base branch 'develop' does not resolve to a commit in ${daemonRepo.path}", refused.start?.detail)
            assertEquals(null, client.board().single { it.id == ticket.id }.branch)
        }
    }
}
