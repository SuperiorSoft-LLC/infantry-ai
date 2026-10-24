package com.superiorsoftllc.agenthq.protocol

import com.superiorsoftllc.agenthq.protocol.EventNormalizer.Line
import com.superiorsoftllc.agenthq.protocol.todo.TodoAdapters
import com.superiorsoftllc.agenthq.protocol.todo.TodoItemPayload
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** TODO-OVERLAY-1: the to-do adapters, over the REAL captured tool calls in `/retrieval` plus table rows per shape. */
class TodoAdapterTest {
    private fun fixture(name: String) =
        checkNotNull(javaClass.getResourceAsStream("/retrieval/$name.jsonl")).bufferedReader().readLines().filter { it.isNotBlank() }

    private fun todosOf(lines: List<String>): List<List<TodoItemPayload>> =
        lines.flatMap { EventNormalizer.normalise(it) }.filterIsInstance<Line.Tool>().mapNotNull { it.todos }

    private fun obj(s: String) = Json.parseToJsonElement(s) as JsonObject

    @Test fun `real opencode todowrite capture yields the list`() {
        val lists = todosOf(fixture("opencode-tools-run"))
        assertEquals(listOf(listOf(TodoItemPayload("check archive", "pending"))), lists.distinct())
    }

    @Test fun `real claude TodoWrite capture with string-encoded todos yields the list`() {
        val lists = todosOf(fixture("claude-run"))
        assertEquals(listOf(listOf(TodoItemPayload("check archive", "pending", "Checking archive"))), lists)
    }

    @Test fun `table of shapes`() {
        val rows = listOf(
            Triple("TodoWrite", """{"todos":[{"content":"a","status":"in_progress","activeForm":"Doing a"},{"content":"b","status":"completed"}]}""",
                listOf(TodoItemPayload("a", "in_progress", "Doing a"), TodoItemPayload("b", "done"))),
            Triple("todowrite", """{"todos":[{"content":"a","status":"cancelled","priority":"high"},{"content":"b","status":"pending"}]}""",
                listOf(TodoItemPayload("a", "done"), TodoItemPayload("b", "pending"))),
            Triple("todowrite", """{"todos":[]}""", emptyList()),
            Triple("TodoWrite", """{"todos":[{"status":"pending"},{"content":"  ","status":"pending"}]}""", emptyList()),
        )
        for ((tool, json, want) in rows) assertEquals(want, TodoAdapters.parse(tool, obj(json)), "$tool $json")
    }

    @Test fun `other tools and malformed input are not to-do calls`() {
        assertNull(TodoAdapters.parse("Read", obj("""{"todos":[{"content":"a"}]}""")))
        assertNull(TodoAdapters.parse("TodoWrite", obj("""{"other":1}""")))
        assertNull(TodoAdapters.parse("TodoWrite", obj("""{"todos":"not json"}""")))
        assertNull(TodoAdapters.parse("TodoWrite", null))
    }

    @Test fun `only items are kept and the list is capped`() {
        val many = (1..80).joinToString(",") { """{"content":"t$it","status":"pending","secret":"x"}""" }
        val list = TodoAdapters.parse("TodoWrite", obj("""{"todos":[$many]}"""))!!
        assertEquals(50, list.size)
    }
}
