package com.superiorsoftllc.agenthq.cli.mcp

import com.superiorsoftllc.agenthq.cli.AuthRefused
import com.superiorsoftllc.agenthq.cli.FrameStream
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.contentOrNull

/**
 * The tools `agenthq mcp serve` still serves itself (the write ones in [McpWriteTools], mail, pipeline, watch) plus the daemon
 * plugins' tools ([McpPluginTools]); RUNTIME-LOAD-5 moved the session / board reads to plugins. Each call opens its own [FrameStream] through
 * [connect] and closes it afterwards, so the server starts and answers `initialize` / `tools/list` even
 * while no daemon is running, and a daemon started later is picked up by the next call.
 *
 * Every daemon call goes over frames the CLI already sends: no new frame, no contract bump.
 */
internal class McpTools(
    private val connect: () -> FrameStream,
    guardrails: McpGuardrails = McpGuardrails(),
    private val getenv: (String) -> String? = { null },
    /** P11-C: the `projectId` used by `agenthq_spawn` / `agenthq_pod_state` when a call passes none. */
    private val defaultProjectId: String? = null,
) {

    /** P11-B: spawn / prompt / message / kill, with the server's depth, concurrency and kill-scope guardrails. */
    private val writes = McpWriteTools(guardrails, getenv, defaultProjectId)

    /** MAIL-MCP-1: inbox / read, over the CLI's own `mail_inbox` / `mail_read` frames. */
    private val mail = McpMailTools(getenv)
    private val pipeline = McpPipelineTools(getenv)

    /** ASK-1: `agenthq_ask`, the non-blocking owner question, over the CLI's own `question_ask` frame. */

    /** STALL-4: `agenthq_watch_file`, the detached file watcher of `agenthq watch-file`. */
    private val watch = McpWatchTools(getenv, java.io.File(System.getProperty("user.dir") ?: "."))

    /** ROLE-EDIT-1: the role catalog (`agenthq_role_*`); an agent caller may only edit persona and description. */

    /** DAEMON-PLUGINS-1: the daemon plugins' tools (TICKET-FILES' two tools are Infantry_Board's now). */
    private val plugins = McpPluginTools(getenv, defaultProjectId)
    @Volatile private var pluginNames: Set<String>? = null

    /**
     * RUNTIME-LOAD-5: false while the last plugin listing could not reach the daemon. Most tools are plugin tools now, so a tool
     * this server does not know is only "unknown" when the daemon answered; otherwise the call reports the unreachable daemon.
     */
    @Volatile var daemonAnswered: Boolean = true
        private set

    /** DAEMON-PLUGINS-1: the daemon plugins' tools for `tools/list`; none when the daemon has none or is unreachable. */
    fun pluginDescriptors(): JsonArray {
        val list = runCatching {
            val stream = connect()
            try { runBlocking { plugins.descriptors(stream) } } finally { stream.close() }
        }.onSuccess { daemonAnswered = true }.getOrElse { daemonAnswered = false; JsonArray(emptyList()) }
        pluginNames = list.mapNotNull { ((it as? JsonObject)?.get("name") as? JsonPrimitive)?.contentOrNull }.toSet()
        return list
    }

    /** True when [name] is a daemon plugin's tool (the daemon is asked again when the last listing does not have it). */
    fun isPluginTool(name: String): Boolean = name in pluginNames.orEmpty() || run { pluginDescriptors(); name in pluginNames.orEmpty() }

    fun call(name: String, arguments: JsonObject): ToolResult {
        val stream = try {
            connect()
        } catch (e: AuthRefused) {
            return ToolResult(e.message ?: "the daemon refused this client's token", isError = true)
        } catch (e: Exception) {
            return ToolResult(DAEMON_UNREACHABLE, isError = true)
        }
        return try {
            runBlocking {
                when (name) {
                    McpWriteTools.SPAWN -> writes.spawn(stream, arguments)
                    McpWriteTools.PROMPT -> writes.prompt(stream, arguments)
                    McpWriteTools.MESSAGE -> writes.message(stream, arguments)
                    McpWriteTools.KILL -> writes.kill(stream, arguments)
                    McpWriteTools.RESTART_SESSION -> writes.restart(stream, arguments)
                    McpWriteTools.REMOVE_SESSION -> writes.removeSession(stream, arguments)
                    McpMailTools.INBOX -> mail.inbox(stream, arguments)
                    McpMailTools.READ -> mail.read(stream, arguments)
                    McpPipelineTools.SHOW -> pipeline.show(stream, arguments)
                    McpPipelineTools.RECORD -> pipeline.record(stream, arguments)
                    McpWatchTools.WATCH_FILE -> watch.watchFile(arguments)
                    McpTicketTools.START -> writes.startTicket(stream, arguments, McpTicketTools.START)
                    else -> plugins.call(stream, name, arguments)
                }
            }
        } catch (e: Exception) {
            ToolResult("$name failed: ${e.message ?: e.javaClass.simpleName}", isError = true)
        } finally {
            stream.close()
        }
    }

    companion object {
        // RUNTIME-LOAD-5: agenthq_list_sessions / _get_session / _pod_state are Infantry_Agents' daemon tools, agenthq_board
        // Infantry_Board's (over the host's sessions read model, docs/RUNTIME-LOAD.md section 11).
        val NAMES = McpWriteTools.NAMES + McpMailTools.NAMES + McpTicketTools.NAMES + McpWatchTools.NAMES + McpPipelineTools.NAMES

        /** The one, fixed message a tool answers with when nothing is listening at the daemon address. */
        const val DAEMON_UNREACHABLE =
            "Infantry AI daemon is not reachable. Start it with `agenthq daemon` (or check --url / --port), then retry."

        /** `tools/list`: each tool's name, description and JSON Schema for its arguments. */
        fun descriptors(): JsonArray = buildJsonArray {
            McpWriteTools.descriptors().forEach { add(it) }
            McpTicketTools.descriptors().forEach { add(it) }
            McpMailTools.descriptors().forEach { add(it) }
            McpPipelineTools.descriptors().forEach { add(it) }
            McpWatchTools.descriptors().forEach { add(it) }
        }
    }
}
