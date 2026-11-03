package com.superiorsoftllc.infantry.protocol.todo

import kotlinx.serialization.json.JsonObject

/** claude `TodoWrite` `{todos:[{content, status, activeForm}]}`; `todos` may arrive as a JSON-encoded string. */
object ClaudeTodoWrite : TodoAdapter {
    override fun parse(toolName: String, input: JsonObject): List<TodoItemPayload>? {
        if (toolName != "TodoWrite") return null
        val array = todoArray(input["todos"]) ?: return null
        return itemsOf(array, arrayOf("content"))
    }
}
