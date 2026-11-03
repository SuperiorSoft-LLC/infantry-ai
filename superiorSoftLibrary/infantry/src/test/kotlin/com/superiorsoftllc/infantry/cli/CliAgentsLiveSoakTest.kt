// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.cli

import com.superiorsoftllc.infantry.daemon.WsTestClient
import com.superiorsoftllc.infantry.protocol.AgentProfile
import com.superiorsoftllc.infantry.protocol.Envelope
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.File
import java.net.URI
import java.net.http.HttpClient
import java.net.http.WebSocket
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * SOAK-1: the live-daemon evidence `CliAcceptanceTest.agentsListsProfilesFromDaemon` cannot give.
 *
 * The acceptance test boots the daemon IN-PROCESS ([com.superiorsoftllc.infantry.daemon.DaemonFixture])
 * and drives [Cli.run] in the same JVM, then saves its profiles and only THEN lists them. That proves
 * the await strategy is deterministic (which is what 38a8867e fixed) and nothing more: the two saves
 * have provably completed before the listing starts, so no listing can ever race them. The reported
 * symptom -- the agents listing showing Backend but not QA -- was a real user-visible failure of the
 * CLI against a daemon someone started with `infantry daemon`, so it has to be re-tested there: a
 * daemon in its own OS process, a CLI in its own OS process, a real socket, real scheduling, a real
 * SQLite file, and saves that are still in flight while the listings are taken.
 *
 * The property under soak, in the form the symptom took:
 *
 * > Every `infantry agents` listing the CLI prints is a WHOLE PREFIX of the profiles the daemon has
 * > saved -- never a partial view with a hole in it, never a row the daemon does not hold, never a
 * > duplicated row, and never a missing row for a profile that was already committed when the
 * > listing was taken.
 *
 * "Backend but not QA" is precisely a listing that is not a prefix: the saves commit in a fixed
 * order, so an honest snapshot of a partially-saved set contains the first k of them. A hole means
 * the answer was assembled from two different points in time, or a row was lost on the way to the
 * terminal -- and that is the class of bug the fixture cannot see.
 *
 * WHY THE TEST CANNOT PASS VACUOUSLY: the soak fails unless it actually observes the daemon
 * MID-SAVE. [listingsTakenMidSave] counts the listings that were taken while at least one profile was
 * committed and at least one was not, and a run with none is reported as proving nothing rather than
 * as a pass. Every save's answer frame is awaited too: a save whose frame never arrives fails the
 * test instead of quietly leaving the next listing legitimately short.
 */
class CliAgentsLiveSoakTest {

    private val daemons = mutableListOf<LiveDaemonProcess>()
    private val sockets = CopyOnWriteArrayList<WebSocket>()
    private val clients = CopyOnWriteArrayList<WsTestClient>()

    /**
     * This run's evidence keeper. One per test JVM, because one Gradle `test` invocation is one run:
     * Gradle writes a single result file per test CLASS, so an in-JVM repeat loop cannot have its own
     * XML and the XML that matters is the invocation's.
     */
    private val archive: SoakRunArchive = SoakRunArchive.forCurrentRun()

    /**
     * Preserve the PREVIOUS run's results before this run's test task overwrites them.
     *
     * Gradle deletes `build/test-results` at the start of every test task, which means the XML of the
     * soak that failed ten minutes ago -- and the only record of why -- is gone by the time anyone
     * reads it. `@BeforeTest` on the first test method of the JVM is the last moment at which those
     * files still exist; the later methods see an already-archived directory and find nothing to do.
     */
    @BeforeTest
    fun preserveThePreviousRunsResults() {
        val archived = archive.archivePreviousRunOnce()
        val dir = archived.directory
        if (dir == null) {
            println(
                "SOAK-1: run ${archive.runIndex} has no previous run to preserve " +
                    "(no results in ${archive.resultsDir.absolutePath})",
            )
        } else {
            println(
                "SOAK-1: preserved run ${archived.previousRunIndex} -- " +
                    archived.files.joinToString { it.name } + " -- under ${dir.absolutePath}",
            )
        }
    }

    /**
     * Runs [body], and if it fails for ANY reason, makes the failure message say where this run's XML
     * is and can be found afterwards.
     *
     * Wrapping the bodies, not the individual assertions, is the point: `fail()`, `error()`, a
     * missing answer frame and a timeout all throw, and there is no failure path that escapes this.
     */
    private fun withEvidence(what: String, body: () -> Unit) {
        try {
            body()
        } catch (failure: Throwable) {
            val banner = buildString {
                appendLine()
                appendLine("SOAK-1: $what FAILED. This run's JUnit XML is preserved at the path below.")
                append(archive.evidenceBanner())
            }
            System.err.println(banner)
            throw if (failure is AssertionError) AssertionError("${failure.message}\n$banner", failure)
            else IllegalStateException("${failure::class.simpleName}: ${failure.message}\n$banner", failure)
        }
    }

    /**
     * Runs [body] as iteration [iteration] of the opt-in repeat loop: the failure is recorded and the
     * loop CONTINUES, so one green iteration cannot hide the 1-in-18 failure this soak exists for.
     * Every iteration's verdict is written under this run's directory before the loop reports.
     */
    private fun repeatIteration(soak: String, iteration: Int, body: () -> String): String {
        val verdict = try {
            val summary = body()
            "PASS iteration $iteration: $summary"
        } catch (failure: Throwable) {
            "FAIL iteration $iteration: ${failure::class.simpleName}: ${failure.message}"
        }
        println(verdict)
        archive.writeIterationNote(soak, iteration, verdict)
        return verdict
    }

    /**
     * The opt-in soak loop, driven by `-Dinfantry.soak.repeat=N`.
     *
     * - REPEAT == 1 (default): body runs once, inside [withEvidence] so a failure still names this
     *   run's XML. Behaviour identical to wrapping in [withEvidence] directly.
     * - REPEAT > 1: body runs up to N times. Each iteration is caught by [repeatIteration] so a single
     *   failure does not abort the loop -- the rest of the iterations still report, which is the
     *   only way to surface a 1-in-18 flake from a single Gradle test invocation. Every verdict is
     *   written under this run's directory by [repeatIteration], so the per-iteration evidence
     *   survives even when the run eventually fails.
     *
     * At the end, if ANY iteration failed, the test fails with a single AssertionError pointing at
     * the run directory. The first failing iteration's message is lost on purpose: the per-iteration
     * notes carry the actual reason, and a long message in the JUnit summary would be a worse
     * summary than a path.
     */
    private fun runSoak(name: String, body: () -> Unit) {
        val repeat = REPEAT
        if (repeat <= 1) {
            withEvidence(name, body)
            return
        }
        val total = repeat
        val failures = (1..total).count { i ->
            val verdict = repeatIteration(name, i) {
                withEvidence("$name iteration $i/$total") { body() }
                "ok"
            }
            verdict.startsWith("FAIL")
        }
        if (failures > 0) {
            throw AssertionError(
                "$name: $failures of $total iterations failed. Per-iteration notes under " +
                    "${archive.runDir.absolutePath}",
            )
        }
    }

    private fun stateDir(tag: String): File =
        Files.createTempDirectory("infantry-live-soak-$tag").toFile()

    private fun launchDaemon(dir: File, dbFile: File = File(dir, "infantry.db")): LiveDaemonProcess =
        LiveDaemonProcess.launch(dir, dbFile = dbFile).also { daemons += it }

    /** A real WebSocket to the live daemon, waited on until its `ready` frame, like any client. */
    private fun connect(port: Int): WsTestClient {
        val client = WsTestClient()
        val socket = HttpClient.newHttpClient()
            .newWebSocketBuilder()
            .buildAsync(URI.create("ws://127.0.0.1:$port/ws"), client)
            .join()
        sockets += socket
        clients += client
        client.await(Envelope.READY)
        return client
    }

    @AfterTest
    fun stopEverything() {
        clients.forEach { runCatching { it.close() } }
        sockets.forEach { runCatching { it.sendClose(WebSocket.NORMAL_CLOSURE, "done").join() } }
        daemons.asReversed().forEach {
            runCatching { it.stop() }
            runCatching { it.deleteState() }
        }
        clients.clear()
        sockets.clear()
        daemons.clear()
    }

    // ------------------------------------------------------------------------
    // the ordering the merged fix rests on, checked on a LIVE daemon
    // ------------------------------------------------------------------------

    /**
     * SOAK-1a: on a live daemon, a profile is ALREADY in `agent_list` at the moment its `agent` answer
     * frame arrives -- for every save, every time.
     *
     * This is the `agents.save(...)`-then-`Envelope(Envelope.AGENT, ...)` ordering at
     * `DaemonMain.kt:1206`, exercised against a separate process instead of the fixture. Each save is
     * followed IMMEDIATELY by an `agent_list` on the same connection: if the answer frame were ever
     * emitted before the profile was committed, the listing would come back short and this fails,
     * naming the save.
     *
     * Weak on its own -- a daemon handles one connection's frames in order, so the list request
     * cannot overtake the save -- and kept for that reason: [cliListingsAreAlwaysAWholePrefix] is the
     * cross-connection, cross-process version of the same guarantee, and this one localises a
     * regression to the exact frame instead of leaving it to the slower test.
     */
    @Test
    fun everyProfileIsListableTheMomentItsAgentFrameArrives() = runSoak("SOAK-1a") {
        val daemon = launchDaemon(stateDir("ordering"))
        val client = connect(daemon.port)
        val expected = mutableListOf<String>()

        // Counts only saves for which the daemon actually returned an answer frame, so the guard at
        // the end cannot be satisfied by the same loop that increments it.
        var answered = 0

        for (i in 1..PAIRS) {
            for ((name, role) in listOf("Backend $i" to "backend", "QA $i" to "qa")) {
                expected += name
                client.send(
                    Envelope(
                        Envelope.AGENT_SAVE,
                        """{"id":"soak-${role}-${"%02d".format(i)}","name":"$name","role":"$role"}""",
                    ),
                )
                // The daemon's own answer, not a sleep: the listing below is taken because this
                // profile is committed, never because enough time passed.
                val answer = client.awaitOrNull(Envelope.AGENT, expected.size - 1, FRAME_TIMEOUT_MS)
                    ?: fail(
                        "no 'agent' answer for '$name' within ${FRAME_TIMEOUT_MS}ms; " +
                            "frames seen: ${client.frames.map { it.type }}",
                    )
                assertTrue(
                    "\"$name\"" in answer.data,
                    "the answer frame for '$name' does not carry it: ${answer.data}",
                )
                answered++

                // The listing is REQUESTED here, not merely waited for: an `agents` answer only
                // exists because this connection asked for one, immediately after the save.
                client.send(Envelope(Envelope.AGENT_LIST, ""))
                val listed = client.await(Envelope.AGENTS, expected.size - 1, FRAME_TIMEOUT_MS)
                val names = profileNames(listed.data)
                assertEquals(
                    expected.toSet(),
                    names.toSet(),
                    "after the answer frame for '$name' the daemon listed ${names.toSet()}, " +
                        "expected exactly ${expected.toSet()}: a frame was published before its profile was stored",
                )
            }
        }

        // Each iteration above fails outright if the daemon does not answer, so reaching here means
        // every save was answered. This asserts the COUNT independently, rather than against a
        // collection the same loop filled - the previous form could not have failed.
        assertEquals(
            PAIRS * 2,
            answered,
            "only $answered of the ${PAIRS * 2} saves produced an `agent` answer: this run proved " +
                "nothing about the moment a frame arrives",
        )
        assertEquals(
            PAIRS * 2,
            expected.size,
            "the loop ran $answered times but built ${expected.size} expectations",
        )
    }

    // ------------------------------------------------------------------------
    // the CLI itself, as a separate process, against the live daemon
    // ------------------------------------------------------------------------

    /**
     * SOAK-1b: THE soak. Real CLI processes listing a real daemon while real saves are in flight.
     *
     * A background writer saves `PAIRS` profiles (Backend n, then QA n, in that order) over a real
     * socket, pacing itself so the burst lasts long enough for listings to land inside it. The test
     * thread launches one `infantry agents` PROCESS per listing -- its own JVM, its own socket, its own
     * exit code -- and checks each listing against the writer's commit order:
     *
     *  - exit 0, and nothing on stderr (a listing that failed quietly is not a listing);
     *  - no duplicated row, and no row naming a profile the daemon never accepted;
     *  - the rows are EXACTLY the first k of the commit order -- the whole prefix, no hole.
     *
     * After the burst a final listing must contain every profile: the anchor that the burst really
     * saved [PAIRS] pairs rather than having quietly lost them.
     */
    @Test
    fun cliListingsAreAlwaysAWholePrefix() = runSoak("SOAK-1b") { soakOnce() }

    /**
     * One iteration of SOAK-1b: a real CLI process listing a real daemon while real saves are in
     * flight. Pulled out so the [runSoak] loop in the @Test can repeat it when the opt-in knob is
     * set, and so [withEvidence]'s banner is the same shape for 1b as for 1a and 1c.
     *
     * Returns the one-line summary that the @Test prints at the end of a clean pass; a failure
     * throws before the return value is used.
     */
    private fun soakOnce(): String {
        val daemon = launchDaemon(stateDir("cli"))
        val writer = connect(daemon.port)

        /**
         * The profiles in the order they are COMMITTED. Appended BEFORE each save goes out, because a
         * profile can only be committed after its name is on this list: the set a listing returns is
         * therefore always a prefix of what this list holds when the listing is checked, never a
         * suffix and never a set this list has not heard of.
         */
        val commitOrder = CopyOnWriteArrayList<String>()
        val framesAnswered = CopyOnWriteArrayList<String>()

        /**
         * When each profile's `agent` answer frame was OBSERVED, in this JVM's clock.
         *
         * This is what makes the soak load-bearing rather than merely plausible. A listing that
         * started after the daemon announced a profile must CONTAIN it: the daemon commits at
         * `DaemonMain.kt:1206` before it constructs and sends that frame, so "announced" means
         * "already durable and already visible to every other connection". A listing that starts
         * later and misses it is the reported symptom exactly -- Backend shown, QA announced and
         * gone -- and it is the one failure a prefix check alone cannot see, because a SHORT listing
         * is a perfectly good prefix.
         *
         * Same clock on both sides (both timestamps are taken in this JVM, and the daemon's send
         * strictly precedes the frame arriving), so the comparison needs no slack.
         */
        val announcedAt = java.util.concurrent.ConcurrentHashMap<String, Long>()
        val writerFailure = AtomicReference<String?>(null)
        val burstDone = CountDownLatch(1)

        val writerThread = Thread({
            try {
                for (i in 1..PAIRS) {
                    for ((name, role) in listOf("Backend $i" to "backend", "QA $i" to "qa")) {
                        commitOrder += name
                        writer.send(
                            Envelope(
                                Envelope.AGENT_SAVE,
                                """{"id":"soak-${role}-${"%02d".format(i)}","name":"$name","role":"$role"}""",
                            ),
                        )
                        val answer = writer.awaitOrNull(Envelope.AGENT, framesAnswered.size, FRAME_TIMEOUT_MS)
                            ?: error("no 'agent' answer for '$name' within ${FRAME_TIMEOUT_MS}ms")
                        if ("\"$name\"" !in answer.data) error("the answer for '$name' does not carry it: ${answer.data}")
                        framesAnswered += name
                        announcedAt[name] = System.currentTimeMillis()
                        // Pace the burst: without it the whole thing commits faster than a JVM can
                        // start, no listing ever lands mid-save, and the concurrency this soak is
                        // about never happens.
                        Thread.sleep(SAVE_PACE_MS)
                    }
                }
            } catch (e: Throwable) {
                writerFailure.compareAndSet(null, "${e::class.simpleName}: ${e.message}")
            } finally {
                burstDone.countDown()
            }
        }, "infantry-soak-writer")
        writerThread.isDaemon = true
        writerThread.start()

        var listings = 0
        var midSave = 0
        val problems = mutableListOf<String>()

        while (burstDone.count > 0) {
            // Taken BEFORE the process starts, so every profile the daemon had already announced
            // when this listing began is one the listing owes us.
            val startedAt = System.currentTimeMillis()
            val listing = daemon.cli("agents")
            val known = commitOrder.toList()
            val names = listing.names
            val where = "listing #${listings + 1} (${names.size} rows, ${known.size} profiles committed)"

            if (listing.exit != 0) problems += "$where exited ${listing.exit}:\n$listing"
            if (listing.err.isNotBlank()) problems += "$where wrote to stderr: ${listing.err.trim()}"

            val duplicates = names.groupingBy { it }.eachCount().filterValues { it > 1 }.keys
            if (duplicates.isNotEmpty()) problems += "$where listed ${duplicates.size} profile(s) twice: $duplicates"

            val unknown = names.filterNot { it in known }
            if (unknown.isNotEmpty()) {
                problems += "$where listed profiles the daemon never accepted: $unknown"
            } else if (names.isNotEmpty()) {
                // A snapshot of a partially-saved set is the first k committed profiles. Anything else
                // is a hole: "Backend but not QA" with both committed is the reported symptom.
                val expectedPrefix = known.take(names.size)
                val highest = names.maxOf { known.indexOf(it) }
                if (highest != names.size - 1) {
                    problems += "$where is not a whole prefix: listed ${names.toSet()}, " +
                        "but at that moment the daemon held ${known.toSet()}"
                } else if (names.toSet() != expectedPrefix.toSet()) {
                    problems += "$where listed ${names.toSet()}, which is not the first ${names.size} of ${known.toSet()}"
                }
            }

            // A cross-process check of what a USER SEES: no hole, no row the daemon never accepted, no
            // duplicate, nothing lost. That is the symptom class this ticket exists for -- an `agents`
            // listing that showed Backend but not QA.
            //
            // It is NOT the proof of the persist-then-announce ordering, and cannot be. The mutation
            // (announce before commit, save deferred) leaves this test GREEN, because starting a `java`
            // process takes ~0.5-1s and so almost always begins after the mutation's staleness window
            // has closed. The ordering guarantee is carried by
            // everyProfileIsListableTheMomentItsAgentFrameArrives and
            // everyProfileIsStillListedAfterTheDaemonProcessIsRestarted, which are in-process, keep one
            // frame per save, and DO go red under that mutation. Do not read a green here as evidence
            // about ordering, and do not weaken those two to make this one look stronger.
            //
            // The check below still runs: an `owed` profile missing from a listing that started after
            // it was announced IS a real user-visible hole, and it is worth catching even though it is
            // not the guarantee's primary proof.
            val owed = announcedAt.filterValues { it < startedAt }.keys - names.toSet()
            if (owed.isNotEmpty()) {
                problems += "$where started after the daemon announced ${owed.sorted()}, " +
                    "and did not list ${owed.sorted()}: a profile was announced before it was committed"
            }

            if (names.isNotEmpty() && names.size < PAIRS * 2) midSave++
            listings++
        }

        writerThread.join(30_000)
        writerFailure.get()?.let { fail("the writer thread failed, so the burst proved nothing: $it") }

        assertEquals(
            PAIRS * 2,
            framesAnswered.size,
            "only ${framesAnswered.size} of ${PAIRS * 2} saves were answered, so the listings below prove nothing",
        )
        assertTrue(
            listings >= MIN_LISTINGS,
            "only $listings listing(s) were taken during the burst (wanted at least $MIN_LISTINGS)",
        )
        assertTrue(
            midSave > 0,
            "none of the $listings listings was taken while the daemon was mid-save " +
                "(every one showed either 0 or all ${PAIRS * 2} profiles), so this run proved nothing " +
                "about the race; raise $PAIRS or $SAVE_PACE_MS",
        )

        val final = daemon.cli("agents")
        assertEquals(0, final.exit, "the listing after the burst failed:\n$final")
        assertEquals(
            (1..PAIRS).flatMap { listOf("Backend $it", "QA $it") }.toSet(),
            final.names.toSet(),
            "after the burst the CLI must list every profile:\n$final",
        )

        assertTrue(problems.isEmpty(), "the soak found ${problems.size} bad listing(s):\n" + problems.joinToString("\n"))
        val summary =
            "SOAK-1b: $listings listing(s) against a live daemon process, $midSave of them mid-save, " +
                "${PAIRS * 2} profiles saved, 0 holes"
        println(summary)
        return summary
    }

    // ------------------------------------------------------------------------
    // the same guarantee, one process death later
    // ------------------------------------------------------------------------

    /**
     * SOAK-1c: a listing taken against a daemon STARTED OVER THE DATABASE FILE the previous daemon
     * process left behind still shows every profile.
     *
     * The in-process fixture can crash-recover by booting a second daemon over the same file; what it
     * cannot show is that the first daemon was a separate PROCESS whose commit is on disk by the
     * time its answer frame went out. If the answer frame could beat the commit to disk, this is
     * where it would show: a profile the CLI was told about, and which no longer exists after a
     * restart.
     */
    @Test
    fun everyProfileIsStillListedAfterTheDaemonProcessIsRestarted() = runSoak("SOAK-1c") {
        val dir = stateDir("restart")
        val dbFile = File(dir, "infantry.db")

        val first = launchDaemon(dir, dbFile)
        val writer = connect(first.port)
        val saved = mutableListOf<String>()
        var acknowledged = 0
        for (i in 1..PAIRS) {
            for ((name, role) in listOf("Backend $i" to "backend", "QA $i" to "qa")) {
                saved += name
                writer.send(
                    Envelope(
                        Envelope.AGENT_SAVE,
                        """{"id":"restart-${role}-${"%02d".format(i)}","name":"$name","role":"$role"}""",
                    ),
                )
                val answer = writer.awaitOrNull(Envelope.AGENT, saved.size - 1, FRAME_TIMEOUT_MS)
                    ?: fail("no 'agent' answer for '$name' within ${FRAME_TIMEOUT_MS}ms")
                assertTrue("\"$name\"" in answer.data, "the answer for '$name' does not carry it: ${answer.data}")
                acknowledged++
            }
        }
        // The loop fails on every unanswered save, so the count below is about how MANY profiles the
        // restart must find -- not about a list this loop filled. The previous form checked
        // `saved.size` against the same count that produced it and could never fail.
        assertEquals(
            PAIRS * 2,
            acknowledged,
            "only $acknowledged of ${PAIRS * 2} saves were acknowledged before the restart",
        )

        // The process goes away -- not a graceful close() -- so anything the daemon had only in
        // memory is gone and only what it committed is left.
        first.stop()
        daemons -= first

        val second = launchDaemon(dir, dbFile)
        val listing = second.cli("agents")
        assertEquals(0, listing.exit, "the listing after the restart failed:\n$listing")
        assertEquals(
            saved.toSet(),
            listing.names.toSet(),
            "a daemon restarted over the file the previous process left listed ${listing.names.toSet()}, " +
                "expected ${saved.toSet()}: an answer frame was published for a profile that was not committed",
        )
    }

    private companion object {
        /**
         * Pairs of profiles per soak. Twenty pairs (forty profiles) over a paced burst is long enough
         * for tens of CLI processes to start, list and exit inside it, and short enough to keep the
         * suite's runtime sane. Override with `-Dinfantry.soak.pairs=N`.
         */
        val PAIRS: Int = System.getProperty("infantry.soak.pairs")?.toIntOrNull()?.coerceAtLeast(1) ?: 20

        /** How long the writer pauses after each save, so listings can land mid-burst. */
        const val SAVE_PACE_MS = 120L

        /** How many frames a save may take to be answered before the soak calls it a failure. */
        const val FRAME_TIMEOUT_MS = 20_000L

        /** A burst that produced fewer listings than this never exercised the socket. */
        const val MIN_LISTINGS = 3

        /**
         * Opt-in repeat knob. `-Dinfantry.soak.repeat=N` runs each opted-in test body N times within
         * one Gradle test JVM; a single failure is recorded and the loop continues, so the 1-in-18
         * flake this soak exists for can be observed in a single XML. Default 1, behaviour identical
         * to a non-loop run.
         */
        val REPEAT: Int = System.getProperty(SoakRunArchive.REPEAT_PROPERTY)
            ?.toIntOrNull()?.coerceAtLeast(1) ?: 1

        }
}

/**
 * The `name` of every profile in an `agents` answer, decoded with the protocol's OWN serializer.
 *
 * Decoding rather than substring-matching: a listing that printed the right names in the wrong place,
 * or truncated one, must not be able to pass this soak.
 */
private fun profileNames(data: String): List<String> =
    Json { ignoreUnknownKeys = true }
        .decodeFromString(ListSerializer(AgentProfile.serializer()), data)
        .map { it.name }
