// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.daemon

import com.superiorsoftllc.infantry.agents.SharedKnowledge
import com.superiorsoftllc.infantry.protocol.AgentProfile
import com.superiorsoftllc.infantry.protocol.Envelope
import com.superiorsoftllc.infantry.harness.AgentProvider
import com.superiorsoftllc.infantry.harness.RunRequest
import com.superiorsoftllc.infantry.provider.StubProvider
import com.superiorsoftllc.infantry.runner.ProcessSpec
import com.superiorsoftllc.infantry.session.SessionState
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Contract 1.4.0 (story 9.1), over the REAL daemon and a real WebSocket: agent profiles are saved,
 * listed and deleted with `agent_save` / `agent_list` / `agent_delete`, and a `new` frame naming a
 * profile (`{"agentId": ...}`) spawns sessions that run on the profile's provider and model, carry its
 * persona ahead of their first prompt, and say whose they are on every `session` frame.
 *
 * The second provider is the stub's whole machinery under another id, recording each request it is
 * asked to run, so the test can see exactly what prompt reached the provider.
 */
class AgentProfileDaemonTest {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    /** The stub under the id `second`, recording every request it builds a spec for. */
    private class RecordingStub(private val inner: StubProvider = StubProvider(delayMs = 0)) : AgentProvider by inner {
        val requests = CopyOnWriteArrayList<RunRequest>()
        override val id = "second"
        override fun spec(request: RunRequest): ProcessSpec {
            requests += request
            return inner.spec(request)
        }
    }

    private fun withDaemon(block: (DaemonFixture, RecordingStub) -> Unit) {
        val second = RecordingStub()
        val fixture = DaemonFixture.launch(additionalProviders = listOf(second))
        try {
            fixture.client.await(Envelope.READY)
            block(fixture, second)
        } finally {
            fixture.stop()
            fixture.deleteState()
        }
    }

    private fun decodeList(frame: Envelope): List<AgentProfile> =
        json.decodeFromString(ListSerializer(AgentProfile.serializer()), frame.data)

    private val backendJson =
        """{"id":"backend","name":"Backend","role":"backend","provider":"second","model":"m-2",""" +
            """"instructions":"You are the backend agent.","projectId":null,"effort":"high","traitIds":[],"skillIds":[],"runner":null}"""

    // --- managing profiles ---------------------------------------------------------------------------

    @Test
    fun profilesAreSavedListedAndDeletedOverTheWire() = withDaemon { fixture, _ ->
        fixture.send(Envelope(Envelope.AGENT_SAVE, backendJson))
        // The literal bytes a UI parses: the stored profile, every field present.
        assertEquals(backendJson, fixture.client.await(Envelope.AGENT).data)

        fixture.send(Envelope(Envelope.AGENT_SAVE, """{"name":" QA ","role":"QA"}"""))
        val qa = fixture.client.await(Envelope.AGENT, occurrence = 1)
        val qaId = json.decodeFromString(AgentProfile.serializer(), qa.data).id!!
        assertTrue(qaId.startsWith("agent-"), "an id the daemon chose: $qaId")
        assertEquals(
            """{"id":"$qaId","name":"QA","role":"qa","provider":null,"model":null,"instructions":null,"projectId":null,"effort":null,"traitIds":[],"skillIds":[],"runner":null}""",
            qa.data,
            "normalised as stored, and nulls written out rather than left for a client to guess",
        )

        fixture.send(Envelope(Envelope.AGENT_LIST))
        val listed = decodeList(fixture.client.await(Envelope.AGENTS))
        assertEquals(setOf("backend", qaId), listed.map { it.id }.toSet())

        // A delete answers with the list as it now stands.
        fixture.send(Envelope(Envelope.AGENT_DELETE, "backend"))
        assertEquals(listOf(qaId), decodeList(fixture.client.await(Envelope.AGENTS, occurrence = 1)).map { it.id })

        fixture.send(Envelope(Envelope.AGENT_DELETE, "backend"))
        val refusal = fixture.client.await(Envelope.ERROR)
        assertTrue(refusal.data.startsWith("agent_delete refused: no agent profile 'backend'"), refusal.data)
        assertEquals(listOf(qaId), fixture.daemon.agents.list().map { it.id })
    }

    @Test
    fun aRefusedSaveIsAnErrorAndStoresNothing() = withDaemon { fixture, _ ->
        fixture.send(Envelope(Envelope.AGENT_SAVE, """{"name":"QA","role":"Quality Assurance"}"""))
        val badRole = fixture.client.await(Envelope.ERROR)
        assertTrue(badRole.data.startsWith("agent_save refused: role"), badRole.data)

        fixture.send(Envelope(Envelope.AGENT_SAVE, """{"name":"QA","role":"qa","colour":"red"}"""))
        val unknownKey = fixture.client.await(Envelope.ERROR, occurrence = 1)
        assertTrue(unknownKey.data.startsWith("agent_save refused: malformed agent_save payload"), unknownKey.data)
        assertTrue(unknownKey.data.contains("colour"), "the refusal names the key it did not know: ${unknownKey.data}")

        fixture.send(Envelope(Envelope.AGENT_SAVE, """{"role":"qa"}"""))
        val missingName = fixture.client.await(Envelope.ERROR, occurrence = 2)
        assertTrue(missingName.data.startsWith("agent_save refused: malformed agent_save payload"), missingName.data)

        fixture.send(Envelope(Envelope.AGENT_SAVE, """{"name":"QA","role":"qa","provider":"gemini"}"""))
        val unknownProvider = fixture.client.await(Envelope.ERROR, occurrence = 3)
        assertTrue(unknownProvider.data.startsWith("agent_save refused:"), unknownProvider.data)
        assertTrue(unknownProvider.data.contains("gemini"), unknownProvider.data)

        fixture.send(Envelope(Envelope.AGENT_SAVE, """{"name":"QA","role":"qa","effort":"turbo"}"""))
        val unknownEffort = fixture.client.await(Envelope.ERROR, occurrence = 4)
        assertEquals("agent_save refused: unknown effort 'turbo': one of [low, medium, high, xhigh, max]", unknownEffort.data)

        fixture.send(Envelope(Envelope.AGENT_LIST))
        assertEquals("[]", fixture.client.await(Envelope.AGENTS).data)
        assertTrue(fixture.client.of(Envelope.AGENT).isEmpty(), "no `agent` frame for a refused save")
    }

    // --- spawning from a profile -------------------------------------------------------------------

    @Test
    fun aSessionSpawnedFromAProfileRunsAsThatProfile() = withDaemon { fixture, second ->
        fixture.send(Envelope(Envelope.AGENT_SAVE, backendJson))
        fixture.client.await(Envelope.AGENT)

        fixture.send(Envelope(Envelope.NEW, """{"agentId":"backend"}"""))
        fixture.send(Envelope(Envelope.PROMPT, "add an endpoint"))
        fixture.client.await(Envelope.FINISHED)

        val session = fixture.daemon.manager.list().single()
        assertEquals("second", session.provider)
        assertEquals("m-2", session.model)
        assertEquals("backend", session.agentId)
        assertEquals("backend", session.role)
        assertEquals(
            SharedKnowledge.withKnowledge("You are the backend agent.")!! + "\n\n" + // b7a55d4: KNOWLEDGE.md follows the persona
                "You are running inside Infantry AI as role 'backend', agent id 'backend'. " +
                "Your environment carries the same identity (INFANTRY_ROLE, INFANTRY_AGENT_ID, INFANTRY_SESSION_ID).\n\n" +
                "add an endpoint",
            second.requests.single().prompt,
            "the persona goes ahead of the first prompt, then who the agent runs as (BUG-808ce596)",
        )
        // The `session` frame a UI renders says whose session this is.
        fixture.client.awaitWhere("a session frame naming the profile and its role") {
            it.type == Envelope.SESSION &&
                it.data.contains("\"agentId\":\"backend\"") && it.data.contains("\"role\":\"backend\"")
        }

        // A follow-up continues the same session with the client's text only: the persona was turn one's.
        fixture.await(what = "the first turn came to rest") {
            fixture.daemon.manager.get(session.id)?.state == SessionState.IDLE
        }
        fixture.send(Envelope(Envelope.PROMPT, "now add a test"))
        fixture.client.await(Envelope.FINISHED, occurrence = 1)

        assertEquals(1, fixture.daemon.manager.list().size, "the follow-up resumed; it did not spawn")
        assertEquals(2, second.requests.size)
        assertEquals("now add a test", second.requests.last().prompt)
        assertEquals("stub-session-1", second.requests.last().resumeSessionId)
        assertTrue(fixture.client.of(Envelope.ERROR).isEmpty(), "errors: ${fixture.client.of(Envelope.ERROR)}")
    }

    @Test
    fun anUnknownProfileIsRefusedAtNewAndTheNextPromptStartsNothing() = withDaemon { fixture, second ->
        fixture.send(Envelope(Envelope.NEW, """{"agentId":"nobody"}"""))
        val refusal = fixture.client.await(Envelope.ERROR)
        assertTrue(refusal.data.startsWith("new refused: no agent profile 'nobody'"), refusal.data)

        fixture.send(Envelope(Envelope.PROMPT, "hello"))
        val blocked = fixture.client.await(Envelope.ERROR, occurrence = 1)
        assertTrue(blocked.data.startsWith("no session started"), blocked.data)
        assertTrue(fixture.daemon.manager.list().isEmpty(), "no session on the default provider either")
        assertTrue(second.requests.isEmpty())
    }

    @Test
    fun theProfileIsReadWhenNewArrivesBecauseTheSessionIsBoundThen() = withDaemon { fixture, second ->
        fixture.send(Envelope(Envelope.AGENT_SAVE, """{"id":"qa","name":"QA","role":"qa","provider":"second"}"""))
        fixture.client.await(Envelope.AGENT)
        fixture.send(Envelope(Envelope.NEW, """{"agentId":"qa","projectId":"p1"}"""))

        // BIND AT CREATION: the session exists before any prompt, bound to profile, role and project.
        val deadline = System.currentTimeMillis() + 5_000 // `new` has no ack frame; wait for the bind
        while (fixture.daemon.manager.list().isEmpty() && System.currentTimeMillis() < deadline) Thread.sleep(20)
        val bound = fixture.daemon.manager.list().single()
        assertEquals("qa", bound.agentId)
        assertEquals("qa", bound.role)
        assertEquals("p1", bound.projectId)
        assertEquals("second", bound.provider)
        assertTrue(second.requests.isEmpty(), "binding starts no run")

        fixture.send(Envelope(Envelope.PROMPT, "check the build"))
        fixture.client.await(Envelope.FINISHED)

        assertEquals(1, fixture.daemon.manager.list().size, "the first prompt ran the bound session; it did not spawn another")
        assertEquals(
            // ROLE-EDIT-1: a profile with no instructions of its own falls back to its role's persona (the bundled qa.md).
            // b7a55d4: the shipped KNOWLEDGE.md follows the persona.
            SharedKnowledge.withKnowledge(com.superiorsoftllc.infantry.plugins.official.roles.daemon.RoleCatalog.load().persona("qa"))!! + "\n\n" +
            "You are running inside Infantry AI as role 'qa', agent id 'qa'. " +
                "Your environment carries the same identity (INFANTRY_ROLE, INFANTRY_AGENT_ID, INFANTRY_SESSION_ID).\n\n" +
                // P10-K9: the spawn names project p1, whose knowledge base is empty, so it gets the empty form.
                "PROJECT KNOWLEDGE. Project p1 has an empty knowledge base in the Infantry AI daemon. Record what a later " +
                "session must not re-derive: `infantry decision add` for a choice, `infantry issue open|attempt|fix` for a " +
                "bug, `infantry memory save` for anything else.\n\n" +
                // ROLE-NOTES-1: a role session always learns the scope how-to, even with no role notes yet.
                "ROLE NOTES: none yet for the 'qa' role. Use `infantry memory index|show|search|save --scope role|session`; " +
                "the default scope is the shared project notes. A role note added later is announced to you in a mail.\n\n" +
                "check the build",
            second.requests.single().prompt,
            "a profile with no persona gets its role's persona, and the agent still learns who it runs as (BUG-808ce596)",
        )
    }

    @Test
    fun aProfileDeletedAfterNewIsRefusedAtThePromptAndNoRunStarts() = withDaemon { fixture, second ->
        fixture.send(Envelope(Envelope.AGENT_SAVE, """{"id":"qa","name":"QA","role":"qa"}"""))
        fixture.client.await(Envelope.AGENT)
        fixture.send(Envelope(Envelope.NEW, """{"agentId":"qa"}"""))
        val bindDeadline = System.currentTimeMillis() + 5_000
        while (fixture.daemon.manager.list().isEmpty() && System.currentTimeMillis() < bindDeadline) Thread.sleep(20)
        fixture.send(Envelope(Envelope.AGENT_DELETE, "qa"))
        fixture.client.await(Envelope.AGENTS)

        fixture.send(Envelope(Envelope.PROMPT, "hello"))
        val refusal = fixture.client.await(Envelope.ERROR)
        assertTrue(refusal.data.startsWith("no agent profile 'qa'"), refusal.data)
        assertTrue(second.requests.isEmpty(), "no run without the profile it asked for")
        assertEquals(0, fixture.daemon.manager.list().single().runCount)
    }
}
