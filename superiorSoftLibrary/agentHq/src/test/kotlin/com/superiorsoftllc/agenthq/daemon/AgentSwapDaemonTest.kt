package com.superiorsoftllc.agenthq.daemon

import com.superiorsoftllc.agenthq.agents.SharedKnowledge
import com.superiorsoftllc.agenthq.mailbox.MailSendRequest
import com.superiorsoftllc.agenthq.protocol.AgentProfile
import com.superiorsoftllc.agenthq.protocol.AgentSwapped
import com.superiorsoftllc.agenthq.protocol.Envelope
import com.superiorsoftllc.agenthq.protocol.SwapExecutor
import com.superiorsoftllc.agenthq.protocol.SwapSession
import com.superiorsoftllc.agenthq.provider.AgentProvider
import com.superiorsoftllc.agenthq.provider.StubProvider
import com.superiorsoftllc.agenthq.runner.ProcessListener
import com.superiorsoftllc.agenthq.runner.ProcessRunner
import com.superiorsoftllc.agenthq.runner.ProcessSpec
import com.superiorsoftllc.agenthq.runner.RunningProcess
import com.superiorsoftllc.agenthq.runner.ScriptedRunner
import com.superiorsoftllc.agenthq.runner.StubScript
import com.superiorsoftllc.agenthq.session.SessionRecord
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Role-first S3 (contract 1.47.0) over the real daemon and real WebSockets: `agent_swap` moves one agent onto another
 * executor, decides its session's fate per docs/AGENTHQ-ROLE-FIRST.md 4.2 (every row), pushes `agent_swapped` to
 * EVERY client, and a refusal writes nothing.
 */
class AgentSwapDaemonTest {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    /** Records every command line it starts, and can hold a run open (its exit) until [release]. */
    private class HeldRunner(private val inner: ProcessRunner) : ProcessRunner {
        override val id = "held"
        val commands = CopyOnWriteArrayList<List<String>>()
        @Volatile private var gate: CountDownLatch? = null

        fun hold() { gate = CountDownLatch(1) }

        fun release() { gate?.countDown(); gate = null }

        override fun start(spec: ProcessSpec, listener: ProcessListener): RunningProcess {
            commands += spec.command
            val held = gate
            return inner.start(spec, object : ProcessListener {
                override fun onStdout(line: String) = listener.onStdout(line)
                override fun onStderr(line: String) = listener.onStderr(line)
                override fun onExit(exitCode: Int) {
                    held?.await(20, TimeUnit.SECONDS)
                    listener.onExit(exitCode)
                }
            })
        }
    }

    /** A second harness, `second`, on its own held transport. */
    private class Second(val runner: HeldRunner) : AgentProvider by StubProvider(delayMs = 0) {
        override val id = "second"
        override fun transport(): ProcessRunner = runner
    }

    private class Rig(
        val f: DaemonFixture,
        val stub: HeldRunner,
        val second: HeldRunner,
        /** The scripted children behind each harness: what each run was actually handed on stdin. */
        val stubScripted: ScriptedRunner,
        val secondScripted: ScriptedRunner,
    )

    private fun withDaemon(block: (Rig) -> Unit) {
        val stubScripted = ScriptedRunner(StubScript.default(), 0)
        val secondScripted = ScriptedRunner(StubScript.default(), 0)
        val stub = HeldRunner(stubScripted)
        val second = HeldRunner(secondScripted)
        val f = DaemonFixture.launch(runner = stub, additionalProviders = listOf(Second(second)))
        try {
            f.client.await(Envelope.READY)
            block(Rig(f, stub, second, stubScripted, secondScripted))
        } finally {
            stub.release()
            second.release()
            f.stop()
            f.deleteState()
        }
    }

    // --- helpers -------------------------------------------------------------------------------------------------

    private fun DaemonFixture.saveAgent(
        provider: String?,
        model: String? = null,
        effort: String? = null,
        instructions: String? = null,
    ): AgentProfile {
        val before = client.of(Envelope.AGENT).size
        val profile = AgentProfile("backend", "Backend", "backend", provider, model, instructions = instructions, effort = effort)
        send(Envelope(Envelope.AGENT_SAVE, json.encodeToString(AgentProfile.serializer(), profile)))
        return json.decodeFromString(AgentProfile.serializer(), client.await(Envelope.AGENT, before).data)
    }

    private fun DaemonFixture.sessionsOf(agentId: String = "backend"): List<SessionRecord> =
        daemon.manager.list().filter { it.agentId == agentId }

    /** BIND AT CREATION: a `new` naming the agent cuts its session at rest (idle, never run). */
    private fun DaemonFixture.bind(): SessionRecord {
        val known = sessionsOf().map { it.id }.toSet()
        send(Envelope(Envelope.NEW, """{"agentId":"backend"}"""))
        await(what = "a bound session for backend") { sessionsOf().any { it.id !in known } }
        return sessionsOf().single { it.id !in known }
    }

    private fun DaemonFixture.prompt(sessionId: String, text: String) =
        send(Envelope(Envelope.PROMPT, text, sessionId = sessionId))

    private fun DaemonFixture.awaitAtRest(sessionId: String, runs: Int) =
        await(what = "session $sessionId at rest after $runs run(s)") {
            daemon.manager.get(sessionId)!!.let { !it.live && it.runCount >= runs }
        }

    private fun DaemonFixture.awaitLive(sessionId: String) =
        await(what = "session $sessionId running") { daemon.manager.get(sessionId)!!.live }

    private fun DaemonFixture.swap(data: String, second: WsTestClient): AgentSwapped {
        val mine = client.of(Envelope.AGENT_SWAPPED).size
        val theirs = second.of(Envelope.AGENT_SWAPPED).size
        send(Envelope(Envelope.AGENT_SWAP, data))
        val pushed = client.await(Envelope.AGENT_SWAPPED, mine).data
        // The push reaches EVERY client, byte for byte, not just the requester.
        assertEquals(pushed, second.await(Envelope.AGENT_SWAPPED, theirs).data, "the second client gets the same push")
        return json.decodeFromString(AgentSwapped.serializer(), pushed)
    }

    private fun DaemonFixture.storedAgent(): AgentProfile {
        val before = client.of(Envelope.AGENTS).size
        send(Envelope(Envelope.AGENT_LIST))
        return json.decodeFromString(ListSerializer(AgentProfile.serializer()), client.await(Envelope.AGENTS, before).data).single { it.id == "backend" }
    }

    private fun List<String>.flag(name: String): String? = indexOf(name).takeIf { it >= 0 }?.let { getOrNull(it + 1) }

    // --- the 4.2 table -------------------------------------------------------------------------------------------

    @Test
    fun noSessionAnswersNoneAndTheNextSpawnRunsOnTheNewExecutor() = withDaemon { r ->
        val other = r.f.connectClient()
        r.f.saveAgent(provider = "stub")
        val swapped = r.f.swap("""{"agentId":"backend","provider":"second","model":"m-2","effort":"high","session":"auto"}""", other)

        assertEquals(SwapSession(SwapSession.NONE, null), swapped.session)
        assertEquals(SwapExecutor("stub", null, null), swapped.from)
        assertEquals(Triple("second", "m-2", "high"), Triple(swapped.agent.provider, swapped.agent.model, swapped.agent.effort))
        assertEquals(swapped.agent, r.f.storedAgent(), "the push carries the profile as stored")
        assertTrue(r.f.sessionsOf().isEmpty(), "none: no session was created")
        // The next spawn simply resolves to the new executor.
        val bound = r.f.bind()
        assertEquals(Triple("second", "m-2", "high"), Triple(bound.provider, bound.model, bound.effort))
    }

    @Test
    fun finishedSessionOnTheSameHarnessIsKeptAndItsNextRunUsesTheNewModelAndEffort() = withDaemon { r ->
        val other = r.f.connectClient()
        r.f.saveAgent(provider = "second", model = "m-1")
        val session = r.f.bind()
        r.f.prompt(session.id, "first turn")
        r.f.awaitAtRest(session.id, runs = 1)
        assertEquals("m-1", r.second.commands.last().flag("--model"))

        val swapped = r.f.swap("""{"agentId":"backend","provider":"second","model":"m-2","effort":"high"}""", other)
        assertEquals(SwapSession(SwapSession.KEPT, session.id), swapped.session)
        assertEquals(SwapExecutor("second", "m-1", null), swapped.from)
        assertEquals(listOf(session.id), r.f.sessionsOf().map { it.id }, "kept: no new session")

        r.f.prompt(session.id, "second turn")
        r.f.awaitAtRest(session.id, runs = 2)
        val next = r.second.commands.last()
        assertEquals("m-2", next.flag("--model"), "the kept session's NEXT run names the new model")
        assertEquals("high", next.flag("--effort"), "and the new effort")
        assertTrue(next.flag("--resume") != null, "same conversation: the run resumes the session's own provider session")
    }

    @Test
    fun idleSessionWithAHarnessChangeIsKeptAndMovedOntoTheNewHarness() = withDaemon { r ->
        // HARNESS-CARRY-1: the conversation is carried, so the agent keeps its ONE session (HarnessCarryDaemonTest).
        val other = r.f.connectClient()
        r.f.saveAgent(provider = "stub")
        val old = r.f.bind()

        val swapped = r.f.swap("""{"agentId":"backend","provider":"second","model":"m-2"}""", other)
        assertEquals(SwapSession(SwapSession.KEPT, old.id), swapped.session)
        val moved = r.f.daemon.manager.get(old.id)!!
        assertEquals(Triple("second", "m-2", "backend"), Triple(moved.provider, moved.model, moved.agentId))
        assertTrue(moved.neverRun, "at rest on the new harness, its next turn a first run there")
        assertEquals(listOf(old.id), r.f.sessionsOf().map { it.id }, "no second session")
    }

    @Test
    fun aRunningSessionOnTheSameHarnessFinishesOnTheOldModelAndIsKept() = withDaemon { r ->
        val other = r.f.connectClient()
        r.f.saveAgent(provider = "second", model = "m-1")
        val session = r.f.bind()
        r.second.hold()
        r.f.prompt(session.id, "long turn")
        r.f.awaitLive(session.id)

        val swapped = r.f.swap("""{"agentId":"backend","provider":"second","model":"m-2"}""", other)
        assertEquals(SwapSession(SwapSession.KEPT, session.id), swapped.session)
        assertTrue(r.f.daemon.manager.get(session.id)!!.live, "nothing interrupted: the run is still in flight")
        assertEquals("m-1", r.second.commands.single().flag("--model"), "the run in flight started on the old model")

        r.second.release()
        r.f.awaitAtRest(session.id, runs = 1)
        val settled = r.f.daemon.manager.get(session.id)!!
        assertNull(settled.killOutcome, "the run finished on its own")
        assertEquals("m-2", settled.model, "the swap survived the run's end")
        r.f.prompt(session.id, "next turn")
        r.f.awaitAtRest(session.id, runs = 2)
        assertEquals("m-2", r.second.commands.last().flag("--model"), "the next run uses the new model")
    }

    @Test
    fun aRunningSessionWithAHarnessChangeIsUntouchedAndTheOutcomeIsNone() = withDaemon { r ->
        val other = r.f.connectClient()
        r.f.saveAgent(provider = "stub")
        val session = r.f.bind()
        r.stub.hold()
        r.f.prompt(session.id, "long turn")
        r.f.awaitLive(session.id)

        val swapped = r.f.swap("""{"agentId":"backend","provider":"second"}""", other)
        assertEquals(SwapSession(SwapSession.NONE, null), swapped.session)
        assertEquals("second", r.f.storedAgent().provider, "the swap is stored now")
        val running = r.f.daemon.manager.get(session.id)!!
        assertTrue(running.live, "nothing interrupted")
        assertEquals("stub", running.provider)
        assertEquals(listOf(session.id), r.f.sessionsOf().map { it.id }, "no session created while the run is in flight")

        r.stub.release()
        r.f.awaitAtRest(session.id, runs = 1)
        assertNull(r.f.daemon.manager.get(session.id)!!.killOutcome, "the run finished on the old executor")
        // The new session comes lazily: the agent's next spawn resolves to the new harness.
        assertEquals("second", r.f.bind().provider)
    }

    /**
     * BUG-S3 (4.2, "the new session is created lazily on the next prompt"): an agent swapped across harnesses while
     * its run is in flight answers `none`, and mail to the agent queued meanwhile must NOT resume the old-harness
     * session once that run ends. It goes to a fresh session on the agent's current harness, opened like any first
     * turn (persona, identity line, then the message); the old session stays in history, untouched.
     */
    @Test
    fun mailAfterACrossHarnessSwapMidRunStartsAFreshSessionOnTheNewHarness() = withDaemon { r ->
        val other = r.f.connectClient()
        r.f.saveAgent(provider = "stub", instructions = "You are the backend persona.")
        val old = r.f.bind()
        r.stub.hold()
        r.f.prompt(old.id, "long turn")
        r.f.awaitLive(old.id)

        val swapped = r.f.swap("""{"agentId":"backend","provider":"second"}""", other)
        assertEquals(SwapSession(SwapSession.NONE, null), swapped.session)

        // Mail to the AGENT while its old-harness run is still in flight: it queues.
        val mailBefore = r.f.client.of(Envelope.MAIL).size
        r.f.send(
            Envelope(
                Envelope.MAIL_SEND,
                json.encodeToString(MailSendRequest.serializer(), MailSendRequest(from = "human", to = "backend", body = "after the swap")),
            ),
        )
        r.f.client.await(Envelope.MAIL, mailBefore)
        assertEquals(1, r.stubScripted.processes.size, "nothing new runs while the old run is in flight")

        r.stub.release()
        r.f.await(what = "a fresh backend session on the new harness, run once") {
            r.f.sessionsOf().any { it.id != old.id && it.provider == "second" && it.runCount >= 1 && !it.live }
        }
        val fresh = r.f.sessionsOf().single { it.id != old.id }
        assertEquals("second", fresh.provider, "the message runs on the agent's CURRENT harness")
        assertEquals(1, r.secondScripted.processes.size)
        val input = r.secondScripted.processes.single().deliveredInput
        assertTrue(
            // b7a55d4: the shipped KNOWLEDGE.md follows the persona, ahead of the identity.
            input.startsWith(SharedKnowledge.withKnowledge("You are the backend persona.") + "\n\nYou are running inside Infantry AI as role 'backend', agent id 'backend'."),
            "a fresh session's first turn: persona, then identity: ${input.take(300)}",
        )
        assertTrue(input.contains("after the swap"), "then the message: ${input.take(600)}")
        assertTrue(r.second.commands.single().flag("--resume") == null, "a fresh conversation, nothing resumed: ${r.second.commands.single()}")

        val settledOld = r.f.daemon.manager.get(old.id)!!
        assertEquals("stub", settledOld.provider, "the old session stays in history on its own harness")
        assertEquals(1, settledOld.runCount, "the old session is untouched: the mail never resumed it")
        assertEquals(1, r.stubScripted.processes.size, "no second run on the old harness")
    }

    @Test
    fun keepOnTheSameHarnessIsKeptAndNewForcesAFreshSession() = withDaemon { r ->
        val other = r.f.connectClient()
        r.f.saveAgent(provider = "second", model = "m-1")
        val session = r.f.bind()

        val kept = r.f.swap("""{"agentId":"backend","provider":"second","model":"m-2","session":"keep"}""", other)
        assertEquals(SwapSession(SwapSession.KEPT, session.id), kept.session)
        assertEquals("m-2", r.f.daemon.manager.get(session.id)!!.model)

        val forced = r.f.swap("""{"agentId":"backend","provider":"second","model":"m-3","session":"new"}""", other)
        assertEquals(SwapSession.NEW, forced.session.outcome)
        assertNotEquals(session.id, forced.session.sessionId)
        assertEquals("m-3", r.f.daemon.manager.get(forced.session.sessionId!!)!!.model)
        assertEquals("m-2", r.f.daemon.manager.get(session.id)!!.model, "the old session is left as it was")
    }

    // --- refusals: an `error`, NOTHING written --------------------------------------------------------------------

    @Test
    fun everyRefusalIsAnErrorAndWritesNothing() = withDaemon { r ->
        val other = r.f.connectClient()
        r.f.saveAgent(provider = "stub", model = "m-1", effort = "low")
        val session = r.f.bind()
        val agentBefore = r.f.storedAgent()
        fun sessionsNow() = r.f.daemon.manager.list().map { listOf(it.id, it.provider, it.model, it.effort, it.state.name, it.agentId) }
        val sessionsBefore = sessionsNow()
        val errorsBefore = r.f.client.of(Envelope.ERROR).size

        listOf(
            """{"agentId":"nobody","provider":"second"}""" to "no agent profile 'nobody'",
            """{"agentId":"backend","provider":"nope"}""" to "provider 'nope' is not configured",
            """{"agentId":"backend","provider":"second","effort":"turbo"}""" to "unknown effort 'turbo'",
            """{"agentId":"backend","provider":"second","session":"keep"}""" to "session 'keep' cannot cross harnesses",
            """{"agentId":"backend","provider":"stub","session":"later"}""" to "session 'later' is not one of",
            """{"agentId":"backend","provider":" "}""" to "provider is required",
            """{"agentId":"backend","provider":"second","modle":"m-2"}""" to "",
        ).forEachIndexed { i, (bad, reason) ->
            r.f.send(Envelope(Envelope.AGENT_SWAP, bad))
            val error = r.f.client.await(Envelope.ERROR, errorsBefore + i).data
            assertTrue(error.startsWith("agent_swap refused: "), "$bad -> $error")
            assertTrue(error.contains(reason), "$bad -> $error (expected '$reason')")
        }
        assertEquals(agentBefore, r.f.storedAgent(), "the profile is untouched")
        assertEquals(sessionsBefore, sessionsNow(), "every session is untouched, none created")
        assertEquals(session.id, r.f.sessionsOf().single().id)
        assertTrue(r.f.client.of(Envelope.AGENT_SWAPPED).isEmpty(), "no push to the requester")
        assertTrue(other.of(Envelope.AGENT_SWAPPED).isEmpty(), "no push to anyone else")
        assertTrue(other.of(Envelope.ERROR).isEmpty(), "the refusal answers the requester only")
    }
}
