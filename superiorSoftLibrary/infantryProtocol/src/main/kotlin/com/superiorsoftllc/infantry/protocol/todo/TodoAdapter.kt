package com.superiorsoftllc.infantry.protocol.todo

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Contract 1.127.0 (TODO-OVERLAY-1): one item of an agent's own to-do list. [status] is `pending`, `in_progress` or
 * `done`; [activeText] is the present-tense form some harnesses carry (claude's `activeForm`), or null.
 * Only these fields leave the daemon: no other tool argument is kept.
 */
@Serializable
data class TodoItemPayload(val text: String, val status: String, val activeText: String? = null) {
    companion object {
        const val PENDING = "pending"
        const val IN_PROGRESS = "in_progress"
        const val DONE = "done"
    }
}

/**
 * Turns one harness's to-do tool call into the neutral list. One implementation per harness shape. These tools send the
 * FULL list on every call, so a non-null answer REPLACES the session's list; null means "not a to-do call".
 */
interface TodoAdapter {
    fun parse(toolName: String, input: JsonObject): List<TodoItemPayload>?
}

/** The registered adapters, tried in order; the first that recognises the tool wins. */
object TodoAdapters {
    private val all: List<TodoAdapter> = listOf(ClaudeTodoWrite, OpenCodeTodoWrite)

    fun parse(toolName: String, input: JsonObject?): List<TodoItemPayload>? {
        if (input == null) return null
        for (a in all) a.parse(toolName, input)?.let { return it }
        return null
    }
}

const val TODO_MAX_ITEMS = 50
const val TODO_MAX_TEXT = 200

/** A `todos` value is an array, or (a captured haiku quirk) the same array encoded as a JSON string. */
fun todoArray(element: JsonElement?): JsonArray? = when (element) {
    is JsonArray -> element
    is JsonPrimitive -> if (element.isString) runCatching { Json.parseToJsonElement(element.content) as? JsonArray }.getOrNull() else null
    else -> null
}

fun JsonObject.str(vararg keys: String): String? =
    keys.firstNotNullOfOrNull { k -> (this[k] as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf { it.isNotBlank() } }

fun normalizeTodoStatus(raw: String?): String = when (raw?.lowercase()) {
    "completed", "complete", "done", "cancelled", "canceled" -> TodoItemPayload.DONE
    "in_progress", "in-progress", "inprogress", "active", "running" -> TodoItemPayload.IN_PROGRESS
    else -> TodoItemPayload.PENDING
}

/** Items with text only; an empty or all-invalid array is a valid, empty list (the agent cleared it). */
fun itemsOf(array: JsonArray, textKeys: Array<String>): List<TodoItemPayload> =
    array.mapNotNull { e ->
        val o = e as? JsonObject ?: return@mapNotNull null
        val text = o.str(*textKeys) ?: return@mapNotNull null
        TodoItemPayload(
            text = text.take(TODO_MAX_TEXT),
            status = normalizeTodoStatus(o.str("status")),
            activeText = o.str("activeForm")?.take(TODO_MAX_TEXT),
        )
    }.take(TODO_MAX_ITEMS)
