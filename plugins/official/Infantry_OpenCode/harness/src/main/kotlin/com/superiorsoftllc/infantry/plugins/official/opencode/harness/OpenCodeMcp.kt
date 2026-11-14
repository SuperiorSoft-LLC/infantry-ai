// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.plugins.official.opencode.harness

import com.superiorsoftllc.infantry.harness.api.HarnessMcp
import com.superiorsoftllc.infantry.harness.api.INFANTRY_MCP_SERVER
import com.superiorsoftllc.infantry.harness.api.McpListing
import com.superiorsoftllc.infantry.harness.api.McpPlan
import com.superiorsoftllc.infantry.harness.api.McpServerSpec
import com.superiorsoftllc.infantry.harness.api.McpServerState
import com.superiorsoftllc.infantry.harness.api.McpServerStatus
import com.superiorsoftllc.infantry.harness.api.McpSource
import com.superiorsoftllc.infantry.harness.api.McpTransport
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/** Runs `opencode mcp ...` ([args] start at `mcp`) in [dir]; exit code and merged output. Faked in tests. */
fun interface OpenCodeMcpRunner {
    fun run(args: List<String>, dir: String?): Pair<Int, String>
}

/**
 * MCP-OPENCODE-1: opencode's own MCP servers, READ-ONLY in Infantry (opencode has no official remove/edit and its config file is never
 * edited). list = `opencode mcp list`; add/remove/edit say where to manage them. Delivery rides the inline `OPENCODE_CONFIG_CONTENT`.
 */
class OpenCodeMcp(private val runner: OpenCodeMcpRunner) : HarnessMcp {
    /** The user server names, without a coroutine (session launch). */
    fun names(): List<String> = listNow(null).map { it.spec.name }

    private fun listNow(project: String?): List<McpListing> {
        val (code, out) = runner.run(listOf("mcp", "list"), project)
        return if (code == 0) parseList(out) else emptyList()
    }

    override suspend fun list(project: String): List<McpListing> = listNow(project)
    override suspend fun add(project: String, spec: McpServerSpec, scope: String): Result<Unit> = Result.failure(UnsupportedOperationException(READ_ONLY))

    override suspend fun remove(project: String, name: String, scope: String?): Result<Unit> = Result.failure(UnsupportedOperationException(READ_ONLY))

    override suspend fun test(project: String, name: String): McpServerStatus =
        list(project).firstOrNull { it.spec.name == name }?.status ?: McpServerStatus(name, McpServerState.UNKNOWN, "not listed by opencode")

    companion object {
        const val READ_ONLY = "read-only: manage in opencode"
        private val ANSI = Regex("\\u001B\\[[0-9;?]*[A-Za-z]")
        private val HEAD = Regex("^[●○◆◇*•]\\s+([✓✗⚠○●]\\s+)?(\\S+)\\s+(.*)$")

        private fun state(text: String): McpServerState {
            val t = text.lowercase()
            return when {
                "auth" in t -> McpServerState.NEEDS_AUTH
                "fail" in t || "error" in t -> McpServerState.FAILED
                "disabled" in t -> McpServerState.DISABLED
                "connected" in t -> McpServerState.CONNECTED
                else -> McpServerState.UNKNOWN
            }
        }

        /** `opencode mcp list`: a `● <icon> name status` line per server, its command or url on the next line (box-drawing prefixes and colours dropped). */
        fun parseList(text: String): List<McpListing> {
            val lines = text.lineSequence().map { ANSI.replace(it, "").trim().trimStart('│', '┌', '└', '├', ' ').trim() }.toList()
            val out = mutableListOf<McpListing>()
            var i = 0
            while (i < lines.size) {
                val m = HEAD.find(lines[i])
                if (m == null) { i++; continue }
                val name = m.groupValues[2]
                val statusText = m.groupValues[3].trim()
                val target = lines.getOrNull(i + 1)?.takeIf { it.isNotEmpty() && HEAD.find(it) == null }.orEmpty()
                val transport = if (target.startsWith("http://") || target.startsWith("https://")) McpTransport.Http(target)
                else McpTransport.Stdio(target.split(Regex("\\s+")).filter { it.isNotEmpty() }.ifEmpty { listOf(name) })
                val st = state(statusText)
                out += McpListing(McpServerSpec(name, transport, McpSource.HARNESS), McpServerStatus(name, st, statusText.takeIf { st != McpServerState.CONNECTED && it.isNotEmpty() }))
                i += 2
            }
            return out
        }

        /** `GET /mcp` -> `{name: {status: connected|failed|disabled|needs_auth|...}}` as one status per server. */
        fun statusesOf(body: String): List<McpServerStatus> =
            ((runCatching { Json.parseToJsonElement(body) }.getOrNull()) as? JsonObject).orEmpty().mapNotNull { (name, v) ->
                val o = v as? JsonObject ?: return@mapNotNull null
                val status = (o["status"] as? JsonPrimitive)?.contentOrNull.orEmpty()
                val st = state(status.replace('_', ' ')).takeIf { it != McpServerState.UNKNOWN } ?: McpServerState.CONFIGURED
                McpServerStatus(name, st, (o["error"] as? JsonPrimitive)?.contentOrNull?.take(200))
            }

        /** The names the session's config must switch off: every user server the plan does not deliver (infantry never). */
        fun toDisable(userServers: Collection<String>, plan: McpPlan): Set<String> {
            val keep = plan.delivered.map { it.name }.toSet() + INFANTRY_MCP_SERVER
            return userServers.filter { it !in keep }.toSet()
        }
    }
}
