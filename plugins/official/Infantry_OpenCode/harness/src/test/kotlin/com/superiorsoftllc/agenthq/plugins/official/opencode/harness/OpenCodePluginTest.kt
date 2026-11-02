package com.superiorsoftllc.agenthq.plugins.official.opencode.harness

import com.superiorsoftllc.agenthq.harness.api.ApprovalReply
import com.superiorsoftllc.agenthq.harness.api.ConfigParts
import com.superiorsoftllc.agenthq.harness.api.HARNESS_API_VERSION
import com.superiorsoftllc.agenthq.harness.api.Harness
import com.superiorsoftllc.agenthq.harness.api.HarnessAnswer
import com.superiorsoftllc.agenthq.harness.api.HarnessEvent
import com.superiorsoftllc.agenthq.harness.api.HarnessHost
import com.superiorsoftllc.agenthq.harness.api.HarnessProcess
import com.superiorsoftllc.agenthq.harness.api.HarnessProcessListener
import com.superiorsoftllc.agenthq.harness.api.HarnessQuestion
import com.superiorsoftllc.agenthq.harness.api.HarnessRegistrar
import com.superiorsoftllc.agenthq.harness.api.HarnessRequest
import com.superiorsoftllc.agenthq.harness.api.HarnessRunningProcess
import com.superiorsoftllc.agenthq.harness.api.SessionOutput
import com.superiorsoftllc.agenthq.harness.api.SessionTurnEnd
import com.superiorsoftllc.agenthq.harness.api.ToolApproval
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** HARNESS H8b-2: the opencode plugin against the harness API only, with a fake host. */
class OpenCodePluginTest {
    private val dir: File = Files.createTempDirectory("opencode-plugin").toFile()

    @AfterTest fun cleanup() { dir.deleteRecursively() }

    private class FakeHost(
        override val stateDir: File,
        private val cli: String? = "/opt/oc/opencode",
        private val mcp: HarnessProcess? = HarnessProcess(listOf("agenthq", "mcp", "serve"), env = mapOf("AGENTHQ_AGENT_ID" to "a1")),
        private val watch: String? = null,
    ) : HarnessHost {
        override val moduleId = "Infantry_OpenCode"
        override val apiVersion = HARNESS_API_VERSION
        override fun log(line: String) = Unit
        override fun setting(key: String): String? = null
        override fun resolveCli(tool: String): String? = if (tool == "opencode") cli else null
        override fun start(process: HarnessProcess, listener: HarnessProcessListener): HarnessRunningProcess = error("not used")
        override fun mcpLaunch(sessionEnv: Map<String, String>): HarnessProcess? = mcp
        override fun watchLauncher(sessionEnv: Map<String, String>): String? = watch
        override suspend fun ask(sessionId: String, question: HarnessQuestion): HarnessAnswer = HarnessAnswer.NONE
    }

    private fun harness(host: HarnessHost = FakeHost(dir)): Harness {
        var registered: Harness? = null
        OpenCodeHarnessPlugin().register(object : HarnessRegistrar {
            override val host: HarnessHost = host
            override fun register(harness: Harness) { registered = harness }
        })
        return registered!!
    }

    private fun request(model: String? = null, sessionId: String? = null, approval: ToolApproval = ToolApproval.DENY) =
        HarnessRequest("hi", "opencode", "proj", model, sessionId, toolApproval = approval)

    private fun obj(text: String) = Json.parseToJsonElement(text) as JsonObject

    @Test fun `the plugin states the API version it was built against and registers opencode`() {
        val plugin = OpenCodeHarnessPlugin()
        assertEquals(HARNESS_API_VERSION, plugin.contractVersion)
        assertEquals("Infantry_OpenCode", plugin.pluginId)
        assertEquals("opencode", harness().id)
    }

    @Test fun `a one-shot run is opencode run --format json with the model and session`() {
        val p = harness().process(request("p/m", "ses_1"))
        assertEquals(listOf("/opt/oc/opencode", "run", "--format", "json", "-m", "p/m", "--session", "ses_1"), p.command)
        assertTrue(harness().process(request()).command.contains(OpenCodeCommand.DEFAULT_MODEL) || System.getenv(OpenCodeCommand.MODEL_ENV) != null)
    }

    @Test fun `tool approval maps to --auto and the inline config`() {
        val h = harness()
        assertEquals(listOf("--auto"), h.approvalArgs(ToolApproval.ALLOW_ALL))
        assertTrue(h.approvalArgs(ToolApproval.DENY).isEmpty())
        val edits = obj(h.process(request(approval = ToolApproval.ALLOW_EDITS)).env.getValue("OPENCODE_CONFIG_CONTENT"))
        assertEquals("allow", ((edits["permission"] as JsonObject)["edit"]).toString().trim('"'))
        assertTrue(edits.toString().contains("agenthq_*"), "the session's MCP server is allowed")
    }

    @Test fun `the rule, the commands and the declared capabilities`() {
        val h = harness()
        assertEquals("agenthq_*", h.mcpToolRule("agenthq"))
        assertEquals(listOf("model", "compact"), h.commands().map { it.name })
        assertEquals(com.superiorsoftllc.agenthq.harness.api.EffortSupport.UNSUPPORTED, h.effort.support)
        assertEquals(com.superiorsoftllc.agenthq.harness.api.ContextLimitSupport.UNSUPPORTED, h.contextLimit.support)
        assertNotNull(h.session())
    }

    @Test fun `merge keeps the user's keys and adds the MCP server and the watch plugin`() {
        val plugin = File(dir, "agenthq-watch.js")
        val merged = obj(harness().mergeConfig("""{"username":"u","permission":{"read":"allow"}}""", ConfigParts(HarnessProcess(listOf("agenthq", "mcp", "serve")), plugin))!!)
        assertEquals("\"u\"", merged["username"].toString())
        assertTrue(merged.toString().contains("\"agenthq_*\":\"allow\""))
        assertTrue(merged.toString().contains("\"read\":\"allow\""))
        assertTrue(merged.toString().contains(plugin.absoluteFile.toPath().toUri().toString()))
    }

    @Test fun `a missing CLI fails the run, not the registration`() {
        val h = harness(FakeHost(dir, cli = null))
        val e = runCatching { h.process(request()) }.exceptionOrNull()
        assertNotNull(e)
        assertTrue(e.message!!.contains("opencode CLI was not found"))
    }

    @Test fun `serve launch builds the loopback command with the password and the merged config`() {
        val session = OpenCodeServeSession(FakeHost(dir), { "/opt/oc/opencode" }, portPicker = { 4321 }, processEnv = { null })
        val p = session.launch(request(approval = ToolApproval.ASK), emptyMap())
        assertEquals(listOf("/opt/oc/opencode", "serve", "--port", "4321", "--hostname", "127.0.0.1"), p.command)
        assertTrue(p.env.getValue("OPENCODE_SERVER_PASSWORD").length >= 20)
        val config = p.env.getValue("OPENCODE_CONFIG_CONTENT")
        assertTrue(config.contains("\"edit\":\"ask\""))
        assertTrue(config.contains("\"agenthq *\":\"allow\""))
        assertTrue(config.contains("agenthq_*"))
        assertFalse(config.contains("agenthq-watch.js"), "no watch plugin without allowAll and a watch launcher")
        assertNotNull(session.fingerprint(request(approval = ToolApproval.ASK), emptyMap()))
        assertTrue(session.fingerprint(request(approval = ToolApproval.ASK), emptyMap()) != session.fingerprint(request(), emptyMap()), "ask restarts the server")
        val watched = OpenCodeServeSession(FakeHost(dir, watch = "/x/launcher"), { "/opt/oc/opencode" }, portPicker = { 1 }, processEnv = { null })
            .launch(request(approval = ToolApproval.ALLOW_ALL), emptyMap())
        assertTrue(watched.env.getValue("OPENCODE_CONFIG_CONTENT").contains("agenthq-watch.js"))
        assertTrue(File(dir, "agenthq-watch.js").isFile)
    }

    @Test fun `translator reads text, tools, usage, denials and the terminal verdict`() {
        val t = OpenCodeTranslator()
        val events = listOf(
            """{"type":"text","sessionID":"ses_1","part":{"text":"hello"}}""",
            """{"type":"tool_use","part":{"type":"tool","tool":"read","callID":"c1","state":{"status":"completed","input":{"filePath":"a"},"output":"x"}}}""",
            """{"type":"step_finish","part":{"tokens":{"input":10,"output":2,"cache":{"read":3,"write":4}}}}""",
        ).flatMap(t::translate)
        assertEquals(HarnessEvent.SessionStarted("ses_1"), events[0])
        assertEquals(HarnessEvent.TextDelta("hello"), events[1])
        assertIs<HarnessEvent.ToolCall>(events[2])
        assertEquals(HarnessEvent.ToolResult("c1", "x", false), events[3])
        assertEquals(HarnessEvent.Usage(10, 2, 3, 4), events[4])
        assertIs<HarnessEvent.Completion>(t.terminal(0, ""))
        val failing = OpenCodeTranslator().also { it.translate("""{"type":"error","error":{"message":"boom"}}""") }
        assertEquals(HarnessEvent.Error("boom"), failing.terminal(0, ""))
        assertTrue(OpenCodeTranslator().translate("""{"type":"step_finish","part":{"tokens":{"input":1}}}""").isEmpty(), "no output count = unknown, not 0")
    }

    @Test fun `a turn emits run-format lines and ends once`() {
        val ended = mutableListOf<SessionTurnEnd>()
        val lines = mutableListOf<String>()
        val out = object : SessionOutput {
            override fun write(text: String) = true
            override fun closeInput() = Unit
            override fun detail(text: String) = Unit
            override fun providerSession(id: String) = Unit
            override fun turnEnded(end: SessionTurnEnd, exitCode: Int?, reason: String?) { ended += end }
            override fun output(line: String) { lines += line }
            override fun requestApproval(tool: String, input: String) = ApprovalReply.DENY
        }
        val turn = OpenCodeServeSession.Turn(out, "p/m")
        turn.onEvent("session.status", obj("""{"status":{"type":"busy"}}"""), null, "ses_1")
        val part = obj("""{"id":"prt_1","type":"text","text":"hi","time":{"end":1},"messageID":"m1"}""")
        turn.onEvent("message.part.updated", obj("""{"part":{}}"""), part, "ses_1")
        turn.onEvent("message.part.updated", obj("""{"part":{}}"""), part, "ses_1") // deduped
        turn.onEvent("session.idle", obj("{}"), null, "ses_1")
        turn.onEvent("session.idle", obj("{}"), null, "ses_1")
        assertEquals(1, lines.size)
        assertEquals(listOf(SessionTurnEnd.COMPLETED), ended)
        assertEquals(listOf<HarnessEvent>(HarnessEvent.SessionStarted("ses_1"), HarnessEvent.TextDelta("hi")), OpenCodeTranslator().translate(lines.single()))
    }

    @Test fun `an idle with nothing said is a failed turn naming the model`() {
        val ends = mutableListOf<Pair<SessionTurnEnd, String?>>()
        val out = object : SessionOutput {
            override fun write(text: String) = true
            override fun closeInput() = Unit
            override fun detail(text: String) = Unit
            override fun providerSession(id: String) = Unit
            override fun turnEnded(end: SessionTurnEnd, exitCode: Int?, reason: String?) { ends += end to reason }
        }
        val turn = OpenCodeServeSession.Turn(out, "p/m") { listOf("auth failed") }
        turn.onEvent("session.status", obj("""{"status":{"type":"busy"}}"""), null, "ses_1")
        turn.onEvent("session.idle", obj("{}"), null, "ses_1")
        assertEquals(SessionTurnEnd.FAILED, ends.single().first)
        assertEquals("no reply from p/m: the turn ended with no assistant output (opencode stderr: auth failed)", ends.single().second)
    }

    @Test fun `models list the config first and fall back to the CLI listing`() {
        val rows = OpenCodeCommand.models(listOf("a/x", "a/y", "b/z", "junk"), emptyList())
        assertEquals(listOf("a", "b"), rows.map { it.group })
        assertEquals(listOf("a/x", "a/y"), rows[0].models)
        val none = OpenCodeCommand.models(emptyList(), listOf("note"))
        assertTrue(none.single().note!!.startsWith("opencode reported no configured models; note"))
    }
}
