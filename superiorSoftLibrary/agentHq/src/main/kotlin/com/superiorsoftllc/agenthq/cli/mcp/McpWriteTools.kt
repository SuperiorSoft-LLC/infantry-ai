package com.superiorsoftllc.agenthq.cli.mcp

import com.superiorsoftllc.agenthq.cli.FrameStream
import com.superiorsoftllc.agenthq.cli.TicketStarter
import com.superiorsoftllc.agenthq.cli.shortTicketId
import com.superiorsoftllc.agenthq.daemon.AccessPolicy
import com.superiorsoftllc.agenthq.harness.CommandLines
import com.superiorsoftllc.agenthq.mailbox.MailRefused
import com.superiorsoftllc.agenthq.mailbox.MailSendRequest
import com.superiorsoftllc.agenthq.mailbox.Message
import com.superiorsoftllc.agenthq.protocol.Envelope
import com.superiorsoftllc.agenthq.protocol.SpawnOptions
import com.superiorsoftllc.agenthq.protocol.Ticket
import com.superiorsoftllc.agenthq.protocol.TicketStatus
import com.superiorsoftllc.agenthq.session.SessionRecord
import com.superiorsoftllc.agenthq.session.SessionState
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import java.io.File

/**
 * The guardrails of `agenthq mcp serve`'s WRITE tools (P11-B), resolved once when the server starts.
 *
 * [depth] is how many MCP spawns sit above the session this server serves: 0 for a server a person (or an
 * agent nobody spawned through MCP) runs, N for a server running inside a session that an MCP server at
 * depth N-1 spawned. A spawn creates a session at `depth + 1`, refused when that exceeds [maxDepth].
 * [maxSpawns] bounds how many sessions spawned by THIS server may be open at once. [allowKillAny] lifts the
 * rule that `agenthq_kill` only stops sessions this server spawned.
 */
internal data class McpGuardrails(
    val depth: Int = 0,
    val maxDepth: Int = DEFAULT_MAX_DEPTH,
    val maxSpawns: Int = DEFAULT_MAX_SPAWNS,
    val allowKillAny: Boolean = false,
    /** Where spawned sessions' depths are recorded so their own MCP servers can read them; null = not recorded. */
    val lineage: McpLineage? = null,
) {
    companion object {
        const val DEFAULT_MAX_DEPTH = 2
        const val DEFAULT_MAX_SPAWNS = 4

        /** Explicit depth of the server process (overridden by `--mcp-depth`). */
        const val DEPTH_ENV = "AGENTHQ_MCP_DEPTH"

        /** Where the lineage records live (overridden by `--lineage-dir`). */
        const val LINEAGE_DIR_ENV = "AGENTHQ_MCP_LINEAGE_DIR"
    }
}

/**
 * HOW DEPTH CROSSES THE PROCESS BOUNDARY. The daemon, not this server, starts a spawned session's process,
 * so this server cannot put a variable in that process's environment. What it CAN do is write the child's
 * depth down, keyed by the child's session id, in a directory both processes see; the daemon already
 * exports `AGENTHQ_SESSION_ID` into every session it spawns, so the child's own `agenthq mcp serve` reads
 * its depth back from here. `--mcp-depth` / `AGENTHQ_MCP_DEPTH` override the lookup.
 *
 * One file per session: `<dir>/<sessionId>` holding the decimal depth.
 */
internal class McpLineage(private val dir: File) {

    fun record(sessionId: String, depth: Int) {
        val file = fileFor(sessionId) ?: return
        dir.mkdirs()
        file.writeText(depth.toString())
    }

    fun depthOf(sessionId: String?): Int? {
        val file = sessionId?.let(::fileFor) ?: return null
        return runCatching { file.readText().trim().toInt() }.getOrNull()
    }

    /** A session id is a file name here, so anything that could walk out of [dir] is not one. */
    private fun fileFor(sessionId: String): File? =
        sessionId.trim().takeIf { SAFE_ID.matches(it) }?.let { File(dir, it) }

    companion object {
        private val SAFE_ID = Regex("[A-Za-z0-9._-]{1,128}")

        fun defaultDir(): File = File(System.getProperty("user.home") ?: ".", ".agenthq/mcp-lineage")
    }
}

/**
 * The WRITE tools (P11-B): spawn, prompt, message, kill. Each runs on the [FrameStream] [McpTools.call]
 * opened for it and uses existing frames only -- `new`, `prompt`, `kill`, `mail_send`, with `sessions` as
 * the fence -- so there is no contract bump.
 *
 * THE FENCE. `new`, a named `prompt` and `kill` are answered with NOTHING on success and an `error` frame
 * (no session id) on refusal. The daemon handles one connection's frames in order, so the answer to a
 * `sessions` request sent right after can only arrive once the write has been handled: every error the
 * write caused is in front of it, and the list it carries already reflects the write. No clock decides.
 *
 * STATE. Which sessions this server spawned, and which of them it has since killed, live in this object for
 * the life of the server process: that is the scope of both the concurrency limit and the kill rule.
 */
internal class McpWriteTools(
    private val guardrails: McpGuardrails,
    private val getenv: (String) -> String?,
    /** P11-C: `--project` / `.agenthq/mcp.json`'s `projectId`, used when a spawn names no project. */
    private val defaultProjectId: String? = null,
) {
    /** Session ids this server spawned, in spawn order. */
    private val spawned = linkedSetOf<String>()

    /** Spawned sessions this server has killed: they no longer hold a slot unless they run again. */
    private val released = mutableSetOf<String>()

    suspend fun spawn(stream: FrameStream, arguments: JsonObject): ToolResult {
        // BUG-ASSIGN-1: work on a ticket starts on the board, never in a session spawned beside it.
        if (arguments.string("ticketId") != null) return startTicket(stream, arguments, SPAWN)
        val agentId = arguments.string("agentId")
        val role = arguments.string("role")
        val projectId = arguments.string("projectId") ?: defaultProjectId
        val task = arguments.string("task")
        if (agentId == null && role == null) return refused("agenthq_spawn needs a role or an agentId (or both)")

        val childDepth = guardrails.depth + 1
        if (childDepth > guardrails.maxDepth) {
            return refused(
                "agenthq_spawn refused: depth limit. This MCP server runs at depth ${guardrails.depth}, so the " +
                    "session would be at depth $childDepth, above the maximum of ${guardrails.maxDepth} " +
                    "(--max-depth). A session spawned through MCP may not spawn further than that.",
            )
        }

        val snapshot = decodeSessions(stream.await(Envelope.SESSIONS))
        val held = holding(snapshot)
        if (held.size >= guardrails.maxSpawns) {
            return refused(
                "agenthq_spawn refused: concurrency limit. This MCP server already has ${held.size} open " +
                    "session(s) of a maximum of ${guardrails.maxSpawns} (--max-spawns): ${held.joinToString(", ")}. " +
                    "Kill one with agenthq_kill, or wait for one to end, then retry.",
            )
        }

        val options = SpawnOptions(agentId = agentId, role = role, projectId = projectId)
        // BUG-G2d (contract 1.58.0): the `new` names the session this server runs inside, so the daemon charges the
        // spawn to that session's pod (cap and depth limit). Outside a session there is none, and nothing is charged.
        val origin = getenv(AccessPolicy.SESSION_ID_ENV)?.trim()?.takeIf { it.isNotEmpty() }
        val (errors, after) = fenced(stream, Envelope(Envelope.NEW, json.encodeToString(SpawnOptions.serializer(), options), origin))
        errors.firstOrNull()?.let { return refused(it) }
        val known = snapshot.map { it.id }.toSet()
        val created = after.orEmpty().filter { it.id !in known }
            .filter { (agentId == null || it.agentId == agentId) && (role == null || it.role == role) }
        val session = when (created.size) {
            1 -> created.single()
            0 -> return refused("agenthq_spawn: the daemon accepted the new frame but no new session appeared")
            else -> return refused(
                "agenthq_spawn: several new sessions match (${created.joinToString(", ") { it.id }}); another " +
                    "client spawned the same agent at the same moment, so this one cannot tell which is its own",
            )
        }
        spawned += session.id
        guardrails.lineage?.let { runCatching { it.record(session.id, childDepth) } }

        var current = session
        if (task != null) {
            val (promptErrors, afterPrompt) = fenced(stream, Envelope(Envelope.PROMPT, task, session.id))
            promptErrors.firstOrNull()?.let {
                return refused("agenthq_spawn: session ${session.id} was created but its task was refused: $it")
            }
            afterPrompt?.firstOrNull { it.id == session.id }?.let { current = it }
        }

        return ok(buildJsonObject {
            put("sessionId", current.id)
            put("agentId", current.agentId)
            put("role", current.role)
            put("provider", current.provider)
            put("model", current.model)
            put("status", current.state.name)
            put("depth", childDepth)
            put("taskSent", task != null)
            put("openSpawns", held.size + 1)
            put("maxSpawns", guardrails.maxSpawns)
        })
    }

    /**
     * BUG-ASSIGN-1: `agenthq_ticket_start`, and `agenthq_spawn` given a `ticketId`. ONE board ticket is started through
     * the board's own start ([TicketStarter]: a `ticket_move` to in_progress, handled by `TicketBoard.handOut` as a card
     * moved by a person is), never by a spawn beside the board. So the worker runs the ticket's role, provider and model,
     * in its own `ticket/<short-id>-<slug>` worktree, and the ticket goes In Progress with the calling session (this
     * server's `AGENTHQ_SESSION_ID`) recorded as its starter.
     *
     * GUARDED LIKE A SPAWN. A start that would run a worker is refused beyond the MCP depth limit and beyond this
     * server's open-session limit, and the worker it runs holds one of this server's slots, its depth recorded in the
     * lineage. A ticket already in progress runs nothing new, so it passes the limits and takes no slot; nor does a
     * start the board queues or holds.
     *
     * Through `agenthq_spawn`, the ticket decides who runs it: a role, an agentId or a project that disagrees with the
     * ticket is refused (the caller changes the ticket with `agenthq_ticket_update`, not the start), and so is a `task`,
     * because the worker's brief is the ticket's own description.
     */
    suspend fun startTicket(stream: FrameStream, arguments: JsonObject, tool: String): ToolResult {
        val requested = arguments.string("ticketId") ?: return refused("$tool needs a ticketId")
        // The daemon pushes a `sessions` snapshot on every new connection: consumed FIRST, so no later answer is taken for it.
        val snapshot = decodeSessions(stream.await(Envelope.SESSIONS))
        val ticket = McpTicketTools.findTicket(stream, requested, getenv).getOrElse { return refused("$tool refused: ${it.message}") }
        if (tool == SPAWN) disagreement(ticket, arguments)?.let { return refused(it) }

        val childDepth = guardrails.depth + 1
        val held = holding(snapshot)
        if (ticket.status != TicketStatus.IN_PROGRESS) {
            if (childDepth > guardrails.maxDepth) {
                return refused(
                    "$tool refused: depth limit. This MCP server runs at depth ${guardrails.depth}, so the ticket's " +
                        "worker would be at depth $childDepth, above the maximum of ${guardrails.maxDepth} " +
                        "(--max-depth). Leave the ticket in todo for a person or a shallower agent to start.",
                )
            }
            if (held.size >= guardrails.maxSpawns) {
                return refused(
                    "$tool refused: concurrency limit. This MCP server already has ${held.size} open session(s) of " +
                        "a maximum of ${guardrails.maxSpawns} (--max-spawns): ${held.joinToString(", ")}. Kill one with " +
                        "agenthq_kill, or wait for one to end, then retry.",
                )
            }
        }

        val origin = getenv(AccessPolicy.SESSION_ID_ENV)?.trim()?.takeIf { it.isNotEmpty() }
        return when (val outcome = TicketStarter.start(stream, ticket, origin)) {
            is TicketStarter.Outcome.Started -> {
                outcome.session?.let { worker ->
                    spawned += worker.id
                    guardrails.lineage?.let { runCatching { it.record(worker.id, childDepth) } }
                }
                val short = shortTicketId(outcome.ticket.id)
                ok(startAnswer(outcome.ticket, outcome.session, OUTCOME_STARTED) {
                    put("startedBy", outcome.startedBy)
                    put("depth", childDepth)
                    put("openSpawns", held.size + if (outcome.session != null) 1 else 0)
                    put("maxSpawns", guardrails.maxSpawns)
                    outcome.warning?.let { put("warning", it) }
                    // BUG-REGRESS-1: what the worker's branch was cut from, so the agent never has to guess what it holds.
                    outcome.note?.let { put("note", it) }
                    put(
                        "message",
                        "Started ticket $short: ${workerLine(outcome.ticket, outcome.session)}" +
                            (outcome.note?.let { ", $it" } ?: "") + ". It is In Progress on the " +
                            "board; follow its worker with agenthq_get_session.",
                    )
                })
            }
            is TicketStarter.Outcome.AlreadyInProgress -> ok(startAnswer(outcome.ticket, outcome.session, OUTCOME_ALREADY) {
                put(
                    "message",
                    "Ticket ${shortTicketId(outcome.ticket.id)} was already in progress: nothing new was started. " +
                        (outcome.session?.let { "Its worker is ${it.id}." } ?: "Its worker is not listed by the daemon."),
                )
            })
            is TicketStarter.Outcome.Queued -> ok(startAnswer(outcome.ticket, null, OUTCOME_QUEUED) {
                put("queuedFor", outcome.ticket.queuedFor ?: outcome.lane)
                put(
                    "message",
                    "Ticket ${shortTicketId(outcome.ticket.id)} is queued: every agent of its lane is busy" +
                        (outcome.lane?.let { " ($it)" } ?: "") + ". The board starts it when one frees up.",
                )
            })
            is TicketStarter.Outcome.Held -> refused(
                "$tool: ticket ${shortTicketId(outcome.ticket.id)} is held by open prerequisites and did not start" +
                    (outcome.detail?.let { ": $it" } ?: "") + ". It starts once they are done.",
            )
            is TicketStarter.Outcome.Refused -> refused(
                "$tool refused: ticket ${shortTicketId(outcome.ticket.id)} did not start: ${outcome.detail}",
            )
        }
    }

    /** What an `agenthq_spawn` naming a ticket asks that the ticket itself does not say: refused, never applied. */
    private fun disagreement(ticket: Ticket, arguments: JsonObject): String? {
        val asked = TicketStarter.roleOf(ticket)
        val short = shortTicketId(ticket.id)
        val fix = "Change the ticket with ${McpTicketTools.UPDATE} (role / provider / model), then start it with " +
            "${McpTicketTools.START} ticketId $short."
        arguments.string("role")?.let { role ->
            // The same role spelled as the ticket stores it (`role:backend`) or in capitals is the same role.
            if (role.trim().lowercase().removePrefix("role:") != asked) {
                return "$SPAWN refused: ticket $short asks for ${asked?.let { "role $it" } ?: "agent ${ticket.agentId ?: "(any)"}"}, " +
                    "not role $role; a start runs who the ticket names. $fix"
            }
        }
        arguments.string("agentId")?.let { agentId ->
            if (agentId != ticket.agentId) {
                return "$SPAWN refused: ticket $short is not assigned to agent $agentId; the board picks its worker. $fix"
            }
        }
        arguments.string("projectId")?.let { projectId ->
            if (projectId != ticket.projectId) {
                return "$SPAWN refused: ticket $short belongs to project ${ticket.projectId ?: "(none)"}, not $projectId."
            }
        }
        if (arguments.string("task") != null) {
            return "$SPAWN refused: a ticket's worker is briefed from the ticket itself, so a task is not sent with it. " +
                "Put the brief in the ticket's description (${McpTicketTools.UPDATE}), then start it with " +
                "${McpTicketTools.START} ticketId $short."
        }
        return null
    }

    /** The answer of a ticket start: the ticket, and the worker it runs as the daemon lists it. */
    private fun startAnswer(
        ticket: Ticket,
        worker: SessionRecord?,
        outcome: String,
        extra: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit,
    ) = buildJsonObject {
        put("ticketId", ticket.id)
        put("title", ticket.title)
        put("status", ticket.status)
        put("outcome", outcome)
        put("started", outcome == OUTCOME_STARTED)
        put("sessionId", worker?.id ?: ticket.sessionId)
        put("agentId", worker?.agentId)
        put("role", worker?.role ?: TicketStarter.roleOf(ticket))
        put("provider", worker?.provider ?: ticket.provider)
        put("model", worker?.model ?: ticket.model)
        put("branch", worker?.worktreeBranch ?: ticket.branch)
        put("worktree", worker?.worktreePath)
        put("workingDir", worker?.workingDir)
        extra()
    }

    private fun workerLine(ticket: Ticket, worker: SessionRecord?): String = if (worker == null) {
        "its worker is not listed by the daemon yet"
    } else {
        "session ${worker.id} (agent ${worker.agentId ?: "?"}, role ${worker.role ?: "?"}) runs " +
            "${worker.provider}${worker.model?.let { " / $it" } ?: ""} on branch ${worker.worktreeBranch ?: ticket.branch ?: "?"} " +
            "in ${worker.worktreePath ?: worker.workingDir}"
    }

    suspend fun prompt(stream: FrameStream, arguments: JsonObject): ToolResult {
        val text = arguments.string("prompt") ?: return refused("agenthq_prompt needs a prompt")
        if (CommandLines.isCommand(text)) {
            // A slash command is executed by the daemon, not sent to the model: it could stop or swap a session
            // around the kill rule. Through MCP a prompt is only ever words for the agent.
            return refused("agenthq_prompt refused: a prompt starting with '/' is a daemon command, not accepted over MCP")
        }
        val snapshot = decodeSessions(stream.await(Envelope.SESSIONS))
        val session = resolveSession(snapshot, arguments) { return it }
        val (errors, after) = fenced(stream, Envelope(Envelope.PROMPT, text, session.id))
        errors.firstOrNull()?.let { return refused("agenthq_prompt refused: $it") }
        val now = after?.firstOrNull { it.id == session.id } ?: session
        return ok(buildJsonObject {
            put("sessionId", now.id)
            put("status", now.state.name)
            put("runCount", now.runCount)
        })
    }

    suspend fun kill(stream: FrameStream, arguments: JsonObject): ToolResult {
        val snapshot = decodeSessions(stream.await(Envelope.SESSIONS))
        val session = resolveSession(snapshot, arguments) { return it }
        killRefusal(session, snapshot)?.let { return refused("$KILL refused: $it") }
        val force = (arguments["force"] as? JsonPrimitive)?.booleanOrNull == true
        val (errors, after) = fenced(stream, Envelope(Envelope.KILL, if (force) "${session.id} force" else session.id))
        errors.firstOrNull()?.let { return refused("agenthq_kill refused: $it") }
        released += session.id
        val now = after?.firstOrNull { it.id == session.id } ?: session
        return ok(buildJsonObject {
            put("sessionId", now.id)
            put("status", now.state.name)
            put("live", now.live)
            now.killOutcome?.let { put("killOutcome", it.name) }
        })
    }

    /**
     * Contract 1.114.0: who may kill [session], or null when the caller may. No session id (the owner / CLI) or
     * `--allow-kill-any`: any session. A privileged role (pm): any session in its own project, whichever MCP process
     * spawned it. Every other role: the sessions it spawned, matched by the recorded spawner session id (so a spawn
     * from before an app restart still counts) or by this server's own spawn list. No session, privileged or not, may
     * kill ITSELF: the pm's project scope covers its own session, but a self-kill ends the caller mid-turn. Its own
     * spawns are checked before the pm's project rule, so a pm's helper with no project is still the pm's to kill.
     */
    internal fun killRefusal(session: SessionRecord, snapshot: List<SessionRecord>): String? {
        if (guardrails.allowKillAny) return null
        val callerId = getenv(AccessPolicy.SESSION_ID_ENV)?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        if (session.id == callerId) {
            return "session ${session.id} is this session itself; an agent may not kill its own session. " +
                "End your turn instead, or ask the owner to stop it."
        }
        // Its own spawns, whatever the role: a pm's no-ticket helper may carry no project, and the project rule must
        // not take back what the spawn rule gives.
        if (session.id in spawned || session.spawnerSessionId == callerId) return null
        val caller = ProjectScope.callerOf(snapshot, getenv)
        if (ProjectScope.isPrivileged(caller, true)) {
            // The project rule for kill is stricter than reading: even a pm reaches only its OWN project.
            val own = caller?.projectId ?: getenv(AccessPolicy.PROJECT_ID_ENV)?.trim()?.takeIf { it.isNotEmpty() }
            if (own == null || session.projectId == own) return null
            return "session ${session.id} belongs to project '${session.projectId ?: "none"}', not this session's project '$own'; " +
                "a pm may only kill sessions in its own project. Ask a pm of that project to do it."
        }
        return "session ${session.id} was not spawned by this session ($callerId); a ${caller?.role ?: "worker"} may only kill sessions it " +
            "spawned itself. Ask the pm to do it: agenthq send pm \"please remove session ${session.id}\""
    }

    /** ROLE-NOTES-1: reboot a session in place (`session_restart`); a mid-turn session is refused unless `force`. */
    suspend fun restart(stream: FrameStream, arguments: JsonObject): ToolResult {
        val snapshot = decodeSessions(stream.await(Envelope.SESSIONS))
        val session = resolveSession(snapshot, arguments) { return it }
        val force = (arguments["force"] as? JsonPrimitive)?.booleanOrNull == true
        val (errors, after) = fenced(stream, Envelope(Envelope.SESSION_RESTART, """{"sessionId":"${session.id}","force":$force}"""))
        errors.firstOrNull()?.let { return refused("agenthq_session_restart refused: $it") }
        val now = after?.firstOrNull { it.id == session.id } ?: session
        return ok(buildJsonObject {
            put("sessionId", now.id)
            put("status", now.state.name)
        })
    }

    /**
     * SESSION-RM-1: drop an ended session from the daemon's session list (the sidebar entry), through the daemon's own
     * `session_delete`. Any session may be removed, not only this server's spawns, because nothing runs any more: a
     * live one is refused here, before the frame is sent, and the daemon refuses it again if it went live meanwhile.
     * The ticket, the runs history, the branch/worktree and mail are not touched.
     */
    suspend fun removeSession(stream: FrameStream, arguments: JsonObject): ToolResult {
        val snapshot = decodeSessions(stream.await(Envelope.SESSIONS))
        val session = resolveSession(snapshot, arguments) { return it }
        val force = (arguments["force"] as? JsonPrimitive)?.booleanOrNull == true
        if (session.live) {
            if (!force) return refused("$REMOVE_SESSION refused: ${liveRefusal(session)}")
            killRefusal(session, snapshot)?.let { return refused("$REMOVE_SESSION refused: $it") }
            val (killErrors, afterKill) = fenced(stream, Envelope(Envelope.KILL, "${session.id} force"))
            killErrors.firstOrNull()?.let { return refused("$REMOVE_SESSION refused: the kill failed: $it") }
            if (afterKill?.firstOrNull { it.id == session.id }?.live == true) {
                return refused("$REMOVE_SESSION refused: session ${session.id} is still running after the kill; retry the same call")
            }
        }
        val (errors, after) = fenced(stream, Envelope(Envelope.SESSION_DELETE, session.id))
        errors.firstOrNull()?.let { return refused("$REMOVE_SESSION refused: ${it.removePrefix(DELETE_REFUSED)}") }
        if (after == null || after.any { it.id == session.id }) {
            return refused("$REMOVE_SESSION: the daemon still lists session ${session.id}; check agenthq_list_sessions")
        }
        return ok(buildJsonObject {
            put("sessionId", session.id)
            put("removed", true)
            put("status", session.state.name)
            session.ticketId?.let { put("ticketId", it) }
        })
    }

    /**
     * Mail through the Infantry AI mailbox, exactly as `agenthq send` does it: the sender is the session named by
     * `AGENTHQ_SESSION_ID` (the daemon exports it into every spawned agent), else the human handle `user`;
     * the answer is the LAST `mail`/`mail_refused` frame before the `mail_outbox` fence.
     */
    suspend fun message(stream: FrameStream, arguments: JsonObject): ToolResult {
        val to = arguments.string("to") ?: return refused("agenthq_message needs a recipient (to)")
        val body = arguments.string("body") ?: return refused("agenthq_message needs a body")
        val session = getenv(AccessPolicy.SESSION_ID_ENV)?.trim()?.takeIf { it.isNotEmpty() }
        val sender = session ?: HUMAN_SENDER
        val request = MailSendRequest(
            from = if (session == null) HUMAN_SENDER else "",
            to = to,
            subject = arguments.string("subject") ?: "",
            body = body,
            replyTo = arguments.string("replyTo"),
            allowCrossProject = (arguments["allowCrossProject"] as? JsonPrimitive)?.booleanOrNull == true,
        )
        val answer = stream.requestLastOf(
            Envelope(Envelope.MAIL_SEND, json.encodeToString(MailSendRequest.serializer(), request), session),
            answerTypes = setOf(Envelope.MAIL, Envelope.MAIL_REFUSED),
            fence = Envelope(Envelope.MAIL_OUTBOX, sender),
            fenceType = Envelope.MAILS,
            what = "mail_send",
            dropWarning = "Do not re-send unless a duplicate message is acceptable",
        )
        if (answer.type == Envelope.MAIL_REFUSED) {
            val reason = runCatching { json.decodeFromString(MailRefused.serializer(), answer.data).reason }.getOrNull()
            return refused("agenthq_message refused: ${reason ?: answer.data}")
        }
        val message = json.decodeFromString(Message.serializer(), answer.data)
        if (message.failed) return refused("agenthq_message: ${message.failureReason ?: "the message could not be delivered"}")
        return ok(buildJsonObject {
            put("messageId", message.id)
            put("threadId", message.threadId)
            put("from", message.from)
            put("to", message.to)
            put("state", message.state.name)
        })
    }

    /**
     * The spawned sessions that hold a concurrency slot: still known to the daemon and either running, or open
     * (not ended) and not killed by this server. A killed session that is prompted back to life counts again.
     */
    private fun holding(snapshot: List<SessionRecord>): List<String> {
        val byId = snapshot.associateBy { it.id }
        return spawned.filter { id ->
            val record = byId[id] ?: return@filter false
            record.live || (id !in released && record.state !in ENDED)
        }
    }

    /** Send [frames], then the `sessions` fence: the control errors they caused, and the list once handled. */
    private suspend fun fenced(stream: FrameStream, vararg frames: Envelope): Pair<List<String>, List<SessionRecord>?> {
        frames.forEach { stream.send(it) }
        stream.send(Envelope(Envelope.SESSIONS))
        val errors = mutableListOf<String>()
        val deadline = System.currentTimeMillis() + FENCE_TIMEOUT_MS
        while (System.currentTimeMillis() < deadline) {
            val frame = stream.next(POLL_MS) ?: continue
            when {
                frame.type == Envelope.SESSIONS -> return errors to decodeSessions(frame)
                // A reply to THIS connection has no session id; a run's own failure lines carry theirs.
                // A launch warning is advice ("the run starts anyway"), not a refusal.
                frame.type == Envelope.ERROR && frame.sessionId == null && !frame.data.startsWith("launch warning:") ->
                    errors += frame.data
            }
        }
        return (errors + "the daemon did not answer within ${FENCE_TIMEOUT_MS}ms") to null
    }

    private inline fun resolveSession(
        snapshot: List<SessionRecord>,
        arguments: JsonObject,
        fail: (ToolResult) -> Nothing,
    ): SessionRecord {
        val requested = arguments.string("sessionId") ?: fail(refused("sessionId is required"))
        val matches = snapshot.filter { it.id == requested }.ifEmpty { snapshot.filter { it.id.startsWith(requested) } }
        return when (matches.size) {
            1 -> matches.single()
            0 -> fail(refused("no session '$requested' on this daemon"))
            else -> fail(refused("session id '$requested' is ambiguous: ${matches.joinToString(", ") { it.id }}"))
        }
    }

    private fun decodeSessions(frame: Envelope): List<SessionRecord> =
        json.decodeFromString(com.superiorsoftllc.agenthq.session.SessionListWire, frame.data)

    private fun ok(payload: JsonObject) = ToolResult(json.encodeToString(JsonObject.serializer(), payload))

    private fun refused(text: String) = ToolResult(text, isError = true)

    private fun JsonObject.string(key: String): String? =
        (this[key] as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }

    companion object {
        const val SPAWN = "agenthq_spawn"
        const val PROMPT = "agenthq_prompt"
        const val MESSAGE = "agenthq_message"
        const val KILL = "agenthq_kill"
        const val REMOVE_SESSION = "agenthq_session_remove"
        const val RESTART_SESSION = "agenthq_session_restart"
        val NAMES = listOf(SPAWN, PROMPT, MESSAGE, KILL, REMOVE_SESSION, RESTART_SESSION)

        /** The prefix of the daemon's `session_delete` refusal, dropped where the caller names its own command. */
        internal const val DELETE_REFUSED = "session_delete refused: "

        /** SESSION-RM-1: the one refusal for removing a running session, shared by `agenthq session rm` and this tool. */
        internal fun liveRefusal(session: SessionRecord) =
            "session ${session.id} is still running (${session.state}); kill it first: call agenthq_session_remove with force=true " +
                "(CLI: agenthq session rm ${session.id} --force) to kill and remove it in one step"

        private const val HUMAN_SENDER = "user"
        private const val OUTCOME_STARTED = "started"
        private const val OUTCOME_ALREADY = "already_in_progress"
        private const val OUTCOME_QUEUED = "queued"
        private val ENDED = setOf(SessionState.EXITED, SessionState.FAILED, SessionState.ORPHANED)
        private const val POLL_MS = 100L
        private const val FENCE_TIMEOUT_MS = 30_000L

        private val json = Json { ignoreUnknownKeys = true; encodeDefaults = false; explicitNulls = false }

        /** `tools/list` entries for the write tools. */
        fun descriptors() = buildJsonArray {
            add(tool(
                SPAWN,
                "Spawn an Infantry AI session for a role and/or an agent profile, optionally sending it a first task, " +
                    "OUTSIDE the board: it runs in the project folder and leaves every ticket untouched. Never use it " +
                    "for ticket work: start ticket work with ${McpTicketTools.START} (or pass ticketId here, which does " +
                    "the same), so the worker runs the ticket's role, provider and model on its own ticket branch and " +
                    "the ticket goes In Progress. Guarded: refused beyond the MCP spawn depth limit and beyond this " +
                    "server's open-session limit.",
                destructive = false,
                properties = {
                    put("role", prop("string", "Role to spawn (role-first, like the `new` frame)."))
                    put("agentId", prop("string", "Agent profile id; with role, it must be that profile's role."))
                    put("projectId", prop("string", "Project the session belongs to (default: the server's --project / .agenthq/mcp.json projectId)."))
                    put("task", prop("string", "Optional first prompt for the new session."))
                    put(
                        "ticketId",
                        prop(
                            "string",
                            "Start this board ticket instead (full id or its 8-char suffix), exactly as " +
                                "${McpTicketTools.START}: the ticket's own role, provider, model and description apply, " +
                                "so role / agentId / projectId must agree with it and task is refused.",
                        ),
                    )
                },
                required = emptyList(),
            ))
            add(tool(
                PROMPT,
                "Send a prompt to an existing session (id or unique prefix). Slash commands are refused.",
                destructive = false,
                properties = {
                    put("sessionId", prop("string", "Session id, or a unique prefix of one."))
                    put("prompt", prop("string", "The words to send to the agent."))
                },
                required = listOf("sessionId", "prompt"),
            ))
            add(tool(
                MESSAGE,
                "Send Infantry AI COMMS mail to an agent id, a role (of the sender's project) or a session id.",
                destructive = false,
                properties = {
                    put("to", prop("string", "Recipient: agent id, role, or session id."))
                    put("body", prop("string", "The message."))
                    put("subject", prop("string", "Optional subject."))
                    put("replyTo", prop("string", "Optional message id this answers."))
                    put("allowCrossProject", prop("boolean", "Send even though the recipient is in another project (refused by default)."))
                },
                required = listOf("to", "body"),
            ))
            add(tool(
                KILL,
                "Kill a session (id or unique prefix). A pm (or the owner) may kill any session in its own project; " +
                    "other roles only sessions they spawned. To kill AND remove a stuck session in one call, use " +
                    "agenthq_session_remove with force=true.",
                destructive = true,
                properties = {
                    put("sessionId", prop("string", "Session id, or a unique prefix of one."))
                    putBoolean("force")
                },
                required = listOf("sessionId"),
            ))
            add(tool(
                REMOVE_SESSION,
                "Remove an ended (exited, failed or orphaned) session from the session list, which is what the " +
                    "sidebar shows. With force=true a running (BUSY / idle) session is killed first, then removed, in one call " +
                    "(same scope rule as agenthq_kill). Without force a running session is refused. Any ended session may be " +
                    "removed, role agents included. Keeps the ticket, the runs history, the branch and worktree, " +
                    "and mail; the removed session can no longer be resumed.",
                destructive = true,
                properties = {
                    put("sessionId", prop("string", "Session id, or a unique prefix of one."))
                    put("force", prop("boolean", "Kill the session first if it is running, then remove it."))
                },
                required = listOf("sessionId"),
            ))
            add(tool(
                RESTART_SESSION,
                "Reboot a session in place: same id, role, harness, model, project and worktree, a fresh agent process " +
                    "whose first prompt is composed anew (role notes, project notes, identity). Refused while the session " +
                    "is mid-turn unless force is true, which kills the running turn's process tree and restarts.",
                destructive = true,
                properties = {
                    put("sessionId", prop("string", "Session id, or a unique prefix of one."))
                    putBoolean("force")
                },
                required = listOf("sessionId"),
            ))
        }

        private fun kotlinx.serialization.json.JsonObjectBuilder.putBoolean(name: String) =
            put(name, buildJsonObject {
                put("type", "boolean")
                put("default", false)
                put("description", "Kill the process tree at once instead of after the grace period.")
            })

        private fun prop(type: String, description: String) = buildJsonObject {
            put("type", type)
            put("description", description)
        }

        private fun tool(
            name: String,
            description: String,
            destructive: Boolean,
            properties: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit,
            required: List<String>,
        ) = buildJsonObject {
            put("name", name)
            put("description", description)
            put("inputSchema", buildJsonObject {
                put("type", "object")
                put("properties", buildJsonObject(properties))
                if (required.isNotEmpty()) put("required", buildJsonArray { required.forEach { add(JsonPrimitive(it)) } })
                put("additionalProperties", false)
            })
            put("annotations", buildJsonObject {
                put("readOnlyHint", false)
                put("destructiveHint", destructive)
                put("openWorldHint", false)
            })
        }
    }
}
