// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.protocol.todo

import java.util.concurrent.ConcurrentHashMap
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * claude's incremental task tools (`CLAUDE_CODE_ENABLE_TODO_TOOLS=1`): `TaskCreate {subject, activeForm}` appends an item
 * whose id arrives in the tool_result text ("Task #N created"), `TaskUpdate {taskId, status, subject}` changes or (status
 * `deleted`) removes one. Unlike `TodoWrite` they never send the full list, so each session keeps its own list here and
 * [feed] answers the FULL list after every call, ready for the existing `todos` event.
 */
class ClaudeTaskTools {
    private class Item(var id: String?, var text: String, var status: String, var activeText: String?)

    private class State {
        val items = mutableListOf<Item>()
        val pendingCreates = HashMap<String, Item>() // tool_use_id -> item awaiting its id
    }

    private val sessions = ConcurrentHashMap<String, State>()

    /** Forget a finished session. */
    fun clear(sessionId: String) {
        sessions.remove(sessionId)
    }

    /** One raw claude stream-json line of [sessionId]; the session's full list if the line changed it, else null. */
    fun feed(sessionId: String, rawLine: String): List<TodoItemPayload>? {
        if (!rawLine.contains("Task") && !rawLine.contains("tool_result")) return null
        val obj = runCatching { Json.parseToJsonElement(rawLine) as? JsonObject }.getOrNull() ?: return null
        val blocks = ((obj["message"] as? JsonObject)?.get("content") as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
        val state = sessions.getOrPut(sessionId) { State() }
        synchronized(state) {
            var changed = false
            for (block in blocks) {
                when ((block["type"] as? JsonPrimitive)?.content) {
                    "tool_use" -> {
                        val input = block["input"] as? JsonObject ?: continue
                        when ((block["name"] as? JsonPrimitive)?.content) {
                            "TaskCreate" -> {
                                val subject = input.str("subject", "content", "title") ?: continue
                                val item = Item(null, subject.take(TODO_MAX_TEXT), TodoItemPayload.PENDING, input.str("activeForm")?.take(TODO_MAX_TEXT))
                                if (state.items.size < TODO_MAX_ITEMS) state.items += item
                                (block["id"] as? JsonPrimitive)?.content?.let { state.pendingCreates[it] = item }
                                changed = true
                            }
                            "TaskUpdate" -> {
                                val id = (input["taskId"] as? JsonPrimitive)?.content ?: continue
                                val item = state.items.firstOrNull { it.id == id } ?: continue
                                val raw = (input["status"] as? JsonPrimitive)?.content?.lowercase()
                                if (raw == "deleted") state.items.remove(item)
                                else if (raw != null) item.status = normalizeTodoStatus(raw)
                                input.str("subject")?.let { item.text = it.take(TODO_MAX_TEXT) }
                                input.str("activeForm")?.let { item.activeText = it.take(TODO_MAX_TEXT) }
                                changed = true
                            }
                        }
                    }
                    "tool_result" -> {
                        val useId = (block["tool_use_id"] as? JsonPrimitive)?.content ?: continue
                        val item = state.pendingCreates.remove(useId) ?: continue
                        item.id = CREATED.find(resultText(block))?.groupValues?.get(1)
                    }
                }
            }
            return if (changed) state.items.map { TodoItemPayload(it.text, it.status, it.activeText) } else null
        }
    }

    private fun resultText(block: JsonObject): String = when (val c = block["content"]) {
        is JsonPrimitive -> c.content
        is JsonArray -> c.joinToString("\n") { ((it as? JsonObject)?.get("text") as? JsonPrimitive)?.content.orEmpty() }
        else -> ""
    }

    private companion object {
        val CREATED = Regex("""Task #(\w+) created""")
    }
}
