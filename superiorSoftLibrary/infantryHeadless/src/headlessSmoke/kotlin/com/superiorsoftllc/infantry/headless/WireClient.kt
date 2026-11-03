// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.headless

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import java.io.File
import java.net.URI
import java.net.http.HttpClient
import java.net.http.WebSocket
import java.time.Duration
import java.util.concurrent.CompletionStage
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit

/**
 * A remote Infantry client that knows the WIRE and nothing else: the JDK's WebSocket, JSON envelopes
 * `{"type","data","sessionId","sequence"}` written by hand, and `Authorization: Bearer <token>`. It deliberately
 * shares no class with the daemon or the desktop client, which is what a phone app or a script on another machine
 * has to work with.
 *
 * Every frame received is kept in [frames] and appended to [transcript] as one JSON line (the evidence).
 */
class WireClient private constructor(private val transcript: File) : WebSocket.Listener {

    val frames = CopyOnWriteArrayList<JsonObject>()
    private val partial = StringBuilder()
    private lateinit var socket: WebSocket

    override fun onText(webSocket: WebSocket, data: CharSequence, last: Boolean): CompletionStage<*>? {
        partial.append(data)
        if (last) {
            val text = partial.toString()
            partial.setLength(0)
            frames += Json.parseToJsonElement(text).jsonObject
            synchronized(transcript) { transcript.appendText(text + "\n") }
        }
        // On EVERY part, not only the last: a fragmented frame must not stall the socket (DaemonTestHarness pitfall).
        webSocket.request(1)
        return null
    }

    /** Send one envelope. [data] is a string, as on the wire (a JSON payload travels encoded inside it). */
    fun send(type: String, data: String = "", sessionId: String? = null) {
        val envelope = buildJsonObject {
            put("type", type)
            put("data", data)
            if (sessionId != null) put("sessionId", sessionId)
        }
        socket.sendText(envelope.toString(), true).get(TIMEOUT_MS, TimeUnit.MILLISECONDS)
    }

    /** The index the next received frame will have: pass it to [await] to look only at what arrives after. */
    fun mark(): Int = frames.size

    /** The first frame at or after [from] matching [predicate], waiting up to [timeoutMs]. */
    fun await(what: String, from: Int = 0, timeoutMs: Long = TIMEOUT_MS, predicate: (JsonObject) -> Boolean): JsonObject {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            frames.drop(from).firstOrNull(predicate)?.let { return it }
            Thread.sleep(POLL_MS)
        }
        throw AssertionError("timed out after ${timeoutMs}ms waiting for $what; frames received: ${frames.map { it.type }}")
    }

    /** Send [type] and return the first answer of [answerType] (or an `error`) after it. */
    fun request(type: String, data: String = "", answerType: String): JsonObject {
        val from = mark()
        send(type, data)
        return await("the $answerType answer to $type", from) { it.type == answerType || it.type == ERROR }
    }

    fun close() {
        runCatching { socket.sendClose(WebSocket.NORMAL_CLOSURE, "headless smoke done").get(TIMEOUT_MS, TimeUnit.MILLISECONDS) }
        runCatching { socket.abort() }
    }

    companion object {
        const val ERROR = "error"
        private const val TIMEOUT_MS = 30_000L
        private const val POLL_MS = 25L

        /** Open `ws://<host>:<port>/ws` presenting [token]; the handshake fails (throws) if the daemon refuses it. */
        fun connect(host: String, port: Int, token: String, transcript: File): WireClient {
            val client = WireClient(transcript.apply { parentFile.mkdirs(); writeText("") })
            client.socket = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build()
                .newWebSocketBuilder()
                .header("Authorization", "Bearer $token")
                .buildAsync(URI.create("ws://$host:$port/ws"), client)
                .get(TIMEOUT_MS, TimeUnit.MILLISECONDS)
            return client
        }
    }
}

val JsonObject.type: String get() = (this["type"] as? JsonPrimitive)?.contentOrNull.orEmpty()

/** The envelope's `data`: a string on the wire. */
val JsonObject.data: String get() = (this["data"] as? JsonPrimitive)?.contentOrNull.orEmpty()

val JsonObject.sessionId: String? get() = (this["sessionId"] as? JsonPrimitive)?.contentOrNull

/** The envelope's `data` decoded as the JSON payload it carries. */
fun JsonObject.payload(): JsonElement = Json.parseToJsonElement(data)

fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull
