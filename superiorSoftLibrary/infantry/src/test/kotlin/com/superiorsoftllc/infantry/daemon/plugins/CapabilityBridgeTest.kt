// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.daemon.plugins

import com.superiorsoftllc.infantry.daemon.TestDaemonPlugins
import com.superiorsoftllc.infantry.daemon.api.Caller
import com.superiorsoftllc.infantry.daemon.api.DaemonApi
import com.superiorsoftllc.infantry.daemon.api.DaemonPlugin
import com.superiorsoftllc.infantry.daemon.api.ToolInvoker
import com.superiorsoftllc.infantry.daemon.api.capabilities.CapabilityResult
import com.superiorsoftllc.infantry.plugin.api.CapabilityCallFrame
import com.superiorsoftllc.infantry.plugin.api.CapabilityDeclaration
import com.superiorsoftllc.infantry.plugin.api.CapabilityEventFrame
import com.superiorsoftllc.infantry.plugin.api.CapabilityHosting
import com.superiorsoftllc.infantry.plugin.api.CapabilityResultFrame
import com.superiorsoftllc.infantry.plugin.api.CapabilityRuns
import com.superiorsoftllc.infantry.plugin.api.CapabilityWire
import com.superiorsoftllc.infantry.plugin.api.DaemonCapabilities
import com.superiorsoftllc.infantry.plugin.api.EmitDeclaration
import com.superiorsoftllc.infantry.plugin.api.HostedModule
import com.superiorsoftllc.infantry.plugin.api.ModuleManifest
import com.superiorsoftllc.infantry.plugin.api.ModuleSetting
import com.superiorsoftllc.infantry.plugin.api.Tier
import com.superiorsoftllc.infantry.plugin.host.ModuleResolver
import com.superiorsoftllc.infantry.plugin.host.PluginFolders
import com.superiorsoftllc.infantry.plugin.host.PluginJson
import com.superiorsoftllc.infantry.protocol.Envelope
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.concurrent.thread
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * PLUGIN-BRIDGES-1: the capability bridge. Manifest parse, the registry's install / remove, the one call tool reaching a daemon-side
 * capability and a fake client host (answer, timeout, no host, disconnect), the grant gate on a plugin-to-plugin call, the declared
 * action as the policy action, and a client's declared event on the event bus.
 */
class CapabilityBridgeTest {
    private val hosts = mutableListOf<DaemonPluginHost>()
    @AfterTest fun cleanup() { hosts.forEach { runCatching { it.close() } } }

    private fun host() = DaemonPluginHost.empty().also { hosts += it }
    private val owner = ToolInvoker(Caller(owner = true))
    private fun obj(json: String) = Json.parseToJsonElement(json).jsonObject

    private fun manifest(name: String, vararg caps: CapabilityDeclaration, emits: List<String> = emptyList(), daemon: Boolean = true) = ModuleManifest(
        name = name, version = "1.0.0", license = "Apache-2.0",
        daemon = if (daemon) DaemonCapabilities() else null,
        capabilities = caps.toList(), emits = emits.map { EmitDeclaration(it) },
    )

    private fun DaemonPluginHost.callTool(capability: String, args: String = "{}", target: String? = null) = invokeTool(
        DaemonPluginHost.CAPABILITY_CALL_TOOL,
        JsonObject(buildMap {
            put("capability", JsonPrimitive(capability)); put("args", obj(args))
            target?.let { put("target", JsonPrimitive(it)) }
        }),
        owner,
    )

    /** A daemon half that answers `acme.echo` with its args plus who called. */
    private class Echo : DaemonPlugin {
        override fun start(api: DaemonApi) {
            api.capabilities.handle("acme.echo") { c -> JsonObject(c.args + ("source" to JsonPrimitive(c.source))) }
        }
    }

    /** A daemon half that only keeps its api, so the test calls other plugins' capabilities as it. */
    private class Caller2 : DaemonPlugin {
        lateinit var api: DaemonApi
        override fun start(api: DaemonApi) { this.api = api }
    }

    @Test fun `module json capabilities and emits parse, and the proof plugin declares plugins status`() {
        val m = PluginJson.decodeFromString(ModuleManifest.serializer(), File(TestDaemonPlugins.repo, "plugins/official/Infantry_Plugins/module.json").readText())
        val c = m.capabilities.single()
        assertEquals("plugins.status", c.id)
        assertEquals(CapabilityRuns.DAEMON, c.runs)
        assertEquals("plugins.read", c.actionId)
        assertEquals("read", c.kind)
        assertNotNull(c.params)
        val parsed = PluginJson.decodeFromString(ModuleManifest.serializer(), """{"name":"Acme_X","version":"1.0.0",
            "capabilities":[{"id":"acme.say","runs":"client","grant":"acme.say.use","timeoutMs":500}],"emits":[{"event":"acme.said"}]}""")
        assertEquals("client", parsed.capabilities.single().runs)
        assertEquals("capability.acme.say", parsed.capabilities.single().actionId, "no action = capability.<id>")
        assertEquals("acme.said", parsed.emits.single().event)
    }

    @Test fun `the proof plugin's built daemon half answers plugins status through the call tool`() {
        val root = TestDaemonPlugins.root("official/Infantry_Plugins")
        try {
            val config = PluginFolders.config(root)
            val h = DaemonPluginHost.load(ModuleResolver.resolve(PluginFolders.scan(root, config, null), config, false), config, TestDaemonPlugins.trustAll, DaemonServices.NONE, File(root, "state"), {})
                .also { hosts += it }
            assertEquals("loaded", h.status("Infantry_Plugins"))
            val r = h.callTool("plugins.status", """{"id":"Nope_Missing"}""")
            assertFalse(r.isError, r.text)
            assertEquals("daemon:Infantry_Plugins", obj(r.text)["host"]!!.jsonPrimitive.content)
            assertEquals("false", obj(r.text)["result"]!!.jsonObject["installed"]!!.jsonPrimitive.content)
            val bad = h.callTool("plugins.status")
            assertTrue(bad.isError && "id is required" in bad.text, bad.text)
        } finally {
            hosts.forEach { runCatching { it.close() } }; hosts.clear()
            root.deleteRecursively()
        }
    }

    @Test fun `the registry installs declarations, refuses bad and duplicate ones, and a removed module offers nothing`() {
        val r = CapabilityRegistry({ 1_000 }, { 5_000 }, {})
        val problems = r.install(listOf(
            "Acme_A" to manifest("Acme_A", CapabilityDeclaration("acme.one"), CapabilityDeclaration("Bad Id"), emits = listOf("acme.happened", "Nope")),
            "Acme_B" to manifest("Acme_B", CapabilityDeclaration("acme.one"), CapabilityDeclaration("acme.two", runs = "elsewhere"), CapabilityDeclaration("acme.three", runs = "client")),
        ))
        assertEquals(4, problems.size, problems.toString())
        assertEquals(listOf("acme.one", "acme.three"), r.list().map { it.id })
        assertEquals("Acme_A", r.declaration("acme.one")?.module, "the first declaration wins")
        assertTrue(r.emits("Acme_A", "acme.happened"))
        assertFalse(r.emits("Acme_A", "Nope"))
        r.dropModule("Acme_A")
        assertEquals(listOf("acme.three"), r.list().map { it.id })
        assertFalse(r.emits("Acme_A", "acme.happened"))
    }

    @Test fun `the call tool reaches a daemon-side capability, and an unloaded plugin's capability is gone`() {
        val h = host()
        assertEquals("loaded", h.startInProcess(manifest("Acme_Echo", CapabilityDeclaration("acme.echo", action = "acme.read", kind = "read")), Tier.OFFICIAL, ModuleSetting(), Echo()))
        val r = h.callTool("acme.echo", """{"x":1}""")
        assertFalse(r.isError, r.text)
        val out = obj(r.text)
        assertEquals("daemon:Acme_Echo", out["host"]!!.jsonPrimitive.content)
        assertEquals("1", out["result"]!!.jsonObject["x"]!!.jsonPrimitive.content)
        assertEquals("cli", out["result"]!!.jsonObject["source"]!!.jsonPrimitive.content)
        val listed = h.invokeTool(DaemonPluginHost.CAPABILITY_LIST_TOOL, JsonObject(emptyMap()), owner)
        assertTrue("acme.echo" in listed.text && "\"available\":true" in listed.text, listed.text)
        // the policy action of a call is the capability's declared one
        val payload = """{"name":"${DaemonPluginHost.CAPABILITY_CALL_TOOL}","arguments":{"capability":"acme.echo"}}"""
        assertEquals("acme.read", h.actionFor(Envelope(DaemonPluginHost.PLUGIN_TOOL_CALL, payload))?.id)
        assertTrue(h.unload("Acme_Echo").ok)
        val gone = h.callTool("acme.echo")
        assertTrue(gone.isError && "unknown capability" in gone.text, gone.text)
    }

    @Test fun `a client-side capability is answered by a host, a silent host times out, no host fails at once, a disconnect fails the wait`() {
        val h = host()
        h.capabilities.install(listOf("Acme_Say" to manifest("Acme_Say", CapabilityDeclaration("acme.say", runs = "client", timeoutMs = 300), daemon = false)))
        val t0 = System.currentTimeMillis()
        val none = h.callTool("acme.say", """{"text":"hi"}""")
        assertTrue(none.isError && "no host" in none.text, none.text)
        assertTrue(System.currentTimeMillis() - t0 < 250, "no host is an error at once, not a wait")

        val hosting = CapabilityHosting("desktop", "test", listOf(HostedModule("Acme_Say", listOf(CapabilityDeclaration("acme.say", runs = "client")))))
        val answering = Any()
        val seen = CopyOnWriteArrayList<CapabilityCallFrame>()
        val hostId = h.capabilities.host(answering, hosting) { type, data ->
            assertEquals(CapabilityWire.CALL, type)
            val call = CapabilityWire.call(data)!!.also { seen += it }
            thread { h.capabilities.result(answering, CapabilityResultFrame(call.callId, ok = true, result = JsonObject(mapOf("spoke" to (call.args["text"] ?: JsonPrimitive("")))))) }
            true
        }
        val ok = h.callTool("acme.say", """{"text":"hi"}""")
        assertFalse(ok.isError, ok.text)
        assertEquals(hostId, obj(ok.text)["host"]!!.jsonPrimitive.content)
        assertEquals("hi", obj(ok.text)["result"]!!.jsonObject["spoke"]!!.jsonPrimitive.content)
        assertEquals(1, seen.size)
        assertTrue(h.callTool("acme.say", "{}", target = "nobody-1").let { it.isError && "not connected" in it.text })

        // a second, silent host: targeting it times out at the capability's timeout
        val silent = Any()
        val silentId = h.capabilities.host(silent, hosting.copy(platform = "android")) { _, _ -> true }
        val t1 = System.currentTimeMillis()
        val late = h.callTool("acme.say", "{}", target = silentId)
        assertTrue(late.isError && "did not answer" in late.text, late.text)
        assertTrue(System.currentTimeMillis() - t1 in 250..5_000)
        // target all: one row per host, the silent one failed
        val all = h.callTool("acme.say", """{"text":"x"}""", target = "all")
        assertFalse(all.isError, all.text)
        assertTrue("\"ok\":true" in all.text && "\"ok\":false" in all.text, all.text)
        // the default target is the desktop host even when the android one was active later
        h.capabilities.touch(silent)
        assertEquals(hostId, obj(h.callTool("acme.say", "{}").text)["host"]!!.jsonPrimitive.content)
        // a disconnect while waiting fails the call at once
        thread { Thread.sleep(50); h.capabilities.disconnected(silent) }
        val t2 = System.currentTimeMillis()
        val dropped = h.callTool("acme.say", "{}", target = silentId)
        assertTrue(dropped.isError && "disconnected" in dropped.text, dropped.text)
        assertTrue(System.currentTimeMillis() - t2 < 250)
        h.capabilities.disconnected(answering)
        assertTrue(h.callTool("acme.say").let { it.isError && "no host" in it.text })
    }

    @Test fun `a plugin calls another plugin's capability through the host, and a community caller needs the declared grant`() {
        val h = host()
        h.startInProcess(manifest("Acme_Echo", CapabilityDeclaration("acme.echo", grant = "acme.echo.use")), Tier.OFFICIAL, ModuleSetting(), Echo())
        val trusted = Caller2()
        h.startInProcess(manifest("Infantry_Other"), Tier.OFFICIAL, ModuleSetting(), trusted)
        val r = trusted.api.capabilities.call("acme.echo", obj("""{"n":"5"}"""))
        assertTrue(r is CapabilityResult.Ok, r.toString())
        assertEquals("plugin:Infantry_Other", (r.value as JsonObject)["source"]!!.jsonPrimitive.content)

        val ungranted = Caller2()
        assertEquals("loaded", h.startInProcess(manifest("Comm_Plain"), Tier.COMMUNITY, ModuleSetting(enabled = true, allow = listOf("daemon.code")), ungranted))
        val refused = ungranted.api.capabilities.call("acme.echo")
        assertTrue(refused is CapabilityResult.Failed && "lacks the grant 'acme.echo.use'" in refused.error, refused.toString())

        val granted = Caller2()
        h.startInProcess(manifest("Comm_Granted"), Tier.COMMUNITY, ModuleSetting(enabled = true, allow = listOf("daemon.code", "acme.echo.use")), granted)
        assertTrue(granted.api.capabilities.call("acme.echo") is CapabilityResult.Ok)
        // a capability handler can only be registered for an id the module itself declares
        assertTrue(runCatching { granted.api.capabilities.handle("acme.echo") { JsonObject(emptyMap()) } }.isFailure)
    }

    @Test fun `a client's declared event is accepted and an undeclared one or one of a module it does not host is refused`() {
        val h = host()
        val conn = Any()
        h.capabilities.host(conn, CapabilityHosting("desktop", "t", listOf(HostedModule("Acme_Say", emits = listOf(EmitDeclaration("acme.said")))))) { _, _ -> true }
        val got = CopyOnWriteArrayList<String>()
        h.namedEvents.subscribe("acme.*") { got += "${it.name} ${it.source} ${it.payload}" }
        val ok = h.capabilities.clientEvent(conn, CapabilityEventFrame("Acme_Say", "acme.said", obj("""{"chars":3}""")))
        assertTrue(ok.isSuccess)
        val (module, name) = ok.getOrThrow()
        h.publishEvent(name, """{"chars":3}""", source = "plugin:$module")
        assertTrue(h.capabilities.clientEvent(conn, CapabilityEventFrame("Acme_Say", "acme.other")).isFailure)
        assertTrue(h.capabilities.clientEvent(conn, CapabilityEventFrame("Acme_Else", "acme.said")).isFailure)
        assertTrue(h.capabilities.clientEvent(Any(), CapabilityEventFrame("Acme_Say", "acme.said")).isFailure)
        val deadline = System.currentTimeMillis() + 2_000
        while (got.isEmpty() && System.currentTimeMillis() < deadline) Thread.sleep(20)
        assertEquals(listOf("""acme.said plugin:Acme_Say {"chars":3}"""), got.toList())
    }
}
