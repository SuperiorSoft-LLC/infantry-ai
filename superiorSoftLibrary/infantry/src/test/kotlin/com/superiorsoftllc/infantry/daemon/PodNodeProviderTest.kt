// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.daemon

import com.superiorsoftllc.infantry.orchestration.NodeSpec
import com.superiorsoftllc.infantry.orchestration.PodStatus
import com.superiorsoftllc.infantry.orchestration.PodSubmitRequest
import com.superiorsoftllc.infantry.protocol.Envelope
import com.superiorsoftllc.infantry.harness.AgentProvider
import com.superiorsoftllc.infantry.provider.StubProvider
import com.superiorsoftllc.infantry.runner.StubScript
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * BUG-PODMODEL over the real daemon: a pod node naming a provider and a model runs its attempt on THAT provider with
 * THAT model; a node naming neither falls back to the daemon's default provider and the provider's default model.
 * Before the fix, `Orchestrator.startAttempt` never read [NodeSpec.provider] / [NodeSpec.model], so every node ran on
 * the default. One code path for every harness: the node's choice goes straight to `SessionManager.startNew`.
 */
class PodNodeProviderTest {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    /** A second scripted provider: the stub's machinery under another id and default model. */
    private class SecondStub : AgentProvider by StubProvider(script = StubScript.default(), delayMs = 0) {
        override val id = "second"
        override fun defaultModel() = "second-default"
    }

    @Test
    fun aPodNodeNamingAProviderAndModelRunsOnThem() = withDaemon { fixture ->
        val pod = submit(fixture, PodSubmitRequest(goal = "g", nodes = listOf(NodeSpec("main", "do it", provider = "second", model = "m-1"))))
        fixture.await(what = "the node's run recorded") { fixture.daemon.store.runs.byPod(pod.pod.id).isNotEmpty() }

        val session = fixture.daemon.manager.list().single()
        assertEquals("second", session.provider, "the node's provider, not the daemon default")
        assertEquals("m-1", session.model, "the node's model")
        assertEquals("second", fixture.daemon.store.runs.byPod(pod.pod.id).single().provider, "the run row names it too")
    }

    @Test
    fun aPodNodeNamingNeitherFallsBackToTheDaemonDefault() = withDaemon { fixture ->
        val pod = submit(fixture, PodSubmitRequest(goal = "g", nodes = listOf(NodeSpec("main", "do it"))))
        fixture.await(what = "the node's run recorded") { fixture.daemon.store.runs.byPod(pod.pod.id).isNotEmpty() }

        val session = fixture.daemon.manager.list().single()
        assertEquals("stub", session.provider)
        assertNull(session.model)
        assertEquals("stub", fixture.daemon.store.runs.byPod(pod.pod.id).single().provider)
    }

    /** BUG-PODMODEL-2: a node naming an agent runs its attempt as that agent; a node naming none runs as no agent. */
    @Test
    fun aPodNodeNamingAnAgentRunsAsThatAgent() = withDaemon { fixture ->
        val pod = submit(fixture, PodSubmitRequest(goal = "g", nodes = listOf(NodeSpec("main", "do it", agentId = "agent-7"))))
        fixture.await(what = "the node's run recorded") { fixture.daemon.store.runs.byPod(pod.pod.id).isNotEmpty() }

        val session = fixture.daemon.manager.list().single()
        assertEquals("agent-7", session.agentId, "the node's agent, not an anonymous session")
    }

    @Test
    fun aPodNodeNamingNoAgentRunsAsNoAgent() = withDaemon { fixture ->
        val pod = submit(fixture, PodSubmitRequest(goal = "g", nodes = listOf(NodeSpec("main", "do it"))))
        fixture.await(what = "the node's run recorded") { fixture.daemon.store.runs.byPod(pod.pod.id).isNotEmpty() }

        assertNull(fixture.daemon.manager.list().single().agentId)
    }

    private fun submit(fixture: DaemonFixture, request: PodSubmitRequest): PodStatus {
        fixture.send(Envelope.POD_SUBMIT, json.encodeToString(PodSubmitRequest.serializer(), request))
        return json.decodeFromString(PodStatus.serializer(), fixture.client.await(Envelope.POD).data)
    }

    private fun withDaemon(block: (DaemonFixture) -> Unit) {
        val fixture = DaemonFixture.launch(additionalProviders = listOf(SecondStub()))
        try {
            fixture.client.await(Envelope.READY)
            block(fixture)
        } finally {
            fixture.stop()
            fixture.deleteState()
        }
    }
}
