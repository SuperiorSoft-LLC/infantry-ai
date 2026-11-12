// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.daemon

import com.superiorsoftllc.infantry.cli.BackgroundCli
import com.superiorsoftllc.infantry.cli.freshToken
import com.superiorsoftllc.infantry.cli.lanIpv4
import com.superiorsoftllc.infantry.cli.lanPathAnswers
import com.superiorsoftllc.infantry.cli.runCli
import com.superiorsoftllc.infantry.cli.writeTokenFile
import com.superiorsoftllc.infantry.mailbox.JsonMailStore
import com.superiorsoftllc.infantry.mailbox.MailSendRequest
import com.superiorsoftllc.infantry.orchestration.GatedRunner
import com.superiorsoftllc.infantry.persistence.SqlInfantryStore
import com.superiorsoftllc.infantry.protocol.Envelope
import com.superiorsoftllc.infantry.protocol.EventVocabulary
import com.superiorsoftllc.infantry.provider.StubProvider
import com.superiorsoftllc.infantry.runner.ProcessListener
import com.superiorsoftllc.infantry.runner.ProcessRunner
import com.superiorsoftllc.infantry.runner.ProcessSpec
import com.superiorsoftllc.infantry.runner.RunningProcess
import com.superiorsoftllc.infantry.runner.StubScript
import com.superiorsoftllc.infantry.session.JsonSessionStore
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonPrimitive
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.net.Socket
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.net.http.WebSocket
import java.net.http.WebSocketHandshakeException
import java.nio.file.Files
import java.util.concurrent.CompletionException
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * The daemon side of Phase 7, over real sockets: who may connect, what a refused client is told, that
 * agents receive the token without it ever being written into a prompt, and that one daemon serves
 * several clients. The access decisions themselves are unit-tested in [AccessPolicyTest]; these tests
 * pin what a client actually sees on the wire.
 */
class RemoteDaemonTest {

    private val fixtures = mutableListOf<DaemonFixture>()
    private val json = Json { encodeDefaults = true }

    @AfterTest
    fun stopAll() {
        fixtures.asReversed().forEach {
            runCatching { it.stop() }
            runCatching { it.deleteState() }
        }
        fixtures.clear()
    }

    /** A daemon requiring [credential], or loopback-only with no credential when it is null. Registered for teardown. */
    private fun launch(
        credential: String?,
        runner: ProcessRunner? = null,
        host: String = "127.0.0.1",
        connectHost: String = "127.0.0.1",
    ): DaemonFixture = DaemonFixture.launch(
        runner = runner,
        host = host,
        connectHost = connectHost,
        token = credential,
    ).also { fixtures += it }

    /** A second WebSocket client on the same daemon, presenting [credential] when given. */
    private fun connectClient(fixture: DaemonFixture, credential: String?): WsTestClient {
        val listener = WsTestClient()
        val builder = HttpClient.newHttpClient().newWebSocketBuilder()
        credential?.let { builder.header("Authorization", "Bearer $it") }
        builder.buildAsync(URI.create("ws://${fixture.connectHost}:${fixture.port}/ws"), listener).join()
        return listener
    }

    /** How the daemon answered a WebSocket upgrade: 101 when it admitted the client, otherwise the refusal. */
    private data class Handshake(val status: Int, val wwwAuthenticate: String?)

    private fun handshake(port: Int, authorization: String? = null, origin: String? = null): Handshake {
        val builder = HttpClient.newHttpClient().newWebSocketBuilder()
        authorization?.let { builder.header("Authorization", it) }
        origin?.let { builder.header("Origin", it) }
        return try {
            val socket = builder.buildAsync(URI.create("ws://127.0.0.1:$port/ws"), WsTestClient()).join()
            socket.sendClose(WebSocket.NORMAL_CLOSURE, "probe").join()
            Handshake(status = 101, wwwAuthenticate = null)
        } catch (e: CompletionException) {
            val refused = e.cause as? WebSocketHandshakeException ?: throw e
            val response = checkNotNull(refused.response) { "a refused handshake carried no response" }
            Handshake(response.statusCode(), response.headers().firstValue("WWW-Authenticate").orElse(null))
        }
    }

    /**
     * The status of one plain HTTP/1.1 request on a raw socket. Raw, because the JDK client forbids setting
     * the Host header, and the Host check is exactly what is under test. HTTP/1.1 requires a Host, so a probe
     * states one unless the caller names its own.
     */
    private fun rawStatus(port: Int, path: String, vararg headers: Pair<String, String>): Int =
        Socket("127.0.0.1", port).use { socket ->
            socket.soTimeout = 5_000
            val withHost = if (headers.any { it.first.equals("Host", ignoreCase = true) }) {
                headers.toList()
            } else {
                listOf("Host" to "127.0.0.1:$port") + headers.toList()
            }
            val request = buildString {
                append("GET ").append(path).append(" HTTP/1.1\r\n")
                for ((name, value) in withHost) append(name).append(": ").append(value).append("\r\n")
                append("Connection: close\r\n\r\n")
            }
            socket.getOutputStream().write(request.toByteArray(Charsets.US_ASCII))
            val statusLine = BufferedReader(InputStreamReader(socket.getInputStream(), Charsets.US_ASCII)).readLine()
            checkNotNull(statusLine) { "the daemon closed the connection without answering" }
            statusLine.split(' ')[1].toInt()
        }

    private fun postJson(port: Int, path: String, body: String, authorization: String?): HttpResponse<String> {
        val request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:$port$path"))
            .header("Content-Type", "application/json")
            .apply { authorization?.let { header("Authorization", it) } }
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build()
        return HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString())
    }

    private fun getText(port: Int, pathAndQuery: String, authorization: String?): HttpResponse<String> {
        val request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:$port$pathAndQuery"))
            .apply { authorization?.let { header("Authorization", it) } }
            .GET()
            .build()
        return HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString())
    }

    /** Wraps a real runner and keeps the spec of every process started: the environment an agent was given. */
    private class RecordingRunner(private val inner: ProcessRunner) : ProcessRunner {
        val specs = CopyOnWriteArrayList<ProcessSpec>()

        override val id: String get() = inner.id

        override fun start(spec: ProcessSpec, listener: ProcessListener): RunningProcess {
            specs += spec
            return inner.start(spec, listener)
        }
    }

    // ----- who may connect -----------------------------------------------------------------------

    @Test
    fun aTokenGatedDaemonRefusesAnAnonymousHandshakeWithABearerChallenge() {
        val fixture = launch(freshToken())
        val refused = handshake(fixture.port)
        assertEquals(401, refused.status)
        assertTrue(refused.wwwAuthenticate?.startsWith("Bearer") == true, "no Bearer challenge: $refused")
    }

    @Test
    fun aWrongOrMalformedCredentialIsRefusedAndTheRightOneIsAdmitted() {
        val token = freshToken()
        val fixture = launch(token)
        assertEquals(401, handshake(fixture.port, authorization = "Bearer ${freshToken()}").status, "a different token")
        assertEquals(401, handshake(fixture.port, authorization = "Basic $token").status, "the wrong scheme")
        assertEquals(401, handshake(fixture.port, authorization = token).status, "the bare token, no scheme")
        assertEquals(101, handshake(fixture.port, authorization = "Bearer $token").status, "the right token")
    }

    @Test
    fun aRequestCarryingAnOriginIsRefusedEvenWithTheRightToken() {
        val token = freshToken()
        val fixture = launch(token)
        val refused = handshake(fixture.port, authorization = "Bearer $token", origin = "https://evil.example")
        assertEquals(403, refused.status, "a browser-originated request is never an API client")
    }

    @Test
    fun anAuthorisedClientIsGreetedWithTheContractVersion() {
        val fixture = launch(freshToken())
        val ready = fixture.client.await(Envelope.READY)
        assertTrue(ready.data.contains(EventVocabulary.VERSION), "READY must name the contract: ${ready.data}")
    }

    @Test
    fun healthIsTheOnlyRouteAnAnonymousCallerReachesAndItNeverEchoesTheToken() {
        val token = freshToken()
        val fixture = launch(token)

        val health = fixture.health()
        assertEquals("ok", health["status"]?.jsonPrimitive?.content)
        assertEquals(EventVocabulary.VERSION, health["contract"]?.jsonPrimitive?.content)
        assertFalse(health.toString().contains(token), "health must never echo the token")

        // The gate runs before routing, so even a path that does not exist is refused to an anonymous caller.
        assertEquals(401, rawStatus(fixture.port, "/does-not-exist"))
        assertEquals(401, rawStatus(fixture.port, "/ws"))
    }

    @Test
    fun aLoopbackDaemonWithNoTokenAdmitsOnlyLoopbackHostNames() {
        val fixture = launch(credential = null)
        val port = fixture.port
        assertEquals(200, rawStatus(port, "/health", "Host" to "127.0.0.1:$port"))
        assertEquals(200, rawStatus(port, "/health", "Host" to "localhost:$port"))
        // A web page under its own hostname (DNS rebinding) reaches this socket under THAT name: refused.
        assertEquals(403, rawStatus(port, "/health", "Host" to "evil.example:$port"))
        assertEquals(403, rawStatus(port, "/health", "Host" to "127.0.0.1:$port", "Origin" to "https://evil.example"))
    }

    @Test
    fun aDaemonThatWouldBeExposedWithoutACredentialOrWithAWeakOneIsNeverBuilt() {
        val exposed = assertFailsWith<AccessConfigurationException> {
            buildWithoutStarting(host = "0.0.0.0", token = null)
        }
        assertTrue(exposed.message!!.contains("without a bearer token"), exposed.message)

        val weak = assertFailsWith<AccessConfigurationException> {
            buildWithoutStarting(host = "127.0.0.1", token = "short")
        }
        assertTrue(weak.message!!.contains("at least"), weak.message)
    }

    /** The real build path up to and including the access check, over throwaway state. Nothing is started. */
    private fun buildWithoutStarting(host: String, token: String?) {
        val stateDir = Files.createTempDirectory("infantry-refuse-test").toFile()
        val store = SqlInfantryStore.open(File(stateDir, "infantry.db").absolutePath)
        val provider = StubProvider(script = StubScript.default(), delayMs = 0)
        try {
            InfantryDaemon.build(
                port = 0,
                host = host,
                provider = provider,
                runner = provider.transport(),
                sessionStore = JsonSessionStore(File(stateDir, "sessions.json")),
                mailStore = JsonMailStore(File(stateDir, "mailbox.json")),
                store = store,
                mailBaseUrl = "http://127.0.0.1:1",
                token = token,
                // Hermetic (BUG-ModelsHeadOfLine): not build()'s builtIn(), which runs this host's `pi --list-models`.
                modelCatalogs = com.superiorsoftllc.infantry.provider.HarnessModelCatalogs.EMPTY,
            )
        } finally {
            store.close()
            stateDir.deleteRecursively()
        }
    }

    // ----- the mail API and the agents' credential ------------------------------------------------

    @Test
    fun theMailApiIsGatedAndARefusedSendNeverReachesTheRecipientsInbox() {
        val token = freshToken()
        val fixture = launch(token)
        val agent = fixture.daemon.manager.startNew("bootstrap")
        val inbox = "/mail/inbox?session=${agent.id}"
        val marker = "gate-marker-" + freshToken()
        val body = json.encodeToString(
            MailSendRequest.serializer(),
            MailSendRequest(from = "human", to = agent.id, body = marker),
        )

        val refused = postJson(fixture.port, "/mail/send", body, authorization = null)
        assertEquals(401, refused.statusCode())
        assertTrue(refused.headers().firstValue("WWW-Authenticate").orElse("").startsWith("Bearer"))
        assertEquals(401, getText(fixture.port, inbox, authorization = null).statusCode(), "reads are gated as well")
        assertFalse(
            getText(fixture.port, inbox, "Bearer $token").body().contains(marker),
            "a refused send must never reach the recipient's inbox",
        )

        val accepted = postJson(fixture.port, "/mail/send", body, authorization = "Bearer $token")
        assertEquals(200, accepted.statusCode(), accepted.body())
        // Positive control: the same message, once authorised, is in the inbox -- so the check above could see it.
        assertTrue(
            getText(fixture.port, inbox, "Bearer $token").body().contains(marker),
            "the authorised message must reach the inbox",
        )
    }

    @Test
    fun aTokenGatedDaemonGivesItsAgentsTheTokenInTheEnvironmentAndALoopbackOneGivesNone() {
        val token = freshToken()
        val gated = RecordingRunner(StubProvider(script = StubScript.default(), delayMs = 0).transport())
        val fixture = launch(token, runner = gated)
        fixture.send(Envelope.PROMPT, "say hello")
        fixture.client.await(Envelope.FINISHED)
        assertEquals(
            token,
            gated.specs.last().env[AccessPolicy.TOKEN_ENV],
            "an agent must receive the token, so delivered mail can authenticate",
        )

        val loopbackRunner = RecordingRunner(StubProvider(script = StubScript.default(), delayMs = 0).transport())
        val loopback = launch(credential = null, runner = loopbackRunner)
        loopback.send(Envelope.PROMPT, "say hello")
        loopback.client.await(Envelope.FINISHED)
        assertFalse(
            loopbackRunner.specs.last().env.containsKey(AccessPolicy.TOKEN_ENV),
            "with no token configured there is no token in an agent's environment",
        )
    }

    // ----- several clients, and resuming a session ------------------------------------------------

    @Test
    fun twoClientsOnOneDaemonEachReceiveTheRunsFrames() {
        val token = freshToken()
        val fixture = launch(token)
        val second = connectClient(fixture, token)
        try {
            second.await(Envelope.READY)
            fixture.send(Envelope.PROMPT, "shared run")
            fixture.client.await(Envelope.FINISHED)
            second.await(Envelope.FINISHED)
        } finally {
            second.close()
        }
    }

    @Test
    fun aReconnectingClientThatNamesItsSessionContinuesThatSessionOnAFreshConnection() {
        val token = freshToken()
        val fixture = launch(token)
        fixture.send(Envelope.PROMPT, "first turn")
        fixture.client.await(Envelope.FINISHED)
        val sessionId = fixture.daemon.manager.list().single().id
        fixture.client.close()

        val fresh = connectClient(fixture, token)
        try {
            fresh.await(Envelope.READY)
            fresh.send(Envelope(Envelope.PROMPT, "second turn", sessionId))
            fresh.await(Envelope.FINISHED)
            val sessions = fixture.daemon.manager.list()
            assertEquals(1, sessions.size, "resume must continue the session, never fork one")
            assertEquals(sessionId, sessions.single().id)
        } finally {
            fresh.close()
        }
    }

    @Test
    fun aPromptNamingAnUnknownSessionIsRefusedAndNeverStartsANewOne() {
        val fixture = launch(freshToken())
        fixture.send(Envelope(Envelope.PROMPT, "orphaned prompt", "no-such-session"))
        val refusal = fixture.client.await(Envelope.ERROR)
        assertTrue(refusal.data.isNotBlank(), "the refusal must say why")
        // SESSION-UNKNOWN-1: the refusal names its session, so a client keeps it in that open session.
        assertEquals("no-such-session", refusal.sessionId)
        assertEquals(0, fixture.daemon.manager.list().size, "an unknown session must not start a new one")
    }

    // ----- THE GATE -------------------------------------------------------------------------------

    /**
     * THE PHASE 7 GATE: a client on one machine spawns, watches and kills an agent on another. The daemon
     * binds this machine's LAN address and the client dials it over the network stack, not loopback.
     * Skipped, and said so, when there is no LAN address. Failed, with the reason, when the LAN address
     * refuses its own connections: a gate that silently passes on a blocked path proves nothing.
     */
    @Test
    fun aClientOnAnotherAddressSpawnsWatchesAndKillsAnAgent() {
        val lan = lanIpv4()
        if (lan == null) {
            println("[skip] no non-loopback IPv4 address on this machine; the LAN gate was not exercised")
            return
        }
        if (!lanPathAnswers(lan)) {
            fail(
                "this machine's LAN address $lan does not accept its own connections (a host firewall is the usual " +
                    "cause); the LAN gate cannot run here",
            )
        }
        val token = freshToken()
        val runner = GatedRunner()
        val fixture = launch(token, runner = runner, host = lan, connectHost = lan)
        val credential = writeTokenFile(token)
        val url = "ws://$lan:${fixture.port}/ws"
        try {
            val submit = runCli("submit", "HANG lan gate pod", "--url", url, "--token-file", credential.path)
            assertEquals(0, submit.exit, submit.err)
            val podId = submit.out.trim()
            fixture.await(what = "the agent to be running on the daemon's machine") { runner.live.get() == 1 }

            val watch = BackgroundCli("watch", podId, "--url", url, "--token-file", credential.path)
            watch.awaitText("[pod] ")

            val cancel = runCli("cancel", podId, "--force", "--url", url, "--token-file", credential.path)
            assertEquals(0, cancel.exit, cancel.err)
            assertEquals(0, watch.finish(), "watch should end on the cancel: ${watch.err.value()}")
            assertTrue(watch.out.value().contains("CANCELLED"), watch.out.value())
            fixture.await(what = "the killed agent to leave the runner") { runner.live.get() == 0 }
        } finally {
            credential.delete()
        }
    }
}
