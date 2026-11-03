// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.daemon

import com.superiorsoftllc.infantry.protocol.AgentProfile
import com.superiorsoftllc.infantry.protocol.Envelope
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * TRAITS-T3 (contract 1.50.0) over the real daemon and a real WebSocket: `agent_save` with `traitIds` stores the
 * additions, a save WITHOUT the field (a pre-1.50.0 client) keeps them, `[]` clears them, the `agent` frame always carries
 * the list, an unknown trait is refused, and `trait_delete` is refused while an agent binds the trait.
 */
class AgentTraitsWireTest {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private fun withDaemon(block: (DaemonFixture) -> Unit) {
        val fixture = DaemonFixture.launch()
        try {
            fixture.client.await(Envelope.READY)
            block(fixture)
        } finally {
            fixture.stop()
            fixture.deleteState()
        }
    }

    private fun agent(data: String) = json.decodeFromString(AgentProfile.serializer(), data)

    @Test
    fun saveKeepsClearsAndAlwaysCarriesTheList() = withDaemon { f ->
        f.send(Envelope(Envelope.TRAIT_SAVE, """{"id":"report-back","name":"RB","body":"mail the pm"}"""))
        f.client.await(Envelope.TRAIT_ITEM)

        f.send(Envelope(Envelope.AGENT_SAVE, """{"id":"a-traits","name":"B","role":"backend"}"""))
        val created = f.client.await(Envelope.AGENT)
        assertEquals("[]", Json.parseToJsonElement(created.data).jsonObject["traitIds"].toString(), "the frame carries [] when none")

        f.send(Envelope(Envelope.AGENT_SAVE, """{"id":"a-traits","name":"B","role":"backend","traitIds":["report-back"]}"""))
        assertEquals(listOf("report-back"), agent(f.client.await(Envelope.AGENT, 1).data).traitIds)

        // A pre-1.50.0 client re-saves the whole profile without the field: the additions survive.
        f.send(Envelope(Envelope.AGENT_SAVE, """{"id":"a-traits","name":"Renamed","role":"backend"}"""))
        val kept = agent(f.client.await(Envelope.AGENT, 2).data)
        assertEquals("Renamed", kept.name)
        assertEquals(listOf("report-back"), kept.traitIds)

        f.send(Envelope(Envelope.AGENT_SAVE, """{"id":"a-traits","name":"Renamed","role":"backend","traitIds":[]}"""))
        assertEquals(emptyList(), agent(f.client.await(Envelope.AGENT, 3).data).traitIds)
    }

    @Test
    fun unknownTraitIsRefused() = withDaemon { f ->
        f.send(Envelope(Envelope.AGENT_SAVE, """{"id":"a-traits","name":"B","role":"backend","traitIds":["nope"]}"""))
        val error = f.client.await(Envelope.ERROR).data
        assertTrue(error.startsWith("agent_save refused") && "unknown trait 'nope'" in error, error)
    }

    @Test
    fun traitDeleteIsRefusedWhileAnAgentBindsIt() = withDaemon { f ->
        f.send(Envelope(Envelope.TRAIT_SAVE, """{"id":"report-back","name":"RB","body":"mail the pm"}"""))
        f.client.await(Envelope.TRAIT_ITEM)
        f.send(Envelope(Envelope.AGENT_SAVE, """{"id":"a-traits","name":"B","role":"backend","traitIds":["report-back"]}"""))
        f.client.await(Envelope.AGENT)

        f.send(Envelope(Envelope.TRAIT_DELETE, "report-back"))
        val refused = f.client.await(Envelope.ERROR).data
        assertTrue(refused.startsWith("trait_delete refused") && "agent 'a-traits'" in refused, refused)

        f.send(Envelope(Envelope.AGENT_SAVE, """{"id":"a-traits","name":"B","role":"backend","traitIds":[]}"""))
        f.client.await(Envelope.AGENT, 1)
        f.send(Envelope(Envelope.TRAIT_DELETE, "report-back"))
        assertEquals("[]", f.client.await(Envelope.TRAITS).data, "unbound, the delete goes through")
    }
}
