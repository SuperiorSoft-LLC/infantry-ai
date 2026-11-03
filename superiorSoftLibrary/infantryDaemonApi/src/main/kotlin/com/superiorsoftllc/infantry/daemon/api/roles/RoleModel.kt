// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.daemon.api.roles

import kotlinx.serialization.Serializable

/**
 * RUNTIME-LOAD-3: the wire shape of a role (`roles` / `role` frames) and of `role_save`, for the Infantry_Roles daemon half.
 * Field order and defaults equal the daemon's own `Role` / `RoleSave`, so a role printed through the default `Json` (defaults
 * left out) is byte-for-byte what the old in-core MCP tool printed. [source] is the daemon's `RoleSource` name.
 */
@Serializable
data class RoleRecord(
    val id: String,
    val name: String,
    val color: String,
    val persona: String = "",
    val source: String = "BUILT_IN",
    val icon: String = "",
    val description: String = "",
    /** TOKEN-1 (1.32.0, additive): the role's MCP response-size fields; blank / null = the project / global limits. */
    val responseDetail: String = "",
    val responseMaxChars: Int? = null,
    val responseBodyChars: Int? = null,
)

/**
 * AR-4b (DaemonApi 1.32.0, additive; docs/DAEMON-CORE.md section 8): the read-only role catalog port, a service in the AR-5
 * registry (`services.find(Roles::class)`). Core registers a local provider over its role service; the Infantry_Roles daemon half
 * takes it over when the role store moves out. Consumers own the absent-provider case: no provider = no roles, so a session
 * spawns with no persona.
 */
interface Roles {
    /** Every role in the catalog's stable order. */
    fun all(): List<RoleRecord>

    /** One role by id, or null when unknown. */
    fun role(id: String): RoleRecord?
}

@Serializable
data class RoleSaveRequest(
    val id: String,
    val name: String,
    val color: String? = null,
    val persona: String = "",
    val icon: String? = null,
    val description: String? = null,
)

/** Role ids renamed by the id redraw (contract 1.90.0): the one table, read by the daemon's `RoleIds` and the roles tools. */
object RetiredRoleIds {
    val MAP: Map<String, String> = com.superiorsoftllc.infantry.protocol.RoleIds.RETIRED

    /** [id] under its current name. */
    fun normalize(id: String): String = MAP[id] ?: id
}
