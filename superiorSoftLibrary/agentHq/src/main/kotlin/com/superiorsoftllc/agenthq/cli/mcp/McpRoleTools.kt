package com.superiorsoftllc.agenthq.cli.mcp

import com.superiorsoftllc.agenthq.cli.FrameStream
import com.superiorsoftllc.agenthq.daemon.AccessPolicy
import com.superiorsoftllc.agenthq.protocol.Envelope
import com.superiorsoftllc.agenthq.protocol.RoleSave
import com.superiorsoftllc.agenthq.role.Role
import com.superiorsoftllc.agenthq.role.RoleIds
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put

/**
 * ROLE-EDIT-1: the role catalog over MCP, on the daemon's own `role_list` / `role_save` / `role_delete` frames.
 *
 * Who may do what: a caller that is an AGENT (a session: `AGENTHQ_SESSION_ID` or `AGENTHQ_ROLE` is set, which is every
 * agent the daemon spawns, the PM included) may read roles and update the persona (the role notes) and description of an
 * EXISTING role, and nothing else. Creating a role, renaming it, recolouring it and deleting it need a caller that is not
 * an agent (a person running `agenthq mcp serve`). Bindings and traits are not exposed here at all.
 */
internal class McpRoleTools(private val getenv: (String) -> String?) {

    private val callerIsAgent: Boolean
        get() = listOf(AccessPolicy.SESSION_ID_ENV, "AGENTHQ_ROLE").any { !getenv(it).isNullOrBlank() }

    suspend fun list(stream: FrameStream): ToolResult {
        val roles = fetchAll(stream) ?: return refused("$LIST failed")
        return ToolResult(json.encodeToString(kotlinx.serialization.json.JsonArray.serializer(), buildJsonArray {
            roles.forEach { role ->
                add(buildJsonObject {
                    put("id", role.id)
                    put("name", role.name)
                    put("description", role.description)
                    put("source", role.source.name)
                    put("personaChars", role.persona.length)
                })
            }
        }))
    }

    suspend fun get(stream: FrameStream, arguments: JsonObject): ToolResult {
        val id = arguments.string("id") ?: return refused("$GET needs an id")
        val role = fetchAll(stream)?.firstOrNull { it.id == RoleIds.normalize(id) } ?: return refused("$GET: unknown role '$id'")
        return ToolResult(json.encodeToString(Role.serializer(), role))
    }

    suspend fun update(stream: FrameStream, arguments: JsonObject): ToolResult {
        val id = RoleIds.normalize(arguments.string("id") ?: return refused("$UPDATE needs an id"))
        val existing = fetchAll(stream)?.firstOrNull { it.id == id } ?: return refused("$UPDATE: unknown role '$id'; $CREATE makes a new one")
        val restricted = listOf("name", "color", "icon").filter { arguments.string(it) != null }
        if (callerIsAgent && restricted.isNotEmpty()) {
            return refused("$UPDATE: an agent may change only persona and description; ${restricted.joinToString()} need a non-agent caller")
        }
        if (arguments.string("persona") == null && arguments.string("description") == null && restricted.isEmpty()) {
            return refused("$UPDATE needs persona and/or description")
        }
        // role_save replaces the persona unless one is sent, so an unnamed field is carried over from the stored role.
        return save(stream, RoleSave(
            id = id,
            name = arguments.string("name") ?: existing.name,
            color = arguments.string("color"),
            persona = arguments.string("persona") ?: existing.persona,
            icon = arguments.string("icon"),
            description = arguments.string("description"),
        ))
    }

    suspend fun create(stream: FrameStream, arguments: JsonObject): ToolResult {
        if (callerIsAgent) return refused("$CREATE needs a non-agent caller: an agent may only update the persona and description of an existing role")
        val id = arguments.string("id") ?: return refused("$CREATE needs an id")
        if (fetchAll(stream)?.any { it.id == RoleIds.normalize(id) } == true) return refused("$CREATE: role '$id' already exists; use $UPDATE")
        return save(stream, RoleSave(
            id = id,
            name = arguments.string("name") ?: return refused("$CREATE needs a name"),
            color = arguments.string("color"),
            persona = arguments.string("persona").orEmpty(),
            icon = arguments.string("icon"),
            description = arguments.string("description"),
        ))
    }

    suspend fun delete(stream: FrameStream, arguments: JsonObject): ToolResult {
        if (callerIsAgent) return refused("$DELETE needs a non-agent caller: an agent may not delete roles")
        val id = RoleIds.normalize(arguments.string("id") ?: return refused("$DELETE needs an id"))
        // A custom override of a bundled role is reset (answered by `role`); a pure custom role is removed (`role_removed`).
        val answerType = if (id in RoleIds.BUILT_IN) Envelope.ROLE else Envelope.ROLE_REMOVED
        return try {
            val answer = stream.request(Envelope(Envelope.ROLE_DELETE, id), answerType, "role_delete", idempotent = false)
            ToolResult(if (answerType == Envelope.ROLE) "reset role '$id' to its bundled definition" else "deleted role '${answer.data}'")
        } catch (e: Exception) {
            refused(e.message ?: "role_delete failed")
        }
    }

    private suspend fun save(stream: FrameStream, request: RoleSave): ToolResult = try {
        val answer = stream.request(Envelope(Envelope.ROLE_SAVE, json.encodeToString(RoleSave.serializer(), request)), Envelope.ROLE, "role_save", idempotent = false)
        val saved = json.decodeFromString(Role.serializer(), answer.data)
        ToolResult("saved role '${saved.id}' (${saved.persona.length} persona characters); it applies to new sessions of the role, not running ones")
    } catch (e: Exception) {
        refused(e.message ?: "role_save failed")
    }

    private suspend fun fetchAll(stream: FrameStream): List<Role>? = try {
        val answer = stream.request(Envelope(Envelope.ROLE_LIST, ""), Envelope.ROLES, "role_list", idempotent = true)
        json.decodeFromString(ListSerializer(Role.serializer()), answer.data)
    } catch (e: Exception) {
        null
    }

    private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull

    private fun refused(text: String) = ToolResult(text, isError = true)

    companion object {
        const val LIST = "agenthq_role_list"
        const val GET = "agenthq_role_get"
        const val UPDATE = "agenthq_role_update"
        const val CREATE = "agenthq_role_create"
        const val DELETE = "agenthq_role_delete"
        val NAMES = listOf(LIST, GET, UPDATE, CREATE, DELETE)

        private val json = Json { ignoreUnknownKeys = true }

        private fun tool(name: String, description: String, readOnly: Boolean, destructive: Boolean, props: List<Pair<String, String>>, required: List<String>) =
            buildJsonObject {
                put("name", name)
                put("description", description)
                put("inputSchema", buildJsonObject {
                    put("type", "object")
                    put("properties", buildJsonObject {
                        props.forEach { (key, text) -> put(key, buildJsonObject { put("type", "string"); put("description", text) }) }
                    })
                    put("required", buildJsonArray { required.forEach { add(JsonPrimitive(it)) } })
                    put("additionalProperties", false)
                })
                put("annotations", buildJsonObject {
                    put("readOnlyHint", readOnly)
                    put("destructiveHint", destructive)
                    put("idempotentHint", readOnly)
                    put("openWorldHint", false)
                })
            }

        private val ID = "id" to "The role id, e.g. automation."
        private val PERSONA = "persona" to "The role notes: the instructions every NEW session of this role receives at start. Replaces the stored text."
        private val DESCRIPTION = "description" to "One line describing the role (at most 200 characters); shown in the role editor, never sent to a session."

        fun descriptors() = buildJsonArray {
            add(tool(LIST, "List every role: id, name, description, source and persona size.", true, false, emptyList(), emptyList()))
            add(tool(GET, "Read one role in full, including its persona (the role notes).", true, false, listOf(ID), listOf("id")))
            add(tool(
                UPDATE,
                "Update an existing role's persona (role notes) and/or description. Applies to new sessions only. An agent caller may change " +
                    "nothing else; a non-agent caller may also rename it (name), recolour it (color #rrggbb) or set its icon.",
                false, false,
                listOf(ID, PERSONA, DESCRIPTION, "name" to "New display name (non-agent caller only).", "color" to "#rrggbb (non-agent caller only).", "icon" to "Icon key (non-agent caller only)."),
                listOf("id"),
            ))
            add(tool(CREATE, "Create a role (non-agent caller only).", false, false, listOf(ID, "name" to "Display name.", PERSONA, DESCRIPTION, "color" to "#rrggbb", "icon" to "Icon key"), listOf("id", "name")))
            add(tool(DELETE, "Delete a custom role, or reset a customised bundled role to its bundled definition (non-agent caller only). Refused while a live session has the role.", false, true, listOf(ID), listOf("id")))
        }
    }
}
