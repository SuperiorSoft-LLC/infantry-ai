// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.plugins.official.notes.daemon

import com.superiorsoftllc.infantry.daemon.api.Frame
import com.superiorsoftllc.infantry.memory.*
import com.superiorsoftllc.infantry.protocol.Envelope
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/** NOTES-1: the calling session as the handler needs it: its id and role. */
data class MemoryCaller(val sessionId: String, val role: String)

/** The resolved (scope, ownerId) pair a [MemoryScoped] request addresses. */
private data class Target(val scope: String, val ownerId: String)

/**
 * MEM-5 / MEM-8: the `memory_*` wire surface over a [MemoryStore], shaped after
 * [com.superiorsoftllc.infantry.epics.EpicHandler]. `memory_search` (JSON [MemorySearch]) is answered
 * by a `memory_hits` frame carrying the project's ranked hits ([MemorySearchResult]). `memory_save`
 * (JSON [MemorySave]) is answered by a `memory_note` frame carrying the whole stored [MemoryNote];
 * `memory_list` (the raw project id as payload, like `epic_list`) by a `memory_notes` frame: the
 * project's notes as [MemoryIndexEntry], in name order. P10-K2: `memory_save` honours a `mode`, and
 * `memory_get` (JSON [MemoryGet]) is answered by `memory_note` or refused `note_not_found`. A refusal answers
 * `<frame> refused: <why>`, where a coded refusal ([MemoryRefusal]) puts its code first.
 * Returns null for a frame that is not this handler's.
 */
class MemoryHandler(
    private val store: MemoryStore,
    /** NOTES-1: resolve a declared session id to its caller, or null when the daemon does not know it. */
    private val caller: (sessionId: String) -> MemoryCaller? = { null },
    private val json: Json = Json { ignoreUnknownKeys = false; encodeDefaults = true },
    /** ROLE-NOTES-1: told when a ROLE-scoped note is saved or deleted, so live sessions of that role can re-read it. */
    private val onRoleNoteChanged: (projectId: String, role: String, name: String, deleted: Boolean) -> Unit = { _, _, _, _ -> },
) {
    fun answer(envelope: Frame): Frame? = when (envelope.type) {
        Envelope.MEMORY_SEARCH -> request(envelope.type) {
            val req = json.decodeFromString(MemorySearch.serializer(), envelope.data)
            Frame(
                Envelope.MEMORY_HITS,
                json.encodeToString(
                    ListSerializer(MemorySearchResult.serializer()),
                    store.search(req.projectId, req.query, resolve(req).scope, resolve(req).ownerId),
                ),
            )
        }

        Envelope.MEMORY_SAVE -> request(envelope.type) {
            val req = json.decodeFromString(MemorySave.serializer(), envelope.data)
            val saved = save(req)
            notifyRole(saved.scope, saved.ownerId, req.projectId, saved.name, deleted = false)
            Frame(Envelope.MEMORY_NOTE, json.encodeToString(MemoryNote.serializer(), saved))
        }

        Envelope.MEMORY_GET -> request(envelope.type) {
            val req = json.decodeFromString(MemoryGet.serializer(), envelope.data)
            val target = resolve(req)
            val note = store.get(req.projectId, req.name, target.scope, target.ownerId)
                ?: throw MemoryRefusal(
                    MemoryRefusal.NOTE_NOT_FOUND,
                    "no ${target.scope} note '${req.name.trim()}' in project '${req.projectId.trim()}'",
                )
            Frame(Envelope.MEMORY_NOTE, json.encodeToString(MemoryNote.serializer(), note))
        }

        Envelope.MEMORY_LIST -> request(envelope.type) {
            val req = if (envelope.data.trimStart().startsWith("{")) {
                json.decodeFromString(MemoryList.serializer(), envelope.data)
            } else {
                MemoryList(projectId = envelope.data)
            }
            Frame(Envelope.MEMORY_NOTES, indexOf(req))
        }

        Envelope.MEMORY_DELETE -> request(envelope.type) {
            val req = json.decodeFromString(MemoryDelete.serializer(), envelope.data)
            val target = resolve(req)
            if (!store.delete(req.projectId, req.name, target.scope, target.ownerId)) {
                throw MemoryRefusal(MemoryRefusal.NOTE_NOT_FOUND, "no ${target.scope} note '${req.name.trim()}' to delete")
            }
            notifyRole(target.scope, target.ownerId, req.projectId, req.name.trim(), deleted = true)
            Frame(Envelope.MEMORY_NOTES, indexOf(MemoryList(req.projectId, req.scope, req.sessionId, req.role)))
        }

        Envelope.MEMORY_PURGE_ROLE -> request(envelope.type) {
            val req = json.decodeFromString(MemoryPurgeRole.serializer(), envelope.data)
            val role = req.role.trim()
            require(role.isNotEmpty()) { "expects a role" }
            require(req.confirm.trim() == role) {
                "deleting a role purges ALL its notes in every project; pass confirm equal to the role id '$role'"
            }
            store.purgeRole(role)
            Frame(Envelope.MEMORY_NOTES, json.encodeToString(ListSerializer(MemoryIndexEntry.serializer()), emptyList()))
        }

        else -> null
    }

    private fun indexOf(req: MemoryList): String {
        val target = resolve(req)
        return json.encodeToString(
            ListSerializer(MemoryIndexEntry.serializer()),
            store.index(req.projectId, target.scope, target.ownerId).sortedBy { it.name }
                .map { MemoryIndexEntry(it.name, it.description, it.folder) },
        )
    }

    /**
     * NOTES-1: turn a request's (scope, sessionId, role) into the (scope, ownerId) it addresses.
     *  - `project` (default): no owner, open to everyone.
     *  - `session`: the declared calling session; an agent can only ever address its OWN session's notes.
     *  - `role`: the caller's own role; naming ANOTHER role is allowed only for the `pm` role and for the owner
     *    (no declared session), so a worker cannot write a PM-only rule into another role's notes.
     */
    private fun resolve(req: MemoryScoped): Target {
        val scope = req.scope?.trim()?.ifEmpty { null } ?: SCOPE_PROJECT
        val who = req.sessionId?.trim()?.ifEmpty { null }?.let { id ->
            caller(id) ?: throw IllegalArgumentException("no session '$id' on this daemon")
        }
        return when (scope) {
            SCOPE_PROJECT -> Target(SCOPE_PROJECT, "")
            SCOPE_SESSION -> Target(
                SCOPE_SESSION,
                who?.sessionId ?: throw IllegalArgumentException("a session note expects the calling session (sessionId)"),
            )
            SCOPE_ROLE -> {
                val wanted = req.role?.trim()?.ifEmpty { null }
                val role = wanted ?: who?.role
                    ?: throw IllegalArgumentException("a role note expects a role (or the calling session's own role)")
                if (who != null && role != who.role && who.role != PM_ROLE) {
                    throw IllegalArgumentException(
                        "role '${who.role}' may only address its own role notes; only the pm and the owner may address '$role'",
                    )
                }
                Target(SCOPE_ROLE, role)
            }
            else -> throw IllegalArgumentException("scope '$scope' is not one of ${NOTE_SCOPES.joinToString("|")}")
        }
    }

    /** One `memory_save`, routed by mode after refusing a field that belongs to another mode (K-D5). */
    /** Best effort: an announcement failure never fails the write. */
    private fun notifyRole(scope: String, ownerId: String, projectId: String, name: String, deleted: Boolean) {
        if (scope == SCOPE_ROLE && ownerId.isNotEmpty()) runCatching { onRoleNoteChanged(projectId.trim(), ownerId, name, deleted) }
    }

    private fun save(req: MemorySave): MemoryNote {
        val mode = req.mode?.trim()?.ifEmpty { null } ?: MemorySave.MODE_REPLACE
        val t = resolve(req)
        fun refuseField(field: String): Nothing =
            throw IllegalArgumentException("mode '$mode' does not take '$field'")
        return when (mode) {
            MemorySave.MODE_REPLACE -> {
                if (req.content != null) refuseField("content' (replace takes 'body")
                if (req.find != null) refuseField("find")
                val description = req.description ?: throw IllegalArgumentException("replace expects 'description'")
                val body = req.body ?: throw IllegalArgumentException("replace expects 'body'")
                store.save(req.projectId, req.name, description, body, folder = req.folder, scope = t.scope, ownerId = t.ownerId)
            }
            MemorySave.MODE_APPEND -> {
                if (req.body != null) refuseField("body' (append takes 'content")
                if (req.find != null) refuseField("find")
                val content = req.content ?: throw IllegalArgumentException("append expects 'content'")
                store.append(req.projectId, req.name, content, req.description, req.folder, t.scope, t.ownerId)
            }
            MemorySave.MODE_EDIT -> {
                if (req.body != null) refuseField("body' (edit takes 'content")
                val find = req.find ?: throw IllegalArgumentException("edit expects 'find'")
                val content = req.content
                    ?: throw IllegalArgumentException("edit expects 'content' (an empty one removes the passage)")
                store.edit(req.projectId, req.name, find, content, req.description, req.folder, t.scope, t.ownerId)
            }
            else -> throw IllegalArgumentException(
                "unknown mode '$mode'; expected one of ${MemorySave.MODES.joinToString(", ")}",
            )
        }
    }

    private companion object {
        const val PM_ROLE = "pm"
    }

    private inline fun request(frame: String, block: () -> Frame): Frame = try {
        block()
    } catch (e: IllegalArgumentException) {
        Frame(Envelope.ERROR, "$frame refused: ${e.message ?: "invalid request"}")
    } catch (e: Exception) {
        Frame(Envelope.ERROR, "$frame failed: ${e.message ?: e::class.simpleName}")
    }
}
