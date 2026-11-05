// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.harness

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import com.superiorsoftllc.infantry.harness.AgentProvider
import com.superiorsoftllc.infantry.plugins.official.opencode.harness.OpenCodeCommand
import com.superiorsoftllc.infantry.plugins.official.opencode.harness.OpenCodePermissions
import com.superiorsoftllc.infantry.plugins.official.opencode.harness.OpenCodeServeSession
import com.superiorsoftllc.infantry.harness.RunRequest
import com.superiorsoftllc.infantry.harness.ToolApproval
import com.superiorsoftllc.infantry.runner.ProcessListener
import com.superiorsoftllc.infantry.runner.ProcessRunner
import com.superiorsoftllc.infantry.runner.ProcessSpec
import com.superiorsoftllc.infantry.runner.RunningProcess
import com.superiorsoftllc.infantry.session.JsonSessionStore
import com.superiorsoftllc.infantry.session.SessionEvent
import com.superiorsoftllc.infantry.session.SessionManager
import com.superiorsoftllc.infantry.session.SessionState
import com.superiorsoftllc.infantry.session.isBusy
import com.superiorsoftllc.infantry.session.host.LifecyclePolicy
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import com.superiorsoftllc.infantry.prompts.PendingPrompts
import com.superiorsoftllc.infantry.protocol.EventNormalizer
import com.superiorsoftllc.infantry.protocol.PromptRequest
import com.superiorsoftllc.infantry.protocol.PromptResponse
import java.io.File
import java.io.OutputStream
import java.net.InetSocketAddress
import java.util.Collections
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * BUG-M2-11b-2: the `opencode serve` PROTOCOL ([OpenCodeServeSession], H8b-3: moved from the core serve adapter test; the plugin session runs behind the core ApiSessionAdapter), driven by the daemon's session host through a real
 * [SessionManager]. The lifecycle rules (reuse, crash restart, close, delete, shutdown, boot lines) belong to the host,
 * proven for every harness in `session/host/SessionHostTest` and `SessionManagerHostTest` over a fake adapter. The
 * `opencode serve` child is a fake too: a counting launcher that answers the HTTP + SSE API in-process.
 */
class OpenCodeServeHostTest {

    /** One fake `opencode serve` process: the slice of the HTTP API the transport uses, plus `/event` SSE. */
    private class FakeServe(port: Int, private val password: String?, private val listener: ProcessListener) : RunningProcess {
        private val http = HttpServer.create(InetSocketAddress("127.0.0.1", port), 0)
        private val streams = CopyOnWriteArrayList<OutputStream>()
        val prompts: MutableList<Pair<String, String>> = Collections.synchronizedList(mutableListOf())
        val aborts = AtomicInteger()
        /** PROMPT-4: every `POST /permission/{id}/reply`, as (id, reply). */
        val replies: MutableList<Pair<String, String>> = Collections.synchronizedList(mutableListOf())
        @Volatile private var alive = true
        /** False = a turn stays in flight until something else ends it (an interrupt). */
        @Volatile var answerTurns = true
        /** DEMO-MODEL-1: sessions whose stored history the provider refuses: every prompt there ends in a 400 `invalid_request`. */
        val poisoned: MutableSet<String> = Collections.synchronizedSet(mutableSetOf())
        private var nextSession = 0

        init {
            http.createContext("/") { ex -> runCatching { handle(ex) }.onFailure { ex.close() } }
            http.executor = java.util.concurrent.Executors.newCachedThreadPool { r -> Thread(r).also { it.isDaemon = true } }
            http.start()
            listener.onStdout("opencode server listening on http://127.0.0.1:$port")
        }

        private fun handle(ex: HttpExchange) {
            val expected = "Basic " + java.util.Base64.getEncoder().encodeToString("opencode:$password".toByteArray())
            if (ex.requestHeaders.getFirst("Authorization") != expected) return reply(ex, 401, "")
            val path = ex.requestURI.path
            val body = ex.requestBody.readBytes().decodeToString()
            when {
                path == "/event" -> {
                    ex.responseHeaders.add("Content-Type", "text/event-stream")
                    ex.sendResponseHeaders(200, 0)
                    streams += ex.responseBody
                    push("""{"type":"server.connected","properties":{}}""")
                }
                path == "/session" -> reply(ex, 200, """{"id":"ses_fake${++nextSession}"}""")
                path.endsWith("/prompt_async") -> {
                    val ses = path.removePrefix("/session/").removeSuffix("/prompt_async")
                    val text = ((Json.parseToJsonElement(body) as JsonObject)["parts"].toString())
                    prompts += ses to text
                    reply(ex, 204, null)
                    if (ses in poisoned) Thread { refuse(ses) }.start()
                    else if (answerTurns) Thread { answer(ses, prompts.size) }.start()
                }
                path.startsWith("/session/") && path.endsWith("/message") && ex.requestMethod == "GET" -> {
                    val ses = path.removePrefix("/session/").removeSuffix("/message")
                    val messages = buildJsonArray {
                        synchronized(prompts) { prompts.filter { it.first == ses } }.forEachIndexed { i, (_, parts) ->
                            add(buildJsonObject {
                                put("info", buildJsonObject { put("id", "msg_$i"); put("role", "user") })
                                put("parts", Json.parseToJsonElement(parts))
                            })
                        }
                    }
                    reply(ex, 200, messages.toString())
                }
                path.endsWith("/abort") -> { aborts.incrementAndGet(); reply(ex, 200, "true") }
                path.startsWith("/permission/") && path.endsWith("/reply") -> {
                    val id = path.removePrefix("/permission/").removeSuffix("/reply")
                    replies += id to (Json.parseToJsonElement(body) as JsonObject)["reply"]!!.jsonPrimitive.content
                    reply(ex, 200, "true")
                }
                else -> reply(ex, 404, "{}")
            }
        }

        private fun answer(ses: String, n: Int) {
            push("""{"type":"session.status","properties":{"sessionID":"$ses","status":{"type":"busy"}}}""")
            push("""{"type":"message.part.updated","properties":{"sessionID":"$ses","part":{"id":"p$n-a","sessionID":"$ses","type":"step-start"},"time":1}}""")
            push("""{"type":"message.part.updated","properties":{"sessionID":"$ses","part":{"id":"p$n-b","sessionID":"$ses","type":"text","text":"reply $n","time":{"start":1}},"time":2}}""")
            push("""{"type":"message.part.updated","properties":{"sessionID":"$ses","part":{"id":"p$n-b","sessionID":"$ses","type":"text","text":"reply $n","time":{"start":1,"end":3}},"time":3}}""")
            push("""{"type":"message.part.updated","properties":{"sessionID":"$ses","part":{"id":"p$n-c","sessionID":"$ses","type":"step-finish","reason":"stop","tokens":{"input":1,"output":1}},"time":4}}""")
            push("""{"type":"session.status","properties":{"sessionID":"$ses","status":{"type":"idle"}}}""")
            push("""{"type":"session.idle","properties":{"sessionID":"$ses"}}""")
        }

        /** What opencode serve sends when the provider answers 400 `invalid_request` (DEMO-CHECK-1's poisoned free-model session). */
        private fun refuse(ses: String) {
            push("""{"type":"session.status","properties":{"sessionID":"$ses","status":{"type":"busy"}}}""")
            push("""{"type":"session.error","properties":{"sessionID":"$ses","error":{"name":"APIError","data":{"message":"Bad Request: invalid_request","statusCode":400,"isRetryable":false,"responseBody":"{\"error\":{\"type\":\"invalid_request\"}}"}}}}""")
            push("""{"type":"session.status","properties":{"sessionID":"$ses","status":{"type":"idle"}}}""")
            push("""{"type":"session.idle","properties":{"sessionID":"$ses"}}""")
        }

        /** PROMPT-4: opencode 1.x asks for [permission] on [patterns]. */
        fun ask(ses: String, id: String, permission: String, vararg patterns: String) = push(
            buildJsonObject {
                put("type", "permission.asked")
                put("properties", buildJsonObject {
                    put("id", id); put("sessionID", ses); put("permission", permission)
                    put("patterns", buildJsonArray { patterns.forEach { add(it) } })
                    put("metadata", buildJsonObject {}); put("always", buildJsonArray {})
                })
            }.toString(),
        )

        fun push(json: String) {
            val bytes = "data: $json\n\n".toByteArray()
            for (s in streams) runCatching { synchronized(s) { s.write(bytes); s.flush() } }
        }

        private fun reply(ex: HttpExchange, code: Int, body: String?) {
            if (body == null) ex.sendResponseHeaders(code, -1) else {
                val bytes = body.toByteArray()
                ex.sendResponseHeaders(code, bytes.size.toLong())
                ex.responseBody.use { it.write(bytes) }
            }
            ex.close()
        }

        /** The process dies on its own (a crash). */
        fun crash() = die(1)

        private fun die(code: Int) {
            if (!alive) return
            alive = false
            for (s in streams) runCatching { s.close() }
            http.stop(0)
            listener.onExit(code)
        }

        override val isAlive: Boolean get() = alive
        override val pid: Long = 4242
        override fun writeInput(text: String) = Unit
        override fun closeInput() = Unit
        override fun killTree() = die(137)
        override fun awaitExit(): Int = 0
    }

    /** Counts every process it is asked to spawn; a `serve` argv becomes a [FakeServe]. */
    private class CountingLauncher : ProcessRunner {
        override val id = "counting"
        val spawns = AtomicInteger()
        val servers = CopyOnWriteArrayList<FakeServe>()
        val specs = CopyOnWriteArrayList<ProcessSpec>()
        override fun start(spec: ProcessSpec, listener: ProcessListener): RunningProcess {
            spawns.incrementAndGet()
            specs += spec
            check(spec.command.getOrNull(1) == "serve") { "only serve is faked: ${spec.command}" }
            val port = spec.command[spec.command.indexOf("--port") + 1].toInt()
            return FakeServe(port, spec.env[OpenCodeServeSession.PASSWORD_ENV], listener).also { servers += it }
        }
    }


    /** The one-shot path: one `opencode run` per reply, counted so "one process" is measured across both paths. */
    private class OneShotRunner : ProcessRunner {
        override val id = "one-shot"
        val spawns = AtomicInteger()
        override fun start(spec: ProcessSpec, listener: ProcessListener): RunningProcess {
            spawns.incrementAndGet()
            return object : RunningProcess {
                @Volatile var alive = true
                override val isAlive get() = alive
                override fun writeInput(text: String) = Unit
                override fun closeInput() {
                    Thread {
                        listener.onStdout("""{"type":"step_start","sessionID":"ses_oneshot"}""")
                        listener.onStdout("""{"type":"text","sessionID":"ses_oneshot","part":{"type":"text","text":"ok"}}""")
                        alive = false
                        listener.onExit(0)
                    }.start()
                }
                override fun killTree() { alive = false }
                override fun awaitExit() = 0
            }
        }
    }

    private class Rig(keepAliveMinutes: Int, promptTimeoutMs: Long = 10_000) {
        /** PROMPT-4: the daemon's prompt API, installed as the manager's [SessionManager.promptAsker]. */
        val prompts = PendingPrompts(timeoutMillis = { promptTimeoutMs })
        val launcher = CountingLauncher()
        val oneShot = OneShotRunner()
        val events: MutableList<SessionEvent> = Collections.synchronizedList(mutableListOf())
        private val adapter = ApiSessionAdapter(
            "opencode", "Infantry_OpenCode",
            OpenCodeServeSession(null, { "opencode.exe" }, bootTimeoutMs = 5_000, modelFor = { it ?: "fake/model" }), {},
        )
        /** The plugin's serve session behind the daemon's adapter, with a one-shot argv that needs no installed opencode.exe. */
        val provider = object : AgentProvider {
            override val id = "opencode"
            override fun spec(request: RunRequest) =
                ProcessSpec(OpenCodeCommand.command("opencode.exe", request.model ?: "fake/model", request.resumeSessionId), request.workingDir)
            override fun defaultModel() = "fake/model"
            override fun sessionAdapter() = adapter
        }
        val manager = SessionManager(
            provider, oneShot, JsonSessionStore(File.createTempFile("infantry-serve", ".json").also { it.delete() }),
            lifecyclePolicy = { LifecyclePolicy(keepAliveMinutes) },
            lineRunner = launcher,
        ).also { m ->
            m.eventSink = { events += it; prompts.onSessionEvent(it) }
            m.promptAsker = prompts
        }

        /** PROMPT-4: the one open prompt, once it is open. */
        fun awaitPrompt(): PromptRequest {
            val deadline = System.currentTimeMillis() + 10_000
            while (true) {
                prompts.snapshot().singleOrNull()?.let { return it }
                if (System.currentTimeMillis() > deadline) fail("no prompt opened: $events")
                Thread.sleep(10)
            }
        }

        // H8b-3: the generic adapter forwards every raw stdout line, so the server banner ("opencode server listening on ...") is an event too; only JSON lines are run lines.
        fun stdoutLines(): List<JsonObject> = synchronized(events) { events.filterIsInstance<SessionEvent.Stdout>().map { it.line } }
            .filter { it.startsWith("{") }.map { Json.parseToJsonElement(it) as JsonObject }

        fun awaitState(id: String, state: SessionState) {
            val deadline = System.currentTimeMillis() + 10_000
            while (manager.get(id)?.state != state) {
                if (System.currentTimeMillis() > deadline) fail("never reached $state: ${manager.get(id)} $events")
                Thread.sleep(10)
            }
        }

        fun texts(): List<JsonObject> = synchronized(events) { events.filterIsInstance<SessionEvent.Stdout>().map { it.line } }
            .filter { it.startsWith("{") }.map { Json.parseToJsonElement(it) as JsonObject }.filter { it["type"]?.jsonPrimitive?.content == "text" }

        fun awaitTexts(count: Int) {
            val deadline = System.currentTimeMillis() + 10_000
            while (texts().size < count) {
                if (System.currentTimeMillis() > deadline) fail("expected $count text lines: $events")
                Thread.sleep(10)
            }
        }
    }

    private val rigs = mutableListOf<Rig>()
    private fun rig(keepAliveMinutes: Int = 30, promptTimeoutMs: Long = 10_000) = Rig(keepAliveMinutes, promptTimeoutMs).also { rigs += it }

    @AfterTest
    fun tearDown() = rigs.forEach { it.manager.shutdown() }

    @Test
    fun twoPrompts_oneServeProcess_oneOpencodeSession_runLinesReEmitted() {
        val rig = rig()
        val session = rig.manager.startNew("first", model = "fake/model")
        rig.awaitTexts(1)
        rig.awaitState(session.id, SessionState.IDLE)
        rig.manager.resume(session.id, "second")
        rig.awaitTexts(2)
        rig.awaitState(session.id, SessionState.IDLE)

        assertEquals(1, rig.launcher.spawns.get(), "two prompts share ONE opencode serve process")
        assertEquals(0, rig.oneShot.spawns.get(), "and no `opencode run` either")
        val server = rig.launcher.servers.single()
        assertEquals(listOf("ses_fake1", "ses_fake1"), server.prompts.map { it.first }, "both turns go to the same opencode session")
        assertTrue(server.prompts[1].second.contains("second"), "the prompt is the turn's text: ${server.prompts}")
        assertTrue(server.isAlive, "the process outlives the turn")
        assertEquals("ses_fake1", rig.manager.get(session.id)?.providerSessionId)
        // The re-emitted lines are what `opencode run --format json` prints: the text part once, when it is finished.
        assertEquals(listOf("reply 1", "reply 2"), rig.texts().map { (it["part"] as JsonObject)["text"]!!.jsonPrimitive.content })
        assertTrue(rig.events.filterIsInstance<SessionEvent.Exit>().none { it.processExited }, "a turn end is not a process exit")
        assertEquals(0, rig.events.filterIsInstance<SessionEvent.ProcessExit>().size)
    }

    @Test
    fun poisonedHistory400_movesTheTurnToAFreshSession_withThePersonaResent() {
        val rig = rig()
        val session = rig.manager.startNew("PERSONA: you are the pm seat", model = "fake/model")
        rig.awaitTexts(1)
        rig.awaitState(session.id, SessionState.IDLE)
        val server = rig.launcher.servers.single()
        server.poisoned += "ses_fake1"

        rig.manager.resume(session.id, "second")
        rig.awaitTexts(2)
        rig.awaitState(session.id, SessionState.IDLE)

        assertEquals(listOf("ses_fake1", "ses_fake1", "ses_fake2"), server.prompts.map { it.first }, "the refused turn is sent again on a fresh session")
        val resent = server.prompts[2].second
        assertTrue("PERSONA: you are the pm seat" in resent && "second" in resent, "the persona rides ahead of the turn: $resent")
        assertEquals("ses_fake2", rig.manager.get(session.id)?.providerSessionId, "the daemon resumes the fresh session from now on")
        assertTrue(rig.events.filterIsInstance<SessionEvent.Exit>().none { it.code != 0 }, "the turn did not fail: ${rig.events}")

        // The next turn goes to the fresh session, with no persona repeated.
        rig.manager.resume(session.id, "third")
        rig.awaitTexts(3)
        rig.awaitState(session.id, SessionState.IDLE)
        assertEquals("ses_fake2", server.prompts[3].first)
        assertTrue("PERSONA" !in server.prompts[3].second)
    }

    @Test
    fun poisonedFreshSessionToo_failsTheTurnOnce_noLoop() {
        val rig = rig()
        val session = rig.manager.startNew("first", model = "fake/model")
        rig.awaitTexts(1)
        rig.awaitState(session.id, SessionState.IDLE)
        val server = rig.launcher.servers.single()
        server.poisoned += listOf("ses_fake1", "ses_fake2")

        rig.manager.resume(session.id, "second")
        val deadline = System.currentTimeMillis() + 10_000
        while (rig.manager.get(session.id)?.isBusy() == true && System.currentTimeMillis() < deadline) Thread.sleep(10)
        Thread.sleep(200)
        assertEquals(listOf("ses_fake1", "ses_fake1", "ses_fake2"), server.prompts.map { it.first }, "one recovery per turn, never a loop")
        assertTrue(rig.events.filterIsInstance<SessionEvent.Exit>().any { it.code == 1 }, "the turn fails visibly: ${rig.events}")
    }

    @Test
    fun runningToolAndReasoningParts_areForwarded_andToolActivityBalances() {
        val rig = rig()
        val session = rig.manager.startNew("first", model = "fake/model")
        rig.awaitTexts(1)
        rig.awaitState(session.id, SessionState.IDLE)
        val server = rig.launcher.servers.single()
        server.answerTurns = false
        rig.manager.resume(session.id, "scan")
        val ses = "ses_fake1"
        fun part(json: String) = server.push("""{"type":"message.part.updated","properties":{"sessionID":"$ses","part":$json}}""")
        server.push("""{"type":"session.status","properties":{"sessionID":"$ses","status":{"type":"busy"}}}""")
        part("""{"id":"r1","type":"reasoning","text":"plan","time":{"start":1}}""")
        part("""{"id":"r1","type":"reasoning","text":"plan the scan","time":{"start":1,"end":2}}""")
        part("""{"id":"t1","type":"tool","tool":"bash","state":{"status":"pending","input":{}}}""")
        part("""{"id":"t1","type":"tool","tool":"bash","state":{"status":"running","input":{"command":"ls -la"}}}""")
        part("""{"id":"t1","type":"tool","tool":"bash","state":{"status":"completed","input":{"command":"ls -la"},"output":"x"}}""")
        val deadline = System.currentTimeMillis() + 10_000
        while (rig.stdoutLines().none { it["type"]?.jsonPrimitive?.content == "tool_use" } && System.currentTimeMillis() < deadline) Thread.sleep(10)

        val types = rig.stdoutLines().map { it["type"]!!.jsonPrimitive.content }.drop(3)
        assertTrue(types.containsAll(listOf("reasoning", "tool_start", "tool_use")), "$types")
        assertEquals(1, types.count { it == "reasoning" }, "reasoning is forwarded once, whole, when finished")
        assertEquals(1, types.count { it == "tool_start" })
        val lines = rig.stdoutLines()
        val start = lines.first { it["type"]!!.jsonPrimitive.content == "tool_start" }
        val running = EventNormalizer.normalise(start.toString())
        assertTrue(running.single() is EventNormalizer.Line.Tool, "$running")
        assertEquals("bash", (running.single() as EventNormalizer.Line.Tool).name)
        val reasoning = EventNormalizer.normalise(lines.first { it["type"]!!.jsonPrimitive.content == "reasoning" }.toString())
        assertEquals(listOf<EventNormalizer.Line>(EventNormalizer.Line.Reasoning("plan the scan")), reasoning)
        assertTrue(com.superiorsoftllc.infantry.protocol.ReasoningCoalescer().process("s", reasoning) == reasoning, "whole block untouched")
        // Start counted once, so the finished line must end it twice-over (its own Tool + the earlier start).
        val done = EventNormalizer.normalise(lines.first { it["type"]!!.jsonPrimitive.content == "tool_use" }.toString())
        assertEquals(2, done.count { it is EventNormalizer.Line.ToolEnd }, "$done")
        // What the UI reads: "Running bash: ..." while the tool works, back to thinking once it finished.
        val tracker = com.superiorsoftllc.infantry.protocol.RunActivityTracker()
        tracker.turnStarted("s")
        val whileRunning = running.mapNotNull { tracker.line("s", it) }.last()
        assertEquals(com.superiorsoftllc.infantry.protocol.RunPhase.TOOL_RUNNING, whileRunning.state)
        assertTrue(whileRunning.label!!.startsWith("bash"), whileRunning.label)
        done.forEach { tracker.line("s", it) }
        assertEquals(com.superiorsoftllc.infantry.protocol.RunPhase.THINKING, tracker.current("s").state)
    }

    @Test
    fun interrupt_postsAbort_andKeepsTheServer() {
        val rig = rig()
        val session = rig.manager.startNew("first", model = "fake/model")
        rig.awaitTexts(1)
        rig.awaitState(session.id, SessionState.IDLE)
        val server = rig.launcher.servers.single()
        server.answerTurns = false
        rig.manager.resume(session.id, "a long scan")
        rig.manager.interrupt(session.id)
        rig.awaitState(session.id, SessionState.IDLE)

        val deadline = System.currentTimeMillis() + 5_000
        while (server.aborts.get() == 0 && System.currentTimeMillis() < deadline) Thread.sleep(10)
        assertEquals(1, server.aborts.get(), "the interrupt is opencode's /abort")
        assertTrue(server.isAlive, "and the server lives on")
        assertEquals(1, rig.launcher.spawns.get())
    }

    @Test
    fun keepAliveZero_runsOpencodeOneShot() {
        val rig = rig(keepAliveMinutes = 0)
        val session = rig.manager.startNew("first", model = "fake/model")
        rig.awaitState(session.id, SessionState.IDLE)
        rig.manager.resume(session.id, "second")
        rig.awaitState(session.id, SessionState.IDLE)

        assertEquals(0, rig.launcher.spawns.get(), "keep-alive off: no opencode serve")
        assertEquals(2, rig.oneShot.spawns.get(), "one `opencode run` per reply")
    }

    // --- PROMPT-4: Tool approval `ask` ------------------------------------------------------------------

    /** An `ask` session whose first turn is done, with a turn in flight that the fake server never ends on its own. */
    private fun askSession(rig: Rig): Pair<String, FakeServe> {
        val session = rig.manager.startNew("first", model = "fake/model", toolApproval = ToolApproval.ASK)
        rig.awaitTexts(1)
        rig.awaitState(session.id, SessionState.IDLE)
        val server = rig.launcher.servers.single()
        server.answerTurns = false
        rig.manager.resume(session.id, "change things")
        return session.id to server
    }

    private fun awaitReply(server: FakeServe, id: String): String {
        val deadline = System.currentTimeMillis() + 10_000
        while (true) {
            server.replies.firstOrNull { it.first == id }?.let { return it.second }
            if (System.currentTimeMillis() > deadline) fail("no reply to $id: ${server.replies}")
            Thread.sleep(10)
        }
    }

    private fun denials(rig: Rig) = rig.stdoutLines().filter { it["type"]?.jsonPrimitive?.content == OpenCodePermissions.DENIAL_TYPE }

    @Test
    fun ask_launchesWithAskRules_andInfantryStillAllowed() {
        val rig = rig()
        askSession(rig)
        val cfg = Json.parseToJsonElement(rig.launcher.specs.single().env.getValue(OpenCodePermissions.CONFIG_CONTENT_ENV)) as JsonObject
        val permission = cfg["permission"] as JsonObject
        for (p in OpenCodePermissions.ASK_PERMISSIONS - "bash") assertEquals("ask", permission[p]!!.jsonPrimitive.content, "$p in $cfg")
        val bash = permission["bash"] as JsonObject
        assertEquals("ask", bash["*"]!!.jsonPrimitive.content)
        assertEquals("allow", bash[OpenCodePermissions.INFANTRY_BASH_PATTERN]!!.jsonPrimitive.content)
        assertEquals(OpenCodePermissions.INFANTRY_BASH_PATTERN, bash.keys.last(), "the infantry rule is the last bash rule")
    }

    @Test
    fun ask_allowOnce_allowSession_deny_mapToOnce_always_reject() {
        val rig = rig()
        val (sessionId, server) = askSession(rig)

        server.ask("ses_fake1", "per_1", "edit", "notes.txt")
        val first = rig.awaitPrompt()
        assertEquals(sessionId, first.sessionId, "the card names the Infantry session")
        assertEquals("edit", first.tool)
        assertEquals("notes.txt", first.input)
        rig.prompts.respond(PromptResponse(first.promptId, decision = "allow_once"), "client:1")
        assertEquals("once", awaitReply(server, "per_1"))

        server.ask("ses_fake1", "per_2", "bash", "git status")
        rig.prompts.respond(PromptResponse(rig.awaitPrompt().promptId, decision = "allow_session"), "client:1")
        assertEquals("always", awaitReply(server, "per_2"))
        assertEquals(emptyList(), denials(rig), "an approval announces no denial")

        server.ask("ses_fake1", "per_3", "webfetch", "https://example.com")
        rig.prompts.respond(PromptResponse(rig.awaitPrompt().promptId, decision = "deny"), "client:1")
        assertEquals("reject", awaitReply(server, "per_3"))
        val deadline = System.currentTimeMillis() + 5_000
        while (denials(rig).isEmpty() && System.currentTimeMillis() < deadline) Thread.sleep(10)
        val denial = denials(rig).single()
        assertEquals("webfetch", denial["tool"]!!.jsonPrimitive.content)
        assertEquals("true", denial[OpenCodePermissions.DENIAL_PROMPTED]!!.jsonPrimitive.content, "the owner was asked: not 'can't prompt'")
        assertEquals(
            listOf<EventNormalizer.Line>(EventNormalizer.Line.Denial("opencode", "webfetch", prompted = true)),
            EventNormalizer.normalise(denial.toString()),
        )
    }

    @Test
    fun ask_timeoutRejects() {
        val rig = rig(promptTimeoutMs = 100)
        val (_, server) = askSession(rig)
        server.ask("ses_fake1", "per_t", "bash", "rm -rf build")
        assertEquals("reject", awaitReply(server, "per_t"), "an unanswered prompt is a reject")
        assertEquals(emptyList(), rig.prompts.snapshot())
    }

    @Test
    fun ask_infantryCommandIsNeverPrompted() {
        val rig = rig()
        val (_, server) = askSession(rig)
        server.ask("ses_fake1", "per_a", "bash", "infantry ticket list")
        assertEquals("once", awaitReply(server, "per_a"))
        assertEquals(emptyList(), rig.prompts.snapshot(), "no card for infantry")
        assertEquals(emptyList(), denials(rig))
    }

    @Test
    fun deny_isUnchanged_noPrompt_noAskRules() {
        val rig = rig()
        val session = rig.manager.startNew("first", model = "fake/model", toolApproval = ToolApproval.DENY)
        rig.awaitTexts(1)
        rig.awaitState(session.id, SessionState.IDLE)
        val server = rig.launcher.servers.single()
        server.answerTurns = false
        rig.manager.resume(session.id, "change things")
        server.ask("ses_fake1", "per_d", "bash", "git status")
        assertEquals("reject", awaitReply(server, "per_d"))
        assertEquals(emptyList(), rig.prompts.snapshot(), "deny never prompts")
        val cfg = rig.launcher.specs.single().env.getValue(OpenCodePermissions.CONFIG_CONTENT_ENV)
        assertTrue("\"ask\"" !in cfg, "deny adds no ask rules: $cfg")
    }

    @Test
    fun askIsPartOfTheFingerprint() {
        val adapter = ApiSessionAdapter("opencode", "Infantry_OpenCode", OpenCodeServeSession(null, { "opencode.exe" }), {})
        val env = mapOf("A" to "1")
        val deny = adapter.fingerprint(RunRequest("x", toolApproval = ToolApproval.DENY), env)
        assertTrue(adapter.fingerprint(RunRequest("x", toolApproval = ToolApproval.ASK), env) != deny, "switching into ask restarts the server")
        assertEquals(deny, adapter.fingerprint(RunRequest("x", toolApproval = ToolApproval.ALLOW_ALL), env))
    }

    @Test
    fun runLineType_mapsFinishedServerPartsOntoRunLines() {
        fun part(json: String) = Json.parseToJsonElement(json) as JsonObject
        assertEquals("step_start", OpenCodeServeSession.runLineType(part("""{"type":"step-start"}""")))
        assertEquals("step_finish", OpenCodeServeSession.runLineType(part("""{"type":"step-finish"}""")))
        assertEquals(null, OpenCodeServeSession.runLineType(part("""{"type":"text","time":{"start":1}}""")))
        assertEquals("text", OpenCodeServeSession.runLineType(part("""{"type":"text","time":{"start":1,"end":2}}""")))
        assertEquals("tool_start", OpenCodeServeSession.runLineType(part("""{"type":"tool","state":{"status":"running"}}""")))
        assertEquals(null, OpenCodeServeSession.runLineType(part("""{"type":"tool","state":{"status":"pending"}}""")))
        assertEquals(null, OpenCodeServeSession.runLineType(part("""{"type":"reasoning","time":{"start":1}}""")))
        assertEquals("reasoning", OpenCodeServeSession.runLineType(part("""{"type":"reasoning","time":{"start":1,"end":2}}""")))
        assertEquals("tool_use", OpenCodeServeSession.runLineType(part("""{"type":"tool","state":{"status":"completed"}}""")))
        assertEquals("tool_use", OpenCodeServeSession.runLineType(part("""{"type":"tool","state":{"status":"error"}}""")))
        assertEquals("opencode-go" to "step-5-preview-free", OpenCodeServeSession.splitModel("opencode-go/step-5-preview-free"))
        assertEquals(null, OpenCodeServeSession.splitModel("nomodel"))
    }
}
