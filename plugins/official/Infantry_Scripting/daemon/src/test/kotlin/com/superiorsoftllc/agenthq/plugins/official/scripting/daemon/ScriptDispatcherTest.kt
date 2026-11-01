package com.superiorsoftllc.agenthq.plugins.official.scripting.daemon

import com.superiorsoftllc.agenthq.daemon.api.Caller
import com.superiorsoftllc.agenthq.daemon.api.DaemonTool
import com.superiorsoftllc.agenthq.daemon.api.Frame
import com.superiorsoftllc.agenthq.daemon.api.ToolCall
import com.superiorsoftllc.agenthq.daemon.api.ToolInfo
import com.superiorsoftllc.agenthq.daemon.api.ToolInvoker
import com.superiorsoftllc.agenthq.daemon.api.ToolResult
import com.superiorsoftllc.agenthq.daemon.api.Tools
import com.superiorsoftllc.agenthq.daemon.api.files.ProjectFileEntry
import com.superiorsoftllc.agenthq.daemon.api.files.ProjectFiles
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** SCRIPT-4: the dispatcher, the alias data, dry-run, failure reporting, script_api and script_run, over a fake registry and a fake engine. */
class ScriptDispatcherTest {
    class FakeTools : Tools {
        val known = LinkedHashMap<String, ToolInfo>()
        val calls = ArrayList<Triple<String, JsonObject, ToolInvoker>>()
        var failOn: String? = null
        fun add(name: String, desc: String = "does $name") {
            known[name] = ToolInfo(name, desc, Json.parseToJsonElement("""{"type":"object","properties":{"ticket":{"type":"string"},"n":{"type":"integer"}},"required":["ticket"]}""").jsonObject)
        }
        override fun register(tool: DaemonTool) {}
        override fun catalog() = known.values.toList()
        override fun invoke(name: String, arguments: JsonObject, `as`: ToolInvoker): ToolResult {
            calls += Triple(name, arguments, `as`)
            return if (name == failOn) ToolResult("$name refused", true) else ToolResult("""{"done":"$name"}""")
        }
    }

    private class ScriptedEngine(val byPhase: (Map<String, Any?>) -> List<QueuedAction>) : ScriptEngine {
        override val id = "fake"
        override val extensions = listOf("fk")
        override val binding = "fake binding"
        val inputs = ArrayList<Map<String, Any?>>()
        override fun run(source: String, input: Map<String, Any?>, limits: ScriptLimits, caller: ScriptCaller): ScriptResult {
            inputs += input
            return ScriptResult(ScriptOutcome.OK, byPhase(input), listOf("log ${input["phase"]}"))
        }
    }

    private val invoker = ToolInvoker(Caller(owner = false, agent = true), "sess-1", "backend", "p1", "script:t")
    private val caller = ScriptCaller("sess-1", "backend")
    private val aliases = mapOf("move" to "t_move", "comment" to "t_comment")

    private fun tools() = FakeTools().apply { add("t_move"); add("t_comment") }

    @Test fun `the dispatcher runs actions in order through the registry as the invoking identity`() {
        val tools = tools()
        val engine = ScriptedEngine { listOf(QueuedAction("t_move", mapOf("ticket" to "a")), QueuedAction("t_comment", mapOf("ticket" to "a", "n" to 2L))) }
        val r = ScriptDispatcher(tools, { aliases }).run(engine, "", mapOf("k" to 1L), ScriptLimits(), caller, invoker, false)
        assertTrue(r.ok, r.render("t"))
        assertEquals(listOf("t_move", "t_comment"), tools.calls.map { it.first })
        assertEquals("backend", tools.calls[0].third.role)
        assertEquals("script:t", tools.calls[0].third.label)
        assertEquals("a", (tools.calls[0].second["ticket"] as JsonPrimitive).content)
        assertEquals(mapOf("k" to 1L), engine.inputs[0]["args"])
    }

    @Test fun `aliases resolve from data and a changed table takes effect at the next run`() {
        val tools = tools()
        val engine = ScriptedEngine { listOf(QueuedAction("move", mapOf("ticket" to "a"))) }
        var table = aliases
        val d = ScriptDispatcher(tools, { table })
        assertEquals("t_move", d.run(engine, "", emptyMap(), ScriptLimits(), caller, invoker, true).records[0].tool)
        table = mapOf("move" to "t_comment")
        assertEquals("t_comment", d.run(engine, "", emptyMap(), ScriptLimits(), caller, invoker, true).records[0].tool)
        table = emptyMap()
        val bad = d.run(engine, "", emptyMap(), ScriptLimits(), caller, invoker, true)
        assertFalse(bad.ok)
        assertTrue("unknown tool or alias 'move'" in bad.error!!)
    }

    @Test fun `dry run returns the plan and executes nothing`() {
        val tools = tools()
        val engine = ScriptedEngine { listOf(QueuedAction("move", mapOf("ticket" to "a")), QueuedAction("comment", mapOf("ticket" to "a")), QueuedAction("again", emptyMap())) }
        val r = ScriptDispatcher(tools, { aliases }).run(engine, "", emptyMap(), ScriptLimits(), caller, invoker, true)
        assertTrue(tools.calls.isEmpty())
        assertEquals(listOf("t_move", "t_comment"), r.records.map { it.tool })
        assertTrue(r.records.all { it.ok == null } && r.dryRun && r.phases == 1)
        assertTrue("DRY-RUN" in r.render("t") && "planned" in r.render("t"))
    }

    @Test fun `a failing action stops the run and names the action and an unknown name runs nothing`() {
        val tools = tools().apply { failOn = "t_comment"; add("t_third") }
        val engine = ScriptedEngine { listOf(QueuedAction("t_move", mapOf("ticket" to "a")), QueuedAction("t_comment", mapOf("ticket" to "a")), QueuedAction("t_third", mapOf("ticket" to "a"))) }
        val r = ScriptDispatcher(tools, { aliases }).run(engine, "", emptyMap(), ScriptLimits(), caller, invoker, false)
        assertEquals("ACTION_FAILED", r.outcome)
        assertEquals(2, r.failedAt)
        assertEquals(listOf("t_move", "t_comment"), tools.calls.map { it.first })
        assertTrue("action 2 (t_comment) failed: t_comment refused" in r.error!!)
        val tools2 = tools()
        val e2 = ScriptedEngine { listOf(QueuedAction("t_move", mapOf("ticket" to "a")), QueuedAction("nope", emptyMap())) }
        val r2 = ScriptDispatcher(tools2, { aliases }).run(e2, "", emptyMap(), ScriptLimits(), caller, invoker, false)
        assertEquals(2, r2.failedAt)
        assertTrue(tools2.calls.isEmpty())
    }

    @Test fun `again re-runs the script with the results and phases are capped`() {
        val tools = tools()
        val engine = ScriptedEngine { i ->
            if (i["results"] == null) listOf(QueuedAction("move", mapOf("ticket" to "a")), QueuedAction("again", emptyMap()))
            else listOf(QueuedAction("comment", mapOf("ticket" to "a")))
        }
        val r = ScriptDispatcher(tools, { aliases }).run(engine, "", emptyMap(), ScriptLimits(), caller, invoker, false)
        assertTrue(r.ok, r.render("t"))
        assertEquals(2, r.phases)
        val res = (engine.inputs[1]["results"] as List<*>)[0] as Map<*, *>
        assertEquals(true, res["ok"])
        assertEquals("t_move", res["tool"])
        assertEquals(mapOf("done" to "t_move"), res["json"])
        val forever = ScriptedEngine { listOf(QueuedAction("again", emptyMap())) }
        val r2 = ScriptDispatcher(tools(), { aliases }, { 2 }).run(forever, "", emptyMap(), ScriptLimits(), caller, invoker, false)
        assertEquals("PHASES_EXHAUSTED", r2.outcome)
        assertEquals(2, forever.inputs.size)
    }

    @Test fun `script api is generated from the live registry and lists a tool registered later`() {
        val tools = tools()
        val before = ScriptApi.describe(tools, aliases, mapOf("fake" to "fake binding"))
        assertTrue("t_move(ticket*:string, n:integer)" in before && "move -> t_move" in before && "fake binding" in before)
        assertFalse("t_brand_new" in before)
        tools.add("t_brand_new", "a tool nobody scripted for")
        val after = ScriptApi.describe(tools, aliases, emptyMap())
        assertTrue("t_brand_new" in after && "a tool nobody scripted for" in after)
        val one = ScriptApi.describe(tools, aliases, emptyMap(), "move")
        assertTrue(one.startsWith("t_move") && "aliases: move" in one && "\"required\"" in one)
        assertTrue(ScriptApi.describe(tools, aliases, emptyMap(), "zzz").startsWith("unknown tool"))
    }

    private class MemFiles(val files: MutableMap<String, ByteArray>) : ProjectFiles {
        override fun put(path: String, bytes: ByteArray, modifiedMillis: Long?) { files[path] = bytes }
        override fun get(path: String) = files[path]
        override fun stat(path: String) = files[path]?.let { ProjectFileEntry(path, it.size.toLong(), 0) }
        override fun list(path: String) = files.keys.filter { it.startsWith("$path/") }.map { ProjectFileEntry(it.substringAfterLast('/'), 1, 0) }
        override fun delete(path: String) = files.remove(path) != null
        override fun touch(path: String, modifiedMillis: Long) = path in files
        override fun absolutePath(path: String) = path
    }

    private fun call(args: String, session: String? = "sess-1", role: String? = "qa") = object : ToolCall {
        override val arguments = Json.parseToJsonElement(args).jsonObject
        override val caller = Caller(owner = false, agent = true)
        override val clientSessionId = session
        override val clientRole = role
        override val clientProjectId: String? = "p1"
        override fun request(type: String, data: String, sessionId: String?, timeoutMs: Long): Frame = error("no frames")
    }

    @Test fun `script run reads the named script of the project and a non pm role runs it`() {
        val tools = tools()
        val files = MemFiles(mutableMapOf("scripts/done.fk" to "src".toByteArray()))
        val engine = ScriptedEngine { i -> listOf(QueuedAction("move", mapOf("ticket" to ((i["args"] as Map<*, *>)["ticket"])))) }
        val engines = ScriptEngines({ mapOf(ScriptEngineWire.PREFIX + "fake" to ScriptEngineWire.asEndpoint(engine)) })
        val st = ScriptTools(tools, engines, ScriptStore({ files }, { engines.extensions() }), ScriptingLimits { null }, { aliases }, { 3 })
        val ok = st.run.call(call("""{"name":"done","args":"{\"ticket\":\"t9\"}"}""", role = "qa"))
        assertFalse(ok.isError, ok.text)
        assertTrue("1. move (t_move)" in ok.text && "-> ok" in ok.text && "log 1" in ok.text, ok.text)
        assertEquals("qa", tools.calls.single().third.role)
        val dry = st.run.call(call("""{"name":"done","dryRun":true,"args":{"ticket":"t9"}}"""))
        assertTrue("DRY-RUN" in dry.text)
        assertEquals(1, tools.calls.size)
        assertTrue(st.run.call(call("""{"name":"missing"}""")).let { it.isError && "no script 'missing'" in it.text && "done" in it.text })
        assertTrue(st.run.call(call("""{"name":"../x"}""")).isError)
        assertTrue(st.run.call(call("""{"name":"done","args":"[1]"}""")).isError)
        assertNotNull(st.api.call(call("{}")).text)
    }
}
