package com.superiorsoftllc.agenthq.daemon

import com.superiorsoftllc.agenthq.protocol.Envelope
import com.superiorsoftllc.agenthq.harness.AgentProvider
import com.superiorsoftllc.agenthq.provider.StubProvider
import com.superiorsoftllc.agenthq.runner.ProcessRunner
import com.superiorsoftllc.agenthq.runner.ScriptedRunner
import com.superiorsoftllc.agenthq.runner.StubScript
import com.superiorsoftllc.agenthq.session.SessionRecord
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * ASSISTANT-1 (contract 1.115.0) over the real daemon: the `assistant` frame creates the ONE global Assistant (role
 * `assistant`, no project, its own directory under the state dir), every later request returns the same session, its
 * model changes through `session_swap` and persists, its role cannot change, and its first turn carries the real CLI help.
 */
class AssistantDaemonTest {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private class Second(val runner: ScriptedRunner) : AgentProvider by StubProvider(delayMs = 0) {
        override val id = "second"
        override fun transport(): ProcessRunner = runner
    }

    private fun withDaemon(block: (DaemonFixture, ScriptedRunner) -> Unit) {
        val stub = ScriptedRunner(StubScript.default(), 0)
        val second = ScriptedRunner(StubScript.default(), 0)
        val f = DaemonFixture.launch(runner = stub, additionalProviders = listOf(Second(second)))
        try {
            f.client.await(Envelope.READY)
            block(f, stub)
        } finally {
            f.stop()
            f.deleteState()
        }
    }

    private fun DaemonFixture.ask(): SessionRecord {
        val seen = client.frames.size
        send(Envelope(Envelope.ASSISTANT, ""))
        val deadline = System.currentTimeMillis() + 20_000
        while (System.currentTimeMillis() < deadline) {
            client.frames.drop(seen).firstOrNull { it.type == Envelope.SESSION && it.data.contains("\"global\":true") }
                ?.let { return json.decodeFromString(SessionRecord.serializer(), it.data) }
            Thread.sleep(20)
        }
        error("no assistant session frame after the assistant request")
    }

    @Test
    fun theAssistantIsOneGlobalProjectlessSessionInItsOwnDirectory() = withDaemon { f, _ ->
        val first = f.ask()
        assertEquals("assistant", first.role)
        assertNull(first.projectId, "the assistant belongs to no project")
        assertTrue(first.global)
        assertTrue(first.workingDir.replace(java.io.File.separatorChar, '/').endsWith("/assistant"), "its own directory under the data dir: ${first.workingDir}")
        assertTrue(first.workingDir.startsWith(f.stateDir.absolutePath), "inside the state dir, never the user's home: ${first.workingDir}")
        val again = f.ask()
        assertEquals(first.id, again.id, "one assistant per installation")
        assertEquals(1, f.daemon.manager.list().count { it.global })
    }

    @Test
    fun theModelChangesAndPersistsButTheRoleIsLocked() = withDaemon { f, _ ->
        val id = f.ask().id
        f.send(Envelope(Envelope.SESSION_SWAP, """{"sessionId":"$id","provider":"second"}"""))
        f.client.await(Envelope.SESSION_SWAPPED)
        assertEquals("second", f.daemon.manager.get(id)!!.provider, "the provider swap is kept on the session")
        assertEquals("assistant", f.daemon.manager.get(id)!!.role)

        val errors = f.client.of(Envelope.ERROR).size
        f.send(Envelope(Envelope.SESSION_SWAP, """{"sessionId":"$id","provider":"second","role":"backend"}"""))
        val refused = f.client.await(Envelope.ERROR, errors)
        assertTrue(refused.data.contains("fixed"), refused.data)
        assertEquals("assistant", f.daemon.manager.get(id)!!.role)
    }

    @Test
    fun theFirstTurnCarriesTheRealCliHelpAndNoToolRestriction() = withDaemon { f, stub ->
        val id = f.ask().id
        f.send(Envelope(Envelope.PROMPT, "how do I start a ticket?", sessionId = id))
        f.await(what = "the assistant's first run") { f.daemon.manager.get(id)!!.let { !it.live && it.runCount >= 1 } }
        val input = stub.processes.single().deliveredInput
        assertTrue(input.contains("Infantry AI CLI"), "the standing instructions embed the real --help: ${input.take(600)}")
        assertTrue(input.contains("agenthq ticket start"), "it names how a ticket is started")
        assertTrue(input.trimEnd().endsWith("how do I start a ticket?"), "the user's message comes last")
        assertTrue(input.contains("no tool restrictions"), "it asks for no reduced tool set")
    }
}
