package com.superiorsoftllc.agenthq.cli

import com.superiorsoftllc.agenthq.daemon.AccessPolicy
import com.superiorsoftllc.agenthq.daemon.DaemonFixture
import com.superiorsoftllc.agenthq.daemon.WsTestClient
import com.superiorsoftllc.agenthq.daemon.awaitAfter
import com.superiorsoftllc.agenthq.daemon.mark
import com.superiorsoftllc.agenthq.daemon.moveTicket
import com.superiorsoftllc.agenthq.daemon.saveProfile
import com.superiorsoftllc.agenthq.daemon.saveTicket
import com.superiorsoftllc.agenthq.mailbox.Message
import com.superiorsoftllc.agenthq.mailbox.MailSendRequest
import com.superiorsoftllc.agenthq.mailbox.MessageState
import com.superiorsoftllc.agenthq.mailbox.ThreadSummary
import com.superiorsoftllc.agenthq.mailbox.WaitingOn
import com.superiorsoftllc.agenthq.orchestration.GatedRunner
import com.superiorsoftllc.agenthq.orchestration.PodStatus
import com.superiorsoftllc.agenthq.protocol.Envelope
import com.superiorsoftllc.agenthq.protocol.Ticket
import com.superiorsoftllc.agenthq.protocol.TicketStatus
import com.superiorsoftllc.agenthq.runner.ProcessRunner
import com.superiorsoftllc.agenthq.runner.ScriptedRunner
import com.superiorsoftllc.agenthq.runner.StubScript
import com.superiorsoftllc.agenthq.session.isAvailable
import com.superiorsoftllc.agenthq.session.isBusy
import com.superiorsoftllc.agenthq.worktree.newGitRepo
import com.superiorsoftllc.agenthq.worktree.testGit
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.File
import java.net.URI
import java.net.http.HttpClient
import java.net.http.WebSocket
import java.nio.file.Files
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Phase 5c acceptance tests for the CLI client.
 *
 * Every test boots a REAL daemon on an ephemeral port (the stub provider, so no provider CLI is
 * needed) and then runs the REAL [Cli.run] against it over a REAL WebSocket. Nothing here
 * re-implements a command: the assertions read the same code path the installed `agenthq` binary
 * runs, which is the whole point of the ticket. Asserting on frames, exit codes and the daemon's
 * own records keeps the tests from pinning incidental formatting.
 *
 * The harness ([DaemonFixture] / [WsTestClient]) is the Phase 5b one, reused rather than forked.
 */
class CliAcceptanceTest {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val fixtures = mutableListOf<DaemonFixture>()

    private fun launch(
        script: StubScript = StubScript.default(),
        runner: ProcessRunner? = null,
        /** BRANCH-PER-TASK 2/3 (step C): the repository a ticket start cuts its branch from; null = none. */
        repo: File? = null,
    ): DaemonFixture =
        DaemonFixture.launch(
            script,
            runner,
            Files.createTempDirectory("agenthq-cli-test").toFile(),
            repo = repo,
        ).also { fixtures += it }

    @AfterTest
    fun stopAll() {
        fixtures.asReversed().forEach {
            runCatching { it.stop() }
            runCatching { it.deleteState() }
        }
        fixtures.clear()
    }

    /** The CLI's stdout and stderr of one invocation, plus its exit code. */
    private data class Result(val exit: Int, val out: String, val err: String)

    /**
     * The CLI runs with [CliTestEnv], NOT with the caller's environment: `tickets` without
     * `--project` shows the project `AGENTHQ_PROJECT_ID` names, so an inherited one would scope
     * every board read to the caller's project instead of the fixture's.
     */
    private fun cli(port: Int, vararg args: String): Result {
        val out = StringBuilder()
        val err = StringBuilder()
        val exit = Cli.run(arrayOf(*args, "--port", port.toString()), out, err, CliTestEnv.getenv)
        return Result(exit, out.toString(), err.toString())
    }

    /** Like [cli] but injects [env] as the sole source of environment variables. */
    private fun cli(port: Int, env: Map<String, String>, vararg args: String): Result {
        val out = StringBuilder()
        val err = StringBuilder()
        val getenv: (String) -> String? = { key -> env[key] }
        val exit = Cli.run(arrayOf(*args, "--port", port.toString()), out, err, getenv)
        return Result(exit, out.toString(), err.toString())
    }

    /**
     * Ask the daemon directly. Used only where a CLI output line cannot be trusted to prove what
     * the daemon actually stored -- the pod's real state, and its real run records.
     */
    private fun podStatus(port: Int, podId: String): PodStatus = runBlocking {
        val client = WsTestClient()
        val socket = HttpClient.newHttpClient()
            .newWebSocketBuilder()
            .buildAsync(URI.create("ws://127.0.0.1:$port/ws"), client)
            .join()
        try {
            client.await(Envelope.READY)
            client.send(Envelope(Envelope.POD_QUERY, podId))
            json.decodeFromString(PodStatus.serializer(), client.await(Envelope.POD).data)
        } finally {
            client.close()
            runCatching { socket.sendClose(WebSocket.NORMAL_CLOSURE, "bye").join() }
        }
    }

    private fun submitPod(port: Int, goal: String): String {
        val result = cli(port, "submit", goal)
        assertEquals(0, result.exit, "submit failed: ${result.err}")
        return result.out.trim().lineSequence().last { it.isNotBlank() }
    }

    /**
     * CLI-T1 fixture: two tickets in project A (one of them already `done`) and exactly one in
     * project B, so every filter has a ticket it must KEEP and a ticket it must DROP.
     */
    private fun seedTwoProjects(fixture: DaemonFixture) {
        fixture.client.await(Envelope.READY)
        fixture.client.saveTicket("""{"title":"A open","projectId":"A"}""")
        val done = fixture.client.saveTicket("""{"title":"A done","projectId":"A"}""")
        fixture.client.saveTicket("""{"title":"B only","projectId":"B"}""")
        fixture.client.moveTicket(done.id, TicketStatus.DONE)
    }

    /**
     * The TITLE column of every row `tickets` printed, in printed order.
     *
     * Read off the columns like this rather than with `contains`, because "the row is absent" is the
     * assertion under test: `contains("A open")` would still pass if the command printed the whole
     * board. The header and the empty-board line are not rows.
     */
    private fun ticketTitles(out: String): List<String> =
        out.lineSequence()
            .map { it.trim().trimEnd('\r') }
            .filter { it.isNotBlank() && it != "ID | STATUS | TITLE | AGENT" && it != "No tickets." }
            .map { row -> row.split(" | ").getOrNull(2) ?: error("not a ticket row: '$row'") }
            .toList()

    /** The session id `runs` reports for the pod's first run -- what `logs` is keyed by. */
    private fun firstSessionId(port: Int, podId: String): String {
        val runs = cli(port, "runs", podId)
        assertEquals(0, runs.exit, "runs failed: ${runs.err}")
        val sessionId = Regex("session=([0-9a-fA-F-]+)").find(runs.out)?.groupValues?.get(1)
        assertTrue(sessionId != null, "runs did not print a session id: ${runs.out}")
        return sessionId!!
    }

    // =========================================================================
    // ACCEPTANCE TESTS
    // =========================================================================

    /**
     * 1. `submit` against a live daemon returns a pod id a later command can find.
     * Contract 1.1.0 has no pod-list frame, so the pod is read back with `runs`, which queries it.
     */
    @Test
    fun submitReturnsPodIdThatAppearsInPods() {
        val fixture = launch(StubScript.default())
        val podId = submitPod(fixture.port, "test goal for pod")
        assertTrue(podId.isNotBlank(), "submit printed no pod id")

        val runs = cli(fixture.port, "runs", podId)
        assertEquals(0, runs.exit, "runs failed: ${runs.err}")
        assertTrue(runs.out.contains(podId), "runs did not report the submitted pod: ${runs.out}")

        // And the daemon really holds that pod, with the goal we asked for.
        val status = podStatus(fixture.port, podId)
        assertEquals(podId, status.pod.id)
        assertEquals("test goal for pod", status.pod.goal)
    }

    /**
     * 2. `watch` renders the run's real streamed output and exits on its own once the pod converges.
     *
     * This is the test the earlier version of this file could not pass. It used to re-implement a
     * frame loop over the whole frame list, which kept re-reading frame zero and grew a list
     * forever -- an OutOfMemoryError that looked like a test bug and was a CLI bug, in the CLI. It
     * now runs [Cli.run], and `watch` terminates because it polls `pod_query` until the pod is
     * terminal. A watch that did not terminate would hang here rather than fail quietly.
     */
    @Test
    fun watchStreamsOutputAndExitsCleanlyWhenThePodConverges() {
        // The lines are spaced out on purpose. Frames are broadcast once, to whoever is connected
        // when they happen, and `watch` attaches only after `submit` has returned -- so a run whose
        // lines all arrive in one burst would finish streaming before `watch` ever looked.
        val script = StubScript(
            name = "watch",
            stdoutLines = listOf(
                """{"type":"step_start","sessionID":"watch-session-1"}""",
                """{"type":"text","text":"watch first streamed line"}""",
                """{"type":"text","text":"watch last streamed line"}""",
            ),
            exitCode = 0,
        )
        val fixture = launch(script, ScriptedRunner(script, delayMs = 400))
        val podId = submitPod(fixture.port, "watch test goal")

        val watch = cli(fixture.port, "watch", podId)
        assertEquals(0, watch.exit, "watch failed: ${watch.err}")

        // The run's real text came through, not a placeholder.
        assertTrue(watch.out.contains("streamed line"), "watch should render the run's text: ${watch.out}")
        // ...it saw the run finish...
        assertTrue(watch.out.contains("[finished]"), "watch missed the finished frame: ${watch.out}")
        // ...and stopped on the pod's terminal state rather than streaming forever.
        assertTrue(watch.out.contains("CONVERGED"), "watch should stop on a converged pod: ${watch.out}")
        assertTrue(
            watch.out.lineSequence().any { it.startsWith("[pod]") },
            "watch printed no pod state: ${watch.out}"
        )
    }

    /**
     * 3. A pod that does not exist fails with the daemon's own message -- not a hang, not a crash.
     * An unknown id answers the frozen `error` frame; the CLI has to pass that on, not hide it.
     */
    @Test
    fun unknownPodIdFailsCleanly() {
        val fixture = launch(StubScript.default())
        val unknownPodId = "unknown-pod-id-" + System.currentTimeMillis()

        val watch = cli(fixture.port, "watch", unknownPodId)
        assertNotEquals(0, watch.exit, "watch on an unknown pod should fail, got: ${watch.out}")
        assertTrue(watch.err.contains("unknown pod"), "error should name the unknown pod: ${watch.err}")

        val cancel = cli(fixture.port, "cancel", unknownPodId)
        assertNotEquals(0, cancel.exit, "cancel on an unknown pod should fail, got: ${cancel.out}")
        assertTrue(cancel.err.contains("unknown pod"), "error should name the unknown pod: ${cancel.err}")
    }

    /**
     * 4. `logs` returns a run's actual stdout, including a non-JSON provider line, verbatim.
     *
     * The line that is NOT JSON is the point of the test. EventNormalizer deliberately maps such a
     * line to nothing in the neutral vocabulary, so it can only ever reach a client on the raw
     * `stdout` frame; a log view built on `text_delta` alone would silently lose it.
     *
     * `logs` is a live tail (contract 1.1.0 has no replay frame), so the script's lines are emitted
     * on a run held open long enough for the tail to be attached when the run produces them.
     */
    @Test
    fun logsReturnsActualStdoutIncludingANonJsonLine() {
        val rawLine = "raw non-JSON provider chatter that only stdout carries"
        val script = StubScript(
            name = "logs",
            stdoutLines = listOf(
                """{"type":"step_start","sessionID":"logs-session-1"}""",
                """{"type":"text","text":"streamed text line"}""",
                rawLine,
                """{"type":"step_finish"}""",
            ),
            stderrLines = listOf("[logs] diagnostic on stderr"),
            exitCode = 0,
        )
        val fixture = launch(script, ScriptedRunner(script, delayMs = 3000))

        val podId = submitPod(fixture.port, "logs test goal")
        val sessionId = firstSessionId(fixture.port, podId)

        val logs = cli(fixture.port, "logs", sessionId)
        assertEquals(0, logs.exit, "logs failed: ${logs.err}")

        assertTrue(
            logs.out.contains("streamed text line"),
            "logs should carry the run's text output: ${logs.out}"
        )
        assertTrue(
            logs.out.contains(rawLine),
            "logs must carry the non-JSON line verbatim: ${logs.out}"
        )
        assertTrue(
            logs.err.contains("diagnostic on stderr"),
            "logs should carry stderr too: ${logs.err}"
        )
        assertFalse(
            logs.out.contains("has no replay"),
            "logs must not claim that retained output cannot be replayed: ${logs.out}"
        )
    }

    /**
     * 5. A daemon that is not running produces a clear, actionable error, not a stack trace or a hang.
     * Spec: exit 2 with exact message "Infantry AI daemon not running on <host>:<port> \u2014 start it with \u0060infantry daemon\u0060"
     */
    @Test
    fun missingDaemonProducesClearError() {
        // Nothing is listening on 9999, and nothing in this test ever starts a listener.
        val out = StringBuilder()
        val err = StringBuilder()
        val started = System.currentTimeMillis()
        val exit = Cli.run(arrayOf("pods", "--port", "9999"), out, err, CliTestEnv.getenv)
        val elapsed = System.currentTimeMillis() - started

        assertEquals(2, exit, "expected exit 2 for unreachable daemon, got exit $exit with err: $err")
        val message = err.toString().trim()
        val expected = "error: Infantry AI daemon not running on 127.0.0.1:9999 \u2014 start it with \u0060infantry daemon\u0060"
        assertEquals(expected, message, "exact message mismatch at CliAcceptanceTest.kt:246")
        assertTrue(elapsed < 15_000, "connecting took ${elapsed}ms -- it should fail fast")
        assertTrue(
            "\tat " !in message,
            "error should be a plain line, not a stack trace: $message",
        )
    }

    /**
     * 6. The pod's structure is what the ticket asks for: one node, RUNNING while the run is held,
     * one attempt allowed, one run at a time.
     */
    @Test
    fun podStructureIsCorrectAfterSubmit() {
        val fixture = launch(runner = GatedRunner(holdMs = 5_000))
        val podId = submitPod(fixture.port, "thread structure test")

        val status = podStatus(fixture.port, podId)
        assertEquals(podId, status.pod.id)
        assertEquals("thread structure test", status.pod.goal)
        assertEquals("RUNNING", status.pod.state)
        assertEquals(1, status.pod.maxConcurrency)
        assertEquals(1, status.nodes.size)
        assertEquals("main", status.nodes.first().name)
        assertEquals(1, status.nodes.first().maxAttempts)
    }

    /** 7. `cancel` reports the cancelled state, and the daemon really cancelled it. */
    @Test
    fun cancelReturnsCancelledPodState() {
        val fixture = launch(runner = GatedRunner(holdMs = 5_000))
        val podId = submitPod(fixture.port, "cancel test goal")

        val cancel = cli(fixture.port, "cancel", podId)
        assertEquals(0, cancel.exit, "cancel failed: ${cancel.err}")
        assertTrue(
            cancel.out.contains("CANCELLED") || cancel.out.contains("CANCELLING"),
            "cancel should report the new state, got: ${cancel.out}"
        )

        val status = podStatus(fixture.port, podId)
        assertEquals(podId, status.pod.id)
        assertTrue(
            status.pod.state == "CANCELLED" || status.pod.state == "CANCELLING",
            "pod should be cancelled or cancelling, got ${status.pod.state}"
        )
    }

    /**
     * 8. `pods` says what it is. Contract 1.1.0 has no pod-list frame, so this must not pretend the
     * session list is a pod list.
     */
    @Test
    fun podsIsExplicitThatItIsNotAPodList() {
        val fixture = launch(StubScript.default())
        submitPod(fixture.port, "a pod exists here")

        val pods = cli(fixture.port, "pods")
        assertEquals(0, pods.exit, "pods failed: ${pods.err}")
        assertTrue(pods.out.contains("SESSIONS:"), "unexpected output: ${pods.out}")
        assertTrue(
            pods.out.contains("not in contract"),
            "pods must be explicit that the contract has no pod list: ${pods.out}"
        )
    }

    /** 9. Usage errors are usage errors: exit 2, an explanation, and no daemon contacted. */
    @Test
    fun usageErrorsExitTwoWithoutConnecting() {
        val cases = listOf(
            Triple(arrayOf<String>(), "no command given", "no command"),
            Triple(arrayOf("nonsense"), "unknown command", "unknown command"),
            Triple(arrayOf("submit"), "submit requires a goal", "submit requires a goal"),
            Triple(arrayOf("watch"), "watch requires a pod id", "watch requires a pod id"),
            Triple(arrayOf("runs"), "runs requires a pod id", "runs requires a pod id"),
            Triple(arrayOf("logs"), "logs requires a session id", "logs requires a session id"),
            Triple(arrayOf("cancel"), "cancel requires a pod id", "cancel requires a pod id"),
            Triple(arrayOf("pods", "--port"), "--port expects a number", "--port expects a number"),
            Triple(arrayOf("pods", "--nope"), "unknown flag", "unknown flag"),
            Triple(arrayOf("pods", "--host"), "--host expects a value", "--host expects a value"),
        )
        for ((args, _, expected) in cases) {
            // Port 9999 is dead on purpose: reaching it would be a bug, and the connection error
            // is a different message, so the assertion below catches that.
            val out = StringBuilder()
            val err = StringBuilder()
            val exit = Cli.run(arrayOf(*args, "--port", "9999"), out, err, CliTestEnv.getenv)
            assertEquals(2, exit, "args ${args.toList()} should be a usage error, err: $err")
            assertTrue(
                err.contains(expected),
                "args ${args.toList()} should say '$expected', got: $err",
            )
        }
    }

    /** 10. `--help` is a success, and documents every command the ticket asks for. */
    @Test
    fun helpListsEveryCommand() {
        val out = StringBuilder()
        assertEquals(0, Cli.run(arrayOf("--help"), out, StringBuilder(), CliTestEnv.getenv))
        val help = out.toString()
        for (command in listOf("submit", "watch", "pods", "runs", "logs", "cancel")) {
            assertTrue(help.contains(command), "help does not mention '$command': $help")
        }
    }

    /**
     * 11. A pod that fails still reaches a terminal state, and `watch` says how it ended rather than
     * exiting silently -- a failure that looks like success is the worst outcome a CLI can have.
     */
    @Test
    fun watchReportsAFailedRunAndStillExits() {
        val fixture = launch(StubScript.failing())
        val podId = submitPod(fixture.port, "FAIL_ALWAYS please")

        val watch = cli(fixture.port, "watch", podId)
        assertEquals(0, watch.exit, "watch should end cleanly on a failed pod: ${watch.err}")
        assertTrue(
            watch.err.contains("scripted failure") || watch.out.contains("FAILED"),
            "watch should surface the failure: ${watch.out} / ${watch.err}"
        )
        assertTrue(
            watch.out.contains("FAILED"),
            "the pod's terminal state should be reported: ${watch.out}"
        )
    }

    /**
     * 12. `tickets` lists tickets from the daemon.
     */
    @Test
    fun ticketsListsTicketsFromDaemon() {
        val fixture = launch(StubScript.default())
        // Create tickets via the daemon directly
        fixture.client.await(Envelope.READY)
        fixture.send(Envelope(Envelope.TICKET_SAVE, """{"title":"Test Ticket 1","ticketType":"enhancement"}"""))
        fixture.client.await(Envelope.TICKET)
        fixture.send(Envelope(Envelope.TICKET_SAVE, """{"title":"Test Ticket 2","ticketType":"defect"}"""))
        fixture.client.await(Envelope.TICKET)

        val tickets = cli(fixture.port, "tickets")
        assertEquals(0, tickets.exit, "tickets failed: ${tickets.err}")
        assertTrue(tickets.out.contains("Test Ticket 1"), "tickets should list first ticket: ${tickets.out}")
        assertTrue(tickets.out.contains("Test Ticket 2"), "tickets should list second ticket: ${tickets.out}")
        assertTrue(tickets.out.contains("todo"), "tickets should show status: ${tickets.out}")
    }

    /**
     * 12b. CLI-T1: `tickets --project <id>` asks the daemon for ONE project's tickets -- the
     * `ticket_list` payload UI-BOARD-PROJ added -- instead of printing every project's board.
     */
    @Test
    fun ticketsWithProjectShowsOnlyThatProjectsTickets() {
        val fixture = launch(StubScript.default())
        seedTwoProjects(fixture)

        val tickets = cli(fixture.port, "tickets", "--project", "A")
        assertEquals(0, tickets.exit, "tickets --project failed: ${tickets.err}")
        assertFalse(tickets.out.contains("B only"), "project B's ticket leaked into --project A: ${tickets.out}")
        assertEquals(
            listOf("A open", "A done"),
            ticketTitles(tickets.out),
            "tickets --project A must print project A's two tickets and no other: ${tickets.out}",
        )
    }

    /**
     * 12c. CLI-T1: `tickets --status <column>` keeps only the tickets in that column, and combines
     * with `--project` rather than replacing it.
     */
    @Test
    fun ticketsWithStatusShowsOnlyThatColumnsTickets() {
        val fixture = launch(StubScript.default())
        seedTwoProjects(fixture)

        val done = cli(fixture.port, "tickets", "--project", "A", "--status", "done")
        assertEquals(0, done.exit, "tickets --status failed: ${done.err}")
        assertEquals(
            listOf("A done"),
            ticketTitles(done.out),
            "--project A --status done must print the one done ticket of A: ${done.out}",
        )

        val todo = cli(fixture.port, "tickets", "--status", "todo")
        assertEquals(0, todo.exit, "tickets --status todo failed: ${todo.err}")
        assertEquals(
            listOf("A open", "B only"),
            ticketTitles(todo.out),
            "--status todo must keep the todo tickets of every project it lists: ${todo.out}",
        )
    }

    /**
     * 12d. CLI-T1: a column the board does not have is a USAGE error -- exit 2, naming the columns
     * that exist. Pinned against a dead port on purpose: the refusal must happen BEFORE any
     * connection, so the error names the allowed statuses and not an unreachable daemon.
     */
    @Test
    fun ticketsWithUnknownStatusIsAUsageError() {
        val out = StringBuilder()
        val err = StringBuilder()
        val exit = Cli.run(arrayOf("tickets", "--status", "bogus", "--port", "9999"), out, err, CliTestEnv.getenv)
        assertEquals(2, exit, "an unknown status must be exit 2: $err")
        assertEquals(
            "error: unknown status 'bogus'; expected one of todo, in_progress, pending, done",
            err.toString().trim(),
            "the refusal must name every allowed column",
        )
        assertFalse(
            err.toString().contains("daemon not running"),
            "a refused --status must not contact the daemon: $err",
        )
        assertEquals("", out.toString(), "a refused usage prints no listing")
    }

    /**
     * 12e. CLI-T1: inside a spawned agent there is no `--project` to type -- the daemon exports
     * AGENTHQ_PROJECT_ID into that agent's environment -- so plain `tickets` is that agent's own
     * project's board.
     */
    @Test
    fun ticketsDefaultsProjectFromTheAgentEnvironment() {
        val fixture = launch(StubScript.default())
        seedTwoProjects(fixture)

        val tickets = cli(fixture.port, mapOf(AccessPolicy.PROJECT_ID_ENV to "A"), "tickets")
        assertEquals(0, tickets.exit, "tickets with AGENTHQ_PROJECT_ID failed: ${tickets.err}")
        assertFalse(tickets.out.contains("B only"), "project B's ticket leaked through the env default: ${tickets.out}")
        assertEquals(
            listOf("A open", "A done"),
            ticketTitles(tickets.out),
            "AGENTHQ_PROJECT_ID must scope plain `tickets` to that project: ${tickets.out}",
        )
    }

    /**
     * BRANCH-PER-TASK 2/3 (step C): `ticket show` of a STARTED ticket prints the branch its start cut and the
     * commit it was cut from, read from the daemon's answer; nothing is re-derived in the CLI.
     */
    @Test
    fun ticketShowPrintsTheBranchAndBaseOfAStartedTicket() {
        val repo = newGitRepo()
        val fixture = launch(repo = repo)
        fixture.client.await(Envelope.READY)
        fixture.client.saveProfile("""{"id":"backend","name":"backend","role":"backend"}""")
        val ticket = fixture.client.saveTicket("""{"title":"Ship the branch","agentId":"backend"}""")
        val started = fixture.client.moveTicket(ticket.id, TicketStatus.IN_PROGRESS)
        assertEquals("started", started.start!!.reason, "the start must succeed: ${started.start}")
        val base = testGit().run(repo, listOf("rev-parse", "HEAD")).out()

        val show = cli(fixture.port, "ticket", "show", ticket.id.takeLast(8))
        assertEquals(0, show.exit, "ticket show failed: ${show.err}")
        assertTrue(show.out.contains("BRANCH: ${started.branch}\n"), "ticket show must print the branch: ${show.out}")
        assertTrue(show.out.contains("BASE: $base\n"), "ticket show must print the base commit: ${show.out}")
    }

    /** BRANCH-PER-TASK 2/3 (step C): a ticket never started shows `-` for both, the same as the other empty fields. */
    @Test
    fun ticketShowPrintsDashesForABranchAndBaseTheTicketDoesNotHave() {
        val fixture = launch()
        fixture.client.await(Envelope.READY)
        val ticket = saveTicketTitled(fixture, "Never started", """{"title":"Never started","projectId":"P"}""")

        val show = cli(fixture.port, "ticket", "show", ticket.id.takeLast(8))
        assertEquals(0, show.exit, "ticket show failed: ${show.err}")
        assertTrue(show.out.contains("BRANCH: -\n"), "an unstarted ticket shows BRANCH: -: ${show.out}")
        assertTrue(show.out.contains("BASE: -\n"), "an unstarted ticket shows BASE: -: ${show.out}")
    }

    /**
     * CLI-T2: `ticket show <id|prefix>` prints ONE ticket in full -- its title AND its whole
     * multi-line description, not the one-line row `tickets` prints. The id is the 8-char prefix
     * `tickets` prints; the helper that resolves it is shared with the later ticket commands.
     */
    @Test
    fun ticketShowPrintsTheTitleAndTheFullMultiLineDescription() {
        val fixture = launch(StubScript.default())
        fixture.client.await(Envelope.READY)
        val description = "first line of the brief\nsecond line, with detail\n\nfourth line after a blank"
        val ticket = saveTicketTitled(
            fixture,
            "Show the whole brief",
            """{"title":"Show the whole brief","projectId":"P","description":${json.encodeToString(kotlinx.serialization.serializer<String>(), description)}}""",
        )

        val show = cli(fixture.port, "ticket", "show", ticket.id.takeLast(8))
        assertEquals(0, show.exit, "ticket show failed: ${show.err}")
        assertTrue(show.out.contains("Show the whole brief"), "ticket show must print the title: ${show.out}")
        assertTrue(
            show.out.contains(description),
            "ticket show must print the FULL multi-line description, verbatim: ${show.out}",
        )
        assertEquals("", show.err, "a successful show writes nothing to stderr")
    }

    /**
     * CLI-T2: a prefix that two tickets share is AMBIGUOUS, so `ticket show` refuses with exit 1 and
     * lists BOTH candidates -- it must never silently pick one and show the wrong brief.
     *
     * Ticket ids are `ticket-` plus eight random hex characters, and `tickets` prints the LAST 8
     * characters. A ONE-character suffix has only 16 distinct values, so seventeen saves force a shared
     * one by pigeonhole, so the collision is guaranteed, not lucky. The
     * test does not care WHICH two ids collide. The pinned-id case is covered deterministically by
     * `TicketPrefixTest`, which needs no daemon.
     */
    @Test
    fun ticketShowRefusesAnAmbiguousPrefixAndListsEveryMatch() {
        val fixture = launch(StubScript.default())
        fixture.client.await(Envelope.READY)
        val saved = (0..16).map { saveTicketTitled(fixture, "Collide $it", """{"title":"Collide $it"}""") }
        val shared = saved.groupBy { it.id.takeLast(1) }.entries.first { it.value.size >= 2 }
        val (prefix, colliding) = shared.key to shared.value.take(2)

        val show = cli(fixture.port, "ticket", "show", prefix)
        assertEquals(1, show.exit, "an ambiguous prefix must exit 1: out=${show.out} err=${show.err}")
        assertEquals("", show.out, "an ambiguous prefix must not print any ticket")
        for (match in colliding) {
            assertTrue(
                show.err.contains(match.id) && show.err.contains(match.title),
                "the ambiguity error must list every match, '${match.id}' / '${match.title}': ${show.err}",
            )
        }
    }

    /** CLI-T2: a prefix that matches no ticket exits 1 saying which prefix it was. */
    @Test
    fun ticketShowWithAnUnknownPrefixExitsOneNamingIt() {
        val fixture = launch(StubScript.default())
        fixture.client.await(Envelope.READY)
        saveTicketTitled(fixture, "Only ticket", """{"title":"Only ticket"}""")

        val show = cli(fixture.port, "ticket", "show", "nosuchtk")
        assertEquals(1, show.exit, "an unknown prefix must exit 1: ${show.err}")
        assertTrue(show.err.contains("no ticket nosuchtk"), "the error must say no ticket matched: ${show.err}")
        assertEquals("", show.out, "nothing is printed for an unknown prefix")
    }

    /** CLI-T4: `ticket move <id> <status>` moves the ticket; `tickets --status <status>` then lists it. */
    @Test
    fun ticketMoveThenTicketsStatusListsIt() {
        val fixture = launch(StubScript.default())
        fixture.client.await(Envelope.READY)
        val ticket = saveTicketTitled(fixture, "Move me to done", """{"title":"Move me to done"}""")

        val move = cli(fixture.port, "ticket", "move", ticket.id.takeLast(8), "done")
        assertEquals(0, move.exit, "ticket move failed: ${move.err}")
        assertEquals("moved ${ticket.id} -> done", move.out.trim(), "ticket move must print the moved line")

        val done = cli(fixture.port, "tickets", "--status", "done")
        assertEquals(0, done.exit, "tickets --status done failed: ${done.err}")
        assertEquals(
            listOf("Move me to done"),
            ticketTitles(done.out),
            "tickets --status done must list the moved ticket: ${done.out}",
        )
    }

    /** Ticket order is priority: `ticket move` takes --top, --bottom and --before, and `tickets` lists the new order. */
    @Test
    fun ticketMoveReordersWithTopBottomAndBefore() {
        val fixture = launch(StubScript.default())
        fixture.client.await(Envelope.READY)
        val ids = listOf("First", "Second", "Third").map { saveTicketTitled(fixture, it, """{"title":"$it"}""").id.takeLast(8) }
        fun order() = ticketTitles(cli(fixture.port, "tickets", "--status", "todo").out)

        assertEquals(listOf("First", "Second", "Third"), order(), "new tickets append to the bottom")
        assertEquals(0, cli(fixture.port, "ticket", "move", ids[2], "--top").exit)
        assertEquals(listOf("Third", "First", "Second"), order())
        assertEquals(0, cli(fixture.port, "ticket", "move", ids[2], "--bottom").exit)
        assertEquals(listOf("First", "Second", "Third"), order())
        assertEquals(0, cli(fixture.port, "ticket", "move", ids[0], "--before", ids[2]).exit)
        assertEquals(listOf("Second", "First", "Third"), order(), "moving down lands just above the target")
        assertEquals(0, cli(fixture.port, "ticket", "move", ids[2], "todo", "--before", ids[1]).exit)
        assertEquals(listOf("Third", "Second", "First"), order(), "moving up lands just above the target")

        val self = cli(fixture.port, "ticket", "move", ids[0], "--before", ids[0])
        assertEquals(1, self.exit, "before itself is refused: ${self.out}")
        val two = cli(fixture.port, "ticket", "move", ids[0], "--top", "--bottom")
        assertEquals(2, two.exit, "two placements are a usage error: ${two.err}")
    }

    /** CLI-T4: an unknown status is a usage error (exit 2), refused before the daemon is asked to move anything. */
    @Test
    fun ticketMoveWithUnknownStatusIsAUsageError() {
        val fixture = launch(StubScript.default())
        fixture.client.await(Envelope.READY)
        val ticket = saveTicketTitled(fixture, "Stay put", """{"title":"Stay put"}""")

        val move = cli(fixture.port, "ticket", "move", ticket.id.takeLast(8), "bogus")
        assertEquals(2, move.exit, "an unknown status must exit 2: out=${move.out} err=${move.err}")
        assertTrue(move.err.contains("bogus"), "the error must name the bad status: ${move.err}")
        assertEquals("", move.out, "nothing is printed as moved for an unknown status")
    }

    /** The `tickets` table prints the id SUFFIX (last 8 chars), and that printed id resolves back to the ticket. */
    @Test
    fun ticketsPrintsTheIdSuffixAndItResolves() {
        val fixture = launch(StubScript.default())
        fixture.client.await(Envelope.READY)
        val t = saveTicketTitled(fixture, "Suffix shown", """{"title":"Suffix shown"}""")

        val list = cli(fixture.port, "tickets")
        assertEquals(0, list.exit, "tickets failed: ${list.err}")
        val line = list.out.lineSequence().first { it.contains("Suffix shown") }
        assertTrue(line.startsWith(t.id.takeLast(8) + " |"), "tickets must print the LAST 8 id chars, not the first: $line")
        val show = cli(fixture.port, "ticket", "show", t.id.takeLast(8))
        assertEquals(0, show.exit, "the printed suffix must resolve: ${show.err}")
    }

    /** CLI-T4: `ticket link A B blocks` links A to B; `ticket show A` then lists the link to B. */
    @Test
    fun ticketLinkBlocksThenShowListsTheLink() {
        val fixture = launch(StubScript.default())
        fixture.client.await(Envelope.READY)
        val a = saveTicketTitled(fixture, "Link source A", """{"title":"Link source A"}""")
        val b = saveTicketTitled(fixture, "Link target B", """{"title":"Link target B"}""")

        // Full ids, not the 8-char prefix: two random ids share that prefix 1 time in 16, and this test
        // must not depend on that luck. A full id is unique on the board and matches only its own ticket.
        val link = cli(fixture.port, "ticket", "link", a.id, b.id, "blocks")
        assertEquals(0, link.exit, "ticket link failed: ${link.err}")

        val show = cli(fixture.port, "ticket", "show", a.id)
        assertEquals(0, show.exit, "ticket show failed: ${show.err}")
        assertTrue(
            show.out.contains("blocks ${b.id}"),
            "ticket show A must list the link to B: ${show.out}",
        )
    }

    /** CLI-T4: an unknown link type is a usage error (exit 2) that lists the types the daemon accepts. */
    @Test
    fun ticketLinkWithUnknownTypeIsAUsageErrorListingTheTypes() {
        val fixture = launch(StubScript.default())
        fixture.client.await(Envelope.READY)
        val a = saveTicketTitled(fixture, "Typo A", """{"title":"Typo A"}""")
        val b = saveTicketTitled(fixture, "Typo B", """{"title":"Typo B"}""")

        val link = cli(fixture.port, "ticket", "link", a.id, b.id, "bogus")
        assertEquals(2, link.exit, "an unknown link type must exit 2: out=${link.out} err=${link.err}")
        assertTrue(
            link.err.contains("blocked_by") && link.err.contains("child_of"),
            "the error must list the accepted link types: ${link.err}",
        )
    }

    /**
     * CLI-T3: `ticket create --title X --desc Y --project A` saves a todo ticket in project A and prints
     * `created <8-char id>`. The printed id is read back with `ticket show`, which must show the title,
     * the description and the project the create asked for.
     */
    @Test
    fun ticketCreatePrintsTheNewIdAndTheSavedTicketShowsWhatWasAsked() {
        val fixture = launch(StubScript.default())
        fixture.client.await(Envelope.READY)

        val created = cli(fixture.port, "ticket", "create", "--title", "X", "--desc", "Y", "--project", "A")
        assertEquals(0, created.exit, "ticket create failed: ${created.err}")
        assertEquals("", created.err, "a successful create writes nothing to stderr")
        val printedId = created.out.trim().removePrefix("created ")
        assertTrue(created.out.startsWith("created "), "ticket create must print 'created <id>': ${created.out}")
        assertEquals(8, printedId.length, "the printed id must be the 8-char prefix: '${created.out}'")

        val show = cli(fixture.port, "ticket", "show", printedId)
        assertEquals(0, show.exit, "ticket show of the created ticket failed: ${show.err}")
        assertTrue(show.out.contains("TITLE: X"), "the created ticket must have the title X: ${show.out}")
        assertTrue(show.out.contains("STATUS: todo"), "a created ticket starts in todo: ${show.out}")
        assertTrue(show.out.contains("PROJECT: A"), "the created ticket must belong to project A: ${show.out}")
        assertTrue(show.out.contains("DESCRIPTION:\nY\n"), "the created ticket must carry description Y: ${show.out}")
    }

    /** CLI-T3: `ticket create` without `--title`, or with a blank one, is a usage error (exit 2) and saves nothing. */
    @Test
    fun ticketCreateWithoutATitleIsAUsageError() {
        val fixture = launch(StubScript.default())
        fixture.client.await(Envelope.READY)

        val missing = cli(fixture.port, "ticket", "create", "--desc", "Y")
        assertEquals(2, missing.exit, "ticket create without --title must exit 2: out=${missing.out} err=${missing.err}")
        assertTrue(missing.err.contains("--title"), "the usage error must name --title: ${missing.err}")

        val blank = cli(fixture.port, "ticket", "create", "--title", "   ")
        assertEquals(2, blank.exit, "a blank --title must exit 2: out=${blank.out} err=${blank.err}")
        assertEquals("", blank.out, "a refused create prints no id")
    }

    /**
     * CLI-T3 patch (675e7c4c): `ticket create --epic <prefix>` saves that epic on the ticket, and `ticket show`
     * of the printed id names it. The epic is a real one under a real project (epic_save refuses an unknown
     * project), so the id asserted is the one the daemon minted.
     */
    @Test
    fun ticketCreateWithAnEpicSavesThatEpicOnTheTicket() {
        val fixture = launch(StubScript.default())
        fixture.client.await(Envelope.READY)
        val projectId = projectNamed(fixture, "Epics")
        val epicId = epicIn(fixture, projectId, "Epic One")

        val created = cli(fixture.port, "ticket", "create", "--title", "X", "--project", projectId, "--epic", epicId.takeLast(8))
        assertEquals(0, created.exit, "ticket create --epic <prefix> failed: ${created.err}")
        val printedId = created.out.trim().removePrefix("created ")

        val show = cli(fixture.port, "ticket", "show", printedId)
        assertEquals(0, show.exit, "ticket show of the created ticket failed: ${show.err}")
        assertTrue(show.out.contains("EPIC: $epicId\n"), "the created ticket must carry epic $epicId: ${show.out}")
    }

    /** CLI-T3 patch: an epic prefix two epics share is refused with exit 2, and both matches are listed. */
    @Test
    fun ticketCreateRefusesAnAmbiguousEpicPrefixAndListsEveryMatch() {
        val fixture = launch(StubScript.default())
        fixture.client.await(Envelope.READY)
        val projectId = projectNamed(fixture, "Epics")
        // Epics print the LAST 8 id characters; a one-character suffix has 16 values, so 17 epics force a
        // shared suffix by pigeonhole, whichever ids the daemon happens to draw.
        val epics = (0..16).map { epicIn(fixture, projectId, "Epic$it") }
        val group = epics.groupBy { it.takeLast(1) }.entries.first { it.value.size >= 2 }
        val shared = group.key
        val (first, second) = group.value.take(2)

        val result = cli(fixture.port, "ticket", "create", "--title", "X", "--project", projectId, "--epic", shared)
        assertEquals(2, result.exit, "an ambiguous epic prefix must exit 2: out=${result.out} err=${result.err}")
        assertTrue(result.err.contains(first) && result.err.contains(second), "the refusal must list both matches: ${result.err}")
        assertEquals("", result.out, "a refused create prints no id")
    }

    /** CLI-T3 patch: an epic prefix that matches no epic is a usage error (exit 2) that names the prefix. */
    @Test
    fun ticketCreateRefusesAnEpicPrefixThatMatchesNothingWithExit2() {
        val fixture = launch(StubScript.default())
        fixture.client.await(Envelope.READY)
        val projectId = projectNamed(fixture, "Epics")
        epicIn(fixture, projectId, "One")

        val result = cli(fixture.port, "ticket", "create", "--title", "X", "--project", projectId, "--epic", "nosuchep")
        assertEquals(2, result.exit, "an epic prefix matching nothing must exit 2: out=${result.out} err=${result.err}")
        assertTrue(result.err.contains("nosuchep"), "the usage error must name the prefix it looked for: ${result.err}")
        assertEquals("", result.out, "a refused create prints no id")
    }

    /**
     * CLI-T5: `epic show <prefix>` prints the epic's title, then exactly ITS OWN stories as `ID | STATUS | TITLE`.
     * The second epic's story lives in the same project, so listing every story of the project would make
     * the count three, not two.
     */
    @Test
    fun epicShowListsExactlyTheStoriesOfThatEpic() {
        val fixture = launch(StubScript.default())
        fixture.client.await(Envelope.READY)
        val projectId = projectNamed(fixture, "A")
        val first = epicIn(fixture, projectId, "First")
        val other = epicIn(fixture, projectId, "Other")
        storyIn(fixture, projectId, first, "alpha")
        storyIn(fixture, projectId, first, "beta")
        storyIn(fixture, projectId, other, "gamma")

        val show = cli(fixture.port, "epic", "show", first.takeLast(8), "--project", projectId)
        assertEquals(0, show.exit, "epic show failed: out=${show.out} err=${show.err}")
        assertTrue(show.out.contains("TITLE: First\n"), "epic show must print the epic's title: ${show.out}")
        assertEquals(listOf("alpha", "beta"), storyRowTitles(show.out), "epic show must list exactly the epic's 2 stories: ${show.out}")
    }

    /** CLI-T5: `epic create --title` prints `created <id>`, and that id shows the new epic with 0 stories. */
    @Test
    fun epicCreatePrintsTheNewIdAndAFreshEpicShowsNoStories() {
        val fixture = launch(StubScript.default())
        fixture.client.await(Envelope.READY)
        val projectId = projectNamed(fixture, "A")

        val created = cli(fixture.port, "epic", "create", "--title", "E", "--project", projectId)
        assertEquals(0, created.exit, "epic create failed: out=${created.out} err=${created.err}")
        assertEquals("", created.err, "a successful epic create writes nothing to stderr")
        assertTrue(created.out.startsWith("created "), "epic create must print 'created <id>': ${created.out}")
        val printedId = created.out.trim().removePrefix("created ")
        assertEquals(8, printedId.length, "the printed id must be the 8-char prefix: '${created.out}'")

        val show = cli(fixture.port, "epic", "show", printedId, "--project", projectId)
        assertEquals(0, show.exit, "epic show of the created epic failed: ${show.err}")
        assertTrue(show.out.contains("TITLE: E\n"), "the created epic must have the title E: ${show.out}")
        assertEquals(emptyList<String>(), storyRowTitles(show.out), "a fresh epic has no stories: ${show.out}")
    }

    /** CLI-T5: `epic create` with a blank title is a usage error (exit 2) and saves nothing. */
    @Test
    fun epicCreateWithABlankTitleIsAUsageError() {
        val fixture = launch(StubScript.default())
        fixture.client.await(Envelope.READY)
        val projectId = projectNamed(fixture, "A")

        val blank = cli(fixture.port, "epic", "create", "--title", "   ", "--project", projectId)
        assertEquals(2, blank.exit, "a blank epic --title must exit 2: out=${blank.out} err=${blank.err}")
        assertTrue(blank.err.contains("requires a title"), "the usage error must say the title is missing, not an unknown command: ${blank.err}")
        assertEquals("", blank.out, "a refused epic create prints no id")
    }

    /** The TITLE column of every ticket row `epic show` printed, in printed order (the header is not a row; E1-e). */
    private fun storyRowTitles(out: String): List<String> =
        out.lineSequence()
            .map { it.trim().trimEnd('\r') }
            .filter { it.contains(" | ") && it != "ID | STATUS | TITLE | AGENT" }
            .map { row -> row.split(" | ")[2] }
            .toList()

    /** CLI-T5: a story under [epicId] in [projectId], returning the id the daemon minted. */
    private fun storyIn(fixture: DaemonFixture, projectId: String, epicId: String, title: String): String {
        val from = fixture.client.mark()
        fixture.client.send(Envelope(Envelope.STORY_SAVE, """{"projectId":"$projectId","title":"$title","epicId":"$epicId"}"""))
        val frame = fixture.client.awaitAfter(from, "the saved story '$title'") { env ->
            env.type == Envelope.STORY && Regex(""""title"\s*:\s*"$title""").containsMatchIn(env.data)
        }
        return Regex(""""id"\s*:\s*"([^"]+)""").find(frame.data)!!.groupValues[1]
    }

    /** CLI-T3 patch: a fresh project, returning the id the daemon minted. */
    private fun projectNamed(fixture: DaemonFixture, name: String): String {
        val from = fixture.client.mark()
        fixture.client.send(Envelope(Envelope.PROJECT_SAVE, """{"name":"$name"}"""))
        val frame = fixture.client.awaitAfter(from, "the saved project '$name'") { env ->
            env.type == Envelope.PROJECT && Regex(""""name"\s*:\s*"$name"""").containsMatchIn(env.data)
        }
        return Regex(""""id"\s*:\s*"([^"]+)"""").find(frame.data)!!.groupValues[1]
    }

    /** CLI-T3 patch: an epic under [projectId], returning the id the daemon minted. */
    private fun epicIn(fixture: DaemonFixture, projectId: String, name: String): String {
        val from = fixture.client.mark()
        fixture.client.send(Envelope(Envelope.EPIC_SAVE, """{"projectId":"$projectId","name":"$name"}"""))
        val frame = fixture.client.awaitAfter(from, "the saved epic '$name'") { env ->
            env.type == Envelope.EPIC && Regex(""""name"\s*:\s*"$name""").containsMatchIn(env.data)
        }
        return Regex(""""id"\s*:\s*"([^"]+)""").find(frame.data)!!.groupValues[1]
    }

    /**
     * 13. `agents` lists agent profiles from the daemon.
     *
     * Each save is followed by an await matched on that profile's own name in the AGENT frame the
     * daemon broadcasts AFTER it has stored the profile. `await` matches by OCCURRENCE, so a bare
     * `await(Envelope.AGENT)` for the QA save returns occurrence 0 (Backend's frame, already in the
     * buffer) instead of waiting for QA, letting the `cli("agents")` query race the save. Content
     * matching waits for the real condition, so the test passes on ordering alone with no timing
     * device. Nothing outside this file is touched: the daemon, the fixture and the harness are
     * untouched, and the store is reached only over the real WebSocket.
     */
    @Test
    fun agentsListsProfilesFromDaemon() {
        val fixture = launch(StubScript.default())
        // Create agent profiles via the daemon directly
        fixture.client.await(Envelope.READY)
        fixture.send(Envelope(Envelope.AGENT_SAVE, """{"name":"Backend","role":"backend"}"""))
        fixture.client.awaitWhere("AGENT(Backend)") { it.type == Envelope.AGENT && "\"name\":\"Backend\"" in it.data }
        fixture.send(Envelope(Envelope.AGENT_SAVE, """{"name":"QA","role":"qa"}"""))
        fixture.client.awaitWhere("AGENT(QA)") { it.type == Envelope.AGENT && "\"name\":\"QA\"" in it.data }

        val agents = cli(fixture.port, "agents")
        assertEquals(0, agents.exit, "agents failed: ${agents.err}")
        assertTrue(agents.out.contains("Backend"), "agents should list Backend: ${agents.out}")
        assertTrue(agents.out.contains("QA"), "agents should list QA: ${agents.out}")
        assertTrue(agents.out.contains("backend"), "agents should show role: ${agents.out}")
        assertTrue(agents.out.contains("qa"), "agents should show role: ${agents.out}")
    }

    /**
     * 14. Missing daemon produces clear error with exit 2 and exact message per spec.
     */
    @Test
    fun missingDaemonProducesClearErrorForTickets() {
        val out = StringBuilder()
        val err = StringBuilder()
        val exit = Cli.run(arrayOf("tickets", "--port", "9999"), out, err, CliTestEnv.getenv)
        assertEquals(2, exit, "expected exit 2 for unreachable daemon, got exit $exit with err: $err")
        val message = err.toString().trim()
        val expected = "error: Infantry AI daemon not running on 127.0.0.1:9999 \u2014 start it with \u0060infantry daemon\u0060"
        assertEquals(expected, message, "exact message mismatch at CliAcceptanceTest.kt:428")
    }

    /**
     * 15. Missing daemon produces clear error with exit 2 and exact message per spec.
     */
    @Test
    fun missingDaemonProducesClearErrorForAgents() {
        val out = StringBuilder()
        val err = StringBuilder()
        val exit = Cli.run(arrayOf("agents", "--port", "9999"), out, err, CliTestEnv.getenv)
        assertEquals(2, exit, "expected exit 2 for unreachable daemon, got exit $exit with err: $err")
        val message = err.toString().trim()
        val expected = "error: Infantry AI daemon not running on 127.0.0.1:9999 \u2014 start it with \u0060infantry daemon\u0060"
        assertEquals(expected, message, "exact message mismatch at CliAcceptanceTest.kt:443")
    }

    /**
     * 16. RED test: MUTATION - changing `return 2` to `return 1` in the catch block at
     * CliMain.kt:160 makes this test fail (exit code becomes 1 instead of 2).
     * This proves the test catches the spec requirement.
     */
    @Test
    fun missingDaemonExitCodeIsTwoNotOne() {
        val out = StringBuilder()
        val err = StringBuilder()
        val exit = Cli.run(arrayOf("tickets", "--port", "9999"), out, err, CliTestEnv.getenv)
        // This test documents the MUTATION check: if exit is 1, the test fails
        assertNotEquals(1, exit, "exit must be 2 (usage/config error), not 1 (runtime failure)")
        assertEquals(2, exit)
    }

    // =========================================================================
    // MAIL-2: `agenthq send <agentId|role> "message" [--reply-to <msgId>]`
    // =========================================================================

    /**
     * Start one ticket in [projectId] owned by [agentId], and return the session it started once
     * that session is AT REST (IDLE and resumable -- the state a queued message can wake).
     *
     * Through a TICKET on purpose, and this is the part that is easy to get wrong: role-addressed
     * mail is resolved against the SENDER's session `projectId` (MAIL-1,
     * `MailRouter.resolveRecipient`), and only a ticket start carries the ticket's project onto the
     * session. A session started on a bare `new` frame has no project at all, so `send pm` could
     * never resolve a pm bound to a real project. The fixture therefore does what production does.
     */
    /**
     * Save a ticket and return THE STORED TICKET, matched on its own [title].
     *
     * Deliberately not the harness's [saveTicket]: that one waits for "the next `ticket` frame",
     * and the daemon pushes a ticket AGAIN on every session status event of a start
     * (`TicketBoard.onSessionEvent`). A later save can therefore read an EARLIER ticket's push and
     * hand back the wrong ticket -- which then makes its `ticket_move` a no-op with no start outcome.
     * A title that is unique in the fixture is what makes this wait precise.
     */
    private fun saveTicketTitled(fixture: DaemonFixture, title: String, body: String): Ticket {
        val from = fixture.client.mark()
        fixture.client.send(Envelope(Envelope.TICKET_SAVE, body))
        val frame = fixture.client.awaitAfter(from, "the stored ticket '$title'") { env ->
            env.type == Envelope.TICKET &&
                runCatching { json.decodeFromString(Ticket.serializer(), env.data) }.getOrNull()
                    ?.let { it.title == title } == true
        }
        return json.decodeFromString(Ticket.serializer(), frame.data)
    }

    private fun startTicketSession(
        fixture: DaemonFixture,
        projectId: String,
        agentId: String,
        title: String,
    ): String {
        val ticket = saveTicketTitled(
            fixture,
            title,
            """{"title":"$title","agentId":"$agentId","projectId":"$projectId","useWorktree":false}""",
        )
        // Wait for THE FRAME CARRYING THE START OUTCOME, not merely for "a ticket frame for this id":
        // the daemon ALSO pushes the ticket when the start's session raises its first status event
        // (`TicketBoard.onSessionEvent`), and that copy carries no `start`. A looser wait can read
        // that second push and conclude the start answered nothing.
        val from = fixture.client.mark()
        fixture.client.send(
            Envelope(
                Envelope.TICKET_MOVE,
                """{"id":"${ticket.id}","status":"${TicketStatus.IN_PROGRESS}"}""",
            ),
        )
        val frame = fixture.client.awaitAfter(from, "the start outcome for ticket '${ticket.id}'") { env ->
            env.type == Envelope.TICKET &&
                runCatching { json.decodeFromString(Ticket.serializer(), env.data) }.getOrNull()
                    ?.let { it.id == ticket.id && it.start != null } == true
        }
        val started = json.decodeFromString(Ticket.serializer(), frame.data)
        val start = assertNotNull(started.start, "a start answers with a `start` outcome, got $started")
        assertTrue(start.ok, "the start of '$title' must succeed: $start")
        val sessionId = assertNotNull(started.sessionId, "the ticket records the session it started")
        fixture.await(what = "the session started for '$title' to come to rest") {
            fixture.daemon.manager.get(sessionId)?.isAvailable() == true
        }
        return sessionId
    }

    /**
     * The messages ADDRESSED to [who], read over the real socket (`mail_inbox` -> `mails`).
     *
     * Read off the daemon rather than assumed from the CLI's own line, because "the pm's mailbox
     * holds it" is exactly the claim under test: a `send` that printed the right thing and stored
     * nothing would satisfy an assertion on stdout alone.
     */
    private fun inboxOf(fixture: DaemonFixture, who: String): List<Message> {
        val from = fixture.client.mark()
        fixture.client.send(Envelope(Envelope.MAIL_INBOX, who))
        val frame = fixture.client.awaitAfter(from, "the inbox of '$who'") { it.type == Envelope.MAILS }
        return json.decodeFromString(ListSerializer(Message.serializer()), frame.data)
    }

    /**
     * 17. MAIL-2: `send pm "hi"` from a session in the pm's project reaches a live, at-rest pm
     * (started from a ticket, as in production), and prints the id of the message the daemon stored.
     */
    @Test
    fun sendToARoleReachesAnAtRestPmStartedFromATicket() {
        // echoPrompt makes the stub replay the prompt it was handed as a `text` line, which is how
        // this test can also prove the pm's CONSOLE received the message, not just the mailbox.
        val fixture = launch(StubScript.default().copy(echoPrompt = true))
        fixture.client.await(Envelope.READY)
        fixture.client.saveProfile("""{"id":"pm-1","name":"PM","role":"pm"}""")
        fixture.client.saveProfile("""{"id":"dev-1","name":"Dev","role":"dev"}""")
        val pmSession = startTicketSession(fixture, "proj-a", "pm-1", "PM work")
        val senderSession = startTicketSession(fixture, "proj-a", "dev-1", "Dev work")

        val sent = cli(fixture.port, mapOf(AccessPolicy.SESSION_ID_ENV to senderSession), "send", "pm", "hi")
        assertEquals(0, sent.exit, "send to a live pm must succeed: out=${sent.out} err=${sent.err}")

        val inbox = inboxOf(fixture, "pm")
        assertEquals(1, inbox.size, "the pm's mailbox must hold exactly the message just sent: $inbox")
        val stored = inbox.single()
        assertEquals("hi", stored.body, "the pm's mailbox must hold the message text")
        assertEquals(
            MessageState.DELIVERED,
            stored.state,
            "an at-rest pm's console must have been woken, not just queued: $stored",
        )
        assertTrue(
            sent.out.contains("sent ${stored.id}"),
            "`send` must print the id of the message the daemon stored: ${sent.out}",
        )

        // The condition occurred, in the window the test is about: the pm's own session was handed
        // the rendered message (the stub echoes what its console received).
        val echoed = fixture.client.awaitAfter(0, "the pm's session to be handed the message") { frame ->
            frame.type == Envelope.TEXT_DELTA && frame.sessionId == pmSession &&
                frame.data.contains("[Infantry AI mailbox]") && frame.data.contains("hi")
        }
        assertTrue(echoed.data.contains("hi"), "the pm's console received: ${echoed.data.take(200)}")
    }

    /**
     * 18. MAIL-2: a role no agent in the SENDER's project holds is a DELIVERY failure, not a
     * refusal, and the CLI says so: exit 1 with the daemon's own reason on stderr. A pm does exist
     * here -- in another project -- so this pins the project scoping, not the absence of a pm.
     */
    @Test
    fun sendToARoleWithNoPmInTheSendersProjectExitsOneNamingTheReason() {
        val fixture = launch()
        fixture.client.await(Envelope.READY)
        fixture.client.saveProfile("""{"id":"pm-1","name":"PM","role":"pm"}""")
        fixture.client.saveProfile("""{"id":"dev-1","name":"Dev","role":"dev"}""")
        val pmSession = startTicketSession(fixture, "proj-a", "pm-1", "PM work")
        val sender = startTicketSession(fixture, "proj-b", "dev-1", "Dev work")
        val sent = cli(fixture.port, mapOf(AccessPolicy.SESSION_ID_ENV to sender), "send", "pm", "hi")
        assertEquals(1, sent.exit, "an undeliverable message must be exit 1: out=${sent.out} err=${sent.err}")
        assertTrue(
            sent.err.contains("no pm agent in this project"),
            "stderr must carry the daemon's reason: ${sent.err}",
        )
        assertFalse(sent.out.contains("sent"), "nothing was delivered, so nothing was sent: ${sent.out}")
    }

    /**
     * 18b. BUG-MAIL-2: a pm in project P1 starts a ticket in project P2 from its own session
     * (`agenthq ticket move` run with its session id). The worker's preamble names the pm as the
     * session to report to, `send starter` reaches the pm's console although P2 holds no pm, and the
     * pm can answer the worker by its session id across the same project boundary.
     */
    @Test
    fun aWorkerReportsToTheSessionThatStartedItsTicketAcrossProjects() {
        val fixture = launch(StubScript.default().copy(echoPrompt = true))
        fixture.client.await(Envelope.READY)
        fixture.client.saveProfile("""{"id":"pm-1","name":"Project Manager","role":"pm"}""")
        fixture.client.saveProfile("""{"id":"arch-1","name":"Architect","role":"architect"}""")
        val pmSession = startTicketSession(fixture, "proj-p1", "pm-1", "PM work")
        val ticket = saveTicketTitled(
            fixture,
            "Design it",
            """{"title":"Design it","agentId":"arch-1","projectId":"proj-p2","useWorktree":false}""",
        )

        val moved = cli(
            fixture.port, mapOf(AccessPolicy.SESSION_ID_ENV to pmSession),
            "ticket", "move", ticket.id, TicketStatus.IN_PROGRESS,
        )
        assertEquals(0, moved.exit, "the pm's move must start the ticket: out=${moved.out} err=${moved.err}")
        // Read the board by its own answer type (TICKET_LIST -> TICKETS): the worker's start also pushes `ticket`
        // frames, and a reader that takes the first backlog frame of any type can decode one of those instead.
        val listedFrom = fixture.client.mark()
        fixture.client.send(Envelope(Envelope.TICKET_LIST, ""))
        val listed = fixture.client.awaitAfter(listedFrom, "the board listing") { it.type == Envelope.TICKETS }
        val worker = json.decodeFromString(ListSerializer(Ticket.serializer()), listed.data)
            .single { it.id == ticket.id }.sessionId
        assertNotNull(worker, "the started ticket records its worker session")
        fixture.await(what = "the worker session to come to rest") {
            fixture.daemon.manager.get(worker)?.isAvailable() == true
        }

        // The preamble the worker was handed names its starter (the stub echoes the prompt it received).
        val preamble = fixture.client.awaitAfter(0, "the worker's preamble") { frame ->
            frame.type == Envelope.TEXT_DELTA && frame.sessionId == worker && frame.data.contains("Report to:")
        }
        assertTrue(
            preamble.data.contains("Report to: Project Manager ($pmSession)") &&
                preamble.data.contains("agenthq send starter"),
            "the Team block names the starter and the send line: ${preamble.data.take(1200)}",
        )

        val sent = cli(fixture.port, mapOf(AccessPolicy.SESSION_ID_ENV to worker), "send", "starter", "DONE design")
        assertEquals(0, sent.exit, "send starter must reach the pm in P1: out=${sent.out} err=${sent.err}")
        fixture.client.awaitAfter(0, "the pm's session to be handed the report") { frame ->
            frame.type == Envelope.TEXT_DELTA && frame.sessionId == pmSession &&
                frame.data.contains("[Infantry AI mailbox]") && frame.data.contains("DONE design")
        }

        fixture.await(what = "the pm session to come to rest after the report") {
            fixture.daemon.manager.get(pmSession)?.isAvailable() == true
        }
        val answered = cli(fixture.port, mapOf(AccessPolicy.SESSION_ID_ENV to pmSession), "send", worker, "ack")
        assertEquals(0, answered.exit, "the starter answers its worker across projects: out=${answered.out} err=${answered.err}")
    }

    /**
     * 18c. BUG-MAIL-2 (owner's rule): a role resolves among the project's own agents -- the sessions opened in it
     * with New Agent -- never from the profile store. A profile with role pm bound to the project but with no
     * session there does not answer `send pm`; a role-only New Agent pm opened in the project does.
     */
    @Test
    fun aRoleResolvesToTheProjectsNewAgentAndNeverToAProfileOnlyRole() {
        val fixture = launch(StubScript.default().copy(echoPrompt = true))
        fixture.client.await(Envelope.READY)
        fixture.client.saveProfile("""{"id":"pm-profile","name":"Profile PM","role":"pm","projectId":"proj-x"}""")
        fixture.client.saveProfile("""{"id":"dev-1","name":"Dev","role":"dev"}""")
        val worker = startTicketSession(fixture, "proj-x", "dev-1", "Dev work")

        val toProfileOnly = cli(fixture.port, mapOf(AccessPolicy.SESSION_ID_ENV to worker), "send", "pm", "first")
        assertEquals(1, toProfileOnly.exit, "a profile-only pm is not a project agent: out=${toProfileOnly.out} err=${toProfileOnly.err}")
        assertTrue(toProfileOnly.err.contains("no pm agent in this project"), "the reason is named: ${toProfileOnly.err}")

        // What the New agent modal sends on Add: `new` naming the role and the project, no profile.
        fixture.send(Envelope(Envelope.NEW, """{"role":"pm","projectId":"proj-x"}"""))
        fixture.await(what = "the New Agent pm session to exist") {
            fixture.daemon.manager.list().any { it.role == "pm" && it.agentId == null && it.projectId == "proj-x" }
        }
        val newAgentPm = fixture.daemon.manager.list().single { it.role == "pm" && it.agentId == null }.id

        val toNewAgent = cli(fixture.port, mapOf(AccessPolicy.SESSION_ID_ENV to worker), "send", "pm", "second")
        assertEquals(0, toNewAgent.exit, "the New Agent pm answers `send pm`: out=${toNewAgent.out} err=${toNewAgent.err}")
        fixture.client.awaitAfter(0, "the New Agent pm to be handed the message") { frame ->
            frame.type == Envelope.TEXT_DELTA && frame.sessionId == newAgentPm &&
                frame.data.contains("[Infantry AI mailbox]") && frame.data.contains("second")
        }
    }

    /**
     * 18d. BUG-ROLE-1 (owner's rule): the roster a ticket worker SEES (`agents --live`) is its project's live,
     * in-session agents -- the New Agent sessions, role-only included -- never the profile store. A role-only
     * New Agent pm in the project is listed; a profile-only pm bound to the project (no session) is not.
     */
    @Test
    fun aWorkersLiveRosterListsTheProjectsNewAgentsAndNeverAProfileOnlyRole() {
        val fixture = launch()
        fixture.client.await(Envelope.READY)
        fixture.client.saveProfile("""{"id":"pm-profile","name":"Profile PM","role":"pm","projectId":"proj-x"}""")
        fixture.client.saveProfile("""{"id":"dev-1","name":"Dev","role":"dev"}""")
        val worker = startTicketSession(fixture, "proj-x", "dev-1", "Dev work")

        fixture.send(Envelope(Envelope.NEW, """{"role":"pm","projectId":"proj-x"}"""))
        fixture.await(what = "the New Agent pm session to exist") {
            fixture.daemon.manager.list().any { it.role == "pm" && it.agentId == null && it.projectId == "proj-x" }
        }
        val newAgentPm = fixture.daemon.manager.list().single { it.role == "pm" && it.agentId == null }.id

        val env = mapOf(AccessPolicy.SESSION_ID_ENV to worker, AccessPolicy.PROJECT_ID_ENV to "proj-x")
        val live = cli(fixture.port, env, "agents", "--live")
        assertEquals(0, live.exit, "agents --live failed: out=${live.out} err=${live.err}")
        assertTrue(live.out.contains("$newAgentPm |"), "the role-only New Agent pm is listed: ${live.out}")
        assertFalse(live.out.contains("pm-profile"), "a profile-only pm is never listed: ${live.out}")
        assertFalse(live.out.contains("Profile PM"), "a profile-only pm is never listed: ${live.out}")
    }

    /**
     * 18e. BUG-ROLE-1 (owner, critical): EVERY session sees the project's live agents, not only ticket workers. A
     * plain New Agent session in the project (no ticket, no profile) sees the other New Agent sessions there, and
     * none from another project.
     */
    @Test
    fun aPlainNewAgentSessionSeesTheOtherLiveNewAgentsOfItsProject() {
        val fixture = launch()
        fixture.client.await(Envelope.READY)
        for ((role, project) in listOf("architect" to "proj-x", "pm" to "proj-x", "qa" to "proj-y")) {
            fixture.send(Envelope(Envelope.NEW, """{"role":"$role","projectId":"$project"}"""))
            fixture.await(what = "the New Agent $role session to exist") {
                fixture.daemon.manager.list().any { it.role == role && it.projectId == project }
            }
        }
        val sessions = fixture.daemon.manager.list()
        val architect = sessions.single { it.role == "architect" }.id
        val pm = sessions.single { it.role == "pm" }.id
        val otherProjectQa = sessions.single { it.role == "qa" }.id

        val env = mapOf(AccessPolicy.SESSION_ID_ENV to architect, AccessPolicy.PROJECT_ID_ENV to "proj-x")
        val live = cli(fixture.port, env, "agents", "--live")
        assertEquals(0, live.exit, "agents --live failed: out=${live.out} err=${live.err}")
        assertTrue(live.out.contains("$pm | pm | pm |"), "the other New Agent of the project is listed: ${live.out}")
        assertTrue(live.out.contains("$architect |"), "the asking session is listed too: ${live.out}")
        assertFalse(live.out.contains(otherProjectQa), "another project's agent is never listed: ${live.out}")
    }

    /**
     * 19. MAIL-2: `send` without a message is a USAGE error (exit 2) that shows the usage text. The
     * `send` line in that text is asserted too: registering the command in the usage is half the
     * ticket, and without this the test would already pass on the old binary as "unknown command".
     */
    @Test
    fun sendWithoutAMessageIsAUsageErrorThatShowsTheUsageText() {
        val out = StringBuilder()
        val err = StringBuilder()
        val exit = Cli.run(arrayOf("send", "pm"), out, err, CliTestEnv.getenv)
        assertEquals(2, exit, "`send` with no message must be exit 2, not a failed connection: $err")
        assertTrue(err.toString().contains("Usage:"), "a usage error must show the usage text: $err")
        assertTrue(
            err.toString().contains("send <agentId|role>"),
            "the usage text must document `send`: $err",
        )
        assertEquals("", out.toString(), "a usage error prints no result")
    }

    /**
     * 20. MAIL-2: `--reply-to` answers a message -- in ITS thread, back to ITS sender -- and the
     * message answered becomes ANSWERED. Who the reply is from is the session named by
     * AGENTHQ_SESSION_ID, which is the identity the daemon puts on the frame.
     */
    @Test
    fun sendWithReplyToAnswersTheMessageInItsThread() {
        val fixture = launch()
        fixture.client.await(Envelope.READY)
        fixture.client.saveProfile("""{"id":"pm-1","name":"PM","role":"pm"}""")
        fixture.client.saveProfile("""{"id":"dev-1","name":"Dev","role":"dev"}""")
        val pmSession = startTicketSession(fixture, "proj-a", "pm-1", "PM work")
        val senderSession = startTicketSession(fixture, "proj-a", "dev-1", "Dev work")

        val question = cli(fixture.port, mapOf(AccessPolicy.SESSION_ID_ENV to senderSession), "send", "pm", "hi")
        assertEquals(0, question.exit, "the question must be sent: ${question.err}")
        val asked = inboxOf(fixture, "pm").single()
        fixture.await(what = "the pm to be at rest again before it answers") {
            fixture.daemon.manager.get(pmSession)?.isAvailable() == true
        }

        val answer = cli(
            fixture.port,
            mapOf(AccessPolicy.SESSION_ID_ENV to pmSession),
            "send", senderSession, "on it", "--reply-to", asked.id,
        )
        assertEquals(0, answer.exit, "the answer must be sent: out=${answer.out} err=${answer.err}")

        val answered = inboxOf(fixture, "pm").single()
        assertEquals(MessageState.ANSWERED, answered.state, "the message being answered must be ANSWERED")
        val reply = inboxOf(fixture, senderSession).single()
        assertEquals("on it", reply.body, "a reply goes back to the sender of the message it answers")
        assertEquals(asked.threadId, reply.threadId, "a reply stays in the thread of the message it answers")
        assertTrue(
            answer.out.contains("sent ${reply.id}"),
            "`send` must print the id of the reply it stored (reply=${reply.id}, asked=${asked.id}): ${answer.out}",
        )
    }

    // =========================================================================
    // MAIL-3: `agenthq inbox [--unread]` and `agenthq read <msgId>`
    // =========================================================================

    /**
     * The message ids `inbox` printed, one per row, in printed order.
     *
     * Read off the FIRST column rather than with `contains`, for the same reason [ticketTitles] is:
     * "the row is absent" is part of what these tests assert, and `contains` would still pass if
     * `--unread` listed every message. The header and the empty-inbox lines are not rows.
     */
    private fun inboxIds(out: String): List<String> =
        out.lineSequence()
            .map { it.trim().trimEnd('\r') }
            .filter { it.isNotBlank() }
            .filter { it != "ID | FROM | WHEN | READ | FIRST LINE" }
            .filter { it != "No messages." && it != "No unread messages." }
            .map { row -> row.split(" | ").first() }
            .toList()

    /** The id `send` printed (`sent <id>`), so a test can read it back through the prefix `inbox` shows. */
    private fun sentId(result: Result): String {
        val line = result.out.lineSequence().firstOrNull { it.startsWith("sent ") }
        return assertNotNull(line, "`send` must print the id of the message it stored: ${result.out}")
            .removePrefix("sent ")
            .trim()
    }

    /** Two sessions at rest in one project, and the messages this test's mailbox is seeded with. */
    private data class TwoAgents(val x: String, val y: String, val toX: List<String>, val toY: String)

    /**
     * Seed the mailbox the way the ticket describes it: TWO messages to X and ONE to Y, so every
     * listing has a row it must KEEP and a row it must DROP.
     *
     * Both recipients are started from a ticket, exactly as in production, and both addresses are
     * SESSION IDs. That is the identity `inbox` asks about (`AGENTHQ_SESSION_ID`), and it is the one
     * `Mailbox.inbox` can answer with a plain equality: a message is keyed by the address it was
     * SENT to, so a role- or agent-id-addressed message would be in a differently keyed inbox.
     *
     * The messages are sent with NO `AGENTHQ_SESSION_ID`, so the sender is the mailbox's non-session
     * handle and can never be mistaken for either recipient.
     */
    private fun mailToTwoAgents(fixture: DaemonFixture): TwoAgents {
        fixture.client.await(Envelope.READY)
        fixture.client.saveProfile("""{"id":"dev-1","name":"Dev","role":"dev"}""")
        fixture.client.saveProfile("""{"id":"qa-1","name":"QA","role":"qa"}""")
        val x = startTicketSession(fixture, "proj-a", "dev-1", "X work")
        val y = startTicketSession(fixture, "proj-a", "qa-1", "Y work")

        val first = cli(fixture.port, emptyMap(), "send", x, "the first body")
        val second = cli(fixture.port, emptyMap(), "send", x, "the second body")
        val only = cli(fixture.port, emptyMap(), "send", y, "only for Y")
        assertEquals(0, first.exit, "the first message to X must be sent: ${first.err}")
        assertEquals(0, second.exit, "the second message to X must be sent: ${second.err}")
        assertEquals(0, only.exit, "the message to Y must be sent: ${only.err}")
        return TwoAgents(x, y, listOf(sentId(first), sentId(second)), sentId(only))
    }

    /**
     * 21. MAIL-3: `inbox`, run as X, lists exactly the two messages addressed to X -- and NOT the
     * one addressed to Y. The ids are printed as the 8-char prefix `read` accepts.
     */
    @Test
    fun inboxListsOnlyTheMessagesAddressedToMe() {
        val fixture = launch()
        val seeded = mailToTwoAgents(fixture)

        val mine = cli(fixture.port, mapOf(AccessPolicy.SESSION_ID_ENV to seeded.x), "inbox")
        assertEquals(0, mine.exit, "`inbox` must succeed: out=${mine.out} err=${mine.err}")
        assertEquals(
            2,
            inboxIds(mine.out).size,
            "`inbox` run as X must list exactly the 2 messages addressed to X: ${mine.out}",
        )
        assertEquals(
            seeded.toX.map { it.take(8) }.toSet(),
            inboxIds(mine.out).toSet(),
            "`inbox` must print the 8-char prefix of each message's id: ${mine.out}",
        )
        assertTrue(mine.out.contains("the first body"), "the first body must be listed: ${mine.out}")
        assertTrue(mine.out.contains("the second body"), "the second body must be listed: ${mine.out}")
        assertFalse(
            mine.out.contains("only for Y"),
            "another agent's message must not appear in my inbox: ${mine.out}",
        )
    }

    /**
     * 22. MAIL-3: `read <8-char prefix>` prints the body and MARKS THE MESSAGE READ, so the next
     * `inbox --unread` no longer lists it -- one of the two is left behind, which is what makes the
     * listing's count an assertion about the read rather than about the filter being absent.
     */
    @Test
    fun readPrintsTheBodyAndMarksItReadSoUnreadDropsIt() {
        val fixture = launch()
        val seeded = mailToTwoAgents(fixture)
        val first = seeded.toX.first()

        // The daemon only marks a DELIVERED message read (`Mailbox.markRead`), and read is asserted
        // through that: prove the condition occurred before relying on it, rather than assume that
        // X's at-rest console was resumed with the message.
        fixture.await(what = "the first message to X to have been delivered") {
            inboxOf(fixture, seeded.x).any { it.id == first && it.delivered }
        }

        val read = cli(fixture.port, mapOf(AccessPolicy.SESSION_ID_ENV to seeded.x), "read", first.take(8))
        assertEquals(0, read.exit, "`read` must succeed: out=${read.out} err=${read.err}")
        assertTrue(
            read.out.contains("the first body"),
            "`read` must print the message's body: ${read.out}",
        )

        val unread = cli(fixture.port, mapOf(AccessPolicy.SESSION_ID_ENV to seeded.x), "inbox", "--unread")
        assertEquals(0, unread.exit, "`inbox --unread` must succeed: ${unread.err}")
        assertEquals(
            1,
            inboxIds(unread.out).size,
            "the message just read must be gone from `inbox --unread`: ${unread.out}",
        )
        assertEquals(
            seeded.toX[1].take(8),
            inboxIds(unread.out).single(),
            "the message left behind must be the one that was NOT read: ${unread.out}",
        )
    }

    /**
     * 23. MAIL-3: an id that is not in my inbox is a failure that SAYS so -- exit 1 and "no such
     * message" -- rather than a crash or a silent no-op. Read against a mailbox that is NOT empty,
     * so this is the lookup finding nothing, not an empty listing passing for a lookup.
     */
    @Test
    fun readWithAnUnknownIdExitsOneSayingNoSuchMessage() {
        val fixture = launch()
        val seeded = mailToTwoAgents(fixture)

        val missing = cli(fixture.port, mapOf(AccessPolicy.SESSION_ID_ENV to seeded.x), "read", "deadbeef")
        assertEquals(
            1,
            missing.exit,
            "an unknown message id must be exit 1, not a crash or a failed connection: out=${missing.out} err=${missing.err}",
        )
        assertTrue(
            missing.err.contains("no such message"),
            "stderr must say the message is unknown: ${missing.err}",
        )
        assertEquals("", missing.out, "nothing was read, so nothing is printed on stdout")
    }

    // =========================================================================
    // MAIL-3b: the three error branches of `read <msgId>` MAIL-3 shipped untested
    // =========================================================================

    /**
     * 24. MAIL-3b: a message that has NOT reached the recipient's console is refused rather than
     * acknowledged, and the CLI names the reason -- the state the message is stuck in and "has not
     * reached a console yet" -- instead of passing the daemon's opaque "unknown or unreadable
     * message" through.
     *
     * WHY THIS NEEDS ITS OWN FIXTURE. The mailbox's DELIVERED -> READ transition has nothing to move
     * from while a message is QUEUED (`Mailbox.markRead` answers null), and the daemon's refusal is
     * the same one it gives for an id that does not exist at all, so a client that passed it through
     * would report "unreadable" for a message it had just listed. Only the client knows which of the
     * two it is, and it knows from the row the listing gave it -- which is what this pins.
     *
     * GETTING THE CONDITION TO OCCUR, deterministically: `MailRouter.deliver` answers Retry while the
     * recipient has a run in flight, so the message stays QUEUED instead of being handed over or
     * failing. A [GatedRunner] process whose prompt carries `HANG` never exits on its own, so the
     * recipient's lane is held open for exactly as long as this test needs it -- no sleep, no window
     * to lose.
     */
    @Test
    fun readRefusesAMessageThatHasNotReachedAConsoleYet() {
        val fixture = launch(runner = GatedRunner())
        try {
            fixture.client.await(Envelope.READY)
            fixture.send(Envelope(Envelope.NEW))
            fixture.send(Envelope(Envelope.PROMPT, "HANG"))
            fixture.await(what = "a session holding its lane open") {
                fixture.daemon.manager.list().singleOrNull()?.live == true
            }
            val me = fixture.daemon.manager.list().single().id

            val sent = cli(fixture.port, emptyMap(), "send", me, "a body that must not be acknowledged")
            assertEquals(0, sent.exit, "`send` to a live session must be accepted: out=${sent.out} err=${sent.err}")
            val id = sentId(sent)

            // The condition the branch guards, PROVEN before it is asserted on: the row exists, it is
            // QUEUED, and it was not handed over. A test that assumed this would pass for the wrong
            // reason the day a delivery lands early.
            val stored = inboxOf(fixture, me).single { it.id == id }
            assertEquals(MessageState.QUEUED, stored.state, "a message to a busy console stays QUEUED: $stored")
            assertFalse(stored.delivered, "a message that was never handed over is not delivered: $stored")

            val read = cli(fixture.port, mapOf(AccessPolicy.SESSION_ID_ENV to me), "read", id.take(8))
            assertEquals(
                1,
                read.exit,
                "a message that never reached a console must not be acknowledged: out=${read.out} err=${read.err}",
            )
            assertTrue(
                read.err.contains("has not reached a console yet"),
                "stderr must name the real reason rather than the daemon's opaque refusal: ${read.err}",
            )
            assertTrue(
                read.err.contains("QUEUED"),
                "stderr must name the state the message is stuck in: ${read.err}",
            )
            assertFalse(
                read.err.contains("unknown or unreadable message"),
                "the daemon's refusal is not the reason, and it is the same one it gives for an id " +
                    "that does not exist: ${read.err}",
            )
            assertEquals("", read.out, "nothing was read, so nothing is printed on stdout")

            // And the refusal really refused: the row is untouched, not silently moved to READ.
            val after = inboxOf(fixture, me).single { it.id == id }
            assertEquals(MessageState.QUEUED, after.state, "a refused read must not move the message: $after")
            assertFalse(after.read, "a refused read must not mark the message read: $after")
        } finally {
            // The held run never exits on its own; kill it so the fixture stops promptly.
            fixture.daemon.manager.list()
                .forEach { runCatching { fixture.daemon.manager.kill(it.id, force = true) } }
        }
    }

    /**
     * 25. MAIL-3b: a message whose delivery FAILED FOR GOOD is refused with ITS reason, not with the
     * "has not reached a console yet" one.
     *
     * WHY THIS IS NOT THE SAME TEST AS 24, and this is the whole point of it: `Message.failed` (state
     * UNDELIVERABLE) and `!Message.delivered` are two DIFFERENT conditions that both hold for a failed
     * row -- an UNDELIVERABLE message was never handed over either. Covering one does not cover the
     * other, and WHOLE-BRANCH deletion of the `failed` check is invisible to test 24: the CLI would
     * still refuse, still exit 1, and still print a refusal -- just the wrong one, telling the reader a
     * message that can never arrive is merely waiting. That is the failure this pins.
     *
     * GETTING THE CONDITION TO OCCUR: a recipient with no session, no agent profile and no role can
     * never be resolved, so `MailRouter.deliver` answers `DeliveryOutcome.Failed` and the row is
     * recorded UNDELIVERABLE with its reason. That is a real production path -- the same one an agent
     * that was never started produces -- and it is deterministic: the pump runs inside the send, so
     * the row is failed before `send` has even answered.
     */
    @Test
    fun readRefusesAMessageWhoseDeliveryFailedWithItsOwnReason() {
        val fixture = launch()
        fixture.client.await(Envelope.READY)
        // No such session, no such agent profile, no project context -- unresolvable, hence a
        // permanent delivery failure rather than a queue the daemon keeps retrying.
        val me = "no-such-session-for-mail-3b"

        // `send`'s own report of this failure is MAIL-2's business (acceptance test 18); what is under
        // test here is what `read` does with a failed row.
        cli(fixture.port, emptyMap(), "send", me, "a body that can never arrive")

        // The condition the branch guards, PROVEN before it is asserted on. If the delivery ever stops
        // failing this test fails here, loudly, instead of passing for the wrong reason.
        fixture.await(what = "the message to have failed for good") {
            inboxOf(fixture, me).singleOrNull()?.failed == true
        }
        val stored = inboxOf(fixture, me).single()
        assertEquals(MessageState.UNDELIVERABLE, stored.state, "a delivery that can never happen is recorded: $stored")
        assertFalse(stored.delivered, "a message that failed was never handed over: $stored")
        val reason = assertNotNull(stored.failureReason, "an UNDELIVERABLE row says WHY it failed: $stored")

        val read = cli(fixture.port, mapOf(AccessPolicy.SESSION_ID_ENV to me), "read", stored.id.take(8))
        assertEquals(
            1,
            read.exit,
            "a message that was never delivered must not be acknowledged: out=${read.out} err=${read.err}",
        )
        assertTrue(
            read.err.contains("was never delivered"),
            "stderr must say the delivery failed for good: ${read.err}",
        )
        assertTrue(
            read.err.contains(reason),
            "stderr must name WHY it failed, not merely that it did: ${read.err}",
        )
        assertFalse(
            read.err.contains("has not reached a console yet"),
            "a permanently failed message is not one that is still waiting: ${read.err}",
        )
        assertEquals("", read.out, "nothing was read, so nothing is printed on stdout")
    }

    /** The longest prefix [a] and [b] share; "" when they share none. */
    private fun sharedPrefix(a: String, b: String): String {
        val limit = minOf(a.length, b.length)
        var i = 0
        while (i < limit && a[i] == b[i]) i++
        return a.substring(0, i)
    }

    /** A NON-EMPTY prefix that at least two of [ids] share, or null when no two do. */
    private fun sharedPrefixOfTwoIds(ids: List<String>): String? {
        for (i in ids.indices) {
            for (j in i + 1 until ids.size) {
                val shared = sharedPrefix(ids[i], ids[j])
                if (shared.isNotEmpty()) return shared
            }
        }
        return null
    }

    /**
     * 26. MAIL-3b: an id PREFIX that matches MORE THAN ONE message is refused, and the candidates are
     * listed -- rather than one of them being picked and silently acknowledged.
     *
     * WHAT IS AT STAKE: `inbox` prints the 8-character prefix, never the full id, and `read` resolves
     * that prefix against MY inbox. Without this branch the first match wins, so a reader who meant the
     * other message has marked the wrong one READ -- and the wrongly acknowledged one drops out of
     * `inbox --unread` for good. That is a silent loss, which is the exact class of failure the mailbox
     * exists to prevent, so a decision was taken to refuse instead. A decision deserves a test.
     *
     * WHY THE PREFIX HERE IS SHORT, AND WHY THAT IS A BOUND AND NOT A HOPE. The ticket asked for two
     * messages sharing an EIGHT-character prefix, and there is no seam to arrange that: ids are minted
     * by the daemon (`UUID.randomUUID()`), `CliAcceptanceTest` sends through the real `agenthq send`,
     * and 8 hex characters is 2^32 -- a collision is not something more sends can be trusted to find.
     * The branch does not care HOW MANY characters two ids share, only that TWO rows match the prefix it
     * is given, so this test gets there by counting instead of by luck: each id starts with one of 16
     * hex characters, so 17 ids cannot all start differently (PIGEONHOLE) -- some pair shares at least
     * its first character. The shortest such prefix is then computed from the rows the daemon actually
     * holds, so the collision is guaranteed, stated, and re-derived from the data rather than assumed.
     *
     * The recipient is an address with no session behind it, which keeps this hermetic: every send is
     * STORED and then recorded UNDELIVERABLE (an unresolvable recipient), and `read` resolves the prefix
     * against exactly those rows. Delivery is not part of what this branch decides, so the test does not
     * pretend to exercise it.
     */
    @Test
    fun readRefusesAnIdPrefixThatMatchesMoreThanOneMessage() {
        val fixture = launch()
        fixture.client.await(Envelope.READY)
        val me = "ambiguous-prefix-target"
        // 17 > 16: the smallest count that makes a shared first character certain. TWO senders come with
        // it, because the mailbox rate-limits a sender to 12 messages per 10-minute window (`MailLimits`)
        // and a REFUSED send is not stored at all, so 17 sends from one sender would leave 12 rows and
        // no bound at all.
        val sends = 17
        repeat(sends) { i ->
            val sender =
                if (i < 12) emptyMap()
                else mapOf(AccessPolicy.SESSION_ID_ENV to "prefix-sender-two")
            cli(fixture.port, sender, "send", me, "prefix body $i")
        }

        val ids = inboxOf(fixture, me).map { it.id }
        assertEquals(
            sends,
            ids.size,
            "every send must be STORED, or the pigeonhole bound below does not hold (a rate-limited " +
                "send is simply absent): $ids",
        )
        val prefix = assertNotNull(
            sharedPrefixOfTwoIds(ids),
            "$sends ids over 16 possible first characters cannot all start differently, so two MUST " +
                "share a prefix; if this fires the seeding changed and the test would be vacuous: $ids",
        )
        val candidates = ids.filter { it.startsWith(prefix) }
        assertTrue(candidates.size > 1, "the prefix '$prefix' must match more than one row: $candidates")

        val read = cli(fixture.port, mapOf(AccessPolicy.SESSION_ID_ENV to me), "read", prefix)
        assertEquals(
            1,
            read.exit,
            "a prefix that matches several messages must be refused, not guessed at: out=${read.out} err=${read.err}",
        )
        assertTrue(
            read.err.contains("ambiguous"),
            "stderr must say the id is ambiguous rather than report a failure: ${read.err}",
        )
        assertTrue(
            candidates.map { it.take(8) }.all { read.err.contains(it) },
            "every candidate must be listed, or the reader cannot pick one: candidates=$candidates err=${read.err}",
        )
        assertEquals("", read.out, "nothing was read, so nothing is printed on stdout")
    }

    // =========================================================================
    // MAIL-4: `agenthq threads`, `agenthq thread <id>` and `agenthq waiting`
    // =========================================================================

    /** The `threads` table header. Asserted, and kept out of the rows [threadRows] parses. */
    private val THREADS_HEADER = "THREAD | WITH | MESSAGES | LAST"

    /** The `waiting` table header, same role as [THREADS_HEADER]. */
    private val WAITING_HEADER = "ID | SESSION | WAITING ON | SINCE | SUBJECT"

    /**
     * The rows `threads` printed, as COLUMNS, in printed order -- the header and the empty-listing
     * line are not rows.
     *
     * Read off the columns rather than with `contains`, for the same reason [inboxIds] is: "this
     * conversation is absent" is half of what `threads` has to prove (it is scoped to ME), and
     * `contains` would still pass for a command that printed every thread on the daemon.
     */
    private fun threadRows(out: String): List<List<String>> =
        out.lineSequence()
            .map { it.trim().trimEnd('\r') }
            .filter { it.isNotBlank() }
            .filter { it != THREADS_HEADER && it != "No threads." }
            .map { row -> row.split(" | ") }
            .toList()

    /** The rows `waiting` printed, as columns; the header and the empty-listing line are not rows. */
    private fun waitingRows(out: String): List<List<String>> =
        out.lineSequence()
            .map { it.trim().trimEnd('\r') }
            .filter { it.isNotBlank() }
            .filter { it != WAITING_HEADER && it != "Nothing is waiting." }
            .map { row -> row.split(" | ") }
            .toList()

    /**
     * The daemon's OWN `mail_threads` answer -- every thread it holds, of every agent -- read over
     * the real socket, exactly as [inboxOf] reads `mail_inbox`.
     *
     * The state `threads` filters DOWN from, and the reason the empty-state tests are not vacuous
     * (evidence bar §3): "No threads." from a daemon that actually held threads would be the
     * participant FILTER firing, not the empty-state GUARD. Reading the daemon's own answer proves
     * which one happened.
     */
    private fun daemonThreads(fixture: DaemonFixture): List<ThreadSummary> {
        val before = fixture.client.mark()
        fixture.client.send(Envelope(Envelope.MAIL_THREADS, ""))
        val frame = fixture.client.awaitAfter(before, "the daemon's thread list") {
            it.type == Envelope.MAIL_THREADS
        }
        return json.decodeFromString(ListSerializer(ThreadSummary.serializer()), frame.data)
    }

    /** The daemon's own `mail_waiting` answer; same non-vacuity role as [daemonThreads]. */
    private fun daemonWaiting(fixture: DaemonFixture): List<WaitingOn> {
        val before = fixture.client.mark()
        fixture.client.send(Envelope(Envelope.MAIL_WAITING, ""))
        val frame = fixture.client.awaitAfter(before, "the daemon's waiting list") {
            it.type == Envelope.MAIL_WAITING
        }
        return json.decodeFromString(ListSerializer(WaitingOn.serializer()), frame.data)
    }

    /**
     * A mailbox instant as the CLI renders it. The LAST column is compared against a stamp derived
     * from the daemon's own `sentAt`, exactly rather than loosely: a column of timestamps that is
     * merely "recent" is a column nobody can trust.
     */
    private fun isoOf(epochMillis: Long): String =
        DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'")
            .withZone(ZoneOffset.UTC)
            .format(Instant.ofEpochMilli(epochMillis))

    /** A three-message conversation between two at-rest agents, plus an agent who is in no thread. */
    private data class Exchange(
        val x: String,
        val y: String,
        val outsider: String,
        val thread: String,
        val ids: List<String>,
        val lastAt: Long,
    )

    /**
     * X -> Y, Y -> X, X -> Y: ONE conversation of three messages, all in the same thread.
     *
     * The third message is deliberately NOT a reply: the subject of this fixture is thread ORDER, and
     * a `--reply-to` would additionally mark its original ANSWERED, which is a different behaviour
     * (MAIL-2's test 20) leaking into the states `thread` prints here.
     *
     * Both addresses are SESSION ids, so the thread is the deterministic `pair:<a>|<b>` -- which is
     * what makes "who else is in it" a column with one knowable answer. The thread id and the instant
     * of its last message are read off the DAEMON's own rows, never re-derived here: the columns under
     * test are compared with what the mailbox actually holds.
     */
    private fun threeMessageExchange(fixture: DaemonFixture): Exchange {
        fixture.client.await(Envelope.READY)
        fixture.client.saveProfile("""{"id":"dev-1","name":"Dev","role":"dev"}""")
        fixture.client.saveProfile("""{"id":"qa-1","name":"QA","role":"qa"}""")
        fixture.client.saveProfile("""{"id":"outsider-1","name":"Outsider","role":"dev"}""")
        val x = startTicketSession(fixture, "proj-a", "dev-1", "X work")
        val y = startTicketSession(fixture, "proj-a", "qa-1", "Y work")
        val outsider = startTicketSession(fixture, "proj-a", "outsider-1", "Outsider work")

        val first = cli(fixture.port, mapOf(AccessPolicy.SESSION_ID_ENV to x), "send", y, "first body")
        val second = cli(fixture.port, mapOf(AccessPolicy.SESSION_ID_ENV to y), "send", x, "second body")
        val third = cli(fixture.port, mapOf(AccessPolicy.SESSION_ID_ENV to x), "send", y, "third body")
        assertEquals(0, first.exit, "the first message must be sent: ${first.err}")
        assertEquals(0, second.exit, "the second message must be sent: ${second.err}")
        assertEquals(0, third.exit, "the third message must be sent: ${third.err}")

        val held = inboxOf(fixture, x) + inboxOf(fixture, y)
        return Exchange(
            x = x,
            y = y,
            outsider = outsider,
            thread = held.map { it.threadId }.distinct().single(),
            ids = listOf(sentId(first), sentId(second), sentId(third)),
            lastAt = held.maxOf { it.sentAt },
        )
    }

    /**
     * Store a NEEDS-INPUT message through the daemon's own socket, the way a blocked agent declares
     * it. `agenthq send` has no flag for it (`MailSendRequest.needsInput`, MAIL-2 shipped no such
     * option), and this is the daemon's public wire rather than a back door: the same `mail_send`
     * frame a client sends, with `from` named explicitly so a test can speak as a session.
     */
    private fun sendNeedsInput(fixture: DaemonFixture, from: String, to: String, body: String): String {
        val before = fixture.client.mark()
        fixture.client.send(
            Envelope(
                Envelope.MAIL_SEND,
                json.encodeToString(
                    MailSendRequest.serializer(),
                    MailSendRequest(from = from, to = to, body = body, needsInput = true),
                ),
                from,
            ),
        )
        val frame = fixture.client.awaitAfter(before, "the stored message '$body'") { env ->
            env.type == Envelope.MAIL &&
                runCatching { json.decodeFromString(Message.serializer(), env.data) }.getOrNull()?.body == body
        }
        return json.decodeFromString(Message.serializer(), frame.data).id
    }

    /**
     * 27. MAIL-4: `threads`, run as X, lists the one conversation X is in -- WHO ELSE is in it, how
     * many messages it holds, and when it last moved -- and lists NOTHING for an agent who is in no
     * conversation at all.
     *
     * BOTH SIDES ARE THE TEST. `mail_threads` answers with every thread the daemon holds, so a
     * `threads` that printed it verbatim would look perfectly right for X and be wrong for everyone
     * else; the outsider's empty listing is the row that must be DROPPED, and without it the scoping
     * is untested. All four columns are asserted, because "with Y" and "3 messages" are the two facts
     * a reader decides anything on.
     */
    @Test
    fun threadsListsMyConversationWithWhoElseHowManyAndWhen() {
        val fixture = launch()
        val exchange = threeMessageExchange(fixture)

        val mine = cli(fixture.port, mapOf(AccessPolicy.SESSION_ID_ENV to exchange.x), "threads")
        assertEquals(0, mine.exit, "`threads` must succeed: out=${mine.out} err=${mine.err}")
        assertTrue(
            mine.out.contains(THREADS_HEADER),
            "`threads` must print its columns: ${mine.out}",
        )
        val rows = threadRows(mine.out)
        assertEquals(1, rows.size, "`threads` run as X must list exactly X's one conversation: ${mine.out}")
        val row = rows.single()
        assertEquals(
            exchange.thread,
            row[0],
            "the THREAD column must be the id `thread` takes, as the daemon holds it: ${mine.out}",
        )
        assertEquals(exchange.y, row[1], "the WITH column must be the OTHER participant: ${mine.out}")
        assertEquals("3", row[2], "the MESSAGES column must count the thread: ${mine.out}")
        assertEquals(
            isoOf(exchange.lastAt),
            row[3],
            "the LAST column must be the newest message's instant: ${mine.out}",
        )

        val stranger = cli(fixture.port, mapOf(AccessPolicy.SESSION_ID_ENV to exchange.outsider), "threads")
        assertEquals(0, stranger.exit, "`threads` must succeed for anyone: ${stranger.err}")
        assertTrue(
            threadRows(stranger.out).isEmpty(),
            "an agent in no conversation must be shown none: ${stranger.out}",
        )
        // The empty-STATE LINE, asserted here and not only in 27b: `threadRows` filters both the
        // header and "No threads." away, so the row assertion above passes identically for a
        // `threads` that deleted the guard and printed the bare header. This is the assertion that
        // kills that mutation on THIS leg.
        assertTrue(
            stranger.out.contains("No threads."),
            "an agent in no conversation must be TOLD so, not shown a header over nothing: ${stranger.out}",
        )
    }

    /**
     * 27b. MAIL-4 follow-up: an agent with NO conversation at all is TOLD so -- "No threads.",
     * exit 0 -- rather than handed the bare table header that reads as a listing with no rows.
     *
     * WHY THIS EXISTS BESIDE 27, and it is the whole point. 27's outsider reaches its empty state
     * THROUGH THE PARTICIPANT FILTER (`me in it.participants`), so deleting the filter reddens 27
     * while deleting the EMPTY-STATE GUARD (`mine.isEmpty()`) reddens NOTHING at all -- QA measured
     * RED = 0 for that mutation on the MAIL-4 gate. This fixture holds ZERO threads for ANY
     * identity ([daemonThreads] proves that below rather than assuming it), so the only branch that
     * can print "No threads." is the guard itself, and this is the test that kills its mutation.
     */
    @Test
    fun threadsWithNoConversationsSaysSoAndExitsZero() {
        val fixture = launch()
        fixture.client.await(Envelope.READY)

        assertTrue(
            daemonThreads(fixture).isEmpty(),
            "the fixture must hold NO thread at all, or this test proves the filter, not the guard",
        )
        val listed = cli(fixture.port, mapOf(AccessPolicy.SESSION_ID_ENV to "no-threads-agent"), "threads")
        assertEquals(0, listed.exit, "an empty listing is not a failure: out=${listed.out} err=${listed.err}")
        assertTrue(
            listed.out.contains("No threads."),
            "`threads` must SAY there are none, not print the header over nothing: ${listed.out}",
        )
        assertFalse(
            listed.out.contains(THREADS_HEADER),
            "the bare table header reads as a listing with no rows: ${listed.out}",
        )
    }

    /**
     * 28. MAIL-4: `thread <id>` prints every message of the conversation, in thread order, bodies
     * whole (not the clipped first line `inbox` shows).
     *
     * The id is taken from `threads`' OWN output rather than recomputed here: that is the chain a
     * reader follows, and the id being usable by the second command is the one thing that can break
     * while both commands look right in isolation.
     *
     * BOTH ORDERINGS ARE ASSERTED -- the ids and the bodies. A command that reversed the thread would
     * still print all three of each, so "3 messages appeared" proves nothing about the order the
     * ticket is about.
     */
    @Test
    fun threadPrintsEveryMessageInOrder() {
        val fixture = launch()
        val exchange = threeMessageExchange(fixture)

        // The id `thread` takes comes from `threads`, and it is asserted HERE first: a `threads`
        // that answered nothing would otherwise make this test crash on an empty list, which reads
        // as a broken test rather than as the missing command it is.
        val listed = cli(fixture.port, mapOf(AccessPolicy.SESSION_ID_ENV to exchange.x), "threads")
        assertEquals(0, listed.exit, "`threads` must succeed, or `thread` cannot be driven at all: ${listed.err}")
        val rows = threadRows(listed.out)
        assertEquals(1, rows.size, "`threads` must list the conversation `thread` is asked for: ${listed.out}")
        val listedId = rows.single()[0]
        assertEquals(exchange.thread, listedId, "`threads` must print the id `thread` takes: ${listed.out}")

        val thread = cli(fixture.port, mapOf(AccessPolicy.SESSION_ID_ENV to exchange.x), "thread", listedId)
        assertEquals(0, thread.exit, "`thread` must succeed: out=${thread.out} err=${thread.err}")

        val idAt = exchange.ids.map { thread.out.indexOf(it) }
        assertTrue(
            idAt.all { it >= 0 },
            "every message of the thread must be printed: expected=${exchange.ids} got=${thread.out}",
        )
        assertEquals(idAt.sorted(), idAt, "the messages must be printed in THREAD order: ${thread.out}")

        val bodyAt = listOf("first body", "second body", "third body").map { thread.out.indexOf(it) }
        assertTrue(bodyAt.all { it >= 0 }, "every body must be printed whole, not clipped: ${thread.out}")
        assertEquals(bodyAt.sorted(), bodyAt, "the bodies must appear in thread order: ${thread.out}")
    }

    /**
     * 29. MAIL-4: a thread id the daemon does not know is a failure that SAYS so -- exit 1, "no such
     * thread" -- and not an empty listing that reads like a real conversation with nothing in it.
     *
     * WHY THE CLIENT MAY DECIDE THIS: a thread is DERIVED from the messages in it (`Mailbox.thread`
     * filters by `threadId`), so a thread the daemon knows is never empty. An empty answer therefore
     * means the id is unknown, and printing "0 messages in thread X" would tell a reader their typo
     * is a conversation. Run against a mailbox that is NOT empty, so this is the lookup finding
     * nothing rather than an empty mailbox passing for a lookup.
     */
    @Test
    fun threadWithAnUnknownIdExitsOneSayingNoSuchThread() {
        val fixture = launch()
        val exchange = threeMessageExchange(fixture)

        val missing = cli(
            fixture.port,
            mapOf(AccessPolicy.SESSION_ID_ENV to exchange.x),
            "thread",
            "pair:no-such-thread",
        )
        assertEquals(
            1,
            missing.exit,
            "an unknown thread id must be exit 1, not an empty listing: out=${missing.out} err=${missing.err}",
        )
        assertTrue(
            missing.err.contains("no such thread"),
            "stderr must say the thread is unknown: ${missing.err}",
        )
        assertEquals("", missing.out, "nothing was listed, so nothing is printed on stdout")
    }

    /**
     * 30. MAIL-4: the three commands are REGISTERED and DOCUMENTED.
     *
     * Registering a command in the usage text is half of shipping it, and an unregistered name is
     * refused as "unknown command" -- the same exit code (2) as a usage error, and a message no test
     * above can tell from it. `thread` with no id is the usage error that proves it is registered.
     */
    @Test
    fun theMailReadCommandsAreRegisteredAndDocumented() {
        val out = StringBuilder()
        val err = StringBuilder()
        val exit = Cli.run(arrayOf("thread"), out, err, CliTestEnv.getenv)
        assertEquals(2, exit, "`thread` with no id must be a usage error, not a failed connection: $err")
        assertTrue(
            err.toString().contains("thread requires a thread id"),
            "the usage error must name what is missing: $err",
        )
        assertEquals("", out.toString(), "a usage error prints no result")

        val help = StringBuilder()
        assertEquals(0, Cli.run(arrayOf("--help"), help, StringBuilder(), CliTestEnv.getenv))
        for (command in listOf("threads", "thread <id>", "waiting")) {
            assertTrue(
                help.toString().contains(command),
                "help does not mention '$command': $help",
            )
        }
    }

    /**
     * 31. MAIL-4: `waiting` lists the open question of an agent that is AT REST -- the message it is
     * stuck on and WHO it is waiting for -- and does NOT list a question asked by an agent that is
     * still working.
     *
     * THE DROPPED ROW IS HALF THE TEST, and it is the half a naive implementation fails.
     * `MailRouter.waiting()` narrows `Mailbox.waiting()` to askers with no run in flight ("an agent
     * mid-run is not waiting on you -- it is working, and saying otherwise is the false signal that
     * erodes trust"). The two messages here differ in exactly that and nothing else, so a `waiting`
     * that printed every needs-input row would still pass on the first row alone.
     *
     * THE BUSY RECIPIENT IS THE POINT of the listed row too: a message to an agent whose console is
     * mid-run stays QUEUED, and that unanswered question is what the mailbox reports, naming the
     * busy agent as who it is waiting on. Both conditions are PROVEN before anything is asserted --
     * a fixture that quietly stopped holding the run open would otherwise pass for the wrong reason.
     */
    @Test
    fun waitingNamesTheBusyAgentAnAtRestAgentIsWaitingOn() {
        val fixture = launch(runner = GatedRunner())
        try {
            fixture.client.await(Envelope.READY)
            fixture.client.saveProfile("""{"id":"dev-1","name":"Dev","role":"dev"}""")
            val x = startTicketSession(fixture, "proj-a", "dev-1", "X work")

            // Y's console is held open by a prompt carrying HANG, so Y stays BUSY for exactly as long
            // as this test needs -- no sleep, and no window to lose.
            fixture.send(Envelope(Envelope.NEW))
            fixture.send(Envelope(Envelope.PROMPT, "HANG"))
            fixture.await(what = "a session holding its lane open") {
                fixture.daemon.manager.list().any { it.live && it.id != x }
            }
            val y = fixture.daemon.manager.list().first { it.id != x }.id
            fixture.await(what = "the held session to be BUSY") {
                fixture.daemon.manager.get(y)?.isBusy() == true
            }

            // The signal that must be DROPPED: asked by Y, which is working. Its recipient is X, at
            // rest, so the message itself is handled normally -- only the ASKER's state can drop it,
            // which is why the test can attribute the drop to the rule it is about.
            val fromBusy = sendNeedsInput(fixture, y, x, "asked by a busy agent")
            // The signal that must be KEPT: asked by X, at rest, of a recipient that is BUSY, so the
            // question stays QUEUED and unanswered.
            val fromRest = sendNeedsInput(fixture, x, y, "asked by an at-rest agent")

            fixture.await(what = "the question to a busy console to be stored") {
                inboxOf(fixture, y).singleOrNull { it.id == fromRest } != null
            }
            val asked = inboxOf(fixture, y).single { it.id == fromRest }
            assertEquals(
                MessageState.QUEUED,
                asked.state,
                "a message to a console with a run in flight stays QUEUED: $asked",
            )
            assertFalse(asked.delivered, "a message to a busy console is not handed over: $asked")
            assertFalse(
                asked.failed,
                "it is QUEUED for a busy console, not undeliverable -- a failed row is dropped from " +
                    "`waiting` for the wrong reason: $asked",
            )
            val dropped = inboxOf(fixture, x).single { it.id == fromBusy }
            assertFalse(
                dropped.failed,
                "the busy agent's question was delivered normally, so only its asker's state can " +
                    "drop it from `waiting`: $dropped",
            )
            // The asker of the listed row must be AT REST by the time `waiting` runs, or the row is
            // filtered out for a race rather than by the rule under test.
            fixture.await(what = "the at-rest asker to come to rest again") {
                fixture.daemon.manager.get(x)?.isAvailable() == true
            }

            val waiting = cli(fixture.port, mapOf(AccessPolicy.SESSION_ID_ENV to x), "waiting")
            assertEquals(0, waiting.exit, "`waiting` must succeed: out=${waiting.out} err=${waiting.err}")
            assertTrue(
                waiting.out.contains(WAITING_HEADER),
                "`waiting` must print its columns: ${waiting.out}",
            )
            val rows = waitingRows(waiting.out)
            assertEquals(
                1,
                rows.size,
                "only the at-rest agent's question is waiting; a working agent's is not: ${waiting.out}",
            )
            val row = rows.single()
            assertEquals(
                fromRest.take(8),
                row[0],
                "the row must name the message the agent is stuck on: ${waiting.out}",
            )
            assertEquals(x, row[1], "the row must name the session that is blocked: ${waiting.out}")
            assertEquals(y, row[2], "the row must name WHO it is waiting on -- the busy agent: ${waiting.out}")
            assertFalse(
                waiting.out.contains(fromBusy.take(8)),
                "a question asked by a working agent is not a waiting signal: ${waiting.out}",
            )
        } finally {
            // The held run never exits on its own; kill it so the fixture stops promptly.
            fixture.daemon.manager.list()
                .forEach { runCatching { fixture.daemon.manager.kill(it.id, force = true) } }
        }
    }

    /**
     * 31b. MAIL-4 follow-up: a daemon holding NO open question says so -- "Nothing is waiting.",
     * exit 0 -- instead of the bare WAITING_HEADER over zero rows.
     *
     * The same shape as the `threads` empty-state guard (27b), one command over: [waitingRows]
     * filters BOTH the header and the empty-listing line away, so every row-reading assertion above
     * passes identically whether the guard exists or was deleted tomorrow. [daemonWaiting] proves
     * the daemon holds nothing, so "Nothing is waiting." cannot be a filter silently dropping rows.
     */
    @Test
    fun waitingWithNothingOpenSaysSoAndExitsZero() {
        val fixture = launch()
        fixture.client.await(Envelope.READY)

        assertTrue(
            daemonWaiting(fixture).isEmpty(),
            "the fixture must hold NO waiting question at all, or this test proves nothing about the guard",
        )
        val waiting = cli(fixture.port, mapOf(AccessPolicy.SESSION_ID_ENV to "no-questions-here"), "waiting")
        assertEquals(0, waiting.exit, "an empty listing is not a failure: out=${waiting.out} err=${waiting.err}")
        assertTrue(
            waiting.out.contains("Nothing is waiting."),
            "`waiting` must SAY nothing is waiting, not print the header over nothing: ${waiting.out}",
        )
        assertFalse(
            waiting.out.contains(WAITING_HEADER),
            "the bare table header reads as a listing with no rows: ${waiting.out}",
        )
    }
}
