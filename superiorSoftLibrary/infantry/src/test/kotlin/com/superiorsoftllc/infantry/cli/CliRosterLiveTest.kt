package com.superiorsoftllc.infantry.cli

import com.superiorsoftllc.infantry.daemon.AccessPolicy
import com.superiorsoftllc.infantry.daemon.DaemonFixture
import com.superiorsoftllc.infantry.protocol.Envelope
import com.superiorsoftllc.infantry.daemon.saveProfile
import com.superiorsoftllc.infantry.runner.ProcessRunner
import com.superiorsoftllc.infantry.runner.StubScript
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * ROSTER-3: `infantry agents --live --project <projectId>` sends the `agent_roster` wire request and prints the
 * returned roster, over a REAL daemon and a REAL [Cli.run] -- the same code path the installed binary
 * runs. A blank projectId is refused by the daemon and must surface as a clean outcome, not a crash.
 *
 * Membership is the project's live sessions (BUG-ROLE-1), opened the way the New Agent modal opens them:
 * two agents in project A, one in project B. Nothing here invents a project binding production cannot produce.
 */
class CliRosterLiveTest {

    private val fixtures = mutableListOf<DaemonFixture>()

    private fun launch(script: StubScript = StubScript.default(), runner: ProcessRunner? = null): DaemonFixture =
        DaemonFixture.launch(
            script,
            runner,
            Files.createTempDirectory("infantry-roster-cli-test").toFile(),
        ).also { fixtures += it }

    @AfterTest
    fun stopAll() {
        fixtures.asReversed().forEach {
            runCatching { it.stop() }
            runCatching { it.deleteState() }
        }
        fixtures.clear()
    }

    private data class Result(val exit: Int, val out: String, val err: String)

    private fun cli(port: Int, vararg args: String): Result = cli(port, emptyMap(), *args)

    /** Like [cli] but injects [env] as the sole source of environment variables. */
    private fun cli(port: Int, env: Map<String, String>, vararg args: String): Result {
        val out = StringBuilder()
        val err = StringBuilder()
        val getenv: (String) -> String? = { key -> env[key] }
        val exit = Cli.run(arrayOf(*args, "--port", port.toString()), out, err, getenv)
        return Result(exit, out.toString(), err.toString())
    }

    /**
     * Two agents in project A and one in project B, each a New Agent session opened in its project (BUG-ROLE-1:
     * the roster is the project's live, in-session agents, never the profile store). The profiles only name them.
     */
    private fun seedProjects(fixture: DaemonFixture) {
        fixture.client.await(Envelope.READY)
        fixture.client.saveProfile("""{"id":"a-one","name":"A One","role":"backend"}""")
        fixture.client.saveProfile("""{"id":"a-two","name":"A Two","role":"reviewer"}""")
        fixture.client.saveProfile("""{"id":"b-one","name":"B One","role":"backend"}""")
        for ((agent, project) in listOf("a-one" to "A", "a-two" to "A", "b-one" to "B")) {
            fixture.send(Envelope(Envelope.NEW, """{"agentId":"$agent","projectId":"$project"}"""))
            fixture.await(what = "the $agent session in $project") {
                fixture.daemon.manager.list().any { it.agentId == agent && it.projectId == project }
            }
        }
    }

    @Test
    fun agentsLivePrintsTheProjectRoster() {
        val fixture = launch()
        seedProjects(fixture)

        // BASE GUARD: the daemon must answer a `roster` frame at all. If this throws a MISSING
        // `roster` frame, the worktree was cut from the wrong base -- stop and report it, do not
        // work around it by building the roster client-side.
        fixture.send(Envelope(Envelope.AGENT_ROSTER, """{"projectId":"A"}"""))
        val rosterFrame = fixture.client.await(Envelope.ROSTER)
        assertTrue(
            rosterFrame.data.contains("a-one"),
            "daemon must answer project A's roster frame (right base): ${rosterFrame.data}",
        )

        val live = cli(fixture.port, "agents", "--live", "--project", "A")
        assertEquals(0, live.exit, "agents --live --project A failed: ${live.err}")

        assertTrue(
            live.out.contains("ID | NAME | ROLE | STATUS | TITLE | PENDING"),
            "roster must print the 6-column header: ${live.out}",
        )

        // Project A's own agents are there, with their real fields (id, name, role, status)...
        assertTrue(live.out.contains("a-one"), "roster must show a-one: ${live.out}")
        assertTrue(live.out.contains("A One"), "roster must show a-one's name: ${live.out}")
        assertTrue(live.out.contains("backend"), "roster must show a-one's role: ${live.out}")
        assertTrue(live.out.contains("a-two"), "roster must show a-two: ${live.out}")
        assertTrue(live.out.contains("A Two"), "roster must show a-two's name: ${live.out}")
        assertTrue(live.out.contains("reviewer"), "roster must show a-two's role: ${live.out}")
        assertTrue(live.out.contains("IDLE"), "roster must show the status column: ${live.out}")

        // ...and project B's agent never appears in project A's roster.
        assertFalse(live.out.contains("b-one"), "project B's agent must not be in project A's roster: ${live.out}")
        assertFalse(live.out.contains("B One"), "project B's agent must not be in project A's roster: ${live.out}")
    }

    @Test
    fun agentsDefaultCombinesLiveAgentsAndIdleProfiles() {
        val fixture = launch()
        seedProjects(fixture)
        fixture.client.saveProfile("""{"id":"tmpl","name":"Template","role":"architect"}""")

        val all = cli(fixture.port, "agents", "--project", "A")
        assertEquals(0, all.exit, all.err)
        assertTrue(all.out.contains("KIND | ID | NAME | ROLE | STATUS | DETAIL"), all.out)
        assertTrue(all.out.lines().any { it.startsWith("agent | a-one |") && it.contains("IDLE") }, all.out)
        assertTrue(all.out.lines().any { it.startsWith("profile | tmpl |") }, all.out)
        // A profile with a live session in A is listed once, as an agent.
        assertFalse(all.out.lines().any { it.startsWith("profile | a-one |") }, all.out)

        val profiles = cli(fixture.port, "agents", "--profiles", "--project", "A")
        assertTrue(profiles.out.contains("ID | NAME | ROLE | MODEL") && !profiles.out.contains("agent |"), profiles.out)
        val live = cli(fixture.port, "agents", "--live", "--project", "A")
        assertTrue(live.out.contains("ID | NAME | ROLE | STATUS | TITLE | PENDING") && !live.out.contains("tmpl"), live.out)
        assertNotEquals(0, cli(fixture.port, "agents", "--live", "--profiles", "--project", "A").exit)
    }

    @Test
    fun agentsDefaultOnEmptyProjectAndWithoutProject() {
        val fixture = launch()
        fixture.client.await(Envelope.READY)

        val empty = cli(fixture.port, "agents", "--project", "nobody")
        assertEquals(0, empty.exit, empty.err)
        assertTrue(empty.out.contains("No agents or profiles."), empty.out)

        val noProject = cli(fixture.port, "agents")
        assertEquals(0, noProject.exit, noProject.err)
        assertTrue(noProject.out.contains("No agent profiles."), noProject.out)
    }

    @Test
    fun blankProjectIdIsRefusedNotACrash() {
        val fixture = launch()
        seedProjects(fixture)

        // A blank projectId is REFUSED by the daemon. The CLI must surface that refusal as a real
        // outcome (a clean non-zero exit and the daemon's message), never crash.
        val live = cli(fixture.port, "agents", "--live", "--project", "")
        assertNotEquals(0, live.exit, "a blank project id must not succeed: ${live.out}")
        assertTrue(live.err.contains("agent_roster refused"), "the refusal must be surfaced, not a crash: ${live.err}")
        assertTrue(live.err.contains("blank"), "the refusal must name the blank project id: ${live.err}")
    }

    @Test
    fun agentsLiveDefaultsProjectFromEnvVar() {
        val fixture = launch()
        seedProjects(fixture)

        // Without --project, the CLI must fall back to INFANTRY_PROJECT_ID. The spawned agent receives
        // this env var from the daemon, so it can roster its own project without being told each time.
        run {
            val live = cli(fixture.port, mapOf(AccessPolicy.PROJECT_ID_ENV to "A"), "agents", "--live")
            assertEquals(0, live.exit, "agents --live must succeed when INFANTRY_PROJECT_ID is set: ${live.err}")
            assertTrue(live.out.contains("a-one"), "roster must show project A's agent from the env var: ${live.out}")
            assertFalse(live.out.contains("b-one"), "roster must not leak project B's agent: ${live.out}")
        }
    }

    @Test
    fun agentsLiveWithoutProjectOrEnvVarIsRefused() {
        val fixture = launch()
        seedProjects(fixture)

        // Neither --project nor INFANTRY_PROJECT_ID is set: the projectId resolves to blank, which the
        // daemon refuses. The CLI must surface that cleanly rather than crash or roster all projects.
        run {
            val live = cli(fixture.port, mapOf(AccessPolicy.PROJECT_ID_ENV to ""), "agents", "--live")
            assertNotEquals(0, live.exit, "a blank project id from no source must not succeed: ${live.out}")
            assertTrue(live.err.contains("agent_roster refused"), "the refusal must be surfaced: ${live.err}")
        }
    }
}
