// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.plugins.official.agents.daemon

import com.superiorsoftllc.infantry.daemon.api.Caller
import com.superiorsoftllc.infantry.daemon.api.DaemonApi
import com.superiorsoftllc.infantry.daemon.api.DaemonTool
import com.superiorsoftllc.infantry.daemon.api.ToolCall
import com.superiorsoftllc.infantry.daemon.api.ToolResult
import com.superiorsoftllc.infantry.daemon.api.progress.ProgressText
import com.superiorsoftllc.infantry.daemon.api.progress.SessionProgress
import com.superiorsoftllc.infantry.daemon.api.sessions.SessionText
import com.superiorsoftllc.infantry.daemon.api.sessions.SessionView
import com.superiorsoftllc.infantry.daemon.api.sessions.SessionsResult
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/**
 * RUNTIME-LOAD-5 (F5 remainder): `infantry_list_sessions`, `infantry_get_session` and `infantry_pod_state`, moved out of the CLI's MCP
 * server (`McpTools`). Same names, descriptors (golden-checked) and answers. Every session read goes through the host's read model
 * ([DaemonApi.sessionReads]), which applies the project scope for the calling session; the transcript is the host's rendering of its
 * replay buffer. The pod's profiles, roster and node state are the same `agent_list` / `agent_roster` / `pod_query` frames the CLI
 * sent, on the caller's own connection.
 */
internal object SessionReadTools {
    const val LIST_SESSIONS = "infantry_list_sessions"
    const val GET_SESSION = "infantry_get_session"
    const val POD_STATE = "infantry_pod_state"
    const val DEFAULT_LINES = 5
    const val DEFAULT_LIST = 50
    /** A transcript line longer than this is cut (a raw JSON dump is not worth its tokens). */
    const val LINE_CHARS = 300
    const val MAX_LINES = 200

    /** A session read is in-process and bounded; this only caps a stuck frame answer (roster, pod). */
    const val TIMEOUT_MS = 30_000L

    private const val ERROR = "error"
    private val json = Json { ignoreUnknownKeys = true }

    /** The caller as the host scopes it: the MCP process's own session (`INFANTRY_SESSION_ID`), else the connection's. */
    fun by(call: ToolCall): Caller = call.caller.copy(activeSession = call.clientSessionId ?: call.caller.activeSession)

    fun requestedProject(arguments: JsonObject): String? =
        (arguments["projectId"] as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }

    fun annotations() = buildJsonObject { put("readOnlyHint", true) }

    fun ok(payload: JsonObject) = ToolResult(Json.encodeToString(JsonObject.serializer(), payload))

    /** A read that did not answer Ok, worded `<tool> refused: <why>` (or the host's whole text for a miss). */
    fun <T> failed(tool: String, r: SessionsResult<T>): ToolResult = when (r) {
        is SessionsResult.Missing -> ToolResult(r.text, isError = true)
        is SessionsResult.Refused -> ToolResult("$tool refused: ${r.reason}", isError = true)
        is SessionsResult.Unavailable -> ToolResult("$tool is unavailable: ${r.reason}", isError = true)
        is SessionsResult.Ok -> error("not a failure")
    }

    /** The row every session tool prints, as the CLI's `McpTools.summary` printed it. */
    fun summary(session: SessionView, now: Long): JsonObject = buildJsonObject {
        put("id", session.id)
        put("sessionId", session.id) // same value as "id": the key every tool takes as input
        put("agentId", session.agentId)
        put("role", session.role)
        put("provider", session.provider)
        put("model", session.model)
        session.projectId?.let { put("projectId", it) }
        put("status", SessionText.status(session))
        session.lastAction?.let { put("lastAction", it) }
        session.lastOutputAt?.let { put("lastOutputAt", it) }
        session.lastEventAt?.let { put("lastEventAt", it) }
        session.backgroundWork?.let { put("backgroundWork", it) }
        // Contract 1.117.0: the agent's own progress report, raw (for a monitor) and as one readable line.
        session.progress?.let { p ->
            put("progress", Json.encodeToJsonElement(SessionProgress.serializer(), p))
            put("progressLine", ProgressText.line(p, now))
        }
    }

    /** [type] on the caller's connection, answered by [answerType]; the answer's data, or the failure worded `<tool> failed: ...`. */
    fun request(call: ToolCall, tool: String, type: String, data: String, answerType: String): Result<String> = try {
        val reply = call.request(type, data)
        when (reply.type) {
            answerType -> Result.success(reply.data)
            ERROR -> Result.failure(IllegalStateException("$tool failed: ${reply.data.ifBlank { "$type failed" }}"))
            else -> Result.failure(IllegalStateException("$tool failed: $type: unexpected answer ${reply.type}"))
        }
    } catch (e: Exception) {
        Result.failure(IllegalStateException("$tool failed: ${e.message ?: e.javaClass.simpleName}"))
    }

    fun parse(data: String): JsonElement = json.parseToJsonElement(data)

    private fun tool(schema: JsonObjectBuilder.() -> Unit) = buildJsonObject(schema)

    class ListSessions(private val api: DaemonApi) : DaemonTool {
        override val name = LIST_SESSIONS
        override val description = "List the SESSIONS (running or past agent runs, not saved profiles) the Infantry AI daemon knows: id, agentId, role, provider, model, projectId, status."
        override val inputSchema = tool {
            put("type", "object")
            putJsonObject("properties") {
                putJsonObject("projectId") {
                    put("type", "string")
                    put("description", "Only this project's sessions. Omitted: a session caller's own project (everyone else: all). \"all\" lists every project (pm / owner callers only).")
                }
                putJsonObject("limit") {
                    put("type", "integer")
                    put("minimum", 1)
                    put("description", "At most this many sessions (live ones first, then the most recent). Default: setting limits.tools.listLimit.")
                }
            }
            put("additionalProperties", false)
        }
        override val annotations = annotations()
        override val timeoutMs = TIMEOUT_MS

        override fun call(call: ToolCall): ToolResult {
            // A SESSION caller sees its OWN project by default; explicit "all" lists every project and an explicit id lists that
            // project (pm / owner roles only, the host's rule). Owner / user callers (no session): all projects.
            val requested = requestedProject(call.arguments)
            val by = by(call)
            val reads = api.sessionReads
            val caller = when (val r = reads.caller(by)) {
                is SessionsResult.Ok -> r.value
                else -> return failed(LIST_SESSIONS, r)
            }
            if (requested != null) {
                when (val s = reads.scope(by, requested)) {
                    is SessionsResult.Ok -> s.value.refusal?.let { return ToolResult("$LIST_SESSIONS refused: $it", isError = true) }
                    else -> return failed(LIST_SESSIONS, s)
                }
            }
            val scope = when {
                requested == "all" -> null
                requested != null -> requested
                else -> caller?.projectId
            }
            val sessions = when (val r = reads.list(by, scope)) {
                is SessionsResult.Ok -> r.value
                else -> return failed(LIST_SESSIONS, r)
            }
            val now = System.currentTimeMillis()
            val cap = ((call.arguments["limit"] as? JsonPrimitive)?.intOrNull ?: api.settings.limit("limits.tools.listLimit")?.toInt() ?: DEFAULT_LIST).takeIf { it > 0 }
            val shown = sessions.sortedWith(compareByDescending<SessionView> { it.live }.thenByDescending { it.lastEventAt ?: 0L }).let { if (cap != null) it.take(cap) else it }
            return ok(buildJsonObject {
                put("sessions", JsonArray(shown.map { summary(it, now) }))
                if (shown.size < sessions.size) put("note", "showing ${shown.size} of ${sessions.size} sessions; pass limit for more")
            })
        }
    }

    /**
     * One session's status plus the last [MAX_LINES]-bounded lines of its transcript, which the HOST renders from the daemon's replay
     * buffer: it covers what THIS daemon process still retains, not the session's whole life across restarts.
     */
    class GetSession(private val api: DaemonApi) : DaemonTool {
        override val name = GET_SESSION
        override val description = "One session's status and the last lines of its transcript (from the daemon's replay buffer, bounded)."
        override val inputSchema = tool {
            put("type", "object")
            putJsonObject("properties") {
                putJsonObject("sessionId") {
                    put("type", "string")
                    put("description", "Session id, or a unique prefix of one.")
                }
                putJsonObject("brief") {
                    put("type", "boolean")
                    put("description", "Omit the transcript: return only state, live state, last action and last output/event times.")
                }
                putJsonObject("lines") {
                    put("type", "integer")
                    put("minimum", 1)
                    put("maximum", MAX_LINES)
                    put("default", DEFAULT_LINES)
                    put("description", "How many trailing transcript lines to return (default: setting limits.tools.sessionTailLines, 5).")
                }
            }
            put("required", buildJsonArray { add(JsonPrimitive("sessionId")) })
            put("additionalProperties", false)
        }
        override val annotations = annotations()
        override val timeoutMs = TIMEOUT_MS

        override fun call(call: ToolCall): ToolResult {
            val requested = (call.arguments["sessionId"] as? JsonPrimitive)?.contentOrNull?.trim()
            if (requested.isNullOrEmpty()) return ToolResult("sessionId is required", isError = true)
            val limit = ((call.arguments["lines"] as? JsonPrimitive)?.intOrNull ?: api.settings.limit("limits.tools.sessionTailLines")?.toInt() ?: DEFAULT_LINES).coerceIn(1, MAX_LINES)
            val by = by(call)
            val session = when (val r = api.sessionReads.find(requested, by)) {
                is SessionsResult.Ok -> r.value
                else -> return failed(GET_SESSION, r)
            }
            val now = System.currentTimeMillis()
            if ((call.arguments["brief"] as? JsonPrimitive)?.contentOrNull == "true") {
                return ok(buildJsonObject {
                    summary(session, now).forEach { (key, value) -> put(key, value) }
                    put("liveState", SessionText.liveState(session))
                    put("activity", SessionText.activity(session, now))
                    session.ticketId?.let { put("ticketId", it) }
                })
            }
            val transcript = when (val r = api.sessionReads.transcript(session.id, by)) {
                is SessionsResult.Ok -> r.value
                else -> return failed(GET_SESSION, r)
            }
            val lines = transcript.lines
            val tail = lines.takeLast(limit)
            return ok(buildJsonObject {
                summary(session, now).forEach { (key, value) -> put(key, value) }
                put("runCount", session.runCount)
                session.lastExitCode?.let { put("lastExitCode", it) }
                session.lastError?.let { put("lastError", it) }
                session.projectId?.let { put("projectId", it) }
                session.ticketId?.let { put("ticketId", it) }
                put("transcript", buildJsonArray { tail.forEach { add(JsonPrimitive(if (it.length > LINE_CHARS) it.take(LINE_CHARS) + "..." else it)) } })
                put("transcriptLines", tail.size)
                put("transcriptTruncated", lines.size > tail.size)
                transcript.note?.let { put("transcriptNote", it) }
            })
        }
    }

    /**
     * The pod at a glance: every agent profile grouped by role, the sessions by status, and -- when asked for -- one project's live
     * roster and one pod's node state (contract 1.x has no "list pods" frame, so a pod is only shown by id).
     */
    class PodState(private val api: DaemonApi) : DaemonTool {
        override val name = POD_STATE
        override val description =
            "Summary of the pod: saved PROFILES (templates, not running) grouped by role under `agents`, SESSIONS by status; " +
                "optionally one project's `roster` (its live AGENTS, BUSY/IDLE/OFFLINE: who can be assigned work) and one pod's node state."
        override val inputSchema = tool {
            put("type", "object")
            putJsonObject("properties") {
                putJsonObject("projectId") {
                    put("type", "string")
                    put("description", "Include this project's live roster (running agents, not profiles: agent, role, BUSY/IDLE/OFFLINE). Default: the caller's own project; \"all\" (pm / owner callers only) covers every project.")
                }
                putJsonObject("podId") {
                    put("type", "string")
                    put("description", "Include this pod's state and nodes.")
                }
            }
            put("additionalProperties", false)
        }
        override val annotations = annotations()
        override val timeoutMs = TIMEOUT_MS

        override fun call(call: ToolCall): ToolResult {
            val by = by(call)
            val reads = api.sessionReads
            val scope = when (val r = reads.scope(by, requestedProject(call.arguments), call.clientProjectId)) {
                is SessionsResult.Ok -> r.value
                else -> return failed(POD_STATE, r)
            }
            scope.refusal?.let { return ToolResult("$POD_STATE refused: $it", isError = true) }
            val projectId = scope.projectId
            val podId = (call.arguments["podId"] as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }

            val profiles = request(call, POD_STATE, "agent_list", "", "agents").getOrElse { return ToolResult(it.message!!, isError = true) }
                .let { (parse(it) as JsonArray).map { p -> p as JsonObject } }
            val sessions = when (val r = reads.list(by, projectId)) {
                is SessionsResult.Ok -> r.value
                else -> return failed(POD_STATE, r)
            }
            val roster = projectId?.let { project ->
                val data = buildJsonObject { put("projectId", project) }.toString()
                request(call, POD_STATE, "agent_roster", data, "roster").getOrElse { return ToolResult(it.message!!, isError = true) }.let(::parse)
            }
            val pod = podId?.let { id ->
                request(call, POD_STATE, "pod_query", id, "pod").getOrElse { return ToolResult(it.message!!, isError = true) }.let(::parse)
            }
            fun str(o: JsonObject, key: String) = (o[key] as? JsonPrimitive)?.contentOrNull

            return ok(buildJsonObject {
                put("scope", scope.label)
                projectId?.let { put("projectId", it) }
                putJsonObject("agents") {
                    put("total", profiles.size)
                    putJsonObject("byRole") {
                        profiles.groupBy { str(it, "role").orEmpty() }.toSortedMap().forEach { (role, members) ->
                            put(role, buildJsonArray {
                                members.forEach { profile ->
                                    add(buildJsonObject {
                                        put("id", str(profile, "id"))
                                        put("name", str(profile, "name"))
                                        str(profile, "model")?.let { put("model", it) }
                                    })
                                }
                            })
                        }
                    }
                }
                putJsonObject("sessions") {
                    put("total", sessions.size)
                    put("live", sessions.count { it.live })
                    putJsonObject("byStatus") {
                        sessions.groupBy { it.state }.toSortedMap().forEach { (state, list) -> put(state, list.size) }
                    }
                }
                roster?.let { rows ->
                    putJsonObject("roster") {
                        put("projectId", projectId)
                        put("agents", rows)
                    }
                }
                pod?.let { put("pod", it) }
            })
        }
    }
}
