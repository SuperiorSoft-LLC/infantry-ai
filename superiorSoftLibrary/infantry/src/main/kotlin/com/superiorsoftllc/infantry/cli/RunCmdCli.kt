package com.superiorsoftllc.infantry.cli

import com.superiorsoftllc.infantry.cli.mcp.McpPluginTools
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * RUN-CMD-TOOL-1: `infantry run-cmd list | save <id> <cwd|.> "<command line>" [--name N] | delete <id> | start <id> | stop <runId> [--force]`
 * (+ `--project <id> --cross-project`). Each is one call of the Infantry_Run tool of the same purpose (`plugin_tool_call`), so the
 * CLI, MCP and Lua share one answer and one gate. Quote the command line so its own flags are not read as flags of this CLI.
 */
internal object RunCmdCli {
    private val TOOLS = mapOf(
        "list" to "infantry_run_commands",
        "save" to "infantry_run_command_save",
        "delete" to "infantry_run_command_delete",
        "start" to "infantry_run_start",
        "stop" to "infantry_run_stop",
    )

    const val USAGE = "infantry run-cmd list | save <id> <cwd|.> \"<command line>\" [--name N] | delete <id> | start <id> | stop <runId> [--force] [--project P --cross-project]"

    fun usageError(args: List<String>): String? {
        val sub = args.firstOrNull() ?: return "run-cmd needs a subcommand: $USAGE"
        if (sub !in TOOLS) return "unknown run-cmd subcommand '$sub': $USAGE"
        val wanted = when (sub) { "list" -> 1; "save" -> 4; else -> 2 }
        return if (args.size != wanted) "run-cmd $sub takes ${wanted - 1} argument(s): $USAGE" else null
    }

    fun run(
        stream: FrameStream, args: List<String>, name: String?, project: String?, crossProject: Boolean, force: Boolean,
        out: Appendable, err: Appendable, getenv: (String) -> String?,
    ): Int = runBlocking {
        val sub = args.first()
        val m = LinkedHashMap<String, kotlinx.serialization.json.JsonElement>()
        when (sub) {
            "save" -> {
                m["id"] = JsonPrimitive(args[1])
                if (args[2] != ".") m["cwd"] = JsonPrimitive(args[2])
                m["command"] = JsonPrimitive(args[3])
                name?.let { m["name"] = JsonPrimitive(it) }
            }
            "stop" -> { m["runId"] = JsonPrimitive(args[1]); if (force) m["force"] = JsonPrimitive(true) }
            "delete", "start" -> m["id"] = JsonPrimitive(args[1])
        }
        if (sub != "list" && sub != "stop") {
            project?.let { m["project"] = JsonPrimitive(it) }
            if (crossProject) m["allowCrossProject"] = JsonPrimitive(true)
        }
        val result = McpPluginTools(getenv).call(stream, TOOLS.getValue(sub), JsonObject(m))
        if (result.text.startsWith("Unknown tool:")) {
            err.appendLine("error: this daemon has no Run tools (Infantry_Run's daemon half is not loaded)")
            return@runBlocking 1
        }
        (if (result.isError) err else out).appendLine(result.text)
        if (result.isError) 1 else 0
    }
}
