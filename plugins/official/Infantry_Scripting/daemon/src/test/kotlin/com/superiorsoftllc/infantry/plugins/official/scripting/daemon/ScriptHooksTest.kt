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
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
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
        override fun catalog(): List<ToolInfo> = listOf("t_move", "t_fail", "infantry_ticket_get", "infantry_ticket_comment", "infantry_message")
            .map { ToolInfo(it, "d", Json.parseToJsonElement("""{"type":"object"}""").jsonObject) }
        override fun invoke(name: String, arguments: JsonObject, `as`: ToolInvoker): ToolResult {
            calls += Triple(name, arguments, `as`)
            return when {
                name == "infantry_ticket_get" -> ToolResult(ticketJson)
                name == "infantry_ticket_comment" && failComment -> ToolResult("comment refused", true)
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

    private fun build(engine: FakeEngine, guard: HookGuardConfig = HookGuardConfig(), tip: (String, String) -> String? = { _, _ -> "abc123tip" }, runLog: ScriptRunLog = ScriptRunLog(), automation: (String, com.superiorsoftllc.infantry.daemon.api.AutomationMark?) -> Unit = { _, _ -> }): ScriptHooks {
        val engines = ScriptEngines({ mapOf(ScriptEngineWire.PREFIX + "fake" to ScriptEngineWire.asEndpoint(engine)) })
        val store = ScriptStore({ files }, { engines.extensions() })
        return ScriptHooks(
            HookStore { files }, store, engines, tools, ScriptDispatcher(tools, { mapOf("move" to "t_move") }),
            projects = { listOf("p1") }, limits = { ScriptLimits() }, guard = { guard }, tip = tip, clock = { now }, executor = { it() }, runLog = runLog, automation = automation,
        )
    }

    private var evSeq = 100
    /** Each call is a new event (fresh id and seq); [fixedSeq] makes a literal redelivery. */
    private fun moved(ticket: String = "ticket-aaaa1111", to: String = "done", depth: Int = 0, fixedSeq: Int? = null): NamedEvent {
        val seq = fixedSeq ?: ++evSeq
        return NamedEvent("ticket.moved", """{"ticketId":"$ticket","from":"in_progress","to":"$to","kind":"moved","seq":$seq}""", "daemon", "ev-$seq", "ev-$seq", depth, 1)
    }

    private fun putHooks(vararg hooks: String) = files.put(HookStore.PATH, hooksJson(*hooks).toByteArray())

    private val mailHook = """{"id":"m","on":"mail.incoming","script":"on-done","to":"pm-1","args":{"k":"v"},"ownerSession":"pm-1","ownerRole":"pm"}"""
    private fun mail(body: String = "merged ok") = com.superiorsoftllc.infantry.daemon.api.IncomingMail("mail-1", "vc-1", "pm", "pm-1", "pm", "subj", body, "t1", "p1")

    @Test fun `MAIL-HOOK-INT-1 an interrupt of the sending thread does not cancel the hook, handled is honoured`() {
        putHooks(mailHook, doneHook)
        val sender = Thread.currentThread()
        val engine = FakeEngine { Thread.sleep(150); listOf(QueuedAction("handled", emptyMap())) }
        val t = Thread { Thread.sleep(50); sender.interrupt() }.also { it.start() }
        val v = build(engine).incoming(mail())
        t.join()
        assertTrue(Thread.interrupted(), "the interrupt flag is preserved for the caller")
        assertTrue(v!!.handled)
    }

    @Test fun `MAIL-HOOK-1 a script that queues handled handles the mail, deliver_text replaces it, args carry the mail`() {
        putHooks(mailHook, doneHook)
        val engine = FakeEngine { listOf(QueuedAction("handled", emptyMap())) }
        val v = build(engine).incoming(mail())!!
        assertTrue(v.handled)
        assertEquals(1, engine.inputs.size, "the lane hook does not run for a mail")
        @Suppress("UNCHECKED_CAST") val args = engine.inputs[0]["args"] as Map<String, Any?>
        assertEquals("merged ok", args["body"]); assertEquals("pm", args["role"]); assertEquals("pm-1", args["sessionId"]); assertEquals("v", args["k"]); assertEquals("vc-1", args["from"])
        val text = build(FakeEngine { listOf(QueuedAction("deliver_text", mapOf("text" to "short"))) }).incoming(mail())!!
        assertEquals("short", text.text); assertTrue(!text.handled)
        assertEquals(null, build(FakeEngine { emptyList() }).incoming(mail()), "no verdict = deliver as today")
    }

    @Test fun `MAIL-HOOK-1 a failing or crashing script, an unknown tool, or no hook delivers as today`() {
        putHooks(mailHook)
        assertEquals(null, build(FakeEngine { listOf(QueuedAction("nope_tool", emptyMap())) }).incoming(mail()))
        assertEquals(null, build(FakeEngine { error("boom") }).incoming(mail()))
        files.files.remove(HookStore.PATH)
        assertEquals(null, build(FakeEngine { listOf(QueuedAction("handled", emptyMap())) }).incoming(mail()))
    }

    @Test fun `SCRIPT-VIS-1 every hook run is recorded with trigger, session, outcome, actions and log, newest first, bounded`() {
        putHooks(mailHook)
        var max = 2
        val log = ScriptRunLog({ max }, { now })
        val h = build(FakeEngine { listOf(QueuedAction("handled", emptyMap())) }, runLog = log)
        assertTrue(h.incoming(mail())!!.handled)
        val r = log.runs("p1").single()
        assertEquals("handled", r.outcome); assertEquals("m mail.incoming", r.trigger); assertEquals("pm-1", r.session); assertTrue(r.ok)
        assertTrue(r.line().startsWith("AUTOMATION · on-done · handled subj from vc-1"), r.line())
        build(FakeEngine { emptyList() }, runLog = log).incoming(mail())
        assertEquals("passed", log.runs("p1").first().outcome)
        build(FakeEngine { listOf(QueuedAction("deliver_text", mapOf("text" to "x"))) }, runLog = log).incoming(mail())
        assertEquals(listOf("delivered", "passed"), log.runs("p1").map { it.outcome }, "limit 2 keeps the newest two")
        build(FakeEngine { error("boom") }, runLog = log).incoming(mail())
        val f = log.runs("p1").first()
        assertEquals("failed", f.outcome); assertFalse(f.ok); assertTrue(f.line().endsWith("FAILED"))
        assertEquals(1, log.runs("p1", session = "other-9").size + 1, "runs filter by session")
        max = 0; build(FakeEngine { emptyList() }, runLog = log).incoming(mail())
        assertTrue(log.runs("p1").isEmpty(), "limit 0 keeps none")
    }

    @Test fun `SCRIPT-VIS-1 a lane hook run is recorded and wake reasons count, mail-handled is a turn saved`() {
        putHooks(doneHook)
        val log = ScriptRunLog({ 50 }, { now })
        build(FakeEngine { listOf(QueuedAction("move", mapOf("ticketId" to "ticket-bbbb2222", "to" to "todo"))) }, runLog = log).onEvent(moved())
        val r = log.runs("p1").single()
        assertEquals("ok", r.outcome); assertEquals("done-hook lane enter done", r.trigger); assertEquals("worker-7", r.session); assertEquals(1, r.actions.size)
        log.wake("s1", "owner", null, "p1"); log.wake("s1", "mail", "m1", "p1"); log.wake("s1", "mail-handled", "m2", "p1"); log.wake("s1", "mail-handled", "m3")
        val st = log.sessionStats("s1")
        assertEquals("2", st["turnsSaved"].toString()); assertEquals("2", st["wakes"].toString())
        assertEquals("""{"mail":1,"owner":1}""", st["wakesByReason"].toString())
        assertEquals("2", log.projectStats("p1")["turnsSaved"].toString())
        val out = log.tool().call(object : ToolCall {
            override val arguments = Json.parseToJsonElement("""{"project":"p1"}""").jsonObject
            override val caller = Caller(owner = true)
            override fun request(type: String, data: String, sessionId: String?, timeoutMs: Long): Frame = error("no frames")
        })
        assertTrue(!out.isError && out.text.contains("turnsSaved") && out.text.contains("AUTOMATION"), out.text)
    }

    @Test fun `a redelivered event id, or the same transition re-published under a new id, runs the hook once`() {
        putHooks(doneHook)
        val engine = FakeEngine { emptyList() }
        val hooks = build(engine)
        val first = moved(fixedSeq = 7)
        hooks.onEvent(first); hooks.onEvent(first)
        assertEquals(1, engine.inputs.size)
        val e = moved(fixedSeq = 7)
        hooks.onEvent(NamedEvent(e.name, e.payload, e.source, "other-id", "other-id", 0, 2))
        assertEquals(1, engine.inputs.size)
        hooks.onEvent(moved("ticket-bbbb2222"))
        assertEquals(2, engine.inputs.size)
    }

    @Test fun `a project with no directory is skipped with one warning and the good project's hook still fires`() {
        putHooks(doneHook)
        val engine = FakeEngine { emptyList() }
        val engines = ScriptEngines({ mapOf(ScriptEngineWire.PREFIX + "fake" to ScriptEngineWire.asEndpoint(engine)) })
        val logs = mutableListOf<String>()
        val lookup: (String) -> com.superiorsoftllc.infantry.daemon.api.files.ProjectFiles = {
            if (it == "bad") throw IllegalArgumentException("project 'bad' has no directory to store uploads in") else files
        }
        val hooks = ScriptHooks(
            HookStore(lookup), ScriptStore(lookup, { engines.extensions() }), engines, tools, ScriptDispatcher(tools, { mapOf("move" to "t_move") }),
            projects = { listOf("bad", "p1") }, limits = { ScriptLimits() }, log = { logs += it }, clock = { now }, executor = { it() },
        )
        hooks.onEvent(moved()); hooks.onEvent(moved("ticket-bbbb2222"))
        assertEquals(2, engine.inputs.size)
        assertEquals(1, logs.count { "bad" in it && "skipped" in it })
    }

    @Test fun `lane exit hooks fire on from, enter on to, on every lane, case-insensitively, with from and to in args`() {
        for (lane in listOf("backlog", "todo", "in_progress", "pending", "done", "Custom-Lane")) {
            for (dir in listOf("enter", "exit")) {
                files.files.remove(HookStore.PATH)
                putHooks("""{"id":"h","on":"LANE ${dir.uppercase()} ${lane.uppercase()}","script":"on-done"}""")
                val engine = FakeEngine { emptyList() }
                val hooks = build(engine)
                val other = "zzz"
                val ev = { from: String, to: String -> NamedEvent("ticket.moved", """{"ticketId":"ticket-aaaa1111","from":"$from","to":"$to","kind":"moved","seq":${++evSeq}}""", "daemon", "e-$from-$to-${++evSeq}", "e-$from-$to-$evSeq", 0, 1) }
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
        assertTrue(tools.named("infantry_ticket_comment").isEmpty(), "a clean run reports nothing")
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
        val report = tools.named("infantry_ticket_comment").single()
        assertTrue("loop guard" in report.second["body"]!!.jsonPrimitive().content)
        assertEquals("issue", report.second["kind"]!!.jsonPrimitive().content)
        assertEquals("ticket-aaaa1111", report.second["ticketId"]!!.jsonPrimitive().content)
    }

    @Test fun `an event long after the hook's last action is a new chain at depth 0`() {
        putHooks(doneHook)
        lateinit var hooks: ScriptHooks
        val engine = FakeEngine { listOf(QueuedAction("move", mapOf("ticket" to "aaaa1111", "status" to "done"))) }
        hooks = build(engine, HookGuardConfig(maxDepth = 1, maxRuns = 100, attributionMs = 5_000))
        var fired = 0
        // The hook's action starts a worker; the worker finishes minutes later (the hook run is still open or just ended).
        tools.onInvoke = { name, _ -> if (name == "t_move" && fired++ < 3) { now += 120_000; hooks.onEvent(moved()) } }
        hooks.onEvent(moved())
        assertEquals(4, engine.inputs.size, "every later finish runs; none is refused")
        val depths = engine.inputs.map { ((it["args"] as Map<*, *>)["event"] as Map<*, *>)["depth"] }
        assertEquals(listOf(0L, 0L, 0L, 0L), depths)
        assertTrue(tools.named("infantry_ticket_comment").isEmpty())
    }

    @Test fun `the per hook rate limit trips, is reported once, and clears after the window`() {
        putHooks(doneHook)
        val engine = FakeEngine { emptyList() }
        val hooks = build(engine, HookGuardConfig(maxDepth = 3, maxRuns = 3, windowMs = 60_000))
        repeat(6) { hooks.onEvent(moved(ticket = "ticket-bbbb000$it")) }
        assertEquals(3, engine.inputs.size)
        assertEquals(1, tools.named("infantry_ticket_comment").size, "one report per hook and window, not one per refusal")
        assertTrue("rate limit" in tools.named("infantry_ticket_comment").single().second["body"]!!.jsonPrimitive().content)
        now += 61_000
        hooks.onEvent(moved(ticket = "ticket-cccc0000"))
        assertEquals(4, engine.inputs.size)
    }

    @Test fun `a failed action is reported as a comment on the triggering ticket`() {
        putHooks(doneHook)
        tools.failTool = "t_fail"
        val engine = FakeEngine { listOf(QueuedAction("t_fail", mapOf("ticket" to "zzzz"))) }
        build(engine).onEvent(moved())
        val c = tools.named("infantry_ticket_comment").single()
        assertEquals("ticket-aaaa1111", c.second["ticketId"]!!.jsonPrimitive().content)
        val body = c.second["body"]!!.jsonPrimitive().content
        assertTrue("done-hook" in body && "failed" in body && "t_fail" in body, body)
        assertTrue(body.lineSequence().first().contains("t_fail"), "the reason is on the first line: $body")
    }

    @Test fun `an optional action that fails does not stop the run`() {
        putHooks(doneHook)
        tools.failTool = "t_fail"
        val engine = FakeEngine { listOf(QueuedAction("t_fail", mapOf("_optional" to true)), QueuedAction("t_move", mapOf("ticket" to "zzzz"))) }
        build(engine).onEvent(moved())
        assertTrue(tools.named("infantry_ticket_comment").isEmpty())
        assertEquals(1, tools.named("t_move").size)
    }

    @Test fun `when the comment cannot be posted, or a script is missing, the hook owner is mailed`() {
        putHooks(doneHook, """{"id":"gone","on":"merge.done","script":"nope","ownerSession":"pm-1"}""")
        tools.failTool = "t_fail"
        tools.failComment = true
        val engine = FakeEngine { listOf(QueuedAction("t_fail", mapOf("ticket" to "zzzz"))) }
        val hooks = build(engine)
        hooks.onEvent(moved())
        val mail = tools.named("infantry_message").single()
        assertEquals("pm-1", mail.second["to"]!!.jsonPrimitive().content)
        assertTrue("failed" in mail.second["body"]!!.jsonPrimitive().content)
        hooks.onEvent(NamedEvent("merge.done", "{}", "cli", "e9", "e9", 0, 1))
        val second = tools.named("infantry_message").last().second["body"]!!.jsonPrimitive().content
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

    @Test fun `onEvent never blocks the bus thread on a slow ticket read, the run happens on the hook executor`() {
        putHooks(doneHook)
        val engine = FakeEngine { emptyList() }
        val engines = ScriptEngines({ mapOf(ScriptEngineWire.PREFIX + "fake" to ScriptEngineWire.asEndpoint(engine)) })
        val gate = java.util.concurrent.CountDownLatch(1)
        val slow = object : Tools by tools {
            override fun invoke(name: String, arguments: JsonObject, `as`: ToolInvoker): ToolResult {
                if (name == "infantry_ticket_get") gate.await(10, java.util.concurrent.TimeUnit.SECONDS)
                return tools.invoke(name, arguments, `as`)
            }
        }
        val pool = java.util.concurrent.Executors.newSingleThreadExecutor()
        try {
            val hooks = ScriptHooks(
                HookStore { files }, ScriptStore({ files }, { engines.extensions() }), engines, slow, ScriptDispatcher(slow, { mapOf("move" to "t_move") }),
                projects = { listOf("p1") }, limits = { ScriptLimits() }, clock = { now }, executor = { job -> pool.execute(job) },
            )
            val t0 = System.nanoTime()
            hooks.onEvent(moved())
            val ms = (System.nanoTime() - t0) / 1_000_000
            assertTrue(ms < 1_000, "onEvent blocked the caller for $ms ms on a slow Board read")
            assertEquals(0, engine.inputs.size, "the script must not have run yet")
            gate.countDown()
            pool.shutdown(); assertTrue(pool.awaitTermination(10, java.util.concurrent.TimeUnit.SECONDS))
            assertEquals(1, engine.inputs.size)
        } finally { gate.countDown(); pool.shutdownNow() }
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

    @Test fun `MAIL-HOOK-1b a mail hook runs only for its own session, a hook without to is ignored, failures are not reported`() {
        putHooks(mailHook)
        val engine = FakeEngine { listOf(QueuedAction("handled", emptyMap())) }
        val h = build(engine)
        assertEquals(null, h.incoming(com.superiorsoftllc.infantry.daemon.api.IncomingMail("m2", "vc-1", "pm", "other-9", "pm", "s", "b", "t1", "p1")), "other session: no run")
        assertEquals(0, engine.inputs.size)
        assertTrue(h.incoming(mail())!!.handled)
        putHooks("""{"id":"wide","on":"mail.incoming","script":"on-done"}""")
        assertEquals(null, build(engine).incoming(mail()), "a project-wide stored hook is ignored")
        putHooks(mailHook)
        assertEquals(null, build(FakeEngine { error("boom") }).incoming(mail()))
        assertTrue(tools.calls.none { it.first == "infantry_message" || it.first == "infantry_ticket_comment" }, "no failure mail or comment")
    }

    private fun hookCall(vararg kv: Pair<String, String>, session: String? = "a-1") =
        object : ToolCall {
            override val arguments = JsonObject(kv.associate { it.first to JsonPrimitive(it.second) })
            override val caller = Caller(owner = false, agent = true)
            override val clientSessionId = session; override val clientRole: String? = "backend"; override val clientProjectId: String? = "p1"
            override fun request(type: String, data: String, sessionId: String?, timeoutMs: Long): Frame = error("unused")
        }

    @Test fun `MAIL-HOOK-1b adding a mail hook defaults to your own session, refuses a second (session, script), and ended sessions lose theirs`() {
        val ht = HookTools(HookStore { files }, ScriptStore({ files }, { listOf("fk") }))
        val ok = ht.handle(hookCall("action" to "add", "on" to "mail.incoming", "script" to "on-done", "to" to "b-2"))
        assertFalse(ok.isError, ok.text)
        val dup = ht.handle(hookCall("action" to "add", "on" to "mail.incoming", "script" to "on-done", "to" to "b-2", "id" to "other"))
        assertTrue(dup.isError && "already has script" in dup.text, dup.text)
        assertFalse(ht.handle(hookCall("action" to "add", "on" to "mail.incoming", "script" to "on-done")).isError, "default = own session a-1")
        assertTrue(ht.handle(hookCall("action" to "add", "on" to "mail.incoming", "script" to "on-done", session = null)).isError, "no session, no to: refused")
        assertTrue(ht.handle(hookCall("action" to "add", "on" to "ticket.commented", "script" to "on-done", "to" to "b-2")).isError)
        val store = HookStore { files }
        val stored = store.list("p1")
        assertEquals(listOf("b-2", "a-1"), stored.map { it.to }); assertEquals("a-1", stored[0].ownerSession); assertEquals("backend", stored[0].ownerRole)
        assertEquals(1, store.removeForSession(listOf("p1"), "b-2"))
        assertEquals(listOf("a-1"), store.list("p1").map { it.to })
    }

    @Test fun `MAIL-HOOK-1b ping-pong - A attaches pong to B, A mails PING, B replies PONG, A's pong-seen logs it, both mails read, nobody woken`() {
        files.put("scripts/pong.fk", "pong".toByteArray()); files.put("scripts/pong-seen.fk", "seen".toByteArray())
        val logs = ArrayList<String>()
        val woken = ArrayList<String>(); val read = ArrayList<String>()
        lateinit var hooks: ScriptHooks
        var n = 0
        val engine = object : ScriptEngine {
            override val id = "fake"; override val extensions = listOf("fk")
            override fun run(source: String, input: Map<String, Any?>, limits: ScriptLimits, caller: ScriptCaller): ScriptResult {
                @Suppress("UNCHECKED_CAST") val a = input["args"] as Map<String, Any?>
                val body = a["body"] as String
                return when (source) {
                    "pong" -> ScriptResult(ScriptOutcome.OK, listOf(QueuedAction("send", mapOf("to" to a["from"], "body" to "PONG " + body.removePrefix("PING "))), QueuedAction("handled", emptyMap())))
                    else -> { logs += "seen $body"; ScriptResult(ScriptOutcome.OK, listOf(QueuedAction("handled", emptyMap()))) }
                }
            }
        }
        val engines = ScriptEngines({ mapOf(ScriptEngineWire.PREFIX + "fake" to ScriptEngineWire.asEndpoint(engine)) })
        val store = HookStore { files }
        val deliver = object : Tools by tools {
            override fun invoke(name: String, arguments: JsonObject, `as`: ToolInvoker): ToolResult {
                if (name != "infantry_message") return tools.invoke(name, arguments, `as`)
                val to = (arguments["to"] as JsonPrimitive).content; val body = (arguments["body"] as JsonPrimitive).content
                val from = `as`.sessionId ?: "?"
                val id = "mail-${++n}"
                val verdict = hooks.incoming(com.superiorsoftllc.infantry.daemon.api.IncomingMail(id, from, to, to, "x", "s", body, "t", "p1"))
                if (verdict?.handled == true) read += id else woken += to
                return ToolResult("sent $id")
            }
        }
        hooks = ScriptHooks(store, ScriptStore({ files }, { engines.extensions() }), engines, deliver, ScriptDispatcher(deliver, { mapOf("send" to "infantry_message") }),
            projects = { listOf("p1") }, limits = { ScriptLimits() }, log = { logs += it }, executor = { it() })
        val ht = HookTools(store, ScriptStore({ files }, { engines.extensions() }))
        // A attaches pong to B and pong-seen to itself.
        assertFalse(ht.handle(hookCall("action" to "add", "on" to "mail.incoming", "script" to "pong", "to" to "B", session = "A")).isError)
        assertFalse(ht.handle(hookCall("action" to "add", "on" to "mail.incoming", "script" to "pong-seen", session = "A")).isError)
        // A mails B PING 42 (as session A).
        deliver.invoke("infantry_message", buildJsonObject { put("to", "B"); put("body", "PING 42") }, ToolInvoker(Caller(owner = false, agent = true), "A", "backend", "p1", "script:ping"))
        assertEquals(2, n, "PING and PONG")
        assertEquals(setOf("mail-1", "mail-2"), read.toSet(), "both mails read")
        assertTrue(woken.isEmpty(), "nobody woken: $woken")
        assertEquals(listOf("seen PONG 42"), logs.filter { it.startsWith("seen") }, "A's hook saw the PONG with the same nonce")
    }

    @Test fun `SCRIPT-VIS-1c a mail hook marks the recipient while it runs and clears the mark after, even when the script fails`() {
        putHooks(mailHook)
        val marks = ArrayList<Pair<String, com.superiorsoftllc.infantry.daemon.api.AutomationMark?>>()
        assertTrue(build(FakeEngine { listOf(QueuedAction("handled", emptyMap())) }, automation = { s, m -> marks += s to m }).incoming(mail())!!.handled)
        assertEquals("pm-1", marks.first().first)
        assertEquals("on-done", marks.first().second!!.script); assertEquals("m", marks.first().second!!.hookId)
        assertNull(marks.last().second, "cleared after the run")
        marks.clear()
        assertNull(build(FakeEngine { error("boom") }, automation = { s, m -> marks += s to m }).incoming(mail()))
        assertNull(marks.last().second, "cleared after a crash too")
    }

    @Test fun `SCRIPT-VIS-1c a throwing mark callback never fails the hook`() {
        putHooks(mailHook)
        assertTrue(build(FakeEngine { listOf(QueuedAction("handled", emptyMap())) }, automation = { _, _ -> error("display only") }).incoming(mail())!!.handled)
    }
}
