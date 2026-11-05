// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.plugins.official.scripting.daemon

import com.superiorsoftllc.infantry.daemon.api.DaemonTool
import com.superiorsoftllc.infantry.daemon.api.ToolCall
import com.superiorsoftllc.infantry.daemon.api.ToolResult
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

/**
 * SCRIPT-CHAT-1: `infantry_chat_note` (script alias `chat_note`): a script posts one status card into a session's chat. It is an automation
 * timeline item (same look as the run lines), pushed through [push] and kept in [store] so a reload shows it again. wake=false (default) means
 * no turn, no inbox entry, no mail; wake=true also hands [wakeSession] the text as a short prompt. Same project only, rate limited, text capped.
 */
class ChatNoteTools(
    private val projectOf: (String) -> String?,
    private val push: (JsonObject) -> Unit,
    private val store: (JsonObject) -> Unit,
    private val wakeSession: (String, String) -> Unit,
    /** CHAT-IMAGE-1: a call carrying `image` is handed to infantry_chat_image, which validates and stores it and posts back here with `storedImage`. */
    private val forwardImage: ((JsonObject, com.superiorsoftllc.infantry.daemon.api.ToolCall) -> ToolResult)? = null,
    /** NOTE-WAKE-PTY-1: holds a wake for a busy session; the daemon sends it as the session's next turn when the running one ends. */
    private val queueWake: ((String, String) -> Unit)? = null,
    private val clock: () -> Long = System::currentTimeMillis,
    private val maxChars: Int = 400,
    private val perMinute: Int = 20,
) {
    private val seq = java.util.concurrent.atomic.AtomicLong()
    private val recent = java.util.concurrent.ConcurrentHashMap<String, java.util.ArrayDeque<Long>>()

    val tool: DaemonTool = object : DaemonTool {
        override val name = NAME
        override val description = "Post a status line into a session's chat as an automation card (cog icon, time, coloured by level), stored in its history. " +
            "No turn, no inbox entry, no mail unless wake=true (then the agent also gets the text as a short prompt, as its next turn when it is busy: use it for real exceptions only). Same project only, " +
            "rate limited, text capped at $maxChars characters. Script: ih.chat_note{sessionId=..., text=..., level='ok'}."
        override val inputSchema: JsonObject = Json.parseToJsonElement(
            """{"type":"object","properties":{"sessionId":{"type":"string","description":"target session (default: the caller's own)"},"text":{"type":"string","description":"the status line"},"level":{"type":"string","description":"info (default) | ok | warn (yellow WARNING, not an error) | fail"},"wake":{"type":"boolean","description":"also prompt the agent with the text (default false)"},"image":{"type":"string","description":"a file inside the project (or uploaded image id) shown inline with the line"}},"required":["text"]}""",
        ).jsonObject
        override fun call(call: ToolCall): ToolResult = handle(call)
    }

    fun handle(call: ToolCall): ToolResult {
        val a = call.arguments
        fun s(k: String) = (a[k] as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }
        val text = s("text") ?: return refuse("needs text")
        s("image")?.let { img -> return (forwardImage ?: return refuse("images are not available here")).invoke(buildJsonObject { a.forEach { (k, v) -> if (k != "image") put(k, v) }; put("path", img); put("caption", text) }, call) }
        val level = (s("level") ?: "info").lowercase().also { if (it !in LEVELS) return refuse("level must be info, ok, warn or fail (got '$it')") }
        val target = s("sessionId") ?: call.clientSessionId ?: return refuse("needs sessionId")
        val targetProject = projectOf(target) ?: return refuse("unknown session $target")
        val callerProject = call.clientProjectId ?: call.clientSessionId?.let(projectOf) ?: return refuse("needs a project context")
        if (targetProject != callerProject) return refuse("session $target is in another project")
        val now = clock()
        val q = recent.computeIfAbsent(callerProject) { java.util.ArrayDeque() }
        synchronized(q) {
            while (q.isNotEmpty() && now - q.first() > 60_000) q.removeFirst()
            if (q.size >= perMinute) return refuse("rate limit: at most $perMinute notes per minute per project")
            q.addLast(now)
        }
        val shown = text.take(maxChars)
        val item = buildJsonObject {
            put("id", "note-$callerProject-$now-${seq.incrementAndGet()}"); put("sessionId", target); put("at", now); put("source", "automation")
            put("label", "AUTOMATION · note"); // CHAT-NOTE-WARN-1: warn is a warning, not a failure: ok stays true (an older client draws it as info), the label says WARNING.
            put("text", "AUTOMATION · $shown${SUFFIX[level].orEmpty()}"); put("ok", level != "fail")
            put("meta", buildJsonObject {
                put("level", level)
                // CHAT-IMAGE-1: set by infantry_chat_image after it validated and stored the file; only a stored chat upload path is accepted.
                s("storedImage")?.takeIf { it.replace("\\", "/").contains("/.infantry/uploads/chat/") && !it.contains("..") }?.let { put("image", it) }
                s("imageData")?.takeIf { it.length <= 2_100_000 }?.let { put("imageData", it) }
            })
        }
        runCatching { store(item) }
        runCatching { push(item) }
        val wake = (a["wake"] as? JsonPrimitive)?.booleanOrNull == true
        if (wake) runCatching { wakeSession(target, shown) }.onFailure {
            // CHAT-NOTE-WAKE-1: a busy session is not an error. NOTE-WAKE-PTY-1: the wake is held in the session's turn queue and sent as
            // its next turn when the running one ends (the chat card alone never reaches the agent, so it used to be lost).
            if (it.javaClass.simpleName == "RunInFlightException" || it.message?.contains("already has a run in flight") == true) {
                val queue = queueWake ?: return ToolResult("noted in chat, but $target is busy and this daemon holds no turn queue: the wake was not delivered", isError = true)
                return runCatching { queue(target, shown) }.fold(
                    { ToolResult("noted in $target ($level, session busy: the wake is queued and runs as its next turn)") },
                    { e -> ToolResult("noted in chat, but $target is busy and the wake could not be queued: ${e.message}", isError = true) },
                )
            }
            return ToolResult("noted in chat, but the wake failed: ${it.message}", isError = true)
        }
        return ToolResult("noted in $target ($level${if (wake) ", woken" else ""})")
    }

    private fun refuse(why: String) = ToolResult("$NAME refused: $why", isError = true)

    companion object { const val NAME = "infantry_chat_note"; val LEVELS = setOf("info", "ok", "warn", "fail")
        private val SUFFIX = mapOf("warn" to " · WARNING", "fail" to " · FAILED") }
}
