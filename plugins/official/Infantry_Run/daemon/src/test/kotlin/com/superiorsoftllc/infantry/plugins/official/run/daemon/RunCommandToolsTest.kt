// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.plugins.official.run.daemon

import com.superiorsoftllc.infantry.daemon.api.Caller
import com.superiorsoftllc.infantry.daemon.api.DaemonTool
import com.superiorsoftllc.infantry.daemon.api.ToolCall
import com.superiorsoftllc.infantry.daemon.api.ToolResult
import com.superiorsoftllc.infantry.protocol.Envelope
import com.superiorsoftllc.infantry.runner.ProcessListener
import com.superiorsoftllc.infantry.runner.ProcessRunner
import com.superiorsoftllc.infantry.runner.ProcessSpec
import com.superiorsoftllc.infantry.runner.RunningProcess
import com.superiorsoftllc.infantry.runner.ScriptedRunner
import com.superiorsoftllc.infantry.runner.StubScript
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
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

    private fun call(name: String, project: String? = "p1", vararg args: Pair<String, Any>, toolMap: Map<String, DaemonTool> = tools): ToolResult {
        val a = JsonObject(args.associate { (k, v) ->
            k to when (v) { is Boolean -> JsonPrimitive(v); is List<*> -> JsonArray(v.map { JsonPrimitive(it.toString()) }); else -> JsonPrimitive(v.toString()) }
        })
        return toolMap.getValue(name).call(object : ToolCall {
            override val arguments = a
            override val caller: Caller get() = error("unused")
            override val clientProjectId = project
            override fun request(type: String, data: String, sessionId: String?, timeoutMs: Long) = error("unused")
        })
    }

    @Test fun `seven tools are offered`() {
        assertEquals(setOf("infantry_run_commands", "infantry_run_command_save", "infantry_run_command_delete", "infantry_run_start", "infantry_run_secrets", "infantry_secret_render", "infantry_run_stop"), tools.keys)
    }

    // SECRETS-2b: names and scopes only, the caller's project by default, the cross-project refusal as for the other tools.
    @Test fun `the secrets tool lists names and scopes for the caller's project`() {
        val asked = mutableListOf<String?>()
        val t = RunCommandTools(store, manager, {}, {}, { p -> asked += p; if (p == "p1") mapOf("deploy" to "project", "android-signing" to "app") else mapOf("android-signing" to "app") })
            .all().associateBy { it.name }
        fun secrets(project: String?, vararg args: Pair<String, Any>): ToolResult = t.getValue("infantry_run_secrets").call(object : ToolCall {
            override val arguments = JsonObject(args.associate { (k, v) -> k to if (v is Boolean) JsonPrimitive(v) else JsonPrimitive(v.toString()) })
            override val caller: Caller get() = error("unused")
            override val clientProjectId = project
            override fun request(type: String, data: String, sessionId: String?, timeoutMs: Long) = error("unused")
        })
        assertEquals("android-signing  (app)\ndeploy  (project)", secrets("p1").text)
        assertEquals("deploy  (project)", secrets("p1", "scope" to "project").text)
        assertEquals("p1", asked.last())
        assertTrue(secrets("p1", "project" to "p2").isError, "another project needs allowCrossProject")
        assertEquals("android-signing  (app)", secrets("p1", "project" to "p2", "allowCrossProject" to true).text)
        assertTrue(secrets("p1", "scope" to "nope").isError)
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
        assertTrue(r.text.contains("cwd outside the project folder"), r.text)
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

    /** A child that runs until it is terminated or killed (HARNESS-GAP G17: the run must still be in flight when stopped). */
    private class HangingRunner : ProcessRunner {
        override val id = "hanging"
        override fun start(spec: ProcessSpec, listener: ProcessListener): RunningProcess = object : RunningProcess {
            private val done = CountDownLatch(1)
            @Volatile private var alive = true
            override val isAlive get() = alive
            override fun writeInput(text: String) {}
            override fun closeInput() {}
            override fun terminate() { end() }
            override fun killTree() { end() }
            private fun end() { if (alive) { alive = false; Thread { listener.onExit(143); done.countDown() }.start() } }
            override fun awaitExit(): Int { done.await(); return 143 }
        }
    }

    @Test fun `stop refuses another project's run and still stops your own`() {
        val hangingTools = RunCommandTools(store, RunManager({ store.registry }, HangingRunner(), { frames += it }), { broadcasts++ }, { frames += it })
            .all().associateBy { it.name }
        assertFalse(call("infantry_run_command_save", "p1", "id" to "hang", "argv" to listOf("x")).isError)
        val started = call("infantry_run_start", "p1", "id" to "hang", toolMap = hangingTools)
        assertFalse(started.isError, started.text)
        val runId = started.text.removePrefix("started run ").trim()
        val foreign = call("infantry_run_stop", "p2", "runId" to runId, toolMap = hangingTools)
        assertTrue(foreign.isError, foreign.text)
        assertTrue(foreign.text.contains("allowCrossProject"), foreign.text)
        assertFalse(call("infantry_run_stop", "p1", "runId" to runId, toolMap = hangingTools).isError)
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
