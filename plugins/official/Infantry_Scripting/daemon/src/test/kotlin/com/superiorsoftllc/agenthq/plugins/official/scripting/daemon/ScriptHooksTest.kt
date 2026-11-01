package com.superiorsoftllc.agenthq.plugins.official.scripting.daemon

import com.superiorsoftllc.agenthq.daemon.api.Caller
import com.superiorsoftllc.agenthq.daemon.api.DaemonTool
import com.superiorsoftllc.agenthq.daemon.api.Frame
import com.superiorsoftllc.agenthq.daemon.api.NamedEvent
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
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** SCRIPT-5: hooks as data, lane and named-event firing with the event payload, the loop guard, failure reports, and the hook tool. */
class ScriptHooksTest {
    private class MemFiles(val files: MutableMap<String, ByteArray> = mutableMapOf()) : ProjectFiles {
        private var tick = 0L
        private val mtime = HashMap<String, Long>()
        override fun put(path: String, bytes: ByteArray, modifiedMillis: Long?) { files[path] = bytes; mtime[path] = ++tick }
        override fun get(path: String) = files[path]
        override fun stat(path: String) = files[path]?.let { ProjectFileEntry(path, it.size.toLong(), mtime[path] ?: 0) }
        override fun list(path: String) = files.keys.filter { it.startsWith("$path/") }.map { ProjectFileEntry(it.substringAfterLast('/'), 1, 0) }
        override fun delete(path: String) = files.remove(path) != null
        override fun touch(path: String, modifiedMillis: Long) = path in files
        override fun absolutePath(path: String) = path
    }

    /** A registry that records every call; `onInvoke` lets a test play the bus (a tool call raising an event). */
    private class FakeTools : Tools {
        val calls = ArrayList<Triple<String, JsonObject, ToolInvoker>>()
        var ticketJson = """{"ticketId":"ticket-aaaa1111","title":"T","status":"done","projectId":"p1","role":"backend","branch":"ticket/aaaa1111-x","base":"dev","sessionId":"worker-7"}"""
        var failTool: String? = null
        var failComment = false
        var onInvoke: (String, JsonObject) -> Unit = { _, _ -> }
        override fun register(tool: DaemonTool) {}
        override fun catalog(): List<ToolInfo> = listOf("t_move", "t_fail", "agenthq_ticket_get", "agenthq_ticket_comment", "agenthq_message")
            .map { ToolInfo(it, "d", Json.parseToJsonElement("""{"type":"object"}""").jsonObject) }
        override fun invoke(name: String, arguments: JsonObject, `as`: ToolInvoker): ToolResult {
            calls += Triple(name, arguments, `as`)
            return when {
                name == "agenthq_ticket_get" -> ToolResult(ticketJson)
                name == "agenthq_ticket_comment" && failComment -> ToolResult("comment refused", true)
                name == failTool -> ToolResult("$name refused", true)
                else -> { onInvoke(name, arguments); ToolResult("ok") }
            }
        }
        fun named(n: String) = calls.filter { it.first == n }
    }

    private class FakeEngine(val actions: (Map<String, Any?>) -> List<QueuedAction>) : ScriptEngine {
        override val id = "fake"
        override val extensions = listOf("fk")
        val inputs = ArrayList<Map<String, Any?>>()
        override fun run(source: String, input: Map<String, Any?>, limits: ScriptLimits, caller: ScriptCaller): ScriptResult {
            inputs += input
            return ScriptResult(ScriptOutcome.OK, actions(input))
        }
    }

    private val files = MemFiles(mutableMapOf("scripts/on-done.fk" to "src".toByteArray()))
    private val tools = FakeTools()
    private var now = 1_000L
    private val owner = Hook("x", HookOn.Lane("done"), "on-done", ownerSession = "pm-1", ownerRole = "pm")

    private fun hooksJson(vararg hooks: String) = """{"version":1,"hooks":[${hooks.joinToString(",")}]}"""
    private val doneHook = """{"id":"done-hook","on":"lane enter done","script":"on-done","args":{"mode":"merge"},"ownerSession":"pm-1","ownerRole":"pm"}"""

    private fun build(engine: FakeEngine, guard: HookGuardConfig = HookGuardConfig(), tip: (String, String) -> String? = { _, _ -> "abc123tip" }): ScriptHooks {
        val engines = ScriptEngines({ mapOf(ScriptEngineWire.PREFIX + "fake" to ScriptEngineWire.asEndpoint(engine)) })
        val store = ScriptStore({ files }, { engines.extensions() })
        return ScriptHooks(
            HookStore { files }, store, engines, tools, ScriptDispatcher(tools, { mapOf("move" to "t_move") }),
            projects = { listOf("p1") }, limits = { ScriptLimits() }, guard = { guard }, tip = tip, clock = { now }, executor = { it() },
        )
    }

    private fun moved(ticket: String = "ticket-aaaa1111", to: String = "done", depth: Int = 0) =
        NamedEvent("ticket.moved", """{"ticketId":"$ticket","from":"in_progress","to":"$to","kind":"moved","seq":5}""", "daemon", "ev-$ticket-$to", "ev-$ticket-$to", depth, 1)

    private fun putHooks(vararg hooks: String) = files.put(HookStore.PATH, hooksJson(*hooks).toByteArray())

    @Test fun `lane exit hooks fire on from, enter on to, on every lane, case-insensitively, with from and to in args`() {
        for (lane in listOf("backlog", "todo", "in_progress", "pending", "done", "Custom-Lane")) {
            for (dir in listOf("enter", "exit")) {
                files.files.remove(HookStore.PATH)
                putHooks("""{"id":"h","on":"LANE ${dir.uppercase()} ${lane.uppercase()}","script":"on-done"}""")
                val engine = FakeEngine { emptyList() }
                val hooks = build(engine)
                val other = "zzz"
                val ev = { from: String, to: String -> NamedEvent("ticket.moved", """{"ticketId":"ticket-aaaa1111","from":"$from","to":"$to","kind":"moved","seq":5}""", "daemon", "e-$from-$to", "e-$from-$to", 0, 1) }
                if (dir == "enter") { hooks.onEvent(ev(other, lane)); hooks.onEvent(ev(lane, other)) } else { hooks.onEvent(ev(lane, other)); hooks.onEvent(ev(other, lane)) }
                assertEquals(1, engine.inputs.size, "$dir $lane")
                val a = engine.inputs.single()["args"] as Map<*, *>
                assertEquals(lane.lowercase(), a["lane"].toString().lowercase())
                assertNotNull(a["from"]); assertNotNull(a["to"])
                assertEquals(if (dir == "enter") other else lane.lowercase(), a["from"].toString().lowercase().let { if (dir == "enter") other else it })
            }
        }
        assertEquals("lane exit in_progress", HookOn.parse("Lane  EXIT In_Progress").toString())
        assertTrue(runCatching { HookOn.parse("lane leave done") }.isFailure)
    }

    @Test fun `a lane enter done hook fires once with the ticket id, branch, tip sha and worker session as input`() {
        putHooks(doneHook)
        val engine = FakeEngine { listOf(QueuedAction("move", mapOf("ticket" to "other-1", "status" to "todo"))) }
        val hooks = build(engine)
        hooks.onEvent(moved())
        assertEquals(1, engine.inputs.size)
        val args = engine.inputs.single()["args"] as Map<*, *>
        assertEquals("ticket-aaaa1111", args["ticketId"])
        assertEquals("ticket/aaaa1111-x", args["branch"])
        assertEquals("abc123tip", args["tipSha"])
        assertEquals("worker-7", args["workerSession"])
        assertEquals("p1", args["projectId"])
        assertEquals("done", args["lane"])
        assertEquals("in_progress", args["from"])
        assertEquals("merge", args["mode"])
        assertEquals("ticket.moved", (args["event"] as Map<*, *>)["name"])
        // The queued action ran as the hook owner's identity, labelled with the script.
        val (tool, _, who) = tools.named("t_move").single()
        assertEquals("t_move", tool)
        assertEquals("script:on-done", who.label)
        assertEquals("pm-1", who.sessionId)
        assertEquals("pm", who.role)
        assertTrue(tools.named("agenthq_ticket_comment").isEmpty(), "a clean run reports nothing")
    }

    @Test fun `a lane hook ignores other lanes and a named event hook fires on its event only`() {
        putHooks(doneHook, """{"id":"c","on":"ticket.commented","script":"on-done"}""", """{"id":"p","on":"ticket.*","script":"on-done","enabled":false}""")
        val engine = FakeEngine { emptyList() }
        val hooks = build(engine)
        hooks.onEvent(moved(to = "todo"))
        assertEquals(0, engine.inputs.size)
        hooks.onEvent(NamedEvent("ticket.commented", """{"ticketId":"ticket-aaaa1111","commentId":"c1","author":"x","bodyPreview":"hi"}""", "Infantry_Board", "e1", "e1", 0, 1))
        assertEquals(1, engine.inputs.size)
        assertEquals("c1", (engine.inputs.single()["args"] as Map<*, *>)["commentId"])
        hooks.onEvent(NamedEvent("mail.sent", "{}", "daemon", "e2", "e2", 0, 1))
        assertEquals(1, engine.inputs.size, "the disabled prefix hook and unrelated events do nothing")
    }

    @Test fun `an event with no ticket reaches a named hook and the lane any user lane works`() {
        putHooks("""{"id":"m","on":"merge.done","script":"on-done"}""", """{"id":"qa","on":"Lane  Enter QA","script":"on-done"}""")
        val engine = FakeEngine { emptyList() }
        val hooks = build(engine)
        hooks.onEvent(NamedEvent("merge.done", """{"sha":"s1"}""", "cli", "e3", "e3", 0, 1))
        hooks.onEvent(moved(to = "QA"))
        assertEquals(2, engine.inputs.size)
        assertEquals("s1", (engine.inputs[0]["args"] as Map<*, *>)["sha"])
    }

    @Test fun `hook config is read from data and an edit on disk is seen at the next event`() {
        val engine = FakeEngine { emptyList() }
        val hooks = build(engine)
        hooks.onEvent(moved())
        assertEquals(0, engine.inputs.size, "no hooks.json, no hooks")
        putHooks(doneHook)
        hooks.onEvent(moved())
        assertEquals(1, engine.inputs.size)
        putHooks()
        hooks.onEvent(moved())
        assertEquals(1, engine.inputs.size, "removing the entry stops it")
        val parsed = HookStore.parse(hooksJson(doneHook)).getOrThrow().single()
        assertEquals(HookOn.Lane("done"), parsed.on)
        assertEquals("merge", parsed.args["mode"]!!.let { (it as JsonPrimitive).content })
        assertTrue(HookStore.parse("""{"hooks":[{"id":"a","on":"nonsense here","script":"s"}]}""").isFailure)
        assertTrue(HookStore.parse("""{"hooks":[{"id":"a","on":"x.y","script":"s"},{"id":"a","on":"x.y","script":"s"}]}""").isFailure)
    }

    @Test fun `the loop guard trips on a self retriggering script, reports it, and never runs past the depth limit`() {
        putHooks(doneHook)
        lateinit var hooks: ScriptHooks
        // The script moves the ticket to done again; the "bus" raises ticket.moved for it, which fires the same hook.
        val engine = FakeEngine { listOf(QueuedAction("move", mapOf("ticket" to "aaaa1111", "status" to "done"))) }
        hooks = build(engine, HookGuardConfig(maxDepth = 3, maxRuns = 100))
        tools.onInvoke = { name, _ -> if (name == "t_move") hooks.onEvent(moved()) }
        hooks.onEvent(moved())
        assertEquals(4, engine.inputs.size, "depth 0..3 run; the next hop is refused")
        val depths = engine.inputs.map { ((it["args"] as Map<*, *>)["event"] as Map<*, *>)["depth"] }
        assertEquals(listOf(0L, 1L, 2L, 3L), depths)
        val report = tools.named("agenthq_ticket_comment").single()
        assertTrue("loop guard" in report.second["body"]!!.jsonPrimitive().content)
        assertEquals("issue", report.second["kind"]!!.jsonPrimitive().content)
        assertEquals("ticket-aaaa1111", report.second["ticketId"]!!.jsonPrimitive().content)
    }

    @Test fun `the per hook rate limit trips, is reported once, and clears after the window`() {
        putHooks(doneHook)
        val engine = FakeEngine { emptyList() }
        val hooks = build(engine, HookGuardConfig(maxDepth = 3, maxRuns = 3, windowMs = 60_000))
        repeat(6) { hooks.onEvent(moved(ticket = "ticket-bbbb000$it")) }
        assertEquals(3, engine.inputs.size)
        assertEquals(1, tools.named("agenthq_ticket_comment").size, "one report per hook and window, not one per refusal")
        assertTrue("rate limit" in tools.named("agenthq_ticket_comment").single().second["body"]!!.jsonPrimitive().content)
        now += 61_000
        hooks.onEvent(moved(ticket = "ticket-cccc0000"))
        assertEquals(4, engine.inputs.size)
    }

    @Test fun `a failed action is reported as a comment on the triggering ticket`() {
        putHooks(doneHook)
        tools.failTool = "t_fail"
        val engine = FakeEngine { listOf(QueuedAction("t_fail", mapOf("ticket" to "zzzz"))) }
        build(engine).onEvent(moved())
        val c = tools.named("agenthq_ticket_comment").single()
        assertEquals("ticket-aaaa1111", c.second["ticketId"]!!.jsonPrimitive().content)
        val body = c.second["body"]!!.jsonPrimitive().content
        assertTrue("done-hook" in body && "failed" in body && "t_fail" in body, body)
    }

    @Test fun `when the comment cannot be posted, or a script is missing, the hook owner is mailed`() {
        putHooks(doneHook, """{"id":"gone","on":"merge.done","script":"nope","ownerSession":"pm-1"}""")
        tools.failTool = "t_fail"
        tools.failComment = true
        val engine = FakeEngine { listOf(QueuedAction("t_fail", mapOf("ticket" to "zzzz"))) }
        val hooks = build(engine)
        hooks.onEvent(moved())
        val mail = tools.named("agenthq_message").single()
        assertEquals("pm-1", mail.second["to"]!!.jsonPrimitive().content)
        assertTrue("failed" in mail.second["body"]!!.jsonPrimitive().content)
        hooks.onEvent(NamedEvent("merge.done", "{}", "cli", "e9", "e9", 0, 1))
        val second = tools.named("agenthq_message").last().second["body"]!!.jsonPrimitive().content
        assertTrue("no script 'nope'" in second, second)
    }

    @Test fun `the hook tool lists, adds and removes for any role and refuses a missing script or a bad on`() {
        val store = HookStore { files }
        val engines = ScriptEngines({ mapOf(ScriptEngineWire.PREFIX + "fake" to ScriptEngineWire.asEndpoint(FakeEngine { emptyList() })) })
        val ht = HookTools(store, ScriptStore({ files }, { engines.extensions() }))
        fun call(args: String, session: String? = "w-1", role: String? = "qa") = object : ToolCall {
            override val arguments = Json.parseToJsonElement(args).jsonObject
            override val caller = Caller(owner = false, agent = true)
            override val clientSessionId = session
            override val clientRole = role
            override val clientProjectId: String? = "p1"
            override fun request(type: String, data: String, sessionId: String?, timeoutMs: Long): Frame = error("no frames")
        }
        assertTrue(ht.handle(call("""{"action":"list"}""")).text.startsWith("no hooks"))
        val added = ht.handle(call("""{"action":"add","on":"lane enter done","script":"on-done","args":"{\"k\":1}"}"""))
        assertFalse(added.isError, added.text)
        val listed = ht.handle(call("""{"action":"list"}""")).text
        assertTrue("on lane enter done -> on-done" in listed, listed)
        val stored = store.list("p1").single()
        assertEquals("w-1", stored.ownerSession)
        assertEquals("qa", stored.ownerRole)
        assertTrue(ht.handle(call("""{"action":"add","on":"lane enter done","script":"on-done"}""")).isError, "same default id twice")
        assertTrue(ht.handle(call("""{"action":"add","on":"lane enter done","script":"missing"}""")).text.contains("no script 'missing'"))
        assertTrue(ht.handle(call("""{"action":"add","on":"Not An Event","script":"on-done"}""")).isError)
        assertTrue(ht.handle(call("""{"action":"remove","id":"nope"}""")).isError)
        assertFalse(ht.handle(call("""{"action":"remove","id":"${stored.id}"}""")).isError)
        assertEquals(0, store.list("p1").size)
        // A hand-edited file that does not parse is never overwritten, and list says why nothing fires.
        files.put(HookStore.PATH, "{ not json".toByteArray())
        assertTrue(ht.handle(call("""{"action":"add","on":"merge.done","script":"on-done"}""")).isError)
        assertTrue("does not parse" in ht.handle(call("""{"action":"list"}""")).text)
        assertEquals("{ not json", files.get(HookStore.PATH)!!.toString(Charsets.UTF_8))
    }

    @Test fun `git tip reads a loose ref or packed refs without a process`() {
        val dir = kotlin.io.path.createTempDirectory("hooktip").toFile()
        try {
            val git = java.io.File(dir, ".git/refs/heads/ticket").apply { mkdirs() }
            java.io.File(git, "a-b").writeText("a".repeat(40) + "\n")
            java.io.File(dir, ".git/packed-refs").writeText("# pack\n${"b".repeat(40)} refs/heads/packed-one\n")
            assertEquals("a".repeat(40), ScriptHooks.gitTip(dir, "ticket/a-b"))
            assertEquals("b".repeat(40), ScriptHooks.gitTip(dir, "packed-one"))
            assertNull(ScriptHooks.gitTip(dir, "missing"))
            assertNull(ScriptHooks.gitTip(dir, "../x"))
            assertNotNull(HookGuardConfig())
        } finally { dir.deleteRecursively() }
    }

    private fun kotlinx.serialization.json.JsonElement.jsonPrimitive(): JsonPrimitive = this as JsonPrimitive
}
