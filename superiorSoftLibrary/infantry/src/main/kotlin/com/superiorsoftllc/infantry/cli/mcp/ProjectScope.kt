// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.cli.mcp

import com.superiorsoftllc.infantry.cli.FrameStream
import com.superiorsoftllc.infantry.daemon.AccessPolicy
import com.superiorsoftllc.infantry.protocol.Envelope
import com.superiorsoftllc.infantry.session.SessionRecord
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/**
 * Contract 1.106.0: the project a board / ticket / session tool call is scoped to.
 *
 * The default is the CALLER'S OWN project; `projectId='all'` (no filter) is for pm / owner callers; any other explicit id
 * is allowed only when it is the caller's own. WHO the caller is comes from the daemon's session record for
 * `INFANTRY_SESSION_ID` (its role and project) -- never from a request argument. A call with no session id is the owner / CLI
 * path and is unchanged: the request's id, else the server default, else every project.
 */
internal class ProjectScope(
    /** The project to filter to; null = every project. */
    val projectId: String?,
    /** Set when the call may not have what it asked for. */
    val refusal: String? = null,
) {
    /** `project:<id>` or `all`: what the answer says it covered. */
    val label: String get() = projectId?.let { "project:$it" } ?: ALL

    companion object {
        const val ALL = "all"
        private val PRIVILEGED_ROLES = setOf("pm", "owner", "assistant")

        private val json = Json { ignoreUnknownKeys = true }

        suspend fun sessionsOf(stream: FrameStream): List<SessionRecord> = json.decodeFromString(
            ListSerializer(SessionRecord.serializer()),
            stream.request(Envelope(Envelope.SESSIONS), Envelope.SESSIONS, "sessions", idempotent = true).data,
        )

        /** The calling session's daemon record, or null for an owner / CLI caller (or an id the daemon no longer holds). */
        fun callerOf(sessions: List<SessionRecord>, getenv: (String) -> String?): SessionRecord? =
            getenv(AccessPolicy.SESSION_ID_ENV)?.trim()?.takeIf { it.isNotEmpty() }?.let { id -> sessions.firstOrNull { it.id == id } }

        /** No session id = the owner / CLI; a session id the daemon does not know is NOT privileged. */
        fun isPrivileged(caller: SessionRecord?, hasSession: Boolean = caller != null): Boolean =
            if (caller == null) !hasSession else caller.role?.lowercase() in PRIVILEGED_ROLES

        /** The caller's own project: the daemon's record first, then the env the daemon exports into the session. */
        private fun ownProject(caller: SessionRecord?, getenv: (String) -> String?): String? =
            caller?.projectId ?: getenv(AccessPolicy.PROJECT_ID_ENV)?.trim()?.takeIf { it.isNotEmpty() }

        suspend fun resolve(stream: FrameStream, requested: String?, getenv: (String) -> String?, defaultProjectId: String?): ProjectScope =
            resolve(requested, sessionsOf(stream), getenv, defaultProjectId)

        fun resolve(requested: String?, sessions: List<SessionRecord>, getenv: (String) -> String?, defaultProjectId: String?): ProjectScope {
            val ask = requested?.trim()?.takeIf { it.isNotEmpty() }
            val sessionId = getenv(AccessPolicy.SESSION_ID_ENV)?.trim()?.takeIf { it.isNotEmpty() }
            val caller = callerOf(sessions, getenv)
            // Owner / CLI: no session id at all. Unchanged.
            if (sessionId == null) {
                return if (ask == ALL) ProjectScope(null) else ProjectScope(ask ?: defaultProjectId?.trim()?.takeIf { it.isNotEmpty() })
            }
            val own = ownProject(caller, getenv) ?: defaultProjectId?.trim()?.takeIf { it.isNotEmpty() }
            return when {
                ask == ALL ->
                    if (isPrivileged(caller, true)) ProjectScope(null)
                    else ProjectScope(own, "projectId='all' is for pm / owner callers; this session's role is '${caller?.role ?: "unknown"}'")
                ask != null && ask != own && !isPrivileged(caller, true) ->
                    ProjectScope(own, "project '$ask' is not this session's project ('${own ?: "unknown"}'); only pm / owner callers may look at another project")
                ask != null -> ProjectScope(ask)
                else -> ProjectScope(own)
            }
        }

        /** Why [caller] may not touch an object of [objectProject], or null when it may. Writes use this too. */
        fun foreignRefusal(what: String, objectProject: String?, caller: SessionRecord?, getenv: (String) -> String?): String? {
            if (getenv(AccessPolicy.SESSION_ID_ENV).isNullOrBlank() || isPrivileged(caller, true)) return null
            val own = ownProject(caller, getenv) ?: return null
            if (objectProject == null || objectProject == own) return null
            return "$what belongs to project '$objectProject', not this session's project '$own'; only pm / owner callers may reach another project"
        }
    }
}
