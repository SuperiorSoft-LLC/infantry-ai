// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.protocol

import kotlinx.serialization.Serializable

/*
 * Contract 1.154.0 (MCP-REGISTRY-1, additive; design docs/design/MCP-TOOLS.md section 4.2): the MCP registry on the wire.
 * Frames (Envelope.MCP_*): `mcp_list` / `mcp_get` / `mcp_add` / `mcp_update` / `mcp_remove` / `mcp_set_enabled` / `mcp_test` /
 * `mcp_assign` / `mcp_revoke` / `mcp_grants` / `mcp_plan`, each carrying one [McpRequest]; the event `mcp_changed` after any change.
 * No secret ever crosses the wire: a server shows env and header KEYS only, and an Infantry-owned server names Secrets entries
 * ([McpServerInput.envSecrets], [McpServerInput.headerSecrets]) instead of holding values.
 */

/** Target name of Infantry's own servers (the daemon's server and the Infantry-owned ones) in an [McpRequest]; any other target is a harness id. */
const val MCP_TARGET_INFANTRY: String = "infantry"

/**
 * One MCP server as a client sees it. [source]: `infantry` | `infantry_user` | `harness` | `plugin`; [target]: [MCP_TARGET_INFANTRY]
 * or the harness id whose CLI lists it; [scope]: the CLI's scope label, or `app` / `project` for an Infantry-owned server.
 * [state]: `configured` | `connected` | `failed` | `needs_auth` | `pending_approval` | `disabled` | `unknown`.
 * [shadowedBy]: the source whose server of the same name wins (this one is never delivered); null = not shadowed.
 */
@Serializable
data class McpServerView(
    val name: String,
    val source: String,
    val target: String,
    val scope: String? = null,
    val transport: String,
    val command: List<String> = emptyList(),
    val url: String? = null,
    val envKeys: List<String> = emptyList(),
    val headerKeys: List<String> = emptyList(),
    val provides: List<String> = emptyList(),
    val localOnly: Boolean = false,
    val enabled: Boolean = true,
    val state: String = "unknown",
    val detail: String? = null,
    val toolCount: Int? = null,
    val shadowedBy: String? = null,
    val projectId: String? = null,
)

/** What a target can do (the screen greys out the rest); [listing] = its list is being read right now; [note] = why the list is old or missing. */
@Serializable
data class McpTargetView(
    val id: String,
    val canAdd: Boolean = false,
    val canRemove: Boolean = false,
    val canEdit: Boolean = false,
    val scopes: List<String> = emptyList(),
    val listing: Boolean = false,
    val note: String? = null,
)

/**
 * One grant: the only way an agent gets a server beyond the daemon's own (the walled default). [scope]: `role` | `agent` | `ticket`;
 * [target]: that role id, agent id or ticket id; [projectId] null = every project. [purpose] says why, [approvedBy] who said yes and
 * [approvedAt] when (epoch millis).
 */
@Serializable
data class McpGrantView(
    val id: String,
    val server: String,
    val scope: String,
    val target: String,
    val projectId: String? = null,
    val purpose: String,
    val approvedBy: String,
    val approvedAt: Long,
)

/** The `mcp_assign` payload's grant: [McpGrantView] without the daemon's id and time. */
@Serializable
data class McpGrantInput(
    val server: String,
    val scope: String,
    val target: String,
    val projectId: String? = null,
    val purpose: String,
    val approvedBy: String,
)

/**
 * An Infantry-owned server as stored and as `mcp_add` / `mcp_update` carry it: a stdio [command] (with [workingDir]) or an http [url].
 * [env] / [headers] hold plain, non-secret values only; a secret goes in the Secrets store and is named in [envSecrets] /
 * [headerSecrets] (key -> secret name). A plain value under a key that reads like a credential is refused.
 */
@Serializable
data class McpServerInput(
    val name: String,
    val command: List<String> = emptyList(),
    val workingDir: String? = null,
    val url: String? = null,
    val env: Map<String, String> = emptyMap(),
    val envSecrets: Map<String, String> = emptyMap(),
    val headers: Map<String, String> = emptyMap(),
    val headerSecrets: Map<String, String> = emptyMap(),
    val provides: List<String> = emptyList(),
    val localOnly: Boolean = false,
    val enabled: Boolean = true,
)

/**
 * The payload of every `mcp_*` frame; each frame reads the fields it needs. [target]: [MCP_TARGET_INFANTRY] or a harness id;
 * [projectId]: the project whose servers (and project-scoped Infantry servers) are meant; [scope]: the CLI scope, or `app` / `project`
 * for an Infantry-owned server. `mcp_plan` explains what one session would get: [sessionId], or [role] / [agentId] / [ticketId] /
 * [remote] with [target] as its harness.
 */
@Serializable
data class McpRequest(
    val target: String = MCP_TARGET_INFANTRY,
    val projectId: String? = null,
    val scope: String? = null,
    val name: String? = null,
    val server: McpServerInput? = null,
    val enabled: Boolean? = null,
    val grant: McpGrantInput? = null,
    val grantId: String? = null,
    val sessionId: String? = null,
    val role: String? = null,
    val agentId: String? = null,
    val ticketId: String? = null,
    val remote: Boolean = false,
) {
    companion object {
        fun decode(text: String): McpRequest = StrictJson.decode(serializer(), text.ifBlank { "{}" }, "mcp request payload")
    }
}

/** `mcp_servers`: the answer to `mcp_list` and to every change. */
@Serializable
data class McpListAnswer(
    val servers: List<McpServerView> = emptyList(),
    val targets: List<McpTargetView> = emptyList(),
    val grants: List<McpGrantView> = emptyList(),
)

/** One server's state on a session (`Session.mcpServers`) or as `mcp_test` answers it; [state] as on [McpServerView]. */
@Serializable
data class McpServerStatusView(
    val name: String,
    val state: String,
    val detail: String? = null,
    val toolCount: Int? = null,
)

/** `mcp_plan`: the servers a session gets ([add]), the CLI's own ones it is told to skip ([deny]) and one reason per server name. */
@Serializable
data class McpPlanView(
    val add: List<String> = emptyList(),
    val deny: List<String> = emptyList(),
    val reasons: Map<String, String> = emptyMap(),
    /** MCP-STATUS-1: server name -> live state for the asking session (empty until its harness reported). */
    val status: Map<String, String> = emptyMap(),
)
