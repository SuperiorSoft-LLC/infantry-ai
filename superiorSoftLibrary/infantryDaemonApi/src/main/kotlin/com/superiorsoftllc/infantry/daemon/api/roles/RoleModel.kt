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
    /**
     * ROLE-PLUGIN-1 (1.58.0, additive): the module that declared the role in its module.json `roles` ("" = none), whether that
     * module is not loaded now ([disabled]), whether the owner edited the seeded role ([edited]: the module never rewrites it), and
     * the card types / tool names the role prefers, by id ([cards], [tools]; they shape the briefing, never gate).
     */
    val plugin: String = "",
    val disabled: Boolean = false,
    val edited: Boolean = false,
    val cards: List<String> = emptyList(),
    val tools: List<String> = emptyList(),
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

    /**
     * ROLE-PLUGIN-1 (1.58.0, additive): the text a NEW session of role [id] receives at start: the persona, plus whatever the
     * provider adds from the role's preferences (a hybrid role's preferred tools and cards that are loaded now). Null = none.
     */
    fun briefing(id: String): String? = role(id)?.persona
}

@Serializable
data class RoleSaveRequest(
    val id: String,
    val name: String,
    val color: String? = null,
    val persona: String = "",
    val icon: String? = null,
    val description: String? = null,
    /** ROLE-PLUGIN-1 (1.58.0): preferred card types / tool names; null = keep the stored list, empty = clear it. */
    val cards: List<String>? = null,
    val tools: List<String>? = null,
)

/** Role ids renamed by the id redraw (contract 1.90.0): the one table, read by the daemon's `RoleIds` and the roles tools. */
object RetiredRoleIds {
    val MAP: Map<String, String> = com.superiorsoftllc.infantry.protocol.RoleIds.RETIRED

    /** [id] under its current name. */
    fun normalize(id: String): String = MAP[id] ?: id
}
