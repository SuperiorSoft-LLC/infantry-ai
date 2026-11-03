// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.plugins.official.agents.daemon

import com.superiorsoftllc.infantry.daemon.api.Caller
import com.superiorsoftllc.infantry.daemon.api.DaemonApi
import com.superiorsoftllc.infantry.daemon.api.DaemonTool
import com.superiorsoftllc.infantry.daemon.api.ToolCall
import com.superiorsoftllc.infantry.daemon.api.ToolResult
import com.superiorsoftllc.infantry.daemon.api.board.BoardResult
import com.superiorsoftllc.infantry.daemon.api.board.ContextLimits
import com.superiorsoftllc.infantry.daemon.api.board.EffortLevels
import com.superiorsoftllc.infantry.daemon.api.board.TicketInfo
import com.superiorsoftllc.infantry.daemon.api.sessions.SessionView
import com.superiorsoftllc.infantry.daemon.api.sessions.SessionsResult
import com.superiorsoftllc.infantry.daemon.api.spawns.SpawnNarrowing
import com.superiorsoftllc.infantry.daemon.api.spawns.SpawnRequest
import com.superiorsoftllc.infantry.daemon.api.spawns.SpawnText
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put

/**
 * RUNTIME-LOAD-7 (F5 last, docs/RUNTIME-LOAD.md section 13): `infantry_spawn`, `infantry_prompt`, `infantry_kill`, `infantry_session_restart`
 * and `infantry_session_remove`, moved out of the CLI's MCP server (`McpWriteTools`). Same names, descriptors (golden-checked) and answers.
 * The guardrails are the host's ([DaemonApi.spawns]): depth, concurrency, the pod cap and the kill scope, keyed by the CALLING session
 * (the MCP process's own `INFANTRY_SESSION_ID`), narrowed by the client's `--max-depth` / `--max-spawns` ([ToolCall.clientNarrowing]).
 * A kill, prompt, restart or delete is the frame the CLI sent, on the caller's own connection; its success answer is the `sessions` list
 * after the write (the daemon's fence for a plugin request), its refusal an `error`.
 */
internal object SpawnTools {
    const val SPAWN = "infantry_spawn"
    const val PROMPT = "infantry_prompt"
    const val KILL = "infantry_kill"
    const val RESTART = "infantry_session_restart"
    const val REMOVE = "infantry_session_remove"
    private const val TICKET_START = "infantry_ticket_start"
    private const val ERROR = "error"
    private const val DELETE_REFUSED = "session_delete refused: "

    /** A frame write is answered once the daemon has handled it; a spawn also creates a worktree and may start a process. */
    private const val FRAME_TIMEOUT_MS = 30_000L
    private const val SPAWN_TIMEOUT_MS = 120_000L
    private const val WRITE_TIMEOUT_MS = 45_000L
    private const val REMOVE_TIMEOUT_MS = 90_000L

    private val json = Json { ignoreUnknownKeys = true }

    /** CONTEXT-LIMIT-1: the one description of a contextLimit argument. */
    const val CONTEXT_LIMIT_HELP =
        "Context limit for the session in tokens (200000, 200k, 1m; off = none, the default): a larger one for a hard, " +
            "long task, a smaller one for a small job. How it is honoured depends on the harness: infantry_list_harnesses " +
            "shows contextLimit per harness (supported / best_effort / unsupported; unsupported = recorded, not applied)."

    /** EFFORT-1: the one description of an effort argument. */
    const val EFFORT_HELP =
        "Reasoning effort for the session: low, medium, high, xhigh or max (off = the harness default, the default). " +
            "How it is honoured depends on the harness: infantry_list_harnesses shows effort per harness " +
            "(supported / best_effort / unsupported; unsupported = recorded, not applied)."

    /** The caller as the host scopes it: the MCP process's own session (`INFANTRY_SESSION_ID`), else the connection's. */
    fun by(call: ToolCall): Caller = call.caller.copy(activeSession = call.clientSessionId ?: call.caller.activeSession)

    private fun narrowing(call: ToolCall) = call.clientNarrowing ?: SpawnNarrowing.NONE

    private fun string(args: JsonObject, key: String): String? = (args[key] as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }

    private fun flag(args: JsonObject, key: String): Boolean = (args[key] as? JsonPrimitive)?.booleanOrNull == true

    private fun ok(payload: JsonObject) = SpawnText.ok(payload)

    private fun refused(text: String) = SpawnText.refused(text)

    /** The host's non-Ok answer as a tool refusal: `<tool> refused: <why>`, a miss's own text, or unavailable. */
    private fun <T> failed(tool: String, r: SessionsResult<T>): ToolResult = when (r) {
        is SessionsResult.Missing -> refused(r.text)
        is SessionsResult.Refused -> refused("$tool refused: ${r.reason}")
        is SessionsResult.Unavailable -> refused("$tool is unavailable: ${r.reason}")
        is SessionsResult.Ok -> error("not a failure")
    }

    /** [type] with [data] on the caller's connection; the daemon's `sessions` list after the write, or the refusal worded as the frame's error. */
    private fun write(call: ToolCall, type: String, data: String, sessionId: String? = null): Result<String> = try {
        val reply = call.request(type, data, sessionId, FRAME_TIMEOUT_MS)
        if (reply.type == ERROR) Result.failure(IllegalStateException(reply.data)) else Result.success(reply.data)
    } catch (e: Exception) {
        Result.failure(IllegalStateException(e.message ?: e.javaClass.simpleName))
    }

    /** The session [id] as the host lists it now (after a write), else [fallback]. */
    private fun fresh(api: DaemonApi, call: ToolCall, id: String, fallback: SessionView): SessionView =
        (api.spawns.target(id, by(call)) as? SessionsResult.Ok)?.value ?: fallback

    private fun lists(sessionsJson: String, id: String): Boolean = runCatching {
        (json.parseToJsonElement(sessionsJson) as JsonArray).any { ((it as? JsonObject)?.get("id") as? JsonPrimitive)?.contentOrNull == id }
    }.getOrDefault(true)

    private fun prop(type: String, description: String) = buildJsonObject {
        put("type", type)
        put("description", description)
    }

    private fun JsonObjectBuilder.putBoolean(name: String) = put(name, buildJsonObject {
        put("type", "boolean")
        put("default", false)
        put("description", "Kill the process tree at once instead of after the grace period.")
    })

    private fun schema(required: List<String>, properties: JsonObjectBuilder.() -> Unit) = buildJsonObject {
        put("type", "object")
        put("properties", buildJsonObject(properties))
        if (required.isNotEmpty()) put("required", buildJsonArray { required.forEach { add(JsonPrimitive(it)) } })
        put("additionalProperties", false)
    }

    private fun annotations(destructive: Boolean) = buildJsonObject {
        put("readOnlyHint", false)
        put("destructiveHint", destructive)
        put("openWorldHint", false)
    }

    private val SESSION_ID = prop("string", "Session id, or a unique prefix of one.")

    class Spawn(private val api: DaemonApi) : DaemonTool {
        override val name = SPAWN
        override val description =
            "Spawn an Infantry AI session for a role and/or an agent profile, optionally sending it a first task, " +
                "OUTSIDE the board: it runs in the project folder and leaves every ticket untouched. Never use it " +
                "for ticket work: start ticket work with $TICKET_START (or pass ticketId here, which does " +
                "the same), so the worker runs the ticket's role, provider and model on its own ticket branch and " +
                "the ticket goes In Progress. Guarded: refused beyond the MCP spawn depth limit and beyond this " +
                "server's open-session limit."
        override val inputSchema = schema(emptyList()) {
            put("role", prop("string", "Role to spawn (role-first, like the `new` frame)."))
            put("agentId", prop("string", "Agent profile id; with role, it must be that profile's role."))
            put("projectId", prop("string", "Project the session belongs to (default: the server's --project / .infantry/mcp.json projectId)."))
            put("task", prop("string", "Optional first prompt for the new session."))
            put("contextLimit", prop("string", CONTEXT_LIMIT_HELP))
            put("effort", prop("string", EFFORT_HELP))
            put(
                "ticketId",
                prop(
                    "string",
                    "Start this board ticket instead (full id or its 8-char suffix), exactly as " +
                        "$TICKET_START: the ticket's own role, provider, model and description apply, " +
                        "so role / agentId / projectId must agree with it and task is refused.",
                ),
            )
        }
        override val annotations = annotations(destructive = false)
        override val timeoutMs = SPAWN_TIMEOUT_MS

        override fun call(call: ToolCall): ToolResult {
            val args = call.arguments
            // BUG-ASSIGN-1: work on a ticket starts on the board, never in a session spawned beside it.
            string(args, "ticketId")?.let { return startTicket(call, it) }
            val agentId = string(args, "agentId")
            val role = string(args, "role")
            val task = string(args, "task")
            if (agentId == null && role == null) return refused("$SPAWN needs a role or an agentId (or both)")
            // CONTEXT-LIMIT-1: `200000`, `200k`, `1m`; blank / off = none. Checked here so a typo never reaches the daemon.
            val contextLimit = ContextLimits.parse(string(args, "contextLimit"))
            contextLimit.error?.let { return refused("$SPAWN: $it") }
            val effort = EffortLevels.parse(string(args, "effort"))
            effort.error?.let { return refused("$SPAWN: $it") }
            val request = SpawnRequest(role, agentId, string(args, "projectId") ?: call.clientProjectId, contextLimit.value, effort.value, task)
            val spawned = when (val r = api.spawns.spawn(request, by(call), narrowing(call))) {
                is SessionsResult.Ok -> r.value
                else -> return failed(SPAWN, r)
            }
            spawned.taskRefused?.let { return refused("$SPAWN: session ${spawned.session.id} was created but its task was refused: $it") }
            val s = spawned.session
            return ok(buildJsonObject {
                put("sessionId", s.id)
                put("agentId", s.agentId)
                put("role", s.role)
                put("provider", s.provider)
                put("model", s.model)
                s.contextLimit?.let { put("contextLimit", it); put("contextLimitSupport", s.contextLimitSupport) }
                s.effort?.let { put("effort", it); put("effortSupport", s.effortSupport) }
                put("status", s.state)
                put("depth", spawned.depth)
                put("taskSent", spawned.taskSent)
                put("openSpawns", spawned.openSpawns)
                put("maxSpawns", spawned.maxSpawns)
            })
        }

        /**
         * `infantry_spawn` given a `ticketId`: the ticket decides who runs it. A role, an agentId or a project that disagrees with the
         * ticket is refused (the caller changes the ticket with `infantry_ticket_update`, not the start), and so is a `task`, because the
         * worker's brief is the ticket's own description.
         */
        private fun startTicket(call: ToolCall, ref: String): ToolResult {
            val ticket = when (val r = api.tickets.get(ref, by(call))) {
                is BoardResult.Ok -> r.value
                is BoardResult.Refused -> return refused("$SPAWN refused: ${r.text}")
                is BoardResult.Unavailable -> return refused("$SPAWN refused: ${r.reason}")
            }
            disagreement(ticket, call.arguments)?.let { return refused(it) }
            return when (val r = api.spawns.startTicket(ticket.id, by(call), narrowing(call))) {
                is SessionsResult.Ok -> SpawnText.startAnswer(SPAWN, r.value)
                else -> failed(SPAWN, r)
            }
        }

        private fun disagreement(ticket: TicketInfo, args: JsonObject): String? {
            val asked = SpawnText.roleOf(ticket)
            val short = SpawnText.shortTicketId(ticket.id)
            val fix = "Change the ticket with infantry_ticket_update (role / provider / model), then start it with $TICKET_START ticketId $short."
            string(args, "role")?.let { role ->
                // The same role spelled as the ticket stores it (`role:backend`) or in capitals is the same role.
                if (role.trim().lowercase().removePrefix("role:") != asked) {
                    return "$SPAWN refused: ticket $short asks for ${asked?.let { "role $it" } ?: "agent ${ticket.agentId ?: "(any)"}"}, " +
                        "not role $role; a start runs who the ticket names. $fix"
                }
            }
            string(args, "agentId")?.let { agentId ->
                if (agentId != ticket.agentId) return "$SPAWN refused: ticket $short is not assigned to agent $agentId; the board picks its worker. $fix"
            }
            string(args, "projectId")?.let { projectId ->
                if (projectId != ticket.projectId) return "$SPAWN refused: ticket $short belongs to project ${ticket.projectId ?: "(none)"}, not $projectId."
            }
            if (string(args, "task") != null) {
                return "$SPAWN refused: a ticket's worker is briefed from the ticket itself, so a task is not sent with it. " +
                    "Put the brief in the ticket's description (infantry_ticket_update), then start it with $TICKET_START ticketId $short."
            }
            return null
        }
    }

    class Prompt(private val api: DaemonApi) : DaemonTool {
        override val name = PROMPT
        override val description = "Send a prompt to an existing session (id or unique prefix). Slash commands are refused."
        override val inputSchema = schema(listOf("sessionId", "prompt")) {
            put("sessionId", SESSION_ID)
            put("prompt", prop("string", "The words to send to the agent."))
        }
        override val annotations = annotations(destructive = false)
        override val timeoutMs = WRITE_TIMEOUT_MS

        override fun call(call: ToolCall): ToolResult {
            val text = string(call.arguments, "prompt") ?: return refused("$PROMPT needs a prompt")
            // A slash command is executed by the daemon, not sent to the model: it could stop or swap a session around the kill rule.
            if (text.trimStart().startsWith("/")) return refused("$PROMPT refused: a prompt starting with '/' is a daemon command, not accepted over MCP")
            val session = when (val r = api.spawns.target(string(call.arguments, "sessionId") ?: return refused("sessionId is required"), by(call))) {
                is SessionsResult.Ok -> r.value
                else -> return failed(PROMPT, r)
            }
            write(call, "prompt", text, session.id).getOrElse { return refused("$PROMPT refused: ${it.message}") }
            val now = fresh(api, call, session.id, session)
            return ok(buildJsonObject {
                put("sessionId", now.id)
                put("status", now.state)
                put("runCount", now.runCount)
            })
        }
    }

    class Kill(private val api: DaemonApi) : DaemonTool {
        override val name = KILL
        override val description =
            "Kill a session (id or unique prefix). A pm (or the owner) may kill any session in its own project; " +
                "other roles only sessions they spawned. To kill AND remove a stuck session in one call, use " +
                "infantry_session_remove with force=true."
        override val inputSchema = schema(listOf("sessionId")) {
            put("sessionId", SESSION_ID)
            putBoolean("force")
        }
        override val annotations = annotations(destructive = true)
        override val timeoutMs = WRITE_TIMEOUT_MS

        override fun call(call: ToolCall): ToolResult {
            val session = when (val r = api.spawns.mayKill(string(call.arguments, "sessionId") ?: return refused("sessionId is required"), by(call))) {
                is SessionsResult.Ok -> r.value
                else -> return failed(KILL, r)
            }
            val force = flag(call.arguments, "force")
            write(call, "kill", if (force) "${session.id} force" else session.id).getOrElse { return refused("$KILL refused: ${it.message}") }
            val now = fresh(api, call, session.id, session)
            return ok(buildJsonObject {
                put("sessionId", now.id)
                put("status", now.state)
                put("live", now.live)
                now.killOutcome?.let { put("killOutcome", it) }
            })
        }
    }

    /** ROLE-NOTES-1: reboot a session in place (`session_restart`); a mid-turn session is refused unless `force`. */
    class Restart(private val api: DaemonApi) : DaemonTool {
        override val name = RESTART
        override val description =
            "Reboot a session in place: same id, role, harness, model, project and worktree, a fresh agent process " +
                "whose first prompt is composed anew (role notes, project notes, identity). Refused while the session " +
                "is mid-turn unless force is true, which kills the running turn's process tree and restarts."
        override val inputSchema = schema(listOf("sessionId")) {
            put("sessionId", SESSION_ID)
            putBoolean("force")
        }
        override val annotations = annotations(destructive = true)
        override val timeoutMs = WRITE_TIMEOUT_MS

        override fun call(call: ToolCall): ToolResult {
            val session = when (val r = api.spawns.target(string(call.arguments, "sessionId") ?: return refused("sessionId is required"), by(call))) {
                is SessionsResult.Ok -> r.value
                else -> return failed(RESTART, r)
            }
            val force = flag(call.arguments, "force")
            write(call, "session_restart", """{"sessionId":"${session.id}","force":$force}""").getOrElse { return refused("$RESTART refused: ${it.message}") }
            val now = fresh(api, call, session.id, session)
            return ok(buildJsonObject {
                put("sessionId", now.id)
                put("status", now.state)
            })
        }
    }

    /**
     * SESSION-RM-1: drop an ended session from the daemon's session list (the sidebar entry), through the daemon's own `session_delete`.
     * Any session may be removed, not only the caller's spawns, because nothing runs any more: a live one is refused before the frame is
     * sent, and the daemon refuses it again if it went live meanwhile. The ticket, the runs history, the branch/worktree and mail are not touched.
     */
    class Remove(private val api: DaemonApi) : DaemonTool {
        override val name = REMOVE
        override val description =
            "Remove an ended (exited, failed or orphaned) session from the session list, which is what the " +
                "sidebar shows. With force=true a running (BUSY / idle) session is killed first, then removed, in one call " +
                "(same scope rule as infantry_kill). Without force a running session is refused. Any ended session may be " +
                "removed, role agents included. Keeps the ticket, the runs history, the branch and worktree, " +
                "and mail; the removed session can no longer be resumed."
        override val inputSchema = schema(listOf("sessionId")) {
            put("sessionId", SESSION_ID)
            put("force", prop("boolean", "Kill the session first if it is running, then remove it."))
        }
        override val annotations = annotations(destructive = true)
        override val timeoutMs = REMOVE_TIMEOUT_MS

        override fun call(call: ToolCall): ToolResult {
            val requested = string(call.arguments, "sessionId") ?: return refused("sessionId is required")
            val session = when (val r = api.spawns.target(requested, by(call))) {
                is SessionsResult.Ok -> r.value
                // SESSION-GHOST-1: an id the daemon does not list is a clean no-op, not an error (a UI ghost row may still show it).
                is SessionsResult.Missing -> {
                    if (!r.text.startsWith("no session ")) return failed(REMOVE, r)
                    write(call, "session_delete", requested) // makes the daemon re-push its list to every client
                    return ok(buildJsonObject {
                        put("sessionId", requested)
                        put("ok", false)
                        put("removed", false)
                        put("reason", "not on this daemon")
                    })
                }
                else -> return failed(REMOVE, r)
            }
            if (session.live) {
                if (!flag(call.arguments, "force")) return refused("$REMOVE refused: ${liveRefusal(session)}")
                api.spawns.mayKill(session.id, by(call)).let { if (it !is SessionsResult.Ok) return failed(REMOVE, it) }
                write(call, "kill", "${session.id} force").getOrElse { return refused("$REMOVE refused: the kill failed: ${it.message}") }
                if (fresh(api, call, session.id, session).live) {
                    return refused("$REMOVE refused: session ${session.id} is still running after the kill; retry the same call")
                }
            }
            val after = write(call, "session_delete", session.id).getOrElse { return refused("$REMOVE refused: ${it.message?.removePrefix(DELETE_REFUSED)}") }
            if (lists(after, session.id)) return refused("$REMOVE: the daemon still lists session ${session.id}; check infantry_list_sessions")
            return ok(buildJsonObject {
                put("sessionId", session.id)
                put("removed", true)
                put("status", session.state)
                session.ticketId?.let { put("ticketId", it) }
            })
        }

        /** SESSION-RM-1: the one refusal for removing a running session. */
        private fun liveRefusal(session: SessionView) =
            "session ${session.id} is still running (${session.state}); kill it first: call infantry_session_remove with force=true " +
                "(CLI: infantry session rm ${session.id} --force) to kill and remove it in one step"
    }
}
