// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.headless

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.File
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.net.http.WebSocket
import java.net.http.WebSocketHandshakeException
import java.security.SecureRandom
import java.time.Duration
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * HEADLESS-1: the Infantry daemon is a headless server that a remote client drives with the protocol and a token.
 *
 * Each scenario launches the INSTALLED daemon as its own JVM ([HeadlessDaemon]) and talks to it as a remote client
 * would ([WireClient], raw JSON over the JDK WebSocket). See the module's build.gradle.kts for why nothing here may
 * depend on the daemon's classes.
 *
 * The three guards, and the product change each one exists to catch:
 *  - [theInstalledDaemonShipsNoUiOrComposeJar] and the class-load scan at the end of
 *    [aRemoteClientDrivesTheHeadlessDaemonWithTheProtocolAndTheTokenAlone]: the daemon pulls in a UI/Compose class;
 *  - [aNonLoopbackBindWithoutATokenNeverStarts]: AccessPolicy.of stops refusing an exposed, token-less bind;
 *  - [aRequestWithoutTheTokenIsRefused]: AccessPolicy.check stops demanding the bearer token.
 */
class HeadlessSmokeTest {

    private val token = freshToken()

    @Test
    fun theInstalledDaemonShipsNoUiOrComposeJar() {
        val jars = File(HeadlessDaemon.install, "lib").list()?.sorted().orEmpty()
        assertTrue(jars.any { it.startsWith("infantry") }, "the installed daemon jar is missing from ${HeadlessDaemon.install}: $jars")
        val ui = jars.filter { UI_JAR.containsMatchIn(it) }
        assertTrue(ui.isEmpty(), "the daemon's runtime classpath ships UI/Compose jars: $ui")
    }

    @Test
    fun aRemoteClientDrivesTheHeadlessDaemonWithTheProtocolAndTheTokenAlone() {
        val summary = linkedMapOf<String, Any?>()
        val loaded: List<String>
        HeadlessDaemon.launch("remote-client", HeadlessDaemon.WILDCARD, token).use { daemon ->
            val port = daemon.awaitListening()
            assertFalse(port in HeadlessDaemon.OWNER_PORTS, "the smoke must never take the owner's ports: $port")
            daemon.assertWorkingFolderIsNotACheckout()
            val host = HeadlessDaemon.clientHost(port)
            summary["bind"] = "${HeadlessDaemon.WILDCARD}:$port"
            summary["clientHost"] = host

            // 1. Connect with the token alone.
            val client = WireClient.connect(host, port, token, File(daemon.runDir, "frames-client.jsonl"))
            try {
                val ready = client.await("ready") { it.type == "ready" }
                assertTrue(ready.data.contains("contract="), "ready must name the contract: ${ready.data}")
                summary["ready"] = ready.data

                // 2. List projects.
                val projects = client.request("project_list", answerType = "projects")
                assertEquals("projects", projects.type, "project_list was refused: ${projects.data}")
                assertTrue(projects.payload() is JsonArray, "projects must be a JSON array: ${projects.data}")
                summary["projects"] = projects.data

                // 3. Create a ticket (assigned to a profile, so it can start).
                val agent = client.request("agent_save", obj { put("id", "headless"); put("name", "Headless"); put("role", "backend") }, "agent")
                assertEquals("agent", agent.type, "agent_save was refused: ${agent.data}")
                val saved = client.request("ticket_save", obj { put("title", "headless smoke"); put("agentId", "headless") }, "ticket")
                assertEquals("ticket", saved.type, "ticket_save was refused: ${saved.data}")
                val ticketId = assertNotNull(saved.payload().jsonObject.string("id"), "the saved ticket has no id: ${saved.data}")
                summary["ticket"] = "$ticketId status=${saved.payload().jsonObject.string("status")}"

                // 4. Start it on the stub harness: a move to in_progress.
                val beforeStart = client.mark()
                client.send("ticket_move", obj { put("id", ticketId); put("status", "in_progress") })
                val moved = client.await("the ticket_move answer", beforeStart) {
                    it.type == WireClient.ERROR || (it.type == "ticket" && it.payload().jsonObject.string("id") == ticketId)
                }
                assertEquals("ticket", moved.type, "ticket_move was refused: ${moved.data}")
                val start = moved.payload().jsonObject["start"] as? JsonObject
                assertEquals(true, start?.get("ok")?.jsonPrimitive?.boolean, "the ticket must start: $start")
                val sessionId = assertNotNull(moved.payload().jsonObject.string("sessionId"), "a started ticket names its session: ${moved.data}")
                summary["start"] = start.toString()
                summary["sessionId"] = sessionId

                // 5. Read the session output: live, as it streams.
                val finished = client.await("the session's finished frame", beforeStart, timeoutMs = 60_000) {
                    it.type == "finished" && it.sessionId == sessionId
                }
                assertEquals("SUCCEEDED", finished.payload().jsonObject.string("outcome"), "the stub run must succeed: ${finished.data}")
                val liveText = client.frames.drop(beforeStart).filter { it.type == "text_delta" && it.sessionId == sessionId }.map { it.data }
                assertTrue(liveText.any { it.isNotBlank() }, "the session streamed no text: ${client.frames.map { it.type }}")
                summary["finished"] = finished.data
                summary["liveText"] = liveText

                // ...and read back by a second remote client that was not there when it ran.
                val late = WireClient.connect(host, port, token, File(daemon.runDir, "frames-replay.jsonl"))
                try {
                    late.await("ready (second client)") { it.type == "ready" }
                    val beforeReplay = late.mark()
                    late.send("replay_request", obj { put("lastSequence", 0) }, sessionId)
                    late.await("the replayed finished frame", beforeReplay) { it.type == "finished" && it.sessionId == sessionId }
                    val replayed = late.frames.drop(beforeReplay).filter { it.sessionId == sessionId }
                    val replayedText = replayed.filter { it.type == "text_delta" }.map { it.data }
                    assertEquals(liveText, replayedText, "the replay must carry the same output as the live stream")
                    summary["replayedTypes"] = replayed.map { it.type }.distinct()
                } finally {
                    late.close()
                }
            } finally {
                client.close()
            }
            daemon.close()
            loaded = daemon.loadedClasses()
        }

        // The whole scenario ran (boot, ticket start, session, replay): the daemon must not have loaded any UI class.
        assertTrue(loaded.contains(HeadlessDaemon.DAEMON_MAIN), "the class-load log does not cover the daemon run (${loaded.size} classes)")
        val ui = loaded.filter { name -> UI_CLASS_PREFIXES.any { name.startsWith(it) } }.distinct()
        summary["classesLoaded"] = loaded.size
        summary["uiClassesLoaded"] = ui
        writeSummary("remote-client", summary)
        assertTrue(ui.isEmpty(), "the headless daemon loaded UI/Compose classes at runtime: ${ui.take(20)}")
    }

    @Test
    fun aRequestWithoutTheTokenIsRefused() {
        val summary = linkedMapOf<String, Any?>()
        HeadlessDaemon.launch("no-token-request", HeadlessDaemon.WILDCARD, token).use { daemon ->
            val port = daemon.awaitListening()
            assertFalse(port in HeadlessDaemon.OWNER_PORTS, "the smoke must never take the owner's ports: $port")
            val host = HeadlessDaemon.clientHost(port)
            summary["clientHost"] = host

            // Controls: the daemon is reachable, and the right token is admitted, so a 401 below is the gate speaking.
            assertEquals(200, httpGet(host, port, "/health", null).statusCode(), "/health is the one open route")
            assertEquals(200, httpGet(host, port, "/attention", "Bearer $token").statusCode(), "the right token is admitted")
            assertEquals(101, handshake(host, port, "Bearer $token").status, "the right token opens the socket")

            val cases = listOf(
                "no Authorization header" to null,
                "a different token" to "Bearer ${freshToken()}",
                "the token without the Bearer scheme" to token,
            )
            for ((label, authorization) in cases) {
                val ws = handshake(host, port, authorization)
                summary["ws: $label"] = ws.status
                assertEquals(401, ws.status, "a WebSocket handshake with $label must be refused")
                assertTrue(ws.challenge?.startsWith("Bearer") == true, "the refusal must challenge for a bearer token: $ws")
                val http = httpGet(host, port, "/attention", authorization).statusCode()
                summary["GET /attention: $label"] = http
                assertEquals(401, http, "GET /attention with $label must be refused")
            }
            writeSummary("no-token-request", summary)
        }
    }

    @Test
    fun aNonLoopbackBindWithoutATokenNeverStarts() {
        HeadlessDaemon.launch("no-token-bind", HeadlessDaemon.WILDCARD, token = null).use { daemon ->
            val deadline = System.currentTimeMillis() + 60_000
            while (daemon.process.isAlive && !daemon.announcedListening() && System.currentTimeMillis() < deadline) {
                Thread.sleep(50)
            }
            assertFalse(
                daemon.announcedListening(),
                "a daemon bound to ${HeadlessDaemon.WILDCARD} with no token must never start listening.\n${daemon.streams()}",
            )
            assertTrue(daemon.process.waitFor(30, TimeUnit.SECONDS), "a refused daemon must exit.\n${daemon.streams()}")
            assertEquals(2, daemon.process.exitValue(), "a refused daemon exits 2.\n${daemon.streams()}")
            assertTrue(
                daemon.err().contains("refusing to start") && daemon.err().contains("without a bearer token"),
                "the refusal must say why.\n${daemon.streams()}",
            )
            writeSummary("no-token-bind", mapOf("exit" to daemon.process.exitValue(), "stderr" to daemon.err().trim().lines().last()))
        }
    }

    // ---------------------------------------------------------------------------------------------------------------

    private data class Handshake(val status: Int, val challenge: String?)

    /** How the daemon answered a WebSocket upgrade: 101 when it admitted the client, otherwise the refusal. */
    private fun handshake(host: String, port: Int, authorization: String?): Handshake {
        val builder = http.newWebSocketBuilder()
        authorization?.let { builder.header("Authorization", it) }
        return try {
            val socket = builder.buildAsync(URI.create("ws://$host:$port/ws"), object : WebSocket.Listener {})
                .get(30, TimeUnit.SECONDS)
            socket.abort()
            Handshake(101, null)
        } catch (e: ExecutionException) {
            val refused = e.cause as? WebSocketHandshakeException ?: throw e
            Handshake(refused.response.statusCode(), refused.response.headers().firstValue("WWW-Authenticate").orElse(null))
        }
    }

    private fun httpGet(host: String, port: Int, path: String, authorization: String?): HttpResponse<String> {
        val request = HttpRequest.newBuilder(URI.create("http://$host:$port$path"))
            .timeout(Duration.ofSeconds(30))
            .apply { authorization?.let { header("Authorization", it) } }
            .GET()
            .build()
        return http.send(request, HttpResponse.BodyHandlers.ofString())
    }

    private fun obj(build: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit): String = buildJsonObject(build).toString()

    /** One readable file per scenario for the gate report. The token is never written. */
    private fun writeSummary(scenario: String, values: Map<String, Any?>) {
        val dir = File(System.getProperty("headlessSmoke.reportDir"), scenario).apply { mkdirs() }
        val text = values.entries.joinToString("\n") { (key, value) -> "$key: $value" }
        check(!text.contains(token)) { "the summary must never carry the token" }
        File(dir, "summary.txt").writeText(text + "\n")
        println("[headlessSmoke] $scenario\n" + text.prependIndent("  "))
    }

    private companion object {
        val http: HttpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build()

        /** Jars a headless daemon has no business shipping. */
        val UI_JAR = Regex("compose|skiko|skia|infantryui|javafx", RegexOption.IGNORE_CASE)

        /**
         * Classes a headless daemon has no business loading: Compose (Jetbrains and androidx), Skia/Skiko, Swing,
         * AWT and its peers, JavaFX, and the Infantry desktop/UI packages (infantryUiCore and infantryUi are all under
         * `com.superiorsoftllc.infantry.ui`). AWT is listed whole on purpose: a server feature that genuinely needs a
         * headless-safe AWT class (image processing, say) must be argued for and added here as a named exception.
         */
        val UI_CLASS_PREFIXES = listOf(
            "androidx.compose.",
            "org.jetbrains.compose.",
            "org.jetbrains.skia.",
            "org.jetbrains.skiko.",
            "javax.swing.",
            "java.awt.",
            "sun.awt.",
            "sun.java2d.",
            "javafx.",
            "com.superiorsoftllc.infantry.ui.",
        )

        /** A 64-hex-character token, comfortably above the daemon's 32-character minimum. */
        fun freshToken(): String {
            val bytes = ByteArray(32).also { SecureRandom().nextBytes(it) }
            return bytes.joinToString("") { "%02x".format(it) }
        }
    }
}
