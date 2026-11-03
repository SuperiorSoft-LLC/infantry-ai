// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.daemon

import com.superiorsoftllc.infantry.protocol.Envelope
import com.superiorsoftllc.infantry.protocol.SkillView
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** P10-B over the real daemon and a real WebSocket: save, list with a tag filter, get, delete, and refusals. */
class SkillWireTest {
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

    private fun list(data: String) = json.decodeFromString(ListSerializer(SkillView.serializer()), data)

    @Test
    fun saveListGetDeleteRoundTrip() = withDaemon { f ->
        f.send(Envelope(Envelope.SKILL_SAVE, """{"name":"Review","description":"Use when reviewing","body":"look hard","tags":["Code","qa"]}"""))
        val saved = json.decodeFromString(SkillView.serializer(), f.client.await(Envelope.SKILL_ITEM).data)
        assertEquals("Review", saved.name)
        assertEquals("Use when reviewing", saved.description)
        assertEquals(listOf("code", "qa"), saved.tags)

        f.send(Envelope(Envelope.SKILL_SAVE, """{"name":"Review","body":"v2"}"""))
        val updated = json.decodeFromString(SkillView.serializer(), f.client.await(Envelope.SKILL_ITEM, 1).data)
        assertEquals(saved.id, updated.id)
        assertEquals("v2", updated.body)
        assertEquals("", updated.description)

        f.send(Envelope(Envelope.SKILL_SAVE, """{"name":"Other","body":"o","tags":["qa"]}"""))
        f.client.await(Envelope.SKILL_ITEM, 2)

        f.send(Envelope(Envelope.SKILL_LIST, ""))
        assertEquals(listOf("Other", "Review"), list(f.client.await(Envelope.SKILLS, 0).data).map { it.name })
        f.send(Envelope(Envelope.SKILL_LIST, "qa"))
        assertEquals(listOf("Other"), list(f.client.await(Envelope.SKILLS, 1).data).map { it.name })

        f.send(Envelope(Envelope.SKILL_GET, saved.id))
        assertEquals("v2", json.decodeFromString(SkillView.serializer(), f.client.await(Envelope.SKILL_ITEM, 3).data).body)

        f.send(Envelope(Envelope.SKILL_DELETE, saved.id))
        assertEquals(listOf("Other"), list(f.client.await(Envelope.SKILLS, 2).data).map { it.name })
    }

    @Test
    fun refusalsAreErrorsAndNothingIsWritten() = withDaemon { f ->
        listOf(
            """{"name":"","body":"b"}""",
            """{"name":"${"n".repeat(121)}","body":"b"}""",
            """{"name":"a","description":"${"d".repeat(501)}","body":"b"}""",
            """{"name":"a","body":"${"b".repeat(64 * 1024 + 1)}"}""",
            """{"name":"a","body":"b","tgs":[]}""",
        ).forEachIndexed { i, bad ->
            f.send(Envelope(Envelope.SKILL_SAVE, bad))
            assertTrue(f.client.await(Envelope.ERROR, i).data.startsWith("skill_save refused"), bad.take(40))
        }
        f.send(Envelope(Envelope.SKILL_GET, "nope"))
        assertTrue(f.client.await(Envelope.ERROR, 5).data.startsWith("skill_get refused"))
        f.send(Envelope(Envelope.SKILL_DELETE, "nope"))
        assertTrue(f.client.await(Envelope.ERROR, 6).data.startsWith("skill_delete refused"))
        f.send(Envelope(Envelope.SKILL_LIST, ""))
        assertEquals("[]", f.client.await(Envelope.SKILLS).data)
    }
}
