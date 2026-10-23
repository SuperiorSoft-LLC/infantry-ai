package com.superiorsoftllc.agenthq.daemon

import com.superiorsoftllc.agenthq.protocol.Envelope
import com.superiorsoftllc.agenthq.protocol.PromptView
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** P10-A over the real daemon and a real WebSocket: save, list with a tag filter, get, delete, and refusals. */
class PromptWireTest {
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

    private fun list(data: String) = json.decodeFromString(ListSerializer(PromptView.serializer()), data)

    @Test
    fun saveListGetDeleteRoundTrip() = withDaemon { f ->
        f.send(Envelope(Envelope.PROMPT_SAVE, """{"name":"Review","body":"look hard","tags":["Code","qa"]}"""))
        val saved = json.decodeFromString(PromptView.serializer(), f.client.await(Envelope.PROMPT_ITEM).data)
        assertEquals("Review", saved.name)
        assertEquals(listOf("code", "qa"), saved.tags)

        f.send(Envelope(Envelope.PROMPT_SAVE, """{"name":"Review","body":"v2"}"""))
        val updated = json.decodeFromString(PromptView.serializer(), f.client.await(Envelope.PROMPT_ITEM, 1).data)
        assertEquals(saved.id, updated.id)
        assertEquals("v2", updated.body)

        f.send(Envelope(Envelope.PROMPT_SAVE, """{"name":"Other","body":"o","tags":["qa"]}"""))
        f.client.await(Envelope.PROMPT_ITEM, 2)

        f.send(Envelope(Envelope.PROMPT_LIST, ""))
        assertEquals(listOf("Other", "Review"), list(f.client.await(Envelope.PROMPTS, 0).data).map { it.name })
        f.send(Envelope(Envelope.PROMPT_LIST, "qa"))
        assertEquals(listOf("Other"), list(f.client.await(Envelope.PROMPTS, 1).data).map { it.name })

        f.send(Envelope(Envelope.PROMPT_GET, saved.id))
        assertEquals("v2", json.decodeFromString(PromptView.serializer(), f.client.await(Envelope.PROMPT_ITEM, 3).data).body)

        f.send(Envelope(Envelope.PROMPT_DELETE, saved.id))
        assertEquals(listOf("Other"), list(f.client.await(Envelope.PROMPTS, 2).data).map { it.name })
    }

    @Test
    fun refusalsAreErrorsAndNothingIsWritten() = withDaemon { f ->
        listOf(
            """{"name":"","body":"b"}""",
            """{"name":"${"n".repeat(121)}","body":"b"}""",
            """{"name":"a","body":"${"b".repeat(64 * 1024 + 1)}"}""",
            """{"name":"a","body":"b","tgs":[]}""",
        ).forEachIndexed { i, bad ->
            f.send(Envelope(Envelope.PROMPT_SAVE, bad))
            assertTrue(f.client.await(Envelope.ERROR, i).data.startsWith("prompt_save refused"), bad.take(40))
        }
        f.send(Envelope(Envelope.PROMPT_GET, "nope"))
        assertTrue(f.client.await(Envelope.ERROR, 4).data.startsWith("prompt_get refused"))
        f.send(Envelope(Envelope.PROMPT_DELETE, "nope"))
        assertTrue(f.client.await(Envelope.ERROR, 5).data.startsWith("prompt_delete refused"))
        f.send(Envelope(Envelope.PROMPT_LIST, ""))
        assertEquals("[]", f.client.await(Envelope.PROMPTS).data)
    }
}
