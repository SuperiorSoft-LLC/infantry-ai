// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.daemon

import com.superiorsoftllc.infantry.plugin.api.PolicyPreset
import com.superiorsoftllc.infantry.plugin.api.SeatsSpec
import com.superiorsoftllc.infantry.policy.PolicyProvider
import com.superiorsoftllc.infantry.policy.PolicyRuntime
import com.superiorsoftllc.infantry.protocol.Envelope
import com.superiorsoftllc.infantry.session.stateDir
import java.io.File
import java.net.URI
import java.net.http.HttpClient
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * DEMO-CHECK-1: the demo booted with NO seated agents. Demo_Lock seated its sessions inside its own `start()`, but the Roles
 * port (Infantry_Roles, which loads after it) and the harness modules (opencode) are only in once every module has loaded, so
 * the seat spawn failed with "unknown role 'backend'" and the visitor found an empty demo. Seating now runs in
 * `DaemonApi.whenStarted` (1.39.0). This boots a real daemon with the Demo_Lock and Infantry_Roles jars (Demo_Lock loads
 * first) and a seated preset whose seat names a role only Infantry_Roles knows.
 */
class SeatsAfterStartDaemonTest {
    private val fixtures = ArrayList<DaemonFixture>()
    private val dirs = ArrayList<File>()

    @AfterTest fun cleanUp() {
        fixtures.forEach { runCatching { it.stop() }; runCatching { it.deleteState() } }
        dirs.forEach { it.deleteRecursively() }
    }

    private fun launchSeated(pluginEnv: (String) -> String? = { null }): DaemonFixture {
        val project = Files.createTempDirectory("seats-after-start").toFile().also { dirs += it }
        val seated = PolicyPreset(
            id = "seated-after-start", label = "Seated after start",
            disable = listOf("*"), allow = listOf("kind:read", "chat.prompt"), reason = "seated test", selectWhenEnv = "SEATS_AFTER_START_TEST",
            seats = SeatsSpec(projectName = "Seated project", projectDir = project.absolutePath, seats = listOf("backend:stub:stub-model")),
        )
        val f = DaemonFixture.launch(
            token = "seats-after-start-access-token-0123456789abcdef",
            policies = { state -> PolicyRuntime(listOf(PolicyProvider { listOf(seated) }), { if (it == "SEATS_AFTER_START_TEST") "1" else null }, File(state, PolicyRuntime.SELECTION_FILE), state) },
            pluginsRoot = TestDaemonPlugins.root("demo/Demo_Lock", "official/Infantry_Roles").also { dirs += it },
            pluginEnv = pluginEnv,
        ).also { fixtures += it }
        f.client.await(Envelope.READY)
        f.await(what = "the seated backend session") { f.daemon.manager.list().any { it.role == "backend" } }
        return f
    }

    @Test fun `a seated preset gets its seat sessions although the Roles plugin loads after the seating plugin`() {
        val f = launchSeated()
        val seats = f.daemon.manager.list().filter { it.role == "backend" }
        assertEquals(1, seats.size, "one session per seat")
        assertTrue(seats.single().projectId != null, "the seat is in the seated project")
    }

    /**
     * DEMO-CHECK-1: the ws connect log read `remoteHost`, a reverse DNS lookup the JVM caches on the socket address, so the
     * Caller's `remoteAddress` became a host name ("localhost") and a loopback reverse proxy was not recognised: every
     * visitor behind Caddy shared ONE per-IP turn budget. A visitor through the loopback proxy is counted under its own IP.
     */
    @Test fun `a visitor behind the loopback proxy is counted under its forwarded address`() {
        val visitor = "seats-after-start-visitor-token-0123456789abcdef"
        val env = mapOf("INFANTRY_DEMO_PUBLIC_TOKEN" to visitor, "INFANTRY_DEMO_TURNS_PER_IP" to "5", "INFANTRY_DEMO_TURN_WINDOW_HOURS" to "1")
        // The plugin data folder sits in the daemon's state dir (the test JVM's isolated one, shared by its fixtures).
        val turns = File(stateDir(), "plugin-data/Demo_Lock/demo-turns.tsv").also { it.delete() }
        val f = launchSeated { env[it] }
        val seat = f.daemon.manager.list().single { it.role == "backend" }.id
        val c = WsTestClient()
        val socket = HttpClient.newHttpClient().newWebSocketBuilder()
            .header("Authorization", "Bearer $visitor").header("X-Forwarded-For", "203.0.113.9")
            .buildAsync(URI.create("ws://127.0.0.1:${f.port}/ws"), c).join()
        c.await(Envelope.READY)
        c.send(Envelope(Envelope.PROMPT, "hello", sessionId = seat))
        f.await(what = "the visitor's counted turn") { turns.isFile && turns.readText().isNotBlank() }
        socket.sendClose(java.net.http.WebSocket.NORMAL_CLOSURE, "done").join()
        val clients = turns.readLines().filter { it.isNotBlank() }.map { it.substringBefore('\t') }.toSet()
        assertEquals(setOf("203.0.113.9"), clients, "the turn is the forwarded client's, not the proxy's")
    }
}
