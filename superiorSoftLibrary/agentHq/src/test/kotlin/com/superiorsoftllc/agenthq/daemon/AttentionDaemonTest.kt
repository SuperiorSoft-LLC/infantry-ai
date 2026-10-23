package com.superiorsoftllc.agenthq.daemon

import com.superiorsoftllc.agenthq.attention.AttentionItem
import com.superiorsoftllc.agenthq.attention.AttentionKind
import com.superiorsoftllc.agenthq.protocol.Envelope
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

class AttentionDaemonTest {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    @Test
    fun attentionPushPullHttpAndAckAreSharedByEveryClient() {
        val fixture = DaemonFixture.launch()
        try {
            val otherClient = fixture.connectClient()
            assertEquals(0L, fixture.health()["attentionOpen"]!!.jsonPrimitive.long)

            val created = fixture.daemon.attention.raise(
                kind = AttentionKind.STALLED,
                ticketId = "ticket-1",
                podId = "pod-1",
                sessionId = "session-1",
                outcome = "STALLED",
                detail = "no output before the stall deadline",
            )
            for (client in listOf(fixture.client, otherClient)) {
                val pushed = client.awaitWhere("attention push for ${created.id}") {
                    it.type == Envelope.ATTENTION &&
                        json.decodeFromString(AttentionItem.serializer(), it.data).id == created.id
                }
                assertEquals(created, json.decodeFromString(AttentionItem.serializer(), pushed.data))
            }

            fixture.send(Envelope.ATTENTION_LIST, "")
            val listed = json.decodeFromString(
                ListSerializer(AttentionItem.serializer()),
                fixture.client.await(Envelope.ATTENTION_ITEMS).data,
            )
            assertEquals(listOf(created), listed)

            val response = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create("http://${fixture.connectHost}:${fixture.port}/attention")).GET().build(),
                HttpResponse.BodyHandlers.ofString(),
            )
            assertEquals(200, response.statusCode())
            assertEquals(listOf(created), json.decodeFromString(ListSerializer(AttentionItem.serializer()), response.body()))

            fixture.send(Envelope.ATTENTION_ACK, created.id)
            val acked = fixture.client.awaitWhere("acknowledged attention push") {
                it.type == Envelope.ATTENTION &&
                    json.decodeFromString(AttentionItem.serializer(), it.data).id == created.id &&
                    json.decodeFromString(AttentionItem.serializer(), it.data).ackedAt != null
            }.let { json.decodeFromString(AttentionItem.serializer(), it.data) }
            assertNotNull(acked.ackedAt)
            val otherAck = otherClient.awaitWhere("acknowledgement reaches every client") {
                it.type == Envelope.ATTENTION &&
                    json.decodeFromString(AttentionItem.serializer(), it.data).id == created.id &&
                    json.decodeFromString(AttentionItem.serializer(), it.data).ackedAt != null
            }
            assertEquals(acked, json.decodeFromString(AttentionItem.serializer(), otherAck.data))
            assertEquals(0L, fixture.health()["attentionOpen"]!!.jsonPrimitive.long)
        } finally {
            fixture.stop()
            fixture.deleteState()
        }
    }
}
