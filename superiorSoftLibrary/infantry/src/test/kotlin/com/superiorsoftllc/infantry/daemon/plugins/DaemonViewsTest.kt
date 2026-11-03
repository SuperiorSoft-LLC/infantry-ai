// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.daemon.plugins

import com.superiorsoftllc.infantry.daemon.TestDaemonPlugins
import com.superiorsoftllc.infantry.daemon.api.Caller
import com.superiorsoftllc.infantry.daemon.api.DaemonApi
import com.superiorsoftllc.infantry.daemon.api.DaemonPlugin
import com.superiorsoftllc.infantry.daemon.api.Frame
import com.superiorsoftllc.infantry.daemon.api.Policy
import com.superiorsoftllc.infantry.daemon.api.ViewReply
import com.superiorsoftllc.infantry.harness.TestHarnessJars
import com.superiorsoftllc.infantry.plugin.host.ModuleResolver
import com.superiorsoftllc.infantry.plugin.host.PluginFolders
import com.superiorsoftllc.infantry.protocol.Envelope
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** A fake daemon half for [DaemonViewsTest]: it is packed into a jar and loaded by the host like a real plugin. */
class ViewsFakePlugin : DaemonPlugin {
    override fun start(api: DaemonApi) {
        // An undeclared id is refused (and logged); the plugin survives it and reports what it saw.
        val ghostRefused = runCatching { api.views.provide("ghost") { ViewReply.Page(JsonObject(emptyMap())) } }.isFailure
        api.views.provide("main") { req ->
            ViewReply.Page(buildJsonObject {
                put("type", "box"); put("ghostRefused", ghostRefused); put("echo", req.params["q"] ?: JsonPrimitive("none"))
            })
        }
        api.views.provide("nope") { ViewReply.Refused("not today\nsecond line") }
        api.views.provide("wide") { ViewReply.Page(buildJsonObject { put("kids", JsonArray(List(600) { JsonObject(emptyMap()) })) }) }
        api.views.provide("deep") {
            var node: JsonObject = JsonObject(emptyMap())
            repeat(40) { node = buildJsonObject { put("c", node) } }
            ViewReply.Page(node)
        }
        api.views.provide("fat") { ViewReply.Page(buildJsonObject { put("text", "x".repeat(300_000)) }) }
    }
}

/**
 * DV-1a (docs/DECL-UI-DESIGN.md 2.2-2.5): the daemon's view seam on a host loading a fake plugin from a jar: a declared view is
 * answered, an undeclared one refused, a static layout is served (and may not escape its folder), the page limits refuse,
 * the policy and the community grant gate. The daemon never learns a widget name: pages are opaque JSON.
 */
class DaemonViewsTest {
    private val dirs = mutableListOf<File>()
    private val hosts = mutableListOf<DaemonPluginHost>()
    private val module = "Acme_Views"
    private val owner = Caller(owner = true)

    @AfterTest fun cleanup() {
        hosts.forEach { runCatching { it.close() } }
        dirs.forEach { runCatching { it.deleteRecursively() } }
    }

    private fun view(id: String, extra: String = "") = """{"id":"$id","title":"T $id","action":"acme.views.read"$extra}"""
    private val allViews = listOf(view("main"), view("nope"), view("wide"), view("deep"), view("fat"), view("hidden", ""","place":"none"""")).joinToString(",")

    /** A plugins root with [module]: its module.json, a fake-plugin jar when [withCode], and [files] (relative path -> text). */
    private fun root(views: String = allViews, withCode: Boolean = true, grants: String = """["daemon.code","daemon.view"]""", files: Map<String, String> = emptyMap()): File {
        val root = Files.createTempDirectory("dv1a-root").toFile().also { dirs += it }
        File(root, "modules.json").writeText("""{"folders":[{"path":"community","tier":"community"}],"modules":{"$module":{"enabled":true,"allow":$grants}}}""")
        val dir = File(root, "community/$module").apply { mkdirs() }
        val daemon = if (withCode) """"entry":"${ViewsFakePlugin::class.java.name}","jar":"daemon/views.jar","apiAtLeast":"1.0.0",""" else ""
        File(dir, "module.json").writeText("""{"name":"$module","version":"1.0.0","apiVersion":2,"author":"Acme","license":"NOASSERTION","description":"DV-1a fake","daemon":{$daemon"views":[$views]}}""")
        if (withCode) TestHarnessJars.pack(File(dir, "daemon/views.jar"), ViewsFakePlugin::class.java, services = emptyMap())
        files.forEach { (rel, text) -> File(dir, rel).apply { parentFile.mkdirs() }.writeText(text) }
        return root
    }

    private fun host(root: File, services: DaemonServices = DaemonServices.NONE): DaemonPluginHost {
        val config = PluginFolders.config(root)
        val res = ModuleResolver.resolve(PluginFolders.scan(root, config, null), config, false)
        return DaemonPluginHost.load(res, config, TestDaemonPlugins.trustAll, services, File(root, "state"), {}).also { hosts += it }
    }

    private fun DaemonPluginHost.open(view: String, params: String = "{}", caller: Caller = owner): JsonObject = runBlocking {
        val e = answerView(Envelope(DaemonPluginHost.PLUGIN_VIEW, """{"module":"$module","view":"$view","params":$params}""", requestId = "r1"), caller)
        assertEquals(DaemonPluginHost.PLUGIN_VIEW_RESULT, e.type, e.data)
        assertEquals("r1", e.requestId)
        Json.parseToJsonElement(e.data).jsonObject
    }

    private fun JsonObject.error() = this["error"]?.jsonPrimitive?.content

    @Test fun `a declared view is answered with the plugin's page, its params and a rev`() {
        val h = host(root())
        assertEquals("loaded", h.status(module))
        val a = h.open("main", """{"q":"hi"}""")
        assertNull(a.error(), a.toString())
        assertEquals("box", a["page"]!!.jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals("hi", a["page"]!!.jsonObject["echo"]!!.jsonPrimitive.content)
        assertEquals("hi", a["params"]!!.jsonObject["q"]!!.jsonPrimitive.content)
        val b = h.open("main")
        assertTrue(b["rev"]!!.jsonPrimitive.content.toLong() > a["rev"]!!.jsonPrimitive.content.toLong(), "rev only grows")
    }

    @Test fun `an undeclared view id is refused at provide and unknown at open`() {
        val h = host(root())
        assertEquals("loaded", h.status(module), "a refused registration does not take the plugin down")
        assertEquals("true", h.open("main")["page"]!!.jsonObject["ghostRefused"]!!.jsonPrimitive.content)
        assertTrue(h.open("ghost").error()!!.contains("no view 'ghost'"))
    }

    @Test fun `a provider's refusal is one line`() {
        val e = host(root()).open("nope").error()!!
        assertEquals("plugin_view refused: not today", e)
    }

    @Test fun `plugin_views lists the declared views of the loaded module`() {
        val h = host(root())
        val list = Json.parseToJsonElement(h.viewsResult(owner).data).jsonObject["views"]!!.jsonArray.map { it.jsonObject }
        assertEquals(setOf("main", "nope", "wide", "deep", "fat", "hidden"), list.map { it["id"]!!.jsonPrimitive.content }.toSet())
        assertTrue(list.all { it["module"]!!.jsonPrimitive.content == module })
        assertEquals("none", list.first { it["id"]!!.jsonPrimitive.content == "hidden" }["place"]!!.jsonPrimitive.content)
        assertEquals(DaemonPluginHost.PLUGIN_VIEWS_RESULT, h.viewsResult(owner).type)
    }

    @Test fun `a code-free module serves its static layout, and the file may not leave the module folder`() {
        val views = listOf(view("about", ""","layout":"layout/about.json""""), view("evil", ""","layout":"../outside.json""""), view("bare")).joinToString(",")
        val r = root(views, withCode = false, files = mapOf("layout/about.json" to """{"type":"text","text":"hi"}"""))
        File(r, "community/outside.json").writeText("""{"secret":true}""")
        val h = host(r)
        val about = h.open("about")
        assertNull(about.error(), about.toString())
        assertEquals("hi", about["page"]!!.jsonObject["text"]!!.jsonPrimitive.content)
        assertTrue(h.open("evil").error()!!.contains("not a file inside the module folder"))
        assertTrue(h.open("bare").error()!!.contains("no provider and no layout"))
    }

    @Test fun `a registered provider wins over the layout file`() {
        val r = root(allViews.replace("""{"id":"main",""", """{"layout":"layout/main.json","id":"main","""), files = mapOf("layout/main.json" to """{"type":"file"}"""))
        assertEquals("box", host(r).open("main")["page"]!!.jsonObject["type"]!!.jsonPrimitive.content)
    }

    @Test fun `a page over a limit is refused with one line naming the limit`() {
        val h = host(root())
        assertTrue(h.open("wide").error()!!.contains("limits.views.maxNodes"))
        assertTrue(h.open("deep").error()!!.contains("limits.views.maxDepth"))
        assertTrue(h.open("fat").error()!!.contains("limits.views.maxPageBytes"))
        assertNull(h.open("main").error())
    }

    @Test fun `the active policy refuses a view by its declared action, and the list omits it`() {
        val refusing = object : DaemonServices by DaemonServices.NONE {
            override fun policy(): Policy = object : Policy by DaemonServices.OPEN_POLICY {
                override fun refusal(frame: Frame): String? = "plugin_view refused: policy denies".takeIf { frame.type == DaemonPluginHost.PLUGIN_VIEW }
            }
        }
        val h = host(root(), refusing)
        assertEquals("plugin_view refused: policy denies", h.open("main").error())
        assertEquals(emptyList(), Json.parseToJsonElement(h.viewsResult(owner).data).jsonObject["views"]!!.jsonArray.toList())
        // The action the policy sees is the view's own declared action, kind read.
        val action = h.actionFor(Envelope(DaemonPluginHost.PLUGIN_VIEW, """{"module":"$module","view":"main"}"""))
        assertNotNull(action)
        assertEquals("acme.views.read", action.id)
        assertEquals("read", action.kind)
    }

    @Test fun `a community module without the daemon view grant is refused`() {
        val h = host(root(grants = """["daemon.code"]"""))
        assertTrue(h.status(module)!!.contains("daemon.view"), h.status(module))
        assertTrue(h.open("main").error()!!.contains("no view"))
        // The same module is refused when it borrows a core action id.
        val core = host(root(views = view("main").replace("acme.views.read", "sessions.read")))
        assertTrue(core.status(module)!!.contains("core action"), core.status(module))
    }

    @Test fun `a code-free community module needs the grant too`() {
        val r = root(view("about", ""","layout":"a.json""""), withCode = false, grants = """[]""", files = mapOf("a.json" to "{}"))
        val h = host(r)
        assertTrue(h.open("about").error()!!.contains("no view"), "refused module serves nothing")
        assertEquals(listOf<String>(), Json.parseToJsonElement(h.viewsResult(owner).data).jsonObject["views"]!!.jsonArray.map { it.toString() })
    }
}
