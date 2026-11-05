// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.plugins.official.claude.harness

import com.superiorsoftllc.infantry.harness.api.ApprovalReply
import com.superiorsoftllc.infantry.harness.api.ToolApproval
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap

/**
 * HARNESS-GAP G3 (ticket 11754e3b): Claude Code's `PermissionRequest` hook, which fires instead of the CLI's own permission dialog, answered from
 * the session's Tool approval so an interactive turn never waits on a dialog nobody sees:
 *
 * - **DENY**: `--permission-mode dontAsk` ([ClaudeCommand.permissionMode]) already refuses every call that is not pre-allowed; the hook is a
 *   second line that also denies.
 * - **ALLOW_EDITS**: `acceptEdits` allows file edits; any other call that would ask is denied here.
 * - **ASK**: the call goes to the owner through [com.superiorsoftllc.infantry.harness.api.SessionOutput.requestApproval] (blocking, the host's
 *   own prompt timer ends it), as opencode does; any allow answer allows this call.
 * - **ALLOW_ALL**: `bypassPermissions`, no hook.
 *
 * The hook is an `http` hook to [PermissionHookServer], a loopback endpoint inside this plugin (no daemon route, no JDK http server module,
 * which the trimmed headless runtime leaves out). Each launch gets an unguessable token in its URL; a request with an unknown token, a body
 * that cannot be read or a session not connected yet is denied, never allowed.
 */
object ClaudePermissionHook {
    const val EVENT = "PermissionRequest"
    const val PATH = "/permission/"

    /** Module setting: seconds claude waits for the hook's answer (an ASK waits for the owner). Past it claude shows its dialog; the screen fallback answers No. */
    const val TIMEOUT_SETTING = "harness.claude.approvalHookSeconds"
    const val DEFAULT_TIMEOUT_SEC = 1800

    fun timeoutSec(raw: String?): Int = raw?.trim()?.toIntOrNull()?.takeIf { it > 0 } ?: DEFAULT_TIMEOUT_SEC

    /** Whether a session in [approval] gets the hook: every mode in which claude could ask. */
    fun wanted(approval: ToolApproval): Boolean = approval != ToolApproval.ALLOW_ALL

    /** The settings entry of the hook (every tool: no matcher). */
    fun entry(url: String, timeoutSec: Int): JsonObject = JsonObject(
        mapOf(
            "hooks" to JsonArray(listOf(JsonObject(mapOf(
                "type" to JsonPrimitive("http"),
                "url" to JsonPrimitive(url),
                "timeout" to JsonPrimitive(timeoutSec),
            )))),
        ),
    )

    /** One hook call's tool and its input as the owner reads it (a shell call: its command). Null when the body is not a hook call. */
    fun parse(body: String): Pair<String, String>? {
        val root = runCatching { TtyJson.json.parseToJsonElement(body) as? JsonObject }.getOrNull() ?: return null
        val tool = (root["tool_name"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() } ?: return null
        val input = root["tool_input"]
        val text = (input as? JsonObject)?.get("command")?.let { runCatching { it.jsonPrimitive.contentOrNull }.getOrNull() } ?: input?.toString().orEmpty()
        return tool to text
    }

    fun denyMessage(approval: ToolApproval, tool: String): String = when (approval) {
        ToolApproval.ASK -> "The owner denied $tool. Do not retry it; carry on without it."
        else -> "Tool approval is ${approval.name.lowercase()}: $tool is not allowed in this session. Do not retry it; carry on without it or ask the owner."
    }

    /** The hook's answer. */
    fun reply(allow: Boolean, message: String? = null): String {
        val decision = LinkedHashMap<String, JsonPrimitive>()
        decision["behavior"] = JsonPrimitive(if (allow) "allow" else "deny")
        if (!allow && message != null) decision["message"] = JsonPrimitive(message)
        return JsonObject(mapOf("hookSpecificOutput" to JsonObject(mapOf("hookEventName" to JsonPrimitive(EVENT), "decision" to JsonObject(decision))))).toString()
    }

    /**
     * The answer for one call of a session in [approval]; [ask] relays to the owner (ASK only). [onDenied] hears every deny with its tool.
     */
    fun decide(approval: ToolApproval, body: String, ask: (String, String) -> ApprovalReply, onDenied: (String) -> Unit = {}): String {
        val (tool, input) = parse(body) ?: return reply(false, "unreadable permission request")
        val allow = approval == ToolApproval.ASK && ask(tool, input) != ApprovalReply.DENY
        if (!allow) onDenied(tool)
        return if (allow) reply(true) else reply(false, denyMessage(approval, tool))
    }
}

/** The loopback endpoint of [ClaudePermissionHook]: started on first use, one thread per request, `POST /permission/<token>`. */
class PermissionHookServer(private val bind: () -> ServerSocket = { ServerSocket(0, BACKLOG, InetAddress.getLoopbackAddress()) }) {
    private val handlers = ConcurrentHashMap<String, (String) -> String>()
    private val lock = Any()
    @Volatile private var socket: ServerSocket? = null

    /** The hook URL for [token]; starts the endpoint if it is not running. */
    fun url(token: String): String = "http://127.0.0.1:${port()}${ClaudePermissionHook.PATH}$token"

    fun register(token: String, handler: (String) -> String) { handlers[token] = handler }

    fun unregister(token: String) { handlers.remove(token) }

    private fun port(): Int = synchronized(lock) {
        socket?.takeIf { !it.isClosed }?.let { return it.localPort }
        val s = bind()
        socket = s
        Thread({ accept(s) }, "infantry-claude-permission").apply { isDaemon = true; start() }
        s.localPort
    }

    private fun accept(s: ServerSocket) {
        while (!s.isClosed) {
            val client = runCatching { s.accept() }.getOrNull() ?: continue
            Thread({ client.use { serve(it) } }, "infantry-claude-permission-call").apply { isDaemon = true; start() }
        }
    }

    private fun serve(client: Socket) {
        val input = client.getInputStream()
        val requestLine = readLine(input) ?: return
        var length = 0
        while (true) {
            val header = readLine(input) ?: return
            if (header.isEmpty()) break
            val colon = header.indexOf(':')
            if (colon > 0 && header.substring(0, colon).trim().equals("content-length", ignoreCase = true)) length = header.substring(colon + 1).trim().toIntOrNull() ?: 0
        }
        val body = if (length in 1..MAX_BODY_BYTES) String(input.readNBytes(length), Charsets.UTF_8) else ""
        val path = requestLine.split(' ').getOrNull(1).orEmpty()
        val token = path.substringAfter(ClaudePermissionHook.PATH, "").substringBefore('?')
        val handler = handlers[token].takeIf { requestLine.startsWith("POST ") && token.isNotEmpty() }
        val answer = handler?.let { runCatching { it(body) }.getOrNull() } ?: ClaudePermissionHook.reply(false, "no infantry session answers this permission request")
        val bytes = answer.toByteArray(Charsets.UTF_8)
        val out = client.getOutputStream()
        out.write("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n".toByteArray(Charsets.US_ASCII))
        out.write(bytes)
        out.flush()
    }

    private fun readLine(input: InputStream): String? {
        val buf = ByteArrayOutputStream()
        while (true) {
            val b = input.read()
            if (b < 0) return if (buf.size() == 0) null else buf.toString(Charsets.UTF_8)
            if (b == '\n'.code) return buf.toString(Charsets.UTF_8).trimEnd('\r')
            if (buf.size() >= MAX_LINE_BYTES) return null
            buf.write(b)
        }
    }

    companion object {
        const val BACKLOG = 50
        /** A request line or header longer than this is refused (the call is then denied by claude's hook failure fallback). */
        const val MAX_LINE_BYTES = 16 * 1024
        /** A hook body is one tool call's input; a larger one is read as empty and denied. */
        const val MAX_BODY_BYTES = 4 * 1024 * 1024
    }
}
