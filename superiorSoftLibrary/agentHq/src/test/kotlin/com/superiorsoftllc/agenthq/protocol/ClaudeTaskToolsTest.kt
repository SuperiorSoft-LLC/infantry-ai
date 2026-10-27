package com.superiorsoftllc.agenthq.protocol

import com.superiorsoftllc.agenthq.protocol.todo.ClaudeTaskTools
import com.superiorsoftllc.agenthq.protocol.todo.TodoItemPayload
import kotlin.test.Test
import kotlin.test.assertEquals

class ClaudeTaskToolsTest {
    @Test
    fun replayingTheProbeYieldsTheFullListAfterEachCall() {
        val lines = javaClass.getResource("/retrieval/claude-task-tools.jsonl")!!.readText().lines().filter { it.isNotBlank() }
        val tools = ClaudeTaskTools()
        val emitted = lines.mapNotNull { tools.feed("s1", it) }
        assertEquals(
            listOf(
                listOf(TodoItemPayload("Read README", "pending", "Reading README")),
                listOf(TodoItemPayload("Read README", "in_progress", "Reading README")),
                emptyList(),
            ),
            emitted,
        )
    }

    @Test
    fun sessionsKeepSeparateLists() {
        val tools = ClaudeTaskTools()
        val create = """{"type":"assistant","message":{"content":[{"type":"tool_use","id":"a","name":"TaskCreate","input":{"subject":"x"}}]}}"""
        tools.feed("a", create)
        assertEquals(1, tools.feed("b", create)!!.size)
    }
}
