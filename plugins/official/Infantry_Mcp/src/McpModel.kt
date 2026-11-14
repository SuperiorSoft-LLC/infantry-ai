// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.plugins.official.mcp

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/*
 * MCP-UI-1: the MCP Tools screen's model. One backend, the `mcp_*` frames the CLI (`infantry mcp`) uses (McpFrames.kt in
 * infantryProtocol; the UI has no dependency on it, so the few fields it reads are decoded here). No secret is ever shown:
 * a server carries env and header KEYS only.
 */

/** The frame names of the MCP registry (Envelope.MCP_*). */
object McpWire {
    const val LIST = "mcp_list"
    const val ADD = "mcp_add"
    const val UPDATE = "mcp_update"
    const val REMOVE = "mcp_remove"
    const val SET_ENABLED = "mcp_set_enabled"
    const val TEST = "mcp_test"
    const val ASSIGN = "mcp_assign"
    const val REVOKE = "mcp_revoke"
    const val SERVERS = "mcp_servers"
    const val STATUS = "mcp_status"
    const val CHANGED = "mcp_changed"

    /** The target of Infantry's own servers; any other target is a harness id. */
    const val INFANTRY = "infantry"
}

data class McpServer(
    val name: String, val source: String, val target: String, val scope: String?, val transport: String,
    val command: List<String>, val url: String?, val envKeys: List<String>, val headerKeys: List<String>,
    val enabled: Boolean, val state: String, val detail: String?, val toolCount: Int?, val shadowedBy: String?,
    val projectId: String?,
) {
    val owned: Boolean get() = target == McpWire.INFANTRY
    /** The server's address as one line: its command or its url. */
    val address: String get() = url ?: command.joinToString(" ")
    val key: String get() = "$target/${scope ?: ""}/$name"
}

data class McpTarget(val id: String, val canAdd: Boolean, val canRemove: Boolean, val canEdit: Boolean, val scopes: List<String>, val listing: Boolean, val note: String?)

data class McpGrant(val id: String, val server: String, val scope: String, val target: String, val projectId: String?, val purpose: String, val approvedBy: String)

data class McpSnapshot(val servers: List<McpServer>, val targets: List<McpTarget>, val grants: List<McpGrant>) {
    companion object { val Empty = McpSnapshot(emptyList(), emptyList(), emptyList()) }
}

private fun JsonObject.str(k: String) = (this[k] as? JsonPrimitive)?.contentOrNull
private fun JsonObject.strings(k: String) = (this[k] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull } ?: emptyList()
private fun JsonObject.bool(k: String, d: Boolean) = (this[k] as? JsonPrimitive)?.booleanOrNull ?: d

/** Decodes an `mcp_servers` answer. */
fun parseMcpSnapshot(text: String): McpSnapshot {
    val o = Json.parseToJsonElement(text.ifBlank { "{}" }).jsonObject
    fun list(k: String) = (o[k] as? JsonArray)?.map { it.jsonObject } ?: emptyList()
    return McpSnapshot(
        servers = list("servers").map {
            McpServer(
                it.str("name") ?: "", it.str("source") ?: "", it.str("target") ?: McpWire.INFANTRY, it.str("scope"), it.str("transport") ?: "",
                it.strings("command"), it.str("url"), it.strings("envKeys"), it.strings("headerKeys"),
                it.bool("enabled", true), it.str("state") ?: "unknown", it.str("detail"), (it["toolCount"] as? JsonPrimitive)?.intOrNull,
                it.str("shadowedBy"), it.str("projectId"),
            )
        },
        targets = list("targets").map {
            McpTarget(it.str("id") ?: "", it.bool("canAdd", false), it.bool("canRemove", false), it.bool("canEdit", false), it.strings("scopes"), it.bool("listing", false), it.str("note"))
        },
        grants = list("grants").map {
            McpGrant(it.str("id") ?: "", it.str("server") ?: "", it.str("scope") ?: "", it.str("target") ?: "", it.str("projectId"), it.str("purpose") ?: "", it.str("approvedBy") ?: "")
        },
    )
}

/** Decodes an `mcp_status` answer into the new state (and detail) of one server. */
fun parseMcpStatus(text: String): Triple<String, String, String?> {
    val o = Json.parseToJsonElement(text.ifBlank { "{}" }).jsonObject
    return Triple(o.str("name") ?: "", o.str("state") ?: "unknown", o.str("detail"))
}

/** One section of the screen: the servers one CLI scope (or Infantry, or a plugin) holds. [readOnly]: the screen cannot change them. */
data class McpGroup(val id: String, val title: String, val readOnly: Boolean, val servers: List<McpServer>)

private val GROUP_ORDER = listOf("claude", "opencode", "pi")

/**
 * Servers grouped by source, in a stable order: Claude (user, project, local), opencode, pi, other CLIs, Infantry-owned, plugins.
 * A group is read-only when its CLI target cannot remove (opencode, pi: the screen lists them, never edits them).
 */
fun groupMcpServers(snapshot: McpSnapshot): List<McpGroup> {
    val targets = snapshot.targets.associateBy { it.id }
    fun readOnly(target: String) = targets[target]?.let { !(it.canRemove || it.canEdit || it.canAdd) } ?: true
    val groups = snapshot.servers.groupBy { s ->
        when {
            s.source == "plugin" -> "plugin"
            s.owned -> "infantry"
            else -> "${s.target}:${s.scope ?: ""}"
        }
    }
    fun title(id: String): String = when (id) {
        "plugin" -> "Plugins"
        "infantry" -> "Infantry-owned"
        else -> {
            val (t, scope) = id.split(":", limit = 2).let { it[0] to it.getOrElse(1) { "" } }
            val name = t.replaceFirstChar { it.uppercase() }
            if (scope.isBlank()) name else "$name ($scope)"
        }
    }
    fun rank(id: String): Int = when {
        id == "infantry" -> 100
        id == "plugin" -> 101
        else -> GROUP_ORDER.indexOf(id.substringBefore(':')).let { if (it < 0) 50 else it }
    }
    val scopeOrder = listOf("user", "project", "local")
    return groups.entries
        .sortedWith(compareBy({ rank(it.key) }, { scopeOrder.indexOf(it.key.substringAfter(':', "")).let { i -> if (i < 0) 9 else i } }, { it.key }))
        .map { (id, list) ->
            McpGroup(id, title(id), readOnly = when (id) { "infantry" -> false; "plugin" -> true; else -> readOnly(id.substringBefore(':')) }, servers = list.sortedBy { it.name })
        }
}

/** The granted-to summary of [server] for its card: "not granted" (walled), or one line per grant. */
fun grantSummary(server: McpServer, grants: List<McpGrant>): String {
    val mine = grants.filter { it.server == server.name }
    if (mine.isEmpty()) return "Not granted (walled)"
    return mine.joinToString("; ") { "${it.scope} ${it.target}" }
}

/** What the grant form holds; [scope]: role | agent | ticket. */
data class GrantDraft(val server: String, val scope: String = "role", val target: String = "", val purpose: String = "")

/** Why [d] cannot be saved yet, or null: a grant names a target and says why (the purpose is required). */
fun grantProblem(d: GrantDraft): String? = when {
    d.target.isBlank() -> "Pick who gets the server."
    d.purpose.isBlank() -> "A grant needs a purpose: say why this server is handed over."
    else -> null
}

/** What the add / edit form holds. [editing] = the fixed name being edited. */
data class ServerDraft(
    val editing: McpServer? = null,
    val target: String = McpWire.INFANTRY,
    val scope: String = "app",
    val name: String = "",
    val useUrl: Boolean = false,
    val command: String = "",
    val url: String = "",
    val env: String = "",
    val envSecrets: String = "",
)

/** Why [d] cannot be saved yet, or null. */
fun serverProblem(d: ServerDraft): String? = when {
    d.name.isBlank() -> "Name the server."
    d.useUrl && d.url.isBlank() -> "Give the server's url."
    !d.useUrl && d.command.isBlank() -> "Give the command that starts the server."
    else -> null
}

private fun pairs(text: String): Map<String, String> =
    text.lines().map { it.trim() }.filter { it.contains('=') }.associate { it.substringBefore('=').trim() to it.substringAfter('=').trim() }.filterKeys { it.isNotEmpty() }

/** The request of `mcp_add` / `mcp_update` for [d]. */
fun serverRequest(d: ServerDraft, projectId: String?): JsonObject = buildJsonObject {
    put("target", d.target)
    put("scope", d.scope)
    projectId?.let { put("projectId", it) }
    put("server", buildJsonObject {
        put("name", d.name.trim())
        if (d.useUrl) put("url", d.url.trim()) else putJsonArray("command") { d.command.trim().split(Regex("\\s+")).forEach { add(JsonPrimitive(it)) } }
        val env = pairs(d.env)
        if (env.isNotEmpty()) put("env", buildJsonObject { env.forEach { (k, v) -> put(k, v) } })
        val sec = pairs(d.envSecrets)
        if (sec.isNotEmpty()) put("envSecrets", buildJsonObject { sec.forEach { (k, v) -> put(k, v) } })
    })
}

/** How the screen reaches the daemon: one request, answered by its direct reply (type, data); null = nothing sent. */
fun interface McpBackend {
    suspend fun request(type: String, data: String): Pair<String, String>?
}

/**
 * The screen's one state owner. Every change goes to the backend and the answer (the new list) replaces [snapshot]; a refusal lands in
 * [error]; [notice] is the last thing worth telling the user (the pane posts it on the notification bus).
 */
class McpViewModel(private val backend: McpBackend, private val projectId: () -> String?) {
    var snapshot by mutableStateOf(McpSnapshot.Empty); private set
    var loading by mutableStateOf(true); private set
    var error by mutableStateOf<String?>(null); private set
    var notice by mutableStateOf<String?>(null); private set
    /** A removal the user has asked for and not yet confirmed (the confirm dialog shows it); nothing is sent before it is confirmed. */
    var pendingRemove by mutableStateOf<McpServer?>(null); private set
    /** The latest test result by server key. */
    var tested by mutableStateOf<Map<String, Pair<String, String?>>>(emptyMap()); private set

    val groups: List<McpGroup> get() = groupMcpServers(snapshot)

    fun clearNotice() { notice = null }

    private fun body(vararg fields: Pair<String, String?>): String =
        buildJsonObject { projectId()?.let { put("projectId", it) }; fields.forEach { (k, v) -> if (v != null) put(k, v) } }.toString()

    private suspend fun send(type: String, data: String, success: String? = null): Boolean {
        val reply = backend.request(type, data)
        if (reply == null) { error = "Not connected to the daemon."; loading = false; return false }
        if (reply.first == "error") { error = reply.second; loading = false; return false }
        error = null
        if (reply.first == McpWire.SERVERS) snapshot = parseMcpSnapshot(reply.second)
        loading = false
        if (success != null) notice = success
        return true
    }

    suspend fun refresh() { send(McpWire.LIST, body()) }

    suspend fun save(d: ServerDraft): Boolean {
        serverProblem(d)?.let { error = it; return false }
        val ok = send(if (d.editing == null) McpWire.ADD else McpWire.UPDATE, serverRequest(d, projectId()).toString(), "Saved MCP server ${d.name.trim()}.")
        return ok
    }

    fun askRemove(server: McpServer) { pendingRemove = server }
    fun cancelRemove() { pendingRemove = null }

    /** Sends the removal the user confirmed; no-op without one. */
    suspend fun confirmRemove() {
        val s = pendingRemove ?: return
        pendingRemove = null
        send(McpWire.REMOVE, body("target" to s.target, "name" to s.name, "scope" to s.scope), "Removed MCP server ${s.name}.")
    }

    suspend fun setEnabled(server: McpServer, enabled: Boolean) {
        val data = buildJsonObject {
            projectId()?.let { put("projectId", it) }
            put("name", server.name); server.scope?.let { put("scope", it) }; put("enabled", enabled)
        }.toString()
        send(McpWire.SET_ENABLED, data, if (enabled) "Enabled ${server.name}." else "Disabled ${server.name}.")
    }

    suspend fun test(server: McpServer) {
        val reply = backend.request(McpWire.TEST, body("target" to server.target, "name" to server.name)) ?: run { error = "Not connected to the daemon."; return }
        if (reply.first == "error") { error = reply.second; return }
        error = null
        val (_, state, detail) = parseMcpStatus(reply.second)
        tested = tested + (server.key to (state to detail))
        notice = "${server.name}: $state" + (detail?.let { " ($it)" } ?: "")
    }

    /** Grants [d]; a draft without target or purpose is refused here and never sent. */
    suspend fun grant(d: GrantDraft, approvedBy: String = "owner"): Boolean {
        grantProblem(d)?.let { error = it; return false }
        val data = buildJsonObject {
            projectId()?.let { put("projectId", it) }
            put("grant", buildJsonObject {
                put("server", d.server); put("scope", d.scope); put("target", d.target.trim()); put("purpose", d.purpose.trim()); put("approvedBy", approvedBy)
                projectId()?.let { put("projectId", it) }
            })
        }.toString()
        val ok = send(McpWire.ASSIGN, data)
        if (ok) { notice = "Granted ${d.server} to ${d.scope} ${d.target.trim()}."; refresh() }
        return ok
    }

    suspend fun revoke(grant: McpGrant) {
        if (send(McpWire.REVOKE, buildJsonObject { put("grantId", grant.id) }.toString())) { notice = "Revoked ${grant.server} from ${grant.scope} ${grant.target}."; refresh() }
    }
}
