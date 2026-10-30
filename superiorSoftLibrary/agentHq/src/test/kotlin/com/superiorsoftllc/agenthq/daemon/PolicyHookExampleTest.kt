package com.superiorsoftllc.agenthq.daemon

import com.superiorsoftllc.agenthq.daemon.plugins.DaemonPluginHost
import com.superiorsoftllc.agenthq.protocol.Envelope
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** DAEMON-PLUGINS-2: `plugins/examples/Acme_PolicyHook` (policy action + frameGate hook) runs from its built jar as a community module. */
class PolicyHookExampleTest {
    private var fixture: DaemonFixture? = null
    private var root: File? = null

    @AfterTest fun tearDown() {
        fixture?.let { it.stop(); it.deleteState() }
        root?.deleteRecursively()
    }

    @Test fun `the tool reports the policy action and the hook counts frames`() {
        val granted = """{"Acme_PolicyHook":{"enabled":true,"allow":["daemon.code","daemon.tool","daemon.hook.frameGate"]}}"""
        root = TestDaemonPlugins.root("examples/Acme_PolicyHook", folder = "community", tier = "community", modulesJson = granted)
        val f = DaemonFixture.launch(
            stateDir = Files.createTempDirectory("agenthq-policy-hook").toFile(), pluginsRoot = root!!,
            pluginTrust = DaemonPluginHost.Trust(emptyList(), devTrust = false, bundledRoot = null), pluginEnv = { null },
        ).also { fixture = it }
        f.client.await(Envelope.READY)
        val id = "call-1"
        f.send(Envelope(DaemonPluginHost.PLUGIN_TOOL_CALL, """{"name":"acme_policy_check","arguments":{"frame":"sessions"}}""", requestId = id))
        val answer = f.client.awaitWhere("the tool answer") { it.requestId == id }
        assertEquals(DaemonPluginHost.PLUGIN_TOOL_RESULT, answer.type, answer.data)
        val text = Json.parseToJsonElement(answer.data).jsonObject["text"]!!.jsonPrimitive.content
        assertTrue(text.startsWith("sessions: action="), text)
        assertTrue("not refused by preset" in text, text)
        assertTrue("Frames seen:" in text && "none yet" !in text, "the frameGate hook counted the tool call itself: $text")
    }
}
