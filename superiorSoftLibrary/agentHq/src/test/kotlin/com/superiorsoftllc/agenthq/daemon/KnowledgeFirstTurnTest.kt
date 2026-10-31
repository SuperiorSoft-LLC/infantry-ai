package com.superiorsoftllc.agenthq.daemon

import com.superiorsoftllc.agenthq.agents.KnowledgeCounts
import com.superiorsoftllc.agenthq.agents.KnowledgeDigest
import com.superiorsoftllc.agenthq.agents.renderKnowledge
import com.superiorsoftllc.agenthq.protocol.Envelope
import com.superiorsoftllc.agenthq.protocol.TicketStatus
import com.superiorsoftllc.agenthq.protocol.TraitView
import com.superiorsoftllc.agenthq.runner.ScriptedRunner
import com.superiorsoftllc.agenthq.runner.StubScript
import com.superiorsoftllc.agenthq.session.RetrievalClassifier
import com.superiorsoftllc.agenthq.session.SessionState
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * P10-K9 (docs/AGENTHQ-PHASE10-KNOWLEDGE.md K-D3, section 4.1) over the real daemon: every first-turn path of a session
 * with a project renders persona -> identity -> traits -> knowledge -> task, the knowledge counts read from the daemon's
 * own memory, decisions and issues stores when the turn renders (so a never-run session renders them at delivery).
 */
class KnowledgeFirstTurnTest {
    private var records = FakeKnowledgeRecords()


    @Test
    fun aTicketStartCarriesTheKnowledgeSourceAfterTraitsAndBeforeTheTicket() = withDaemon { fixture, runner ->
        seed(fixture)
        seedKnowledge(fixture)
        val ticket = fixture.client.saveTicket("""{"title":"Ship it","description":"the body","agentId":"backend","projectId":"$PROJECT"}""")
        val started = fixture.client.moveTicket(ticket.id, TicketStatus.IN_PROGRESS)
        assertEquals("started", started.start?.reason, "the ticket must start: ${started.start?.detail}")
        val sessionId = started.sessionId!!
        awaitFirstRunFinished(fixture, sessionId)

        val input = runner.processes.single().deliveredInput
        assertTrue(input.contains("$RENDERED\n\n$IDENTITY\n\n$TRAITS_BLOCK\n\n$FULL\n\nShip it\n\nthe body"), "traits, knowledge, ticket: ${input.take(3000)}")
        val sources = fixture.daemon.manager.get(sessionId)!!.contextSources
        assertEquals(listOf("agenthq", "team", "instructions", "identity", "traits", "knowledge", "ticket", "related"), sources.map { it.source })
        assertEquals(FULL.length, sources.single { it.source == "knowledge" }.chars)
    }

    @Test
    fun aPromptFramesOwnNewSessionCarriesTheKnowledgeOfItsProject() = withDaemon { fixture, runner ->
        seed(fixture)
        seedKnowledge(fixture)
        val bound = bind(fixture)
        // Drop the bind-at-creation session, so the connection's next prompt takes DaemonMain's own startNew branch.
        fixture.daemon.manager.delete(bound.id)
        fixture.send(Envelope(Envelope.PROMPT, "add an endpoint"))
        fixture.await(what = "the prompt's own session") { fixture.daemon.manager.list().isNotEmpty() }
        val session = fixture.daemon.manager.list().single()
        awaitFirstRunFinished(fixture, session.id)

        assertEquals(PROJECT, session.projectId, "the session carries the project its knowledge section names")
        assertEquals("$RENDERED\n\n$IDENTITY\n\n$TRAITS_BLOCK\n\n$FULL\n\n$ROLE_NOTES\n\nadd an endpoint", runner.processes.single().deliveredInput)
    }

    @Test
    fun aNeverRunSessionNamedByIdRendersItsKnowledgeFirst() = withDaemon { fixture, runner ->
        seed(fixture)
        val bound = bind(fixture)
        seedKnowledge(fixture)
        val other = fixture.connectClient()
        other.await(Envelope.READY)
        other.send(Envelope(Envelope.PROMPT, "add an endpoint", bound.id))
        awaitFirstRunFinished(fixture, bound.id)
        assertEquals("$RENDERED\n\n$IDENTITY\n\n$TRAITS_BLOCK\n\n$FULL\n\n$ROLE_NOTES\n\nadd an endpoint", runner.processes.single().deliveredInput)
    }

    @Test
    fun theSameConnectionsNeverRunSessionRendersItsKnowledgeFirst() = withDaemon { fixture, runner ->
        seed(fixture)
        val bound = bind(fixture)
        seedKnowledge(fixture)
        fixture.send(Envelope(Envelope.PROMPT, "add an endpoint"))
        awaitFirstRunFinished(fixture, bound.id)
        assertEquals("$RENDERED\n\n$IDENTITY\n\n$TRAITS_BLOCK\n\n$FULL\n\n$ROLE_NOTES\n\nadd an endpoint", runner.processes.single().deliveredInput)
    }

    @Test
    fun aNeverRunSessionRendersTheCountsOfItsDeliveryNotOfItsSpawn() = withDaemon { fixture, runner ->
        seed(fixture)
        val bound = bind(fixture)
        // Spawned over an EMPTY base: the counts must be read when the mail is delivered, not when the console was bound.
        seedKnowledge(fixture)
        val response = HttpClient.newHttpClient().send(
            HttpRequest.newBuilder(URI.create("http://127.0.0.1:${fixture.port}/mail/send"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("""{"from":"human","to":"${bound.id}","body":"ping from the owner"}"""))
                .build(),
            HttpResponse.BodyHandlers.ofString(),
        )
        assertEquals(200, response.statusCode(), response.body())
        awaitFirstRunFinished(fixture, bound.id)
        val input = runner.processes.single().deliveredInput
        assertTrue(input.startsWith("$RENDERED\n\n$IDENTITY\n\n$TRAITS_BLOCK\n\n$FULL\n\n$ROLE_NOTES\n\n"), "MailRouter.deliver renders the knowledge before the mail: ${input.take(3000)}")
        assertTrue(input.contains("ping from the owner"))
    }

    /**
     * BUG-G2e RED: the first turn CARRIES the knowledge (not just a pointer to `agenthq knowledge`): the active
     * decision D2, the open issue, the note index; never the superseded D1, never the fixed I2.
     */
    @Test
    fun aFirstTurnCarriesTheActiveDecisionsAndOpenIssuesNotTheSupersededOnes() = withDaemon { fixture, runner ->
        seed(fixture)
        seedKnowledge(fixture)
        val ticket = fixture.client.saveTicket("""{"title":"Ship it","description":"the body","agentId":"backend","projectId":"$PROJECT"}""")
        val sessionId = fixture.client.moveTicket(ticket.id, TicketStatus.IN_PROGRESS).sessionId!!
        awaitFirstRunFinished(fixture, sessionId)

        val input = runner.processes.single().deliveredInput
        assertTrue(input.contains("\nD2 use sqlite with WAL\n"), "the active decision D2 is in the first turn: ${input.take(3000)}")
        assertTrue(input.contains("\nI1 flaky start (src/a.kt)\n"), "the open issue is in the first turn: ${input.take(3000)}")
        assertTrue(input.contains("\narchitecture: how it fits\n"), "the note index is in the first turn")
        assertFalse(input.contains("D1 use sqlite"), "the superseded D1 is never listed as active: ${input.take(3000)}")
        assertFalse(input.contains("slow start"), "the fixed I2 is never listed as open")
        assertFalse(input.contains("run `agenthq knowledge`: it lists"), "the old fetch-it-first pointer is gone")
        assertEquals(
            RetrievalClassifier.Verdict.INJECTED,
            RetrievalClassifier.classifyLines(emptyList(), firstTurn = input).verdict,
            "G2's check: the knowledge was in the first prompt",
        )
    }

    @Test
    fun aSessionWithNoProjectGetsNoKnowledgeSection() = withDaemon { fixture, runner ->
        seed(fixture)
        seedKnowledge(fixture)
        val ticket = fixture.client.saveTicket("""{"title":"Ship it","description":"the body","agentId":"backend"}""")
        val started = fixture.client.moveTicket(ticket.id, TicketStatus.IN_PROGRESS)
        awaitFirstRunFinished(fixture, started.sessionId!!)
        val input = runner.processes.single().deliveredInput
        assertTrue(!input.contains("PROJECT KNOWLEDGE"), input.take(3000))
        assertTrue(fixture.daemon.manager.get(started.sessionId!!)!!.contextSources.none { it.source == "knowledge" })
    }

    // --- helpers ---------------------------------------------------------------------------------------------------

    private fun seed(fixture: DaemonFixture) {
        fixture.client.await(Envelope.READY)
        val store = fixture.daemon.store
        store.traits.saveWith("tdd") { TraitView("tdd", "TDD", 1, "red first", null, 1L, 1L) }
        fixture.client.saveProfile("""{"id":"backend","name":"Backend","role":"backend","instructions":"$PERSONA","traitIds":["tdd"]}""")
    }

    /** One note, one ACTIVE decision (D1 superseded by D2), one OPEN issue (I2 fixed): counts 1/1/1. */
    private fun seedKnowledge(fixture: DaemonFixture) {
        val daemon = fixture.daemon
        daemon.memoryNotes.save(PROJECT, "architecture", "how it fits", "the body")
        daemon.memoryNotes.save("another-project", "elsewhere", "not ours", "the body")
        records.addDecision(PROJECT, "use sqlite")
        records.addDecision(PROJECT, "use sqlite with WAL", supersedes = "D1")
        records.openIssue(PROJECT, "flaky start", "src/a.kt")
        records.openIssue(PROJECT, "slow start", "src/b.kt")
        records.fixIssue(PROJECT, "I2")
    }

    private fun bind(fixture: DaemonFixture) = run {
        fixture.send(Envelope(Envelope.NEW, """{"agentId":"backend","projectId":"$PROJECT"}"""))
        fixture.await(what = "the profile session to be bound at `new`") { fixture.daemon.manager.list().isNotEmpty() }
        fixture.daemon.manager.list().single().also { assertEquals(PROJECT, it.projectId) }
    }

    private fun awaitFirstRunFinished(fixture: DaemonFixture, sessionId: String) {
        fixture.await(what = "session $sessionId's first run to finish") {
            fixture.daemon.manager.get(sessionId)?.let { it.runCount == 1 && it.state == SessionState.IDLE } == true
        }
    }

    private fun withDaemon(block: (DaemonFixture, ScriptedRunner) -> Unit) {
        val script = StubScript.default()
        val runner = ScriptedRunner(script, delayMs = 0)
        records = FakeKnowledgeRecords().install()
        val fixture = DaemonFixture.launch(script, runner = runner)
        try {
            block(fixture, runner)
        } finally {
            fixture.stop()
            fixture.deleteState()
            FakeKnowledgeRecords.uninstall()
        }
    }

    private companion object {
        const val PROJECT = "proj-kn"
        const val PERSONA = "You are the backend agent."
        /** b7a55d4: the rendered instructions are the persona plus the shipped KNOWLEDGE.md. */
        val RENDERED: String = com.superiorsoftllc.agenthq.agents.SharedKnowledge.withKnowledge(PERSONA)!!
        const val IDENTITY =
            "You are running inside Infantry AI as role 'backend', agent id 'backend'. " +
                "Your environment carries the same identity (AGENTHQ_ROLE, AGENTHQ_AGENT_ID, AGENTHQ_SESSION_ID)."
        const val TRAITS_BLOCK = "Standing rules bound to your role (they apply to this whole session):\n\n## TDD (tdd v1)\nred first"
        /** ROLE-NOTES-1: the non-ticket first turns add the role-notes line for a role with no notes yet. */
        const val ROLE_NOTES =
            "ROLE NOTES: none yet for the 'backend' role. Use `agenthq memory index|show|search|save --scope role|session`; " +
                "the default scope is the shared project notes. A role note added later is announced to you in a mail."

        /** KnowledgeTest pins this text; here it only has to be the full form with the seeded counts and digest. */
        val FULL = renderKnowledge(
            PROJECT,
            KnowledgeCounts(
                notes = 1,
                activeDecisions = 1,
                openIssues = 1,
                digest = KnowledgeDigest(
                    decisions = listOf("D2 use sqlite with WAL"),
                    issues = listOf("I1 flaky start (src/a.kt)"),
                    notes = listOf("architecture: how it fits"),
                ),
            ),
        )!!
    }
}
