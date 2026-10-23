package com.superiorsoftllc.agenthq.daemon

import com.superiorsoftllc.agenthq.agents.KnowledgeCounts
import com.superiorsoftllc.agenthq.agents.renderKnowledge
import com.superiorsoftllc.agenthq.agents.renderSkills
import com.superiorsoftllc.agenthq.persistence.SkillStore
import com.superiorsoftllc.agenthq.protocol.Envelope
import com.superiorsoftllc.agenthq.protocol.SkillSave
import com.superiorsoftllc.agenthq.protocol.SkillView
import com.superiorsoftllc.agenthq.protocol.TicketStatus
import com.superiorsoftllc.agenthq.runner.ScriptedRunner
import com.superiorsoftllc.agenthq.runner.StubScript
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
 * P10-K15 (docs/AGENTHQ-PHASE10-KNOWLEDGE.md K-D3 / K-D10) over the real daemon: every first-turn path renders
 * persona -> identity -> knowledge -> **skills** -> task. The ids are the session's FROZEN instance's (K13); the bodies
 * are read from the daemon's skill library when the turn renders.
 */
class SkillsFirstTurnTest {

    @Test
    fun aTicketStartCarriesTheSkillsSourceAfterKnowledgeAndBeforeTheTicket() = withDaemon { fixture, runner ->
        val (tdd, _) = seed(fixture)
        val ticket = fixture.client.saveTicket("""{"title":"Ship it","description":"the body","agentId":"backend","projectId":"$PROJECT"}""")
        val started = fixture.client.moveTicket(ticket.id, TicketStatus.IN_PROGRESS)
        assertEquals("started", started.start?.reason, "the ticket must start: ${started.start?.detail}")
        val sessionId = started.sessionId!!
        awaitFirstRunFinished(fixture, sessionId)

        val skills = expected(fixture, listOf(tdd.id))
        val input = runner.processes.single().deliveredInput
        assertTrue(input.contains("$PERSONA\n\n$IDENTITY\n\n$KNOWLEDGE\n\n$skills\n\nShip it\n\nthe body"), input.take(3000))
        val sources = fixture.daemon.manager.get(sessionId)!!.contextSources
        assertEquals(listOf("agenthq", "team", "instructions", "identity", "knowledge", "skills", "ticket"), sources.map { it.source })
        assertEquals(skills.length, sources.single { it.source == "skills" }.chars)
        assertEquals(listOf(tdd.id), fixture.daemon.manager.skillIds(sessionId), "what rendered is what froze")
    }

    @Test
    fun aTicketWithIncludeSkillsContextFalseGetsNoSection() = withDaemon { fixture, runner ->
        seed(fixture)
        val ticket = fixture.client.saveTicket(
            """{"title":"Ship it","description":"the body","agentId":"backend","projectId":"$PROJECT","includeSkillsContext":false}""",
        )
        val started = fixture.client.moveTicket(ticket.id, TicketStatus.IN_PROGRESS)
        awaitFirstRunFinished(fixture, started.sessionId!!)
        val input = runner.processes.single().deliveredInput
        assertFalse("SKILLS (" in input, input.take(3000))
        assertTrue(input.contains("$KNOWLEDGE\n\nShip it\n\nthe body"))
        assertTrue(fixture.daemon.manager.get(started.sessionId!!)!!.contextSources.none { it.source == "skills" })
    }

    @Test
    fun aPromptFramesOwnNewSessionCarriesItsSkills() = withDaemon { fixture, runner ->
        val (tdd, _) = seed(fixture)
        val bound = bind(fixture)
        fixture.daemon.manager.delete(bound.id)
        fixture.send(Envelope(Envelope.PROMPT, "add an endpoint"))
        fixture.await(what = "the prompt's own session") { fixture.daemon.manager.list().isNotEmpty() }
        val session = fixture.daemon.manager.list().single()
        awaitFirstRunFinished(fixture, session.id)
        assertEquals(
            "$PERSONA\n\n$IDENTITY\n\n$KNOWLEDGE\n\n$ROLE_NOTES\n\n${expected(fixture, listOf(tdd.id))}\n\nadd an endpoint",
            runner.processes.single().deliveredInput,
        )
    }

    @Test
    fun aNeverRunSessionNamedByIdRendersItsSkills() = withDaemon { fixture, runner ->
        val (tdd, _) = seed(fixture)
        val bound = bind(fixture)
        val other = fixture.connectClient()
        other.await(Envelope.READY)
        other.send(Envelope(Envelope.PROMPT, "add an endpoint", bound.id))
        awaitFirstRunFinished(fixture, bound.id)
        assertEquals(
            "$PERSONA\n\n$IDENTITY\n\n$KNOWLEDGE\n\n$ROLE_NOTES\n\n${expected(fixture, listOf(tdd.id))}\n\nadd an endpoint",
            runner.processes.single().deliveredInput,
        )
    }

    @Test
    fun theSameConnectionsNeverRunSessionRendersItsFrozenSkillsWithADeletedOneMarked() = withDaemon { fixture, runner ->
        val (tdd, _) = seed(fixture)
        val bound = bind(fixture)
        // Deleted AFTER spawn: the frozen id stays and renders as `(deleted)` (K-D9).
        SkillStore(fixture.daemon.store.skills).delete(tdd.id)
        fixture.send(Envelope(Envelope.PROMPT, "add an endpoint"))
        awaitFirstRunFinished(fixture, bound.id)
        val input = runner.processes.single().deliveredInput
        assertEquals("$PERSONA\n\n$IDENTITY\n\n$KNOWLEDGE\n\n$ROLE_NOTES\n\n${expected(fixture, listOf(tdd.id))}\n\nadd an endpoint", input)
        assertTrue("- ${tdd.id} (deleted)" in input, input)
    }

    @Test
    fun aMailDeliveredFirstTurnRendersSkillsAfterKnowledge() = withDaemon { fixture, runner ->
        val (tdd, _) = seed(fixture)
        val bound = bind(fixture)
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
        assertTrue(input.startsWith("$PERSONA\n\n$IDENTITY\n\n$KNOWLEDGE\n\n$ROLE_NOTES\n\n${expected(fixture, listOf(tdd.id))}\n\n"), input.take(3000))
        assertTrue(input.contains("ping from the owner"))
    }

    // --- helpers ---------------------------------------------------------------------------------------------------

    /** One attached skill (tdd), one visible unattached (review), one attachedOnly unattached (secret). */
    private fun seed(fixture: DaemonFixture): Pair<SkillView, SkillView> {
        fixture.client.await(Envelope.READY)
        val skills = SkillStore(fixture.daemon.store.skills)
        val tdd = skills.save(SkillSave(name = "tdd", description = "Use when writing code.", body = "RED first, then GREEN."))
        val review = skills.save(SkillSave(name = "review", description = "Use when reviewing a diff.", body = "Read it all."))
        skills.save(SkillSave(name = "secret", description = "Use when secret.", body = "hidden", attachedOnly = true))
        fixture.client.saveProfile("""{"id":"backend","name":"Backend","role":"backend","instructions":"$PERSONA","skillIds":["${tdd.id}"]}""")
        return tdd to review
    }

    /** The section, rendered from the library as it is NOW (the pure renderer is pinned by SkillsTest). */
    private fun expected(fixture: DaemonFixture, attached: List<String>): String {
        val text = renderSkills(attached, SkillStore(fixture.daemon.store.skills).list(null))!!
        assertFalse("secret" in text, "attachedOnly and unattached: hidden")
        return text
    }

    private fun bind(fixture: DaemonFixture) = run {
        fixture.send(Envelope(Envelope.NEW, """{"agentId":"backend","projectId":"$PROJECT"}"""))
        fixture.await(what = "the profile session to be bound at `new`") { fixture.daemon.manager.list().isNotEmpty() }
        fixture.daemon.manager.list().single()
    }

    private fun awaitFirstRunFinished(fixture: DaemonFixture, sessionId: String) {
        fixture.await(what = "session $sessionId's first run to finish") {
            fixture.daemon.manager.get(sessionId)?.let { it.runCount == 1 && it.state == SessionState.IDLE } == true
        }
    }

    private fun withDaemon(block: (DaemonFixture, ScriptedRunner) -> Unit) {
        val script = StubScript.default()
        val runner = ScriptedRunner(script, delayMs = 0)
        val fixture = DaemonFixture.launch(script, runner = runner)
        try {
            block(fixture, runner)
        } finally {
            fixture.stop()
            fixture.deleteState()
        }
    }

    private companion object {
        const val PROJECT = "proj-sk"
        const val PERSONA = "You are the backend agent."
        const val IDENTITY =
            "You are running inside Infantry AI as role 'backend', agent id 'backend'. " +
                "Your environment carries the same identity (AGENTHQ_ROLE, AGENTHQ_AGENT_ID, AGENTHQ_SESSION_ID)."

        /** An empty knowledge base: the short form (KnowledgeTest pins it). */
        val KNOWLEDGE = renderKnowledge(PROJECT, KnowledgeCounts(0, 0, 0))!!
        /** ROLE-NOTES-1: the non-ticket first turns add the role-notes line for a role with no notes yet. */
        const val ROLE_NOTES =
            "ROLE NOTES: none yet for the 'backend' role. Use `agenthq memory index|show|search|save --scope role|session`; " +
                "the default scope is the shared project notes. A role note added later is announced to you in a mail."
    }
}
