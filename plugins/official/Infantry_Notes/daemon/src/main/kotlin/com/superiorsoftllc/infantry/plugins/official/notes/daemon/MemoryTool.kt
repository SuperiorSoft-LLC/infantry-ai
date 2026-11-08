// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.plugins.official.notes.daemon

import com.superiorsoftllc.infantry.daemon.api.DaemonTool
import com.superiorsoftllc.infantry.daemon.api.ToolCall
import com.superiorsoftllc.infantry.daemon.api.ToolResult
import com.superiorsoftllc.infantry.memory.MemoryDelete
import com.superiorsoftllc.infantry.memory.MemoryGet
import com.superiorsoftllc.infantry.memory.MemoryList
import com.superiorsoftllc.infantry.memory.MemorySave
import com.superiorsoftllc.infantry.memory.MemorySearch
import com.superiorsoftllc.infantry.protocol.Envelope
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put

/**
 * ROLE-NOTE-READ-1: `infantry_memory`, the MCP way to read project memory and role notes when the `infantry` CLI is not on a
 * session's PATH. `action` show | search | index; `scope` project | role | session (show without a scope tries role, then
 * project, then session). It sends the same `memory_*` frames the CLI does on the caller's own connection, so the project /
 * role scoping and the policy gates are the frames' own; this tool holds no rule of its own.
 */
class MemoryTool(private val projectOf: (sessionId: String) -> String?) : DaemonTool {
    override val name = NAME
    override val description =
        "Read and write knowledge notes (what `infantry memory show|search|index|save|delete` does). action=show needs name; " +
            "action=search needs query (matches names, descriptions and bodies); action=index lists notes; action=write needs name, " +
            "description, body (replaces the note; optional folder); action=update needs name and content (appends; with find it " +
            "replaces that one exact passage); action=delete needs name. scope: project (default), global (shared by every project " +
            "on this install; writing needs a role in setting knowledge.globalWriteRoles, default pm), role (your role's notes), " +
            "session; show without scope tries role, then project (which includes global), then session."
    override val inputSchema = buildJsonObject {
        put("type", "object")
        put("properties", buildJsonObject {
            put("action", buildJsonObject { put("type", "string"); put("enum", buildJsonArray { ACTIONS.forEach { add(JsonPrimitive(it)) } }) })
            put("name", buildJsonObject { put("type", "string"); put("description", "Note name (action=show).") })
            put("query", buildJsonObject { put("type", "string"); put("description", "Search term (action=search).") })
            put("scope", buildJsonObject { put("type", "string"); put("enum", buildJsonArray { SCOPES.forEach { add(JsonPrimitive(it)) } }) })
            for (k in listOf("description", "body", "content", "find", "folder")) {
                put(k, buildJsonObject { put("type", "string"); put("description", "action=write: description, body, folder. action=update: content (+ find to replace a passage).") })
            }
            put("projectId", buildJsonObject { put("type", "string"); put("description", "Default: this session's project.") })
        })
        put("required", buildJsonArray { add(JsonPrimitive("action")) })
        put("additionalProperties", false)
    }

    override fun call(call: ToolCall): ToolResult {
        fun arg(k: String) = (call.arguments[k] as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }
        val session = call.clientSessionId?.trim()?.takeIf { it.isNotEmpty() }
        val project = arg("projectId") ?: session?.let(projectOf) ?: call.clientProjectId?.trim()?.takeIf { it.isNotEmpty() }
            ?: return refused("$NAME: no project (this session has none; pass projectId)")
        val scope = arg("scope")
        if (scope != null && scope !in SCOPES) return refused("$NAME: scope must be one of ${SCOPES.joinToString("|")}")
        val role = call.clientRole?.trim()?.takeIf { it.isNotEmpty() }
        return try {
            when (arg("action")) {
                "show" -> {
                    val n = arg("name") ?: return refused("$NAME show needs name")
                    var last = ""
                    for (s in scope?.let { listOf(it) } ?: listOf("role", "project", "session")) {
                        val r = call.request(Envelope.MEMORY_GET, json.encodeToString(MemoryGet.serializer(), MemoryGet(project, n, s, session, role)), session)
                        if (r.type != Envelope.MEMORY_NOTE) { last = r.data; continue }
                        val o = json.parseToJsonElement(r.data) as JsonObject
                        return ToolResult("SCOPE: $s\nNAME: $n\n\n" + ((o["body"] as? JsonPrimitive)?.contentOrNull ?: ""))
                    }
                    refused(last.ifBlank { "$NAME: no note '$n'" })
                }
                "search" -> {
                    val q = arg("query") ?: return refused("$NAME search needs query")
                    val r = call.request(Envelope.MEMORY_SEARCH, json.encodeToString(MemorySearch.serializer(), MemorySearch(project, q, scope, session, role)), session)
                    if (r.type != Envelope.MEMORY_HITS) refused(r.data) else ToolResult(r.data)
                }
                "index" -> {
                    val r = call.request(Envelope.MEMORY_LIST, json.encodeToString(MemoryList.serializer(), MemoryList(project, scope, session, role)), session)
                    if (r.type != Envelope.MEMORY_NOTES) refused(r.data) else ToolResult(r.data)
                }
                "write", "update" -> {
                    val n = arg("name") ?: return refused("$NAME ${arg("action")} needs name")
                    val req = if (arg("action") == "write") {
                        val d = arg("description") ?: return refused("$NAME write needs description")
                        val b = (call.arguments["body"] as? JsonPrimitive)?.contentOrNull ?: return refused("$NAME write needs body")
                        MemorySave(project, n, description = d, body = b, folder = arg("folder"), scope = scope, sessionId = session, role = role)
                    } else {
                        val c = (call.arguments["content"] as? JsonPrimitive)?.contentOrNull ?: return refused("$NAME update needs content")
                        val f = (call.arguments["find"] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotEmpty() }
                        MemorySave(
                            project, n, description = arg("description"), content = c, find = f, folder = arg("folder"),
                            mode = if (f != null) MemorySave.MODE_EDIT else MemorySave.MODE_APPEND, scope = scope, sessionId = session, role = role,
                        )
                    }
                    val r = call.request(Envelope.MEMORY_SAVE, json.encodeToString(MemorySave.serializer(), req), session)
                    if (r.type != Envelope.MEMORY_NOTE) refused(r.data) else ToolResult("saved ${scope ?: "project"} note '$n'")
                }
                "delete" -> {
                    val n = arg("name") ?: return refused("$NAME delete needs name")
                    val r = call.request(Envelope.MEMORY_DELETE, json.encodeToString(MemoryDelete.serializer(), MemoryDelete(project, n, scope, session, role)), session)
                    if (r.type != Envelope.MEMORY_NOTES) refused(r.data) else ToolResult("deleted ${scope ?: "project"} note '$n'")
                }
                else -> refused("$NAME: action must be one of ${ACTIONS.joinToString("|")}")
            }
        } catch (e: Exception) {
            refused("$NAME failed: ${e.message}")
        }
    }

    private fun refused(text: String) = ToolResult(text, isError = true)

    companion object {
        const val NAME = "infantry_memory"
        val ACTIONS = listOf("show", "search", "index", "write", "update", "delete")
        val SCOPES = listOf("project", "role", "session", "global")
        private val json = Json { ignoreUnknownKeys = true }
    }
}
