// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.plugins.official.lua.daemon

import com.superiorsoftllc.infantry.daemon.api.Caller
import com.superiorsoftllc.infantry.daemon.api.DaemonTool
import com.superiorsoftllc.infantry.daemon.api.ToolInfo
import com.superiorsoftllc.infantry.daemon.api.ToolInvoker
import com.superiorsoftllc.infantry.daemon.api.ToolResult
import com.superiorsoftllc.infantry.daemon.api.Tools
import com.superiorsoftllc.infantry.plugins.official.scripting.daemon.ScriptCaller
import com.superiorsoftllc.infantry.plugins.official.scripting.daemon.ScriptDispatcher
import com.superiorsoftllc.infantry.plugins.official.scripting.daemon.ScriptLimits
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * SCRIPT-ARSENAL-1: every script the app bundles into the global scope (Infantry_Scripting resources `global-scripts/`) is parsed and run
 * for real through the Lua engine and the dispatcher, ALL phases, against a fake tool registry. A syntax error, a missing arg or a
 * phase that throws in any bundled script fails the build; a script with no scenario here fails too.
 */
class BundledGlobalScriptsTest {
    private val dir = File("../../Infantry_Scripting/daemon/src/main/resources/global-scripts")

    private class Fake(val answers: Map<String, String>) : Tools {
        val calls = mutableListOf<Pair<String, JsonObject>>()
        override fun register(tool: DaemonTool) {}
        override fun catalog() = TOOLS.map { ToolInfo(it, it, JsonObject(emptyMap())) }
        override fun invoke(name: String, arguments: JsonObject, `as`: ToolInvoker): ToolResult {
            calls += name to arguments
            return ToolResult(answers[name] ?: "{}")
        }
        fun named(n: String) = calls.filter { it.first == n }.map { it.second }
    }

    private class Scenario(val args: Map<String, Any?>, val answers: Map<String, String> = emptyMap(), val expectTools: List<String> = emptyList(), val expectLog: String? = null, val minPhases: Int = 1)

    private val now = System.currentTimeMillis()
    private val ticket = "ticket-aaaa1111"
    private val pm = "s-pm"

    private val scenarios: Map<String, List<Scenario>> = mapOf(
        "hook-health" to listOf(Scenario(
            mapOf("pm" to pm),
            mapOf("infantry_script_stats" to """{"runs":[{"script":"bad","ok":false,"error":"boom","at":$now},{"script":"bad","ok":false,"error":"boom","at":$now},{"script":"bad","ok":false,"error":"boom","at":$now}]}"""),
            listOf("infantry_notify", "infantry_chat_note"), minPhases = 2)),
        "find-ticket" to listOf(Scenario(mapOf("q" to "login"), mapOf("infantry_board" to """{"board":["  aaaa1111 | login page | backend"]}"""), expectLog = "login page", minPhases = 2)),
        "comment-tail" to listOf(Scenario(mapOf("ticket" to ticket), mapOf("infantry_ticket_comments" to "first\nlast line"), expectLog = "last line", minPhases = 2)),
        "needs-owner-alert" to listOf(
            Scenario(mapOf("ticketId" to ticket, "body" to "This needs owner decision"), expectTools = listOf("infantry_notify")),
            Scenario(mapOf("ticketId" to ticket), mapOf("infantry_ticket_comments" to "abc12345 x by agent-1: needs the owner"), listOf("infantry_notify"), minPhases = 2)),
        "worker-issue-wake" to listOf(Scenario(mapOf("ticketId" to ticket, "wake" to pm), mapOf("infantry_ticket_comments" to "c1 issue/open by agent-77 question"), listOf("infantry_chat_note"), minPhases = 2)),
        "wake-on-log" to listOf(Scenario(mapOf("path" to "x.log", "pattern" to "ready"), mapOf("infantry_exec" to """{"exitCode":0,"stdoutTail":"found: ready"}"""), listOf("infantry_exec", "infantry_chat_note"), minPhases = 2)),
        "done-forward" to listOf(Scenario(
            mapOf("ticket" to "aaaa1111", "ticketId" to ticket, "recipient" to "pm@other", "pm" to pm, "hookId" to "h1", "startNext" to "bbbb2222"),
            mapOf("infantry_ticket_comments" to "the report"), listOf("infantry_message", "infantry_ticket_start", "infantry_notify", "infantry_script_hook"), minPhases = 3)),
        "goal-handoff" to listOf(Scenario(
            mapOf("goals" to listOf("aaaa1111"), "ticketId" to ticket, "recipient" to "pm@other", "pm" to pm, "hookId" to "h1"),
            mapOf("infantry_ticket_get" to """{"status":"done"}"""), listOf("infantry_message", "infantry_chat_note", "infantry_script_hook"), minPhases = 2)),
        "goal-autostart-off" to listOf(Scenario(
            mapOf("goals" to listOf("aaaa1111"), "ticketId" to ticket, "pm" to pm, "offArgsJson" to "{}"),
            mapOf("infantry_ticket_get" to """{"status":"done"}"""), listOf("infantry_chat_note", "infantry_script_hook", "infantry_prompt"), minPhases = 2)),
        "mem-check" to listOf(Scenario(mapOf("wake" to pm), mapOf("infantry_processes" to "memory: 4000 MB free\nrogue (2)\n"), listOf("infantry_processes_reap", "infantry_chat_note"), minPhases = 2)),
        "worker-silent-ping" to listOf(Scenario(
            mapOf("pm" to pm),
            mapOf("infantry_list_sessions" to """{"sessions":[{"id":"s-worker-1","agentId":"a1","role":"backend","status":"BUSY","lastOutputAt":${now - 910_000}}]}"""),
            listOf("infantry_message", "infantry_chat_note"), minPhases = 2)),
        "worker-cap" to listOf(Scenario(
            mapOf("fill" to true),
            mapOf(
                "infantry_settings_get" to """{"value":2}""",
                "infantry_project_config_get" to """{"data":{"workers.cap.lowered":"1","workers.cap.status":"Lowered to 1 by the usage governor"}}""",
                "infantry_board" to "  aaaa1111 | todo | backend | a ticket\n",
            ),
            listOf("infantry_settings_get", "infantry_project_config_get", "infantry_board"), minPhases = 2)),
        "usage-governor" to listOf(Scenario(
            mapOf("project" to "p1", "pm" to pm),
            mapOf(
                "infantry_usage" to """{"meters":[{"meter":"claude","rows":[{"label":"Current week","paceDelta":1.5}]}]}""",
                "infantry_settings_get" to """{"value":{"maxWorkers":2}}""",
                "infantry_project_config_get" to """{"data":{},"routingRules":[]}""",
            ),
            listOf("infantry_settings_set", "infantry_project_config_set", "infantry_notify"), minPhases = 2)),
        "lane-park" to listOf(Scenario(mapOf("ticketId" to ticket, "doneLane" to "done", "from" to "in_progress", "to" to "todo", "workerSession" to "w1"), expectTools = listOf("infantry_session_remove", "infantry_ticket_comment"), minPhases = 2)),
        "lane-pending-note" to listOf(
            Scenario(mapOf("ticketId" to ticket, "from" to "todo", "to" to "pending"), expectTools = listOf("infantry_ticket_comment")),
            Scenario(mapOf("ticketId" to ticket, "from" to "pending", "to" to "todo", "startLane" to "todo"), expectTools = listOf("infantry_ticket_start"))),
        "lane-pin-check" to listOf(Scenario(
            mapOf("ticketId" to ticket, "to" to "todo", "provider" to "claude", "allowedProviders" to listOf("claude"), "defaultModel" to "m1"),
            mapOf("infantry_ticket_get" to """{"ticketId":"$ticket","provider":"other","model":"x","weight":2,"role":"backend"}"""),
            listOf("infantry_ticket_update", "infantry_ticket_comment"), minPhases = 2)),
        "lane-one-worker" to listOf(
            Scenario(
                mapOf("ticketId" to ticket, "maxInProgress" to 1, "exemptRoles" to listOf("version-control"), "workerSession" to "w1", "kickPrompt" to "go {ticket}"),
                mapOf("infantry_ticket_get" to """{"role":"backend","title":"t"}""", "infantry_board" to """{"board":["  cccc3333 | other | backend 1234abcd x"]}"""),
                listOf("infantry_session_remove", "infantry_ticket_move", "infantry_ticket_comment"), minPhases = 2),
            Scenario(
                mapOf("ticketId" to ticket, "maxInProgress" to 1, "workerSession" to "w1", "kickPrompt" to "go {ticket}"),
                mapOf("infantry_ticket_get" to """{"role":"backend","title":"t"}""", "infantry_board" to """{"board":[]}""", "infantry_get_session" to """{"status":"IDLE","runCount":1}"""),
                listOf("infantry_prompt"), minPhases = 3)),
    )

    private fun index(): List<String> =
        (Json.parseToJsonElement(File(dir, "index.json").readText()) as JsonArray).map { (it as JsonPrimitive).content }

    private fun toLua(v: Any?): Any? = v

    private val aliases = mapOf("message" to "infantry_message", "comment" to "infantry_ticket_comment")
    private val invoker = ToolInvoker(Caller(owner = false, agent = true), "s1", "pm", "p", "script:bundled")

    @Test fun `every bundled script parses and has the documented header`() {
        val engine = LuaScriptEngine()
        val names = index()
        assertTrue(names.size >= 14, "the starter set shrank: $names")
        for (file in names) {
            val src = File(dir, file).readText()
            assertNull(engine.check(src), "$file does not parse")
            for (marker in listOf("-- Args:", "-- Hook:", "-- Gotchas")) assertTrue(marker in src, "$file has no '$marker' header line")
            assertTrue(!Regex("project-[0-9a-f]{8}|agent-[0-9a-f]{8}|pm@project-|agenthq").containsMatchIn(src), "$file names a project, session or old product id")
        }
    }

    @Test fun `every bundled script has a scenario`() {
        assertEquals(index().map { it.removeSuffix(".lua") }.sorted(), scenarios.keys.sorted(), "add (or remove) a scenario for each bundled script")
    }

    @Test fun `every bundled script runs end to end through all its phases`() {
        for ((name, list) in scenarios) {
            val src = File(dir, "$name.lua").readText()
            list.forEachIndexed { i, sc ->
                val label = "$name#${i + 1}"
                val f = Fake(sc.answers)
                val r = ScriptDispatcher(f, { aliases }, { 6 }).run(LuaScriptEngine(), src, sc.args, ScriptLimits(), ScriptCaller("s1", "pm"), invoker, false)
                assertTrue(r.ok, "$label: ${r.render(name)}")
                assertTrue(r.phases >= sc.minPhases, "$label ran ${r.phases} phase(s), wanted >= ${sc.minPhases}: ${r.render(name)}")
                for (t in sc.expectTools) assertTrue(f.named(t).isNotEmpty(), "$label never called $t; called ${f.calls.map { it.first }}")
                sc.expectLog?.let { l -> assertTrue(r.logs.any { l in it }, "$label log lacks '$l': ${r.logs}") }
            }
        }
    }

    @Test fun `phase one of every scenario also plans cleanly as a dry run`() {
        for ((name, list) in scenarios) {
            val src = File(dir, "$name.lua").readText()
            val r = ScriptDispatcher(Fake(emptyMap()), { aliases }, { 6 }).run(LuaScriptEngine(), src, list.first().args, ScriptLimits(), ScriptCaller("s1", "pm"), invoker, true)
            assertTrue(r.ok, "$name dry run: ${r.render(name)}")
        }
    }

    @Test fun `hook-health alerts after three failures in a row and stays quiet below that`() {
        val src = File(dir, "hook-health.lua").readText()
        fun run(n: Int): Fake {
            val runs = (1..n).joinToString(",") { """{"script":"bad","ok":false,"error":"boom","at":$now}""" }
            val f = Fake(mapOf("infantry_script_stats" to """{"runs":[$runs]}"""))
            assertTrue(ScriptDispatcher(f, { aliases }, { 3 }).run(LuaScriptEngine(), src, mapOf("pm" to pm), ScriptLimits(), ScriptCaller("s1", "pm"), invoker, false).ok)
            return f
        }
        assertTrue(run(2).named("infantry_notify").isEmpty(), "two failures do not alert")
        val three = run(3)
        assertEquals(1, three.named("infantry_notify").size)
        assertTrue((three.named("infantry_notify").single()["body"] as JsonPrimitive).contentOrNull!!.contains("failed 3 runs"))
    }

    companion object {
        val TOOLS = listOf(
            "infantry_notify", "infantry_chat_note", "infantry_ticket_comment", "infantry_ticket_comments", "infantry_ticket_update", "infantry_ticket_get",
            "infantry_ticket_move", "infantry_ticket_start", "infantry_script_hook", "infantry_script_stats", "infantry_session_remove", "infantry_prompt",
            "infantry_message", "infantry_board", "infantry_list_sessions", "infantry_get_session", "infantry_processes", "infantry_processes_reap",
            "infantry_usage", "infantry_settings_get", "infantry_settings_set", "infantry_project_config_get", "infantry_project_config_set", "infantry_exec",
        )
    }
}
