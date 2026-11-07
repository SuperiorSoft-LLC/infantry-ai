// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.daemon

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
import java.util.concurrent.TimeoutException

/**
 * RUNNER-POOL-7: runs ONE frame on this daemon's own `/ws`, as a non-owner caller holding [token], and returns the direct answer
 * (the frame echoing the request id), or null when none came within [timeoutMs] (which also bounds the connect and the send).
 *
 * A frame that arrives on behalf of an agent somewhere else goes through the very path a local agent's frame takes: the access
 * policy, the policy runtime, the frame gate, the plugin gates and the agent tier all run unchanged, and every dispatch (frame
 * table, plugin frames, plugin tools, the inline frames) is reached. Nothing is re-implemented here. The connection carries no
 * owner header, so an owner-only frame is refused by the gate exactly as for any agent.
 */
class LoopbackFrames(
    private val url: String,
    private val token: () -> String?,
    private val timeoutMs: () -> Long,
    private val json: Json = Json { ignoreUnknownKeys = true; encodeDefaults = false },
) {
    private val client: HttpClient by lazy { HttpClient.newHttpClient() }

    fun call(frame: Envelope): Envelope? {
        val waitMs = timeoutMs().coerceAtLeast(1)
        val requestId = "fwd:${UUID.randomUUID()}"
        val answer = CompletableFuture<Envelope?>()
        val listener = object : WebSocket.Listener {
            private val buffer = StringBuilder()
            override fun onText(webSocket: WebSocket, data: CharSequence, last: Boolean): CompletionStage<*>? {
                buffer.append(data)
                if (last) {
                    val text = buffer.toString()
                    buffer.setLength(0)
                    runCatching { json.decodeFromString(Envelope.serializer(), text) }.getOrNull()
                        ?.takeIf { it.requestId == requestId }
                        ?.let { answer.complete(it) }
                }
                webSocket.request(1)
                return null
            }
            override fun onClose(webSocket: WebSocket, statusCode: Int, reason: String?): CompletionStage<*>? {
                answer.complete(null)
                return null
            }
            override fun onError(webSocket: WebSocket, error: Throwable) { answer.completeExceptionally(error) }
        }
        val builder = client.newWebSocketBuilder().connectTimeout(Duration.ofMillis(waitMs))
        token()?.let { builder.header("Authorization", "Bearer $it") }
        val socket = builder.buildAsync(URI.create(url), listener).get(waitMs, TimeUnit.MILLISECONDS)
        try {
            socket.sendText(json.encodeToString(Envelope.serializer(), frame.copy(requestId = requestId)), true).get(waitMs, TimeUnit.MILLISECONDS)
            return try {
                answer.get(waitMs, TimeUnit.MILLISECONDS)
            } catch (_: TimeoutException) {
                null
            }?.copy(requestId = frame.requestId)
        } finally {
            runCatching { socket.sendClose(WebSocket.NORMAL_CLOSURE, "done") }
            runCatching { socket.abort() }
        }
    }

}
