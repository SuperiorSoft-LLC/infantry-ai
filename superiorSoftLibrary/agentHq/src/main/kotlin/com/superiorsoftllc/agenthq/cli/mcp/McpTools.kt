package com.superiorsoftllc.agenthq.cli.mcp

import com.superiorsoftllc.agenthq.cli.TurnLine
import com.superiorsoftllc.agenthq.cli.AuthRefused
import com.superiorsoftllc.agenthq.cli.BoardSummary
import com.superiorsoftllc.agenthq.cli.shortTicketId
import com.superiorsoftllc.agenthq.protocol.Ticket
import com.superiorsoftllc.agenthq.cli.FrameStream
import com.superiorsoftllc.agenthq.cli.HarnessCatalog
import com.superiorsoftllc.agenthq.cli.HarnessInstallClient
import com.superiorsoftllc.agenthq.daemon.RosterRow
import com.superiorsoftllc.agenthq.orchestration.PodStatus
import com.superiorsoftllc.agenthq.protocol.AgentProfile
import com.superiorsoftllc.agenthq.protocol.Envelope
import com.superiorsoftllc.agenthq.protocol.FinishedPayload
import com.superiorsoftllc.agenthq.protocol.RosterRequest
import com.superiorsoftllc.agenthq.session.SessionRecord
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/**
 * The tools `agenthq mcp serve` exposes: the READ-ONLY ones here (P11-A), the write ones in [McpWriteTools] (P11-B). Each call opens its own [FrameStream] through
 * [connect] and closes it afterwards, so the server starts and answers `initialize` / `tools/list` even
 * while no daemon is running, and a daemon started later is picked up by the next call.
 *
 * Every daemon read goes over frames the CLI already sends (`sessions`, `agent_list`, `agent_roster`,
 * `pod_query`, `replay_request`, `mail_inbox`, `mail_read`): no new frame, no contract bump.
 */
internal class McpTools(
    private val connect: () -> FrameStream,
    guardrails: McpGuardrails = McpGuardrails(),
    private val getenv: (String) -> String? = { null },
    /** P11-C: the `projectId` used by `agenthq_spawn` / `agenthq_pod_state` when a call passes none. */
    private val defaultProjectId: String? = null,
) {

    /** P11-B: spawn / prompt / message / kill, with the server's depth, concurrency and kill-scope guardrails. */
    private val writes = McpWriteTools(guardrails, getenv, defaultProjectId)

    /** MCP-1: ticket create / update / get, over the CLI's own `ticket_save` / `ticket_list` frames. */
    private val tickets = McpTicketTools(getenv, defaultProjectId)

    /** MAIL-MCP-1: inbox / read, over the CLI's own `mail_inbox` / `mail_read` frames. */
    private val mail = McpMailTools(getenv)
    private val pipeline = McpPipelineTools(getenv)

    /** ASK-1: `agenthq_ask`, the non-blocking owner question, over the CLI's own `question_ask` frame. */
    private val questions = McpQuestionTools(getenv)

    /** Contract 1.117.0: `agenthq_progress`, the caller's own progress report, over the CLI's `progress` frame. */
    private val progress = McpProgressTools(getenv)

    /** STALL-4: `agenthq_watch_file`, the detached file watcher of `agenthq watch-file`. */
    private val watch = McpWatchTools(getenv, java.io.File(System.getProperty("user.dir") ?: "."))

    /** ROLE-EDIT-1: the role catalog (`agenthq_role_*`); an agent caller may only edit persona and description. */
    private val roles = McpRoleTools(getenv)
    private val projectConfig = McpProjectConfigTools()

    /** DAEMON-PLUGINS-1: the daemon plugins' tools (TICKET-FILES' two tools are Infantry_Board's now). */
    private val plugins = McpPluginTools()
    @Volatile private var pluginNames: Set<String>? = null

    /** DAEMON-PLUGINS-1: the daemon plugins' tools for `tools/list`; none when the daemon has none or is unreachable. */
    fun pluginDescriptors(): JsonArray {
        val list = runCatching {
            val stream = connect()
            try { runBlocking { plugins.descriptors(stream) } } finally { stream.close() }
        }.getOrElse { JsonArray(emptyList()) }
        pluginNames = list.mapNotNull { ((it as? JsonObject)?.get("name") as? JsonPrimitive)?.contentOrNull }.toSet()
        return list
    }

    /** True when [name] is a daemon plugin's tool (the daemon is asked once when `tools/list` has not been). */
    fun isPluginTool(name: String): Boolean = name in (pluginNames ?: run { pluginDescriptors(); pluginNames.orEmpty() })

    fun call(name: String, arguments: JsonObject): ToolResult {
        val stream = try {
            connect()
        } catch (e: AuthRefused) {
            return ToolResult(e.message ?: "the daemon refused this client's token", isError = true)
        } catch (e: Exception) {
            return ToolResult(DAEMON_UNREACHABLE, isError = true)
        }
        return try {
            runBlocking {
                when (name) {
                    LIST_SESSIONS -> listSessions(stream, arguments)
                    GET_SESSION -> getSession(stream, arguments)
                    POD_STATE -> podState(stream, arguments)
                    BOARD -> board(stream, arguments)
                    LIST_HARNESSES -> listHarnesses(stream)
                    HARNESS_STATUS -> harnessStatus(stream, arguments)
                    HARNESS_INSTALL -> harnessInstall(stream, arguments)
                    McpWriteTools.SPAWN -> writes.spawn(stream, arguments)
                    McpWriteTools.PROMPT -> writes.prompt(stream, arguments)
                    McpWriteTools.MESSAGE -> writes.message(stream, arguments)
                    McpWriteTools.KILL -> writes.kill(stream, arguments)
                    McpWriteTools.RESTART_SESSION -> writes.restart(stream, arguments)
                    McpWriteTools.REMOVE_SESSION -> writes.removeSession(stream, arguments)
                    McpMailTools.INBOX -> mail.inbox(stream, arguments)
                    McpMailTools.READ -> mail.read(stream, arguments)
                    McpPipelineTools.SHOW -> pipeline.show(stream, arguments)
                    McpPipelineTools.RECORD -> pipeline.record(stream, arguments)
                    McpQuestionTools.ASK -> questions.ask(stream, arguments)
                    McpProgressTools.PROGRESS -> progress.progress(stream, arguments)
                    McpWatchTools.WATCH_FILE -> watch.watchFile(arguments)
                    McpRoleTools.LIST -> roles.list(stream)
                    McpRoleTools.GET -> roles.get(stream, arguments)
                    McpRoleTools.UPDATE -> roles.update(stream, arguments)
                    McpRoleTools.CREATE -> roles.create(stream, arguments)
                    McpRoleTools.DELETE -> roles.delete(stream, arguments)
                    McpProjectConfigTools.GET -> projectConfig.get(stream, arguments)
                    McpProjectConfigTools.SET -> projectConfig.set(stream, arguments)
                    McpTicketTools.CREATE -> tickets.create(stream, arguments)
                    McpTicketTools.UPDATE -> tickets.update(stream, arguments)
                    McpTicketTools.GET -> tickets.get(stream, arguments)
                    McpTicketTools.EPIC_BASE -> tickets.epicBase(stream, arguments)
                    McpTicketTools.DELETE -> tickets.delete(stream, arguments)
                    McpTicketTools.MOVE -> tickets.move(stream, arguments)
                    McpTicketTools.START -> writes.startTicket(stream, arguments, McpTicketTools.START)
                    else -> plugins.call(stream, name, arguments)
                }
            }
        } catch (e: Exception) {
            ToolResult("$name failed: ${e.message ?: e.javaClass.simpleName}", isError = true)
        } finally {
            stream.close()
        }
    }

    private fun requestedProject(arguments: JsonObject): String? =
        (arguments["projectId"] as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }

    private suspend fun listSessions(stream: FrameStream, arguments: JsonObject): ToolResult {
        // A SESSION caller (AGENTHQ_SESSION_ID) sees its OWN project by default; explicit "all" lists every project and an
        // explicit id lists that project. Owner/user callers (no session id) are unaffected: all projects.
        val requested = requestedProject(arguments)
        val all = fetchSessions(stream)
        val callerId = getenv("AGENTHQ_SESSION_ID")?.trim()?.takeIf { it.isNotEmpty() }
        val caller = all.firstOrNull { it.id == callerId }
        if (callerId != null && !ProjectScope.isPrivileged(caller, true) && requested != null) {
            // Only the pm / owner roles may name another project or ask for "all"; the daemon's record says who this is.
            val refusal = ProjectScope.resolve(requested, all, getenv, defaultProjectId).refusal
            if (refusal != null) return ToolResult("$LIST_SESSIONS refused: $refusal", isError = true)
        }
        val scope = when {
            requested == "all" -> null
            requested != null -> requested
            else -> callerId?.let { caller?.projectId }
        }
        val sessions = all.filter { scope == null || it.projectId == scope }
        return ok(buildJsonObject {
            put("sessions", JsonArray(sessions.map { summary(it) }))
        })
    }

    /**
     * One session's status plus the last [MAX_LINES]-bounded lines of its transcript.
     *
     * The transcript comes from the daemon's replay buffer (`replay_request` from sequence 0, contract
     * 1.11.0), so it covers what THIS daemon process still retains -- not the session's whole life across
     * restarts. The replayed frames are bounded by a FENCE: the daemon handles one connection's frames in
     * order and writes the whole replay before it reads the next frame, so the answer to a `sessions`
     * request sent right after can only arrive once the replay is complete. No clock is involved.
     */
    private suspend fun getSession(stream: FrameStream, arguments: JsonObject): ToolResult {
        val requested = (arguments["sessionId"] as? JsonPrimitive)?.contentOrNull?.trim()
        if (requested.isNullOrEmpty()) return ToolResult("sessionId is required", isError = true)
        val limit = ((arguments["lines"] as? JsonPrimitive)?.intOrNull ?: DEFAULT_LINES).coerceIn(1, MAX_LINES)

        // The daemon pushes a `sessions` snapshot on every new connection; consuming it HERE is what keeps
        // it from being mistaken for the fence's answer below.
        val snapshot = decodeSessions(stream.await(Envelope.SESSIONS))
        val matches = snapshot.filter { it.id == requested }.ifEmpty { snapshot.filter { it.id.startsWith(requested) } }
        val session = when (matches.size) {
            1 -> matches.single()
            0 -> return ToolResult("no session '$requested' on this daemon", isError = true)
            else -> return ToolResult(
                "session id '$requested' is ambiguous: ${matches.joinToString(", ") { it.id }}",
                isError = true,
            )
        }
        ProjectScope.foreignRefusal("session ${session.id}", session.projectId, ProjectScope.callerOf(snapshot, getenv), getenv)
            ?.let { return ToolResult("$GET_SESSION refused: $it", isError = true) }

        if ((arguments["brief"] as? JsonPrimitive)?.contentOrNull == "true") {
            return ok(buildJsonObject {
                summary(session).forEach { (key, value) -> put(key, value) }
                put("liveState", BoardSummary.liveState(session))
                put("activity", BoardSummary.activity(session, System.currentTimeMillis()))
                session.ticketId?.let { put("ticketId", it) }
            })
        }

        stream.send(Envelope(REPLAY_REQUEST, """{"lastSequence":0}""", session.id))
        stream.send(Envelope(Envelope.SESSIONS))
        val entries = mutableListOf<StringBuilder>()
        var lastWasDelta = false
        var note: String? = null
        var fenced = false
        val deadline = System.currentTimeMillis() + FENCE_TIMEOUT_MS
        while (!fenced && System.currentTimeMillis() < deadline) {
            val frame = stream.next(POLL_MS) ?: continue
            if (frame.type == Envelope.SESSIONS) {
                fenced = true
                continue
            }
            if (frame.type == Envelope.ERROR && frame.sessionId == null && frame.data.startsWith("no replay history")) {
                note = frame.data
                continue
            }
            if (frame.sessionId != session.id) continue
            when (frame.type) {
                // `text_delta` is the readable translation of the raw stdout line, so the raw line is skipped
                // (the CLI's `watch` does the same); deltas of one turn are joined into one entry.
                Envelope.TEXT_DELTA -> {
                    if (!lastWasDelta) entries += StringBuilder()
                    entries.last().append(frame.data)
                    lastWasDelta = true
                    continue
                }
                // Contract 1.125.0: who the turn came from, from its typed origin; the text is the model-facing rendering.
                Envelope.TURN -> entries += StringBuilder(TurnLine.of(frame) + " " + frame.data.lineSequence().first().take(200))
                Envelope.STDERR -> entries += StringBuilder("[stderr] ${frame.data}")
                Envelope.ERROR -> entries += StringBuilder("[error] ${frame.data}")
                Envelope.FINISHED -> {
                    val payload = runCatching { json.decodeFromString(FinishedPayload.serializer(), frame.data) }.getOrNull()
                    entries += StringBuilder("[finished] ${payload?.outcome ?: "UNKNOWN"} exit=${payload?.exitCode ?: "?"}")
                }
                SESSION_REPLAY_GAP -> entries += StringBuilder("[replay gap] earlier output was evicted: ${frame.data}")
                else -> Unit
            }
            lastWasDelta = false
        }
        if (!fenced) note = (note?.let { "$it; " } ?: "") + "transcript may be incomplete: the daemon did not finish the replay in time"

        val lines = entries.flatMap { it.toString().lines() }.filter { it.isNotBlank() }
        val tail = lines.takeLast(limit)
        return ok(buildJsonObject {
            summary(session).forEach { (key, value) -> put(key, value) }
            put("runCount", session.runCount)
            session.lastExitCode?.let { put("lastExitCode", it) }
            session.lastError?.let { put("lastError", it) }
            session.projectId?.let { put("projectId", it) }
            session.ticketId?.let { put("ticketId", it) }
            put("transcript", buildJsonArray { tail.forEach { add(JsonPrimitive(it)) } })
            put("transcriptLines", tail.size)
            put("transcriptTruncated", lines.size > tail.size)
            note?.let { put("transcriptNote", it) }
        })
    }

    /**
     * The pod at a glance: every agent profile grouped by role, the sessions by status, and -- when asked
     * for -- one project's live roster and one pod's node state. Contract 1.x has no "list pods" frame
     * (see the CLI's `pods`), so a specific pod is only shown by id.
     */
    private suspend fun podState(stream: FrameStream, arguments: JsonObject): ToolResult {
        val sessionsAll = fetchSessions(stream)
        val scope = ProjectScope.resolve(requestedProject(arguments), sessionsAll, getenv, defaultProjectId)
        scope.refusal?.let { return ToolResult("$POD_STATE refused: $it", isError = true) }
        val projectId = scope.projectId
        val podId = (arguments["podId"] as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }

        val profiles = json.decodeFromString(
            ListSerializer(AgentProfile.serializer()),
            stream.request(Envelope(Envelope.AGENT_LIST, ""), Envelope.AGENTS, "agent_list", idempotent = true).data,
        )
        val sessions = sessionsAll.filter { projectId == null || it.projectId == projectId }
        val roster = projectId?.let { project ->
            json.decodeFromString(
                ListSerializer(RosterRow.serializer()),
                stream.request(
                    Envelope(Envelope.AGENT_ROSTER, json.encodeToString(RosterRequest.serializer(), RosterRequest(project))),
                    Envelope.ROSTER,
                    "agent_roster",
                    idempotent = true,
                ).data,
            )
        }
        val pod = podId?.let { id ->
            json.decodeFromString(
                PodStatus.serializer(),
                stream.request(Envelope(Envelope.POD_QUERY, id), Envelope.POD, "pod_query", idempotent = true).data,
            )
        }

        return ok(buildJsonObject {
            put("scope", scope.label)
            projectId?.let { put("projectId", it) }
            putJsonObject("agents") {
                put("total", profiles.size)
                putJsonObject("byRole") {
                    profiles.groupBy { it.role }.toSortedMap().forEach { (role, members) ->
                        put(role, buildJsonArray {
                            members.forEach { profile ->
                                add(buildJsonObject {
                                    put("id", profile.id)
                                    put("name", profile.name)
                                    profile.model?.let { put("model", it) }
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
                    sessions.groupBy { it.state.name }.toSortedMap().forEach { (state, list) -> put(state, list.size) }
                }
            }
            roster?.let { rows ->
                putJsonObject("roster") {
                    put("projectId", projectId)
                    put("agents", json.encodeToJsonElement(ListSerializer(RosterRow.serializer()), rows))
                }
            }
            pod?.let { put("pod", json.encodeToJsonElement(PodStatus.serializer(), it)) }
        })
    }


    /** One-call board summary: [BoardSummary]'s lines over the project's tickets and the daemon's sessions. */
    private suspend fun board(stream: FrameStream, arguments: JsonObject): ToolResult {
        val sessions = fetchSessions(stream)
        val scope = ProjectScope.resolve(requestedProject(arguments), sessions, getenv, defaultProjectId)
        scope.refusal?.let { return ToolResult("$BOARD refused: $it", isError = true) }
        val tickets = json.decodeFromString(
            ListSerializer(Ticket.serializer()),
            stream.request(Envelope(Envelope.TICKET_LIST, scope.projectId ?: ""), Envelope.TICKETS, "ticket_list", idempotent = true).data,
        )
        val lines = BoardSummary.render(tickets, sessions, System.currentTimeMillis(), BoardSummary.gitCommitOf(sessions))
            .map { line -> if (scope.projectId == null) tickets.firstOrNull { line.startsWith("  " + shortTicketId(it.id) + " ") }?.let { "  [${it.projectId}] ${line.trimStart()}" } ?: line else line }
        return ok(buildJsonObject {
            scope.projectId?.let { put("projectId", it) }
            put("scope", scope.label)
            put("board", buildJsonArray { lines.forEach { add(JsonPrimitive(it)) } })
        })
    }
    /**
     * BUG-ASSIGN-1: what an agent may pin on a ticket -- every harness (= provider id) and each one's models -- read
     * the same way as `agenthq harnesses` ([HarnessCatalog]). `models` is null when the harness lists none.
     */
    private suspend fun listHarnesses(stream: FrameStream): ToolResult {
        val harnesses = HarnessCatalog.read(stream)
        return ok(buildJsonObject {
            put("harnesses", buildJsonArray {
                harnesses.forEach { harness ->
                    add(buildJsonObject {
                        put("id", harness.id)
                        put("default", harness.default)
                        put("defaultModel", harness.defaultModel)
                        put("models", harness.models?.let { models -> buildJsonArray { models.forEach { add(JsonPrimitive(it)) } } } ?: JsonNull)
                        put("available", harness.available)
                        put("unavailableReason", harness.unavailableReason)
                        put("note", harness.note)
                    })
                }
            })
            put("howToUse", HarnessCatalog.HOW_TO_PIN)
        })
    }

    /** HARNESS-INSTALL-1: the same `harness_install_status` read as `agenthq harness-status`, as JSON plus the printed table. */
    private suspend fun harnessStatus(stream: FrameStream, arguments: JsonObject): ToolResult {
        val check = (arguments["checkLatest"] as? JsonPrimitive)?.contentOrNull == "true"
        val report = HarnessInstallClient.status(stream, check)
        return ok(buildJsonObject {
            put("report", json.encodeToJsonElement(com.superiorsoftllc.agenthq.protocol.HarnessInstallReport.serializer(), report))
            put("text", StringBuilder().also { HarnessInstallClient.render(report, it) }.toString())
        })
    }

    /** HARNESS-INSTALL-1: the same `harness_install` as `agenthq harness-install`; a failed harness makes the result an error. */
    private suspend fun harnessInstall(stream: FrameStream, arguments: JsonObject): ToolResult {
        val ids = (arguments["ids"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { s -> s.isNotEmpty() } }.orEmpty()
        val update = (arguments["update"] as? JsonPrimitive)?.contentOrNull == "true"
        val result = HarnessInstallClient.install(stream, ids, update)
        val payload = buildJsonObject {
            put("result", json.encodeToJsonElement(com.superiorsoftllc.agenthq.protocol.HarnessInstallResult.serializer(), result))
            put("text", StringBuilder().also { HarnessInstallClient.render(result, it) }.toString())
        }
        return ToolResult(json.encodeToString(JsonObject.serializer(), payload), isError = HarnessInstallClient.failed(result))
    }

    private suspend fun fetchSessions(stream: FrameStream): List<SessionRecord> =
        decodeSessions(stream.request(Envelope(Envelope.SESSIONS), Envelope.SESSIONS, "sessions", idempotent = true))

    private fun decodeSessions(frame: Envelope): List<SessionRecord> =
        json.decodeFromString(ListSerializer(SessionRecord.serializer()), frame.data)

    private fun summary(session: SessionRecord): JsonObject = buildJsonObject {
        put("id", session.id)
        put("sessionId", session.id) // same value as "id": the key every tool takes as input
        put("agentId", session.agentId)
        put("role", session.role)
        put("provider", session.provider)
        put("model", session.model)
        session.projectId?.let { put("projectId", it) }
        put("status", if (session.runPhase == "compacting") "compacting" else session.state.name)
        session.lastAction?.let { put("lastAction", it) }
        session.lastOutputAt?.let { put("lastOutputAt", it) }
        session.lastEventAt?.let { put("lastEventAt", it) }
        session.backgroundWork?.let { put("backgroundWork", it.label) }
        // Contract 1.117.0: the agent's own progress report, raw (for a monitor) and as one readable line.
        session.progress?.let { p ->
            put("progress", Json.encodeToJsonElement(com.superiorsoftllc.agenthq.session.SessionProgress.serializer(), p))
            put("progressLine", com.superiorsoftllc.agenthq.session.ProgressReducer.line(p, System.currentTimeMillis()))
        }
    }

    private fun ok(payload: JsonObject) = ToolResult(json.encodeToString(JsonObject.serializer(), payload))

    companion object {
        const val LIST_SESSIONS = "agenthq_list_sessions"
        const val GET_SESSION = "agenthq_get_session"
        const val POD_STATE = "agenthq_pod_state"
        const val BOARD = "agenthq_board"

        /** BUG-ASSIGN-1: the harnesses (= provider ids) a ticket may pin, and each one's models. */
        const val LIST_HARNESSES = "agenthq_list_harnesses"

        /** HARNESS-INSTALL-1: detect / install the harness CLIs (claude, opencode, pi) through npm, on a local or remote daemon. */
        const val HARNESS_STATUS = "agenthq_harness_status"
        const val HARNESS_INSTALL = "agenthq_harness_install"
        val NAMES = setOf(LIST_SESSIONS, GET_SESSION, POD_STATE, BOARD, LIST_HARNESSES, HARNESS_STATUS, HARNESS_INSTALL) + McpWriteTools.NAMES + McpMailTools.NAMES + McpTicketTools.NAMES + McpQuestionTools.NAMES + McpProgressTools.NAMES + McpWatchTools.NAMES + McpPipelineTools.NAMES + McpRoleTools.NAMES + McpProjectConfigTools.NAMES

        /** The one, fixed message a tool answers with when nothing is listening at the daemon address. */
        const val DAEMON_UNREACHABLE =
            "Infantry AI daemon is not reachable. Start it with `agenthq daemon` (or check --url / --port), then retry."

        const val DEFAULT_LINES = 50
        const val MAX_LINES = 200

        private const val REPLAY_REQUEST = "replay_request"
        private const val SESSION_REPLAY_GAP = "replay_gap"
        private const val POLL_MS = 100L
        private const val FENCE_TIMEOUT_MS = 10_000L

        private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; explicitNulls = true }

        /** `tools/list`: each tool's name, description and JSON Schema for its arguments. */
        fun descriptors(): JsonArray = buildJsonArray {
            add(tool(LIST_SESSIONS, "List the SESSIONS (running or past agent runs, not saved profiles) the Infantry AI daemon knows: id, agentId, role, provider, model, projectId, status.") {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("projectId") {
                        put("type", "string")
                        put("description", "Only this project's sessions. Omitted: a session caller's own project (everyone else: all). \"all\" lists every project (pm / owner callers only).")
                    }
                }
                put("additionalProperties", false)
            })
            add(tool(
                GET_SESSION,
                "One session's status and the last lines of its transcript (from the daemon's replay buffer, bounded).",
            ) {
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
                        put("description", "How many trailing transcript lines to return.")
                    }
                }
                put("required", buildJsonArray { add(JsonPrimitive("sessionId")) })
                put("additionalProperties", false)
            })
            add(tool(
                POD_STATE,
                "Summary of the pod: saved PROFILES (templates, not running) grouped by role under `agents`, SESSIONS by status; " +
                    "optionally one project's `roster` (its live AGENTS, BUSY/IDLE/OFFLINE: who can be assigned work) and one pod's node state.",
            ) {
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
            })
            add(tool(
                BOARD,
                "The board in one call: tickets grouped by column (backlog, todo, in_progress, pending, done), one line each with its " +
                    "worker (role, session id), the worker's live state (busy / idle / running background process), last action, " +
                    "last output age, branch and last commit. Use this instead of tickets + sessions + transcripts.",
            ) {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("projectId") {
                        put("type", "string")
                        put("description", "Project whose tickets to summarise. Default: the caller's own project; \"all\" (pm / owner callers only) covers every project, each line prefixed [projectId].")
                    }
                }
                put("additionalProperties", false)
            })
            add(tool(
                LIST_HARNESSES,
                "List the harnesses a ticket can run on: each harness id (the value of a ticket's provider) with its " +
                    "models (the values of its model), which one is the default, and whether its CLI is available. " +
                    "Pin them with agenthq_ticket_create / agenthq_ticket_update, then start the ticket with " +
                    "agenthq_ticket_start.",
            ) {
                put("type", "object")
                putJsonObject("properties") {}
                put("additionalProperties", false)
            })
            add(tool(
                HARNESS_STATUS,
                "Which harness CLIs (claude, opencode, pi) are installed on the daemon's machine: state installed / missing / " +
                    "outdated, the version, where it runs from, and whether npm is available. checkLatest=true asks npm for the " +
                    "latest version too (slower). Install the missing ones with agenthq_harness_install.",
            ) {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("checkLatest") {
                        put("type", "boolean")
                        put("description", "Also ask npm for each CLI's latest version (default false).")
                    }
                }
                put("additionalProperties", false)
            })
            add(tool(
                HARNESS_INSTALL,
                "npm-install harness CLIs on the daemon's machine, into Infantry AI's own prefix dir (no admin rights, no user data " +
                    "touched; idempotent). ids: which ones (default: every missing one). update=true also refreshes outdated ones. " +
                    "Needs Node/npm on that machine (the answer says so when it is missing). Sign-in is still the user's: each CLI keeps " +
                    "its own login. Can take minutes.",
            ) {
                put("type", "object")
                putJsonObject("properties") {
                    putJsonObject("ids") {
                        put("type", "array")
                        putJsonObject("items") { put("type", "string") }
                        put("description", "Harness ids to install, from: claude, opencode, pi. Default: all missing.")
                    }
                    putJsonObject("update") {
                        put("type", "boolean")
                        put("description", "Also update installed CLIs that are outdated (default false).")
                    }
                }
                put("additionalProperties", false)
            })
            McpWriteTools.descriptors().forEach { add(it) }
            McpTicketTools.descriptors().forEach { add(it) }
            McpMailTools.descriptors().forEach { add(it) }
            McpPipelineTools.descriptors().forEach { add(it) }
            McpRoleTools.descriptors().forEach { add(it) }
            McpProjectConfigTools.descriptors().forEach { add(it) }
            McpQuestionTools.descriptors().forEach { add(it) }
            McpProgressTools.descriptors().forEach { add(it) }
            McpWatchTools.descriptors().forEach { add(it) }
        }

        private fun tool(
            name: String,
            description: String,
            schema: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit,
        ) = buildJsonObject {
            put("name", name)
            put("description", description)
            put("inputSchema", buildJsonObject(schema))
            putJsonObject("annotations") { put("readOnlyHint", true) }
        }
    }
}
