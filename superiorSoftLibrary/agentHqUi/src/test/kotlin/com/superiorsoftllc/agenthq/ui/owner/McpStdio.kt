package com.superiorsoftllc.agenthq.ui.owner

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import java.io.File
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * E2E-OWNER-1: an MCP client for `agenthq mcp serve`, talking to it exactly as a harness does: one JSON-RPC message per
 * line on the child's stdin, one per line back on its stdout. The child's stderr goes to [errLog], never to an unread
 * pipe (a full pipe would stall the server).
 *
 * Shared by the test (the PM's side) and [RecordingHarness] (the worker's side), so both ends use one client.
 */
class McpStdio private constructor(
    private val process: Process,
    val name: String,
    private val errLog: File,
) : AutoCloseable {
    private val writer = process.outputStream.bufferedWriter(Charsets.UTF_8)
    private val lines = LinkedBlockingQueue<String>()
    private var nextId = 1

    init {
        thread(isDaemon = true, name = "mcp-stdout-$name") {
            runCatching { process.inputStream.bufferedReader(Charsets.UTF_8).forEachLine { lines.put(it) } }
        }
    }

    /** One `tools/call` answer: the text the tool returned and whether the server flagged it as an error. */
    data class ToolAnswer(val tool: String, val text: String, val isError: Boolean) {
        /** The answer as a JSON object (every AgentHQ tool answers one on success). */
        fun json(): JsonObject = runCatching { Json.parseToJsonElement(text) as JsonObject }.getOrElse {
            throw AssertionError("$tool did not answer a JSON object (isError=$isError): $text")
        }

        /** A string field of [json], null when absent or JSON null. */
        fun string(key: String): String? = (json()[key] as? JsonPrimitive)?.contentOrNull
    }

    /** The tool names the server lists (`tools/list`). */
    fun toolNames(): List<String> {
        val tools = request("tools/list", JsonObject(emptyMap()))["tools"] as? JsonArray
            ?: throw AssertionError("MCP $name: tools/list answered no tools array")
        return tools.mapNotNull { ((it as? JsonObject)?.get("name") as? JsonPrimitive)?.contentOrNull }
    }

    /** `tools/call` [tool] with [arguments]. */
    fun call(tool: String, arguments: JsonObject, timeoutMs: Long = CALL_TIMEOUT_MS): ToolAnswer {
        val result = request("tools/call", buildJsonObject {
            put("name", tool)
            put("arguments", arguments)
        }, timeoutMs)
        val text = (result["content"] as? JsonArray).orEmpty()
            .mapNotNull { ((it as? JsonObject)?.get("text") as? JsonPrimitive)?.contentOrNull }
            .joinToString("\n")
        val isError = (result["isError"] as? JsonPrimitive)?.booleanOrNull ?: false
        return ToolAnswer(tool, text, isError)
    }

    private fun initialize() {
        request("initialize", buildJsonObject {
            put("protocolVersion", PROTOCOL_VERSION)
            put("capabilities", JsonObject(emptyMap()))
            put("clientInfo", buildJsonObject {
                put("name", "owner-smoke-$name")
                put("version", "1")
            })
        }, START_TIMEOUT_MS)
        send(buildJsonObject {
            put("jsonrpc", "2.0")
            put("method", "notifications/initialized")
        })
    }

    private fun request(method: String, params: JsonObject, timeoutMs: Long = CALL_TIMEOUT_MS): JsonObject {
        val id = nextId++
        send(buildJsonObject {
            put("jsonrpc", "2.0")
            put("id", id)
            put("method", method)
            put("params", params)
        })
        val deadline = System.currentTimeMillis() + timeoutMs
        while (true) {
            val left = deadline - System.currentTimeMillis()
            if (left <= 0) {
                throw AssertionError(
                    "MCP $name: no answer to $method within ${timeoutMs}ms (alive=${process.isAlive}); stderr: ${stderrTail()}",
                )
            }
            val line = lines.poll(minOf(left, POLL_MS), TimeUnit.MILLISECONDS)
            if (line == null) {
                if (!process.isAlive && lines.isEmpty()) {
                    throw AssertionError("MCP $name exited (${process.exitValue()}) before answering $method; stderr: ${stderrTail()}")
                }
                continue
            }
            val message = runCatching { Json.parseToJsonElement(line) as? JsonObject }.getOrNull() ?: continue
            if ((message["id"] as? JsonPrimitive)?.contentOrNull != id.toString()) continue
            (message["error"] as? JsonObject)?.let { throw AssertionError("MCP $name: $method failed: $it") }
            return message["result"] as? JsonObject ?: throw AssertionError("MCP $name: $method answered no result: $line")
        }
    }

    private fun send(message: JsonElement) {
        writer.write(message.toString())
        writer.write("\n")
        writer.flush()
    }

    /** The last lines the server wrote to stderr, for a failure message. */
    fun stderrTail(lines: Int = 15): String =
        runCatching { errLog.readLines().takeLast(lines).joinToString(" | ") }.getOrDefault("(no stderr log)")

    /** Closes stdin (the server's clean shutdown), then makes sure nothing it started outlives it. */
    override fun close() {
        runCatching { writer.close() }
        if (!process.waitFor(STOP_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
            process.descendants().forEach { it.destroyForcibly() }
            process.destroyForcibly()
            process.waitFor(STOP_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        }
    }

    companion object {
        const val PROTOCOL_VERSION = "2024-11-05"
        private const val START_TIMEOUT_MS = 90_000L
        private const val CALL_TIMEOUT_MS = 90_000L
        private const val STOP_TIMEOUT_MS = 5_000L
        private const val POLL_MS = 200L

        /**
         * Starts [argv] in [cwd] with its environment prepared by [env] (the inherited map, edited in place), and
         * completes the MCP handshake. A child that cannot handshake is killed and reported.
         */
        fun start(argv: List<String>, cwd: File, name: String, errLog: File, env: (MutableMap<String, String>) -> Unit): McpStdio {
            errLog.parentFile?.mkdirs()
            val builder = ProcessBuilder(argv).directory(cwd).redirectError(errLog)
            env(builder.environment())
            val client = McpStdio(builder.start(), name, errLog)
            try {
                client.initialize()
            } catch (e: Throwable) {
                client.close()
                throw e
            }
            return client
        }
    }
}
