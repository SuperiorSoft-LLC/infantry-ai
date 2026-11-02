package com.superiorsoftllc.agenthq.daemon

import com.superiorsoftllc.agenthq.protocol.Envelope
import com.superiorsoftllc.agenthq.protocol.SessionSwapped
import com.superiorsoftllc.agenthq.harness.AgentProvider
import com.superiorsoftllc.agenthq.provider.StubProvider
import com.superiorsoftllc.agenthq.runner.ProcessRunner
import com.superiorsoftllc.agenthq.runner.ScriptedRunner
import com.superiorsoftllc.agenthq.runner.StubScript
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * SWAP-AGENT-1 over the real daemon: `session_swap` with a `role` swaps the AGENT of one session in place -- the same
 * session id and transcript, the role replaced (and the harness / model, when asked). The next turn is a first turn
 * under the NEW role (its identity line, from the re-frozen instance) and, HARNESS-CARRY-1, it carries the earlier
 * turns. Nothing tells the agent it was switched. A role the daemon does not know is refused and changes nothing.
 */
class SessionRoleSwapDaemonTest {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private class Second(val runner: ScriptedRunner) : AgentProvider by StubProvider(delayMs = 0) {
        override val id = "second"
        override fun transport(): ProcessRunner = runner
    }

    private class Rig(val f: DaemonFixture, val stub: ScriptedRunner, val second: ScriptedRunner)

    private fun withDaemon(block: (Rig) -> Unit) {
        val stub = ScriptedRunner(StubScript.default(), 0)
        val second = ScriptedRunner(StubScript.default(), 0)
        val f = DaemonFixture.launch(runner = stub, additionalProviders = listOf(Second(second)))
        try {
            f.client.await(Envelope.READY)
            block(Rig(f, stub, second))
        } finally {
            f.stop()
            f.deleteState()
        }
    }

    private fun DaemonFixture.turn(sessionId: String, text: String, runs: Int) {
        send(Envelope(Envelope.PROMPT, text, sessionId = sessionId))
        await(what = "session $sessionId at rest after $runs run(s)") { daemon.manager.get(sessionId)!!.let { !it.live && it.runCount >= runs } }
    }

    /** A role-only session (the New agent box's spawn) with two turns on the default `stub` harness. */
    private fun Rig.pmSession(): String {
        f.send(Envelope(Envelope.NEW, """{"role":"pm"}"""))
        f.await(what = "a pm session") { f.daemon.manager.list().any { it.role == "pm" } }
        val id = f.daemon.manager.list().single { it.role == "pm" }.id
        earlier.forEachIndexed { i, text -> f.turn(id, text, runs = i + 1) }
        return id
    }

    private val earlier = listOf("first: sketch the billing API", "second: add a refunds endpoint")
    private val question = "what did I ask first?"

    private fun assertFirstTurnAsBackend(input: String) {
        for (prompt in earlier) assertTrue(input.contains(prompt), "the first run as backend carries '$prompt': ${input.take(1500)}")
        val identity = input.lastIndexOf("role 'backend'")
        assertTrue(identity > input.lastIndexOf(earlier.last()), "the new role's identity opens the new turn, after the carried ones: ${input.take(1500)}")
        assertTrue(input.trimEnd().endsWith(question), "the new prompt comes last: ${input.takeLast(300)}")
        assertFalse(input.contains("switch", ignoreCase = true), "nothing tells the agent it was switched: ${input.take(1500)}")
    }

    @Test
    fun aRoleAndHarnessSwapKeepsTheSessionAndTheNextTurnRunsAsTheNewRoleWithTheConversation() = withDaemon { r ->
        val id = r.pmSession()
        val sessionsBefore = r.f.daemon.manager.list().map { it.id }

        val swaps = r.f.client.of(Envelope.SESSION_SWAPPED).size
        r.f.send(Envelope(Envelope.SESSION_SWAP, """{"sessionId":"$id","provider":"second","role":"backend"}"""))
        val swapped = json.decodeFromString(SessionSwapped.serializer(), r.f.client.await(Envelope.SESSION_SWAPPED, swaps).data)
        assertEquals(id, swapped.sessionId)
        assertEquals("backend", swapped.role, "the push names the role the session now runs as")
        assertEquals("pm", swapped.fromRole)
        assertEquals("second", swapped.provider)
        val record = r.f.daemon.manager.get(id)!!
        assertEquals("backend", record.role)
        assertEquals("second", record.provider)
        assertEquals(sessionsBefore, r.f.daemon.manager.list().map { it.id }, "no new session")
        assertEquals("backend", r.f.daemon.manager.instance(id)?.role, "the frozen instance is the new role's")

        r.f.turn(id, question, runs = 1)
        assertEquals(1, r.second.processes.size)
        assertFirstTurnAsBackend(r.second.processes.single().deliveredInput)
        r.f.turn(id, "and then?", runs = 2)
        assertEquals("and then?", r.second.processes[1].deliveredInput, "only the first run after the swap is seeded")
    }

    @Test
    fun aRoleOnlySwapOnTheSameHarnessStartsAFreshCliConversationThatCarriesTheTranscript() = withDaemon { r ->
        val id = r.pmSession()
        val runsBefore = r.stub.processes.size

        val swaps = r.f.client.of(Envelope.SESSION_SWAPPED).size
        r.f.send(Envelope(Envelope.SESSION_SWAP, """{"sessionId":"$id","provider":"stub","role":"backend"}"""))
        r.f.client.await(Envelope.SESSION_SWAPPED, swaps)
        val record = r.f.daemon.manager.get(id)!!
        assertEquals("backend", record.role)
        assertEquals("stub", record.provider)
        assertEquals(null, record.providerSessionId, "the old role's CLI conversation is not resumed")

        r.f.turn(id, question, runs = 1)
        assertFirstTurnAsBackend(r.stub.processes[runsBefore].deliveredInput)
    }

    @Test
    fun anUnknownRoleIsRefusedAndChangesNothing() = withDaemon { r ->
        val id = r.pmSession()
        val before = r.f.daemon.manager.get(id)!!
        val errors = r.f.client.of(Envelope.ERROR).size
        r.f.send(Envelope(Envelope.SESSION_SWAP, """{"sessionId":"$id","provider":"second","role":"ghost"}"""))
        val error = r.f.client.await(Envelope.ERROR, errors).data
        assertTrue(error.startsWith("session_swap refused: ") && error.contains("ghost"), error)
        val after = r.f.daemon.manager.get(id)!!
        assertEquals(before.role, after.role)
        assertEquals(before.provider, after.provider, "a refused role leaves the harness alone too")
        assertEquals(before.providerSessionId, after.providerSessionId)
    }
}
