package com.superiorsoftllc.agenthq.protocol.todo

import kotlinx.serialization.json.JsonObject

/** opencode `todowrite` `{todos:[{content, status: pending|in_progress|completed|cancelled, priority}]}` (DeepSeek runs on it). */
object OpenCodeTodoWrite : TodoAdapter {
    override fun parse(toolName: String, input: JsonObject): List<TodoItemPayload>? {
        if (toolName != "todowrite") return null
        val array = todoArray(input["todos"]) ?: return null
        return itemsOf(array, arrayOf("content"))
    }
}
