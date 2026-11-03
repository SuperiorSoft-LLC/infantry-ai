package com.superiorsoftllc.infantry.cli.mcp

import com.superiorsoftllc.infantry.cli.BgCli
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import java.io.File

/**
 * STALL-4 (ticket 14ac9383) item 2a: `infantry_watch_file`, the MCP form of `infantry watch-file <path>`: the same detached
 * watcher, started from this MCP server's process for the session in `INFANTRY_SESSION_ID`, so a harness without a shell
 * tool can follow a file too.
 */
internal class McpWatchTools(private val getenv: (String) -> String?, private val cwd: File) {

    fun watchFile(arguments: JsonObject): ToolResult {
        val path = (arguments["path"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
            ?: return ToolResult("$WATCH_FILE: needs path", isError = true)
        val args = buildList {
            add("watch-file"); add(File(path).let { if (it.isAbsolute) it else File(cwd, path) }.path)
            (arguments["untilPid"] as? JsonPrimitive)?.longOrNull?.let { add("--until-pid"); add(it.toString()) }
            (arguments["idleSeconds"] as? JsonPrimitive)?.longOrNull?.let { add("--idle-seconds"); add(it.toString()) }
            (arguments["name"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }?.let { add("--name"); add(it) }
        }
        val out = StringBuilder()
        val err = StringBuilder()
        val code = BgCli.main(args, out, err, getenv) { error("watch-file does not talk to the daemon from the caller") }
        val text = (out.toString() + err.toString()).trim()
        return ToolResult(text.ifEmpty { "$WATCH_FILE exited $code" }, isError = code != 0)
    }

    companion object {
        const val WATCH_FILE = "infantry_watch_file"
        val NAMES = listOf(WATCH_FILE)

        fun descriptors() = buildJsonArray {
            add(buildJsonObject {
                put("name", WATCH_FILE)
                put(
                    "description",
                    "Watch a file another process writes (a build sent to a log with > or tee): you are mailed when it goes " +
                        "quiet, prints a watched pattern (BUILD FAILED, e: ...), runs long, or is done (the process untilPid " +
                        "exited, or the file stopped growing for idleSeconds). Only appended bytes are read, never a copy. " +
                        "Start it, then end your turn; no sleep, no polling.",
                )
                put("inputSchema", buildJsonObject {
                    put("type", "object")
                    put("properties", buildJsonObject {
                        put("path", buildJsonObject { put("type", "string"); put("description", "The file to follow.") })
                        put("untilPid", buildJsonObject { put("type", "integer"); put("description", "Done when this process exits.") })
                        put("idleSeconds", buildJsonObject { put("type", "integer"); put("description", "No pid: done once the file has not grown this long (default limits.watch.fileIdleSeconds).") })
                        put("name", buildJsonObject { put("type", "string"); put("description", "A label for the notices.") })
                    })
                    put("required", buildJsonArray { add(JsonPrimitive("path")) })
                    put("additionalProperties", false)
                })
                put("annotations", buildJsonObject {
                    put("readOnlyHint", true)
                    put("destructiveHint", false)
                    put("idempotentHint", false)
                    put("openWorldHint", false)
                })
            })
        }
    }
}
