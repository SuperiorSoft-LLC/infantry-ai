package com.superiorsoftllc.agenthq.plugins.official.lua.daemon

import com.superiorsoftllc.agenthq.daemon.api.Caller
import com.superiorsoftllc.agenthq.daemon.api.DaemonTool
import com.superiorsoftllc.agenthq.daemon.api.Frame
import com.superiorsoftllc.agenthq.daemon.api.ToolCall
import com.superiorsoftllc.agenthq.daemon.api.ToolInfo
import com.superiorsoftllc.agenthq.daemon.api.ToolInvoker
import com.superiorsoftllc.agenthq.daemon.api.ToolResult
import com.superiorsoftllc.agenthq.daemon.api.Tools
import com.superiorsoftllc.agenthq.plugins.official.scripting.daemon.ExecTools
import com.superiorsoftllc.agenthq.plugins.official.scripting.daemon.ScriptCaller
import com.superiorsoftllc.agenthq.plugins.official.scripting.daemon.ScriptDispatcher
import com.superiorsoftllc.agenthq.plugins.official.scripting.daemon.ScriptLimits
import kotlinx.serialization.json.JsonObject
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** SCRIPT-CMD-1: a Lua script execs processes, reads {exitCode, ...} in phase 2 and branches on it (ih.exec is built in, no alias setting). */
class LuaExecTest {
    private val sent = ArrayList<String>()
    private val exec = ExecTools({ if (it == "scripting.exec.enabled") "true" else null }, { Files.createTempDirectory("lx").toFile() }, { _, _ -> }, {})
    private val prompt = object : DaemonTool {
        override val name = "fake_prompt"
        override val description = "p"
        override val inputSchema = JsonObject(emptyMap())
        override fun call(call: ToolCall): ToolResult { sent += call.arguments.toString(); return ToolResult("ok") }
    }
    private val registry = object : Tools {
        private val all = listOf(exec.exec, exec.status, prompt).associateBy { it.name }
        override fun register(tool: DaemonTool) {}
        override fun catalog() = all.values.map { ToolInfo(it.name, it.description, it.inputSchema) }
        override fun invoke(name: String, arguments: JsonObject, `as`: ToolInvoker): ToolResult = all.getValue(name).call(object : ToolCall {
            override val arguments = arguments
            override val caller = `as`.caller
            override val clientSessionId = `as`.sessionId
            override val clientRole = `as`.role
            override val clientProjectId = `as`.projectId
            override fun request(type: String, data: String, sessionId: String?, timeoutMs: Long): Frame = error("n/a")
        })
    }
    private val invoker = ToolInvoker(Caller(owner = false, agent = true), "s1", "vc", "p", "script:t")

    @Test fun `a script execs in phase 1 and branches on the exit code in phase 2`() {
        val java = File(System.getProperty("java.home"), "bin/java").path.replace("\\", "/")
        val src = """
            if not input.results then
              ih.exec{command={"$java", "-version"}}
              ih.exec{command={"$java", "-cp", "no-such-dir", "NoSuchMain"}}
              ih.again()
            else
              local good, bad = input.results[1].json, input.results[2].json
              ih.log("ok exit "..good.exitCode)
              if bad.exitCode ~= 0 then ih.call("fake_prompt", {text="exec failed: "..bad.exitCode}) end
            end
        """.trimIndent()
        val r = ScriptDispatcher(registry, { emptyMap() }).run(LuaScriptEngine(), src, emptyMap(), ScriptLimits(), ScriptCaller("s1", "vc"), invoker, false)
        assertTrue(r.ok, r.render("t"))
        assertTrue("[phase 2] ok exit 0" in r.logs, r.logs.toString())
        assertEquals(1, sent.size)
        assertTrue("exec failed: 1" in sent[0], sent[0])
    }

    @Test fun `ih cli takes a bare command line string and a table`() {
        val java = File(System.getProperty("java.home"), "bin/java").path.replace("\\", "/")
        val src = """
            if not input.results then
              ih.cli("$java -version")
              ih.cli{command={"$java", "-version"}}
              ih.again()
            else
              ih.log("exits "..input.results[1].json.exitCode..input.results[2].json.exitCode)
            end
        """.trimIndent()
        val r = ScriptDispatcher(registry, { emptyMap() }).run(LuaScriptEngine(), src, emptyMap(), ScriptLimits(), ScriptCaller("s1", "vc"), invoker, false)
        assertTrue(r.ok, r.render("t"))
        assertTrue("[phase 2] exits 00" in r.logs, r.logs.toString())
    }
}
