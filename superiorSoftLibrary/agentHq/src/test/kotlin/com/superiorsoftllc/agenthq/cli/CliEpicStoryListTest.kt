package com.superiorsoftllc.agenthq.cli

import com.superiorsoftllc.agenthq.daemon.AccessPolicy
import com.superiorsoftllc.agenthq.daemon.DaemonFixture
import com.superiorsoftllc.agenthq.daemon.TestWait
import com.superiorsoftllc.agenthq.epics.Epic
import com.superiorsoftllc.agenthq.protocol.Envelope
import com.superiorsoftllc.agenthq.protocol.Ticket
import com.superiorsoftllc.agenthq.runner.ProcessRunner
import com.superiorsoftllc.agenthq.runner.StubScript
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * AGENT-CLI-2 / E1-e: `agenthq epics`, `epic show`, `tickets --epic` and the deprecated `stories` --
 * the Project -> Epic -> Ticket hierarchy read from the CLI, over a
 * REAL daemon and the REAL [Cli.run], the same code path the installed binary runs.
 *
 * TWO FACTS ABOUT THE WIRE THIS FILE EXISTS TO PIN, both verified in the daemon source:
 *
 * 1. THE TWO LISTINGS ARE NOT SYMMETRIC. `epic_list` carries the RAW project id and
 *    [com.superiorsoftllc.agenthq.epics.EpicStore.list] filters on an EXACT match
 *    (`it.projectId == projectId.trim()`), so an empty payload lists NOTHING -- it does not mean
 *    "every project". `story_list` with an empty payload means the WHOLE board
 *    ([com.superiorsoftllc.agenthq.mvp.BoardHandler.board] treats null as no filter). So `epics`
 *    REQUIRES a project id and `stories` does not, and a client that assumed symmetry would either
 *    print an empty epic list or print every project's stories under a project filter.
 * 2. `epic` frames arrive UNSOLICITED. A `story_save` carrying an `epicId` makes the daemon push the
 *    changed epic to EVERY connected client ([broadcastEpic]). So a socket waiting for an `epics`
 *    answer can be handed an `epic` frame it never asked for, and the reply must be matched on the
 *    `epics` frame TYPE specifically.
 *
 * Nothing here creates or moves anything: this is the READ path only.
 */
class CliEpicStoryListTest {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val fixtures = mutableListOf<DaemonFixture>()

    private companion object {
        /** The `epics` table header, pinned here so a renamed column fails the row parser loudly. */
        const val EPIC_HEADER = "ID | NAME | GOAL | STORIES"
    }

    private fun launch(script: StubScript = StubScript.default(), runner: ProcessRunner? = null): DaemonFixture =
        DaemonFixture.launch(
            script,
            runner,
            Files.createTempDirectory("agenthq-epic-story-cli-test").toFile(),
        ).also { fixtures += it }

    @AfterTest
    fun stopAll() {
        fixtures.asReversed().forEach {
            runCatching { it.stop() }
            runCatching { it.deleteState() }
        }
        fixtures.clear()
        seen.clear()
    }

    private data class Result(val exit: Int, val out: String, val err: String)

    /**
     * How many `project` / `epic` / `story` answers the fixture client has already consumed.
     *
     * It has to COUNT them: [WsTestClient.await] returns the OCCURRENCE-th frame of a type, so a
     * helper that always asks for occurrence 0 hands back the FIRST project / epic / story for every
     * call. The ids it then saves into would all be that first one, and a test would assert against
     * ids that never existed.
     */
    private val seen = mutableMapOf<String, Int>()

    /** Await the next answer of [type] this client has not consumed yet, and count it. */
    private fun DaemonFixture.awaitNext(type: String): Envelope {
        val occurrence = seen.getOrElse(type) { 0 }
        seen[type] = occurrence + 1
        return client.await(type, occurrence = occurrence, timeoutMs = TestWait.defaultMs)
    }

    private fun cli(port: Int, vararg args: String): Result = cli(port, emptyMap(), *args)

    private fun cli(port: Int, env: Map<String, String>, vararg args: String): Result {
        val out = StringBuilder()
        val err = StringBuilder()
        val getenv: (String) -> String? = { key -> env[key] }
        val exit = Cli.run(arrayOf(*args, "--port", port.toString()), out, err, getenv)
        return Result(exit, out.toString(), err.toString())
    }

    // ------------------------------------------------------------------------
    // fixtures
    // ------------------------------------------------------------------------

    /**
     * A project, by name, returning the id THE DAEMON MINTS.
     *
     * It is a real uuid-ish id, not the free text a ticket's `projectId` accepts: `project_save` is
     * what creates the project an epic and a story board hang off, so these tests filter by the id
     * the daemon returned and not by the name that was typed.
     */
    private fun project(fixture: DaemonFixture, name: String): String {
        fixture.send(Envelope(Envelope.PROJECT_SAVE, """{"name":"$name"}"""))
        return json.parseToJsonElement(fixture.awaitNext(Envelope.PROJECT).data)
            .jsonObject["id"]!!.jsonPrimitive.content
    }

    /** An epic under [projectId]; returns its id. */
    private fun epic(fixture: DaemonFixture, projectId: String, name: String, goal: String = ""): String {
        fixture.send(Envelope(Envelope.EPIC_SAVE, """{"projectId":"$projectId","name":"$name","goal":"$goal"}"""))
        return json.parseToJsonElement(fixture.awaitNext(Envelope.EPIC).data)
            .jsonObject["id"]!!.jsonPrimitive.content
    }

    /** The NAME column of every row an `epics` listing printed, in printed order. */
    private fun epicNames(out: String): List<String> =
        out.lineSequence()
            .map { it.trim().trimEnd('\r') }
            .filter { it.isNotBlank() && it != EPIC_HEADER && !it.startsWith("No epics") }
            .map { row -> row.split(" | ").getOrNull(1) ?: error("not an epic row: '$row'") }
            .toList()

    // =========================================================================
    // epics
    // =========================================================================

    /**
     * 1. `epics --project <id>` lists that project's epics, from the daemon, with their real names
     * and goals -- and NEVER another project's.
     */
    @Test
    fun epicsListsOnlyTheProjectsEpics() {
        val fixture = launch()
        fixture.client.await(Envelope.READY)
        val a = project(fixture, "Alpha")
        val b = project(fixture, "Beta")
        epic(fixture, a, "Alpha epic one", "first goal")
        epic(fixture, a, "Alpha epic two")
        epic(fixture, b, "Beta epic", "other goal")

        val listed = cli(fixture.port, "epics", "--project", a)
        assertEquals(0, listed.exit, "epics failed: ${listed.err}")
        assertEquals(
            listOf("Alpha epic one", "Alpha epic two"),
            epicNames(listed.out),
            "epics --project must print that project's epics and no other: ${listed.out}",
        )
        assertTrue(listed.out.contains("first goal"), "epics must show the epic's goal: ${listed.out}")
        assertFalse(listed.out.contains("Beta epic"), "project B's epic leaked into --project A: ${listed.out}")
    }

    /**
     * 2. THE ASYMMETRY, as a usage error: `epics` with no project id is refused BEFORE connecting.
     *
     * It has to be, because there is nothing to send that would work: `epic_list` filters on an exact
     * project match, so the empty payload answers `[]`, which the CLI would then print as "this project
     * has no epics" for a daemon full of them. Silently answering "no epics" is the wrong answer; the
     * honest one is to say the listing needs a project.
     *
     * Pinned against a DEAD port: reaching it would be the bug, and the connection error is a
     * different message, so the assertion below catches it.
     */
    @Test
    fun epicsWithoutAProjectIsRefusedBeforeConnecting() {
        val out = StringBuilder()
        val err = StringBuilder()
        val exit = Cli.run(arrayOf("epics", "--port", "9999"), out, err, { null })
        assertEquals(2, exit, "epics with no project must be a usage error: $err")
        // The exact FIRST line, not "the word project somewhere in the usage text": `epics` was an
        // unknown command before this ticket, and that refusal also exits 2 while printing a usage
        // block full of `--project`. Only this wording is the real refusal.
        assertEquals(
            "error: epics requires a project id (--project <id>, or ${AccessPolicy.PROJECT_ID_ENV})",
            err.toString().lineSequence().first().trimEnd('\r'),
            "the refusal must name what is missing, on its own line",
        )
        assertFalse(
            err.toString().contains("daemon not running"),
            "the refusal must happen BEFORE any connection: $err",
        )
        assertEquals("", out.toString(), "a refused usage prints no listing")
    }

    /**
     * 2b. Inside a spawned agent there is no `--project` to type -- the daemon exports
     * AGENTHQ_PROJECT_ID -- so plain `epics` is that agent's own project's epics.
     */
    @Test
    fun epicsDefaultsProjectFromTheAgentEnvironment() {
        val fixture = launch()
        fixture.client.await(Envelope.READY)
        val a = project(fixture, "Alpha")
        val b = project(fixture, "Beta")
        epic(fixture, a, "Alpha epic")
        epic(fixture, b, "Beta epic")

        val listed = cli(fixture.port, mapOf(AccessPolicy.PROJECT_ID_ENV to a), "epics")
        assertEquals(0, listed.exit, "epics with AGENTHQ_PROJECT_ID failed: ${listed.err}")
        assertEquals(
            listOf("Alpha epic"),
            epicNames(listed.out),
            "AGENTHQ_PROJECT_ID must scope plain `epics` to that project: ${listed.out}",
        )
    }

    /** 2c. A project with no epics is an empty listing, exit 0 -- not an error. */
    @Test
    fun epicsOnAProjectWithNoneSaysSoAndSucceeds() {
        val fixture = launch()
        fixture.client.await(Envelope.READY)
        val empty = project(fixture, "Empty")

        val listed = cli(fixture.port, "epics", "--project", empty)
        assertEquals(0, listed.exit, "an epic-less project is not a failure: ${listed.err}")
        assertTrue(listed.out.contains("No epics"), "an empty project must say it has none: ${listed.out}")
    }

    /**
     * 3. THE UNSOLICITED PUSH, through the REAL command, WITH THE PUSH PROVEN.
     *
     * A `story_save` carrying an `epicId` makes the daemon append the story id and PUSH the changed
     * `epic` to every connected client ([broadcastEpic]). The CLI's own socket is one of them, so it
     * can be handed an `epic` frame it never asked for. The listing must still be the `epics` answer:
     * a client that matched "the first epic-ish frame that arrived" would print one pushed epic
     * object instead of the project's list.
     *
     * WHY THE CONCURRENCY IS HERE AND WHY IT IS NO LONGER A VACUOUS TEST. The pushes have to happen
     * WHILE the command is waiting for its answer, which means another thread: the CLI blocks inside
     * [Cli.run]. The earlier version of this test asserted only the OUTCOME, so it passed even when
     * not one push landed in the window -- a green test that had exercised nothing. It now refuses
     * to launch the command until the daemon has demonstrably pushed at least one changed epic, so
     * the command always runs against a LIVE hammer. (An earlier fix counted pushes after the
     * command returned; under load the first push could land after an in-process `Cli.run`
     * finished, so that count raced and flaked -- BUG-FLAKE-EPICPUSH.)
     *
     * [aBufferedUnsolicitedEpicFrameIsNotTheEpicsAnswer] is the deterministic half: no thread, no
     * window, the pushed frame provably in the buffer before the request goes out. This one is the
     * end-to-end half through the installed binary's own code path.
     *
     * WHAT THIS TEST DOES AND DOES NOT PROVE (read before trusting a green run). The gate proves
     * the hammer was pushing when the command STARTED. The "request sent" and "answer received"
     * instants happen inside [Cli.run] and are NOT observable from here, so nothing proves a push
     * landed exactly between them. The 15ms hammer makes that likely; nothing here guarantees it.
     *
     * Therefore [aBufferedUnsolicitedEpicFrameIsNotTheEpicsAnswer] is the AUTHORITATIVE proof of
     * the guarantee. This test is BEST-EFFORT coverage of the real-binary path: it catches wiring
     * regressions the seam test cannot see, but it is not evidence on its own that the trap was
     * exercised.
     */
    @Test
    fun epicsIgnoresUnsolicitedEpicPushesWhileItLists() {
        val fixture = launch()
        fixture.client.await(Envelope.READY)
        val a = project(fixture, "Alpha")
        val epicId = epic(fixture, a, "Alpha epic one")
        epic(fixture, a, "Alpha epic two")

        // `fixture.client` is connected for the whole test, so it receives every broadcast the daemon
        // makes -- which is how the test SEES the pushes it caused, and counts them.
        val broadcastsBefore = fixture.client.of(Envelope.EPIC).size

        val hammering = Thread {
            var n = 0
            while (!Thread.currentThread().isInterrupted) {
                runCatching {
                    fixture.send(
                        Envelope(
                            Envelope.STORY_SAVE,
                            """{"projectId":"$a","title":"pushed $n","epicId":"$epicId"}""",
                        ),
                    )
                }
                n++
                Thread.sleep(15)
            }
        }
        hammering.isDaemon = true
        hammering.start()
        try {
            // Gate the CLI on a LIVE hammer: do not launch until the daemon has demonstrably pushed
            // at least one changed epic. This is an ordering wait, not a timing bet -- under load
            // the hammer's first push can land later than an in-process `Cli.run` takes to finish,
            // and the old after-the-fact count then saw zero pushes (BUG-FLAKE-EPICPUSH). Once the
            // hammer is proven live, the command always runs while it is pushing.
            val liveDeadline = System.currentTimeMillis() + 5_000
            while (System.currentTimeMillis() < liveDeadline &&
                fixture.client.of(Envelope.EPIC).size <= broadcastsBefore
            ) {
                Thread.sleep(10)
            }
            assertTrue(
                fixture.client.of(Envelope.EPIC).size > broadcastsBefore,
                "the hammer must be pushing changed epics before `epics` runs, or this test proves " +
                    "nothing: before=$broadcastsBefore now=${fixture.client.of(Envelope.EPIC).size}",
            )

            val listed = cli(fixture.port, "epics", "--project", a)
            assertEquals(0, listed.exit, "epics must not be confused by pushed epic frames: ${listed.err}")
            assertEquals(
                listOf("Alpha epic one", "Alpha epic two"),
                epicNames(listed.out),
                "epics must answer with the project's epics frame, not a pushed single epic: ${listed.out}",
            )
        } finally {
            hammering.interrupt()
            hammering.join(2_000)
        }
    }

    /**
     * 3b. THE SAME TRAP, DETERMINISTICALLY. [FrameStream] is driven directly here so the unsolicited
     * `epic` frame is guaranteed to be sitting in the socket's buffer BEFORE the `epic_list` request
     * is even sent -- no thread, no race, no timing window.
     *
     * [epicsIgnoresUnsolicitedEpicPushesWhileItLists] proves it through the real binary; this proves
     * it through the seam, so a regression cannot hide behind a push that happened to arrive after
     * the answer. Matching the reply on "an epic-ish frame" instead of on `epics` returns that
     * single pushed epic here, every single run.
     */
    @Test
    fun aBufferedUnsolicitedEpicFrameIsNotTheEpicsAnswer() = runBlocking {
        val fixture = launch()
        fixture.client.await(Envelope.READY)
        val a = project(fixture, "Alpha")
        val epicId = epic(fixture, a, "Alpha epic one")
        epic(fixture, a, "Alpha epic two")

        // A second connection, so the daemon has a client to PUSH to besides our stream.
        val pusher = fixture.connectClient()
        val err = StringBuilder()
        val stream = FrameStream.open(Target("ws://127.0.0.1:${fixture.port}/ws", null, "none"), err)
        try {
            // Save a story INTO the epic: the daemon appends the story id and pushes that epic to
            // every connected client -- including `stream`, which has asked for nothing.
            pusher.send(
                Envelope(
                    Envelope.STORY_SAVE,
                    """{"projectId":"$a","title":"joined","epicId":"$epicId"}""",
                ),
            )
            pusher.await(Envelope.STORY, timeoutMs = 4_000)

            // Wait until that push really is in OUR buffer, so the reply cannot race ahead of it.
            val deadline = System.currentTimeMillis() + 5_000
            while (System.currentTimeMillis() < deadline &&
                stream.peek(Envelope.EPIC) == null
            ) {
                delay(20)
            }
            assertTrue(
                stream.peek(Envelope.EPIC) != null,
                "the daemon must have pushed the changed epic to our socket (the trap under test)",
            )

            // Now the request. Its answer must be the `epics` FRAME, not the `epic` already buffered.
            val answer = stream.request(
                Envelope(Envelope.EPIC_LIST, a),
                answerType = Envelope.EPICS,
                what = "epic_list",
                idempotent = true,
            )
            assertEquals(
                Envelope.EPICS,
                answer.type,
                "the reply must be matched on the epics frame type, not on the pushed epic: ${answer.data}",
            )
            val epics = json.decodeFromString(ListSerializer(Epic.serializer()), answer.data)
            assertEquals(
                listOf("Alpha epic one", "Alpha epic two"),
                epics.map { it.name },
                "the epics frame is the project's whole list: $answer",
            )
        } finally {
            stream.close()
        }
    }

    // =========================================================================
    // E1-e: `epic show`, `tickets --epic` and the deprecated `stories` read the TICKET board
    // =========================================================================

    /** The tickets-board header every E1-e listing prints: one model, one row shape. */
    private val ticketHeader = "ID | STATUS | TITLE | AGENT"

    /** A board ticket titled [title] in [projectId], filed under [epicId] when given; the stored ticket. */
    private fun ticket(fixture: DaemonFixture, projectId: String, title: String, epicId: String? = null): Ticket {
        val withEpic = if (epicId == null) "" else ""","epicId":"$epicId""""
        fixture.send(Envelope(Envelope.TICKET_SAVE, """{"title":"$title","projectId":"$projectId"$withEpic}"""))
        // Titles are unique per fixture, so matching on the title finds THIS save's push and no other.
        val frame = fixture.client.awaitWhere("the stored ticket '$title'") { env ->
            env.type == Envelope.TICKET &&
                runCatching { json.decodeFromString(Ticket.serializer(), env.data) }.getOrNull()?.title == title
        }
        return json.decodeFromString(Ticket.serializer(), frame.data)
    }

    /** The TITLE column of every ticket row printed, in printed order; header and empty-state lines are not rows. */
    private fun ticketTitles(out: String): List<String> =
        out.lineSequence()
            .map { it.trimEnd('\r') }
            .filter { it.contains(" | ") && it != ticketHeader }
            .map { row -> row.split(" | ")[2] }
            .toList()

    /** Project A with epics A and B, two tickets in A, one in B, one loose -- interleaved, so board order matters. */
    private data class Board(val a: String, val epicA: String, val epicB: String)

    private fun seedBoard(fixture: DaemonFixture): Board {
        val a = project(fixture, "Alpha")
        val epicA = epic(fixture, a, "Epic A")
        val epicB = epic(fixture, a, "Epic B")
        ticket(fixture, a, "A one", epicA)
        ticket(fixture, a, "B one", epicB)
        ticket(fixture, a, "Loose")
        ticket(fixture, a, "A two", epicA)
        return Board(a, epicA, epicB)
    }

    /**
     * 4. `epic show <prefix>` prints the epic's id and title, then exactly the TICKETS filed under it, in
     * board order, as the tickets board's rows. Another epic's ticket and a ticket with no epic, in the
     * same project, never appear.
     */
    @Test
    fun epicShowPrintsExactlyTheEpicsTickets() {
        val fixture = launch()
        fixture.client.await(Envelope.READY)
        val board = seedBoard(fixture)

        val shown = cli(fixture.port, "epic", "show", board.epicA.takeLast(8), "--project", board.a)
        assertEquals(0, shown.exit, "epic show failed: ${shown.err}")
        assertTrue(shown.out.contains("ID: ${board.epicA}\n"), "epic show must print the epic's id: ${shown.out}")
        assertTrue(shown.out.contains("TITLE: Epic A\n"), "epic show must print the epic's title: ${shown.out}")
        assertTrue(shown.out.contains("$ticketHeader\n"), "epic show must print the tickets header: ${shown.out}")
        assertEquals(
            listOf("A one", "A two"),
            ticketTitles(shown.out),
            "epic show must list exactly the epic's tickets, in board order: ${shown.out}",
        )
    }

    /** 4b. An epic no ticket is filed under says so, exit 0, and prints no bare header. */
    @Test
    fun epicShowOfAnEpicWithNoTicketsSaysSo() {
        val fixture = launch()
        fixture.client.await(Envelope.READY)
        val a = project(fixture, "Alpha")
        val empty = epic(fixture, a, "Empty epic")
        ticket(fixture, a, "Loose")

        val shown = cli(fixture.port, "epic", "show", empty.takeLast(8), "--project", a)
        assertEquals(0, shown.exit, "an epic with no tickets is not a failure: ${shown.err}")
        assertTrue(
            shown.out.contains("No tickets in epic ${empty.takeLast(8)}."),
            "an empty epic must say it has no tickets: ${shown.out}",
        )
        assertFalse(shown.out.contains(ticketHeader), "no bare header for an empty epic: ${shown.out}")
        assertEquals(emptyList(), ticketTitles(shown.out), "a loose ticket is not the epic's: ${shown.out}")
    }

    /** 5. `tickets --epic <prefix>` narrows the board to that epic's tickets. */
    @Test
    fun ticketsWithEpicListsOnlyThatEpicsTickets() {
        val fixture = launch()
        fixture.client.await(Envelope.READY)
        val board = seedBoard(fixture)

        val listed = cli(fixture.port, "tickets", "--project", board.a, "--epic", board.epicB.takeLast(8))
        assertEquals(0, listed.exit, "tickets --epic failed: ${listed.err}")
        assertEquals(listOf("B one"), ticketTitles(listed.out), "tickets --epic must print that epic's only: ${listed.out}")
    }

    /** 5b. `tickets --epic` without a project has no epic list to resolve the prefix in: refused before connecting. */
    @Test
    fun ticketsWithEpicButNoProjectIsRefusedBeforeConnecting() {
        val out = StringBuilder()
        val err = StringBuilder()
        val exit = Cli.run(arrayOf("tickets", "--epic", "abcd1234", "--port", "9999"), out, err, { null })
        assertEquals(2, exit, "tickets --epic with no project must be a usage error: $err")
        assertEquals(
            "error: tickets --epic needs a project id (--project <id>, or ${AccessPolicy.PROJECT_ID_ENV})",
            err.toString().lineSequence().first().trimEnd('\r'),
        )
        assertFalse(err.toString().contains("daemon not running"), "refused BEFORE any connection: $err")
        assertEquals("", out.toString())
    }

    /**
     * 6. `stories --epic` is deprecated: exactly ONE deprecation line first, then output IDENTICAL to
     * `tickets --epic` -- the same rows, from the same board.
     */
    @Test
    fun storiesPrintsOneDeprecationLineThenTheSameRowsAsTicketsEpic() {
        val fixture = launch()
        fixture.client.await(Envelope.READY)
        val board = seedBoard(fixture)
        val prefix = board.epicA.takeLast(8)

        val tickets = cli(fixture.port, "tickets", "--project", board.a, "--epic", prefix)
        val stories = cli(fixture.port, "stories", "--project", board.a, "--epic", prefix)
        assertEquals(0, stories.exit, "stories failed: ${stories.err}")
        val firstBreak = stories.out.indexOf('\n')
        assertTrue(firstBreak > 0, "stories must print a deprecation line: ${stories.out}")
        assertEquals(
            DEPRECATION,
            stories.out.substring(0, firstBreak).trimEnd('\r'),
            "the first line must be the deprecation: ${stories.out}",
        )
        assertEquals(
            tickets.out,
            stories.out.substring(firstBreak + 1),
            "after the deprecation line, stories must print exactly what tickets --epic prints",
        )
        assertEquals(listOf("A one", "A two"), ticketTitles(stories.out))
    }

    /**
     * 6b. `stories` with no `--epic` keeps its meaning on the one model: every ticket filed under SOME
     * epic (what a story was), never a loose ticket; still one deprecation line first.
     */
    @Test
    fun storiesWithoutEpicListsEveryTicketThatHasAnEpic() {
        val fixture = launch()
        fixture.client.await(Envelope.READY)
        val board = seedBoard(fixture)

        val listed = cli(fixture.port, "stories", "--project", board.a)
        assertEquals(0, listed.exit, "stories failed: ${listed.err}")
        assertEquals(DEPRECATION, listed.out.lineSequence().first().trimEnd('\r'), "deprecation first: ${listed.out}")
        assertEquals(1, listed.out.lines().count { it.startsWith("deprecated") }, "ONE deprecation line: ${listed.out}")
        assertEquals(
            listOf("A one", "B one", "A two"),
            ticketTitles(listed.out),
            "stories must list the epic'd tickets in board order and never the loose one: ${listed.out}",
        )
    }

    /** 6c. The old story column `doing` still filters, as the ticket column it became (`in_progress`). */
    @Test
    fun storiesStatusDoingIsTheInProgressColumn() {
        val fixture = launch()
        fixture.client.await(Envelope.READY)
        val board = seedBoard(fixture)
        val prefix = board.epicA.takeLast(8)

        val doing = cli(fixture.port, "stories", "--project", board.a, "--epic", prefix, "--status", "doing")
        assertEquals(0, doing.exit, "stories --status doing must be accepted: ${doing.err}")
        assertEquals(emptyList(), ticketTitles(doing.out), "nothing is in progress yet: ${doing.out}")
        assertTrue(doing.out.contains("No tickets."), "an empty column says so: ${doing.out}")

        val todo = cli(fixture.port, "stories", "--project", board.a, "--epic", prefix, "--status", "todo")
        assertEquals(listOf("A one", "A two"), ticketTitles(todo.out), "todo keeps both: ${todo.out}")
    }

    /** 6d. A column the ticket board does not have is a usage error naming its columns, refused before connecting. */
    @Test
    fun storiesWithUnknownStatusIsAUsageError() {
        val out = StringBuilder()
        val err = StringBuilder()
        val exit = Cli.run(arrayOf("stories", "--status", "bogus", "--port", "9999"), out, err, { null })
        assertEquals(2, exit, "an unknown column must be a usage error: $err")
        assertTrue(err.toString().contains("backlog, todo, in_progress, pending, done"), "the refusal names the columns: $err")
        assertFalse(err.toString().contains("daemon not running"), "refused before connecting: $err")
    }

    /** 7. An epic prefix that matches nothing is a clean failure naming the prefix, with no rows. */
    @Test
    fun storiesWithAnUnknownEpicFailsNamingIt() {
        val fixture = launch()
        fixture.client.await(Envelope.READY)
        val a = project(fixture, "Alpha")

        val listed = cli(fixture.port, "stories", "--project", a, "--epic", "nosuchep")
        assertNotEquals(0, listed.exit, "an unknown epic must not report success: ${listed.out}")
        assertTrue(listed.err.contains("no epic matches 'nosuchep'"), "the miss must be named: ${listed.err}")
        assertFalse(listed.out.contains(ticketHeader), "a refused listing prints no rows: ${listed.out}")
    }
}

/** The one line `agenthq stories` prints before its rows (E1-e). */
private const val DEPRECATION =
    "deprecated: `agenthq stories` lists board tickets now; use `agenthq tickets --epic <id|prefix>`"
