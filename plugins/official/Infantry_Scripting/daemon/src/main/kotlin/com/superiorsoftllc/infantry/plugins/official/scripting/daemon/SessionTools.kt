// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.plugins.official.scripting.daemon

import com.superiorsoftllc.infantry.daemon.api.DaemonTool
import com.superiorsoftllc.infantry.daemon.api.QueuedMessage
import com.superiorsoftllc.infantry.daemon.api.SessionInfo
import com.superiorsoftllc.infantry.daemon.api.SessionStatus
import com.superiorsoftllc.infantry.daemon.api.ToolCall
import com.superiorsoftllc.infantry.daemon.api.ToolResult
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/**
 * SCRIPT-SESSION-1: a script's general bridge to a session beside the chat note (SCRIPT-CHAT-1) and the prompt/wake:
 *  - `infantry_session_read` (alias `session_read`): the session's state, current tool, last output time and its last chat lines, capped.
 *  - `infantry_session_act` (alias `session_act`): `interrupt` (stop the turn in flight now), `end_turn` (interrupt only when a turn runs; idle = no-op).
 *  - `infantry_notice_suppress` (alias `notice_suppress`): the daemon's built-in notice of a kind sends nothing for a while.
 * Same project only, rate limited per project. Which of these a project uses (and for what) is its own business.
 */
class SessionTools(
    private val session: (String) -> SessionInfo?,
    private val status: (String) -> SessionStatus?,
    private val lines: (String, Int) -> List<String>,
    private val interrupt: (String) -> Boolean,
    private val suppress: (name: String, sessionId: String?, ms: Long) -> Unit,
    private val clock: () -> Long = System::currentTimeMillis,
    private val maxLines: Int = 200,
    private val maxChars: Int = 300,
    private val perMinute: Int = 60,
    private val noticeNames: () -> Set<String> = { emptySet() },
    /** CLI-PTY-3: `mcp_reconnect` (session id, force) -> what happened; throws IllegalArgumentException when refused. */
    private val reconnectMcp: (String, Boolean) -> String = { _, _ -> throw UnsupportedOperationException("this daemon cannot reconnect MCP servers") },
    /** CHAT-QUEUE-1: the daemon's held-message queue (`queue_list` / `queue_add` / `queue_remove` / `queue_force`); the defaults = none. */
    private val queued: (String) -> List<QueuedMessage> = { emptyList() },
    private val queueAdd: (String, String) -> QueuedMessage? = { _, _ -> throw UnsupportedOperationException("this daemon holds no message queue") },
    private val queueRemove: (String, String) -> QueuedMessage = { _, _ -> throw UnsupportedOperationException("this daemon holds no message queue") },
    private val queueForce: (String, String) -> String = { _, _ -> throw UnsupportedOperationException("this daemon holds no message queue") },
    /** HARNESS-GAP G21: every session id, read only when a sessionId is not a full id (a unique prefix names one session). */
    private val allSessionIds: () -> List<String> = { emptyList() },
) {
    private val recent = java.util.concurrent.ConcurrentHashMap<String, java.util.ArrayDeque<Long>>()

    val read: DaemonTool = object : DaemonTool {
        override val name = READ
        override val annotations: JsonObject = Json.parseToJsonElement("""{"readOnlyHint":true}""").jsonObject
        override val description = "Read a session's state and recent chat. Returns {sessionId, state, lastAction (the current tool or 'replying'), lastOutputAt, lastEventAt, lines[]} " +
            "with the last `lines` (default 30, max $maxLines) of assistant text, '[tool] ...' and '[error] ...' lines, each capped at $maxChars characters. Same project only. " +
            "Script: ih.session_read{sessionId=..., lines=30} then ih.again() to read input.results."
        override val inputSchema: JsonObject = Json.parseToJsonElement(
            """{"type":"object","properties":{"sessionId":{"type":"string","description":"target session (default: the caller's own)"},"lines":{"type":"integer","description":"how many recent lines (default 30)"}}}""",
        ).jsonObject
        override fun call(call: ToolCall): ToolResult = guarded(call, requireText = false) { target, a ->
            val n = (a["lines"] as? JsonPrimitive)?.intOrNull ?: 30
            val st = status(target)
            ToolResult(buildJsonObject {
                put("sessionId", target)
                put("state", st?.state ?: "UNKNOWN")
                st?.lastAction?.let { put("lastAction", it.take(maxChars)) }
                st?.lastOutputAt?.let { put("lastOutputAt", it) }
                st?.lastEventAt?.let { put("lastEventAt", it) }
                session(target)?.let { s -> s.mcp?.let { put("mcp", it) }; s.mcpChangedAt?.let { put("mcpChangedAt", it) } }
                put("lines", buildJsonArray { lines(target, n.coerceIn(1, maxLines)).takeLast(n.coerceIn(1, maxLines)).forEach { add(JsonPrimitive(it.take(maxChars))) } })
            }.toString())
        }
    }

    val act: DaemonTool = object : DaemonTool {
        override val name = ACT
        override val description = "Act on a session's turn. action=interrupt stops the turn in flight now (refused when none runs); action=end_turn does the same only if a turn runs and is a no-op on an idle session; " +
            "action=mcp_reconnect brings the session's MCP servers back (session field mcp, event session.mcp) without losing it: reconnect in the live CLI, else restart it so the next turn resumes the same conversation " +
            "(same session id, model and context); refused mid-turn unless force=true. The daemon never does this on its own. " +
            "Held messages (what is typed while the agent is busy): action=queue_list lists them [{id,text,at}]; queue_add (text) holds one for the next turn (sent at once when idle); " +
            "queue_remove (id) drops one, never delivered; queue_force (id) delivers it now INTO the running turn without stopping it (harnesses with mid-turn input), or as a turn when idle. " +
            "Same project only, rate limited. Posting a note and prompting stay with chat_note (wake=true) . Script: ih.session_act{sessionId=..., action='interrupt'}."
        override val inputSchema: JsonObject = Json.parseToJsonElement(
            """{"type":"object","properties":{"sessionId":{"type":"string","description":"target session (default: the caller's own)"},"action":{"type":"string","description":"interrupt | end_turn | mcp_reconnect | queue_list | queue_add | queue_remove | queue_force"},"force":{"type":"boolean","description":"mcp_reconnect only: stop a turn in flight first"},"id":{"type":"string","description":"queue_remove / queue_force: the held message id"},"text":{"type":"string","description":"queue_add: the message"}},"required":["action"]}""",
        ).jsonObject
        override fun call(call: ToolCall): ToolResult = guarded(call, requireText = false) { target, a ->
            when (val action = (a["action"] as? JsonPrimitive)?.contentOrNull?.trim()?.lowercase()) {
                "interrupt" -> if (interrupt(target)) ToolResult("interrupted $target") else refuse("$target has no turn running")
                "end_turn" -> { val busy = status(target)?.state == "BUSY"; if (busy && interrupt(target)) ToolResult("turn of $target ended") else ToolResult("$target is not in a turn: nothing to end") }
                "mcp_reconnect" -> {
                    val force = (a["force"] as? JsonPrimitive)?.booleanOrNull ?: false
                    try { ToolResult("mcp_reconnect $target: ${reconnectMcp(target, force)}") }
                    catch (e: IllegalArgumentException) { refuse(e.message ?: "refused") }
                    catch (e: UnsupportedOperationException) { refuse(e.message ?: "not supported") }
                }
                "queue_list" -> ToolResult(buildJsonArray {
                    queued(target).forEach { q -> add(buildJsonObject { put("id", q.id); put("text", q.text); put("at", q.at) }) }
                }.toString())
                "queue_add", "queue_remove", "queue_force" -> {
                    val key = if (action == "queue_add") "text" else "id"
                    val arg = (a[key] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() } ?: return@guarded refuse("$action needs $key")
                    try {
                        when (action) {
                            "queue_add" -> ToolResult(queueAdd(target, arg)?.let { "queued ${it.id} on $target" } ?: "$target was at rest: sent as a turn")
                            "queue_remove" -> ToolResult("removed ${queueRemove(target, arg.trim()).id} from $target's queue")
                            else -> ToolResult("queue_force ${arg.trim()} on $target: ${queueForce(target, arg.trim())}")
                        }
                    } catch (e: IllegalArgumentException) { refuse(e.message ?: "refused") }
                    catch (e: IllegalStateException) { refuse(e.message ?: "refused") }
                    catch (e: UnsupportedOperationException) { refuse(e.message ?: "not supported") }
                }
                else -> refuse("action must be interrupt, end_turn, mcp_reconnect, queue_list, queue_add, queue_remove or queue_force (got '${action.orEmpty()}')")
            }
        }
    }

    val suppressNotice: DaemonTool = object : DaemonTool {
        override val name = SUPPRESS
        override val description = "Mark the daemon's built-in notice `name` (a nudge/notice event such as nudge.worker_idle, or *) as suppressed for `seconds` (default 300, max 3600), " +
            "for one session (sessionId) or any. The notice still fires its event, but the built-in sends nothing while the mark lasts: a hook that handles the event replaces it. " +
            "Script: ih.notice_suppress{name='nudge.worker_idle', sessionId=..., seconds=600}."
        override val inputSchema: JsonObject = Json.parseToJsonElement(
            """{"type":"object","properties":{"name":{"type":"string","description":"notice event name, or *"},"sessionId":{"type":"string","description":"only this session (default: any)"},"seconds":{"type":"integer","description":"how long (default 300)"}},"required":["name"]}""",
        ).jsonObject
        override fun call(call: ToolCall): ToolResult {
            val a = call.arguments
            val n = (a["name"] as? JsonPrimitive)?.contentOrNull?.trim().orEmpty()
            val known = noticeNames()
            if (n != "*" && known.isNotEmpty() && n !in known) return refuse("unknown notice '$n' (known: ${known.sorted().joinToString()})")
            if (n.isEmpty()) return refuse("needs name")
            val sid = (a["sessionId"] as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }
            val project = call.clientProjectId ?: call.clientSessionId?.let { session(it)?.projectId } ?: return refuse("needs a project context")
            if (sid != null && session(sid)?.projectId != project) return refuse("session $sid is not in this project")
            if (!allow(project)) return refuse("rate limit: at most $perMinute calls per minute per project")
            val secs = ((a["seconds"] as? JsonPrimitive)?.longOrNull ?: 300L).coerceIn(1L, 3600L)
            suppress(n, sid, secs * 1000)
            return ToolResult("suppressing $n${sid?.let { " for $it" }.orEmpty()} for ${secs}s")
        }
    }

    private fun guarded(call: ToolCall, requireText: Boolean, body: (String, JsonObject) -> ToolResult): ToolResult {
        val a = call.arguments
        val ref = (a["sessionId"] as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() } ?: call.clientSessionId ?: return refuse("needs sessionId")
        val target = when (val r = SessionRef.of(ref, { session(it) != null }, allSessionIds)) {
            is SessionRef.Found -> r.id
            is SessionRef.Refused -> return refuse(r.why)
        }
        val targetProject = session(target)?.projectId ?: return refuse("unknown session $target")
        val callerProject = call.clientProjectId ?: call.clientSessionId?.let { session(it)?.projectId } ?: return refuse("needs a project context")
        if (targetProject != callerProject) return refuse("session $target is in another project")
        if (!allow(callerProject)) return refuse("rate limit: at most $perMinute calls per minute per project")
        return body(target, a)
    }

    private fun allow(project: String): Boolean {
        val now = clock()
        val q = recent.computeIfAbsent(project) { java.util.ArrayDeque() }
        synchronized(q) {
            while (q.isNotEmpty() && now - q.first() > 60_000) q.removeFirst()
            if (q.size >= perMinute) return false
            q.addLast(now)
        }
        return true
    }

    private fun refuse(why: String) = ToolResult("session tools refused: $why", isError = true)

    companion object {
        const val READ = "infantry_session_read"
        const val ACT = "infantry_session_act"
        const val SUPPRESS = "infantry_notice_suppress"
        val ALIASES = mapOf("session_read" to READ, "session_act" to ACT, "notice_suppress" to SUPPRESS)
    }
}
