// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.daemon.plugins

import com.superiorsoftllc.infantry.daemon.OwnerCredential
import com.superiorsoftllc.infantry.protocol.Envelope
import kotlinx.serialization.json.Json
import java.net.URI
import java.net.http.HttpClient
import java.net.http.WebSocket
import java.time.Duration
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import java.util.concurrent.TimeUnit

/**
 * SCRIPT-HOOK-3: how a daemon-run tool call (a script's action) gets the frames its tool wants answered. Many tools are thin: they send
 * a core frame (`ticket_move`, `mail_send`, `session_remove`...) over the caller's own connection. A script has no connection, so the
 * daemon opens one to itself: the same `/ws` every client uses, so the same gates and the same handlers answer. Nothing is special-cased
 * per tool. [ownerToken] is presented only for an owner [com.superiorsoftllc.infantry.daemon.api.Caller]; [accessToken] is the bearer
 * (null when the daemon runs without one).
 */
class LoopbackFrames(
    private val host: String,
    private val port: Int,
    private val accessToken: String?,
    private val ownerToken: String?,
    private val json: Json = Json { ignoreUnknownKeys = true; encodeDefaults = true },
) {
    private val client: HttpClient by lazy { HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build() }

    /** Sends [request] (as [asOwner]) and returns the answer carrying its requestId; an error envelope when none comes in [timeoutMs]. */
    fun request(request: Envelope, asOwner: Boolean, timeoutMs: Long): Envelope {
        val id = "loopback:" + UUID.randomUUID()
        val answer = CompletableFuture<Envelope>()
        val buf = StringBuilder()
        val listener = object : WebSocket.Listener {
            override fun onOpen(ws: WebSocket) { ws.request(1) }
            override fun onText(ws: WebSocket, data: CharSequence, last: Boolean): CompletionStage<*>? {
                buf.append(data)
                if (last) {
                    val text = buf.toString(); buf.setLength(0)
                    runCatching { json.decodeFromString(Envelope.serializer(), text) }.getOrNull()?.takeIf { it.requestId == id }?.let { answer.complete(it) }
                }
                ws.request(1)
                return null
            }
            override fun onError(ws: WebSocket, error: Throwable) { answer.complete(Envelope(Envelope.ERROR, "loopback connection failed: ${error.message}")) }
        }
        val ws = try {
            client.newWebSocketBuilder().connectTimeout(Duration.ofSeconds(5)).apply {
                accessToken?.let { header("Authorization", "Bearer $it") }
                if (asOwner) ownerToken?.let { header(OwnerCredential.HEADER, it) }
            }.buildAsync(URI.create("ws://$host:$port/ws"), listener).get(10, TimeUnit.SECONDS)
        } catch (e: Exception) {
            return Envelope(Envelope.ERROR, "loopback connection refused: ${e.message ?: e::class.simpleName}")
        }
        return try {
            ws.sendText(json.encodeToString(Envelope.serializer(), request.copy(requestId = id)), true).get(10, TimeUnit.SECONDS)
            answer.get(timeoutMs, TimeUnit.MILLISECONDS)
        } catch (e: java.util.concurrent.TimeoutException) {
            Envelope(Envelope.ERROR, "${request.type} got no answer in $timeoutMs ms")
        } catch (e: Exception) {
            Envelope(Envelope.ERROR, "${request.type} failed: ${e.message ?: e::class.simpleName}")
        } finally {
            runCatching { ws.sendClose(WebSocket.NORMAL_CLOSURE, "done") }
        }
    }
}
