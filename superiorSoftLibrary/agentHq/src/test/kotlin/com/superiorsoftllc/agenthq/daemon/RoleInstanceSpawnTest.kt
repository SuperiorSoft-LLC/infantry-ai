package com.superiorsoftllc.agenthq.daemon

import com.superiorsoftllc.agenthq.orchestration.NodeSpec
import com.superiorsoftllc.agenthq.orchestration.PodSubmitRequest
import com.superiorsoftllc.agenthq.protocol.Envelope
import com.superiorsoftllc.agenthq.protocol.TicketStatus
import com.superiorsoftllc.agenthq.protocol.TraitView
import com.superiorsoftllc.agenthq.runner.ProcessListener
import com.superiorsoftllc.agenthq.runner.ProcessRunner
import com.superiorsoftllc.agenthq.runner.ProcessSpec
import com.superiorsoftllc.agenthq.runner.RunningProcess
import com.superiorsoftllc.agenthq.runner.ScriptedRunner
import com.superiorsoftllc.agenthq.runner.StubScript
import com.superiorsoftllc.agenthq.session.RoleInstance
import com.superiorsoftllc.agenthq.session.SessionState
import com.superiorsoftllc.agenthq.session.TraitSnapshot
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * TRAITS-T4 + T5 (docs/AGENTHQ-ROLE-TRAITS.md sections 3 and 4) over the real daemon and a real WebSocket.
 *
 * T4: every one of the five callers of `SessionManager.startNew` (ticket hand-out, trigger prompt, `DaemonMain` new +
 * prompt, bind at creation, pods) is driven through its real frame, and the `session_instances` row it leaves is compared
 * with the instance the agent resolves to at spawn: persona plus the role's traits then the agent's additions, as
 * snapshots. The row never changes afterwards (a persona edit, a trait edit, a role rebind), an anonymous spawn has none,
 * and `session_delete` takes it with the record.
 *
 * T5: each path's first turn is persona -> identity -> traits -> task, rendered from that row; the three never-run
 * first-turn sites (`MailRouter.deliver` and both `DaemonMain` branches) read the FROZEN row after a profile edit.
 */
class RoleInstanceSpawnTest {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    // --- T4: one parity test per spawn path ------------------------------------------------------------------

    @Test
    fun path1TicketHandOutFreezesTheInstanceItRendered() = withDaemon { fixture, runner ->
        seed(fixture)
        val ticket = fixture.client.saveTicket("""{"title":"Ship it","description":"the body","agentId":"backend"}""")
        val started = fixture.client.moveTicket(ticket.id, TicketStatus.IN_PROGRESS)
        assertEquals("started", started.start?.reason, "the ticket must start: ${started.start?.detail}")
        val sessionId = started.sessionId!!
        awaitFirstRunFinished(fixture, sessionId)

        assertEquals(EXPECTED, row(fixture, sessionId), "the frozen row is the agent's instance at spawn")
        val input = runner.inner.processes.single().deliveredInput
        assertTrue(input.contains("$PERSONA\n\n$IDENTITY\n\n$TRAITS_BLOCK\n\nShip it\n\nthe body"), "persona, identity, traits, ticket: ${input.take(1500)}")
        val sources = fixture.daemon.manager.get(sessionId)!!.contextSources.map { it.source }
        assertEquals(listOf("instructions", "identity", "traits", "ticket"), sources.filter { it in setOf("instructions", "identity", "traits", "ticket") })
        assertEquals(TRAITS_BLOCK.length, fixture.daemon.manager.get(sessionId)!!.contextSources.single { it.source == "traits" }.chars)
    }

    @Test
    fun path2TriggerPromptFreezesTheInstanceItRendered() = withDaemon { fixture, runner ->
        seed(fixture)
        val mark = fixture.client.mark()
        fixture.send(Envelope(Envelope.TRIGGER_SAVE, """{"name":"digest","coalesceMs":0,"action":{"kind":"prompt","agentId":"backend","prompt":"summarise the day"}}"""))
        val triggerId = json.parseToJsonElement(
            fixture.client.awaitAfter(mark, "the saved trigger") { it.type == Envelope.TRIGGER }.data,
        ).jsonObject.getValue("id").jsonPrimitive.content
        fixture.send(Envelope(Envelope.TRIGGER_FIRE, triggerId))
        fixture.await(what = "the trigger's session") { fixture.daemon.manager.list().isNotEmpty() }
        val sessionId = fixture.daemon.manager.list().single().id
        awaitFirstRunFinished(fixture, sessionId)

        assertEquals(EXPECTED, row(fixture, sessionId))
        assertEquals("$PERSONA\n\n$IDENTITY\n\n$TRAITS_BLOCK\n\nsummarise the day", runner.inner.processes.single().deliveredInput)
    }

    @Test
    fun path3DaemonNewThenPromptFreezesTheInstanceItRendered() = withDaemon { fixture, runner ->
        seed(fixture)
        fixture.send(Envelope(Envelope.NEW, """{"agentId":"backend"}"""))
        fixture.await(what = "the bound session") { fixture.daemon.manager.list().isNotEmpty() }
        // Drop the bind-at-creation session, so the connection's next prompt takes DaemonMain's own startNew branch.
        fixture.daemon.manager.delete(fixture.daemon.manager.list().single().id)
        fixture.send(Envelope(Envelope.PROMPT, "add an endpoint"))
        fixture.await(what = "the prompt's own session") { fixture.daemon.manager.list().isNotEmpty() }
        val sessionId = fixture.daemon.manager.list().single().id
        awaitFirstRunFinished(fixture, sessionId)

        assertEquals(EXPECTED, row(fixture, sessionId))
        assertEquals("$PERSONA\n\n$IDENTITY\n\n$TRAITS_BLOCK\n\nadd an endpoint", runner.inner.processes.single().deliveredInput)
    }

    @Test
    fun path4BindAtCreationFreezesTheInstanceBeforeAnyRun() = withDaemon { fixture, _ ->
        seed(fixture)
        val bound = bindBackend(fixture)
        assertEquals(0, bound.runCount, "binding starts no run")
        assertEquals(EXPECTED, row(fixture, bound.id), "the row exists the moment the console is bound")
    }

    @Test
    fun path5PodSpawnsAreAnonymousAndWriteNoRow() = withDaemon { fixture, runner ->
        seed(fixture)
        val already = fixture.client.of(Envelope.POD).size
        fixture.send(Envelope.POD_SUBMIT, json.encodeToString(PodSubmitRequest.serializer(), PodSubmitRequest(goal = "g", nodes = listOf(NodeSpec("a", "do a")))))
        fixture.client.await(Envelope.POD, occurrence = already)
        fixture.await(what = "the pod's session to run") { runner.inner.processes.isNotEmpty() }
        val sessionId = fixture.daemon.manager.list().single().id
        assertNull(row(fixture, sessionId), "an anonymous (pod) session has no instance row")
        assertNull(fixture.daemon.manager.instance(sessionId))
        assertEquals("do a", runner.inner.processes.single().deliveredInput, "no persona, identity or traits for a pod")
    }

    @Test
    fun anAnonymousConsoleWritesNoRowAndKeepsItsTextAsTyped() = withDaemon { fixture, runner ->
        seed(fixture)
        fixture.send(Envelope(Envelope.PROMPT, "hello agent"))
        fixture.await(what = "the anonymous session") { fixture.daemon.manager.list().isNotEmpty() }
        val sessionId = fixture.daemon.manager.list().single().id
        awaitFirstRunFinished(fixture, sessionId)
        assertNull(row(fixture, sessionId))
        assertEquals("hello agent", runner.inner.processes.single().deliveredInput)
    }

    // --- T4: immutability + delete ------------------------------------------------------------------------------

    @Test
    fun aLaterPersonaTraitAndRoleEditNeverChangesTheRow() = withDaemon { fixture, _ ->
        seed(fixture)
        val bound = bindBackend(fixture)
        editEverything(fixture)
        assertEquals(EXPECTED, row(fixture, bound.id), "the row is frozen at spawn")
        assertEquals(EXPECTED, fixture.daemon.manager.instance(bound.id))
        // A NEW spawn sees the edits, which proves the edits really landed.
        val fresh = fixture.daemon.manager.list().size
        val other = fixture.connectClient()
        other.await(Envelope.READY)
        other.send(Envelope(Envelope.NEW, """{"agentId":"backend"}"""))
        fixture.await(what = "a second bound session") { fixture.daemon.manager.list().size == fresh + 1 }
        val second = fixture.daemon.manager.list().single { it.id != bound.id }
        val now = row(fixture, second.id)!!
        assertEquals("You are the NEW backend agent.", now.instructions)
        assertEquals(listOf("tdd" to 1), now.traits.map { it.id to it.version }, "the role was rebound to no traits; the addition stays")
    }

    @Test
    fun sessionDeleteDeletesTheRow() = withDaemon { fixture, _ ->
        seed(fixture)
        val bound = bindBackend(fixture)
        assertNotNull(row(fixture, bound.id))
        fixture.daemon.manager.delete(bound.id)
        assertNull(row(fixture, bound.id))
    }

    // --- T5: the never-run first-turn sites read the FROZEN instance ---------------------------------------------

    @Test
    fun aDeliveredFirstTurnUsesTheFrozenInstanceAfterAProfileEdit() = withDaemon { fixture, runner ->
        seed(fixture)
        val bound = bindBackend(fixture)
        editEverything(fixture)
        val response = HttpClient.newHttpClient().send(
            HttpRequest.newBuilder(URI.create("http://127.0.0.1:${fixture.port}/mail/send"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("""{"from":"human","to":"${bound.id}","body":"ping from the owner"}"""))
                .build(),
            HttpResponse.BodyHandlers.ofString(),
        )
        assertEquals(200, response.statusCode(), response.body())
        awaitFirstRunFinished(fixture, bound.id)
        val input = runner.inner.processes.single().deliveredInput
        assertTrue(input.startsWith("$PERSONA\n\n$IDENTITY\n\n$TRAITS_BLOCK\n\n"), "MailRouter.deliver must render the FROZEN instance: ${input.take(1500)}")
        assertTrue(input.contains("ping from the owner"))
    }

    @Test
    fun anExplicitSessionIdFirstTurnUsesTheFrozenInstanceAfterAProfileEdit() = withDaemon { fixture, runner ->
        seed(fixture)
        val bound = bindBackend(fixture)
        editEverything(fixture)
        val other = fixture.connectClient()
        other.await(Envelope.READY)
        other.send(Envelope(Envelope.PROMPT, "add an endpoint", bound.id))
        awaitFirstRunFinished(fixture, bound.id)
        assertEquals("$PERSONA\n\n$IDENTITY\n\n$TRAITS_BLOCK\n\nadd an endpoint", runner.inner.processes.single().deliveredInput)
    }

    @Test
    fun theSameConnectionsFirstTurnUsesTheFrozenInstanceAfterAProfileEdit() = withDaemon { fixture, runner ->
        seed(fixture)
        val bound = bindBackend(fixture)
        editEverything(fixture)
        fixture.send(Envelope(Envelope.PROMPT, "add an endpoint"))
        awaitFirstRunFinished(fixture, bound.id)
        assertEquals("$PERSONA\n\n$IDENTITY\n\n$TRAITS_BLOCK\n\nadd an endpoint", runner.inner.processes.single().deliveredInput)
    }

    // --- P10-K13: the effective skill ids, frozen on the instance at spawn -----------------------------------------

    @Test
    fun aTicketHandOutFreezesTheEffectiveSkillsAndATicketEditAfterSpawnLeavesTheRowUnchanged() = withDaemon { fixture, _ ->
        seed(fixture)
        val store = fixture.daemon.store
        for (id in listOf("s-agent", "s-project", "s-ticket", "s-late")) store.skills.upsertByName(id, "Use when $id", "body", emptyList(), 1L) { id }
        fixture.client.saveProfile("""{"id":"backend","name":"Backend","role":"backend","instructions":"$PERSONA","traitIds":["tdd","report-back"],"skillIds":["s-agent","s-project"]}""")
        store.settings.put(com.superiorsoftllc.agenthq.persistence.SettingRow("context.skillIds", """["s-project"]""", 1L))
        val ticket = fixture.client.saveTicket("""{"title":"Ship it","description":"the body","agentId":"backend","skillIds":["s-ticket","s-agent"]}""")
        val beforeStart = fixture.client.mark()
        val sessionId = fixture.client.moveTicket(ticket.id, TicketStatus.IN_PROGRESS).sessionId!!
        awaitFirstRunFinished(fixture, sessionId)
        // The session goes IDLE under the session manager's monitor; the board records the run's end LATER, on its
        // `sessionEvents` executor, and then pushes one `ticket` frame carrying the ended lastRun. Arriving after
        // the edit's mark, that push was taken as the edit's answer, so :220 read the store before the edit landed.
        // Wait for the push itself (the TicketStartDaemonTest pattern), so the edit below gets its own answer.
        fixture.client.awaitAfter(beforeStart, "the board publishes the finished run on its ticket") {
            it.type == Envelope.TICKET && it.ticketOrNull()?.let { t -> t.id == ticket.id && t.lastRun?.endedAt != null } == true
        }

        val frozen = listOf("s-agent", "s-project", "s-ticket")
        assertEquals(EXPECTED.copy(skillIds = frozen), row(fixture, sessionId), "agent, then project, then ticket, first wins")

        fixture.client.saveTicket("""{"id":"${ticket.id}","title":"Ship it","skillIds":["s-late"],"skillsMode":"replace"}""")
        assertEquals(listOf("s-late"), store.tickets.get(ticket.id)!!.skillIds, "the ticket edit landed")
        assertEquals(frozen, row(fixture, sessionId)!!.skillIds, "the row is frozen at spawn")
        assertEquals(frozen, fixture.daemon.manager.skillIds(sessionId))
    }

    // --- helpers ---------------------------------------------------------------------------------------------------

    /** Two library traits, the backend role binding `report-back`, and the backend profile adding `tdd` + a persona. */
    private fun seed(fixture: DaemonFixture) {
        fixture.client.await(Envelope.READY)
        val store = fixture.daemon.store
        store.traits.saveWith("report-back") { TraitView("report-back", "REPORT BACK", 1, "mail the pm", null, 1L, 1L) }
        store.traits.saveWith("tdd") { TraitView("tdd", "TDD", 1, "red first", null, 1L, 1L) }
        store.roleTraits.set("backend", listOf("report-back"), 1L)
        fixture.client.saveProfile("""{"id":"backend","name":"Backend","role":"backend","instructions":"$PERSONA","traitIds":["tdd","report-back"]}""")
    }

    /** After spawn: a new persona, `report-back` edited to v2, and the role's list cleared. */
    private fun editEverything(fixture: DaemonFixture) {
        val store = fixture.daemon.store
        fixture.client.saveProfile("""{"id":"backend","name":"Backend","role":"backend","instructions":"You are the NEW backend agent.","traitIds":["tdd"]}""")
        store.traits.saveWith("report-back") { TraitView("report-back", "REPORT BACK", 2, "mail the pm NOW", null, 1L, 2L) }
        store.roleTraits.set("backend", emptyList(), 2L)
    }

    private fun bindBackend(fixture: DaemonFixture) = run {
        fixture.send(Envelope(Envelope.NEW, """{"agentId":"backend"}"""))
        fixture.await(what = "the profile session to be bound at `new`") { fixture.daemon.manager.list().isNotEmpty() }
        fixture.daemon.manager.list().single()
    }

    private fun row(fixture: DaemonFixture, sessionId: String): RoleInstance? = fixture.daemon.store.sessionInstances.get(sessionId)

    private fun awaitFirstRunFinished(fixture: DaemonFixture, sessionId: String) {
        fixture.await(what = "session $sessionId's first run to finish") {
            fixture.daemon.manager.get(sessionId)?.let { it.runCount == 1 && it.state == SessionState.IDLE } == true
        }
    }

    private class RecordingRunner(val inner: ScriptedRunner) : ProcessRunner {
        val specs: MutableList<ProcessSpec> = CopyOnWriteArrayList()
        override val id: String = "recording-${inner.id}"
        override fun start(spec: ProcessSpec, listener: ProcessListener): RunningProcess {
            specs += spec
            return inner.start(spec, listener)
        }
    }

    private fun withDaemon(block: (DaemonFixture, RecordingRunner) -> Unit) {
        val script = StubScript.default()
        val runner = RecordingRunner(ScriptedRunner(script, delayMs = 0))
        val fixture = DaemonFixture.launch(script, runner = runner)
        try {
            block(fixture, runner)
        } finally {
            fixture.stop()
            fixture.deleteState()
        }
    }

    private companion object {
        const val PERSONA = "You are the backend agent."
        const val IDENTITY =
            "You are running inside Infantry AI as role 'backend', agent id 'backend'. " +
                "Your environment carries the same identity (AGENTHQ_ROLE, AGENTHQ_AGENT_ID, AGENTHQ_SESSION_ID)."

        /** Role list first (`report-back`), then the agent's additions (`tdd`; its duplicate `report-back` dropped). */
        const val TRAITS_BLOCK =
            "Standing rules bound to your role (they apply to this whole session):\n\n" +
                "## REPORT BACK (report-back v1)\nmail the pm\n\n" +
                "## TDD (tdd v1)\nred first"

        val EXPECTED = RoleInstance(
            agentId = "backend",
            role = "backend",
            instructions = PERSONA,
            traits = listOf(
                TraitSnapshot("report-back", 1, "REPORT BACK", "mail the pm"),
                TraitSnapshot("tdd", 1, "TDD", "red first"),
            ),
        )
    }
}
