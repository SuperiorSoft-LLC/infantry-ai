package com.superiorsoftllc.agenthq.daemon

import com.superiorsoftllc.agenthq.daemon.api.attention.AttentionKinds
import com.superiorsoftllc.agenthq.protocol.Envelope
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/** ATTN-2b: attention push / pull / HTTP / ack, served by the BUILT Infantry_Attention daemon plugin. */
class AttentionDaemonTest {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    @Serializable
    private data class Wire(
        val id: String, val at: Long, val kind: String,
        val triggerId: String? = null, val ticketId: String? = null, val podId: String? = null, val sessionId: String? = null,
        val outcome: String, val detail: String, val ackedAt: Long? = null,
    )

    private fun item(data: String) = json.decodeFromString(Wire.serializer(), data)

    @Test
    fun attentionPushPullHttpAndAckAreSharedByEveryClient() {
        val fixture = DaemonFixture.launch(pluginsRoot = TestDaemonPlugins.boardRoot("official/Infantry_Attention"))
        try {
            val otherClient = fixture.connectClient()
            assertEquals(0L, fixture.health()["attentionOpen"]!!.jsonPrimitive.long)

            val id = assertNotNull(fixture.daemon.attention.raise(
                kind = AttentionKinds.STALLED,
                ticketId = "ticket-1",
                podId = "pod-1",
                sessionId = "session-1",
                outcome = "STALLED",
                detail = "no output before the stall deadline",
            ))
            val created = Wire(id, 0, "stalled", null, "ticket-1", "pod-1", "session-1", "STALLED", "no output before the stall deadline")
            for (client in listOf(fixture.client, otherClient)) {
                val pushed = client.awaitWhere("attention push for $id") { it.type == Envelope.ATTENTION && item(it.data).id == id }
                assertEquals(created, item(pushed.data).copy(at = 0))
            }

            fixture.send(Envelope.ATTENTION_LIST, "")
            val listed = json.decodeFromString(ListSerializer(Wire.serializer()), fixture.client.await(Envelope.ATTENTION_ITEMS).data)
            assertEquals(listOf(created), listed.map { it.copy(at = 0) })

            val response = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create("http://${fixture.connectHost}:${fixture.port}/attention")).GET().build(),
                HttpResponse.BodyHandlers.ofString(),
            )
            assertEquals(200, response.statusCode())
            assertEquals(listOf(created), json.decodeFromString(ListSerializer(Wire.serializer()), response.body()).map { it.copy(at = 0) })
            assertEquals(1L, fixture.health()["attentionOpen"]!!.jsonPrimitive.long)

            fixture.send(Envelope.ATTENTION_ACK, id)
            val acked = fixture.client.awaitWhere("acknowledged attention push") {
                it.type == Envelope.ATTENTION && item(it.data).id == id && item(it.data).ackedAt != null
            }.let { item(it.data) }
            assertNotNull(acked.ackedAt)
            val otherAck = otherClient.awaitWhere("acknowledgement reaches every client") {
                it.type == Envelope.ATTENTION && item(it.data).id == id && item(it.data).ackedAt != null
            }
            assertEquals(acked, item(otherAck.data))
            assertEquals(0L, fixture.health()["attentionOpen"]!!.jsonPrimitive.long)

            fixture.send(Envelope.ATTENTION_ACK, "missing")
            fixture.client.awaitWhere("unknown id refused") { it.type == Envelope.ERROR && "unknown attention item" in it.data }
        } finally {
            fixture.stop()
            fixture.deleteState()
        }
    }
}
