package com.superiorsoftllc.agenthq.daemon

import com.superiorsoftllc.agenthq.plugin.api.PolicyPreset
import com.superiorsoftllc.agenthq.plugin.api.SeatsSpec
import com.superiorsoftllc.agenthq.policy.ActionCatalog
import com.superiorsoftllc.agenthq.policy.PolicyProvider
import com.superiorsoftllc.agenthq.policy.PolicyRuntime
import com.superiorsoftllc.agenthq.protocol.Envelope
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * POLICY-1 gaps 1 and 2 (PM, ticket-b6446a82): the owner's ONE switch reaches every part of the daemon at runtime, with no
 * restart. A real daemon over a real WebSocket boots `production`, the owner sends `policy_set` to a seated preset and
 * back, and each time: the keystore's `locked`, RunManager's interactive-run refusal, the visitor token's admission (the
 * `/ws` handshake and the public token route) all follow. No env var names the preset; only the switch moves.
 */
class PolicySwitchDaemonTest {
    private val fixtures = ArrayList<DaemonFixture>()
    private val json = Json { ignoreUnknownKeys = true }
    private val http = HttpClient.newHttpClient()
    private val access = "policy-switch-access-token-0123456789abcdef"
    private val visitor = "policy-switch-visitor-token-0123456789abcdef"

    /** A seated preset that keeps reads (and the run start this test probes) and nothing else; no seats to spawn. */
    private val seated = PolicyPreset(
        id = "seated-test", label = "Seated test",
        disable = listOf("*"), allow = listOf("kind:read", "runs.start"),
        reason = "seated test", seats = SeatsSpec(projectName = "Seated test"),
    )

    @AfterTest fun cleanUp() {
        fixtures.forEach { runCatching { it.stop() }; runCatching { it.deleteState() } }
    }

    private fun launch(): DaemonFixture = DaemonFixture.launch(
        token = access,
        policies = { state ->
            PolicyRuntime(
                listOf(PolicyProvider { listOf(seated) }),
                // The deployment's visitor token is configured all along; only the active preset decides its admission.
                { name -> if (name == DemoLock.PUBLIC_TOKEN_ENV) visitor else null },
                File(state, PolicyRuntime.SELECTION_FILE), state,
            )
        },
    ).also { fixtures += it; it.client.await(Envelope.READY) }

    private fun switch(f: DaemonFixture, id: String) {
        f.client.send(Envelope(ActionCatalog.POLICY_SET, id, requestId = "switch:$id"))
        f.await(what = "the policy frame for '$id'") {
            f.client.frames.any { it.type == "policy" && (json.parseToJsonElement(it.data) as JsonObject)["preset"]?.jsonPrimitive?.content == id }
        }
    }

    private fun secretsLocked(f: DaemonFixture, tag: String): Boolean {
        f.client.send(Envelope(Envelope.SECRET_STATUS, "", requestId = tag))
        val state = f.client.awaitWhere("the secret state for $tag") { it.requestId == tag && it.type == Envelope.SECRET_STATE }
        return (json.parseToJsonElement(state.data) as JsonObject)["locked"]?.jsonPrimitive?.boolean ?: false
    }

    private fun interactiveRunRefusal(f: DaemonFixture, tag: String): String? {
        // RunManager's reply carries no requestId: the next run_start refusal after the mark is this one's.
        val mark = f.client.frames.size
        f.client.send(Envelope(Envelope.RUN_START, """{"commandId":"none","interactive":true}""", requestId = tag))
        f.await(what = "the run_start answer for $tag") { f.client.frames.drop(mark).any { it.type == Envelope.ERROR && "run_start refused" in it.data } }
        val answer = f.client.frames.drop(mark).first { it.type == Envelope.ERROR && "run_start refused" in it.data }
        return answer.data.takeIf { "interactive runs are off" in it }
    }

    /** Whether a client presenting only the visitor token completes the `/ws` handshake. */
    private fun visitorAdmitted(f: DaemonFixture): Boolean = runCatching {
        val c = WsTestClient()
        val socket = http.newWebSocketBuilder().header("Authorization", "Bearer $visitor")
            .buildAsync(URI.create("ws://127.0.0.1:${f.port}/ws"), c).join()
        c.await(Envelope.READY)
        socket.sendClose(java.net.http.WebSocket.NORMAL_CLOSURE, "done").join()
    }.isSuccess

    private fun visitorRoute(f: DaemonFixture): HttpResponse<String> = http.send(
        HttpRequest.newBuilder(URI.create("http://127.0.0.1:${f.port}${DemoLock.VISITOR_TOKEN_PATH}")).GET().build(),
        HttpResponse.BodyHandlers.ofString(),
    )

    @Test fun `the one switch locks secrets, interactive runs and admits the visitor token at runtime, and back`() {
        val f = launch()

        // Production: nothing locked, the visitor token is NOT admitted (the normal app never admits it).
        assertFalse(secretsLocked(f, "s1"), "production locks no secrets")
        assertEquals(null, interactiveRunRefusal(f, "r1"), "production refuses no interactive run for the lock")
        assertFalse(visitorAdmitted(f), "production admits the visitor token")
        assertTrue(visitorRoute(f).statusCode() != 200, "production serves the visitor token")

        // The owner flips ONLY the setting to the seated preset: every part follows, no restart.
        switch(f, seated.id)
        assertTrue(secretsLocked(f, "s2"), "the keystore follows the switch (gap 1)")
        assertTrue(interactiveRunRefusal(f, "r2") != null, "RunManager follows the switch (gap 1)")
        assertTrue(visitorAdmitted(f), "visitor admission follows the switch without a restart (gap 2)")
        visitorRoute(f).let { assertEquals(200, it.statusCode()); assertEquals(visitor, it.body()) }

        // And back to production: all of it closes again.
        switch(f, PolicyPreset.PRODUCTION)
        assertFalse(secretsLocked(f, "s3"), "the keystore unlocks on the switch back")
        assertEquals(null, interactiveRunRefusal(f, "r3"))
        assertFalse(visitorAdmitted(f), "the visitor token is refused again after the switch back")
        assertTrue(visitorRoute(f).statusCode() != 200)
    }

    @Test fun `the daemon's plugin view takes the lock it is given over the env (gap 1)`() {
        val state = kotlin.io.path.createTempDirectory("policy-plugins").toFile()
        try {
            val envOn: (String) -> String? = { if (it == DemoLock.ENV) "1" else null }
            assertTrue(DaemonPlugins.resolve(state, env = { null }, log = {}, locked = true).demoLock, "a switched-on lock reaches the plugins")
            assertFalse(DaemonPlugins.resolve(state, env = envOn, log = {}, locked = false).demoLock, "the active preset wins over the env")
            assertTrue(DaemonPlugins.resolve(state, env = envOn, log = {}).demoLock, "no lock given: the env alone, as before")
        } finally {
            state.deleteRecursively()
        }
    }
}
