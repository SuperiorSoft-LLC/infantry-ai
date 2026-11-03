package com.superiorsoftllc.infantry.plugins.official.run.daemon

import com.superiorsoftllc.infantry.daemon.api.Caller
import com.superiorsoftllc.infantry.daemon.api.ToolCall
import com.superiorsoftllc.infantry.daemon.api.ToolResult
import com.superiorsoftllc.infantry.protocol.Envelope
import com.superiorsoftllc.infantry.runner.ScriptedRunner
import com.superiorsoftllc.infantry.runner.StubScript
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** RUN-CMD-TOOL-1: save / list / delete / start / stop through the tools, and the cross-project refusal. */
class RunCommandToolsTest {
    private val repo: File = Files.createTempDirectory("infantry-runcmd-tool").toFile()
    private val frames = CopyOnWriteArrayList<Envelope>()
    private val store = CommandStore(File(repo, "commands.json"), repo)
    private val manager = RunManager({ store.registry }, ScriptedRunner(StubScript(stdoutLines = listOf("a")), 60_000), { frames += it })
    private var broadcasts = 0
    private val tools = RunCommandTools(store, manager, { broadcasts++ }, { frames += it }).all().associateBy { it.name }

    private fun call(name: String, project: String? = "p1", vararg args: Pair<String, Any>): ToolResult {
        val a = JsonObject(args.associate { (k, v) ->
            k to when (v) { is Boolean -> JsonPrimitive(v); is List<*> -> JsonArray(v.map { JsonPrimitive(it.toString()) }); else -> JsonPrimitive(v.toString()) }
        })
        return tools.getValue(name).call(object : ToolCall {
            override val arguments = a
            override val caller: Caller get() = error("unused")
            override val clientProjectId = project
            override fun request(type: String, data: String, sessionId: String?, timeoutMs: Long) = error("unused")
        })
    }

    @Test fun `five tools are offered`() {
        assertEquals(setOf("infantry_run_commands", "infantry_run_command_save", "infantry_run_command_delete", "infantry_run_start", "infantry_run_stop"), tools.keys)
    }

    @Test fun `save then list then delete`() {
        val saved = call("infantry_run_command_save", "p1", "id" to "site", "name" to "Site", "argv" to listOf("echo", "hi"))
        assertFalse(saved.isError, saved.text)
        assertTrue(call("infantry_run_commands").text.contains("\"site\""))
        assertEquals(1, broadcasts)
        val edited = call("infantry_run_command_save", "p1", "id" to "site", "command" to "echo two")
        assertFalse(edited.isError, edited.text)
        assertTrue(call("infantry_run_commands").text.contains("echo two"))
        assertFalse(call("infantry_run_command_delete", "p1", "id" to "site").isError)
        assertFalse(call("infantry_run_commands").text.contains("\"site\""))
        assertTrue(call("infantry_run_command_delete", "p1", "id" to "site").isError, "no such command")
    }

    @Test fun `a bad cwd is refused like the frame refuses it`() {
        val r = call("infantry_run_command_save", "p1", "id" to "x", "argv" to listOf("echo"), "cwd" to "../out")
        assertTrue(r.isError)
        assertTrue(r.text.contains("cwd outside the repo"), r.text)
    }

    @Test fun `start returns a run id and stop stops it`() {
        call("infantry_run_command_save", "p1", "id" to "svc", "argv" to listOf("x"))
        val started = call("infantry_run_start", "p1", "id" to "svc")
        assertFalse(started.isError, started.text)
        val runId = started.text.removePrefix("started run ").trim()
        assertTrue(runId.isNotEmpty() && runId != "?", started.text)
        assertFalse(call("infantry_run_stop", "p1", "runId" to runId, "force" to true).isError)
        assertTrue(call("infantry_run_stop", "p1", "runId" to "nope").isError)
    }

    @Test fun `another project is refused without the explicit flag`() {
        val refused = call("infantry_run_command_save", "p1", "id" to "x", "argv" to listOf("echo"), "project" to "p2")
        assertTrue(refused.isError)
        assertTrue(refused.text.contains("allowCrossProject"), refused.text)
        assertTrue(call("infantry_run_start", "p1", "id" to "x", "project" to "p2").isError)
        val ok = call("infantry_run_command_save", "p1", "id" to "x", "argv" to listOf("echo"), "project" to "p2", "allowCrossProject" to true)
        assertFalse(ok.isError, ok.text)
    }
}
