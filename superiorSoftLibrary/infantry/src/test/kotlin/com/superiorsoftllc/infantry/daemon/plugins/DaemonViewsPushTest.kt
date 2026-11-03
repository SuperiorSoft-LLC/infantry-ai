// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.daemon.plugins

import com.superiorsoftllc.infantry.daemon.TestDaemonPlugins
import com.superiorsoftllc.infantry.daemon.api.Caller
import com.superiorsoftllc.infantry.daemon.api.DaemonApi
import com.superiorsoftllc.infantry.daemon.api.DaemonPlugin
import com.superiorsoftllc.infantry.daemon.api.DaemonTool
import com.superiorsoftllc.infantry.daemon.api.ToolCall
import com.superiorsoftllc.infantry.daemon.api.ToolResult
import com.superiorsoftllc.infantry.daemon.api.ViewReply
import com.superiorsoftllc.infantry.harness.TestHarnessJars
import com.superiorsoftllc.infantry.plugin.host.ModuleResolver
import com.superiorsoftllc.infantry.plugin.host.PluginFolders
import com.superiorsoftllc.infantry.protocol.Envelope
import com.superiorsoftllc.infantry.settings.LimitDef
import com.superiorsoftllc.infantry.settings.LimitRegistry
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** A fake daemon half for [DaemonViewsPushTest]: view `poke` calls `views.changed` for the view named in its params. */
class ViewsPushFakePlugin : DaemonPlugin {
    override fun start(api: DaemonApi) {
        api.views.provide("main") { ViewReply.Page(buildJsonObject { put("type", "box") }) }
        api.views.provide("other") { ViewReply.Page(buildJsonObject { put("type", "box") }) }
        api.views.provide("poke") { req ->
            val target = (req.params["view"] as? JsonPrimitive)?.content ?: "main"
            api.views.changed(target, req.params["for"] as? JsonObject)
            ViewReply.Page(buildJsonObject { put("type", "box") })
        }
        api.tools.register(object : DaemonTool {
            override val name = "acme_ping"
            override val description = "ping"
            override val inputSchema = buildJsonObject { put("type", "object") }
            override fun call(call: ToolCall) = ToolResult("pong")
        })
    }
}

/**
 * DV-1b (docs/DECL-UI-DESIGN.md 2.3-2.5): subscriptions, the coalesced `plugin_view_changed` push, `plugin_view_close`,
 * disconnect, the subscription cap, the origin check on tool calls, and the unload drop.
 */
class DaemonViewsPushTest {
    private val dirs = mutableListOf<File>()
    private val hosts = mutableListOf<DaemonPluginHost>()
    private val module = "Acme_Push"
    private val owner = Caller(owner = true)

    private class Conn { val got = CopyOnWriteArrayList<Envelope>(); val conn = ViewConnection { got += it }
        fun changed() = got.filter { it.type == DaemonPluginHost.PLUGIN_VIEW_CHANGED }.map { Json.parseToJsonElement(it.data).jsonObject } }

    @AfterTest fun cleanup() {
        hosts.forEach { runCatching { it.close() } }
        dirs.forEach { runCatching { it.deleteRecursively() } }
    }

    private fun view(id: String) = """{"id":"$id","title":"T $id","action":"acme.push.read"}"""

    private fun host(coalesceMs: Long = 20, max: Long = 16): DaemonPluginHost {
        val root = Files.createTempDirectory("dv1b-root").toFile().also { dirs += it }
        File(root, "modules.json").writeText("""{"folders":[{"path":"community","tier":"community"}],"modules":{"$module":{"enabled":true,"allow":["daemon.code","daemon.view","daemon.tool"]}}}""")
        val dir = File(root, "community/$module").apply { mkdirs() }
        File(dir, "module.json").writeText("""{"name":"$module","version":"1.0.0","apiVersion":2,"author":"Acme","license":"NOASSERTION","description":"DV-1b fake","daemon":{"entry":"${ViewsPushFakePlugin::class.java.name}","jar":"daemon/push.jar","apiAtLeast":"1.0.0","tools":[{"name":"acme_ping","action":"acme.ping"}],"views":[${listOf("main", "other", "poke").joinToString(",") { view(it) }}]}}""")
        TestHarnessJars.pack(File(dir, "daemon/push.jar"), ViewsPushFakePlugin::class.java, services = emptyMap())
        val services = object : DaemonServices by DaemonServices.NONE {
            override fun limit(def: LimitDef): Long = when (def) {
                LimitRegistry.VIEWS_CHANGED_COALESCE_MS -> coalesceMs
                LimitRegistry.VIEWS_MAX_SUBSCRIPTIONS -> max
                else -> def.default
            }
        }
        val config = PluginFolders.config(root)
        val res = ModuleResolver.resolve(PluginFolders.scan(root, config, null), config, false)
        return DaemonPluginHost.load(res, config, TestDaemonPlugins.trustAll, services, File(root, "state"), {}).also { hosts += it }
    }

    private fun DaemonPluginHost.open(c: Conn, view: String, params: String = "{}", extra: String = ""): JsonObject = runBlocking {
        val e = answerView(Envelope(DaemonPluginHost.PLUGIN_VIEW, """{"module":"$module","view":"$view","params":$params$extra}"""), owner, c.conn)
        assertEquals(DaemonPluginHost.PLUGIN_VIEW_RESULT, e.type, e.data)
        Json.parseToJsonElement(e.data).jsonObject
    }

    private fun DaemonPluginHost.close(c: Conn, view: String, params: String = "{}") =
        closeView(Envelope(DaemonPluginHost.PLUGIN_VIEW_CLOSE, """{"module":"$module","view":"$view","params":$params}"""), c.conn)

    private fun await(what: String, cond: () -> Boolean) {
        val end = System.currentTimeMillis() + 5_000
        while (!cond() && System.currentTimeMillis() < end) Thread.sleep(10)
        assertTrue(cond(), what)
    }

    @Test fun `changed pushes to the subscribed connection only`() {
        val h = host()
        val a = Conn(); val b = Conn(); val poker = Conn()
        h.open(a, "main"); h.open(b, "other"); h.open(poker, "poke", """{"view":"main"}""", ""","subscribe":false""")
        await("a gets the push") { a.changed().isNotEmpty() }
        Thread.sleep(100)
        val p = a.changed().single()
        assertEquals("main", p["view"]!!.jsonPrimitive.content)
        assertEquals(module, p["module"]!!.jsonPrimitive.content)
        assertTrue(p["rev"]!!.jsonPrimitive.content.toLong() > 0)
        assertEquals(emptyList(), b.changed(), "a connection watching another view gets nothing")
        assertEquals(emptyList(), poker.changed(), "subscribe:false subscribes nothing")
    }

    @Test fun `changed with params reaches only that params and null reaches all`() {
        val h = host()
        val a = Conn(); val b = Conn(); val poker = Conn()
        h.open(a, "main", """{"x":1,"y":2}"""); h.open(b, "main", """{"x":9}""")
        h.open(poker, "poke", """{"view":"main","for":{"y":2,"x":1}}""", ""","subscribe":false""")
        await("a gets it (key order does not matter)") { a.changed().isNotEmpty() }
        Thread.sleep(100)
        assertEquals(emptyList(), b.changed())
        h.open(poker, "poke", """{"view":"main"}""", ""","subscribe":false""")
        await("b gets the null-params push") { b.changed().isNotEmpty() }
    }

    @Test fun `calls inside the window collapse into one push`() {
        val h = host(coalesceMs = 400)
        val a = Conn(); val poker = Conn()
        h.open(a, "main")
        repeat(5) { h.open(poker, "poke", """{"view":"main"}""", ""","subscribe":false""") }
        await("the first push") { a.changed().isNotEmpty() }
        await("the trailing push") { a.changed().size >= 2 }
        Thread.sleep(600)
        assertEquals(2, a.changed().size, "one leading and one trailing push for five calls")
    }

    @Test fun `close and disconnect stop pushes`() {
        val h = host()
        val a = Conn(); val b = Conn(); val poker = Conn()
        h.open(a, "main"); h.open(b, "main")
        h.close(a, "main"); h.viewConnectionClosed(b.conn)
        h.open(poker, "poke", """{"view":"main"}""", ""","subscribe":false""")
        Thread.sleep(300)
        assertEquals(emptyList(), a.changed())
        assertEquals(emptyList(), b.changed())
    }

    @Test fun `the oldest subscription is dropped over the cap`() {
        val h = host(max = 2)
        val a = Conn(); val poker = Conn()
        h.open(a, "main", """{"n":1}"""); h.open(a, "main", """{"n":2}"""); h.open(a, "main", """{"n":3}""")
        h.open(poker, "poke", """{"view":"main"}""", ""","subscribe":false""")
        await("pushes arrive") { a.changed().size >= 2 }
        Thread.sleep(100)
        val ns = a.changed().map { it["params"]!!.jsonObject["n"]!!.jsonPrimitive.content }.toSet()
        assertEquals(setOf("2", "3"), ns)
    }

    @Test fun `origin refuses a foreign tool and allows an own tool`() {
        val h = host()
        fun call(name: String, origin: JsonObject?) = runBlocking {
            h.callToolSuspending(name, JsonObject(emptyMap()), owner, JsonObject(emptyMap()), origin) { _, _ -> Envelope(Envelope.ERROR, "none") }
        }
        val from = JsonObject(mapOf("module" to JsonPrimitive(module), "view" to JsonPrimitive("main")))
        val own = call("acme_ping", from)
        assertEquals("pong", own.text, own.text)
        val foreign = call("acme_ping", JsonObject(mapOf("module" to JsonPrimitive("Other_Mod"), "view" to JsonPrimitive("main"))))
        assertTrue(foreign.isError && foreign.text.contains("only its own module's tools"), foreign.text)
        assertEquals("pong", call("acme_ping", null).text, "without origin nothing changes")
        assertTrue(call("infantry_event_emit", from).isError, "a tool no module registered is refused for a view")
    }

    @Test fun `unloading a module pushes a star change and drops its subscriptions`() {
        val h = host()
        val a = Conn(); val lister = Conn()
        h.open(a, "main"); h.viewsResult(owner, lister.conn)
        h.unload(module)
        await("lister and subscriber both told") { a.changed().isNotEmpty() && lister.changed().isNotEmpty() }
        assertEquals("*", lister.changed().last()["view"]!!.jsonPrimitive.content)
        assertEquals(module, lister.changed().last()["module"]!!.jsonPrimitive.content)
    }
}
