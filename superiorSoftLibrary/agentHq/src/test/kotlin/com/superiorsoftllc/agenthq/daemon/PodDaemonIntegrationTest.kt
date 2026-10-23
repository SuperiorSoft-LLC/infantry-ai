package com.superiorsoftllc.agenthq.daemon

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import com.superiorsoftllc.agenthq.orchestration.GatedRunner
import com.superiorsoftllc.agenthq.orchestration.NodeSpec
import com.superiorsoftllc.agenthq.orchestration.NodeState
import com.superiorsoftllc.agenthq.orchestration.Orchestrator
import com.superiorsoftllc.agenthq.orchestration.PodState
import com.superiorsoftllc.agenthq.orchestration.PodStatus
import com.superiorsoftllc.agenthq.orchestration.PodSubmitRequest
import com.superiorsoftllc.agenthq.persistence.NodeAttempt
import com.superiorsoftllc.agenthq.persistence.NodeRecord
import com.superiorsoftllc.agenthq.persistence.PodRecord
import com.superiorsoftllc.agenthq.persistence.RunEvent
import com.superiorsoftllc.agenthq.persistence.RunRecord
import com.superiorsoftllc.agenthq.persistence.SqlAgentHqStore
import com.superiorsoftllc.agenthq.protocol.Envelope
import com.superiorsoftllc.agenthq.protocol.EventVocabulary
import com.superiorsoftllc.agenthq.protocol.FinishedPayload
import com.superiorsoftllc.agenthq.protocol.StatusPayload
import com.superiorsoftllc.agenthq.runner.ProcessRunner
import com.superiorsoftllc.agenthq.runner.StubScript
import com.superiorsoftllc.agenthq.session.SessionState
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.nio.file.Files
import java.nio.file.Paths
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * THE Phase 5b ACCEPTANCE CRITERIA, as executable tests.
 *
 * Phase 5 built the orchestrator and deliberately left it unreachable: nothing outside the daemon
 * could submit a pod, and the daemon never opened the database the orchestrator writes to. This file
 * is what makes that wiring checkable.
 *
 * Nothing here is faked except the script (or, where a test must hold a run open, the
 * [GatedRunner]). Every test boots the REAL daemon -- `AgentHqDaemon.build`, the same call `main`
 * makes -- on an ephemeral port, and drives it with a REAL `java.net.http.WebSocket`. A test that
 * called the orchestrator directly would prove the library works, not that the wire surface reaches
 * it, and reachability is the entire subject of this ticket.
 */
class PodDaemonIntegrationTest {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val fixtures = mutableListOf<DaemonFixture>()

    @AfterTest
    fun stopAll() {
        fixtures.asReversed().forEach {
            runCatching { it.stop() }
            runCatching { it.deleteState() }
        }
        fixtures.clear()
    }

    private fun launch(
        script: StubScript = StubScript.default(),
        runner: ProcessRunner? = null,
        stateDir: File = Files.createTempDirectory("agenthq-pod-test").toFile(),
        storePath: String = File(stateDir, "agenthq.db").absolutePath,
    ): DaemonFixture =
        DaemonFixture.launch(script, runner, stateDir, storePath).also { fixtures += it }

    /**
     * Send [command] and return the [answerType] frame that arrives AFTER it.
     *
     * Two things make this necessary rather than convenient. The answer frame is not the command
     * frame -- `pod_submit` is answered by `pod` -- so the two types are passed separately. And the
     * occurrence is counted BEFORE the send, because several tests issue several commands of one
     * kind in a row: awaiting "the next `pod` frame" must mean after THIS send, or the second query
     * would keep being answered by the first query's reply and a wait for a state change would spin
     * forever on a stale answer.
     */
    private fun answer(
        fixture: DaemonFixture,
        command: String,
        data: String,
        answerType: String,
    ): Envelope {
        val already = fixture.client.of(answerType).size
        fixture.send(command, data)
        return fixture.client.await(answerType, occurrence = already)
    }

    private fun submit(fixture: DaemonFixture, request: PodSubmitRequest): PodStatus =
        json.decodeFromString(
            PodStatus.serializer(),
            answer(
                fixture,
                Envelope.POD_SUBMIT,
                json.encodeToString(PodSubmitRequest.serializer(), request),
                Envelope.POD,
            ).data,
        )

    private fun query(fixture: DaemonFixture, podId: String): PodStatus =
        json.decodeFromString(
            PodStatus.serializer(),
            answer(fixture, Envelope.POD_QUERY, podId, Envelope.POD).data,
        )

    // --- 1. the daemon boots with AGENTHQ_PROVIDER=stub and its own store is really WAL --------------

    /**
     * The first half is the ordinary claim ("the stub boots the daemon"). The second half is the one
     * worth having: WAL is asserted on the DAEMON'S OWN database handle, read back out of SQLite
     * through the factory's driver. Opening a store inside the test and asserting on it would pass
     * even if `AgentHqDaemon.build` opened a different file, or none at all.
     */
    @Test
    fun theDaemonBootsOnTheStubProviderAndItsOwnStoreIsInWalMode() {
        val fixture = launch()

        // `AGENTHQ_PROVIDER=stub` is a registry entry, not a special case: the daemon names it.
        assertTrue(
            fixture.client.await(Envelope.READY).data.contains("provider=stub"),
            "the ready frame must name the stub provider",
        )
        assertEquals("stub", fixture.health()["provider"]!!.jsonPrimitive.content)

        // The factory's own driver reports the effective mode -- not "a PRAGMA was issued".
        assertEquals("wal", journalMode(fixture.sqlStore.handle.driver).lowercase())
        // And it is a real file on disk, not an in-memory database that merely answers PRAGMA.
        assertTrue(File(fixture.stateDir, "agenthq.db").isFile, "the daemon must have created its database file")
    }

    // --- 2. recovery runs at startup --------------------------------------------------------------

    /**
     * THE CLAIM: the daemon performs crash recovery on open, and the orchestrator reconciles the pods
     * that owned the recovered runs. Nothing here calls `recoverAfterCrash` or `Orchestrator.recover`
     * -- booting the daemon IS the mechanism under test.
     *
     * The crash is REAL: a child JVM writes an open pod, a RUNNING node and a run with no end time,
     * prints READY, and is killed with `destroyForcibly()` (TerminateProcess on Windows, SIGKILL on
     * Unix). No `close()`, no shutdown hook, no checkpoint -- exactly the rows a daemon killed between
     * "spawned the child" and "observed its exit" leaves behind.
     *
     * [com.superiorsoftllc.agenthq.persistence.AgentHqStoreTest] already covers `recoverAfterCrash`
     * on its own. What is new here is that the ORCHESTRATOR's half runs too, and that the whole thing
     * happens because the daemon was started.
     */
    @Test
    fun recoveryRunsAtStartupAndIsIdempotentOnASecondOpen() {
        val state = Files.createTempDirectory("agenthq-recover-").toFile()
        val dbPath = File(state, "agenthq.db").absolutePath
        killACrashingDaemon(dbPath)

        // ---- first restart: both halves of recovery run, in order --------------------------------
        val first = launch(stateDir = state, storePath = dbPath)
        first.client.await(Envelope.READY)

        val recovered = first.daemon.store.runs.get(CRASH_RUN)!!
        assertEquals(
            SessionState.ORPHANED, recovered.state,
            "a run still in flight belonged to a daemon that is gone",
        )
        assertTrue(recovered.endedAt != null && recovered.endedAt > 0, "recovery must close the interval")
        assertNull(recovered.exitCode, "an exit code nobody observed must not be invented")
        assertEquals("ses_crash", recovered.providerSessionId, "the resume handle must survive recovery")
        assertEquals(3L, recovered.stdoutLines, "the partial log must survive recovery")

        val log = first.daemon.store.events.forRun(CRASH_RUN).map { it.event }
        assertEquals(
            RunEvent.Finished(
                FinishedPayload(null, FinishedPayload.Outcome.ORPHANED, SqlAgentHqStore.ORPHAN_DETAIL),
            ),
            log.last(),
            "a client replaying the log must see the run end exactly as a live client would",
        )
        assertEquals(
            listOf(
                RunEvent.Status(StatusPayload(SessionState.BUSY, "working")),
                RunEvent.TextDelta("half an answer"),
            ),
            log.dropLast(1),
            "the pre-crash events must survive untouched, in order",
        )

        // The orchestrator's half: the pod that owned the run is reconciled, not left RUNNING.
        val pod = first.daemon.store.pods.get(CRASH_POD)!!
        assertEquals(PodState.INTERRUPTED.name, pod.state)
        assertTrue(pod.endedAt != null && pod.endedAt > 0, "an interrupted pod must have an end time")
        val node = first.daemon.store.nodes.byPod(CRASH_POD).single()
        assertEquals(CRASH_NODE, node.id)
        assertEquals(NodeState.FAILED.name, node.state)
        assertEquals(Orchestrator.DETAIL_RESTARTED_MID_RUN, node.detail)
        assertTrue(first.daemon.store.runs.unfinished().isEmpty(), "no run may be left in flight")

        first.stop()

        // ---- second restart: idempotent --------------------------------------------------------
        val second = launch(stateDir = state, storePath = dbPath)
        second.client.await(Envelope.READY)

        assertEquals(recovered, second.daemon.store.runs.get(CRASH_RUN), "recovery must not re-stamp a run")
        assertEquals(
            log.size.toLong(), second.daemon.store.events.count(CRASH_RUN),
            "recovery must not append a second finished event",
        )
        assertEquals(pod, second.daemon.store.pods.get(CRASH_POD))
        assertEquals(node, second.daemon.store.nodes.byPod(CRASH_POD).single())
    }

    /**
     * Spawn the child JVM that plays the dying daemon, wait for it to reach its crash point, then
     * `destroyForcibly()`. stderr is merged into stdout so a child failure shows up in the message.
     */
    private fun killACrashingDaemon(dbPath: String) {
        val java = Paths.get(System.getProperty("java.home"), "bin", "java").toString()
        val child = ProcessBuilder(
            java, "-cp", System.getProperty("java.class.path"),
            CrashingPodDaemonMain::class.java.name, dbPath,
        ).redirectErrorStream(true).start()
        try {
            val seen = mutableListOf<String>()
            val reader = child.inputReader()
            while (true) {
                val line = reader.readLine() ?: break
                seen += line
                if (line == "READY") break
            }
            assertEquals(
                "READY", seen.lastOrNull(),
                "child daemon never reached its crash point:\n" + seen.joinToString("\n"),
            )
        } finally {
            // No close, no shutdown hook: the process is simply gone, mid-write.
            child.destroyForcibly()
            child.waitFor()
        }
    }

    // --- 3. a pod submitted over the socket answers, and queries back ------------------------------

    /**
     * The ticket's reason for existing, round-tripped through the real socket in both directions:
     * `pod_submit` answers with a `pod` frame, and a subsequent `pod_query` for the id from that
     * answer returns the pod's state again. The orchestrator is never touched directly -- if the wire
     * surface were not wired, both would be `error` frames.
     */
    @Test
    fun aPodSubmittedOverTheSocketAnswersWithItsStateAndQueriesBack() {
        val fixture = launch()
        fixture.client.await(Envelope.READY)

        val submitted = submit(
            fixture,
            PodSubmitRequest(
                goal = "wire the pod surface",
                maxConcurrency = 2,
                nodes = listOf(
                    NodeSpec("build", "wire the frames"),
                    NodeSpec("check", "prove it round-trips", dependsOn = setOf("build")),
                ),
            ),
        )

        // The answer is a real PodStatus, and it is the client's only way to learn the new pod's id.
        assertEquals("wire the pod surface", submitted.pod.goal)
        assertEquals(PodState.RUNNING.name, submitted.pod.state)
        assertEquals(2, submitted.pod.maxConcurrency)
        assertEquals(listOf("build", "check"), submitted.nodes.map { it.name }, "nodes keep spec order")

        // The DAG edge survived the round trip: `check` waits on `build`, by name.
        val checkNode = submitted.nodes.single { it.name == "check" }
        val dependencyNames = fixture.daemon.store.nodes.dependencies(submitted.pod.id)
            .filter { it.nodeId == checkNode.id }
            .map { edge -> submitted.nodes.single { it.id == edge.dependsOn }.name }
        assertEquals(listOf("build"), dependencyNames)

        // A query answers with the SAME pod, read back through the same socket.
        val queried = query(fixture, submitted.pod.id)
        assertEquals(submitted.pod.id, queried.pod.id)
        assertEquals(submitted.pod.goal, queried.pod.goal)

        // ...and it carries LIVE state, not a snapshot frozen at submit time.
        fixture.await(what = "pod ${submitted.pod.id} converges") {
            query(fixture, submitted.pod.id).pod.endedAt != null
        }
        val converged = query(fixture, submitted.pod.id)
        assertEquals(PodState.CONVERGED.name, converged.pod.state)
        assertTrue(converged.nodes.all { it.state == NodeState.SUCCEEDED.name }, "nodes: ${converged.nodes}")
        assertEquals(2, converged.runs.values.sumOf { it.size }, "one run per node, both persisted")

        // A bad request is refused visibly, on the FROZEN error frame, and writes nothing.
        val refusal = answer(fixture, Envelope.POD_SUBMIT, "not json at all", Envelope.ERROR)
        assertEquals(Envelope.ERROR, refusal.type)
        assertTrue(refusal.data.contains("pod_submit"), "the refusal must say what failed: ${refusal.data}")
        assertEquals(1, fixture.daemon.store.pods.all().size, "a refused pod writes nothing")
    }

    // --- 4. /health reports pod counts and publishes 1.28.0 ----------------------------------------

    /**
     * `pods` and `runsInFlight` only mean something if they MOVE, so this drives the gated runner: a
     * `HANG` node never exits on its own, so the pod is deterministically open and its run
     * deterministically in flight for exactly as long as the test needs.
     */
    @Test
    fun healthReportsPodCountsAndPublishesTheContractVersion() {
        val fixture = launch(runner = GatedRunner())
        fixture.client.await(Envelope.READY)

        val before = fixture.health()
        assertEquals("1.101.0", EventVocabulary.VERSION)
        assertEquals(EventVocabulary.VERSION, before["contract"]!!.jsonPrimitive.content)
        // Every field 1.0.0 published is still there, under the same name.
        assertEquals("ok", before["status"]!!.jsonPrimitive.content)
        assertTrue(before.containsKey("provider") && before.containsKey("runner"), "health: $before")
        assertTrue(before.containsKey("sessions") && before.containsKey("worktrees"), "health: $before")
        assertEquals(0, before["pods"]!!.jsonPrimitive.int)
        assertEquals(0, before["runsInFlight"]!!.jsonPrimitive.int)

        val submitted = submit(
            fixture,
            PodSubmitRequest(goal = "hold a run open", nodes = listOf(NodeSpec("hang", "please HANG"))),
        )
        fixture.await(what = "the hanging node starts a run") {
            fixture.health()["runsInFlight"]!!.jsonPrimitive.int == 1
        }

        val during = fixture.health()
        assertEquals(1, during["pods"]!!.jsonPrimitive.int, "an open pod must be counted")
        assertEquals(1, during["runsInFlight"]!!.jsonPrimitive.int, "a run with no end time must be counted")

        // Cancel it, and the counts fall back -- the half that proves they are not constants.
        val cancelled = answer(fixture, Envelope.POD_CANCEL, "${submitted.pod.id} force", Envelope.POD)
        assertEquals(Envelope.POD, cancelled.type)
        fixture.await(what = "the cancelled pod leaves the open set") {
            val health = fixture.health()
            health["pods"]!!.jsonPrimitive.int == 0 && health["runsInFlight"]!!.jsonPrimitive.int == 0
        }
    }

    // --- 5. the four frozen frames are unchanged ---------------------------------------------------

    /**
     * THE REGRESSION GUARD for the additive-only rule, and the most important test in the ticket.
     *
     * 1.25.0 is still a minor bump only because `text_delta` / `status` / `error` / `finished` still mean, and
     * still serialise, EXACTLY what 1.0.0 published. These are LITERAL expected strings, not
     * round-trips: a round trip through the same encoder proves nothing, because a payload reshaped
     * on both sides still round-trips. Pinning the bytes is the only form of this assertion that can
     * fail.
     */
    @Test
    fun theFourFrozenFramesSerialiseExactlyAs1_0_0PublishedThem() {
        // The frame NAMES are part of the contract too.
        assertEquals("text_delta", EventVocabulary.TEXT_DELTA)
        assertEquals("status", EventVocabulary.STATUS)
        assertEquals("error", EventVocabulary.ERROR)
        assertEquals("finished", EventVocabulary.FINISHED)

        // `text_delta` and `error` carry a PLAIN STRING: the payload IS the data field, unencoded.
        assertEquals(
            "Reading the failing test",
            RunEvent.encodePayload(RunEvent.TextDelta("Reading the failing test")),
        )
        assertEquals("provider refused", RunEvent.encodePayload(RunEvent.Error("provider refused")))
        // ...including one that would break the moment anyone gave it a JSON wrapper.
        assertEquals("""{"a":1}""", RunEvent.encodePayload(RunEvent.Error("""{"a":1}""")))

        // `status`: state then detail, in declaration order, detail always present.
        assertEquals(
            """{"state":"BUSY","detail":"provisioning worktree"}""",
            json.encodeToString(
                StatusPayload.serializer(),
                StatusPayload(SessionState.BUSY, "provisioning worktree"),
            ),
        )
        assertEquals(
            """{"state":"IDLE","detail":""}""",
            json.encodeToString(StatusPayload.serializer(), StatusPayload(SessionState.IDLE)),
        )

        // `finished`: exitCode, outcome, detail -- and a null exitCode stays null, never 0.
        assertEquals(
            """{"exitCode":0,"outcome":"SUCCEEDED","detail":""}""",
            json.encodeToString(
                FinishedPayload.serializer(),
                FinishedPayload(0, FinishedPayload.Outcome.SUCCEEDED),
            ),
        )
        assertEquals(
            """{"exitCode":null,"outcome":"ORPHANED","detail":"daemon restarted"}""",
            json.encodeToString(
                FinishedPayload.serializer(),
                FinishedPayload(null, FinishedPayload.Outcome.ORPHANED, "daemon restarted"),
            ),
        )
        // Every outcome 1.0.0 published is still spelled the same way, in the same order.
        assertEquals(
            listOf("SUCCEEDED", "FAILED", "KILLED_GRACEFUL", "KILLED_FORCED", "ORPHANED"),
            FinishedPayload.Outcome.entries.map { it.name },
        )
        // ...and the seven states of `status`, unchanged.
        assertEquals(
            listOf("STARTING", "BUSY", "WAITING_INPUT", "IDLE", "EXITED", "ORPHANED", "FAILED"),
            SessionState.entries.map { it.name },
        )

        // The same bytes again, this time OFF a live daemon rather than off the encoder.
        val fixture = launch()
        fixture.client.await(Envelope.READY)
        fixture.send(Envelope(Envelope.PROMPT, "hello stub"))
        fixture.client.await(Envelope.FINISHED)

        assertEquals(
            listOf(
                "[stub] daemon is running without a provider CLI.",
                "Every frame on this socket is the real protocol; only the text is scripted.",
            ),
            fixture.client.of(Envelope.TEXT_DELTA).map { it.data },
        )
        val statuses = fixture.client.of(Envelope.STATUS)
            .map { json.decodeFromString(StatusPayload.serializer(), it.data) }
        assertTrue(
            SessionState.BUSY in statuses.map { it.state },
            "a streamed session must report BUSY, got $statuses",
        )
        assertTrue(
            statuses.any { it == StatusPayload(SessionState.BUSY, "") },
            "a provider-derived status carries a label; a lifecycle one carries an empty detail: $statuses",
        )
        assertEquals(
            FinishedPayload(0, FinishedPayload.Outcome.SUCCEEDED, ""),
            json.decodeFromString(FinishedPayload.serializer(), fixture.client.await(Envelope.FINISHED).data),
        )
    }

    // --- 6. the orchestrator receives the broadcast events ------------------------------------------

    /**
     * The sink has THREE arms -- broadcast, mailbox, orchestrator -- and the ticket is explicit that
     * the orchestrator must observe the same events the clients are told about, or it schedules
     * against stale state.
     *
     * The proof is structural: pods, nodes and run events are written by the ORCHESTRATOR and by
     * nothing else in the daemon, so a pod that reaches CONVERGED with every node SUCCEEDED and a
     * `finished` event per run can only have got there through `onSessionEvent`. This test never
     * touches the orchestrator. It then checks the same `text_delta` reached BOTH the client and the
     * run's own event log -- which is what "the same events" has to mean.
     */
    @Test
    fun theOrchestratorAdvancesOnTheSameEventsThatAreBroadcast() {
        val fixture = launch(runner = GatedRunner(holdMs = 50))
        fixture.client.await(Envelope.READY)

        val submitted = submit(
            fixture,
            PodSubmitRequest(
                goal = "prove the third sink arm",
                maxConcurrency = 2,
                nodes = listOf(NodeSpec("left", "left work"), NodeSpec("right", "right work")),
            ),
        )

        fixture.await(what = "both nodes converge") {
            fixture.daemon.store.pods.get(submitted.pod.id)?.endedAt != null
        }

        val pod = fixture.daemon.store.pods.get(submitted.pod.id)!!
        assertEquals(PodState.CONVERGED.name, pod.state)
        val nodes = fixture.daemon.store.nodes.byPod(submitted.pod.id)
        assertEquals(2, nodes.size)
        assertTrue(nodes.all { it.state == NodeState.SUCCEEDED.name }, "nodes: $nodes")
        assertTrue(nodes.all { it.reviewState == "PENDING" }, "a converged pod queues its nodes for review")

        val runs = nodes.flatMap { node ->
            fixture.daemon.store.nodes.attempts(node.id).map { fixture.daemon.store.runs.get(it.runId)!! }
        }
        assertEquals(2, runs.size)
        for (run in runs) {
            assertEquals(0, run.exitCode)
            val log = fixture.daemon.store.events.forRun(run.id).map { it.event }
            assertEquals(
                FinishedPayload(0, FinishedPayload.Outcome.SUCCEEDED, run.error ?: ""),
                assertIs<RunEvent.Finished>(log.last(), "the last stored event must be `finished`: $log").payload,
                "the orchestrator must close each run with a finished event",
            )
            // The orchestrator stores exactly what the provider streamed.
            val deltas = log.filterIsInstance<RunEvent.TextDelta>().map { it.text }
            assertEquals(1, deltas.size, "the gated runner streams one text line per run: $deltas")
            assertTrue(deltas.single().startsWith("ran #"), "unexpected stored text: $deltas")
        }

        // ...and the client really was told the same thing over the same socket.
        val broadcast = fixture.client.of(Envelope.TEXT_DELTA).map { it.data }
        assertEquals(2, broadcast.size, "both runs' text reached the client: $broadcast")
        assertTrue(broadcast.all { it.startsWith("ran #") }, "unexpected broadcast text: $broadcast")

        // A terminal pod answers `pod_query` at once, and the orchestrator thread threw nothing.
        assertEquals(PodState.CONVERGED.name, query(fixture, submitted.pod.id).pod.state)
        assertTrue(
            fixture.daemon.orchestrator.failures.isEmpty(),
            "orchestrator thread threw: ${fixture.daemon.orchestrator.failures}",
        )
    }

    private fun journalMode(driver: SqlDriver): String =
        driver.executeQuery(
            identifier = null,
            sql = "PRAGMA journal_mode;",
            mapper = { cursor ->
                // For a JVM driver the mapper runs synchronously, so unwrapping the value is exactly
                // the work of reading one row.
                val hasRow = cursor.next().value
                QueryResult.Value(if (hasRow) cursor.getString(0).orEmpty() else "")
            },
            parameters = 0,
        ).value

    private companion object {
        const val CRASH_POD = "pod-crash"
        const val CRASH_RUN = "run-crash"
        const val CRASH_NODE = "node-crash"
    }
}

/**
 * The child process of the recovery test: a daemon that dies mid-write.
 *
 * It writes exactly what a daemon killed between "spawned the child" and "observed its exit" leaves
 * behind -- a pod still open, a node still RUNNING, a run with no end time and a partial event log
 * -- signals READY, then blocks forever until the test kills it.
 */
object CrashingPodDaemonMain {
    @JvmStatic
    fun main(args: Array<String>) {
        val store = SqlAgentHqStore.open(args[0])
        store.transaction {
            store.pods.create(
                PodRecord(
                    id = "pod-crash", goal = "survive a crash", state = PodState.RUNNING.name,
                    maxConcurrency = 1, createdAt = 100,
                ),
            )
            store.nodes.create(
                NodeRecord(
                    id = "node-crash", podId = "pod-crash", name = "worker", specIndex = 0,
                    prompt = "work", state = NodeState.RUNNING.name, attempts = 1, createdAt = 100,
                ),
            )
            store.runs.upsert(
                RunRecord(
                    id = "run-crash", sessionId = "session-crash", provider = "stub",
                    workingDir = "C:/w", prompt = "work", state = SessionState.BUSY,
                    startedAt = 200, podId = "pod-crash", stdoutLines = 3, providerSessionId = "ses_crash",
                ),
            )
            store.events.append("run-crash", RunEvent.Status(StatusPayload(SessionState.BUSY, "working")), 300)
            store.events.append("run-crash", RunEvent.TextDelta("half an answer"), 301)
            store.nodes.recordAttempt(NodeAttempt("run-crash", "node-crash", 1))
        }

        println("READY")
        System.out.flush()
        Thread.sleep(Long.MAX_VALUE)
    }
}
