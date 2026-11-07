// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.plugins.official.scripting.daemon

import com.superiorsoftllc.infantry.daemon.api.Caller
import com.superiorsoftllc.infantry.daemon.api.Frame
import com.superiorsoftllc.infantry.daemon.api.ToolCall
import com.superiorsoftllc.infantry.daemon.api.files.DirectoryProjectFiles
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** SCRIPT-ARSENAL-1: the global scope: resolution order, who may write it, the project fence, and the bundled seed. */
class GlobalScriptsTest {
    /** The script's source is its action: `tool:<name>` queues that tool once. */
    private class Eng : ScriptEngine {
        override val id = "lua"
        override val extensions = listOf("lua")
        override fun check(source: String) = if ("BAD" in source) "unexpected symbol near BAD" else null
        override fun run(source: String, input: Map<String, Any?>, limits: ScriptLimits, caller: ScriptCaller) =
            ScriptResult(ScriptOutcome.OK, listOf(QueuedAction("t_mark", mapOf("ticket" to source))), listOf("ran:$source"))
    }

    private val tmp = createTempDirectory("arsenal").toFile()
    private val projects = mapOf("p1" to DirectoryProjectFiles(File(tmp, "p1")), "p2" to DirectoryProjectFiles(File(tmp, "p2")))
    private val engines = ScriptEngines({ mapOf(ScriptEngineWire.PREFIX + "lua" to ScriptEngineWire.asEndpoint(Eng())) })
    private val global = GlobalScripts(File(tmp, "state/global-scripts"), { engines.extensions() })
    private val store = ScriptStore({ projects.getValue(it) }, { engines.extensions() }, global)
    private val hooks = HookStore { projects.getValue(it) }
    private var writers = "pm,owner"
    private val save = SaveTools(store, hooks, engines, { "stamp($it)" }, { writers }, { projects.keys.toList() })

    private fun call(args: String, project: String = "p1", role: String = "pm", owner: Boolean = false) = object : ToolCall {
        override val arguments = Json.parseToJsonElement(args).jsonObject
        override val caller = Caller(owner = owner, agent = !owner)
        override val clientSessionId: String? = "sess-$project"
        override val clientRole: String? = role
        override val clientProjectId: String? = project
        override fun request(type: String, data: String, sessionId: String?, timeoutMs: Long): Frame = error("no frames")
    }

    @Test fun `a project copy shadows the global one and deleting it falls back`() {
        assertFalse(save.save.call(call("""{"name":"hh","source":"G","scope":"global"}""")).isError)
        assertEquals("G", store.read("p1", "hh")?.source)
        assertEquals("global", store.read("p2", "hh")?.scope)
        assertFalse(save.save.call(call("""{"name":"hh","source":"P1"}""")).isError)
        assertEquals("P1", store.read("p1", "hh")?.source)
        assertEquals("project", store.read("p1", "hh")?.scope)
        assertEquals("G", store.read("p2", "hh")?.source, "the other project still sees the global")
        assertEquals("G", global.read("hh")?.source, "refining never touches the global")
        val list = save.list.call(call("{}")).text
        assertTrue("scope=project" in list && "shadows the global" in list && "scope=global" in list && "SHADOWED by the project copy" in list, list)
        assertFalse(save.delete.call(call("""{"name":"hh"}""")).isError)
        assertEquals("G", store.read("p1", "hh")?.source)
        assertEquals(listOf("hh"), store.names("p1"))
    }

    @Test fun `who may write the global scope is a setting, the owner always may`() {
        val deny = save.save.call(call("""{"name":"x","source":"S","scope":"global"}""", role = "backend"))
        assertTrue(deny.isError && "scripting.global.writeRoles" in deny.text, deny.text)
        assertNull(global.read("x"))
        assertFalse(save.save.call(call("""{"name":"x","source":"S","scope":"global"}""", role = "backend", owner = true)).isError)
        writers = ""
        assertFalse(save.save.call(call("""{"name":"y","source":"S","scope":"global"}""", role = "backend")).isError, "empty setting = any role")
        writers = "pm"
        assertTrue(save.delete.call(call("""{"name":"y","scope":"global"}""", role = "backend")).isError)
        assertFalse(save.delete.call(call("""{"name":"y","scope":"global"}""", role = "pm")).isError)
        assertTrue(save.save.call(call("""{"name":"z","source":"S","scope":"nowhere"}""")).isError)
        assertTrue(save.save.call(call("""{"name":"z","source":"BAD","scope":"global"}""")).isError, "parse check applies to the global scope too")
    }

    @Test fun `a global script is refused for delete while a project hook uses it`() {
        save.save.call(call("""{"name":"g","source":"S","scope":"global"}"""))
        HookTools(hooks, store).handle(call("""{"action":"add","on":"ticket.commented","script":"g","id":"hk9"}""", project = "p2"))
        val d = save.delete.call(call("""{"name":"g","scope":"global"}"""))
        assertTrue(d.isError && "hk9" in d.text, d.text)
    }

    @Test fun `the fence - a global script runs as the calling project and reaches only that project`() {
        save.save.call(call("""{"name":"mark","source":"G","scope":"global"}"""))
        save.save.call(call("""{"name":"only1","source":"P1ONLY"}""", project = "p1"))
        val tools = ScriptDispatcherTest.FakeTools().apply { add("t_mark") }
        val st = ScriptTools(tools, engines, store, ScriptingLimits { null }, { emptyMap() }, { 1 })
        assertFalse(st.run.call(call("""{"name":"mark"}""", project = "p1")).isError)
        assertFalse(st.run.call(call("""{"name":"mark"}""", project = "p2")).isError)
        assertEquals(listOf("p1", "p2"), tools.calls.map { it.third.projectId }, "each run is invoked as the project that called it")
        val other = st.run.call(call("""{"name":"only1"}""", project = "p2"))
        assertTrue(other.isError && "no script 'only1' in project p2" in other.text, "a project's own script is not visible to another project: ${other.text}")
    }

    @Test fun `seeding copies a missing script, refreshes an unedited one and never overwrites an edit`() {
        val state = File(tmp, "state/seed")
        fun seed(vararg b: BundledScript) = BundledScripts.seed(global, state, b.toList())
        assertEquals(listOf("a", "b"), seed(BundledScript("a", "lua", "A1"), BundledScript("b", "lua", "B1")))
        assertEquals("A1", global.readExt("a", "lua")?.source)
        assertEquals("bundled", global.entries().first { it.name == "a" }.savedBy)
        assertEquals(emptyList(), seed(BundledScript("a", "lua", "A1"), BundledScript("b", "lua", "B1")), "unchanged: nothing written")
        global.write("b", "lua", "B-EDITED", "owner")
        assertEquals(listOf("a"), seed(BundledScript("a", "lua", "A2"), BundledScript("b", "lua", "B2")), "a is refreshed, the edited b is kept")
        assertEquals("A2", global.readExt("a", "lua")?.source)
        assertEquals("B-EDITED", global.readExt("b", "lua")?.source)
        save.save.call(call("""{"name":"a","source":"MINE"}"""))
        seed(BundledScript("a", "lua", "A3"))
        assertEquals("MINE", store.read("p1", "a")?.source, "a project copy is never touched by the seed")
        global.delete("a")
        assertEquals(listOf("a"), seed(BundledScript("a", "lua", "A3")), "a missing bundled script is copied again")
    }

    @Test fun `the index lists the jar resources and an unreadable index seeds nothing`() {
        val files = mapOf("global-scripts/index.json" to """["x.lua","gone.lua"]""", "global-scripts/x.lua" to "X")
        val got = BundledScripts.load({ files[it] })
        assertEquals(listOf(BundledScript("x", "lua", "X")), got)
        assertEquals(emptyList(), BundledScripts.load({ if (it.endsWith("index.json")) "not json" else null }))
    }
}
