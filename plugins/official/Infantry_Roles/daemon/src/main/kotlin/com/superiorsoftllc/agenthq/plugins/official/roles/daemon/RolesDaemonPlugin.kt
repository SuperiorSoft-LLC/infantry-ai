package com.superiorsoftllc.agenthq.plugins.official.roles.daemon

import com.superiorsoftllc.agenthq.daemon.api.DaemonApi
import com.superiorsoftllc.agenthq.daemon.api.DaemonPlugin
import com.superiorsoftllc.agenthq.daemon.api.DaemonTool
import com.superiorsoftllc.agenthq.daemon.api.ToolCall
import com.superiorsoftllc.agenthq.daemon.api.ToolResult
import com.superiorsoftllc.agenthq.daemon.api.roles.RetiredRoleIds
import com.superiorsoftllc.agenthq.daemon.api.roles.RoleBindings
import com.superiorsoftllc.agenthq.daemon.api.roles.RoleTraitLists
import com.superiorsoftllc.agenthq.daemon.api.roles.Roles
import com.superiorsoftllc.agenthq.daemon.api.roles.RoleRecord
import com.superiorsoftllc.agenthq.daemon.api.roles.RoleSaveRequest
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put

/**
 * RUNTIME-LOAD-3 (docs/RUNTIME-LOAD.md, F3): Infantry_Roles' daemon half. ROLE-EDIT-1's five role tools were built into the CLI's
 * MCP server; now each sends the daemon's own `role_list` / `role_save` / `role_delete` frames on the caller's connection. Same
 * names, schemas, answers and gating (`roles.read`, `roles.save`, `roles.delete`).
 *
 * Who may do what: a caller that is an AGENT (the MCP process runs in a session: `AGENTHQ_SESSION_ID` or `AGENTHQ_ROLE` is set,
 * which is every agent the daemon spawns, the PM included; or the connection holds the agent token) may read roles and update the
 * persona and description of an EXISTING role, and nothing else. Creating, renaming, recolouring and deleting need a caller that is
 * not an agent. Bindings and traits are not exposed here at all.
 */
class RolesDaemonPlugin : DaemonPlugin {
    override fun start(api: DaemonApi) {
        // AR-4b-ii-b: the catalog, its frames and the role binding / trait stores are this plugin's; core reads them through the ports.
        val service = RoleService(RolesFrames.rolesDir(api))
        api.services.provide(Roles::class, ServiceRoles(service))
        api.services.provide(RoleBindings::class, StorageRoleBindings(api.storage))
        api.services.provide(RoleTraitLists::class, StorageRoleTraitLists(api.storage))
        RolesFrames.register(api, service)
        listOf(RoleListTool, RoleGetTool, RoleUpdateTool, RoleCreateTool, RoleDeleteTool, SkillListTool, SkillSaveTool, SkillDeleteTool).forEach { api.tools.register(it) }
    }
}

private val json = Json { ignoreUnknownKeys = true }
private const val ANSWER_TIMEOUT_MS = 45_000L

private fun ToolCall.callerIsAgent(): Boolean = caller.agent || clientSessionId != null || clientRole != null

private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull

private fun refused(text: String) = ToolResult(text, isError = true)

/** The roles the daemon knows, or null when the frame fails. */
private fun ToolCall.fetchAll(): List<RoleRecord>? = try {
    val reply = request("role_list", "", timeoutMs = ANSWER_TIMEOUT_MS)
    if (reply.type == "roles") json.decodeFromString(ListSerializer(RoleRecord.serializer()), reply.data) else null
} catch (e: Exception) {
    null
}

private fun ToolCall.save(what: RoleSaveRequest): ToolResult = try {
    val reply = request("role_save", json.encodeToString(RoleSaveRequest.serializer(), what), timeoutMs = ANSWER_TIMEOUT_MS)
    when (reply.type) {
        "role" -> {
            val saved = json.decodeFromString(RoleRecord.serializer(), reply.data)
            ToolResult("saved role '${saved.id}' (${saved.persona.length} persona characters); it applies to new sessions of the role, not running ones")
        }
        "error" -> refused(reply.data.ifBlank { "role_save failed" })
        else -> refused("role_save: unexpected answer ${reply.type}")
    }
} catch (e: Exception) {
    refused(e.message ?: "role_save failed")
}

/** A tool's `inputSchema` and `annotations`, shaped exactly as the in-core descriptors were. */
private fun shape(
    readOnly: Boolean, destructive: Boolean, props: List<Pair<String, String>>, required: List<String>,
): Pair<JsonObject, JsonObject> = buildJsonObject {
    put("type", "object")
    put("properties", buildJsonObject {
        props.forEach { (key, text) -> put(key, buildJsonObject { put("type", "string"); put("description", text) }) }
    })
    put("required", buildJsonArray { required.forEach { add(JsonPrimitive(it)) } })
    put("additionalProperties", false)
} to buildJsonObject {
    put("readOnlyHint", readOnly)
    put("destructiveHint", destructive)
    put("idempotentHint", readOnly)
    put("openWorldHint", false)
}

private val ID = "id" to "The role id, e.g. automation."
private val PERSONA = "persona" to "The role notes: the instructions every NEW session of this role receives at start. Replaces the stored text."
private val DESCRIPTION = "description" to "One line describing the role (at most 200 characters); shown in the role editor, never sent to a session."

object RoleListTool : DaemonTool {
    const val NAME = "agenthq_role_list"
    private val shape = shape(true, false, emptyList(), emptyList())
    override val name = NAME
    override val description = "List every role: id, name, description, source and persona size."
    override val inputSchema = shape.first
    override val annotations = shape.second

    override fun call(call: ToolCall): ToolResult {
        val roles = call.fetchAll() ?: return refused("$NAME failed")
        return ToolResult(json.encodeToString(JsonArray.serializer(), buildJsonArray {
            roles.forEach { role ->
                add(buildJsonObject {
                    put("id", role.id)
                    put("name", role.name)
                    put("description", role.description)
                    put("source", role.source)
                    put("personaChars", role.persona.length)
                })
            }
        }))
    }
}

object RoleGetTool : DaemonTool {
    const val NAME = "agenthq_role_get"
    private val shape = shape(true, false, listOf(ID), listOf("id"))
    override val name = NAME
    override val description = "Read one role in full, including its persona (the role notes)."
    override val inputSchema = shape.first
    override val annotations = shape.second

    override fun call(call: ToolCall): ToolResult {
        val id = call.arguments.string("id") ?: return refused("$NAME needs an id")
        val role = call.fetchAll()?.firstOrNull { it.id == RetiredRoleIds.normalize(id) } ?: return refused("$NAME: unknown role '$id'")
        return ToolResult(json.encodeToString(RoleRecord.serializer(), role))
    }
}

object RoleUpdateTool : DaemonTool {
    const val NAME = "agenthq_role_update"
    private val shape = shape(
        false, false,
        listOf(ID, PERSONA, DESCRIPTION, "name" to "New display name (non-agent caller only).", "color" to "#rrggbb (non-agent caller only).", "icon" to "Icon key (non-agent caller only)."),
        listOf("id"),
    )
    override val name = NAME
    override val description =
        "Update an existing role's persona (role notes) and/or description. Applies to new sessions only. An agent caller may change " +
            "nothing else; a non-agent caller may also rename it (name), recolour it (color #rrggbb) or set its icon."
    override val inputSchema = shape.first
    override val annotations = shape.second

    override fun call(call: ToolCall): ToolResult {
        val arguments = call.arguments
        val id = RetiredRoleIds.normalize(arguments.string("id") ?: return refused("$NAME needs an id"))
        val existing = call.fetchAll()?.firstOrNull { it.id == id } ?: return refused("$NAME: unknown role '$id'; ${RoleCreateTool.NAME} makes a new one")
        val restricted = listOf("name", "color", "icon").filter { arguments.string(it) != null }
        if (call.callerIsAgent() && restricted.isNotEmpty()) {
            return refused("$NAME: an agent may change only persona and description; ${restricted.joinToString()} need a non-agent caller")
        }
        if (arguments.string("persona") == null && arguments.string("description") == null && restricted.isEmpty()) {
            return refused("$NAME needs persona and/or description")
        }
        // role_save replaces the persona unless one is sent, so an unnamed field is carried over from the stored role.
        return call.save(RoleSaveRequest(
            id = id,
            name = arguments.string("name") ?: existing.name,
            color = arguments.string("color"),
            persona = arguments.string("persona") ?: existing.persona,
            icon = arguments.string("icon"),
            description = arguments.string("description"),
        ))
    }
}

object RoleCreateTool : DaemonTool {
    const val NAME = "agenthq_role_create"
    private val shape = shape(false, false, listOf(ID, "name" to "Display name.", PERSONA, DESCRIPTION, "color" to "#rrggbb", "icon" to "Icon key"), listOf("id", "name"))
    override val name = NAME
    override val description = "Create a role (non-agent caller only)."
    override val inputSchema = shape.first
    override val annotations = shape.second

    override fun call(call: ToolCall): ToolResult {
        val arguments = call.arguments
        if (call.callerIsAgent()) return refused("$NAME needs a non-agent caller: an agent may only update the persona and description of an existing role")
        val id = arguments.string("id") ?: return refused("$NAME needs an id")
        if (call.fetchAll()?.any { it.id == RetiredRoleIds.normalize(id) } == true) return refused("$NAME: role '$id' already exists; use ${RoleUpdateTool.NAME}")
        return call.save(RoleSaveRequest(
            id = id,
            name = arguments.string("name") ?: return refused("$NAME needs a name"),
            color = arguments.string("color"),
            persona = arguments.string("persona").orEmpty(),
            icon = arguments.string("icon"),
            description = arguments.string("description"),
        ))
    }
}

object RoleDeleteTool : DaemonTool {
    const val NAME = "agenthq_role_delete"
    private val shape = shape(false, true, listOf(ID), listOf("id"))
    override val name = NAME
    override val description =
        "Delete a custom role, or reset a customised bundled role to its bundled definition (non-agent caller only). Refused while a live session has the role."
    override val inputSchema = shape.first
    override val annotations = shape.second

    override fun call(call: ToolCall): ToolResult {
        if (call.callerIsAgent()) return refused("$NAME needs a non-agent caller: an agent may not delete roles")
        val id = RetiredRoleIds.normalize(call.arguments.string("id") ?: return refused("$NAME needs an id"))
        return try {
            val reply = call.request("role_delete", id, timeoutMs = ANSWER_TIMEOUT_MS)
            // A custom override of a bundled role is reset (answered by `role`); a pure custom role is removed (`role_removed`).
            when (reply.type) {
                "role" -> ToolResult("reset role '$id' to its bundled definition")
                "role_removed" -> ToolResult("deleted role '${reply.data}'")
                "error" -> refused(reply.data.ifBlank { "role_delete failed" })
                else -> refused("role_delete: unexpected answer ${reply.type}")
            }
        } catch (e: Exception) {
            refused(e.message ?: "role_delete failed")
        }
    }
}

// SKILLS-GLOBAL-1b: the three skill layers (global / project / role) over the daemon's layer_skill_* frames.
private val SCOPE = "scope" to "global, project or role."
private val SKILL_NAME = "name" to "The skill name."
private val SKILL_PROJECT = "projectId" to "Project id for project/role scope (default: the caller's project)."
private val SKILL_ROLE = "role" to "Role id for role scope (default: the caller's role)."

private fun ToolCall.skillRequest(op: String, extra: Map<String, String?> = emptyMap()): ToolResult = try {
    val a = arguments
    val data = buildJsonObject {
        put("scope", a.string("scope") ?: "global")
        (a.string("projectId") ?: clientProjectId)?.let { put("projectId", it) }
        (a.string("role") ?: clientRole)?.let { put("role", it) }
        extra.forEach { (k, v) -> if (v != null) put(k, v) }
    }.toString()
    val reply = request("layer_skill_$op", data, timeoutMs = ANSWER_TIMEOUT_MS)
    when (reply.type) {
        "layer_skills" -> ToolResult(reply.data)
        "error" -> refused(reply.data.ifBlank { "layer_skill_$op failed" })
        else -> refused("layer_skill_$op: unexpected answer ${reply.type}")
    }
} catch (e: Exception) {
    refused(e.message ?: "layer_skill_$op failed")
}

object SkillListTool : DaemonTool {
    const val NAME = "agenthq_skill_list"
    private val shape = shape(true, false, listOf(SCOPE, SKILL_PROJECT, SKILL_ROLE), listOf("scope"))
    override val name = NAME
    override val description = "List the skills of one layer (global, project or role) in full."
    override val inputSchema = shape.first
    override val annotations = shape.second
    override fun call(call: ToolCall) = call.skillRequest("list")
}

object SkillSaveTool : DaemonTool {
    const val NAME = "agenthq_skill_save"
    private val shape = shape(false, false, listOf(SCOPE, SKILL_NAME, "description" to "One line.", "body" to "The skill text.", SKILL_PROJECT, SKILL_ROLE), listOf("scope", "name", "body"))
    override val name = NAME
    override val description = "Create or replace a skill (by name) in one layer. A narrower layer overrides a wider one of the same name."
    override val inputSchema = shape.first
    override val annotations = shape.second
    override fun call(call: ToolCall): ToolResult {
        val a = call.arguments
        return call.skillRequest("save", mapOf("name" to a.string("name"), "description" to a.string("description"), "body" to a.string("body")))
    }
}

object SkillDeleteTool : DaemonTool {
    const val NAME = "agenthq_skill_delete"
    private val shape = shape(false, true, listOf(SCOPE, SKILL_NAME, SKILL_PROJECT, SKILL_ROLE), listOf("scope", "name"))
    override val name = NAME
    override val description = "Delete a skill from one layer."
    override val inputSchema = shape.first
    override val annotations = shape.second
    override fun call(call: ToolCall) = call.skillRequest("delete", mapOf("name" to call.arguments.string("name")))
}
