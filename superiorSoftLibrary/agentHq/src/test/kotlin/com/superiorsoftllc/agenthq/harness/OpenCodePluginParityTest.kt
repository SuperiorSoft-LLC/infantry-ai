package com.superiorsoftllc.agenthq.harness

import com.superiorsoftllc.agenthq.daemon.AgentHqMcp
import com.superiorsoftllc.agenthq.harness.api.ConfigParts
import com.superiorsoftllc.agenthq.harness.api.HarnessProcess
import com.superiorsoftllc.agenthq.harness.api.SessionOutput
import com.superiorsoftllc.agenthq.harness.api.SessionTurnEnd
import com.superiorsoftllc.agenthq.opencode.OpenCodeCommand
import com.superiorsoftllc.agenthq.opencode.OpenCodePermissions
import com.superiorsoftllc.agenthq.opencode.OpenCodeServeAdapter
import com.superiorsoftllc.agenthq.protocol.EventNormalizer
import com.superiorsoftllc.agenthq.session.host.AdapterSink
import com.superiorsoftllc.agenthq.session.host.TurnEnd
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import com.superiorsoftllc.agenthq.harness.api.HarnessEvent as ApiEvent
import com.superiorsoftllc.agenthq.harness.api.ToolApproval as ApiMode
import com.superiorsoftllc.agenthq.plugins.official.opencode.harness.OpenCodeCommand as PluginCommand
import com.superiorsoftllc.agenthq.plugins.official.opencode.harness.OpenCodeConfig as PluginConfig
import com.superiorsoftllc.agenthq.plugins.official.opencode.harness.OpenCodePermissions as PluginPermissions
import com.superiorsoftllc.agenthq.plugins.official.opencode.harness.OpenCodeServeSession as PluginSession
import com.superiorsoftllc.agenthq.plugins.official.opencode.harness.OpenCodeTranslator

/**
 * HARNESS H8b-2: the Infantry_OpenCode plugin and the core opencode copy (still in production, H8b-3 switches) give the same answers for the same
 * inputs: the `opencode run` argv, the merged inline config, the permission replies and the stream (the lines the serve path re-emits and what the
 * daemon reads from them).
 */
class OpenCodePluginParityTest {
    init {
        // The core copy merges the MCP server through the registry slot the daemon fills at start.
        com.superiorsoftllc.agenthq.daemon.CoreHarnessContributions.register()
    }

    private val modes = ToolApproval.values().toList().associateWith { ApiMode.valueOf(it.name) }

    private fun obj(text: String) = Json.parseToJsonElement(text) as JsonObject

    @Test fun `argv - core run command equals the plugin command plus the host-appended approval args`() {
        for ((core, plugin) in modes) for (session in listOf(null, "ses_1")) {
            assertEquals(
                OpenCodeCommand.command("oc", "p/m", session, core),
                PluginCommand.command("oc", "p/m", session) + PluginCommand.approvalArgs(plugin),
                "$core $session",
            )
        }
        assertEquals(OpenCodeCommand.DEFAULT_MODEL, PluginCommand.DEFAULT_MODEL)
        for (v in listOf(null, "", " default ", "p/m")) assertEquals(OpenCodeCommand.effectiveModel(v) { null }, PluginCommand.effectiveModel(v) { null }, "model $v")
    }

    @Test fun `merged config - run env and serve config are equal for every mode and inherited config`() {
        val launch = AgentHqMcp.launch(mapOf("AGENTHQ_AGENT_ID" to "a1"))
        val mcp = HarnessProcess(launch.argv, env = launch.env)
        val inherited = listOf(null, "", """{"username":"u"}""", """{"permission":"allow"}""", """{"permission":{"bash":{"git *":"allow"},"edit":"deny"},"mcp":{"x":{}}}""")
        for ((core, plugin) in modes) for (existing in inherited) {
            assertEquals(
                OpenCodePermissions.runEnv(core, existing, mapOf("AGENTHQ_AGENT_ID" to "a1")),
                PluginPermissions.runEnv(plugin, existing, mcp),
                "runEnv $core $existing",
            )
            assertEquals(
                OpenCodePermissions.serveConfig(core, existing, mapOf("AGENTHQ_AGENT_ID" to "a1")),
                PluginPermissions.serveConfig(plugin, existing, mcp),
                "serveConfig $core $existing",
            )
        }
    }

    @Test fun `merged config - the harness config merge (mcp + watch plugin) is equal`() {
        val launch = AgentHqMcp.launch(emptyMap())
        val mcp = HarnessProcess(launch.argv, env = launch.env)
        val plugin = File("watch-plugin-dir", "agenthq-watch.js")
        for (existing in listOf(null, "{}", """{"plugin":["file:///other.js"],"permission":"ask"}""")) {
            for (parts in listOf(ConfigParts(mcp), ConfigParts(watchPlugin = plugin), ConfigParts(mcp, plugin), ConfigParts())) {
                assertEquals(
                    coreMerge(existing, parts),
                    PluginConfig.merge(existing, parts),
                    "$existing $parts",
                )
            }
        }
    }

    private fun coreMerge(existing: String?, parts: ConfigParts): String {
        var c = existing ?: "{}"
        parts.mcp?.let { c = AgentHqMcp.mergeOpenCode(c, AgentHqMcp.Launch(it.command.first(), it.command.drop(1), it.env)) }
        parts.watchPlugin?.let { c = com.superiorsoftllc.agenthq.daemon.WatchHooks.appendOpenCodePlugin(c, it) }
        return c
    }

    @Test fun `permission replies - same decision, reply body, denied tool and owner routing`() {
        val asks = listOf(
            """{"id":"p1","permission":"bash","patterns":["git status"]}""",
            """{"id":"p2","permission":"bash","patterns":["agenthq ticket list"]}""",
            """{"id":"p3","permission":"bash","patterns":["agenthq x; rm -rf /"]}""",
            """{"id":"p4","permission":"edit","patterns":["*"]}""",
            """{"id":"p5","permission":"webfetch","patterns":["*"],"metadata":{"url":"http://x"}}""",
            """{"id":"p6","permission":"agenthq_ticket_get"}""",
            """{"id":"p7","type":"bash","pattern":"agenthq mail inbox"}""",
            """{"id":"p8","permission":"read","metadata":{"filepath":"/a/b"}}""",
            """{"permission":"bash"}""",
        ).map(::obj)
        for ((core, plugin) in modes) for (ask in asks) {
            val c = OpenCodeServeAdapter.permissionDecision(ask, core)
            val p = PluginSession.permissionDecision(ask, plugin)
            assertEquals(c?.requestId, p?.requestId, "$core $ask")
            assertEquals(c?.replyBody, p?.replyBody, "$core $ask")
            assertEquals(c?.deniedTool, p?.deniedTool, "$core $ask")
            assertEquals(OpenCodeServeAdapter.needsOwner(ask, core), PluginSession.needsOwner(ask, plugin), "needsOwner $core $ask")
            assertEquals(OpenCodeServeAdapter.permissionName(ask), PluginSession.permissionName(ask))
            assertEquals(OpenCodeServeAdapter.approvalInput(ask), PluginSession.approvalInput(ask))
        }
        assertEquals(OpenCodePermissions.denialLine("ses_1", "bash").replace(Regex("\"timestamp\":\\d+"), ""), PluginPermissions.denialLine("ses_1", "bash").replace(Regex("\"timestamp\":\\d+"), ""))
        for (m in listOf("p/m", "p/", "/m", "nomodel", "a/b/c")) assertEquals(OpenCodeServeAdapter.splitModel(m), PluginSession.splitModel(m))
    }

    private class CoreSink : AdapterSink {
        val lines = mutableListOf<String>()
        val ends = mutableListOf<Triple<TurnEnd, Int?, String?>>()
        override fun output(line: String) { lines += line }
        override fun diagnostic(line: String) = Unit
        override fun detail(text: String) = Unit
        override fun providerSession(id: String) = Unit
        override fun turnEnded(end: TurnEnd, exitCode: Int?, reason: String?) { ends += Triple(end, exitCode, reason) }
    }

    private class PluginOut : SessionOutput {
        val lines = mutableListOf<String>()
        val ends = mutableListOf<Triple<SessionTurnEnd, Int?, String?>>()
        override fun write(text: String) = true
        override fun closeInput() = Unit
        override fun detail(text: String) = Unit
        override fun providerSession(id: String) = Unit
        override fun turnEnded(end: SessionTurnEnd, exitCode: Int?, reason: String?) { ends += Triple(end, exitCode, reason) }
        override fun output(line: String) { lines += line }
    }

    /** The server events of one realistic turn: the user echo, text, a tool running then completed, a rejected tool, a step finish, idle. */
    private val events: List<Triple<String, String, String?>> = listOf(
        Triple("message.updated", """{"info":{"role":"user","id":"mu"}}""", null),
        Triple("message.part.updated", """{"time":5,"part":{"id":"pu","messageID":"mu","type":"text","text":"prompt","time":{"end":1}}}""", """{"id":"pu","messageID":"mu","type":"text","text":"prompt","time":{"end":1}}"""),
        Triple("session.status", """{"status":{"type":"busy"}}""", null),
        Triple("message.part.updated", """{"time":7,"part":{}}""", """{"id":"t1","messageID":"m1","type":"tool","tool":"bash","callID":"c1","state":{"status":"running","input":{"command":"ls"}}}"""),
        Triple("message.part.updated", """{"time":8,"part":{}}""", """{"id":"t1","messageID":"m1","type":"tool","tool":"bash","callID":"c1","state":{"status":"completed","input":{"command":"ls"},"output":"a"}}"""),
        Triple("message.part.updated", """{"time":9,"part":{}}""", """{"id":"t2","messageID":"m1","type":"tool","tool":"edit","callID":"c2","state":{"status":"error","error":"The user rejected permission to use this specific tool call."}}"""),
        Triple("message.part.updated", """{"time":10,"part":{}}""", """{"id":"r1","messageID":"m1","type":"reasoning","text":"hmm","time":{"end":2}}"""),
        Triple("message.part.updated", """{"time":11,"part":{}}""", """{"id":"x1","messageID":"m1","type":"text","text":"done","time":{"end":3}}"""),
        Triple("message.part.updated", """{"time":12,"part":{}}""", """{"id":"f1","messageID":"m1","type":"step-finish","tokens":{"input":9,"output":3,"cache":{"read":1,"write":2}}}"""),
        Triple("session.idle", "{}", null),
    )

    @Test fun `stream - the serve path re-emits identical lines and the plugin reads what the daemon reads`() {
        val coreSink = CoreSink()
        val pluginOut = PluginOut()
        val core = OpenCodeServeAdapter.Turn(coreSink, "p/m")
        val plugin = PluginSession.Turn(pluginOut, "p/m")
        for ((type, props, part) in events) {
            val p = obj(props)
            val partObj = part?.let(::obj)
            core.onEvent(type, p, partObj, "ses_1")
            plugin.onEvent(type, p, partObj, "ses_1")
        }
        assertEquals(coreSink.lines, pluginOut.lines)
        assertEquals(listOf(TurnEnd.COMPLETED), coreSink.ends.map { it.first })
        assertEquals(listOf("COMPLETED"), pluginOut.ends.map { it.first.name })
        assertEquals(coreSink.ends.map { Triple(it.first.name, it.second, it.third) }, pluginOut.ends.map { Triple(it.first.name, it.second, it.third) })

        // What the daemon makes of each line (core normaliser) and what the plugin translator makes of it agree on the neutral facts.
        val translator = OpenCodeTranslator()
        val translated = pluginOut.lines.flatMap(translator::translate)
        val normalised = coreSink.lines.flatMap { EventNormalizer.normalise(it) }
        assertEquals(
            normalised.filterIsInstance<EventNormalizer.Line.Text>().map { it.delta },
            translated.filterIsInstance<ApiEvent.TextDelta>().map { it.text },
        )
        assertEquals(
            normalised.filterIsInstance<EventNormalizer.Line.Reasoning>().map { it.text },
            translated.filterIsInstance<ApiEvent.Reasoning>().map { it.text },
        )
        assertEquals(
            // The core line announces a started call again when it ends (and closes both); the plugin announces it once.
            normalised.filterIsInstance<EventNormalizer.Line.Tool>().map { it.name }.distinct(),
            translated.filterIsInstance<ApiEvent.ToolCall>().map { it.name }.distinct(),
        )
        assertEquals(
            normalised.filterIsInstance<EventNormalizer.Line.Denial>().map { it.tool },
            translated.filterIsInstance<ApiEvent.Denial>().map { it.tool },
        )
        assertEquals(1, translated.count { it is ApiEvent.Usage })
    }

    @Test fun `stream - an idle with nothing said and a session error end the same way`() {
        for (script in listOf(
            listOf(Triple("session.status", """{"status":{"type":"busy"}}""", null as String?), Triple("session.idle", "{}", null)),
            listOf(Triple("session.error", """{"error":{"name":"E","data":{"message":"bad key"}}}""", null), Triple("session.idle", "{}", null)),
        )) {
            val coreSink = CoreSink()
            val pluginOut = PluginOut()
            val core = OpenCodeServeAdapter.Turn(coreSink, "p/m") { listOf("stderr line") }
            val plugin = PluginSession.Turn(pluginOut, "p/m") { listOf("stderr line") }
            for ((type, props, _) in script) { core.onEvent(type, obj(props), null, "ses_1"); plugin.onEvent(type, obj(props), null, "ses_1") }
            assertEquals(coreSink.lines.map { it.replace(Regex("\"timestamp\":\\d+"), "") }, pluginOut.lines.map { it.replace(Regex("\"timestamp\":\\d+"), "") })
            assertEquals(coreSink.ends.map { Triple(it.first.name, it.second, it.third) }, pluginOut.ends.map { Triple(it.first.name, it.second, it.third) })
        }
    }
}
