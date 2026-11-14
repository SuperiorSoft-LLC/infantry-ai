// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.plugins.official.pi.harness

import com.superiorsoftllc.infantry.harness.api.HarnessMcp
import com.superiorsoftllc.infantry.harness.api.McpListing
import com.superiorsoftllc.infantry.harness.api.McpServerSpec
import com.superiorsoftllc.infantry.harness.api.McpServerState
import com.superiorsoftllc.infantry.harness.api.McpServerStatus
import com.superiorsoftllc.infantry.harness.api.McpSource
import com.superiorsoftllc.infantry.harness.api.McpTransport
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonObject

/** Runs `pi mcp ...` ([args] start at `mcp`) in [dir]; exit code and the merged output. Faked in tests. */
fun interface PiMcpRunner {
    fun run(args: List<String>, dir: String?): Pair<Int, String>
}

/**
 * MCP-PI-1: pi's own MCP servers, reached ONLY through the official `pi mcp` commands (never `~/.pi/agent/mcp.json`).
 * list/test = `pi mcp list --json`; add = `pi mcp add [-l]`; remove = `pi mcp remove [-l]`. Scopes: global, project (`-l`).
 * `pi mcp add` replaces a server of the same name, so an edit is an add. The list carries only a transport summary, so no
 * secret value can reach the screen.
 */
class PiMcp(private val runner: PiMcpRunner) : HarnessMcp {
    override val canAdd: Boolean get() = true
    override val canRemove: Boolean get() = true
    override val canEdit: Boolean get() = true
    override val scopes: List<String> get() = SCOPES

    override suspend fun list(project: String): List<McpListing> = parseList(runner.run(listOf("mcp", "list", "--json"), project).second)

    override suspend fun test(project: String, name: String): McpServerStatus =
        list(project).firstOrNull { it.spec.name == name }?.status
            ?: McpServerStatus(name, McpServerState.UNKNOWN, "not listed by `pi mcp list`")

    override suspend fun add(project: String, spec: McpServerSpec, scope: String): Result<Unit> = runCatching {
        val (code, out) = runner.run(addArgs(spec, scope), project)
        check(code == 0) { "pi mcp add failed: ${firstLine(out)}" }
    }

    override suspend fun remove(project: String, name: String, scope: String?): Result<Unit> = runCatching {
        val args = buildList { add("mcp"); add("remove"); add(name); if (scope == "project") add("-l") }
        val (code, out) = runner.run(args, project)
        check(code == 0) { "pi mcp remove failed: ${firstLine(out)}" }
    }

    companion object {
        val SCOPES = listOf("global", "project")
        private val json = Json { ignoreUnknownKeys = true; isLenient = true }

        private fun firstLine(text: String) = text.lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() }?.take(200)

        /** `pi mcp add <name> [-l] [--env K=V]... -- <cmd> [args]`, or `--url <url> [--header K=V]...`. */
        fun addArgs(spec: McpServerSpec, scope: String): List<String> {
            require(scope in SCOPES) { "unknown scope '$scope' (global, project)" }
            return buildList {
                add("mcp"); add("add"); add(spec.name)
                if (scope == "project") add("-l")
                when (val t = spec.transport) {
                    is McpTransport.Stdio -> {
                        t.env.forEach { (k, v) -> add("--env"); add("$k=$v") }
                        t.workingDir?.let { add("--cwd"); add(it) }
                        add("--"); addAll(t.command)
                    }
                    is McpTransport.Http -> {
                        add("--url"); add(t.url)
                        t.headers.forEach { (k, v) -> add("--header"); add("$k=$v") }
                    }
                }
            }
        }

        private fun state(s: String): McpServerState = when (s) {
            "connected" -> McpServerState.CONNECTED
            "needs-auth" -> McpServerState.NEEDS_AUTH
            "disabled" -> McpServerState.DISABLED
            "connecting" -> McpServerState.CONFIGURED
            "disconnected", "closed" -> McpServerState.FAILED
            else -> McpServerState.UNKNOWN
        }

        /** `pi mcp list --json`: `{servers:[{name, scope, enabled, transport, state, tools[], error?}], errors[]}`. It exits 1 on a failed server but still prints the JSON. */
        fun parseList(text: String): List<McpListing> {
            val start = text.indexOf('{')
            if (start < 0) return emptyList()
            val root = runCatching { json.parseToJsonElement(text.substring(start)).jsonObject }.getOrNull() ?: return emptyList()
            val servers = root["servers"] as? JsonArray ?: return emptyList()
            return servers.mapNotNull { e ->
                val o = e as? JsonObject ?: return@mapNotNull null
                val name = (o["name"] as? JsonPrimitive)?.content?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                val target = (o["transport"] as? JsonPrimitive)?.content.orEmpty().trim()
                val transport = if (target.startsWith("http://") || target.startsWith("https://")) McpTransport.Http(target)
                else McpTransport.Stdio(target.split(Regex("\\s+")).filter { it.isNotEmpty() }.ifEmpty { return@mapNotNull null })
                val enabled = (o["enabled"] as? JsonPrimitive)?.let { runCatching { it.boolean }.getOrNull() } ?: true
                val st = if (!enabled) McpServerState.DISABLED else state((o["state"] as? JsonPrimitive)?.content.orEmpty())
                McpListing(
                    McpServerSpec(name, transport, McpSource.HARNESS, (o["scope"] as? JsonPrimitive)?.content),
                    McpServerStatus(name, st, (o["error"] as? JsonPrimitive)?.content?.take(200), (o["tools"] as? JsonArray)?.size),
                )
            }
        }

        /** pi names a server's tools `mcp__<server>__<tool>`, every character other than letters, digits and `_` replaced by `_`. */
        fun toolPattern(server: String): String =
            "mcp__" + server.map { if (it.code < 128 && (it.isLetterOrDigit() || it == '_')) it else '_' }.joinToString("") + "__*"
    }
}
