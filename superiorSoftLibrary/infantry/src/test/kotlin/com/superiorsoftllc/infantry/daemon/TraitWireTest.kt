package com.superiorsoftllc.infantry.daemon

import com.superiorsoftllc.infantry.protocol.Envelope
import com.superiorsoftllc.infantry.protocol.TraitView
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * TRAITS-T1 (contract 1.48.0) over the real daemon and a real WebSocket: save, versioned update by id, identical
 * save, list, get, delete, the push to every client, and refusals that write nothing.
 */
class TraitWireTest {
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

    private fun item(data: String) = json.decodeFromString(TraitView.serializer(), data)
    private fun list(data: String) = json.decodeFromString(ListSerializer(TraitView.serializer()), data)

    @Test
    fun saveVersionListGetDeleteRoundTrip() = withDaemon { f ->
        val other = f.connectClient()
        other.await(Envelope.READY)

        f.send(Envelope(Envelope.TRAIT_SAVE, """{"id":"report-back","name":"REPORT BACK","body":"mail the pm","check":{"kind":"report","fields":["commit","tests"]}}"""))
        val saved = item(f.client.await(Envelope.TRAIT_ITEM).data)
        assertEquals(1, saved.version)
        assertEquals("pm", saved.check!!.reportTo)
        assertEquals(saved, item(other.await(Envelope.TRAIT_ITEM).data), "the save is pushed to EVERY client")

        f.send(Envelope(Envelope.TRAIT_SAVE, """{"id":"report-back","name":"REPORT BACK","body":"mail the pm, now"}"""))
        val updated = item(f.client.await(Envelope.TRAIT_ITEM, 1).data)
        assertEquals(saved.id, updated.id)
        assertEquals(2, updated.version)
        assertEquals(null, updated.check)

        f.send(Envelope(Envelope.TRAIT_SAVE, """{"id":"report-back","name":"REPORT BACK","body":"mail the pm, now"}"""))
        assertEquals(updated, item(f.client.await(Envelope.TRAIT_ITEM, 2).data), "an identical save changes nothing")

        f.send(Envelope(Envelope.TRAIT_SAVE, """{"id":"a-first","name":"A","body":"x"}"""))
        f.client.await(Envelope.TRAIT_ITEM, 3)

        f.send(Envelope(Envelope.TRAIT_LIST, ""))
        assertEquals(listOf("a-first" to 1, "report-back" to 2), list(f.client.await(Envelope.TRAITS, 0).data).map { it.id to it.version })

        f.send(Envelope(Envelope.TRAIT_GET, "report-back"))
        assertEquals(updated, item(f.client.await(Envelope.TRAIT_ITEM, 4).data))

        f.send(Envelope(Envelope.TRAIT_DELETE, "a-first"))
        assertEquals(listOf("report-back"), list(f.client.await(Envelope.TRAITS, 1).data).map { it.id })
        assertEquals(listOf("report-back"), list(other.await(Envelope.TRAITS).data).map { it.id }, "the delete is pushed to EVERY client")
    }

    @Test
    fun refusalsAreErrorsAndNothingIsWritten() = withDaemon { f ->
        listOf(
            """{"id":"Bad Id","name":"n","body":"b"}""",
            """{"id":"a","name":"","body":"b"}""",
            """{"id":"a","name":"n","body":"${"b".repeat(2049)}"}""",
            """{"id":"a","name":"n","body":"b","tgs":[]}""",
            """{"id":"a","name":"n","body":"b","check":{"kind":"report","fields":["x"],"extra":1}}""",
            """{"id":"a","name":"n","body":"b","check":{"kind":"lint","fields":["x"]}}""",
            """{"id":"a","name":"n","body":"b","check":{"kind":"report","reportTo":"nobody","fields":["x"]}}""",
            """{"id":"a","name":"n","body":"b","check":{"kind":"report","fields":[]}}""",
        ).forEachIndexed { i, bad ->
            f.send(Envelope(Envelope.TRAIT_SAVE, bad))
            val error = f.client.await(Envelope.ERROR, i).data
            assertTrue(error.startsWith("trait_save refused"), "$bad -> $error")
        }
        f.send(Envelope(Envelope.TRAIT_GET, "nope"))
        assertTrue(f.client.await(Envelope.ERROR, 8).data.startsWith("trait_get refused"))
        f.send(Envelope(Envelope.TRAIT_DELETE, "nope"))
        assertTrue(f.client.await(Envelope.ERROR, 9).data.startsWith("trait_delete refused"))
        f.send(Envelope(Envelope.TRAIT_LIST, ""))
        assertEquals("[]", f.client.await(Envelope.TRAITS).data)
    }
}
