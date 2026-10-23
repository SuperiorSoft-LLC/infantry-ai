package com.superiorsoftllc.agenthq.daemon

import com.superiorsoftllc.agenthq.decisions.Decision
import com.superiorsoftllc.agenthq.protocol.Envelope
import com.superiorsoftllc.agenthq.protocol.TicketStatus
import com.superiorsoftllc.agenthq.protocol.TraitView
import com.superiorsoftllc.agenthq.runner.ProcessListener
import com.superiorsoftllc.agenthq.runner.ProcessRunner
import com.superiorsoftllc.agenthq.runner.ProcessSpec
import com.superiorsoftllc.agenthq.runner.RunningProcess
import com.superiorsoftllc.agenthq.runner.ScriptedProcess
import com.superiorsoftllc.agenthq.runner.StubScript
import com.superiorsoftllc.agenthq.session.RetrievalClassifier
import com.superiorsoftllc.agenthq.session.RetrievalClassifier.Kind
import com.superiorsoftllc.agenthq.session.RetrievalClassifier.Verdict
import com.superiorsoftllc.agenthq.session.SessionState
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNotSame
import kotlin.test.assertTrue

/**
 * P10-G1 (docs/AGENTHQ-PHASE10-KNOWLEDGE.md section 6): the phase gate. "An agent recalls a decision from a previous
 * session without being told, and reads it before re-deriving it."
 *
 * Everything is the real daemon over a real WebSocket and a real SQLite file; only the provider is scripted, and its
 * lines are whole, unedited lines of REAL opencode runs (`/retrieval/`, captured for P10-K10), so they go through the
 * same [com.superiorsoftllc.agenthq.protocol.EventNormalizer] a live run does.
 *
 *  1. Daemon #1 over state dir S: D1 "gzip", then D2 "zstd level 19" superseding D1, through the CLI's own frames.
 *  2. Daemon #1 stops; daemon #2 boots over S. It is a new process (new `instance`), and the table still holds both rows.
 *  3. A ticket "Add transcript export" (no `zstd`, no `gzip` in it) starts on the project. The scripted agent runs
 *     `agenthq knowledge`, then `agenthq decision show D2`, then reads a source file, then answers.
 *
 * A1 first turn, A2 data path, A3 order (classifier over the session stream as the daemon recorded it, read back with
 * `replay_request`, the stream `cli/SessionReplay.kt` resumes), A4 negative controls, each its own session on daemon #2.
 */
class KnowledgeRecallGateTest {

    private val json = Json { ignoreUnknownKeys = true }
    private val fixtures = mutableListOf<DaemonFixture>()
    private var stateDir: java.io.File? = null

    @AfterTest
    fun stopAll() {
        fixtures.asReversed().forEach { runCatching { it.stop() } }
        stateDir?.let { runCatching { it.deleteRecursively() } }
    }

    @Test
    fun aDecisionFromAnEarlierDaemonIsRecalledAndReadBeforeExploring() {
        val dir = Files.createTempDirectory("agenthq-recall-gate").toFile().also { stateDir = it }

        // --- 1. daemon #1 records D1, then D2 superseding it -------------------------------------------------------
        val first = launch(dir, StubScript.default().let { s -> RoutedRunner { s } })
        val firstInstance = instanceOf(first.client.await(Envelope.READY))
        first.send(Envelope.DECISION_ADD, """{"projectId":"$PROJECT","summary":"$D1_SUMMARY","body":"the default"}""")
        assertEquals("D1", decode(first.client.await(Envelope.DECISION_ITEM)).ref)
        first.send(Envelope.DECISION_ADD, """{"projectId":"$PROJECT","summary":"$D2_SUMMARY","body":"smaller archives","supersedes":"D1"}""")
        val d2 = decode(first.client.await(Envelope.DECISION_ITEM, occurrence = 1))
        assertEquals("D2" to 1, d2.ref to d2.supersedes)
        // A2 before the restart: the count is asserted, not assumed.
        assertEquals(listOf("D2"), list(first, includeSuperseded = false, occurrence = 0).map { it.ref }, "A2 before the restart: active only")
        val before = list(first, includeSuperseded = true, occurrence = 1).map { it.number to it.supersedes }
        assertEquals(listOf(1 to null, 2 to 1), before, "A2 before the restart: both decisions, D2 superseding D1")
        val firstDaemon = first.daemon
        first.stop()

        // --- 2. daemon #2 over the same state ----------------------------------------------------------------------
        val runner = RoutedRunner(::scriptFor)
        val second = launch(dir, runner)
        val secondInstance = instanceOf(second.client.await(Envelope.READY))
        assertNotSame(firstDaemon, second.daemon, "a new daemon object")
        assertNotEquals(firstInstance, secondInstance, "a new daemon process identity")
        assertEquals(before, tableRows(second), "A2 after the restart: the decisions table still holds the same two rows")

        // --- A2: the data path, on the new daemon ------------------------------------------------------------------
        assertEquals(listOf("D2"), list(second, includeSuperseded = false, occurrence = 0).map { it.ref }, "A2: decision_list is D2 only")
        val all = list(second, includeSuperseded = true, occurrence = 1)
        assertEquals(listOf("D1" to 2, "D2" to null), all.map { it.ref to it.supersededBy }, "A2: D1 is superseded by D2")
        second.send(Envelope.DECISION_GET, """{"projectId":"$PROJECT","ref":"D2"}""")
        assertTrue(decode(second.client.await(Envelope.DECISION_ITEM)).summary.contains("zstd"), "A2: decision_get D2 carries zstd")

        // --- 3. the ticket ------------------------------------------------------------------------------------------
        seedAgents(second)
        val description = "Export a session transcript as an archive file the operator can download."
        val sessionId = startTicket(second, GATE_TITLE, description, "backend")
        val delivered = runner.processes.single { it.deliveredInput.contains(GATE_TITLE) }.deliveredInput

        // --- A1: the first turn -------------------------------------------------------------------------------------
        val sources = second.daemon.manager.get(sessionId)!!.contextSources.map { it.source }
        assertTrue("knowledge" in sources, "A1: the knowledge section is present: $sources")
        assertTrue(
            sources.indexOf("identity") < sources.indexOf("traits") &&
                sources.indexOf("traits") < sources.indexOf("knowledge") &&
                sources.indexOf("knowledge") < sources.indexOf("ticket"),
            "A1: identity -> traits -> knowledge -> ticket: $sources",
        )
        val knowledgeAt = delivered.indexOf("PROJECT KNOWLEDGE")
        assertTrue(knowledgeAt >= 0 && knowledgeAt < delivered.indexOf(GATE_TITLE), "A1: knowledge before the ticket text")
        val section = delivered.substring(knowledgeAt, delivered.indexOf(GATE_TITLE))
        assertTrue(section.contains("1 active decision"), "A1: the section says 1 active decision: $section")
        // BUG-G2e (owner, 2026-10-19): the first turn now CARRIES the active decisions (it replaced K-D2's "counts only"):
        // D2's summary is handed to the session, the superseded D1's never is.
        assertTrue(section.contains("\nD2 $D2_SUMMARY\n"), "A1: the section carries the active D2: $section")
        assertFalse(section.contains("gzip"), "A1: the section never carries the superseded D1: $section")
        assertEquals(Verdict.INJECTED, RetrievalClassifier.classifyLines(emptyList(), firstTurn = delivered).verdict, "A1: injected")

        // Non-vacuity: neither the ticket nor the scripted stream names the format; only the knowledge base does.
        for (text in listOf(GATE_TITLE, description)) {
            assertFalse(text.contains("zstd", ignoreCase = true) || text.contains("gzip", ignoreCase = true), "non-vacuity: '$text'")
        }
        assertFalse(gateScript().stdoutLines.any { it.contains("zstd") }, "non-vacuity: the scripted stream never names zstd")

        // --- A3: the order, over the stream the daemon recorded -----------------------------------------------------
        val recorded = recordedStdout(second, sessionId)
        assertEquals(gateScript().stdoutLines, recorded, "the daemon recorded the scripted lines, in order")
        val result = RetrievalClassifier.classifyLines(recorded)
        assertEquals(Verdict.READ_FIRST, result.verdict, "A3: ${result.calls.map { it.command to it.kind }}")
        val firstRead = assertNotNull(result.firstRead, "A3: a first knowledge read")
        assertEquals("agenthq knowledge", firstRead.command, "A3: the first read is the `agenthq knowledge` event")
        assertEquals(result.calls.first(), firstRead, "A3: nothing came before it")
        assertTrue(
            result.calls.any { it.kind == Kind.KNOWLEDGE_READ && it.command!!.endsWith("decision show D2") },
            "A3: the agent opened D2",
        )
        assertTrue(result.calls.indexOf(result.firstExploration) > result.calls.indexOf(firstRead), "A3: the read came after")

        // --- A4: negative controls, each a real session on the same daemon ------------------------------------------
        val controls = listOf(
            Triple(EXPLORE_TITLE, "ctl-explore", Verdict.EXPLORED_FIRST),
            Triple(NEVER_TITLE, "ctl-never", Verdict.NEVER_READ),
            Triple(NEUTRAL_TITLE, "ctl-neutral", Verdict.READ_FIRST),
        )
        for ((title, agentId, expected) in controls) {
            val control = startTicket(second, title, "a control run", agentId)
            val verdict = RetrievalClassifier.classifyLines(recordedStdout(second, control))
            assertEquals(expected, verdict.verdict, "A4 '$title': ${verdict.calls.map { it.command to it.kind }}")
        }
    }

    // --- the scripted agent ---------------------------------------------------------------------------------------

    private fun fixture(name: String): List<String> =
        checkNotNull(javaClass.getResourceAsStream("/retrieval/$name.jsonl")) { "fixture $name missing" }
            .bufferedReader().readLines().filter { it.isNotBlank() }

    // Real opencode lines (see /retrieval/PROVENANCE.txt); each tool call sits between its step_start and step_finish.
    private val oc = fixture("opencode-bash-run")
    private val ot = fixture("opencode-tools-run")
    private val ocInbox = oc.slice(0..2)       // agenthq inbox
    private val ocKnowledge = oc.slice(3..5)   // agenthq knowledge
    private val ocShowD2 = oc.slice(9..11)     // cd <long path> && C:/Users/dev/bin/agenthq.cmd decision show D2
    private val ocGrep = oc.slice(12..14)      // rtk grep -rn archive src
    private val ocSend = oc.slice(24..26)      // agenthq send pm hello
    private val ocText = oc.slice(27..29)      // the closing reply, no tool
    private val otRead = ot.slice(3..5)        // read src/a.txt

    private fun gateScript() = StubScript(stdoutLines = ocKnowledge + ocShowD2 + otRead + ocText)

    /** The script a run replays, chosen by the ticket its first turn carries. */
    private fun scriptFor(input: String): StubScript = when {
        input.contains(GATE_TITLE) -> gateScript()
        input.contains(EXPLORE_TITLE) -> StubScript(stdoutLines = ocGrep + ocKnowledge + ocText)
        input.contains(NEVER_TITLE) -> StubScript(stdoutLines = ocInbox + ocGrep + ocSend + ocText)
        input.contains(NEUTRAL_TITLE) -> StubScript(stdoutLines = ocInbox + ocKnowledge + otRead + ocText)
        else -> StubScript.default()
    }

    // --- helpers ---------------------------------------------------------------------------------------------------

    private fun launch(dir: java.io.File, runner: ProcessRunner): DaemonFixture =
        DaemonFixture.launch(runner = runner, stateDir = dir).also { fixtures += it }

    private fun instanceOf(ready: Envelope): String =
        assertNotNull(Regex("instance=(\\S+)").find(ready.data)?.groupValues?.get(1), "ready names its instance: ${ready.data}")

    private fun decode(envelope: Envelope) = json.decodeFromString(Decision.serializer(), envelope.data)

    private fun list(fixture: DaemonFixture, includeSuperseded: Boolean, occurrence: Int): List<Decision> {
        fixture.send(Envelope.DECISION_LIST, """{"projectId":"$PROJECT","includeSuperseded":$includeSuperseded}""")
        return json.decodeFromString(ListSerializer(Decision.serializer()), fixture.client.await(Envelope.DECISIONS, occurrence).data)
    }

    /** The decisions table read straight off the daemon's own SQLite handle: (number, supersedes). */
    private fun tableRows(fixture: DaemonFixture): List<Pair<Int, Int?>> =
        fixture.sqlStore.database.decisionsQueries.getProjectDecisions(PROJECT).executeAsList()
            .map { it.number.toInt() to it.supersedes?.toInt() }

    private fun seedAgents(fixture: DaemonFixture) {
        fixture.daemon.store.traits.saveWith("tdd") { TraitView("tdd", "TDD", 1, "red first", null, 1L, 1L) }
        fixture.client.saveProfile("""{"id":"backend","name":"Backend","role":"backend","instructions":"You are the backend agent.","traitIds":["tdd"]}""")
        for (id in listOf("ctl-explore", "ctl-never", "ctl-neutral")) {
            fixture.client.saveProfile("""{"id":"$id","name":"$id","role":"backend","instructions":"You are $id."}""")
        }
    }

    /** Start a ticket on the project and wait for its first run to finish; returns the session id. */
    private fun startTicket(fixture: DaemonFixture, title: String, description: String, agentId: String): String {
        val ticket = fixture.client.saveTicket(
            """{"title":"$title","description":"$description","agentId":"$agentId","projectId":"$PROJECT"}""",
        )
        val started = fixture.client.moveTicket(ticket.id, TicketStatus.IN_PROGRESS)
        assertEquals("started", started.start?.reason, "the ticket must start: ${started.start?.detail}")
        val sessionId = started.sessionId!!
        fixture.await(what = "session $sessionId's first run to finish") {
            fixture.daemon.manager.get(sessionId)?.let { it.runCount == 1 && it.state == SessionState.IDLE } == true
        }
        return sessionId
    }

    /**
     * The session's provider stdout as the DAEMON recorded it: a fresh client asks `replay_request` from sequence 0,
     * exactly what `cli/SessionReplay.kt` sends after a reconnect, and keeps the replayed `stdout` frames in order.
     */
    private fun recordedStdout(fixture: DaemonFixture, sessionId: String): List<String> {
        val reader = fixture.connectClient()
        reader.send(Envelope("replay_request", """{"lastSequence":0}""", sessionId))
        reader.awaitWhere("the replayed finish of $sessionId") { it.type == Envelope.FINISHED && it.sessionId == sessionId }
        return reader.frames.filter { it.type == Envelope.STDOUT && it.sessionId == sessionId }
            .sortedBy { it.sequence }
            .map { it.data }
    }

    /**
     * A [ProcessRunner] that picks each run's [StubScript] from the first turn it is handed, then replays it through
     * the production [ScriptedProcess]. Several sessions of one daemon can then run different scripted agents.
     */
    private class RoutedRunner(private val route: (String) -> StubScript) : ProcessRunner {
        override val id: String = "routed"
        val processes = CopyOnWriteArrayList<RoutedProcess>()

        override fun start(spec: ProcessSpec, listener: ProcessListener): RunningProcess =
            RoutedProcess(listener, route).also { processes += it }
    }

    private class RoutedProcess(
        private val listener: ProcessListener,
        private val route: (String) -> StubScript,
    ) : RunningProcess {
        private val input = StringBuilder()
        @Volatile private var inner: ScriptedProcess? = null
        @Volatile private var killed = false

        val deliveredInput: String get() = input.toString()

        override val isAlive: Boolean get() = !killed && (inner?.isAlive ?: true)

        override fun writeInput(text: String) {
            input.append(text)
        }

        override fun closeInput() {
            if (killed) return
            val process = ScriptedProcess(listener, route(deliveredInput), 0)
            process.writeInput(deliveredInput)
            inner = process
            process.closeInput()
        }

        override fun killTree() {
            killed = true
            inner?.killTree()
        }

        override fun awaitExit(): Int {
            val deadline = System.currentTimeMillis() + 30_000
            while (inner == null && !killed && System.currentTimeMillis() < deadline) Thread.sleep(10)
            return inner?.awaitExit() ?: 1
        }
    }

    private companion object {
        const val PROJECT = "proj-recall"
        const val D1_SUMMARY = "Transcript archives: gzip"
        const val D2_SUMMARY = "Transcript archives: zstd level 19"
        const val GATE_TITLE = "Add transcript export"
        const val EXPLORE_TITLE = "Control explore first"
        const val NEVER_TITLE = "Control never read"
        const val NEUTRAL_TITLE = "Control neutral first"
    }
}
