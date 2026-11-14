// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.mcp

import com.superiorsoftllc.infantry.harness.api.HarnessMcp
import com.superiorsoftllc.infantry.harness.api.McpServerSpec
import com.superiorsoftllc.infantry.harness.api.McpServerStatus
import com.superiorsoftllc.infantry.harness.api.McpSource
import com.superiorsoftllc.infantry.protocol.Envelope
import com.superiorsoftllc.infantry.protocol.MCP_TARGET_INFANTRY
import com.superiorsoftllc.infantry.protocol.McpGrantView
import com.superiorsoftllc.infantry.protocol.McpListAnswer
import com.superiorsoftllc.infantry.protocol.McpRequest
import com.superiorsoftllc.infantry.protocol.McpServerInput
import com.superiorsoftllc.infantry.protocol.McpServerStatusView
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/**
 * MCP-REGISTRY-1: the ONE backend of the `mcp_*` frames (the MCP Tools screen, the `infantry mcp` CLI and the agent tool all send them).
 * [handle] answers one frame; [McpAnswer.changed] tells the daemon to push `mcp_changed` to every client. A CLI target is routed to
 * that harness's [HarnessMcp] (its official commands); what the CLI cannot do is refused. [plan] is the walled selection for a session.
 */
class McpService(
    val registry: McpRegistry,
    val owned: McpOwnedServers,
    val grants: McpGrants,
    private val harnessMcp: (String) -> HarnessMcp?,
    private val loadsUserMcp: (String) -> Boolean,
    /** A Secrets entry's value (secret name, project id), the same lookup the registry uses; for a server added to a CLI's own config. */
    private val secret: (String, String?) -> String?,
    /** A live session as the selection sees it, or null for an unknown id. */
    private val sessionContext: (String) -> McpSessionContext? = { null },
    /** The daemon's own server for a session context (the infantry entry of its plan). */
    private val infantryFor: (McpSessionContext) -> McpServerSpec? = { null },
) {
    /** One frame's answer: its [type] and JSON [data]; [changed] = something was written, push `mcp_changed`. */
    data class McpAnswer(val type: String, val data: String, val changed: Boolean = false)

    /** Answer the `mcp_*` frame [type] whose payload is [data]. A refusal throws IllegalArgumentException with the reason. */
    fun handle(type: String, data: String): McpAnswer {
        val r = McpRequest.decode(data)
        return when (type) {
            Envelope.MCP_LIST -> list(r)
            Envelope.MCP_GET -> {
                val name = need(r.name, "name")
                val e = registry.find(name, r.projectId, r.target.takeIf { it != MCP_TARGET_INFANTRY })
                    ?: throw IllegalArgumentException("no MCP server named '$name'")
                McpAnswer(Envelope.MCP_SERVER, JSON.encodeToString(com.superiorsoftllc.infantry.protocol.McpServerView.serializer(), e.view()))
            }
            Envelope.MCP_ADD -> { add(r); list(r, changed = true) }
            Envelope.MCP_UPDATE -> { update(r); list(r, changed = true) }
            Envelope.MCP_REMOVE -> { remove(r); list(r, changed = true) }
            Envelope.MCP_SET_ENABLED -> {
                require(r.target == MCP_TARGET_INFANTRY) {
                    "a CLI's own server is kept from Infantry sessions by having no grant (walled by default); its CLI config is not changed"
                }
                val name = need(r.name, "name")
                val enabled = r.enabled ?: throw IllegalArgumentException("expects enabled (true or false)")
                require(owned.setEnabled(name, ownedProject(r), enabled)) { "no Infantry MCP server named '$name' in ${scopeText(r)}" }
                list(r, changed = true)
            }
            Envelope.MCP_TEST -> McpAnswer(Envelope.MCP_STATUS, JSON.encodeToString(McpServerStatusView.serializer(), test(r)))
            Envelope.MCP_ASSIGN -> {
                grants.grant(r.grant ?: throw IllegalArgumentException("expects a grant {server, scope, target, purpose, approvedBy}"))
                grantsAnswer(changed = true)
            }
            Envelope.MCP_DECLARE -> {
                val d = r.declare ?: throw IllegalArgumentException("expects declare {scope, target, servers, purpose}")
                McpDeclarations(grants).declare(
                    McpGrantScope.parse(d.scope), d.target, d.projectId, McpBlock(d.servers, d.purpose), McpDeclarer(d.declaredBy.ifBlank { "owner" }),
                )
                grantsAnswer(changed = true)
            }
            Envelope.MCP_REVOKE -> {
                val id = need(r.grantId, "grantId")
                require(grants.revoke(id)) { "no MCP grant '$id'" }
                grantsAnswer(changed = true)
            }
            Envelope.MCP_GRANTS -> grantsAnswer()
            Envelope.MCP_PLAN -> {
                val ctx = r.sessionId?.let { sessionContext(it) ?: throw IllegalArgumentException("unknown session '$it'") }
                    ?: McpSessionContext(
                        harnessId = r.target.takeIf { it != MCP_TARGET_INFANTRY } ?: throw IllegalArgumentException("expects a sessionId, or a harness id as target"),
                        projectId = r.projectId, role = r.role, agentId = r.agentId, ticketId = r.ticketId, remote = r.remote,
                    )
                McpAnswer(Envelope.MCP_PLAN, JSON.encodeToString(com.superiorsoftllc.infantry.protocol.McpPlanView.serializer(), plan(ctx).view()))
            }
            else -> throw IllegalArgumentException("not an MCP frame: $type")
        }
    }

    /** The walled selection for [ctx]: what its harness is handed ([com.superiorsoftllc.infantry.harness.api.McpPlan]) and why. */
    fun plan(ctx: McpSessionContext, infantry: McpServerSpec? = infantryFor(ctx)): McpSelectionResult =
        McpSelection.select(registry.catalog(ctx.projectId, ctx.harnessId).entries, grants.list(), ctx, infantry, loadsUserMcp(ctx.harnessId))

    /** [plan] for the session whose environment is [sessionEnv] (its session id); null when the env names no known session. */
    fun planForEnv(sessionEnv: Map<String, String>, infantry: McpServerSpec?): McpSelectionResult? =
        sessionEnv[SESSION_ID_ENV]?.let(sessionContext)?.let { plan(it, infantry) }

    private fun list(r: McpRequest, changed: Boolean = false): McpAnswer {
        val harness = r.target.takeIf { it != MCP_TARGET_INFANTRY }
        val catalog = registry.catalog(r.projectId, harness)
        val answer = McpListAnswer(catalog.entries.map { it.view() }, catalog.targets, grants.list())
        return McpAnswer(Envelope.MCP_SERVERS, JSON.encodeToString(McpListAnswer.serializer(), answer), changed)
    }

    private fun grantsAnswer(changed: Boolean = false) =
        McpAnswer(Envelope.MCP_GRANTS, JSON.encodeToString(ListSerializer(McpGrantView.serializer()), grants.list()), changed)

    private fun add(r: McpRequest) {
        val server = r.server ?: throw IllegalArgumentException("expects a server")
        if (r.target == MCP_TARGET_INFANTRY) { owned.put(server, ownedProject(r)); return }
        val mcp = cli(r.target)
        require(mcp.canAdd) { "${r.target} cannot add MCP servers through its CLI" }
        val scope = cliScope(mcp, r)
        val spec = cliSpec(server, r.projectId)
        runBlocking { mcp.add(registry.dirOf(r.projectId), spec, scope) }.getOrElse { throw IllegalArgumentException("${r.target} refused the add: ${it.message}") }
        registry.invalidate()
    }

    private fun update(r: McpRequest) {
        val server = r.server ?: throw IllegalArgumentException("expects a server")
        if (r.target == MCP_TARGET_INFANTRY) { owned.put(server, ownedProject(r), replace = true); return }
        val mcp = cli(r.target)
        require(mcp.canEdit && mcp.canRemove && mcp.canAdd) { "${r.target} cannot edit MCP servers through its CLI" }
        val scope = cliScope(mcp, r)
        val spec = cliSpec(server, r.projectId)
        val dir = registry.dirOf(r.projectId)
        runBlocking {
            mcp.remove(dir, server.name, scope).getOrElse { throw IllegalArgumentException("${r.target} refused the remove: ${it.message}") }
            mcp.add(dir, spec, scope).getOrElse { throw IllegalArgumentException("${r.target} refused the add: ${it.message}") }
        }
        registry.invalidate()
    }

    private fun remove(r: McpRequest) {
        val name = need(r.name, "name")
        if (r.target == MCP_TARGET_INFANTRY) {
            require(owned.remove(name, ownedProject(r))) { "no Infantry MCP server named '$name' in ${scopeText(r)}" }
            return
        }
        val mcp = cli(r.target)
        require(mcp.canRemove) { "${r.target} cannot remove MCP servers through its CLI" }
        runBlocking { mcp.remove(registry.dirOf(r.projectId), name, r.scope) }.getOrElse { throw IllegalArgumentException("${r.target} refused the remove: ${it.message}") }
        registry.invalidate()
    }

    private fun test(r: McpRequest): McpServerStatusView {
        val name = need(r.name, "name")
        val status: McpServerStatus = if (r.target == MCP_TARGET_INFANTRY) {
            registry.find(name, r.projectId, MCP_TARGET_INFANTRY)?.status ?: throw IllegalArgumentException("no Infantry MCP server named '$name'")
        } else {
            runBlocking { cli(r.target).test(registry.dirOf(r.projectId), name) }
        }
        return McpServerStatusView(status.name, status.state.name.lowercase(), status.detail, status.toolCount)
    }

    private fun cli(target: String): HarnessMcp = harnessMcp(target) ?: throw IllegalArgumentException("'$target' has no MCP management (unknown harness, or its CLI's servers are not visible)")

    private fun cliScope(mcp: HarnessMcp, r: McpRequest): String {
        val scope = r.scope?.trim()?.takeIf { it.isNotEmpty() } ?: mcp.scopes.firstOrNull() ?: ""
        require(mcp.scopes.isEmpty() || scope in mcp.scopes) { "${r.target} scopes are ${mcp.scopes.joinToString()}; got '$scope'" }
        return scope
    }

    /** A server for a CLI's own config: validated like an Infantry one; its secret refs resolved, because the CLI stores the values itself. */
    private fun cliSpec(server: McpServerInput, projectId: String?): McpServerSpec {
        McpOwnedServers.validate(server)
        val spec = McpOwnedServers.toSpec(OwnedServer(server, projectId), secret).getOrElse { throw IllegalArgumentException(it.message) }
        return spec.copy(source = McpSource.HARNESS, scope = null)
    }

    private fun ownedProject(r: McpRequest): String? = when (r.scope?.trim()?.lowercase()) {
        null, "", McpRegistry.SCOPE_APP -> null
        McpRegistry.SCOPE_PROJECT -> r.projectId?.takeIf { it.isNotBlank() } ?: throw IllegalArgumentException("scope project needs a projectId")
        else -> throw IllegalArgumentException("an Infantry MCP server's scope is app or project; got '${r.scope}'")
    }

    private fun scopeText(r: McpRequest) = ownedProject(r)?.let { "project $it" } ?: "the app scope"

    private fun need(v: String?, what: String): String = v?.trim()?.takeIf { it.isNotEmpty() } ?: throw IllegalArgumentException("expects $what")

    companion object {
        private val JSON = Json { encodeDefaults = false }
        private const val SESSION_ID_ENV = com.superiorsoftllc.infantry.daemon.AccessPolicy.SESSION_ID_ENV

        /** The daemon's one service (set at start, like the health service); null in a daemon without it. */
        @Volatile
        var current: McpService? = null
    }
}
