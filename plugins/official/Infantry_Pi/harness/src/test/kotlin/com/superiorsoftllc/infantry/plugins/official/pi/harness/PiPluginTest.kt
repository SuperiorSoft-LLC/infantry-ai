// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.plugins.official.pi.harness

import com.superiorsoftllc.infantry.harness.api.HARNESS_API_VERSION
import com.superiorsoftllc.infantry.harness.api.HarnessAnswer
import com.superiorsoftllc.infantry.harness.api.HarnessEvent
import com.superiorsoftllc.infantry.harness.api.HarnessHost
import com.superiorsoftllc.infantry.harness.api.HarnessProcess
import com.superiorsoftllc.infantry.harness.api.HarnessProcessListener
import com.superiorsoftllc.infantry.harness.api.HarnessQuestion
import com.superiorsoftllc.infantry.harness.api.HarnessRegistrar
import com.superiorsoftllc.infantry.harness.api.HarnessRequest
import com.superiorsoftllc.infantry.harness.api.HarnessRunningProcess
import com.superiorsoftllc.infantry.harness.api.SandboxRoots
import com.superiorsoftllc.infantry.harness.api.SessionOutput
import com.superiorsoftllc.infantry.harness.api.SessionTurnEnd
import com.superiorsoftllc.infantry.harness.api.Harness
import com.superiorsoftllc.infantry.harness.api.EffortSupport
import com.superiorsoftllc.infantry.harness.api.ContextLimitSupport
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** HARNESS H6c: the pi plugin against the harness API only, with a fake host and a fake persistent process. */
class PiPluginTest {
    private val dir: File = Files.createTempDirectory("pi-plugin").toFile()

    @AfterTest fun cleanup() { dir.deleteRecursively() }

    private class FakeHost(override val stateDir: File, private val cli: String? = "/opt/pi/pi") : HarnessHost {
        override val moduleId = "Infantry_Pi"
        override val apiVersion = HARNESS_API_VERSION
        override fun log(line: String) = Unit
        override fun setting(key: String): String? = null
        override fun resolveCli(tool: String): String? = if (tool == "pi") cli else if (tool == "node") "/opt/node/bin/node" else null
        override fun start(process: HarnessProcess, listener: HarnessProcessListener): HarnessRunningProcess = error("not used")
        override fun mcpLaunch(sessionEnv: Map<String, String>): HarnessProcess? = null
        override fun watchLauncher(sessionEnv: Map<String, String>): String? = null
        override suspend fun ask(sessionId: String, question: HarnessQuestion): HarnessAnswer = HarnessAnswer.NONE
    }

    private fun harness(cli: String? = "/opt/pi/pi"): Harness {
        val fake = FakeHost(dir, cli)
        var registered: Harness? = null
        PiHarnessPlugin().register(object : HarnessRegistrar {
            override val host: HarnessHost = fake
            override fun register(harness: Harness) { registered = harness }
        })
        return registered!!
    }

    private fun request(model: String? = null, sessionId: String? = null, effort: String? = null, contextLimit: Int? = null) =
        HarnessRequest("hi", "pi", "proj", model, sessionId, effort, contextLimit = contextLimit)

    // ---- the plugin -------------------------------------------------------------------------

    @Test fun `the plugin states the API version it was built against and registers pi`() {
        val plugin = PiHarnessPlugin()
        assertEquals(HARNESS_API_VERSION, plugin.contractVersion)
        assertEquals("Infantry_Pi", plugin.pluginId)
        assertEquals("pi", harness().id)
    }

    // ---- argv ---------------------------------------------------------------------------------

    @Test fun `a one-shot run is pi --mode json with the model, thinking level and session`() {
        val gate = listOf("-e", File(dir, PiExtensions.APPROVAL).absolutePath)
        assertEquals(listOf("/opt/pi/pi", "--mode", "json") + gate, harness().process(request()).command)
        assertEquals(
            listOf("/opt/pi/pi", "--mode", "json", "--model", "m", "--thinking", "high", "--session", "s-1") + gate,
            harness().process(request("m", "s-1", "high")).command,
        )
    }

    @Test fun `a js entry point runs on the node the daemon resolves, a native pi needs none`() {
        assertEquals(listOf("/opt/node/bin/node", "/x/cli.js", "--mode", "json", "-e", File(dir, PiExtensions.APPROVAL).absolutePath), harness("/x/cli.js").process(request()).command)
        assertEquals(listOf("/x/pi.cmd", "--mode", "json", "-e", File(dir, PiExtensions.APPROVAL).absolutePath), harness("/x/pi.cmd").process(request()).command)
        assertEquals(listOf("node", "/x/cli.js"), PiCommand.launcherFor("/x/cli.js", node = { "node" }))
    }

    @Test fun `a missing pi CLI fails the run with the reason`() {
        val e = kotlin.runCatching { harness(cli = null).process(request()) }.exceptionOrNull()
        assertTrue(e!!.message!!.contains("pi CLI was not found"), e.message)
    }

    @Test fun `the harness declares effort supported and a best-effort context limit`() {
        val h = harness()
        assertEquals(EffortSupport.SUPPORTED, h.effort.support)
        assertEquals(ContextLimitSupport.BEST_EFFORT, h.contextLimit.support)
        assertTrue(h.session() != null)
    }

    // ---- contributions ------------------------------------------------------------------------

    @Test fun `watch loads the watch extension from the module's own data folder`() {
        val c = harness().watch(emptyMap(), "infantry")!!
        assertEquals(listOf("-e", File(dir, "infantry-watch.ts").absolutePath), c.extraArgs)
        val ts = c.files.getValue("infantry-watch.ts")
        assertTrue(ts.contains("pi.on(\"tool_call\"") && ts.contains("event.input.command = out.command"))
    }

    @Test fun `sandbox lets the session read the extensions folder`() {
        val c = harness().sandbox(SandboxRoots(dir, File("wt")))!!
        assertEquals(listOf(dir), c.readDirs)
    }

    // ---- extensions ---------------------------------------------------------------------------

    @Test fun `the todo extension registers todowrite and is reinstalled when deleted`() {
        val src = PiExtensions.source(PiExtensions.TODO)
        assertTrue("name: \"todowrite\"" in src)
        assertTrue("\"in_progress\"" in src && "\"completed\"" in src && "content" in src)
        assertFalse(src.contains("import "), "no imports, so it loads from any directory")
        val file = File(dir, PiExtensions.TODO)
        assertEquals(listOf("-e", file.absolutePath), PiExtensions.launchArgs(dir, PiExtensions.TODO))
        file.delete()
        PiExtensions.launchArgs(dir, PiExtensions.TODO)
        assertTrue(file.isFile)
    }

    // ---- the stream ---------------------------------------------------------------------------

    @Test fun `a captured failed run is an Error with pi's own reason and reports no usage`() {
        val lines = javaClass.getResourceAsStream("/captured-failed-run.jsonl")!!.bufferedReader().readLines()
        val t = harness().newTranslator()
        val events = lines.flatMap { t.translate(it) }
        assertTrue(events.none { it is HarnessEvent.Usage }, "a failed run reports no usage: $events")
        val end = t.terminal(0, "")
        assertIs<HarnessEvent.Error>(end)
        assertTrue(end.message.isNotBlank())
    }

    @Test fun `usage keeps the cache buckets apart`() {
        val t = harness().newTranslator()
        listOf(
            """{"type":"session","id":"s-1"}""",
            """{"type":"message_end","message":{"role":"assistant","stopReason":"stop","usage":{"input":10,"cacheRead":5,"cacheWrite":3,"output":7}}}""",
            """{"type":"message_end","message":{"role":"assistant","stopReason":"stop","usage":{"input":1,"cacheRead":1,"cacheWrite":1,"output":1}}}""",
        ).forEach { t.translate(it) }
        val events = t.translate("""{"type":"agent_settled"}""")
        assertEquals(listOf(HarnessEvent.Usage(11, 8, 6, 4)), events)
        assertEquals(HarnessEvent.Completion(), t.terminal(0, ""))
    }

    @Test fun `thinking deltas are joined into one Reasoning event`() {
        val t = harness().newTranslator()
        fun delta(d: String) = """{"type":"message_update","assistantMessageEvent":{"type":"thinking_delta","delta":"$d"}}"""
        assertEquals(emptyList(), t.translate(delta("Let me th")))
        assertEquals(emptyList(), t.translate(delta("ink")))
        val out = t.translate("""{"type":"message_update","assistantMessageEvent":{"type":"text_delta","delta":"ok"}}""")
        assertEquals(listOf(HarnessEvent.Reasoning("Let me think"), HarnessEvent.TextDelta("ok")), out)
    }

    // ---- models -------------------------------------------------------------------------------

    private val recorded get() = javaClass.getResourceAsStream("/list-models.txt")!!.bufferedReader().readText()

    @Test fun `the recorded table parses to six providers and 179 provider-qualified ids`() {
        val parsed = PiModels.parseListModels(recorded)
        assertEquals(6, parsed.size)
        assertEquals(179, parsed.values.sumOf { it.size })
        assertTrue(parsed.getValue("google").all { it.startsWith("google/") })
    }

    @Test fun `the parser tolerates banners and junk but needs the header`() {
        assertEquals(mapOf("p" to listOf("p/m1")), PiModels.parseListModels("warning: x\nprovider model context\np m1 1k\n??? bad\n"))
        assertTrue(PiModels.parseListModels("p m1 1k\n").isEmpty())
    }

    @Test fun `models are grouped by provider and a failure is a noted row, never an error`() {
        val rows = PiModels({ recorded }, { null }).models()
        assertEquals(6, rows.size)
        val failed = PiModels({ error("pi not found") }, { null }).models().single()
        assertTrue(failed.models.isEmpty())
        assertTrue(failed.note!!.contains("pi not found"), failed.note)
        val empty = PiModels({ "provider model\n" }, { null }).models().single()
        assertTrue(empty.unavailable)
    }

    @Test fun `the models-store fallback names the reason and the listing is cached for the ttl`() {
        val store = File(dir, "models-store.json").also { it.writeText("""{"acme":{"models":[{"id":"m1"}]}}""") }
        val rows = PiModels({ error("down") }, { store }).models()
        assertEquals(listOf("acme/m1"), rows.first().models)
        assertEquals(PiModels.NOTES_GROUP, rows.last().group)
        var calls = 0
        var now = 0L
        val cached = PiModels({ calls++; "provider model\na m\n" }, { null }, ttlMs = 100, clock = { now })
        cached.models(); cached.models()
        assertEquals(1, calls)
        now = 500
        cached.models()
        assertEquals(2, calls)
    }

    // ---- the persistent session -----------------------------------------------------------------

    private class FakeOut : SessionOutput {
        val written = mutableListOf<String>()
        val details = mutableListOf<String>()
        val sessions = mutableListOf<String>()
        val ends = mutableListOf<Triple<SessionTurnEnd, Int?, String?>>()
        var closed = false
        var writable = true
        var reply = com.superiorsoftllc.infantry.harness.api.ApprovalReply.DENY
        val asked = mutableListOf<Pair<String, String>>()
        override fun requestApproval(tool: String, input: String): com.superiorsoftllc.infantry.harness.api.ApprovalReply { asked += tool to input; return reply }
        @Synchronized override fun write(text: String): Boolean { if (writable) written += text; return writable }
        override fun closeInput() { closed = true }
        override fun detail(text: String) { details += text }
        override fun providerSession(id: String) { sessions += id }
        override fun turnEnded(end: SessionTurnEnd, exitCode: Int?, reason: String?) { ends += Triple(end, exitCode, reason) }
        fun sent(type: String) = written.flatMap { it.lines() }.filter { it.isNotBlank() }
            .map { Json.parseToJsonElement(it) as JsonObject }.filter { it["type"]?.jsonPrimitive?.content == type }
    }

    private fun session(out: FakeOut) = PiSession(FakeHost(dir), { listOf("pi") }).connect(out)

    @Test fun `launch is pi --mode rpc, resumes the provider session and loads the todo extension`() {
        val s = PiSession(FakeHost(dir), { listOf("pi") })
        val p = s.launch(request("m", "prov-1", "low"), emptyMap())
        assertEquals(listOf("pi", "--mode", "rpc", "--model", "m", "--thinking", "low", "--session", "prov-1", "-e", File(dir, PiExtensions.TODO).absolutePath, "-e", File(dir, PiExtensions.APPROVAL).absolutePath), p.command)
        assertEquals("DENY", p.env[PiExtensions.APPROVAL_ENV])
        // A different session id does not change the fingerprint (the host resumes), a different model does.
        assertEquals(s.fingerprint(request("m", "a"), emptyMap()), s.fingerprint(request("m", "b"), emptyMap()))
        assertFalse(s.fingerprint(request("m"), emptyMap()) == s.fingerprint(request("n"), emptyMap()))
    }

    @Test fun `a turn is a prompt plus get_state, learns the provider session and ends on agent_settled`() {
        val out = FakeOut()
        val c = session(out)
        c.sendTurn(request())
        assertEquals("hi", out.sent("prompt").single()["message"]!!.jsonPrimitive.content)
        assertEquals(1, out.sent("get_state").size)
        c.onStdout("""{"type":"response","command":"get_state","success":true,"data":{"sessionId":"prov-9"}}""")
        assertEquals(listOf("prov-9"), out.sessions)
        c.onStdout("""{"type":"message_end","message":{"role":"assistant","stopReason":"stop"}}""")
        c.onStdout("""{"type":"agent_settled"}""")
        assertEquals(listOf(Triple<SessionTurnEnd, Int?, String?>(SessionTurnEnd.COMPLETED, 0, null)), out.ends)
    }

    @Test fun `a stopReason error is FAILED with pi's message and a refused prompt fails at once`() {
        val out = FakeOut()
        val c = session(out)
        c.sendTurn(request())
        c.onStdout("""{"type":"message_end","message":{"role":"assistant","stopReason":"error","errorMessage":"quota"}}""")
        c.onStdout("""{"type":"agent_settled"}""")
        assertEquals(Triple(SessionTurnEnd.FAILED, null, "quota"), out.ends.single())
        val out2 = FakeOut()
        val c2 = session(out2)
        c2.sendTurn(request())
        val id = out2.sent("prompt").single()["id"]!!.jsonPrimitive.content
        c2.onStdout("""{"type":"response","command":"prompt","id":"$id","success":false,"error":"bad"}""")
        assertEquals(Triple(SessionTurnEnd.FAILED, null, "bad"), out2.ends.single())
    }

    @Test fun `abort sends abort and the turn ends INTERRUPTED on the aborted settle, the process lives`() {
        val out = FakeOut()
        val c = session(out)
        c.sendTurn(request())
        assertTrue(c.abortTurn())
        assertEquals(1, out.sent("abort").size)
        c.onStdout("""{"type":"agent_settled","aborted":true}""")
        assertEquals(SessionTurnEnd.INTERRUPTED, out.ends.single().first)
        assertFalse(out.closed)
        assertTrue(c.close())
        assertTrue(out.closed)
    }

    @Test fun `a dialog request is answered cancelled`() {
        val out = FakeOut()
        val c = session(out)
        c.onStdout("""{"type":"extension_ui_request","id":"d1","method":"confirm"}""")
        c.onStdout("""{"type":"extension_ui_request","id":"d2","method":"setStatus"}""")
        val reply = out.sent("extension_ui_response").single()
        assertEquals("d1", reply["id"]!!.jsonPrimitive.content)
        assertEquals("true", reply["cancelled"]!!.jsonPrimitive.content)
    }

    @Test fun `HARNESS-GAP G5 the approval mode reaches pi's env in both modes and changes the fingerprint`() {
        for (mode in com.superiorsoftllc.infantry.harness.api.ToolApproval.values()) {
            val r = request().copy(toolApproval = mode)
            assertEquals(mode.name, harness().process(r).env[PiExtensions.APPROVAL_ENV])
            assertEquals(mode.name, PiSession(FakeHost(dir), { listOf("pi") }).launch(r, emptyMap()).env[PiExtensions.APPROVAL_ENV])
        }
        val s = PiSession(FakeHost(dir), { listOf("pi") })
        assertFalse(s.fingerprint(request(), emptyMap()) == s.fingerprint(request().copy(toolApproval = com.superiorsoftllc.infantry.harness.api.ToolApproval.ALLOW_ALL), emptyMap()))
    }

    @Test fun `HARNESS-GAP G5 the approval extension blocks per mode and fails closed`() {
        val ts = PiExtensions.source(PiExtensions.APPROVAL)
        assertTrue(ts.contains("block: true"))
        assertTrue(ts.contains("""|| "DENY""""))
        for (m in listOf("ALLOW_ALL", "ALLOW_EDITS", "ASK", "DENY")) assertTrue(ts.contains(m), m)
    }

    @Test fun `HARNESS-GAP G5 an approval confirm is relayed to the owner and answered`() {
        for ((reply, expected) in listOf(
            com.superiorsoftllc.infantry.harness.api.ApprovalReply.ALLOW_ONCE to "true",
            com.superiorsoftllc.infantry.harness.api.ApprovalReply.DENY to "false",
        )) {
            val out = FakeOut().also { it.reply = reply }
            session(out).onStdout("""{"type":"extension_ui_request","id":"a1","method":"confirm","title":"infantry-approval:bash","message":"rm -rf x"}""")
            val deadline = System.currentTimeMillis() + 5000
            while (out.sent("extension_ui_response").isEmpty() && System.currentTimeMillis() < deadline) Thread.sleep(10)
            assertEquals(listOf("bash" to "rm -rf x"), out.asked)
            val r = out.sent("extension_ui_response").single()
            assertEquals(expected, r["confirmed"]!!.jsonPrimitive.content)
        }
    }

    @Test fun `a write that fails ends the turn FAILED`() {
        val out = FakeOut().also { it.writable = false }
        session(out).sendTurn(request())
        assertEquals(SessionTurnEnd.FAILED, out.ends.single().first)
    }

    @Test fun `a turn over the context limit is compacted before it ends`() {
        val out = FakeOut()
        val c = session(out)
        c.sendTurn(request(contextLimit = 1000))
        c.onStdout("""{"type":"agent_settled"}""")
        assertTrue(out.ends.isEmpty(), "held for the context check")
        val statsId = out.sent("get_session_stats").single()["id"]!!.jsonPrimitive.content
        c.onStdout("""{"type":"response","command":"get_session_stats","id":"$statsId","success":true,"data":{"contextUsage":{"tokens":5000}}}""")
        assertTrue(out.ends.isEmpty())
        val compactId = out.sent("compact").single()["id"]!!.jsonPrimitive.content
        c.onStdout("""{"type":"response","command":"compact","id":"$compactId","success":true,"data":{"tokensBefore":5000,"estimatedTokensAfter":900}}""")
        assertEquals(SessionTurnEnd.COMPLETED, out.ends.single().first)
        assertTrue(out.details.any { it.contains("compacted") })
    }

    @Test fun `no context limit asks nothing, and a turn under the limit is not compacted`() {
        val out = FakeOut()
        val c = session(out)
        c.sendTurn(request())
        c.onStdout("""{"type":"agent_settled"}""")
        assertTrue(out.sent("get_session_stats").isEmpty())
        assertEquals(SessionTurnEnd.COMPLETED, out.ends.single().first)
        val out2 = FakeOut()
        val c2 = session(out2)
        c2.sendTurn(request(contextLimit = 1000))
        c2.onStdout("""{"type":"agent_settled"}""")
        val id = out2.sent("get_session_stats").single()["id"]!!.jsonPrimitive.content
        c2.onStdout("""{"type":"response","command":"get_session_stats","id":"$id","success":true,"data":{"contextUsage":{"tokens":10}}}""")
        assertTrue(out2.sent("compact").isEmpty())
        assertEquals(SessionTurnEnd.COMPLETED, out2.ends.single().first)
    }
}
