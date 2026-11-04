// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.plugins.official.notes.daemon

import com.superiorsoftllc.infantry.daemon.api.Caller
import com.superiorsoftllc.infantry.daemon.api.Frame
import com.superiorsoftllc.infantry.daemon.api.ToolCall
import com.superiorsoftllc.infantry.memory.MemorySave
import com.superiorsoftllc.infantry.protocol.Envelope
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** ROLE-NOTE-READ-1: infantry_memory reads project and role notes through the memory_* frames, scoped to the caller. */
class MemoryToolTest {
    private val path = Files.createTempFile("infantry-memtool-", ".db").also { it.toFile().delete() }.toAbsolutePath().toString()
    private val connections = mutableListOf<java.sql.Connection>()
    private val handler = MemoryHandler(
        MemoryStore(StorageMemoryPersistence(NotesTestDb.open(path).also { connections += it.first }.second)),
        caller = { id -> if (id == "s1") MemoryCaller("s1", "fullstack") else null },
    )
    private val json = Json { ignoreUnknownKeys = true }

    @AfterTest
    fun close() { connections.forEach { runCatching { it.close() } } }

    private fun call(vararg args: Pair<String, String>) = object : ToolCall {
        override val arguments = JsonObject(args.toMap().mapValues { JsonPrimitive(it.value) })
        override val caller = Caller(owner = false)
        override val clientSessionId = "s1"
        override val clientRole = "fullstack"
        override fun request(type: String, data: String, sessionId: String?, timeoutMs: Long): Frame =
            handler.answer(Frame(type, data, sessionId)) ?: Frame("error", "unhandled $type")
    }

    private fun save(scope: String, name: String, body: String) {
        handler.answer(Frame(Envelope.MEMORY_SAVE, json.encodeToString(MemorySave.serializer(),
            MemorySave("p1", name, "d", body, scope = scope, sessionId = "s1", role = "fullstack"))))
    }

    private val tool = MemoryTool { "p1" }

    @Test
    fun showFindsARoleNoteWithoutAScopeAndAProjectNoteToo() {
        save("role", "ui-preview", "preview before done")
        save("project", "plan", "the plan")
        assertTrue("preview before done" in tool.call(call("action" to "show", "name" to "ui-preview")).text)
        assertTrue("the plan" in tool.call(call("action" to "show", "name" to "plan")).text)
        assertTrue("the plan" in tool.call(call("action" to "show", "name" to "plan", "scope" to "project")).text)
    }

    @Test
    fun indexSearchAndRefusals() {
        save("role", "ui-preview", "preview before done")
        assertTrue("ui-preview" in tool.call(call("action" to "index", "scope" to "role")).text)
        assertTrue("ui-preview" in tool.call(call("action" to "search", "query" to "preview", "scope" to "role")).text)
        val missing = tool.call(call("action" to "show", "name" to "nope"))
        assertTrue(missing.isError)
        assertTrue(tool.call(call("action" to "show")).isError)
        assertTrue(tool.call(call("action" to "bogus")).isError)
        assertFalse(tool.call(call("action" to "index")).isError)
        assertEquals(true, tool.call(call("action" to "index", "scope" to "x")).isError)
    }
}
