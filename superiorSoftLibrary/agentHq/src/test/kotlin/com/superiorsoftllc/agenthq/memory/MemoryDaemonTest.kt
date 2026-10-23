package com.superiorsoftllc.agenthq.memory

import com.superiorsoftllc.agenthq.daemon.DaemonFixture
import com.superiorsoftllc.agenthq.protocol.Envelope
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * MEM-5 (`memory_search`) and MEM-8 (`memory_save` / `memory_list`) over the real daemon socket, like
 * [com.superiorsoftllc.agenthq.mvp.WorkspaceDaemonTest].
 */
class MemoryDaemonTest {
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun memorySearchFrameReturnsHits() {
        val fixture = DaemonFixture.launch()
        try {
            fixture.client.await(Envelope.READY)

            // Two notes in project P; only one body mentions "rotation".
            fixture.daemon.memoryNotes.save(
                projectId = "P",
                name = "key-rotation",
                description = "how keys are rolled",
                body = "the rotation procedure is run every ninety days",
            )
            fixture.daemon.memoryNotes.save(
                projectId = "P",
                name = "cart-notes",
                description = "nothing about keys here",
                body = "a note about carts and quantities",
            )

            fixture.send(Envelope.MEMORY_SEARCH, """{"projectId":"P","query":"rotation"}""")
            val hits = json.parseToJsonElement(
                fixture.client.await(Envelope.MEMORY_HITS, timeoutMs = 4_000).data,
            ).jsonArray

            assertEquals(1, hits.size, "exactly the rotation note must match")
            val hit = hits.single().jsonObject
            assertEquals("key-rotation", hit["name"]!!.jsonPrimitive.content)
            assertTrue(
                hit["snippet"]!!.jsonPrimitive.content.contains("rotation"),
                "the snippet must contain the matched term, got '${hit["snippet"]!!.jsonPrimitive.content}'",
            )
        } finally {
            fixture.stop()
            fixture.deleteState()
        }
    }

    @Test
    fun memorySaveFrameIsListedByMemoryList() {
        val fixture = DaemonFixture.launch()
        try {
            fixture.client.await(Envelope.READY)

            // memory_save answers with the whole stored note.
            fixture.send(
                Envelope.MEMORY_SAVE,
                """{"projectId":"P","name":"key-rotation","description":"how keys are rolled","body":"run the rotation every ninety days"}""",
            )
            val saved = json.parseToJsonElement(
                fixture.client.await(Envelope.MEMORY_NOTE, timeoutMs = 4_000).data,
            ).jsonObject
            assertEquals("key-rotation", saved["name"]!!.jsonPrimitive.content)
            assertEquals("how keys are rolled", saved["description"]!!.jsonPrimitive.content)

            // A second note, saved out of alphabetical order.
            fixture.send(
                Envelope.MEMORY_SAVE,
                """{"projectId":"P","name":"cart-notes","description":"carts and quantities","body":"a note about carts"}""",
            )
            fixture.client.await(Envelope.MEMORY_NOTE, occurrence = 1, timeoutMs = 4_000)

            // memory_list answers the project's notes as {name, description}, in name order.
            fixture.send(Envelope.MEMORY_LIST, "P")
            val listed = json.parseToJsonElement(
                fixture.client.await(Envelope.MEMORY_NOTES, timeoutMs = 4_000).data,
            ).jsonArray
            assertEquals(
                listOf("cart-notes", "key-rotation"),
                listed.map { it.jsonObject["name"]!!.jsonPrimitive.content },
                "memory_list must answer in name order",
            )
            val first = listed.first().jsonObject
            assertEquals("carts and quantities", first["description"]!!.jsonPrimitive.content)
            assertEquals(
                // P10-K2 (1.51.0) adds `folder`; the body is still withheld.
                setOf("name", "description", "folder"),
                first.keys,
                "a list entry carries only name, description and folder, got ${first.keys}",
            )
        } finally {
            fixture.stop()
            fixture.deleteState()
        }
    }
}
