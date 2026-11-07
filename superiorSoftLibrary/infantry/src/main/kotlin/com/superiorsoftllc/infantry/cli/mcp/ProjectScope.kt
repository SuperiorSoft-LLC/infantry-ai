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
 * The default is the CALLER'S OWN project. PROJECT-ISOLATION-1: no agent reaches another project, the pm included: `projectId='all'`
 * (no filter) and another project's id are only for the owner (no session) and the owner's own agent roles ([CROSS_PROJECT_ROLES]).
 * A pm keeps its pm powers ([isPrivileged]: kill / spawn rules) inside its own project. WHO the caller is comes from the daemon's session record for
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
        /** Roles with pm powers inside their own project (kill and spawn rules). Never a pass to another project. */
        private val PRIVILEGED_ROLES = setOf("pm", "owner", "assistant")
        /** PROJECT-ISOLATION-1: the owner's own agent roles, the only sessions that may reach another project. */
        val CROSS_PROJECT_ROLES = setOf("owner", "assistant")
        /** The session id a confined call ([Caller.project], no session) is seen with: never a real session, never privileged. */
        const val CONFINED_PREFIX = "project:"

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

        /** PROJECT-ISOLATION-1: may [caller] reach another project? Only the owner (no session) and [CROSS_PROJECT_ROLES]. */
        fun crossesProjects(caller: SessionRecord?, hasSession: Boolean = caller != null): Boolean =
            if (caller == null) !hasSession else crossesProjects(caller.role)

        /** The same rule by role, for a caller known only by its role (the host's tool gate). */
        fun crossesProjects(role: String?): Boolean = role?.lowercase() in CROSS_PROJECT_ROLES

        /**
         * PROJECT-ISOLATION-1: the env view a daemon-side scope check reads for [by]: its session, or for a call confined to a project
         * with no session ([Caller.project], a project hook) a synthetic session id and that project, so the rules above apply unchanged.
         */
        fun envOf(by: com.superiorsoftllc.infantry.daemon.api.Caller): (String) -> String? {
            val session = by.activeSession?.trim()?.takeIf { it.isNotEmpty() }
            val confined = by.project?.trim()?.takeIf { it.isNotEmpty() }
            return { name ->
                when (name) {
                    AccessPolicy.SESSION_ID_ENV -> session ?: confined?.let { CONFINED_PREFIX + it }
                    AccessPolicy.PROJECT_ID_ENV -> confined
                    else -> null
                }
            }
        }

        /**
         * The one refusal text for an object of [objectProject] reached by a caller of project [own] that may not cross projects, or
         * null when it may ([objectProject] null = a global object, open to all).
         */
        fun foreignText(what: String, objectProject: String?, own: String?): String? {
            if (objectProject == null || own == null || objectProject == own) return null
            return "$what belongs to project '$objectProject', another project: this caller is confined to project '$own' and only the owner reaches another project"
        }

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
            // HOOK-TIMER-SCOPE-1: a project hook (confined, no session) is named as what it is, not as a session of role 'unknown'.
            val hook = caller == null && sessionId.startsWith(CONFINED_PREFIX)
            val who = if (hook) "this project hook" else "this session (role '${caller?.role ?: "unknown"}')"
            val whose = if (hook) "this project hook's project" else "this session's project"
            return when {
                ask == ALL ->
                    if (crossesProjects(caller, true)) ProjectScope(null)
                    else ProjectScope(own, "projectId='all' is for the owner; $who is confined to project '${own ?: "unknown"}'")
                ask != null && ask != own && !crossesProjects(caller, true) ->
                    ProjectScope(own, "project '$ask' is not $whose ('${own ?: "unknown"}'); only the owner may look at another project")
                ask != null -> ProjectScope(ask)
                else -> ProjectScope(own)
            }
        }

        /** Why [caller] may not touch an object of [objectProject], or null when it may. Writes use this too. */
        fun foreignRefusal(what: String, objectProject: String?, caller: SessionRecord?, getenv: (String) -> String?): String? {
            if (getenv(AccessPolicy.SESSION_ID_ENV).isNullOrBlank() || crossesProjects(caller, true)) return null
            return foreignText(what, objectProject, ownProject(caller, getenv))
        }
    }
}
