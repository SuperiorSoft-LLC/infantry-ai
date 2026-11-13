// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.plugins.official.scripting.daemon

import com.superiorsoftllc.infantry.daemon.api.Caller
import com.superiorsoftllc.infantry.daemon.api.Frame
import com.superiorsoftllc.infantry.daemon.api.NamedEvent
import com.superiorsoftllc.infantry.daemon.api.ToolCall
import com.superiorsoftllc.infantry.daemon.api.files.DirectoryProjectFiles
import com.superiorsoftllc.infantry.daemon.api.plugins.LoadedModule
import com.superiorsoftllc.infantry.plugin.api.ModuleManifest
import com.superiorsoftllc.infantry.plugin.api.ScriptDeclaration
import com.superiorsoftllc.infantry.plugin.api.ScriptHookDeclaration
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** PLUGIN-SCRIPTS-1: scripts a module ships: seeded into the plugin scope, edits kept, disabled (not deleted) with the module, hooks live only while loaded. */
class PluginScriptsTest {
    /** Records each run's source; the source is the script's log line. */
    private class Eng : ScriptEngine {
        override val id = "lua"
        override val extensions = listOf("lua")
        val ran = ArrayList<String>()
        override fun check(source: String) = if ("BAD" in source) "unexpected symbol near BAD" else null
        override fun run(source: String, input: Map<String, Any?>, limits: ScriptLimits, caller: ScriptCaller): ScriptResult {
            ran += source
            return ScriptResult(ScriptOutcome.OK, emptyList(), listOf("ran:$source"))
        }
    }

    private val tmp = createTempDirectory("plugin-scripts").toFile()
    private val moduleDir = File(tmp, "modules/Acme_Demo").apply { File(this, "scripts").mkdirs() }
    private val project = DirectoryProjectFiles(File(tmp, "p1"))
    private val eng = Eng()
    private val engines = ScriptEngines({ mapOf(ScriptEngineWire.PREFIX + "lua" to ScriptEngineWire.asEndpoint(eng)) })
    private var declared = listOf(
        ScriptDeclaration("demo", "scripts/demo.lua", "Demo script", listOf(ScriptHookDeclaration("greet", "acme.hello", Json.parseToJsonElement("""{"who":"hook"}""").jsonObject))),
    )
    private var installed = true
    private val modules: () -> List<LoadedModule>? = {
        if (installed) listOf(LoadedModule("Acme_Demo", "community", ModuleManifest(name = "Acme_Demo", version = "1.0.0", scripts = declared), moduleDir)) else emptyList()
    }
    private val plugins = PluginScripts(File(tmp, "state/plugin-scripts"), modules)
    private val global = GlobalScripts(File(tmp, "state/global-scripts"), { engines.extensions() })
    private val store = ScriptStore({ project }, { engines.extensions() }, global, plugins)
    private val hookStore = HookStore({ project }, null, plugins::hooks)
    private val save = SaveTools(store, hookStore, engines, { "stamp" })
    private val tools = ScriptTools(ScriptDispatcherTest.FakeTools(), engines, store, ScriptingLimits { null }, { emptyMap() }, { 3 })

    private fun ship(source: String) = File(moduleDir, "scripts/demo.lua").writeText(source)

    private fun call(args: String, owner: Boolean = true) = object : ToolCall {
        override val arguments = Json.parseToJsonElement(args).jsonObject
        override val caller = Caller(owner = owner, agent = !owner)
        override val clientSessionId: String? = if (owner) null else "sess-1"
        override val clientRole: String? = if (owner) null else "backend"
        override val clientProjectId: String? = "p1"
        override fun request(type: String, data: String, sessionId: String?, timeoutMs: Long): Frame = error("no frames")
    }

    @Test fun `install seeds the script, it is listed with its plugin and runs for any role`() {
        ship("V1")
        val out = plugins.sync()
        assertEquals(listOf("Acme_Demo/demo"), out.written, out.problems.toString())
        val list = save.list.call(call("{}")).text
        assertTrue("demo  scope=plugin:Acme_Demo" in list && "hooks: Acme_Demo.greet" in list && "Demo script" in list && "1 from plugins" in list, list)
        val ran = tools.run.call(call("""{"name":"demo"}""", owner = false))
        assertFalse(ran.isError, ran.text); assertTrue("ran:V1" in ran.text, ran.text)
        assertEquals(listOf("demo"), store.names("p1"))
    }

    @Test fun `an unedited copy follows the module, an edit is kept across an update and a reinstall`() {
        ship("V1"); plugins.sync()
        ship("V2")
        assertEquals(listOf("Acme_Demo/demo"), plugins.sync().written)
        assertEquals("V2", plugins.read("Acme_Demo", "demo")?.source)
        val edit = save.save.call(call("""{"name":"demo","source":"MINE","scope":"plugin"}"""))
        assertFalse(edit.isError, edit.text)
        assertTrue(plugins.entries().single().edited)
        ship("V3")
        installed = false; plugins.sync(); installed = true
        assertEquals(emptyList(), plugins.sync().written, "the owner edit is never overwritten")
        assertEquals("MINE", plugins.read("Acme_Demo", "demo")?.source)
        assertTrue(save.save.call(call("""{"name":"demo","source":"BAD","scope":"plugin"}""")).isError, "the parse check applies to plugin edits")
        assertTrue(save.save.call(call("""{"name":"nope","source":"x","scope":"plugin"}""")).isError, "scope plugin only edits a shipped script")
        assertTrue(save.delete.call(call("""{"name":"demo","scope":"plugin"}""")).isError, "a plugin's script goes with the plugin")
    }

    @Test fun `uninstall leaves the script disabled and detaches its hook, reinstall enables both`() {
        ship("V1"); plugins.sync()
        assertEquals(listOf("Acme_Demo.greet"), hookStore.list("p1").map { it.id })
        installed = false
        assertEquals(emptyList(), hookStore.list("p1"), "the hook goes with the module")
        val e = plugins.entries().single()
        assertFalse(e.enabled); assertEquals("V1", File(tmp, "state/plugin-scripts/Acme_Demo/demo.lua").readText(), "never deleted")
        assertTrue("[disabled: plugin not loaded]" in save.list.call(call("{}")).text)
        val ran = tools.run.call(call("""{"name":"demo"}"""))
        assertTrue(ran.isError && "disabled" in ran.text && "Acme_Demo" in ran.text, ran.text)
        installed = true
        assertTrue(plugins.entries().single().enabled)
        assertEquals(listOf("Acme_Demo.greet"), hookStore.list("p1").map { it.id })
    }

    @Test fun `an owner script of the same name is never touched and wins a lookup, the plugin hook still runs its own script`() {
        project.put("scripts/demo.lua", "OWNER".toByteArray())
        ship("V1"); plugins.sync()
        assertEquals("OWNER", project.get("scripts/demo.lua")!!.toString(Charsets.UTF_8))
        assertEquals("OWNER", store.read("p1", "demo")?.source)
        assertTrue("SHADOWED by the project copy" in save.list.call(call("{}")).text)
        val hooks = ScriptHooks(hookStore, store, engines, ScriptDispatcherTest.FakeTools(), ScriptDispatcher(ScriptDispatcherTest.FakeTools(), { emptyMap() }),
            projects = { listOf("p1") }, limits = { ScriptLimits() }, executor = { it() })
        hooks.onEvent(NamedEvent("acme.hello", """{"projectId":"p1"}""", "test", "e1", "e1", 0, 1))
        assertEquals(listOf("V1"), eng.ran, "the plugin's hook ran the plugin's script")
    }

    @Test fun `a bad declaration is refused with its reason and never escapes the module folder`() {
        ship("V1")
        File(tmp, "modules/secret.lua").writeText("S")
        declared = listOf(
            ScriptDeclaration("escape", "../secret.lua"),
            ScriptDeclaration("missing", "scripts/missing.lua"),
            ScriptDeclaration("bad name!", "scripts/demo.lua"),
            ScriptDeclaration("demo", "scripts/demo.lua", hooks = listOf(ScriptHookDeclaration("h1", "not an event!"), ScriptHookDeclaration("h2", "mail.incoming"), ScriptHookDeclaration("ok", "lane enter done"))),
        )
        val out = plugins.sync()
        assertEquals(listOf("Acme_Demo/demo"), out.written)
        assertEquals(5, out.problems.size, out.problems.toString())
        assertTrue(out.problems.any { "escape" in it } && out.problems.any { "missing" in it } && out.problems.any { "h1" in it } && out.problems.any { "h2" in it }, out.problems.toString())
        assertNull(plugins.entries().firstOrNull { it.name == "escape" })
        assertEquals(listOf("Acme_Demo.ok"), plugins.hooks().map { it.id }, "only the hook with a known kind is attached")
        assertFailsWith<IllegalArgumentException> { plugins.edit("Acme_Demo", "escape", "lua", "x", "owner") }
        assertEquals(JsonPrimitive("lane enter done"), JsonPrimitive(plugins.hooks().single().on.toString()))
    }
}
