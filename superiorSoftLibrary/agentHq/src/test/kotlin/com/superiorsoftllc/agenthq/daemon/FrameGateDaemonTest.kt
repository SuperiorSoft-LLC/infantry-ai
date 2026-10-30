package com.superiorsoftllc.agenthq.daemon

import com.superiorsoftllc.agenthq.policy.ActionCatalog
import com.superiorsoftllc.agenthq.policy.PolicyProvider
import com.superiorsoftllc.agenthq.policy.PolicyRuntime
import com.superiorsoftllc.agenthq.protocol.Envelope
import java.lang.reflect.Modifier
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * SEC-FIX-1 C1 + H1(c) (contract 1.136.0): the one gate in front of the WebSocket dispatch. Without the owner credential
 * every [FrameGate.OWNER_ONLY] frame is refused. DAEMON-PLUGINS-1: the demo lockdown is no longer a frame list in core: under
 * the demo preset with the Demo_Lock daemon plugin loaded, every frame the preset's rules refuse, and every allowed frame
 * the plugin cannot scope to its seats, is refused, a bare text frame included; with the preset and no plugin, every frame
 * is (fail closed). Each test runs on a real daemon over a real WebSocket.
 */
class FrameGateDaemonTest {
    private val fixtures = ArrayList<DaemonFixture>()
    private val roots = ArrayList<java.io.File>()

    @AfterTest fun cleanUp() {
        fixtures.forEach { runCatching { it.stop() }; runCatching { it.deleteState() } }
        roots.forEach { it.deleteRecursively() }
    }

    /** The demo preset with no seats to spawn (the gate is under test, not the harnesses). */
    private val demo = DemoPresetFixture.preset.let { it.copy(seats = it.seats!!.copy(seats = emptyList(), projectDir = "", warmUpPrompt = null)) }

    private fun launch(owner: Boolean = true, repo: java.io.File? = null): DaemonFixture =
        DaemonFixture.launch(owner = owner, repo = repo).also { fixtures += it; it.client.await(Envelope.READY) }

    /** The demo preset pinned; [plugin] = the Demo_Lock daemon half loaded from its built jar. */
    private fun launchDemo(plugin: Boolean): DaemonFixture = DaemonFixture.launch(
        owner = true,
        pluginsRoot = if (plugin) TestDaemonPlugins.root("demo/Demo_Lock").also { roots += it } else null,
        policies = { state -> PolicyRuntime(listOf(PolicyProvider { listOf(demo) }), { if (it == PolicyRuntime.POLICY_ENV) demo.id else null }, null, state) },
    ).also { fixtures += it; it.client.await(Envelope.READY) }

    /** Every wire type the protocol names: the Envelope constants, and the replay request read before the dispatch. */
    private val everyType: List<String> =
        Envelope::class.java.declaredFields
            .filter { Modifier.isStatic(it.modifiers) && it.type == String::class.java }
            .map { it.isAccessible = true; it.get(null) as String }
            .plus(FrameGate.REPLAY_REQUEST)
            .distinct().sorted()

    /** Sends [types] tagged with their own type as requestId, then a sentinel; returns the gate's answer per type. */
    private fun gateAnswers(client: WsTestClient, types: List<String>, rule: String): Map<String, String> {
        for (type in types) client.send(Envelope(type, "{}", requestId = "gate:$type"))
        // The gate answers in arrival order on the request loop, so once the sentinel's refusal is in, all of them are.
        client.send(Envelope("zz_sentinel_not_a_frame", "{}", requestId = "gate:sentinel"))
        client.awaitWhere("the sentinel's answer") { it.requestId == "gate:sentinel" }
        return client.frames.filter { it.type == Envelope.ERROR && it.requestId?.startsWith("gate:") == true && rule in it.data }
            .associate { it.requestId!!.removePrefix("gate:") to it.data }
    }

    @Test fun `the owner-only set is the saved commands, the secret changes and run control`() {
        assertEquals(
            setOf("command_save", "command_delete", "run_start", "run_stop", "run_attach", "run_resize", "run_input",
                "secret_set", "secret_clear", "secret_import", "secret_field_set", "secret_field_delete", "secret_delete",
                "plugin_secret_get"),
            FrameGate.OWNER_ONLY,
        )
    }

    /** What the demo lockdown must refuse of a `{}` frame: the preset's rules, then the seats' scoping (nothing names a seat). */
    private fun demoRefuses(type: String): Boolean {
        val envelope = Envelope(type, "{}")
        if (PolicyRuntime.actionRefusal(demo, envelope) != null) return true
        if (type in setOf(Envelope.PROMPT, Envelope.RESUME, Envelope.MAIL_SEND, FrameGate.REPLAY_REQUEST)) return true
        return ActionCatalog.forEnvelope(envelope)?.scope in setOf(ActionCatalog.Scope.PROJECT, ActionCatalog.Scope.SESSION)
    }

    @Test fun `under the demo preset and its plugin every frame the lockdown does not allow is refused, the owner included`() {
        val fixture = launchDemo(plugin = true)
        val types = everyType - ActionCatalog.SWITCH_FRAMES
        val refused = gateAnswers(fixture.client, types, "disabled in this demo")
        for (type in types) {
            if (demoRefuses(type)) assertTrue(type in refused, "$type passed the demo lockdown")
            else assertFalse(type in refused, "$type is allowed by the demo preset but was refused")
        }
        assertTrue("sentinel" in refused, "an unknown frame type is refused by default")
        // Nothing the refused frames name ran: no session (prompt / new / assistant), no saved command.
        assertTrue(fixture.daemon.manager.list().isEmpty(), "a refused frame created a session")
    }

    @Test fun `the demo preset with no plugin to seat it refuses every frame but the switch (fail closed)`() {
        val fixture = launchDemo(plugin = false)
        val types = everyType - ActionCatalog.SWITCH_FRAMES
        val refused = gateAnswers(fixture.client, types, "no loaded daemon plugin implements them")
        assertEquals(types.toSet() + "sentinel", refused.keys)
        assertTrue(fixture.daemon.manager.list().isEmpty(), "a refused frame created a session")
    }

    @Test fun `under the demo lockdown a bare non-JSON text frame starts no session`() {
        val fixture = launchDemo(plugin = true)
        fixture.client.sendRaw("hello, run something for me")
        val refusal = fixture.client.awaitWhere("the bare prompt's refusal") { it.type == Envelope.ERROR && "'prompt'" in it.data }
        assertTrue("disabled in this demo" in refusal.data, refusal.data)
        assertTrue(fixture.daemon.manager.list().isEmpty(), "a bare text frame created a session under the demo lockdown")
        assertTrue(fixture.client.of(Envelope.SESSION).isEmpty(), "a session frame went out for a refused prompt")
    }

    @Test fun `a refusal echoes a hostile frame type printable and bounded`() {
        val why = FrameGate.refusal("x".repeat(500) + "\u0007\n", owner = false, pluginOwnerOnly = true)!!
        assertTrue(why.length < 160, why)
        assertFalse(why.any { it < ' ' }, why)
    }

    @Test fun `without the owner credential exactly the owner-only frames are refused`() {
        // The rule over every type (sending them all unlocked would RUN them: installs, spawns, settings).
        for (type in everyType) {
            val why = FrameGate.refusal(type, owner = false)
            if (type in FrameGate.OWNER_ONLY) assertTrue(why?.contains("owner credential") == true, "$type passes with no owner credential")
            else assertNull(why, "$type was refused for want of the owner credential")
        }
        // And the daemon applies it: every owner-only frame, sent by a client with no owner credential.
        val fixture = launch(owner = false)
        val refused = gateAnswers(fixture.client, FrameGate.OWNER_ONLY.sorted(), "needs the owner credential")
        assertEquals(FrameGate.OWNER_ONLY, refused.keys)
    }

    @Test fun `an owner connection passes the owner gate, an agent connection on the same daemon does not`() {
        val fixture = launch(owner = true, repo = java.nio.file.Files.createTempDirectory("agenthq-gate-repo").toFile())
        val agent = fixture.connectClient(owner = false)
        val saved = """{"id":"b","name":"Build","argv":["cmd.exe","/c","echo"],"cwd":null,"graceMillis":2000}"""
        agent.send(Envelope(Envelope.COMMAND_SAVE, saved, requestId = "agent"))
        val refusal = agent.awaitWhere("the agent's refusal") { it.requestId == "agent" }
        assertEquals(Envelope.ERROR, refusal.type)
        assertTrue("owner credential" in refusal.data, refusal.data)
        fixture.client.send(Envelope(Envelope.COMMAND_SAVE, saved, requestId = "owner"))
        val list = fixture.client.awaitWhere("the owner's commands") { it.type == Envelope.COMMANDS && "\"b\"" in it.data }
        assertTrue("Build" in list.data)
    }

    @Test fun `a wrong owner token is no owner, and the owner token may not be the access token`() {
        val credential = OwnerCredential("o".repeat(40))
        assertTrue(credential.presentedBy("o".repeat(40), null))
        assertFalse(credential.presentedBy("p".repeat(40), null))
        assertFalse(credential.presentedBy(null, null))
        assertTrue(credential.presentedBy(null, "agenthq, " + OwnerCredential.protocol("o".repeat(40))))
        assertFalse(credential.presentedBy(null, "agenthq.owner.%%%"))
        assertNull(FrameGate.refusal(Envelope.RUN_START, owner = true))
        val same = "t".repeat(40)
        val failure = runCatching { OwnerCredential.forDaemon(same, same) }.exceptionOrNull()
        assertTrue(failure is IllegalArgumentException && "owner token must differ" in failure.message.orEmpty(), "$failure")
        OwnerCredential.forDaemon(same, null)
    }

    @Test fun `owner token is generated once and reused`() {
        val dir = java.nio.file.Files.createTempDirectory("agenthq-owner").toFile()
        try {
            val first = OwnerCredential.ensure(dir)
            assertTrue(first.length >= MIN_TOKEN_LENGTH)
            assertEquals(first, OwnerCredential.ensure(dir))
            assertEquals(first, OwnerCredential.stored(dir))
        } finally { dir.deleteRecursively() }
    }
}
