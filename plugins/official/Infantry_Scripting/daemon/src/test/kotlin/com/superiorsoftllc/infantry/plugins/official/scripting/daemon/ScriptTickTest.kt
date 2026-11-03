package com.superiorsoftllc.infantry.plugins.official.scripting.daemon

import com.superiorsoftllc.infantry.daemon.api.Caller
import com.superiorsoftllc.infantry.daemon.api.DaemonTool
import com.superiorsoftllc.infantry.daemon.api.Frame
import com.superiorsoftllc.infantry.daemon.api.NamedEvent
import com.superiorsoftllc.infantry.daemon.api.ToolCall
import com.superiorsoftllc.infantry.daemon.api.ToolInfo
import com.superiorsoftllc.infantry.daemon.api.ToolInvoker
import com.superiorsoftllc.infantry.daemon.api.ToolResult
import com.superiorsoftllc.infantry.daemon.api.Tools
import com.superiorsoftllc.infantry.daemon.api.files.ProjectFileEntry
import com.superiorsoftllc.infantry.daemon.api.files.ProjectFiles
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** SCRIPT-TICK-1: timer hooks (every <N>s, exec.tick) and progress from a script for the session it acts for. */
class ScriptTickTest {
    private class MemFiles(val files: MutableMap<String, ByteArray> = mutableMapOf()) : ProjectFiles {
        private var t = 0L
        private val mtime = HashMap<String, Long>()
        override fun put(path: String, bytes: ByteArray, modifiedMillis: Long?) { files[path] = bytes; mtime[path] = ++t }
        override fun get(path: String) = files[path]
        override fun stat(path: String) = files[path]?.let { ProjectFileEntry(path, it.size.toLong(), mtime[path] ?: 0) }
        override fun list(path: String) = files.keys.filter { it.startsWith("$path/") }.map { ProjectFileEntry(it.substringAfterLast('/'), 1, 0) }
        override fun delete(path: String) = files.remove(path) != null
        override fun touch(path: String, modifiedMillis: Long) = path in files
        override fun absolutePath(path: String) = path
    }

    private class FakeTools : Tools {
        val calls = ArrayList<Triple<String, JsonObject, ToolInvoker>>()
        override fun register(tool: DaemonTool) {}
        override fun catalog(): List<ToolInfo> = listOf("infantry_progress", "t_noop").map { ToolInfo(it, "d", Json.parseToJsonElement("""{"type":"object"}""").jsonObject) }
        override fun invoke(name: String, arguments: JsonObject, `as`: ToolInvoker): ToolResult { calls += Triple(name, arguments, `as`); return ToolResult("ok") }
    }

    private class FakeEngine(val actions: (Map<String, Any?>) -> List<QueuedAction>) : ScriptEngine {
        override val id = "fake"
        override val extensions = listOf("fk")
        val inputs = CopyOnWriteArrayList<Map<String, Any?>>()
        override fun run(source: String, input: Map<String, Any?>, limits: ScriptLimits, caller: ScriptCaller): ScriptResult {
            inputs += input
            return ScriptResult(ScriptOutcome.OK, actions(input))
        }
    }

    private val files = MemFiles(mutableMapOf("scripts/w.fk" to "src".toByteArray()))
    private val tools = FakeTools()
    private var now = 1_000_000L
    private val logs = CopyOnWriteArrayList<String>()
    private val progress = QueuedAction("infantry_progress", mapOf("mode" to "status", "label" to "x"))

    private fun build(engine: FakeEngine, sessions: Map<String, String> = mapOf("S1" to "p1", "OTHER" to "p2")): ScriptHooks {
        val engines = ScriptEngines({ mapOf(ScriptEngineWire.PREFIX + "fake" to ScriptEngineWire.asEndpoint(engine)) })
        return ScriptHooks(
            HookStore { files }, ScriptStore({ files }, { engines.extensions() }), engines, tools,
            ScriptDispatcher(tools, { emptyMap() }, sessionProject = { sessions[it] }),
            projects = { listOf("p1") }, limits = { ScriptLimits() }, log = { logs += it }, clock = { now }, executor = { it() },
        )
    }

    private fun putHooks(vararg h: String) = files.put(HookStore.PATH, """{"version":1,"hooks":[${h.joinToString(",")}]}""".toByteArray())
    private val everyHook = """{"id":"tick","on":"every 20s","script":"w","to":"S1","args":{"k":"v"},"ownerSession":"S1","ownerRole":"backend"}"""

    @Test fun `every hook parses and prints`() {
        assertEquals(HookOn.Every(30), HookOn.parse("Every  30s"))
        assertEquals("every 30s", HookOn.parse("every 30s").toString())
        assertTrue(runCatching { HookOn.parse("every 0s") }.isFailure)
        assertTrue(runCatching { HookOn.parse("every soon") }.isFailure)
    }

    @Test fun `every-N hook fires each period with tick at and hookId, honours the floor, and goes with its session`() {
        putHooks(everyHook)
        val engine = FakeEngine { emptyList() }
        val hooks = build(engine)
        hooks.timerTick(10); now += 19_000; hooks.timerTick(10)
        assertEquals(0, engine.inputs.size, "not before the period")
        now += 2_000; hooks.timerTick(10)
        assertEquals(1, engine.inputs.size)
        @Suppress("UNCHECKED_CAST") val a = engine.inputs[0]["args"] as Map<String, Any?>
        assertEquals(1L, a["tick"]); assertEquals(now, a["at"]); assertEquals("tick", a["hookId"]); assertEquals("v", a["k"])
        now += 20_000; hooks.timerTick(10)
        assertEquals(2L, (engine.inputs[1]["args"] as Map<*, *>)["tick"])
        // a floor above the hook's period slows it
        now += 20_000; hooks.timerTick(60)
        assertEquals(2, engine.inputs.size, "floor 60s: 20s is too soon")
        // the session ends: the hook is removed and never fires again
        assertEquals(1, HookStore { files }.removeForSession(listOf("p1"), "S1"))
        now += 100_000; hooks.timerTick(10)
        assertEquals(2, engine.inputs.size)
    }

    @Test fun `a tick arriving while the previous run is still going is skipped, never stacked`() {
        putHooks(everyHook)
        var hooks: ScriptHooks? = null
        val engine = FakeEngine { now += 25_000; hooks!!.timerTick(10); emptyList() }
        hooks = build(engine)
        hooks.timerTick(10); now += 21_000; hooks.timerTick(10)
        assertEquals(1, engine.inputs.size, "the nested tick did not run")
        assertTrue(logs.any { "skipped" in it }, logs.toString())
    }

    @Test fun `HookTools adds timer hooks with to, refuses a period below the floor and keeps to refused for lane hooks`() {
        val ht = HookTools(HookStore { files }, ScriptStore({ files }, { listOf("fk") }), { 10L })
        fun call(vararg kv: Pair<String, String>) = object : ToolCall {
            override val arguments: JsonObject = JsonObject(kv.associate { it.first to JsonPrimitive(it.second) })
            override val caller = Caller(owner = false, agent = true)
            override val clientSessionId: String = "S1"
            override val clientRole: String = "backend"
            override val clientProjectId: String = "p1"
            override fun request(type: String, data: String, sessionId: String?, timeoutMs: Long): Frame = error("n/a")
        }
        assertFalse(ht.handle(call("action" to "add", "on" to "every 30s", "script" to "w", "to" to "S1")).isError)
        assertFalse(ht.handle(call("action" to "add", "on" to "exec.tick", "script" to "w", "id" to "et")).isError)
        assertTrue(ht.handle(call("action" to "add", "on" to "every 5s", "script" to "w", "id" to "fast")).isError)
        assertTrue(ht.handle(call("action" to "add", "on" to "lane enter done", "script" to "w", "to" to "S1", "id" to "l")).isError)
        assertEquals("S1", HookStore { files }.list("p1").first { it.id.startsWith("w-every") }.to)
    }

    @Test fun `exec-tick hook runs for a live background run and the run stops ticking at exec-done`() {
        val root: File = Files.createTempDirectory("tick-root").toFile().canonicalFile
        val events = CopyOnWriteArrayList<Pair<String, String>>()
        val exec = ExecTools({ if (it == "scripting.exec.enabled") "true" else null }, { root }, { n, p -> events += n to p }, { })
        val java = File(System.getProperty("java.home"), "bin/java").path
        val q = { s: String -> Json.encodeToString(JsonPrimitive.serializer(), JsonPrimitive(s)) }
        val argv = listOf(java, "-cp", System.getProperty("java.class.path"), ExecProbe::class.java.name, "sleep", "1500").joinToString(",", "[", "]") { q(it) }
        val call = object : ToolCall {
            override val arguments: JsonObject = Json.parseToJsonElement("""{"command":$argv,"background":true,"script":"w"}""").jsonObject
            override val caller = Caller(owner = false, agent = true)
            override val clientSessionId: String = "S1"
            override val clientRole: String = "backend"
            override val clientProjectId: String = "p1"
            override fun request(type: String, data: String, sessionId: String?, timeoutMs: Long): Frame = error("n/a")
        }
        val id = Json.parseToJsonElement(exec.exec.call(call).text).jsonObject["id"]!!.jsonPrimitive.content
        exec.tick()
        val tick = events.single { it.first == "exec.tick" }
        val o = Json.parseToJsonElement(tick.second).jsonObject
        assertEquals(id, o["id"]!!.jsonPrimitive.content); assertEquals("w", o["script"]!!.jsonPrimitive.content); assertEquals("S1", o["session"]!!.jsonPrimitive.content)
        assertEquals("p1", o["projectId"]!!.jsonPrimitive.content)
        for (k in listOf("elapsedMs", "stdoutTail", "stderrTail", "bytesSinceLast")) assertTrue(k in o, k)
        // the hook runs for that event
        putHooks("""{"id":"et","on":"exec.tick","script":"w","ownerSession":"S1","ownerRole":"backend"}""")
        val engine = FakeEngine { emptyList() }
        val hooks = build(engine)
        hooks.onEvent(NamedEvent("exec.tick", tick.second, "daemon", "e1"))
        assertEquals(1, engine.inputs.size)
        assertEquals(id, (engine.inputs[0]["args"] as Map<*, *>)["id"])
        // wait for the run to end, then no more ticks
        val deadline = System.currentTimeMillis() + 15_000
        while (events.none { it.first == "exec.done" } && System.currentTimeMillis() < deadline) Thread.sleep(50)
        assertTrue(events.any { it.first == "exec.done" })
        val before = events.count { it.first == "exec.tick" }
        exec.tick()
        assertEquals(before, events.count { it.first == "exec.tick" }, "no tick after exec.done")
    }

    @Test fun `progress from a script lands on the hook's to session, or on the sessionId the script names`() {
        putHooks(everyHook)
        val hooks = build(FakeEngine { listOf(progress) })
        hooks.timerTick(10); now += 21_000; hooks.timerTick(10)
        val c = tools.calls.single { it.first == "infantry_progress" }
        assertEquals("S1", c.third.sessionId)
        assertFalse("sessionId" in c.second)
        // owner "S9" (an agent) acts for S1 via a named sessionId arg
        tools.calls.clear()
        val d = ScriptDispatcher(tools, { emptyMap() }, sessionProject = { mapOf("S1" to "p1")[it] })
        val inv = ToolInvoker(Caller(owner = false, agent = true, activeSession = "S9"), "S9", "backend", "p1", "script:w")
        val engine = FakeEngine { listOf(QueuedAction("infantry_progress", mapOf("mode" to "step", "sessionId" to "S1"))) }
        val r = d.run(engine, "", emptyMap(), ScriptLimits(), ScriptCaller("S9", "backend"), inv, false)
        assertTrue(r.ok, r.render("w"))
        val c2 = tools.calls.single()
        assertEquals("S1", c2.third.sessionId); assertEquals("S1", c2.third.caller.activeSession); assertFalse("sessionId" in c2.second)
    }

    @Test fun `progress naming a session of another project, or an unknown one, is refused and nothing is reported`() {
        val d = ScriptDispatcher(tools, { emptyMap() }, sessionProject = { mapOf("S1" to "p1", "OTHER" to "p2")[it] })
        val inv = ToolInvoker(Caller(owner = false, agent = true, activeSession = "S9"), "S9", "backend", "p1", "script:w")
        for (target in listOf("OTHER", "ghost")) {
            val engine = FakeEngine { listOf(QueuedAction("infantry_progress", mapOf("mode" to "step", "sessionId" to target))) }
            val r = d.run(engine, "", emptyMap(), ScriptLimits(), ScriptCaller("S9", "backend"), inv, false)
            assertFalse(r.ok)
            assertTrue("not a session of this project" in r.error!!, r.error)
        }
        assertTrue(tools.calls.isEmpty())
    }
}
