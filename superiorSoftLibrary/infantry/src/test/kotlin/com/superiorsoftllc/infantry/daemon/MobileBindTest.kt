// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.daemon

import com.superiorsoftllc.infantry.cli.freshToken
import com.superiorsoftllc.infantry.mailbox.JsonMailStore
import com.superiorsoftllc.infantry.persistence.SqlInfantryStore
import com.superiorsoftllc.infantry.provider.HarnessModelCatalogs
import com.superiorsoftllc.infantry.provider.StubProvider
import com.superiorsoftllc.infantry.runner.StubScript
import com.superiorsoftllc.infantry.session.JsonSessionStore
import java.io.File
import java.net.URI
import java.net.http.HttpClient
import java.net.http.WebSocket
import java.net.http.WebSocketHandshakeException
import java.nio.file.Files
import java.util.concurrent.CompletionException
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * MOBILE-BIND-1: a phone or another PC (e.g. over Tailscale) reaches a daemon bound beyond loopback.
 * The four acceptance cases, over the real build path and real sockets, on ephemeral ports only:
 *  1. bound to 0.0.0.0 with no token, the daemon is never built (so it never listens);
 *  2. bound to 0.0.0.0 with a token, a WebSocket upgrade without the token is refused (401);
 *  3. ... and one carrying it is admitted (101);
 *  4. with no host given, the daemon binds loopback.
 */
class MobileBindTest {

    private val fixtures = mutableListOf<DaemonFixture>()

    @AfterTest
    fun stopAll() {
        fixtures.asReversed().forEach {
            runCatching { it.stop() }
            runCatching { it.deleteState() }
        }
        fixtures.clear()
    }

    @Test
    fun aWildcardBindWithoutATokenRefusesToStart() {
        val refused = assertFailsWith<AccessConfigurationException>(
            "a daemon bound to 0.0.0.0 with no token was built, so it would start and accept connections",
        ) {
            withThrowawayDaemon(host = "0.0.0.0", token = null) { }
        }
        assertTrue(refused.message!!.contains("without a bearer token"), refused.message)
    }

    @Test
    fun aWildcardBindWithATokenRefusesAnAnonymousUpgradeAndAdmitsTheToken() {
        val token = freshToken()
        val fixture = DaemonFixture.launch(host = "0.0.0.0", connectHost = "127.0.0.1", token = token)
            .also { fixtures += it }
        assertEquals(401, upgrade(fixture.port, authorization = null), "a client without the token")
        assertEquals(401, upgrade(fixture.port, authorization = "Bearer ${freshToken()}"), "a client with a wrong token")
        assertEquals(101, upgrade(fixture.port, authorization = "Bearer $token"), "a client with the token")
    }

    @Test
    fun theDefaultBindIsLoopback() {
        // Only meaningful when the test JVM itself is not configured to bind elsewhere.
        if (System.getenv("INFANTRY_HOST") != null) {
            println("[skip] INFANTRY_HOST is set in the test environment; the default was not exercised")
            return
        }
        withThrowawayDaemon(host = null, token = null) { daemon ->
            assertEquals("127.0.0.1", daemon.host, "the default bind address")
            assertTrue(daemon.policy.loopbackBind, "the default bind must be loopback-only")
            assertTrue(!daemon.policy.tokenRequired, "a loopback default needs no token")
        }
    }

    /** The WebSocket upgrade status for one client: 101 when admitted, otherwise the refusal's status. */
    private fun upgrade(port: Int, authorization: String?): Int {
        val builder = HttpClient.newHttpClient().newWebSocketBuilder()
        authorization?.let { builder.header("Authorization", it) }
        return try {
            val socket = builder.buildAsync(URI.create("ws://127.0.0.1:$port/ws"), WsTestClient()).join()
            socket.sendClose(WebSocket.NORMAL_CLOSURE, "probe").join()
            101
        } catch (e: CompletionException) {
            val refused = e.cause as? WebSocketHandshakeException ?: throw e
            refused.response.statusCode()
        }
    }

    /** The real build path over throwaway state on an ephemeral port; [host] null = build's own default. */
    private fun withThrowawayDaemon(host: String?, token: String?, use: (InfantryDaemon) -> Unit) {
        val stateDir = Files.createTempDirectory("infantry-mobile-bind").toFile()
        val store = SqlInfantryStore.open(File(stateDir, "infantry.db").absolutePath)
        val provider = StubProvider(script = StubScript.default(), delayMs = 0)
        val sessions = JsonSessionStore(File(stateDir, "sessions.json"))
        val mail = JsonMailStore(File(stateDir, "mailbox.json"))
        try {
            val daemon = if (host == null) {
                InfantryDaemon.build(
                    port = 0, provider = provider, runner = provider.transport(), sessionStore = sessions,
                    mailStore = mail, store = store, mailBaseUrl = "http://127.0.0.1:1", token = token,
                    modelCatalogs = HarnessModelCatalogs.EMPTY,
                )
            } else {
                InfantryDaemon.build(
                    port = 0, host = host, provider = provider, runner = provider.transport(), sessionStore = sessions,
                    mailStore = mail, store = store, mailBaseUrl = "http://127.0.0.1:1", token = token,
                    modelCatalogs = HarnessModelCatalogs.EMPTY,
                )
            }
            try {
                use(daemon)
            } finally {
                daemon.close()
            }
        } finally {
            runCatching { store.close() }
            stateDir.deleteRecursively()
        }
    }
}
