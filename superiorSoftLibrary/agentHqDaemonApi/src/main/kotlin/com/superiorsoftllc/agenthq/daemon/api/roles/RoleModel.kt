package com.superiorsoftllc.agenthq.daemon.api.roles

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
)

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
    val MAP: Map<String, String> = mapOf(
        "git" to "version-control",
        "i18n" to "localization",
        "brainstormer" to "ideation",
        "bot" to "automation",
        "chief-of-staff" to "assistant",
    )

    /** [id] under its current name. */
    fun normalize(id: String): String = MAP[id] ?: id
}
