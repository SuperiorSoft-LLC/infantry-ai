// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.cli

import com.superiorsoftllc.infantry.protocol.Envelope
import com.superiorsoftllc.infantry.protocol.MCP_TARGET_INFANTRY
import com.superiorsoftllc.infantry.protocol.McpGrantInput
import com.superiorsoftllc.infantry.protocol.McpGrantView
import com.superiorsoftllc.infantry.protocol.McpListAnswer
import com.superiorsoftllc.infantry.protocol.McpRequest
import com.superiorsoftllc.infantry.protocol.McpServerInput
import com.superiorsoftllc.infantry.protocol.McpServerStatusView
import com.superiorsoftllc.infantry.protocol.McpServerView
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/**
 * MCP-CLI-1: `infantry mcp list|get|add|edit|remove|enable|disable|test|grant|revoke|grants`, one face of the MCP registry backend (the
 * `mcp_*` frames the MCP Tools screen and the `infantry_mcp` agent tool use too). `infantry mcp serve` stays the MCP server (CliMain).
 * Output is compact, one line per server; a secret value is never in a view, so none is printed. A session's own CLI (INFANTRY_SESSION_ID
 * set, role other than pm) may read but not change: the walled default must not be widened by the agent it limits.
 */
internal object McpCli {
    const val USAGE = "usage: infantry mcp serve | list [target] | get <name> [target] | add|edit <target> [scope] --payload <server json> | " +
        "remove <target> <name> [scope] | enable|disable <name> [scope] | test <target> <name> | " +
        "grant <server> <role|agent|ticket> <id> --purpose <why> | revoke <grantId> | grants   (all take --project <id>; list/get take --json)"

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = false }
    private val READS = setOf("list", "get", "test", "grants")
    private val CHANGES = setOf("add", "edit", "remove", "enable", "disable", "grant", "revoke")

    /** A usage problem with [args] (the words after `mcp`), or null. */
    fun usageError(args: List<String>, payload: String?, purpose: String?): String? {
        val n = args.size
        return when (args.firstOrNull()) {
            "serve" -> if (n == 1) null else USAGE
            "list" -> if (n in 1..2) null else USAGE
            "get" -> if (n in 2..3) null else USAGE
            "add", "edit" -> if (n in 2..3 && !payload.isNullOrBlank()) null else USAGE
            "remove" -> if (n in 3..4) null else USAGE
            "enable", "disable" -> if (n in 2..3) null else USAGE
            "test" -> if (n == 3) null else USAGE
            "grant" -> if (n == 4 && !purpose.isNullOrBlank()) null else USAGE
            "revoke" -> if (n == 2) null else USAGE
            "grants" -> if (n == 1) null else USAGE
            else -> USAGE
        }
    }

    /** Why this process may not change the registry, or null. A pm session or a person at a terminal (no session env) may. */
    fun changeRefusal(verb: String, getenv: (String) -> String?): String? {
        if (verb !in CHANGES) return null
        if (getenv("INFANTRY_SESSION_ID").isNullOrBlank()) return null
        if (getenv("INFANTRY_ROLE") == "pm") return null
        return "mcp $verb: an agent session cannot change MCP servers or grants (walled by default). Ask the PM or the owner (infantry ask)."
    }

    suspend fun run(stream: FrameStream, args: List<String>, project: String?, payload: String?, purpose: String?, jsonOut: Boolean, out: Appendable, err: Appendable, getenv: (String) -> String?): Int {
        val verb = args.first()
        changeRefusal(verb, getenv)?.let { err.appendLine(it); return 1 }
        return try {
            val by = if (getenv("INFANTRY_SESSION_ID").isNullOrBlank()) "owner" else "pm:${getenv("INFANTRY_SESSION_ID")}"
            val (type, req, answerType) = frame(verb, args, project, payload, purpose, by)
            val answer = stream.request(
                Envelope(type, json.encodeToString(McpRequest.serializer(), req)), answerType = answerType, what = type, idempotent = verb in READS,
            )
            if (jsonOut) out.appendLine(answer.data) else render(answerType, answer.data, out)
            0
        } catch (e: IllegalArgumentException) {
            err.appendLine("mcp $verb: ${e.message}"); 1
        } catch (e: Exception) {
            err.appendLine("mcp $verb failed: ${e.message?.take(300)}"); 1
        }
    }

    internal data class Built(val type: String, val req: McpRequest, val answer: String)

    internal fun frame(verb: String, a: List<String>, project: String?, payload: String?, purpose: String?, by: String): Built {
        fun target(i: Int) = a.getOrNull(i) ?: MCP_TARGET_INFANTRY
        return when (verb) {
            "list" -> Built(Envelope.MCP_LIST, McpRequest(target = target(1), projectId = project), Envelope.MCP_SERVERS)
            "get" -> Built(Envelope.MCP_GET, McpRequest(target = target(2), projectId = project, name = a[1]), Envelope.MCP_SERVER)
            "add", "edit" -> {
                val server = json.decodeFromString(McpServerInput.serializer(), payload!!)
                val type = if (verb == "add") Envelope.MCP_ADD else Envelope.MCP_UPDATE
                Built(type, McpRequest(target = a[1], projectId = project, scope = a.getOrNull(2), server = server), Envelope.MCP_SERVERS)
            }
            "remove" -> Built(Envelope.MCP_REMOVE, McpRequest(target = a[1], projectId = project, name = a[2], scope = a.getOrNull(3)), Envelope.MCP_SERVERS)
            "enable", "disable" ->
                Built(Envelope.MCP_SET_ENABLED, McpRequest(projectId = project, name = a[1], scope = a.getOrNull(2), enabled = verb == "enable"), Envelope.MCP_SERVERS)
            "test" -> Built(Envelope.MCP_TEST, McpRequest(target = a[1], projectId = project, name = a[2]), Envelope.MCP_STATUS)
            "grant" -> Built(
                Envelope.MCP_ASSIGN,
                McpRequest(grant = McpGrantInput(server = a[1], scope = a[2], target = a[3], projectId = project, purpose = purpose!!, approvedBy = by)),
                Envelope.MCP_GRANTS,
            )
            "revoke" -> Built(Envelope.MCP_REVOKE, McpRequest(grantId = a[1]), Envelope.MCP_GRANTS)
            else -> Built(Envelope.MCP_GRANTS, McpRequest(), Envelope.MCP_GRANTS)
        }
    }

    private fun render(answerType: String, data: String, out: Appendable) {
        when (answerType) {
            Envelope.MCP_SERVERS -> {
                val l = json.decodeFromString(McpListAnswer.serializer(), data)
                if (l.servers.isEmpty()) out.appendLine("no MCP servers")
                l.servers.forEach { out.appendLine(serverLine(it)) }
                l.targets.filter { it.note != null }.forEach { out.appendLine("note ${it.id}: ${it.note}") }
            }
            Envelope.MCP_SERVER -> {
                val s = json.decodeFromString(McpServerView.serializer(), data)
                out.appendLine(serverLine(s))
                val how = s.url ?: s.command.joinToString(" ")
                out.appendLine("  ${s.transport} $how".trimEnd())
                if (s.envKeys.isNotEmpty()) out.appendLine("  env: ${s.envKeys.joinToString(",")} (values hidden)")
                if (s.headerKeys.isNotEmpty()) out.appendLine("  headers: ${s.headerKeys.joinToString(",")} (values hidden)")
                s.detail?.let { out.appendLine("  $it") }
            }
            Envelope.MCP_STATUS -> {
                val s = json.decodeFromString(McpServerStatusView.serializer(), data)
                out.appendLine("${s.name} ${s.state}${s.toolCount?.let { " $it tools" } ?: ""}${s.detail?.let { " - $it" } ?: ""}")
            }
            else -> {
                val g = json.decodeFromString(ListSerializer(McpGrantView.serializer()), data)
                if (g.isEmpty()) out.appendLine("no grants (walled: sessions get only the infantry server)")
                g.forEach { out.appendLine(grantLine(it)) }
            }
        }
    }

    internal fun serverLine(s: McpServerView): String =
        "${s.name}  ${s.target}/${s.source}${s.scope?.let { ":$it" } ?: ""}  ${s.state}${if (s.enabled) "" else " (off)"}" +
            "${s.toolCount?.let { "  $it tools" } ?: ""}${s.shadowedBy?.let { "  shadowed by $it" } ?: ""}"

    internal fun grantLine(g: McpGrantView): String =
        "${g.id}  ${g.server} -> ${g.scope} ${g.target}${g.projectId?.let { " (project $it)" } ?: ""}  by ${g.approvedBy}: ${g.purpose}"
}
