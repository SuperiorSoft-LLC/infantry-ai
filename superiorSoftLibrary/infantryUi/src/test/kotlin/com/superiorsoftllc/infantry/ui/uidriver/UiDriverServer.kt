// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.ui.uidriver

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import java.net.InetAddress
import java.net.InetSocketAddress
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/** One API call waiting for the UI thread: the verb (`click`, `query`, ...), its JSON body, and where the answer goes. */
class UiDriverRequest(val verb: String, val body: JsonObject, val answer: CompletableFuture<UiDriverAnswer> = CompletableFuture())

/** An answer: an HTTP status and a JSON body (`{"ok": true, ...}` or `{"ok": false, "error": "..."}`). */
class UiDriverAnswer(val status: Int, val body: JsonObject)

/**
 * UI-DRIVER-1: the driver's API. A JDK HttpServer bound to the loopback address only, on [port] (0 = any, for tests),
 * guarded by a per-run [token] (`Authorization: Bearer <token>`, compared in constant time). It never touches the UI
 * itself: each call is queued in [requests] and answered by the thread that owns the offscreen UI (UiDriverSession),
 * because Compose test actions must run there.
 *
 * Routes: `POST /ui/<verb>` with a JSON object body; `GET /ui/health` needs the token too.
 */
class UiDriverServer(port: Int, val token: String = newToken(), private val answerTimeoutMs: Long = DEFAULT_ANSWER_TIMEOUT_MS) : AutoCloseable {
    val requests = LinkedBlockingQueue<UiDriverRequest>()
    private val pool = Executors.newFixedThreadPool(2) { r -> Thread(r, "ui-driver-http").apply { isDaemon = true } }
    private val server: HttpServer = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), port), 0).apply {
        executor = pool
        createContext("/ui/") { ex -> runCatching { handle(ex) }.onFailure { runCatching { ex.close() } } }
        start()
    }
    val port: Int get() = server.address.port

    private fun handle(ex: HttpExchange) {
        ex.use {
            val auth = ex.requestHeaders.getFirst("Authorization").orEmpty()
            if (!MessageDigest.isEqual(auth.toByteArray(), "Bearer $token".toByteArray())) {
                return respond(ex, UiDriverAnswer(401, error("missing or wrong token")))
            }
            val verb = ex.requestURI.path.removePrefix("/ui/").trim('/')
            if (verb.isEmpty() || !verb.all { it.isLetter() }) return respond(ex, UiDriverAnswer(404, error("unknown route ${ex.requestURI.path}")))
            val raw = ex.requestBody.use { it.readNBytes(MAX_BODY_BYTES + 1) }
            if (raw.size > MAX_BODY_BYTES) return respond(ex, UiDriverAnswer(413, error("body over $MAX_BODY_BYTES bytes")))
            val body = runCatching { if (raw.isEmpty()) JsonObject(emptyMap()) else Json.parseToJsonElement(raw.decodeToString()).jsonObject }
                .getOrElse { return respond(ex, UiDriverAnswer(400, error("body is not a JSON object"))) }
            val request = UiDriverRequest(verb, body)
            requests.put(request)
            val answer = runCatching { request.answer.get(answerTimeoutMs, TimeUnit.MILLISECONDS) }
                .getOrElse { UiDriverAnswer(504, error("the UI did not answer within $answerTimeoutMs ms")) }
            respond(ex, answer)
        }
    }

    private fun respond(ex: HttpExchange, answer: UiDriverAnswer) {
        // `tree` as plain text when asked (Accept: text/plain): a shell reads it without a JSON tool.
        val plain = (answer.body["tree"] as? JsonPrimitive)?.takeIf { it.isString && "text/plain" in ex.requestHeaders.getFirst("Accept").orEmpty() }
        val bytes = (plain?.content ?: answer.body.toString()).toByteArray()
        ex.responseHeaders.add("Content-Type", if (plain != null) "text/plain; charset=utf-8" else "application/json")
        ex.sendResponseHeaders(answer.status, bytes.size.toLong())
        ex.responseBody.use { it.write(bytes) }
    }

    override fun close() {
        server.stop(0)
        pool.shutdownNow()
    }

    companion object {
        const val MAX_BODY_BYTES = 64 * 1024
        /** Longer than the longest `wait` a caller may ask for (UiDriverSession.MAX_WAIT_MS). */
        const val DEFAULT_ANSWER_TIMEOUT_MS = 11 * 60_000L

        fun newToken(): String = ByteArray(24).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) }

        fun error(message: String): JsonObject = buildJsonObject { put("ok", false); put("error", message) }
    }
}
