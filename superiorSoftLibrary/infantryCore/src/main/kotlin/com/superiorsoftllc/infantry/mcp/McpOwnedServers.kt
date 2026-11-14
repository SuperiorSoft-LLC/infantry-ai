// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.mcp

import com.superiorsoftllc.infantry.harness.api.INFANTRY_MCP_SERVER
import com.superiorsoftllc.infantry.harness.api.McpServerSpec
import com.superiorsoftllc.infantry.harness.api.McpSource
import com.superiorsoftllc.infantry.harness.api.McpTransport
import com.superiorsoftllc.infantry.protocol.McpServerInput
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** MCP-REGISTRY-1: an Infantry-owned server and where it lives: [projectId] null = the app scope (every project). */
data class OwnedServer(val server: McpServerInput, val projectId: String?)

/**
 * MCP-REGISTRY-1: the servers the user added in Infantry itself (source [McpSource.INFANTRY_USER]), in the app scope or one project's.
 * Stored in [storage] (document `servers`); values that are secrets are never stored: a server names Secrets entries
 * ([McpServerInput.envSecrets], [McpServerInput.headerSecrets]) and [toSpec] resolves them when a session needs the server.
 * Mechanism only: it names no server and no harness.
 */
class McpOwnedServers(
    private val storage: McpStorage,
    /** The most servers one scope may hold (`limits.mcp.serversMax`). */
    private val maxPerScope: () -> Int,
) {
    @Serializable
    private data class Doc(val app: List<McpServerInput> = emptyList(), val projects: Map<String, List<McpServerInput>> = emptyMap())

    private val lock = Any()

    /** The servers [projectId] sees: its own first, then the app scope's (a project server shadows an app one of the same name). */
    fun visible(projectId: String?): List<OwnedServer> = synchronized(lock) {
        val doc = load()
        val own = projectId?.let { doc.projects[it] }.orEmpty().map { OwnedServer(it, projectId) }
        val names = own.map { it.server.name }.toSet()
        own + doc.app.filter { it.name !in names }.map { OwnedServer(it, null) }
    }

    /** Every stored server, every scope (the management screen). */
    fun all(): List<OwnedServer> = synchronized(lock) {
        val doc = load()
        doc.app.map { OwnedServer(it, null) } + doc.projects.flatMap { (p, list) -> list.map { OwnedServer(it, p) } }
    }

    /** Add [server] to [projectId]'s scope (null = app). A name already there is refused unless [replace]. */
    fun put(server: McpServerInput, projectId: String?, replace: Boolean = false): OwnedServer = synchronized(lock) {
        validate(server)
        val doc = load()
        val list = scope(doc, projectId)
        val exists = list.any { it.name == server.name }
        require(!exists || replace) { "an Infantry MCP server named '${server.name}' already exists in ${scopeLabel(projectId)}; use update" }
        require(exists || !replace) { "no Infantry MCP server named '${server.name}' in ${scopeLabel(projectId)}" }
        require(exists || list.size < maxPerScope()) {
            "${scopeLabel(projectId)} already holds ${list.size} Infantry MCP servers (setting 'limits.mcp.serversMax')"
        }
        val next = if (exists) list.map { if (it.name == server.name) server else it } else list + server
        save(withScope(doc, projectId, next))
        OwnedServer(server, projectId)
    }

    /** Remove [name] from [projectId]'s scope; false when it was not there. */
    fun remove(name: String, projectId: String?): Boolean = synchronized(lock) {
        val doc = load()
        val list = scope(doc, projectId)
        if (list.none { it.name == name }) return false
        save(withScope(doc, projectId, list.filter { it.name != name }))
        true
    }

    /** Turn [name] in [projectId]'s scope on or off (an off server is never delivered); false when it was not there. */
    fun setEnabled(name: String, projectId: String?, enabled: Boolean): Boolean = synchronized(lock) {
        val doc = load()
        val list = scope(doc, projectId)
        if (list.none { it.name == name }) return false
        save(withScope(doc, projectId, list.map { if (it.name == name) it.copy(enabled = enabled) else it }))
        true
    }

    private fun scope(doc: Doc, projectId: String?) = if (projectId == null) doc.app else doc.projects[projectId].orEmpty()

    private fun withScope(doc: Doc, projectId: String?, list: List<McpServerInput>): Doc =
        if (projectId == null) doc.copy(app = list)
        else doc.copy(projects = (doc.projects - projectId) + (if (list.isEmpty()) emptyMap() else mapOf(projectId to list)))

    private fun load(): Doc = storage.read(DOC)?.let { runCatching { JSON.decodeFromString(Doc.serializer(), it) }.getOrNull() } ?: Doc()

    private fun save(doc: Doc) = storage.write(DOC, JSON.encodeToString(Doc.serializer(), doc))

    private fun scopeLabel(projectId: String?) = projectId?.let { "project $it" } ?: "the app scope"

    companion object {
        private const val DOC = "servers"
        private val JSON = Json { ignoreUnknownKeys = true; encodeDefaults = false; prettyPrint = true }

        /** A server name becomes the agent's tool prefix (`mcp__<name>__*`): letters, digits, `_` and `-` only. */
        val NAME = Regex("[A-Za-z0-9][A-Za-z0-9_-]{0,63}")

        /** A key whose plain value would be a credential: those values go in the Secrets store. */
        private val CREDENTIAL_KEY = Regex("(?i).*(token|secret|password|passwd|api[_-]?key|private[_-]?key|credential|authorization|cookie|bearer).*")

        /** Refuse a server that cannot be stored: a bad or reserved name, both or neither transport, a plain credential. */
        fun validate(server: McpServerInput) {
            require(NAME.matches(server.name)) { "MCP server name '${server.name}' must be letters, digits, '_' or '-' (at most 64)" }
            require(server.name != INFANTRY_MCP_SERVER) { "'$INFANTRY_MCP_SERVER' is the daemon's own MCP server" }
            val stdio = server.command.isNotEmpty()
            val http = !server.url.isNullOrBlank()
            require(stdio != http) { "MCP server '${server.name}' needs exactly one of a command (stdio) or a url (http)" }
            val url = server.url.orEmpty()
            if (http) require(url.startsWith("http://") || url.startsWith("https://")) { "MCP server url must start with http:// or https://" }
            require(stdio || (server.env.isEmpty() && server.envSecrets.isEmpty() && server.workingDir == null)) { "env and workingDir apply to a stdio server only" }
            require(http || (server.headers.isEmpty() && server.headerSecrets.isEmpty())) { "headers apply to an http server only" }
            (server.env.keys + server.headers.keys).firstOrNull { CREDENTIAL_KEY.matches(it) }?.let {
                throw IllegalArgumentException("'$it' looks like a credential: store it in Secrets and name it in envSecrets / headerSecrets, never as a plain value")
            }
            (server.envSecrets.keys intersect server.env.keys).firstOrNull()?.let { throw IllegalArgumentException("env key '$it' is given twice") }
            (server.headerSecrets.keys intersect server.headers.keys).firstOrNull()?.let { throw IllegalArgumentException("header key '$it' is given twice") }
        }

        /**
         * [owned] as a spec for a session, its secret refs resolved through [secret] (secret name, project -> value). A secret that is
         * not set fails the whole server: the answer names the missing entry, never a value.
         */
        fun toSpec(owned: OwnedServer, secret: (String, String?) -> String?): Result<McpServerSpec> = runCatching {
            val s = owned.server
            fun resolve(refs: Map<String, String>): Map<String, String> = refs.mapValues { (key, name) ->
                secret(name, owned.projectId) ?: throw IllegalStateException("secret '$name' (for $key) is not set")
            }
            val transport = if (s.command.isNotEmpty()) McpTransport.Stdio(s.command, s.env + resolve(s.envSecrets), s.workingDir)
            else McpTransport.Http(s.url!!, s.headers + resolve(s.headerSecrets))
            McpServerSpec(s.name, transport, McpSource.INFANTRY_USER, scope = if (owned.projectId == null) "app" else "project", provides = s.provides.toSet(), localOnly = s.localOnly)
        }
    }
}
