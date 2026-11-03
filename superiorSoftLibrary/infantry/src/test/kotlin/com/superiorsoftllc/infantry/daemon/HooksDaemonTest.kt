package com.superiorsoftllc.infantry.daemon

import com.superiorsoftllc.infantry.protocol.TriggerSave
import com.superiorsoftllc.infantry.triggers.WebhookIngress
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

    @AfterTest
    fun stopAll() {
        fixtures.asReversed().forEach { runCatching { it.stop() }; runCatching { it.deleteState() } }
        fixtures.clear()
    }

    private fun launch(): DaemonFixture = DaemonFixture.launch(token = token).also { fixtures += it }

    private fun post(
        f: DaemonFixture, path: String, body: ByteArray, headers: Map<String, String> = emptyMap(),
    ): HttpResponse<String> {
        val builder = HttpRequest.newBuilder(URI.create("http://${f.connectHost}:${f.port}$path"))
            .POST(HttpRequest.BodyPublishers.ofByteArray(body))
        headers.forEach { (k, v) -> builder.header(k, v) }
        return http.send(builder.build(), HttpResponse.BodyHandlers.ofString())
    }

    private fun signed(body: ByteArray, delivery: String, ts: Long = System.currentTimeMillis() / 1_000, key: String = secret) = mapOf(
        WebhookIngress.SIGNATURE_HEADER to WebhookIngress.sign(key, ts, delivery, body),
        WebhookIngress.TIMESTAMP_HEADER to ts.toString(),
        WebhookIngress.DELIVERY_HEADER to delivery,
    )

    private fun hook(f: DaemonFixture): String =
        f.daemon.triggers.save(TriggerSave(name = "gh", kind = "webhook", coalesceMs = 0, webhookSecret = secret)).id

    @Test
    fun aSignedPostIsAdmittedWithoutTheBearerAndRecorded() {
        val f = launch()
        val id = hook(f)
        val body = """{"ref":"main"}""".toByteArray()
        val response = post(f, "/hooks/$id", body, signed(body, "delivery-1"))
        assertEquals(200, response.statusCode(), response.body())
        assertTrue(response.body().contains("\"disposition\""), response.body())
        assertEquals("delivery-1", f.daemon.triggers.events(id).single().deliveryId)
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
        assertTrue(f.daemon.triggers.events(id).isEmpty())
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
