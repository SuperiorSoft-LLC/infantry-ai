// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.runners

import com.superiorsoftllc.infantry.protocol.Envelope
import kotlinx.serialization.json.Json
import java.net.URI
import java.net.http.HttpClient
import java.net.http.WebSocket
import java.time.Duration
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionStage
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import com.superiorsoftllc.infantry.runners.RunnerLinkFrames as F

/**
 * RUNNER-POOL-5: the ORCHESTRATOR side of one link call: open the runner's link, send one request, read its answer, close. JDK
 * only ([HttpClient] WebSocket). A `wss://` URL is checked against the pinned [fingerprint] ([RunnerLinkTls.pinnedContext]), never
 * a CA or a host name; `ws://` is refused unless [allowPlain] (`runners.allowPlainTailnet`).
 */
object RunnerLinkClient {
    /** A call that got no answer. [presentedFingerprint] is set when the TLS pin refused the runner's certificate. */
    class Failure(message: String, val presentedFingerprint: String? = null) : Exception(message)

    fun call(
        url: String,
        fingerprint: String?,
        allowPlain: Boolean,
        orchestratorId: String?,
        credential: String?,
        type: String,
        data: String,
        timeoutSeconds: Long,
    ): RunnerLinkServer.Answer {
        val uri = endpoint(url, allowPlain)
        val builder = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(timeoutSeconds))
        val pin = if (uri.scheme == "wss") RunnerLinkTls.pinnedContext(fingerprint).also { builder.sslContext(it.first) }.second else null
        val client = builder.build()
        val answer = CompletableFuture<String>()
        val socket = try {
            client.newWebSocketBuilder()
                .connectTimeout(Duration.ofSeconds(timeoutSeconds))
                .apply {
                    if (!orchestratorId.isNullOrBlank()) header(F.ORCHESTRATOR_HEADER, orchestratorId)
                    if (!credential.isNullOrBlank()) header(F.CREDENTIAL_HEADER, credential)
                }
                .buildAsync(uri, Collector(answer))
                .get(timeoutSeconds, TimeUnit.SECONDS)
        } catch (e: ExecutionException) {
            val shown = pin?.presented
            if (shown != null && (fingerprint == null || RunnerLinkTls.normalized(shown) != RunnerLinkTls.normalized(fingerprint))) {
                throw Failure(
                    if (fingerprint == null) "the runner presented certificate fingerprint $shown; compare it with the one `infantry runner pair` printed and pass it as --fingerprint"
                    else "certificate fingerprint mismatch: pinned $fingerprint, the runner presented $shown (refused; a changed certificate means re-pairing)",
                    shown,
                )
            }
            throw Failure("cannot reach the runner link at $url: ${e.cause?.message ?: e.message}")
        } catch (e: TimeoutException) {
            throw Failure("the runner link at $url did not answer within ${timeoutSeconds}s")
        }
        try {
            val requestId = UUID.randomUUID().toString()
            socket.sendText(json.encodeToString(Envelope.serializer(), Envelope(type, data, requestId = requestId)), true).get(timeoutSeconds, TimeUnit.SECONDS)
            val reply = runCatching { json.decodeFromString(Envelope.serializer(), answer.get(timeoutSeconds, TimeUnit.SECONDS)) }
                .getOrElse { throw Failure("the runner link at $url gave no answer: ${(it.cause ?: it).message}") }
            return RunnerLinkServer.Answer(reply.type, reply.data)
        } finally {
            runCatching { socket.sendClose(WebSocket.NORMAL_CLOSURE, "").get(timeoutSeconds, TimeUnit.SECONDS) }
            runCatching { socket.abort() }
        }
    }

    /** [url] as the link endpoint: `wss://host:port` (or `ws://` with [allowPlain]); the [F.PATH] is added when absent. */
    fun endpoint(url: String, allowPlain: Boolean): URI {
        val uri = runCatching { URI(url.trim()) }.getOrElse { throw Failure("not a URL: $url") }
        when (uri.scheme?.lowercase()) {
            "wss" -> Unit
            "ws" -> if (!allowPlain) throw Failure("plain ws:// is refused: the runner link needs wss:// (runners.allowPlainTailnet is off)")
            else -> throw Failure("the runner URL must start with wss:// (got '$url')")
        }
        if (uri.host.isNullOrBlank()) throw Failure("the runner URL names no host: $url")
        return if (uri.path.isNullOrEmpty() || uri.path == "/") URI(uri.scheme.lowercase(), null, uri.host, uri.port, F.PATH, null, null) else uri
    }

    private class Collector(private val answer: CompletableFuture<String>) : WebSocket.Listener {
        private val text = StringBuilder()

        override fun onText(webSocket: WebSocket, data: CharSequence, last: Boolean): CompletionStage<*>? {
            text.append(data)
            if (last) answer.complete(text.toString())
            webSocket.request(1)
            return null
        }

        override fun onClose(webSocket: WebSocket, statusCode: Int, reason: String): CompletionStage<*>? {
            answer.completeExceptionally(Failure("the runner closed the link ($statusCode $reason)"))
            return null
        }

        override fun onError(webSocket: WebSocket, error: Throwable) {
            answer.completeExceptionally(error)
        }
    }

    private val json = Json { ignoreUnknownKeys = true }
}
