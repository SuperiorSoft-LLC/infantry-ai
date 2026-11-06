// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.daemon.plugins

import com.superiorsoftllc.infantry.cli.mcp.ProjectScope
import com.superiorsoftllc.infantry.daemon.api.Caller
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import java.util.concurrent.ConcurrentHashMap

/**
 * PROJECT-ISOLATION-1: no project's events, tickets or sessions reach another project. The ONE project-scope layer the host applies to
 * every plugin tool call ([toolRefusal]) and to every named event ([stamp], which the hook dispatch then matches on). The rule itself
 * (who may cross projects, the refusal text) is [ProjectScope]'s, so the board, session reads, the MCP tools and this gate agree.
 *
 * TOOLS. A call is SCOPED when it comes from a session (the client's `INFANTRY_SESSION_ID` or the connection's active session) or is
 * confined to a project ([Caller.project], a project hook's script). A scoped caller of project P may not name another project in the
 * generic target arguments ([PROJECT_ARGS], [TICKET_ARGS], [SESSION_ARGS]); `projectId='all'` is refused too. Only the owner (no session)
 * and the owner's own agent roles ([ProjectScope.CROSS_PROJECT_ROLES]) pass. A tool that DECLARES `allowCrossProject` in its input schema
 * (cross-project mail, runs) keeps that explicit opt-in. A target the gate cannot resolve is left to the tool (which answers "no such ...").
 *
 * EVENTS. A named event whose payload has no `projectId` gets the project of its ticket or session, so a hook matches on the event's own
 * project. The project of a ticket or session is remembered while it lives, so `ticket.deleted` and `session.ended` still name it.
 */
class ProjectIsolation(
    /** The project of the ticket [ref] names (full id or prefix), or null when unknown or global. Read as the owner. */
    private val ticketProject: (ref: String) -> String?,
    /** The daemon's record of session [id], or null when it does not know it. */
    private val session: (id: String) -> Who?,
) {
    /** What the gate needs of a session: its project (null = a global session) and role. */
    data class Who(val projectId: String?, val role: String?)

    private val ticketProjects = ConcurrentHashMap<String, String>()
    private val sessionProjects = ConcurrentHashMap<String, String>()

    fun projectOfTicket(ref: String): String? {
        val p = runCatching { ticketProject(ref) }.getOrNull()
        if (p != null) { ticketProjects[ref] = p; return p }
        return ticketProjects[ref]
    }

    fun projectOfSession(id: String): String? {
        val p = runCatching { session(id) }.getOrNull()?.projectId
        if (p != null) { sessionProjects[id] = p; return p }
        return sessionProjects[id]
    }

    /** The project an event payload belongs to: its own `projectId`, else its ticket's, else its session's; null = a project-less event. */
    fun eventProject(payload: JsonObject): String? =
        str(payload, "projectId")
            ?: str(payload, "ticketId")?.let(::projectOfTicket)
            ?: EVENT_SESSION_KEYS.firstNotNullOfOrNull { k -> str(payload, k)?.let(::projectOfSession) }

    /** [payload] with `projectId` added when it has none and one resolves; anything else unchanged. Never throws. */
    fun stamp(name: String, payload: String): String = runCatching {
        val o = Json.parseToJsonElement(payload) as? JsonObject ?: return payload
        val project = eventProject(o)
        forget(name, o)
        if (project == null || str(o, "projectId") != null) payload
        else JsonObject(o + ("projectId" to JsonPrimitive(project))).toString()
    }.getOrDefault(payload)

    /** A deleted ticket or an ended session leaves the memory once its last event is stamped. */
    private fun forget(name: String, o: JsonObject) {
        if (name == "ticket.deleted") str(o, "ticketId")?.let { ticketProjects.remove(it) }
        if (name == "session.ended") str(o, "sessionId")?.let { sessionProjects.remove(it) }
    }

    /**
     * Why tool [tool] may not run with [args] for this caller, or null when it may. [declaresCrossProject]: the tool's schema has an
     * `allowCrossProject` property (its own explicit opt-in, honoured when the call sets it).
     */
    fun toolRefusal(tool: String, args: JsonObject, caller: Caller, clientSessionId: String?, clientProjectId: String?, declaresCrossProject: Boolean): String? {
        val sid = (clientSessionId ?: caller.activeSession)?.trim()?.takeIf { it.isNotEmpty() }
        val confined = caller.project?.trim()?.takeIf { it.isNotEmpty() }
        if (sid == null && confined == null) return null // the owner / CLI: every project
        val who = if (confined == null && sid != null) runCatching { session(sid) }.getOrNull() else null
        if (confined == null && who != null && ProjectScope.crossesProjects(who.role)) return null
        val own = confined ?: who?.projectId ?: clientProjectId?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        if (declaresCrossProject && (args["allowCrossProject"] as? JsonPrimitive)?.booleanOrNull == true) return null
        for (k in PROJECT_ARGS) {
            val v = str(args, k) ?: continue
            if (v.equals(ProjectScope.ALL, ignoreCase = true)) return "$tool refused: $k='all' is for the owner; this caller is confined to project '$own'"
            ProjectScope.foreignText("project '$v'", v, own)?.let { return "$tool refused: $it" }
        }
        for (k in TICKET_ARGS) {
            val v = str(args, k) ?: continue
            ProjectScope.foreignText("ticket $v", projectOfTicket(v), own)?.let { return "$tool refused: $it" }
        }
        for (k in SESSION_ARGS) {
            val v = str(args, k) ?: continue
            ProjectScope.foreignText("session $v", runCatching { session(v) }.getOrNull()?.projectId, own)?.let { return "$tool refused: $it" }
        }
        return null
    }

    companion object {
        /** The generic argument names a tool names a project, a ticket or a session by (the board, session, script and run tools). */
        val PROJECT_ARGS = listOf("projectId", "project")
        val TICKET_ARGS = listOf("ticketId")
        val SESSION_ARGS = listOf("sessionId", "targetSessionId")
        /** Payload keys of a named event that name a session. */
        val EVENT_SESSION_KEYS = listOf("sessionId", "workerSession")

        private fun str(o: JsonObject, k: String): String? =
            (o[k] as? JsonPrimitive)?.takeIf { it.isString }?.content?.trim()?.takeIf { it.isNotEmpty() }

        /** True when [schema] (a tool's input schema) declares an `allowCrossProject` property. */
        fun declaresCrossProject(schema: JsonObject): Boolean = (schema["properties"] as? JsonObject)?.containsKey("allowCrossProject") == true
    }
}
