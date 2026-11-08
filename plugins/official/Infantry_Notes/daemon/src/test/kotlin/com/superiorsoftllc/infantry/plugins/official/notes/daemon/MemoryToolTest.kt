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

    /** KNOWLEDGE-WRITE-GAP-1: a pm session may write a global note; search finds it by body keyword from another project; a worker is refused. */
    @Test
    fun globalWriteSearchByBodyReadFromAnotherProjectAndRefusedRole() {
        val h = MemoryHandler(
            MemoryStore(StorageMemoryPersistence(NotesTestDb.open(path + "2").also { connections += it.first }.second)),
            caller = { id -> when (id) { "pm1" -> MemoryCaller("pm1", "pm"); "w1" -> MemoryCaller("w1", "fullstack"); else -> null } },
        )
        fun tc(sid: String, role: String, vararg args: Pair<String, String>) = object : ToolCall {
            override val arguments = JsonObject(args.toMap().mapValues { JsonPrimitive(it.value) })
            override val caller = Caller(owner = false)
            override val clientSessionId = sid
            override val clientRole = role
            override fun request(type: String, data: String, sessionId: String?, timeoutMs: Long): Frame =
                h.answer(Frame(type, data, sessionId)) ?: Frame("error", "unhandled $type")
        }
        val write = arrayOf("action" to "write", "scope" to "global", "name" to "usage-override", "description" to "pacing override", "body" to "run infantry usage override meter 3")
        val ok = MemoryTool { "p1" }.call(tc("pm1", "pm", *write))
        assertFalse(ok.isError, ok.text)
        val refused = MemoryTool { "p1" }.call(tc("w1", "fullstack", *write))
        assertTrue(refused.isError && refused.text.contains("may not write global"), refused.text)
        val hit = MemoryTool { "p2" }.call(tc("w1", "fullstack", "action" to "search", "query" to "meter 3", "projectId" to "p2"))
        assertTrue(hit.text.contains("usage-override"), hit.text)
        val shown = MemoryTool { "p2" }.call(tc("w1", "fullstack", "action" to "show", "scope" to "global", "name" to "usage-override", "projectId" to "p2"))
        assertTrue(shown.text.contains("infantry usage override"), shown.text)
        val upd = MemoryTool { "p1" }.call(tc("pm1", "pm", "action" to "update", "scope" to "global", "name" to "usage-override", "content" to "verified"))
        assertFalse(upd.isError, upd.text)
        assertTrue(MemoryTool { "p2" }.call(tc("w1", "fullstack", "action" to "show", "scope" to "global", "name" to "usage-override")).text.endsWith("verified"))
        assertTrue(MemoryTool { "p1" }.call(tc("w1", "fullstack", "action" to "delete", "scope" to "global", "name" to "usage-override")).isError)
        assertFalse(MemoryTool { "p1" }.call(tc("pm1", "pm", "action" to "delete", "scope" to "global", "name" to "usage-override")).isError)
    }
}
