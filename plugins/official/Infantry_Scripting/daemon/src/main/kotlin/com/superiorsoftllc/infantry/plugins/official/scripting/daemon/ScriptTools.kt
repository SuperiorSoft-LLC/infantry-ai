package com.superiorsoftllc.infantry.plugins.official.scripting.daemon

import com.superiorsoftllc.infantry.daemon.api.DaemonTool
import com.superiorsoftllc.infantry.daemon.api.ToolCall
import com.superiorsoftllc.infantry.daemon.api.ToolInvoker
import com.superiorsoftllc.infantry.daemon.api.ToolResult
import com.superiorsoftllc.infantry.daemon.api.Tools
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject

/** The two MCP tools of the Scripting plugin. No role check anywhere (D18): the caller's identity is carried, never tested. */
class ScriptTools(
    private val tools: Tools,
    private val engines: ScriptEngines,
    private val store: ScriptStore,
    private val limits: ScriptingLimits,
    private val aliases: () -> Map<String, String>,
    private val maxPhases: () -> Int,
    /** SCRIPT-VIS-1: every manual run is recorded here. */
    private val runLog: ScriptRunLog = ScriptRunLog(),
    /** SCRIPT-ACTIVE-1: lights the invoker's card while the script runs. */
    private val marks: AutomationMarks = AutomationMarks({ _, _ -> }),
) {
    private val dispatcher = ScriptDispatcher(tools, aliases, maxPhases)

    val run: DaemonTool = object : DaemonTool {
        override val name = RUN
        override val description = "Run a named script of this project (<project>/.infantry/scripts/<name>.<ext>) as you: the daemon runs the script and the tool calls it queues, no model, no tokens. " +
            "Output: the actions taken (or planned with dryRun) with each result, and the script's log. Read the API with infantry_script_api. Open to every role."
        override val inputSchema: JsonObject = Json.parseToJsonElement(
            """{"type":"object","properties":{"name":{"type":"string","description":"Script name, e.g. on-done"},"args":{"type":"string","description":"JSON object given to the script as input.args (default {})"},"dryRun":{"type":"boolean","description":"Return the planned actions without running any"},"project":{"type":"string","description":"Project id (default: yours)"}},"required":["name"]}""",
        ).jsonObject
        override val timeoutMs: Long = 120_000
        override fun call(call: ToolCall): ToolResult = runScript(call)
    }

    val api: DaemonTool = object : DaemonTool {
        override val name = API
        override val description = "Everything a script can call: every tool in the live registry with its arguments, the alias table and how the script language binds them. Compact; pass tool for one in full. Open to every role."
        override val inputSchema: JsonObject = Json.parseToJsonElement(
            """{"type":"object","properties":{"tool":{"type":"string","description":"One tool (or alias) in full"}}}""",
        ).jsonObject
        override val annotations: JsonObject = Json.parseToJsonElement("""{"readOnlyHint":true}""").jsonObject
        override fun call(call: ToolCall): ToolResult {
            val tool = (call.arguments["tool"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
            val text = ScriptApi.describe(tools, aliases(), engines.bindings(), tool)
            return ToolResult(text, isError = tool != null && text.startsWith("unknown tool"))
        }
    }

    fun runScript(call: ToolCall): ToolResult {
        val a = call.arguments
        val name = (a["name"] as? JsonPrimitive)?.contentOrNull?.trim().orEmpty()
        val project = (a["project"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() } ?: call.clientProjectId
            ?: return ToolResult("$RUN needs a project (pass project, or run it from a project session)", isError = true)
        val dry = (a["dryRun"] as? JsonPrimitive)?.booleanOrNull == true
        val args: Map<String, Any?> = try {
            when (val raw = a["args"]) {
                null -> emptyMap()
                is JsonPrimitive -> if (raw.contentOrNull.isNullOrBlank()) emptyMap() else parseArgs(raw.content)
                is JsonObject -> parseArgs(raw.toString())
                else -> return ToolResult("$RUN: args must be a JSON object", isError = true)
            }
        } catch (e: Exception) {
            return ToolResult("$RUN: args must be a JSON object (${e.message?.take(120)})", isError = true)
        }
        val script = try {
            store.read(project, name)
        } catch (e: IllegalArgumentException) {
            return ToolResult("$RUN refused: ${e.message}", isError = true)
        } catch (e: IllegalStateException) {
            return ToolResult("$RUN refused: ${e.message}", isError = true)
        } ?: return ToolResult("$RUN: no script '$name' in project $project (looked in .infantry/scripts/ for ${engines.extensions().joinToString("/") { ".$it" }}); have: ${runCatching { store.names(project) }.getOrDefault(emptyList()).joinToString().ifBlank { "none" }}", isError = true)
        val engine = engines.byExtension(script.extension) ?: return ToolResult("$RUN: no engine loaded for .${script.extension}", isError = true)
        val who = ScriptCaller(call.clientSessionId.orEmpty(), call.clientRole.orEmpty())
        val invoker = ToolInvoker(call.caller, call.clientSessionId, call.clientRole, project, "script:$name")
        val report = (if (dry) AutoCloseable { } else marks.begin(call.clientSessionId, name, "manual")).use { dispatcher.run(engine, script.source, args, limits.current(), who, invoker, dry) }
        if (!dry) runLog.record(project, name, "manual", call.clientSessionId, report, if (report.ok) "ok" else "failed", "ran ${report.records.size} action(s)", report.error)
        return ToolResult(report.render(name), isError = !report.ok)
    }

    @Suppress("UNCHECKED_CAST")
    private fun parseArgs(text: String): Map<String, Any?> = ScriptEngineWire.fromJson(Json.parseToJsonElement(text).jsonObject) as Map<String, Any?>

    companion object {
        const val RUN = "infantry_script_run"
        const val API = "infantry_script_api"
    }
}
