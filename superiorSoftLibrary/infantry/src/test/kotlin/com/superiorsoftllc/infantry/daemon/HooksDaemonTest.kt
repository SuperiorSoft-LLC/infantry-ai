// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.daemon

import com.superiorsoftllc.infantry.protocol.TriggerSave
import com.superiorsoftllc.infantry.protocol.Envelope
import com.superiorsoftllc.infantry.protocol.TriggerEventView
import com.superiorsoftllc.infantry.protocol.TriggerView
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * P9.6 over a REAL socket on a TOKEN-GATED daemon (the Owner's decision, 2026-10-18): `POST /hooks/{id}` is
 * reachable without the daemon bearer but only with a valid per-hook signature, and every other route still
 * demands the bearer.
 */
class HooksDaemonTest {
    private val token = "correct-horse-battery-staple-0123456789"
    private val secret = "whsec-0123456789abcdef0123456789abcdef"
    private val fixtures = mutableListOf<DaemonFixture>()
    private val http = HttpClient.newHttpClient()
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    @AfterTest
    fun stopAll() {
        fixtures.asReversed().forEach { runCatching { it.stop() }; runCatching { it.deleteState() } }
        fixtures.clear()
    }

    private fun launch(): DaemonFixture = DaemonFixture.launch(token = token, pluginsRoot = TestDaemonPlugins.triggersRoot()).also { fixtures += it }

    private fun post(
        f: DaemonFixture, path: String, body: ByteArray, headers: Map<String, String> = emptyMap(),
    ): HttpResponse<String> {
        val builder = HttpRequest.newBuilder(URI.create("http://${f.connectHost}:${f.port}$path"))
            .POST(HttpRequest.BodyPublishers.ofByteArray(body))
        headers.forEach { (k, v) -> builder.header(k, v) }
        return http.send(builder.build(), HttpResponse.BodyHandlers.ofString())
    }

    private fun signed(body: ByteArray, delivery: String, ts: Long = System.currentTimeMillis() / 1_000, key: String = secret) = mapOf(
        "X-Infantry-Signature" to sign(key, ts, delivery, body),
        "X-Infantry-Timestamp" to ts.toString(),
        "X-Infantry-Delivery" to delivery,
    )

    /**
     * TRIG-2c: the service runs in Infantry_Triggers now (its classes are not on this module's test path), so the test signs as any
     * sender does (the documented v1 scheme: hex HMAC-SHA256 of "v1:<ts>:<delivery>:<raw body>") and reaches the service by its frames.
     */
    private fun sign(key: String, ts: Long, delivery: String, body: ByteArray): String {
        val mac = Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(key.toByteArray(Charsets.UTF_8), "HmacSHA256")) }
        return "v1=" + mac.doFinal("v1:$ts:$delivery:".toByteArray(Charsets.UTF_8) + body).joinToString("") { "%02x".format(it) }
    }

    private fun hook(f: DaemonFixture): String {
        val mark = f.client.frames.size
        f.send(Envelope(Envelope.TRIGGER_SAVE, json.encodeToString(TriggerSave.serializer(), TriggerSave(name = "gh", kind = "webhook", coalesceMs = 0, webhookSecret = secret))))
        return json.decodeFromString(TriggerView.serializer(), f.client.awaitAfter(mark, "the saved trigger") { it.type == Envelope.TRIGGER }.data).id
    }

    private fun events(f: DaemonFixture, id: String): List<TriggerEventView> {
        val mark = f.client.frames.size
        f.send(Envelope(Envelope.TRIGGER_EVENTS, id))
        val frame = f.client.awaitAfter(mark, "the trigger events") { it.type == Envelope.TRIGGER_EVENTS }
        return json.decodeFromString(ListSerializer(TriggerEventView.serializer()), frame.data)
    }

    @Test
    fun aSignedPostIsAdmittedWithoutTheBearerAndRecorded() {
        val f = launch()
        val id = hook(f)
        val body = """{"ref":"main"}""".toByteArray()
        val response = post(f, "/hooks/$id", body, signed(body, "delivery-1"))
        assertEquals(200, response.statusCode(), response.body())
        assertTrue(response.body().contains("\"disposition\""), response.body())
        assertEquals("delivery-1", events(f, id).single().deliveryId)
    }

    @Test
    fun anUnsignedOrWronglySignedPostIsRefusedAndTheBearerDoesNotHelp() {
        val f = launch()
        val id = hook(f)
        val body = "x".toByteArray()
        assertEquals(401, post(f, "/hooks/$id", body).statusCode())
        assertEquals(401, post(f, "/hooks/$id", body, signed(body, "d", key = "w".repeat(40))).statusCode())
        // The daemon bearer is NOT a substitute for the signature on this route.
        assertEquals(401, post(f, "/hooks/$id", body, mapOf("Authorization" to "Bearer $token")).statusCode())
        assertEquals(401, post(f, "/hooks/trg-unknown", body, signed(body, "d")).statusCode())
        assertTrue(events(f, id).isEmpty())
    }

    @Test
    fun everyOtherRouteStillDemandsTheBearer() {
        val f = launch()
        val body = "{}".toByteArray()
        assertEquals(401, post(f, "/mail/send", body).statusCode())
        assertEquals(401, post(f, "/hooks/../mail/send", body).statusCode())
        assertEquals(401, post(f, "/hooks/a/b", body).statusCode())
    }

    @Test
    fun aBodyOverSixtyFourKilobytesIsRefusedWith413EvenWhenCorrectlySigned() {
        val f = launch()
        val id = hook(f)
        val big = ByteArray(65_536 + 1) { 'a'.code.toByte() }
        assertEquals(413, post(f, "/hooks/$id", big, signed(big, "big")).statusCode())
        val atCap = ByteArray(65_536) { 'a'.code.toByte() }
        assertEquals(200, post(f, "/hooks/$id", atCap, signed(atCap, "cap")).statusCode())
    }

    @Test
    fun aReplayedDeliveryAnswers200DuplicateAndAStaleOneAnswers401() {
        val f = launch()
        val id = hook(f)
        val body = "{}".toByteArray()
        assertEquals(200, post(f, "/hooks/$id", body, signed(body, "once")).statusCode())
        val again = post(f, "/hooks/$id", body, signed(body, "once"))
        assertEquals(200, again.statusCode())
        assertTrue(again.body().contains("duplicate"), again.body())
        val stale = System.currentTimeMillis() / 1_000 - 3_600
        assertEquals(401, post(f, "/hooks/$id", body, signed(body, "old", ts = stale)).statusCode())
    }
}
