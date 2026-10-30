package com.superiorsoftllc.agenthq.cli.mcp

import com.superiorsoftllc.agenthq.protocol.EventVocabulary
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import java.io.BufferedReader
import java.io.Flushable

/**
 * `agenthq mcp serve`: Infantry AI as an MCP server over stdio (Phase 11, slice P11-A).
 *
 * TRANSPORT. MCP's stdio transport: one JSON-RPC 2.0 message per line on stdin, one per line on stdout.
 * Stdout carries the protocol and NOTHING else -- a stray log line there is a parse error on the client
 * side, so every diagnostic goes to [err] (stderr).
 *
 * WHY HAND-ROLLED. No MCP Kotlin SDK is on this module's classpath (nor in the version catalog), and the
 * surface this slice needs -- `initialize`, `notifications/initialized`, `ping`, `tools/list`,
 * `tools/call` -- is a few dozen lines of JSON-RPC. kotlinx.serialization, already a dependency, does the
 * JSON. Adding the SDK would pull in a new coordinate (and its Ktor version) for no capability we use.
 *
 * TOOLS live in [McpTools] (read-only, P11-A) and [McpWriteTools] (spawn/prompt/message/kill, P11-B); they reach the daemon through the CLI's own
 * [com.superiorsoftllc.agenthq.cli.FrameStream], over existing frames only (no contract bump).
 *
 * Requests are handled one at a time, in arrival order. A tool call blocks the loop until it answers (a
 * daemon request is bounded by the CLI's own answer timeout), which keeps the write tools' guardrail state free of races.
 */
internal class McpServer(
    private val input: BufferedReader,
    private val out: Appendable,
    private val err: Appendable,
    private val tools: McpTools,
) {

    /** Serves until stdin reaches EOF. Returns the process exit code (always 0: EOF is a clean shutdown). */
    fun serve(): Int {
        err.appendLine("agenthq mcp: serving MCP over stdio (${SERVER_NAME} ${SERVER_VERSION})")
        while (true) {
            val line = input.readLine() ?: break
            if (line.isBlank()) continue
            val response = handleLine(line) ?: continue
            write(response)
        }
        err.appendLine("agenthq mcp: stdin closed, exiting")
        return 0
    }

    /** One line in, at most one response out. Null = a notification, which is never answered. */
    internal fun handleLine(line: String): JsonObject? {
        val message = try {
            json.parseToJsonElement(line)
        } catch (e: Exception) {
            return error(JsonNull, PARSE_ERROR, "Parse error: ${e.message?.lineSequence()?.firstOrNull() ?: "invalid JSON"}")
        }
        if (message !is JsonObject) {
            // A batch (JSON array) was dropped from MCP in 2025-06-18; anything that is not one object is refused.
            return error(JsonNull, INVALID_REQUEST, "Invalid Request: expected a single JSON-RPC object")
        }
        val id = message["id"]
        val method = (message["method"] as? JsonPrimitive)?.takeIf { it.isString }?.content
        if (method == null) {
            // A response to something we never sent, or garbage: answer only when it carried an id.
            return if (id == null) null else error(id, INVALID_REQUEST, "Invalid Request: missing method")
        }
        val params = message["params"] as? JsonObject ?: JsonObject(emptyMap())

        // A notification (no id) is never answered -- including one we do not understand.
        if (id == null) {
            if (method != "notifications/initialized") err.appendLine("agenthq mcp: ignoring notification '$method'")
            return null
        }

        return when (method) {
            "initialize" -> result(id, initializeResult(params))
            "ping" -> result(id, JsonObject(emptyMap()))
            "tools/list" -> result(id, buildJsonObject { put("tools", JsonArray(McpTools.descriptors() + tools.pluginDescriptors())) })
            "tools/call" -> {
                val name = (params["name"] as? JsonPrimitive)?.contentOrNull
                    ?: return error(id, INVALID_PARAMS, "Invalid params: tools/call requires a tool name")
                if (name !in McpTools.NAMES && !tools.isPluginTool(name)) return error(id, INVALID_PARAMS, "Unknown tool: $name")
                val arguments = params["arguments"] as? JsonObject ?: JsonObject(emptyMap())
                result(id, tools.call(name, arguments).toJson())
            }
            else -> error(id, METHOD_NOT_FOUND, "Method not found: $method")
        }
    }

    private fun initializeResult(params: JsonObject): JsonObject {
        val requested = (params["protocolVersion"] as? JsonPrimitive)?.contentOrNull
        val version = requested?.takeIf { it in SUPPORTED_PROTOCOL_VERSIONS } ?: SUPPORTED_PROTOCOL_VERSIONS.first()
        return buildJsonObject {
            put("protocolVersion", version)
            put("capabilities", buildJsonObject {
                put("tools", buildJsonObject { put("listChanged", false) })
            })
            put("serverInfo", buildJsonObject {
                put("name", SERVER_NAME)
                put("version", SERVER_VERSION)
            })
            put(
                "instructions",
                "Infantry AI daemon: list sessions, inspect one session's status and transcript, summarise the " +
                    "agents/roles of the pod; spawn, prompt and kill sessions and send COMMS mail, within this " +
                    "server's depth, concurrency and kill-scope guardrails; create, update and read board tickets. " +
                    "To put an agent to work on a ticket, pin its role / provider / model (agenthq_list_harnesses " +
                    "lists the harnesses and their models), then start it with agenthq_ticket_start: the worker " +
                    "runs on the ticket's own branch and the ticket goes In Progress. agenthq_spawn alone works " +
                    "outside the board.",
            )
        }
    }

    private fun write(message: JsonObject) {
        // One line per message: kotlinx's compact encoding never emits a raw newline (strings escape it).
        out.append(json.encodeToString(JsonObject.serializer(), message)).append('\n')
        (out as? Flushable)?.flush()
    }

    companion object {
        const val SERVER_NAME = "agenthq"

        /** The daemon contract this server speaks; Infantry AI has no separate product version string. */
        val SERVER_VERSION: String = EventVocabulary.VERSION

        /** Newest first: an unknown requested version is answered with the newest we speak. */
        val SUPPORTED_PROTOCOL_VERSIONS = listOf("2025-06-18", "2025-03-26", "2024-11-05")

        const val PARSE_ERROR = -32700
        const val INVALID_REQUEST = -32600
        const val METHOD_NOT_FOUND = -32601
        const val INVALID_PARAMS = -32602

        private val json = Json { encodeDefaults = true; explicitNulls = false }

        private fun result(id: JsonElement, result: JsonObject) = buildJsonObject {
            put("jsonrpc", "2.0")
            put("id", id)
            put("result", result)
        }

        private fun error(id: JsonElement, code: Int, message: String) = buildJsonObject {
            put("jsonrpc", "2.0")
            put("id", id)
            put("error", buildJsonObject {
                put("code", code)
                put("message", message)
            })
        }
    }
}

/** An MCP `tools/call` result: one text content block holding JSON (or a message, when [isError]). */
internal data class ToolResult(val text: String, val isError: Boolean = false) {
    fun toJson(): JsonObject = buildJsonObject {
        put("content", buildJsonArray {
            add(buildJsonObject {
                put("type", "text")
                put("text", text)
            })
        })
        put("isError", isError)
    }
}
