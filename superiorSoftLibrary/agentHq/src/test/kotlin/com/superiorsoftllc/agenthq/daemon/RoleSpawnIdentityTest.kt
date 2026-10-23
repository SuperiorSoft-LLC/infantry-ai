package com.superiorsoftllc.agenthq.daemon

import com.superiorsoftllc.agenthq.mailbox.Message
import com.superiorsoftllc.agenthq.mailbox.MessageState
import com.superiorsoftllc.agenthq.protocol.Envelope
import com.superiorsoftllc.agenthq.protocol.TicketStatus
import com.superiorsoftllc.agenthq.runner.ProcessListener
import com.superiorsoftllc.agenthq.runner.ProcessRunner
import com.superiorsoftllc.agenthq.runner.ProcessSpec
import com.superiorsoftllc.agenthq.runner.RunningProcess
import com.superiorsoftllc.agenthq.runner.ScriptedRunner
import com.superiorsoftllc.agenthq.runner.StubScript
import com.superiorsoftllc.agenthq.session.SessionState
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.io.IOException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * BUG-808ce596: an agent added through the New agent modal (`new {"role":X}` then `prompt`) must KNOW its
 * role: in the harness environment (`AGENTHQ_ROLE`, plus `AGENTHQ_AGENT_ID` when a profile is bound) and
 * in its opening context (an identity line after the persona, before the first turn).
 *
 * Real daemon, real WebSocket, the exact frames the modal sends. The env names are string literals on
 * purpose: this file must compile against the code it proves wrong.
 */
class RoleSpawnIdentityTest {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    @Test
    fun aRoleOnlySpawnKnowsItsRoleInItsEnvironmentAndItsOpeningContext() = withDaemon { fixture, runner ->
        fixture.client.await(Envelope.READY)
        // What the New agent modal sends on Add: `new` naming the role (no agent profile), then the task.
        fixture.send(Envelope(Envelope.NEW, """{"role":"qa"}"""))
        fixture.send(Envelope(Envelope.PROMPT, "check the login flow"))
        val sessionId = finishedFirstRun(fixture)

        val env = runner.specs.single().env
        assertEquals("qa", env["AGENTHQ_ROLE"], "the harness env must name the role: $env")
        assertEquals(sessionId, env["AGENTHQ_SESSION_ID"])
        assertNull(env["AGENTHQ_AGENT_ID"], "no profile is bound, so no agent id is invented")
        assertEquals(
            "$QA_PERSONA\n\n$ROLE_ONLY_IDENTITY\n\ncheck the login flow",
            runner.inner.processes.single().deliveredInput,
            "ROLE-EDIT-1: the role's persona, then the identity, then the task",
        )
        assertEquals("qa", fixture.daemon.manager.get(sessionId)!!.role)
    }

    @Test
    fun aProfileSpawnCarriesItsAgentIdAndRoleAfterItsPersona() = withDaemon { fixture, runner ->
        fixture.client.await(Envelope.READY)
        fixture.send(
            Envelope(
                Envelope.AGENT_SAVE,
                """{"id":"backend","name":"Backend","role":"backend","instructions":"You are the backend agent."}""",
            ),
        )
        fixture.client.await(Envelope.AGENT)
        fixture.send(Envelope(Envelope.NEW, """{"agentId":"backend"}"""))
        fixture.send(Envelope(Envelope.PROMPT, "add an endpoint"))
        finishedFirstRun(fixture)

        val env = runner.specs.single().env
        assertEquals("backend", env["AGENTHQ_AGENT_ID"], "the harness env must name the bound agent: $env")
        assertEquals("backend", env["AGENTHQ_ROLE"], "the harness env must name the role: $env")
        assertEquals(
            "You are the backend agent.\n\n$PROFILE_IDENTITY\n\nadd an endpoint",
            runner.inner.processes.single().deliveredInput,
            "persona first, then who the agent is, then the task",
        )
    }

    @Test
    fun anAnonymousSpawnIsLeftExactlyAsTyped() = withDaemon { fixture, runner ->
        fixture.client.await(Envelope.READY)
        fixture.send(Envelope(Envelope.PROMPT, "hello agent"))
        finishedFirstRun(fixture)

        val env = runner.specs.single().env
        assertNull(env["AGENTHQ_ROLE"], "no role, no role variable: $env")
        assertNull(env["AGENTHQ_AGENT_ID"], "no profile, no agent id variable: $env")
        assertEquals("hello agent", runner.inner.processes.single().deliveredInput)
    }

    /**
     * The owner's second claim: the session id is not in another tool's home directory. AgentHQ never writes
     * outside its own state; its sessions live in the DAEMON's state dir and its mail API. This proves
     * a role-only session is registered the moment `new` lands (before any prompt), persisted, and
     * reachable BY SESSION ID through the agent-facing HTTP mail API -- and that a delivered first turn
     * carries the identity too.
     */
    @Test
    fun aRoleOnlySessionIsRegisteredAtNewAndMailToItsSessionIdCarriesItsRole() = withDaemon { fixture, runner ->
        fixture.client.await(Envelope.READY)
        fixture.send(Envelope(Envelope.NEW, """{"role":"qa"}"""))
        fixture.await(what = "the role-only session to be bound at `new`") { fixture.daemon.manager.list().isNotEmpty() }
        val bound = fixture.daemon.manager.list().single()
        assertEquals("qa", bound.role)
        assertNull(bound.agentId)
        assertEquals(SessionState.IDLE, bound.state)
        assertEquals(0, bound.runCount, "binding starts no run")
        val sessionsFile = File(fixture.stateDir, "sessions.json")
        // FLAKE-1: the daemon replaces this file by moving a temp file over it. On Windows a java.io read
        // (File.readText) opens without FILE_SHARE_DELETE, so a read landing on that move threw "being used by
        // another process", or made the daemon's own move fail. NIO opens with FILE_SHARE_DELETE; a read that
        // still lands in the replace window means "not yet", and the next poll reads the committed file.
        fixture.await(what = "the bound session persisted to the daemon's sessions file") {
            try {
                Files.readString(sessionsFile.toPath()).contains(bound.id)
            } catch (_: IOException) {
                false
            }
        }

        val response = HttpClient.newHttpClient().send(
            HttpRequest.newBuilder(URI.create("http://127.0.0.1:${fixture.port}/mail/send"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("""{"from":"human","to":"${bound.id}","body":"ping from the owner"}"""))
                .build(),
            HttpResponse.BodyHandlers.ofString(),
        )
        assertEquals(200, response.statusCode(), response.body())
        assertEquals(MessageState.DELIVERED, json.decodeFromString(Message.serializer(), response.body()).state, response.body())
        fixture.await(what = "the delivered first turn to finish") {
            fixture.daemon.manager.get(bound.id)?.let { it.runCount == 1 && it.state == SessionState.IDLE } == true
        }

        assertEquals("qa", runner.specs.single().env["AGENTHQ_ROLE"], "the mail-started run names the role in its env")
        val input = runner.inner.processes.single().deliveredInput
        assertTrue(input.startsWith("$QA_PERSONA\n\n$ROLE_ONLY_IDENTITY\n\n"), "a delivered first turn opens with the role's persona, then the identity: ${input.take(200)}")
        assertTrue(input.contains("ping from the owner"), input.take(400))
    }

    /**
     * Follow-up path 1: a client that NAMES a bound, never-run session (`prompt` with a sessionId, e.g. a
     * second tab or a reconnect) gives it its first turn, so that turn carries the persona and identity too.
     */
    @Test
    fun anExplicitSessionIdPromptToABoundNeverRunSessionCarriesItsPersonaAndIdentity() = withDaemon { fixture, runner ->
        fixture.client.await(Envelope.READY)
        saveBackendProfile(fixture)
        fixture.send(Envelope(Envelope.NEW, """{"agentId":"backend"}"""))
        fixture.await(what = "the profile session to be bound at `new`") { fixture.daemon.manager.list().isNotEmpty() }
        val bound = fixture.daemon.manager.list().single()
        assertEquals(0, bound.runCount, "binding starts no run")

        val other = fixture.connectClient()
        other.await(Envelope.READY)
        other.send(Envelope(Envelope.PROMPT, "add an endpoint", bound.id))
        awaitFirstRunFinished(fixture, bound.id)

        assertEquals("backend", runner.specs.single().env["AGENTHQ_ROLE"])
        assertEquals(
            "You are the backend agent.\n\n$PROFILE_IDENTITY\n\nadd an endpoint",
            runner.inner.processes.single().deliveredInput,
            "a first turn by session id: persona, then identity, then the task",
        )
    }

    /** Follow-up path 2: a ticket start's composed context names the agent's role and id after its persona. */
    @Test
    fun aTicketStartCarriesTheIdentityAfterThePersona() = withDaemon { fixture, runner ->
        fixture.client.await(Envelope.READY)
        saveBackendProfile(fixture)
        val ticket = fixture.client.saveTicket("""{"title":"Ship it","description":"the body","agentId":"backend"}""")
        val started = fixture.client.moveTicket(ticket.id, TicketStatus.IN_PROGRESS)
        assertEquals("started", started.start?.reason, "the ticket must start: ${started.start?.detail}")
        awaitFirstRunFinished(fixture, started.sessionId!!)

        assertEquals("backend", runner.specs.single().env["AGENTHQ_ROLE"])
        val input = runner.inner.processes.single().deliveredInput
        assertTrue(
            input.contains("You are the backend agent.\n\n$PROFILE_IDENTITY\n\nShip it\n\nthe body"),
            "persona, then identity, then the ticket: ${input.take(800)}",
        )
    }

    /** Follow-up path 3: a trigger's `prompt` action starts a session whose first turn names the agent. */
    @Test
    fun aPromptTriggerCarriesThePersonaAndIdentity() = withDaemon { fixture, runner ->
        fixture.client.await(Envelope.READY)
        saveBackendProfile(fixture)
        val mark = fixture.client.mark()
        fixture.send(
            Envelope(
                Envelope.TRIGGER_SAVE,
                """{"name":"digest","coalesceMs":0,"action":{"kind":"prompt","agentId":"backend","prompt":"summarise the day"}}""",
            ),
        )
        val triggerId = json.parseToJsonElement(
            fixture.client.awaitAfter(mark, "the saved trigger") { it.type == Envelope.TRIGGER }.data,
        ).jsonObject.getValue("id").jsonPrimitive.content
        fixture.send(Envelope(Envelope.TRIGGER_FIRE, triggerId))
        fixture.await(what = "the trigger's session") { fixture.daemon.manager.list().isNotEmpty() }
        awaitFirstRunFinished(fixture, fixture.daemon.manager.list().single().id)

        assertEquals("backend", runner.specs.single().env["AGENTHQ_ROLE"])
        assertEquals(
            "You are the backend agent.\n\n$PROFILE_IDENTITY\n\nsummarise the day",
            runner.inner.processes.single().deliveredInput,
            "a trigger's first turn: persona, then identity, then the prompt",
        )
    }

    // --- helpers ---------------------------------------------------------------------------------

    private fun awaitFirstRunFinished(fixture: DaemonFixture, sessionId: String) {
        fixture.await(what = "session $sessionId's first run to finish") {
            fixture.daemon.manager.get(sessionId)?.let { it.runCount == 1 && it.state == SessionState.IDLE } == true
        }
    }

    private fun saveBackendProfile(fixture: DaemonFixture) {
        fixture.client.saveProfile("""{"id":"backend","name":"Backend","role":"backend","instructions":"You are the backend agent."}""")
    }

    /** Wait for the first turn's run to finish; returns its session id. */
    private fun finishedFirstRun(fixture: DaemonFixture): String {
        val sessionId = fixture.client.awaitWhere("the first turn", 10_000) { it.type == Envelope.TURN }.sessionId!!
        fixture.client.awaitWhere("the first run to finish", 10_000) { it.type == Envelope.FINISHED && it.sessionId == sessionId }
        return sessionId
    }

    /** Records every spec (and so every env) the daemon hands its transport, then runs it on the script. */
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
        /** ROLE-EDIT-1: a role-only spawn now carries its role's persona (the bundled qa.md) ahead of the identity. */
        val QA_PERSONA: String = com.superiorsoftllc.agenthq.role.RoleCatalog.load().persona("qa")!!
        const val ROLE_ONLY_IDENTITY =
            "You are running inside Infantry AI as role 'qa'. " +
                "Your environment carries the same identity (AGENTHQ_ROLE, AGENTHQ_SESSION_ID)."
        const val PROFILE_IDENTITY =
            "You are running inside Infantry AI as role 'backend', agent id 'backend'. " +
                "Your environment carries the same identity (AGENTHQ_ROLE, AGENTHQ_AGENT_ID, AGENTHQ_SESSION_ID)."
    }
}
