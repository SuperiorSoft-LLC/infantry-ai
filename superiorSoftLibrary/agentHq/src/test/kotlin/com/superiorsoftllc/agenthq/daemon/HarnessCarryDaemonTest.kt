package com.superiorsoftllc.agenthq.daemon

import com.superiorsoftllc.agenthq.protocol.AgentProfile
import com.superiorsoftllc.agenthq.protocol.AgentSwapped
import com.superiorsoftllc.agenthq.protocol.Envelope
import com.superiorsoftllc.agenthq.protocol.SessionSwapped
import com.superiorsoftllc.agenthq.protocol.SwapSession
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
 * HARNESS-CARRY-1 over the real daemon: a provider switch carries the conversation. The first run on the new harness
 * is handed the earlier turns (the AgentHQ transcript is the source of truth), the UI transcript stays one
 * conversation (the carried block is never a `turn` frame), nothing tells the agent it was switched, and only that
 * first run is seeded. Both entrances: a saved agent (`agent_swap`) and an unsaved session (`session_swap`).
 * The stub harnesses record exactly what each run was handed on stdin.
 */
class HarnessCarryDaemonTest {
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

    private fun DaemonFixture.awaitAtRest(sessionId: String, runs: Int) =
        await(what = "session $sessionId at rest after $runs run(s)") {
            daemon.manager.get(sessionId)!!.let { !it.live && it.runCount >= runs }
        }

    private fun DaemonFixture.turn(sessionId: String, text: String, runs: Int) {
        send(Envelope(Envelope.PROMPT, text, sessionId = sessionId))
        awaitAtRest(sessionId, runs)
    }

    private fun DaemonFixture.turnsOf(sessionId: String): List<String> =
        client.of(Envelope.TURN).filter { it.sessionId == sessionId }.map { it.data }

    /** What B's FIRST run must carry, and what it must not say. */
    private fun assertCarried(input: String, earlier: List<String>, now: String) {
        for (prompt in earlier) assertTrue(input.contains(prompt), "B's first run carries '$prompt': ${input.take(1200)}")
        assertTrue(input.contains("[stub] daemon is running without a provider CLI."), "and A's replies: ${input.take(1200)}")
        assertTrue(input.trimEnd().endsWith(now), "the new prompt comes last, whole: ${input.takeLast(300)}")
        assertTrue(input.indexOf(earlier.first()) < input.indexOf(earlier.last()), "oldest first: ${input.take(1200)}")
        assertFalse(input.contains("switch", ignoreCase = true), "nothing tells the agent it was switched: ${input.take(1200)}")
    }

    private val earlier = listOf("first: sketch the billing API", "second: add a refunds endpoint", "third: name the tables")
    private val question = "what did I ask first?"

    @Test
    fun aSavedAgentSwitchedAcrossHarnessesCarriesItsConversationIntoTheSameSession() = withDaemon { r ->
        val before = r.f.client.of(Envelope.AGENT).size
        r.f.send(Envelope(Envelope.AGENT_SAVE, json.encodeToString(AgentProfile.serializer(), AgentProfile("backend", "Backend", "backend", "stub"))))
        r.f.client.await(Envelope.AGENT, before)
        r.f.send(Envelope(Envelope.NEW, """{"agentId":"backend"}"""))
        r.f.await(what = "a bound session") { r.f.daemon.manager.list().any { it.agentId == "backend" } }
        val id = r.f.daemon.manager.list().single { it.agentId == "backend" }.id
        earlier.forEachIndexed { i, text -> r.f.turn(id, text, runs = i + 1) }
        assertEquals(3, r.stub.processes.size)

        val swaps = r.f.client.of(Envelope.AGENT_SWAPPED).size
        r.f.send(Envelope(Envelope.AGENT_SWAP, """{"agentId":"backend","provider":"second"}"""))
        val swapped = json.decodeFromString(AgentSwapped.serializer(), r.f.client.await(Envelope.AGENT_SWAPPED, swaps).data)
        assertEquals(SwapSession(SwapSession.KEPT, id), swapped.session, "the agent keeps its session: one conversation")
        assertEquals("second", r.f.daemon.manager.get(id)!!.provider)

        r.f.turn(id, question, runs = 1)
        assertEquals(1, r.second.processes.size)
        assertCarried(r.second.processes.single().deliveredInput, earlier, question)

        val turns = r.f.turnsOf(id)
        assertEquals(4, turns.size, "one continuous transcript on the one session: $turns")
        assertTrue(turns.last().endsWith(question), "the turn frame shows what was asked: ${turns.last()}")
        assertTrue(turns.none { it.contains(earlier.first()) && it.contains(question) }, "the carried block is invisible in the transcript")

        r.f.turn(id, "and then?", runs = 2)
        assertEquals("and then?", r.second.processes[1].deliveredInput, "only the first run on the new harness is seeded")
    }

    @Test
    fun anUnsavedSessionSwitchedAcrossHarnessesCarriesItsConversation() = withDaemon { r ->
        r.f.send(Envelope(Envelope.PROMPT, earlier[0]))
        val id = r.f.client.awaitWhere("the first turn", 10_000) { it.type == Envelope.TURN }.sessionId!!
        r.f.awaitAtRest(id, 1)
        r.f.turn(id, earlier[1], runs = 2)
        r.f.turn(id, earlier[2], runs = 3)

        val swaps = r.f.client.of(Envelope.SESSION_SWAPPED).size
        r.f.send(Envelope(Envelope.SESSION_SWAP, """{"sessionId":"$id","provider":"second"}"""))
        val swapped = json.decodeFromString(SessionSwapped.serializer(), r.f.client.await(Envelope.SESSION_SWAPPED, swaps).data)
        assertEquals("second", swapped.provider)
        assertFalse(swapped.newConversation, "a harness change no longer starts a new conversation")

        r.f.turn(id, question, runs = 1)
        assertCarried(r.second.processes.single().deliveredInput, earlier, question)
        assertEquals(earlier + question, r.f.turnsOf(id), "the transcript is the four turns as asked, nothing else")
    }
}
