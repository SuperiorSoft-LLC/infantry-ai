// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.plugins.official.scripting.daemon

import com.superiorsoftllc.infantry.daemon.api.Caller
import com.superiorsoftllc.infantry.daemon.api.Frame
import com.superiorsoftllc.infantry.daemon.api.ToolCall
import com.superiorsoftllc.infantry.daemon.api.files.DirectoryProjectFiles
import com.superiorsoftllc.infantry.daemon.api.knowledge.KnowledgeWriter
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** SCRIPT-ARSENAL-2: generated knowledge entries, `q=` search, global hooks, and the generated index of the docs page. */
class ArsenalKnowledgeTest {
    private class Eng : ScriptEngine {
        override val id = "lua"
        override val extensions = listOf("lua")
        override fun check(source: String): String? = null
        override fun run(source: String, input: Map<String, Any?>, limits: ScriptLimits, caller: ScriptCaller) = ScriptResult(ScriptOutcome.OK, emptyList(), emptyList())
    }

    private class FakeWriter : KnowledgeWriter {
        val notes = LinkedHashMap<Pair<String, String>, Pair<String, String>>()
        override fun save(project: String, name: String, description: String, body: String, folder: String?) { notes[project to name] = description to body }
        override fun delete(project: String, name: String) = notes.remove(project to name) != null
        override fun names(project: String, prefix: String) = notes.keys.filter { it.first == project && it.second.startsWith(prefix) }.map { it.second }
    }

    private val tmp = createTempDirectory("arsenalkb").toFile()
    private val projects = mapOf("p1" to DirectoryProjectFiles(File(tmp, "p1")), "p2" to DirectoryProjectFiles(File(tmp, "p2")))
    private val engines = ScriptEngines({ mapOf(ScriptEngineWire.PREFIX + "lua" to ScriptEngineWire.asEndpoint(Eng())) })
    private val global = GlobalScripts(File(tmp, "state/global-scripts"), { engines.extensions() })
    private val store = ScriptStore({ projects.getValue(it) }, { engines.extensions() }, global)
    private val hooks = HookStore({ projects.getValue(it) }, File(tmp, "state/global-hooks.json"))
    private val writer = FakeWriter()
    private val kb = ArsenalKnowledge({ writer }, store, { projects.keys.toList() })
    private val save = SaveTools(store, hooks, engines, { "stamp($it)" }, { "pm,owner" }, { projects.keys.toList() }, changed = { scope, project, name ->
        if (scope == ScriptStore.GLOBAL || project == null) kb.refreshGlobal(name) else kb.refreshProject(project, name)
    })

    private fun call(args: String, project: String = "p1", role: String = "pm") = object : ToolCall {
        override val arguments = Json.parseToJsonElement(args).jsonObject
        override val caller = Caller(owner = false, agent = true)
        override val clientSessionId: String? = "sess-$project"
        override val clientRole: String? = role
        override val clientProjectId: String? = project
        override fun request(type: String, data: String, sessionId: String?, timeoutMs: Long): Frame = error("no frames")
    }

    private val src = "-- demo: counts the failing runs\n-- Scenario: Alert when a script keeps failing.\n-- Args:\n--   n (number, 3): how many\n-- Hook: on=\"every 60s\"  args {\"n\":3}\n-- Gotchas: needs stats.\nreturn 1"

    private fun saveGlobal(name: String, source: String = src) =
        save.save.call(call("""{"name":"$name","source":${kotlinx.serialization.json.JsonPrimitive(source)},"scope":"global"}"""))

    @Test fun `the header is parsed into purpose scenario args hook gotchas and an example`() {
        val d = assertNotNull(ScriptHeader.parse("demo", src))
        assertEquals("counts the failing runs", d.purpose)
        assertEquals("Alert when a script keeps failing.", d.scenario)
        assertEquals(listOf("n (number, 3): how many"), d.args)
        assertEquals("infantry_script_run name=demo args={\"n\":3}", d.example)
        assertNull(ScriptHeader.parse("demo", "return 1"), "no header, no entry")
    }

    @Test fun `saving seeding refreshing and deleting a global script keeps its entry in step`() {
        assertFalse(saveGlobal("demo").isError)
        val (desc, body) = assertNotNull(writer.notes[KnowledgeWriter.GLOBAL_PROJECT to "script-arsenal-demo"])
        assertTrue("Script Arsenal" in desc && "Script Arsenal" in body && "Alert when a script keeps failing" in body, body)
        assertTrue("Shadowed by project copies:** none" in body, body)
        // a project copy shadows it: the global entry lists the project, and the project gets its own entry
        assertFalse(save.save.call(call("""{"name":"demo","source":"-- demo: p1 variant\nreturn 2"}""")).isError)
        assertTrue("Shadowed by project copies:** p1" in writer.notes.getValue(KnowledgeWriter.GLOBAL_PROJECT to "script-arsenal-demo").second)
        assertTrue("p1 variant" in writer.notes.getValue("p1" to "script-arsenal-demo").second)
        // an edited header updates the entry
        assertFalse(saveGlobal("demo", src.replace("counts the failing runs", "counts failures twice")).isError)
        assertTrue("counts failures twice" in writer.notes.getValue(KnowledgeWriter.GLOBAL_PROJECT to "script-arsenal-demo").second)
        // deleting the project copy drops its entry; deleting the global drops the global one
        assertFalse(save.delete.call(call("""{"name":"demo"}""")).isError)
        assertNull(writer.notes["p1" to "script-arsenal-demo"])
        assertTrue("none" in writer.notes.getValue(KnowledgeWriter.GLOBAL_PROJECT to "script-arsenal-demo").second.substringAfter("Shadowed by project copies:**"))
        assertFalse(save.delete.call(call("""{"name":"demo","scope":"global"}""")).isError)
        assertNull(writer.notes[KnowledgeWriter.GLOBAL_PROJECT to "script-arsenal-demo"])
    }

    @Test fun `syncAll writes every global script and drops entries of scripts that are gone`() {
        BundledScripts.seed(global, File(tmp, "state/seed"), BundledScripts.fromJar())
        writer.save(KnowledgeWriter.GLOBAL_PROJECT, "script-arsenal-ghost", "x", "x")
        assertEquals(18, kb.syncAll())
        assertNull(writer.notes[KnowledgeWriter.GLOBAL_PROJECT to "script-arsenal-ghost"])
        assertEquals(18, writer.notes.keys.count { it.first == KnowledgeWriter.GLOBAL_PROJECT })
        val hh = writer.notes.getValue(KnowledgeWriter.GLOBAL_PROJECT to "script-arsenal-hook-health")
        assertTrue("alert when a script keeps failing" in (hh.first + hh.second).lowercase())
        assertEquals(-1, ArsenalKnowledge({ null }, store, { emptyList() }).syncAll(), "no writer yet: nothing happens, the caller retries")
    }

    @Test fun `list q searches names purposes and scenarios across both scopes`() {
        saveGlobal("demo")
        assertTrue("demo" in save.list.call(call("""{"q":"keeps failing"}""")).text)
        assertTrue("no script matches" in save.list.call(call("""{"q":"nothing-like-it"}""")).text)
    }

    @Test fun `a global hook runs for every project, a project hook of the same id shadows it`() {
        saveGlobal("demo")
        val t = HookTools(hooks, store, { 10L }, save::globalWriteRefusal)
        assertFalse(t.tool.call(call("""{"action":"add","scope":"global","on":"every 60s","script":"demo","id":"hh"}""")).isError)
        assertEquals(listOf("hh"), hooks.list("p1").map { it.id })
        assertEquals(listOf("hh"), hooks.list("p2").map { it.id })
        assertTrue(hooks.list("p2").single().arsenal)
        assertTrue("[arsenal: every project]" in t.tool.call(call("""{"action":"list"}""")).text)
        assertTrue(t.tool.call(call("""{"action":"add","scope":"global","on":"every 60s","script":"demo","id":"hh"}""")).isError, "duplicate id")
        // a project hook with the same id opts p1 out of the global one
        assertFalse(t.tool.call(call("""{"action":"add","on":"every 90s","script":"demo","id":"hh"}""")).isError)
        assertFalse(hooks.list("p1").single().arsenal)
        assertTrue(hooks.list("p2").single().arsenal)
        // the global script cannot be deleted while a global hook names it
        assertTrue(save.delete.call(call("""{"name":"demo","scope":"global"}""")).isError)
        assertFalse(t.tool.call(call("""{"action":"remove","scope":"global","id":"hh"}""")).isError)
        assertTrue(hooks.arsenalHooks().isEmpty())
        // writing global hooks follows the same role setting
        val denied = t.tool.call(call("""{"action":"add","scope":"global","on":"every 60s","script":"demo"}""", role = "backend"))
        assertTrue(denied.isError && "writeRoles" in denied.text, denied.text)
    }

    /** The docs page's table is generated from the bundled headers; this fails when it is stale. ARSENAL_INDEX_WRITE=1 rewrites it. */
    @Test fun `the docs index table equals the one generated from the bundled headers`() {
        val docs = BundledScripts.fromJar().map { assertNotNull(ScriptHeader.parse(it.name, it.source), "${it.name} has no header") }
        assertEquals(18, docs.size)
        docs.forEach { assertTrue(it.scenario != it.purpose, "${it.name} needs a Scenario: line") }
        val table = ScriptHeader.indexTable(docs)
        var dir: File? = File("").absoluteFile
        while (dir != null && !File(dir, "docs/wiki/script-arsenal.md").isFile) dir = dir.parentFile
        val page = File(assertNotNull(dir, "docs/wiki/script-arsenal.md not found above ${File("").absolutePath}"), "docs/wiki/script-arsenal.md")
        val text = page.readText().replace("\r\n", "\n") // a Windows checkout may hold CRLF
        val start = "<!-- arsenal-index:start -->\n"
        val end = "<!-- arsenal-index:end -->"
        val a = text.indexOf(start)
        val b = text.indexOf(end)
        assertTrue(a >= 0 && b > a, "markers missing in ${page.absolutePath} (a=$a b=$b)")
        val wanted = text.substring(0, a + start.length) + table + text.substring(b)
        if (System.getenv("ARSENAL_INDEX_WRITE") == "1") page.writeText(wanted)
        else assertEquals(wanted, text, "docs/wiki/script-arsenal.md index is stale: run this test with ARSENAL_INDEX_WRITE=1")
    }
}
