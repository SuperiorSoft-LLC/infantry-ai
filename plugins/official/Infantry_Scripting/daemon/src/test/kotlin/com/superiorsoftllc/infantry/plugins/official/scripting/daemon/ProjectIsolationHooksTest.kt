// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.plugins.official.scripting.daemon

import com.superiorsoftllc.infantry.daemon.api.DaemonTool
import com.superiorsoftllc.infantry.daemon.api.IncomingMail
import com.superiorsoftllc.infantry.daemon.api.NamedEvent
import com.superiorsoftllc.infantry.daemon.api.ToolInfo
import com.superiorsoftllc.infantry.daemon.api.ToolInvoker
import com.superiorsoftllc.infantry.daemon.api.ToolResult
import com.superiorsoftllc.infantry.daemon.api.Tools
import com.superiorsoftllc.infantry.daemon.api.files.ProjectFileEntry
import com.superiorsoftllc.infantry.daemon.api.files.ProjectFiles
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** PROJECT-ISOLATION-1: a hook armed in project A runs only for A's events; a project-less event reaches only hooks marked global. */
class ProjectIsolationHooksTest {
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

    /** Tickets: ticket-a… is project A's, ticket-b… is B's (as the owner's infantry_ticket_get answers). */
    private class FakeTools : Tools {
        val calls = ArrayList<Pair<String, ToolInvoker>>()
        override fun register(tool: DaemonTool) {}
        override fun catalog(): List<ToolInfo> = listOf("t_move", "infantry_ticket_get", "infantry_ticket_comment")
            .map { ToolInfo(it, "d", Json.parseToJsonElement("""{"type":"object"}""").jsonObject) }
        override fun invoke(name: String, arguments: JsonObject, `as`: ToolInvoker): ToolResult {
            calls += name to `as`
            if (name == "infantry_ticket_get") {
                val id = arguments["ticketId"].toString().trim('"')
                val p = if (id.startsWith("ticket-a")) "A" else "B"
                return ToolResult("""{"ticketId":"$id","title":"T","status":"done","projectId":"$p"}""")
            }
            return ToolResult("ok")
        }
    }

    /** Records the project of every hook that ran (its args carry `hookProject`) and moves the event's ticket. */
    private class FakeEngine : ScriptEngine {
        override val id = "fake"
        override val extensions = listOf("fk")
        val ran = ArrayList<String>()
        override fun run(source: String, input: Map<String, Any?>, limits: ScriptLimits, caller: ScriptCaller): ScriptResult {
            ran += ((input["args"] as Map<*, *>)["hookProject"] as String)
            return ScriptResult(ScriptOutcome.OK, listOf(QueuedAction("move", mapOf("ticketId" to "ticket-a0000001", "to" to "done"))))
        }
    }

    private val files = mapOf("A" to MemFiles(), "B" to MemFiles())
    private val tools = FakeTools()
    private val engine = FakeEngine()

    private fun arm(project: String, vararg hooks: String) {
        files.getValue(project).put("scripts/s.fk", "src".toByteArray())
        files.getValue(project).put(HookStore.PATH, """{"version":1,"hooks":[${hooks.joinToString(",")}]}""".toByteArray())
    }

    private fun hook(id: String, on: String, project: String, global: Boolean = false, to: String? = null) =
        """{"id":"$id","on":"$on","script":"s","args":{"hookProject":"$project"}${if (global) ""","global":true""" else ""}${to?.let { ""","to":"$it","ownerSession":"$it","ownerRole":"pm"""" } ?: ""}}"""

    private fun hooks(): ScriptHooks {
        val engines = ScriptEngines({ mapOf(ScriptEngineWire.PREFIX + "fake" to ScriptEngineWire.asEndpoint(engine)) })
        return ScriptHooks(
            HookStore { files.getValue(it) }, ScriptStore({ files.getValue(it) }, { engines.extensions() }), engines, tools,
            ScriptDispatcher(tools, { mapOf("move" to "t_move") }),
            projects = { listOf("A", "B") }, limits = { ScriptLimits() }, executor = { it() },
        )
    }

    private var seq = 0
    private fun ev(name: String, payload: String) = (++seq).let { NamedEvent(name, payload, "daemon", "e$it", "e$it", 0, 1) }

    @Test fun `B's events do not run A's hooks, A's events still do`() {
        arm("A", hook("a", "ticket.commented", "A"), hook("al", "lane enter done", "A"))
        val h = hooks()
        h.onEvent(ev("ticket.commented", """{"ticketId":"ticket-b0000001","commentId":"c1"}"""))
        h.onEvent(ev("ticket.moved", """{"ticketId":"ticket-b0000002","from":"todo","to":"done"}"""))
        assertEquals(emptyList(), engine.ran, "B's comment and lane change never reach A's hooks")
        h.onEvent(ev("ticket.commented", """{"ticketId":"ticket-a0000001","commentId":"c2"}"""))
        h.onEvent(ev("ticket.moved", """{"ticketId":"ticket-a0000003","from":"todo","to":"done"}"""))
        assertEquals(listOf("A", "A"), engine.ran)
    }

    @Test fun `a stamped event goes to its project's hooks only`() {
        arm("A", hook("a", "session.idle", "A"))
        arm("B", hook("b", "session.idle", "B"))
        val h = hooks()
        h.onEvent(ev("session.idle", """{"sessionId":"s1","projectId":"B"}"""))
        assertEquals(listOf("B"), engine.ran)
    }

    @Test fun `an event with no project reaches only hooks marked global`() {
        arm("A", hook("a", "merge.done", "A"))
        arm("B", hook("g", "merge.done", "B", global = true))
        hooks().onEvent(ev("merge.done", """{"sha":"s1"}"""))
        assertEquals(listOf("B"), engine.ran)
    }

    @Test fun `a hook's tool calls are confined to the hook's project`() {
        arm("A", hook("a", "ticket.commented", "A"))
        hooks().onEvent(ev("ticket.commented", """{"ticketId":"ticket-a0000001","commentId":"c1"}"""))
        val (_, who) = tools.calls.first { it.first == "t_move" }
        assertEquals("A", who.caller.project)
    }

    @Test fun `mail of project B does not run A's mail hook`() {
        arm("A", hook("m", "mail.incoming", "A", to = "pm-1"))
        val h = hooks()
        h.incoming(IncomingMail("mail-1", "vc-1", "pm", "pm-1", "pm", "subj", "body", null, "B"))
        assertTrue(engine.ran.isEmpty())
        h.incoming(IncomingMail("mail-2", "vc-1", "pm", "pm-1", "pm", "subj", "body", null, "A"))
        assertEquals(listOf("A"), engine.ran)
        assertFalse(ScriptHooks.admitsProject("A", Hook("x", HookOn.Named("y"), "s"), null))
        assertTrue(ScriptHooks.admitsProject("A", Hook("x", HookOn.Named("y"), "s", global = true), null))
    }
}
