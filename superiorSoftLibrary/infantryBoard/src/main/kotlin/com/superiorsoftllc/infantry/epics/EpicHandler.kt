package com.superiorsoftllc.infantry.epics

import com.superiorsoftllc.infantry.protocol.Envelope
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/** `epic_save` payload: the owning project, the name, and the optional goal text. */
@Serializable
data class EpicSave(
    val projectId: String,
    val name: String,
    val goal: String = "",
)

/**
 * Contract 1.82.0 (BASE-1c): `epic_base`, answered by the whole [Epic] (`epic` frame).
 *
 * - [branch] set: the epic's base branch becomes [branch], refused with the reason unless it resolves to a commit in
 *   the repository ticket starts cut in (the very check `project_base` runs, against the epic's project).
 * - [clear] true: the setting is removed; the epic's tickets fall back to the project's base, else
 *   `INFANTRY_BASE_BRANCH`, else `HEAD`.
 * - neither: a read.
 *
 * Tickets already started keep the base commit they recorded; a change affects new cuts only.
 */
@Serializable
data class EpicBaseRequest(
    val epicId: String,
    val branch: String? = null,
    val clear: Boolean = false,
)

/**
 * EPIC-4: the `epic_*` wire surface over an [EpicStore], shaped after
 * [com.superiorsoftllc.infantry.mvp.WorkspaceHandler]. `epic_save` (JSON [EpicSave]) is answered by an
 * `epic` frame; `epic_list` (the raw project id as payload, like `project_get` / `workspace_list`) by
 * an `epics` frame. A refusal answers `<frame> refused: <why>`. Returns null for a frame that is not
 * this handler's.
 */
class EpicHandler(
    private val store: EpicStore,
    private val json: Json = Json { ignoreUnknownKeys = false; encodeDefaults = true },
    /**
     * BASE-1c: checks a base branch before `epic_base` stores it (throws [IllegalArgumentException] with the
     * reason); null = this handler has no repository to check against, and refuses every set (a read and a clear
     * still answer).
     */
    private val baseCheck: ((Epic, String) -> Unit)? = null,
    /** DP-5e: validates and normalises a base-branch name before it reaches [baseCheck] (the daemon passes `ProjectBaseCheck.checkName`). */
    private val branchName: (String) -> String = { it.trim() },
) {
    fun answer(envelope: Envelope): Envelope? = when (envelope.type) {
        Envelope.EPIC_SAVE -> request(envelope.type) {
            val req = json.decodeFromString(EpicSave.serializer(), envelope.data)
            Envelope(
                Envelope.EPIC,
                json.encodeToString(Epic.serializer(), store.save(req.projectId, req.name, req.goal)),
            )
        }

        // BASE-1c (contract 1.82.0): read, set or clear the epic's base branch.
        Envelope.EPIC_BASE -> request(envelope.type) {
            val req = json.decodeFromString(EpicBaseRequest.serializer(), envelope.data)
            require(!(req.clear && req.branch != null)) { "pass a branch or clear, not both" }
            val epic = when {
                req.clear -> store.setBaseBranch(req.epicId, null)
                req.branch != null -> {
                    val name = branchName(req.branch)
                    val check = baseCheck ?: throw IllegalArgumentException("this daemon has no repository to check a base branch against")
                    store.setBaseBranch(req.epicId, name, check)
                }
                else -> store.get(req.epicId) ?: throw IllegalArgumentException("unknown epic '${req.epicId.trim().take(80)}'")
            }
            Envelope(Envelope.EPIC, json.encodeToString(Epic.serializer(), epic))
        }

        Envelope.EPIC_LIST -> request(envelope.type) {
            Envelope(
                Envelope.EPICS,
                json.encodeToString(ListSerializer(Epic.serializer()), store.list(envelope.data)),
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
