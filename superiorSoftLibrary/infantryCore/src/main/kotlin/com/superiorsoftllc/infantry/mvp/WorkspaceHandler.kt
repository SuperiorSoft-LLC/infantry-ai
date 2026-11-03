package com.superiorsoftllc.infantry.mvp

import com.superiorsoftllc.infantry.protocol.Envelope
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/** `workspace_save` payload: the owning project, the name, and the optional checkout folder. */
@Serializable
data class WorkspaceSave(
    val projectId: String,
    val name: String,
    val path: String? = null,
)

/**
 * PW-5: the `workspace_*` wire surface over a [WorkspaceStore], shaped after [ProjectHandler].
 * `workspace_save` (JSON [WorkspaceSave]) is answered by a `workspace` frame; `workspace_list` (the raw
 * project id as payload, like `project_get`) by a `workspaces` frame. A refusal answers
 * `<frame> refused: <why>`. Returns null for a frame that is not this handler's.
 */
class WorkspaceHandler(
    private val store: WorkspaceStore,
    private val json: Json = Json { ignoreUnknownKeys = false; encodeDefaults = true },
) {
    fun answer(envelope: Envelope): Envelope? = when (envelope.type) {
        Envelope.WORKSPACE_SAVE -> request(envelope.type) {
            val req = json.decodeFromString(WorkspaceSave.serializer(), envelope.data)
            Envelope(
                Envelope.WORKSPACE,
                json.encodeToString(Workspace.serializer(), store.save(req.projectId, req.name, req.path)),
            )
        }

        Envelope.WORKSPACE_LIST -> request(envelope.type) {
            Envelope(
                Envelope.WORKSPACES,
                json.encodeToString(ListSerializer(Workspace.serializer()), store.list(envelope.data)),
            )
        }

        else -> null
    }

    private inline fun request(frame: String, block: () -> Envelope): Envelope = try {
        block()
    } catch (e: IllegalArgumentException) {
        Envelope(Envelope.ERROR, "$frame refused: ${e.message ?: "invalid request"}")
    } catch (e: Exception) {
        Envelope(Envelope.ERROR, "$frame failed: ${e.message ?: e::class.simpleName}")
    }
}
