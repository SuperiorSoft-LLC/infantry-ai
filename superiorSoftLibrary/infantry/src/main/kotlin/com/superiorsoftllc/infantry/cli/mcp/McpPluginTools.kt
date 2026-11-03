package com.superiorsoftllc.infantry.cli.mcp

import com.superiorsoftllc.infantry.cli.FrameStream
import com.superiorsoftllc.infantry.daemon.AccessPolicy
import com.superiorsoftllc.infantry.daemon.api.LocalFiles
import com.superiorsoftllc.infantry.protocol.Envelope
import com.superiorsoftllc.infantry.settings.LimitRegistry
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull

/**
 * DAEMON-PLUGINS-1 (docs/DAEMON-PLUGINS.md section 3): the daemon plugins' tools, served by this MCP server next to its
 * own. `tools/list` appends the daemon's `plugin_tools` (none when the daemon has none, or is unreachable); `tools/call`
 * of such a name is one `plugin_tool_call`, answered by `plugin_tool_result {text, isError}`. The daemon gates the call by
 * the tool's own policy action and this connection's token, so this side holds no rule of its own.
 */
internal class McpPluginTools(
    private val getenv: (String) -> String? = System::getenv,
    /** RUNTIME-LOAD-4: this server's `--project` / `.infantry/mcp.json` project, sent as the call's default project. */
    private val defaultProjectId: String? = null,
    /** Where a relative local-file argument is read from (this process's directory). */
    private val cwd: java.io.File = java.io.File(System.getProperty("user.dir") ?: "."),
    /** RUNTIME-LOAD-7: this server's `--max-depth` / `--max-spawns` / `--mcp-depth` and its process key, sent in the call's client object. */
    private val narrowing: McpNarrowing = McpNarrowing(),
) {

    /** Each listed tool's effective timeout (the daemon's `timeoutMs`), so a call waits as long as its own tool may run. */
    private val timeouts = java.util.concurrent.ConcurrentHashMap<String, Long>()

    /** RUNTIME-LOAD-4: each listed tool's local-file argument keys (the daemon's `localFiles`), whose files this side reads and sends. */
    private val localFiles = java.util.concurrent.ConcurrentHashMap<String, List<String>>()

    /** Tools whose schema has a `verbose` property (`detail: full` sets it). */
    private val verboseTools = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    /** Tools whose own schema declares `detail` (their own levels): the argument reaches the tool instead of being read here. */
    private val ownDetailTools = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    /** The daemon's plugin tools, as MCP descriptors (the daemon's bookkeeping keys stripped). */
    suspend fun descriptors(stream: FrameStream): JsonArray {
        val answer = stream.request(Envelope(PLUGIN_TOOLS), PLUGIN_TOOLS, PLUGIN_TOOLS, idempotent = true)
        val all = json.parseToJsonElement(answer.data) as? JsonArray ?: return JsonArray(emptyList())
        timeouts.clear()
        localFiles.clear()
        return JsonArray(all.mapNotNull { (it as? JsonObject)?.let { d ->
            val name = (d["name"] as? JsonPrimitive)?.contentOrNull
            val ms = (d["timeoutMs"] as? JsonPrimitive)?.longOrNull
            if (name != null && ms != null) timeouts[name] = ms
            val files = (d[LocalFiles.DESCRIPTOR_KEY] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
            if (name != null && !files.isNullOrEmpty()) localFiles[name] = files
            if (name != null && ((d["inputSchema"] as? JsonObject)?.get("properties") as? JsonObject)?.containsKey("verbose") == true) verboseTools.add(name)
            if (name != null && ((d["inputSchema"] as? JsonObject)?.get("properties") as? JsonObject)?.containsKey("detail") == true) ownDetailTools.add(name)
            withDetail(JsonObject(d - setOf("module", "action", "timeoutMs", LocalFiles.DESCRIPTOR_KEY)))
        } })
    }

    /** TOKEN-1: every tool takes `detail` (ack | summary | full) and `maxChars`; this side reads them, the tool never sees them. */
    private fun withDetail(d: JsonObject): JsonObject {
        val schema = d["inputSchema"] as? JsonObject ?: return d
        val props = schema["properties"] as? JsonObject ?: return d
        val extra = JsonObject(mapOf(
            "detail" to (props["detail"] ?: JsonObject(mapOf("type" to JsonPrimitive("string"), "description" to JsonPrimitive("How much to send back: ack (tiny), summary (compact), full. Default: the tool's own, or setting limits.mcp.defaultDetail.")))),
            "maxChars" to JsonObject(mapOf("type" to JsonPrimitive("integer"), "description" to JsonPrimitive("Cut this call's result at this many characters (default: setting limits.mcp.maxResultChars)."))),
        ))
        return JsonObject(d + ("inputSchema" to JsonObject(schema + ("properties" to JsonObject(props + extra)))))
    }

    suspend fun call(stream: FrameStream, name: String, arguments: JsonObject): ToolResult {
        val detail = (arguments["detail"] as? JsonPrimitive)?.contentOrNull?.trim()?.lowercase()
            ?: when (LimitRegistry.fromEnv(LimitRegistry.MCP_DEFAULT_DETAIL, System::getenv)) { 1L -> "ack"; 2L -> "summary"; 3L -> "full"; else -> null }
        val maxChars = (arguments["maxChars"] as? JsonPrimitive)?.longOrNull
        val bodyChars = LimitRegistry.fromEnv(LimitRegistry.MCP_BODY_CHARS, System::getenv)
        val withBody = if (name == "infantry_read" && "bodyChars" !in arguments && bodyChars > 0) arguments + ("bodyChars" to JsonPrimitive(bodyChars)) else arguments
        val bare = JsonObject(withBody - (if (name in ownDetailTools) setOf("maxChars") else setOf("detail", "maxChars")) + (if (detail == "full" && "verbose" !in arguments && name in verboseTools) mapOf("verbose" to JsonPrimitive(true)) else emptyMap()))
        val result = callTool(stream, name, bare)
        return if (maxChars != null) ToolResult(result.capped(maxChars), result.isError) else result
    }

    private suspend fun callTool(stream: FrameStream, name: String, arguments: JsonObject): ToolResult {
        // RUNTIME-LOAD-3: who runs this MCP process (an agent session or a person), for the tools whose rule depends on it.
        val client = JsonObject(listOfNotNull(
            getenv(AccessPolicy.SESSION_ID_ENV)?.trim()?.takeIf { it.isNotEmpty() }?.let { "sessionId" to JsonPrimitive(it) },
            getenv("INFANTRY_ROLE")?.trim()?.takeIf { it.isNotEmpty() }?.let { "role" to JsonPrimitive(it) },
            // RUNTIME-LOAD-4: the project a call that names none works in, as the CLI's own ticket tools defaulted it.
            (defaultProjectId?.trim()?.takeIf { it.isNotEmpty() } ?: getenv(AccessPolicy.PROJECT_ID_ENV)?.trim()?.takeIf { it.isNotEmpty() })
                ?.let { "projectId" to JsonPrimitive(it) },
        ).toMap() + narrowing.toClient())
        if (!timeouts.containsKey(name)) runCatching { descriptors(stream) }
        // RUNTIME-LOAD-4: the tool runs in the daemon, which cannot open this machine's paths: the declared ones travel as bytes.
        val sent = LocalFiles.encodeArguments(arguments, localFiles[name].orEmpty()) { path -> java.io.File(path.trim()).let { if (it.isAbsolute) it else java.io.File(cwd, path.trim()) } }
        val payload = JsonObject(mapOf("name" to JsonPrimitive(name), "arguments" to sent, "client" to client))
        val answer = stream.request(
            Envelope(PLUGIN_TOOL_CALL, json.encodeToString(JsonObject.serializer(), payload)), PLUGIN_TOOL_RESULT, PLUGIN_TOOL_CALL,
            // A tool may change state; never sent twice.
            idempotent = false, timeoutMs = answerWaitMs(timeouts[name]),
        )
        val result = json.parseToJsonElement(answer.data) as? JsonObject ?: return ToolResult("malformed plugin_tool_result", isError = true)
        return ToolResult(
            (result["text"] as? JsonPrimitive)?.contentOrNull.orEmpty(),
            isError = (result["isError"] as? JsonPrimitive)?.booleanOrNull ?: false,
        )
    }

    companion object {
        const val PLUGIN_TOOLS = "plugin_tools"
        const val PLUGIN_TOOL_CALL = "plugin_tool_call"
        const val PLUGIN_TOOL_RESULT = "plugin_tool_result"
        /** The daemon disables a tool past its own timeout (default limits.plugins.toolTimeoutMs); this waits a quarter longer. */
        internal fun answerWaitMs(toolTimeoutMs: Long?): Long =
            (toolTimeoutMs ?: LimitRegistry.fromEnv(LimitRegistry.PLUGIN_TOOL_TIMEOUT_MS, System::getenv)).let { it + it / 4 }
        private val json = Json { ignoreUnknownKeys = true }
    }
}
