// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.plugins.official.claude.harness

import com.superiorsoftllc.infantry.harness.api.HarnessMcp
import com.superiorsoftllc.infantry.harness.api.INFANTRY_MCP_SERVER
import com.superiorsoftllc.infantry.harness.api.McpListing
import com.superiorsoftllc.infantry.harness.api.McpPlan
import com.superiorsoftllc.infantry.harness.api.McpServerSpec
import com.superiorsoftllc.infantry.harness.api.McpServerState
import com.superiorsoftllc.infantry.harness.api.McpServerStatus
import com.superiorsoftllc.infantry.harness.api.McpSource
import com.superiorsoftllc.infantry.harness.api.McpTransport
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.File
import java.security.MessageDigest

/** Runs `claude mcp ...` ([args] start at `mcp`) in [dir]; exit code and the merged output. Faked in tests. */
fun interface ClaudeMcpRunner {
    fun run(args: List<String>, dir: String?): Pair<Int, String>
}

/**
 * MCP-CLAUDE-1: Claude's own MCP servers, reached ONLY through the official `claude mcp` commands (never `~/.claude.json`).
 * list = `claude mcp list`; test = `claude mcp get <name>`; add = `claude mcp add-json -s <scope>`; remove = `claude mcp remove -s <scope>`.
 * Every spec this class returns to the screen has its env and header values masked ([MASK]).
 */
class ClaudeMcp(
    private val runner: ClaudeMcpRunner,
    private val windows: Boolean = System.getProperty("os.name").orEmpty().startsWith("Windows"),
) : HarnessMcp {
    override val canAdd: Boolean get() = true
    override val canRemove: Boolean get() = true
    override val canEdit: Boolean get() = true
    override val scopes: List<String> get() = SCOPES

    override suspend fun list(project: String): List<McpListing> {
        val (code, out) = runner.run(listOf("mcp", "list"), project)
        return if (code == 0) parseList(out) else emptyList()
    }

    override suspend fun test(project: String, name: String): McpServerStatus {
        val (code, out) = runner.run(listOf("mcp", "get", name), project)
        if (code != 0) return McpServerStatus(name, McpServerState.UNKNOWN, firstLine(out))
        return parseGet(name, out, mask = true)?.second ?: McpServerStatus(name, McpServerState.UNKNOWN, "unreadable `claude mcp get` output")
    }

    override suspend fun add(project: String, spec: McpServerSpec, scope: String): Result<Unit> = runCatching {
        require(scope in SCOPES) { "unknown scope '$scope' (local, user, project)" }
        val json = addJson(spec).toString().let { if (windows) it.replace("\"", "\\\"") else it }
        val (code, out) = runner.run(listOf("mcp", "add-json", "-s", scope, spec.name, json), project)
        check(code == 0) { "claude mcp add-json failed: ${firstLine(out)}" }
    }

    override suspend fun remove(project: String, name: String, scope: String?): Result<Unit> = runCatching {
        val args = buildList { add("mcp"); add("remove"); scope?.let { add("-s"); add(it) }; add(name) }
        val (code, out) = runner.run(args, project)
        check(code == 0) { "claude mcp remove failed: ${firstLine(out)}" }
    }

    /** Edit = remove + add-json in the same scope; when the add fails the old definition ([before]) is put back. */
    suspend fun edit(project: String, spec: McpServerSpec, scope: String, before: McpServerSpec?): Result<Unit> {
        remove(project, spec.name, scope).onFailure { return Result.failure(it) }
        val added = add(project, spec, scope)
        if (added.isFailure && before != null) add(project, before, scope)
        return added
    }

    /** The full definition of [name] with its REAL env/header values (for a session's config file only, never the screen). */
    fun definition(project: String?, name: String): McpServerSpec? {
        val (code, out) = runner.run(listOf("mcp", "get", name), project)
        return if (code == 0) parseGet(name, out, mask = false)?.first else null
    }

    companion object {
        const val MASK = "***"
        val SCOPES = listOf("local", "user", "project")

        private fun firstLine(text: String) = text.lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() }?.take(200)

        private fun state(text: String): McpServerState {
            val t = text.lowercase()
            return when {
                "needs auth" in t -> McpServerState.NEEDS_AUTH
                "fail" in t || "✗" in text -> McpServerState.FAILED
                "connected" in t || "✓" in text -> McpServerState.CONNECTED
                "disabled" in t -> McpServerState.DISABLED
                "pending" in t || "approval" in t -> McpServerState.PENDING_APPROVAL
                else -> McpServerState.UNKNOWN
            }
        }

        private fun detail(st: McpServerState, text: String): String? =
            text.trimStart('✓', '✗', '⚠', ' ').takeIf { st != McpServerState.CONNECTED && it.isNotEmpty() }

        /** `claude mcp list`: one `name: target - status` line per server; other lines (the health header) are skipped. */
        fun parseList(text: String): List<McpListing> = text.lineSequence().mapNotNull { raw ->
            val line = raw.trim()
            val sep = line.indexOf(": ")
            val dash = line.lastIndexOf(" - ")
            if (line.isEmpty() || sep <= 0 || dash <= sep || line.startsWith("Checking")) return@mapNotNull null
            val name = line.substring(0, sep).trim()
            if (name.any { it.isWhitespace() }) return@mapNotNull null
            val target = line.substring(sep + 2, dash).trim().replace(Regex("\\s*\\((HTTP|SSE)\\)$", RegexOption.IGNORE_CASE), "")
            val statusText = line.substring(dash + 3).trim()
            val transport = if (target.startsWith("http://") || target.startsWith("https://")) McpTransport.Http(target)
            else McpTransport.Stdio(target.split(Regex("\\s+")).filter { it.isNotEmpty() }.ifEmpty { return@mapNotNull null })
            val st = state(statusText)
            McpListing(McpServerSpec(name, transport, McpSource.HARNESS), McpServerStatus(name, st, detail(st, statusText)))
        }.toList()

        private fun scopeOf(label: String): String? {
            val l = label.lowercase()
            return when {
                l.startsWith("local") -> "local"
                l.startsWith("user") -> "user"
                l.startsWith("project") -> "project"
                else -> null
            }
        }

        /** `claude mcp get <name>`: the spec ([mask] hides env and header values) and its status; null when no transport is readable. */
        fun parseGet(name: String, text: String, mask: Boolean): Pair<McpServerSpec, McpServerStatus>? {
            var scope: String? = null
            var type: String? = null
            var command: String? = null
            var args = emptyList<String>()
            var url: String? = null
            var statusText = ""
            var block = ""
            val env = linkedMapOf<String, String>()
            val headers = linkedMapOf<String, String>()
            for (raw in text.lines()) {
                val line = raw.trim()
                if (line.isEmpty() || line.startsWith("To remove")) { block = ""; continue }
                val indented = raw.startsWith("    ")
                if (indented && block == "env") {
                    val i = line.indexOf('=')
                    if (i > 0) env[line.substring(0, i)] = if (mask) MASK else line.substring(i + 1)
                    continue
                }
                if (indented && block == "headers") {
                    val i = line.indexOf(':')
                    if (i > 0) headers[line.substring(0, i).trim()] = if (mask) MASK else line.substring(i + 1).trim()
                    continue
                }
                val i = line.indexOf(':')
                if (i <= 0) continue
                val key = line.substring(0, i).trim().lowercase()
                val value = line.substring(i + 1).trim()
                block = ""
                when (key) {
                    "scope" -> scope = scopeOf(value)
                    "status" -> statusText = value
                    "type" -> type = value.lowercase()
                    "command" -> command = value
                    "args" -> args = value.split(Regex("\\s+")).filter { it.isNotEmpty() }
                    "url" -> url = value
                    "environment" -> block = "env"
                    "headers" -> block = "headers"
                }
            }
            val transport = when {
                url != null && type != "stdio" -> McpTransport.Http(url, headers)
                command != null -> McpTransport.Stdio(listOf(command) + args, env)
                else -> return null
            }
            val st = state(statusText)
            return McpServerSpec(name, transport, McpSource.HARNESS, scope) to McpServerStatus(name, st, detail(st, statusText))
        }

        /** One server's JSON, as `claude mcp add-json` and `--mcp-config` take it. */
        fun addJson(spec: McpServerSpec): JsonObject = when (val t = spec.transport) {
            is McpTransport.Stdio -> JsonObject(mapOf(
                "type" to JsonPrimitive("stdio"),
                "command" to JsonPrimitive(t.command.first()),
                "args" to JsonArray(t.command.drop(1).map(::JsonPrimitive)),
                "env" to JsonObject(t.env.mapValues { JsonPrimitive(it.value) }),
            ))
            is McpTransport.Http -> JsonObject(buildMap {
                put("type", JsonPrimitive("http"))
                put("url", JsonPrimitive(t.url))
                if (t.headers.isNotEmpty()) put("headers", JsonObject(t.headers.mapValues { JsonPrimitive(it.value) }))
            })
        }

        /**
         * The one `--mcp-config` document of a session: every server of [plan] (infantry, Infantry-owned, plugin, granted CLI servers).
         * A granted CLI server's list entry is only a summary, so its full definition comes from [definition] (`claude mcp get`); when
         * that finds nothing the server is left out (never guessed).
         */
        fun configJson(plan: McpPlan, definition: (String) -> McpServerSpec?): String {
            val servers = plan.delivered.mapNotNull { spec ->
                val full = if (spec.source == McpSource.HARNESS) definition(spec.name) ?: return@mapNotNull null else spec
                spec.name to addJson(full)
            }
            return JsonObject(mapOf("mcpServers" to JsonObject(servers.toMap()))).toString()
        }

        /** The headless `system`/`init` record's `mcp_servers` ([{name, status}]) as session status; an interactive CLI has none (CONFIGURED + tool-error FAILED detection is the hook point). */
        fun initStatuses(record: JsonObject): List<McpServerStatus> =
            (record["mcp_servers"] as? kotlinx.serialization.json.JsonArray).orEmpty().mapNotNull { e ->
                val o = e as? JsonObject ?: return@mapNotNull null
                val name = (o["name"] as? JsonPrimitive)?.content ?: return@mapNotNull null
                val status = (o["status"] as? JsonPrimitive)?.content.orEmpty()
                McpServerStatus(name, state(status).takeIf { it != McpServerState.UNKNOWN } ?: McpServerState.CONFIGURED, status.takeIf { it.isNotEmpty() && !it.equals("connected", true) })
            }

        /** `--disallowedTools` rules for the servers the plan turns off. */
        fun disallowed(plan: McpPlan): List<String> =
            plan.deny.filter { it != INFANTRY_MCP_SERVER }.sorted().map { ClaudeCommand.mcpToolRule(it) }

        /** The config as a content-addressed file in [dir] (inline JSON loses its quotes on Windows); null when it holds no server. */
        fun configFile(json: String, dir: File): String? {
            if (json.contains("\"mcpServers\":{}")) return null
            val sha = MessageDigest.getInstance("SHA-256").digest(json.toByteArray()).joinToString("") { "%02x".format(it) }.take(16)
            val file = File(dir, "plan-$sha.json")
            if (!file.isFile) { dir.mkdirs(); file.writeText(json) }
            return file.absolutePath
        }
    }
}

/** What one launch gets from the plan: the config files and the denied tool rules. */
data class ClaudeMcpDelivery(val configs: List<String> = emptyList(), val disallowed: List<String> = emptyList())
