package com.superiorsoftllc.agenthq.cli

import com.superiorsoftllc.agenthq.protocol.Envelope
import com.superiorsoftllc.agenthq.protocol.Ticket
import com.superiorsoftllc.agenthq.protocol.TicketMove
import com.superiorsoftllc.agenthq.protocol.TicketStart
import com.superiorsoftllc.agenthq.protocol.TicketStatus
import com.superiorsoftllc.agenthq.session.SessionRecord
import com.superiorsoftllc.agenthq.session.TicketWorkerHealth
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/**
 * BUG-ASSIGN-1: the ONE way an agent starts a ticket, shared by `agenthq ticket start` and the MCP tools
 * `agenthq_ticket_start` / `agenthq_spawn` given a `ticketId`.
 *
 * It is the board's own start and nothing of its own: a `ticket_move` to `in_progress`, which the daemon hands to
 * `TicketBoard.handOut` exactly as it does a person moving the card. So the worker gets the ticket's role, provider and
 * model (ticket pins > profile > role binding > default), its own `ticket/<short-id>-<slug>` worktree, and the ticket
 * goes In Progress with the calling agent's session (the frame's session id) recorded as its starter. Nothing here
 * picks an executor or a folder. Existing frames only (`ticket_move`, `sessions`): no contract bump.
 *
 * THE ANSWER is the `ticket` frame for THIS ticket that carries `start`: pushed to every client when the start changed
 * the board, replied to this connection when it did not (a refusal, a held ticket). Other tickets' pushes, and this
 * ticket's own pushes without `start` (its worker's first status event can arrive first), are skipped, never taken
 * for it.
 */
internal object TicketStarter {

    sealed interface Outcome {
        val ticket: Ticket

        /** The board started it. [session] is its worker as the daemon lists it (null only if the listing missed it). */
        data class Started(
            override val ticket: Ticket,
            val session: SessionRecord?,
            /** The session the daemon recorded as the ticket's starter: the caller's own, when the daemon knows it. */
            val startedBy: String?,
            /** A launch warning (a spent budget, a limited provider): advice, the run started anyway. */
            val warning: String?,
            /**
             * BUG-REGRESS-1: what the board cut the worker's branch from (`cut from <ref> <short sha>`); null when
             * the start reused the branch the ticket already had, or ran without one.
             */
            val note: String? = null,
        ) : Outcome

        /** It was already in progress with a worker that can carry it: nothing was started, [session] is that worker. */
        data class AlreadyInProgress(override val ticket: Ticket, val session: SessionRecord?) : Outcome

        /** The board ran nothing for this start: queued, held or refused. */
        sealed interface NotStarted : Outcome

        /** Every agent of its lane is busy: the board queued it in [lane] and starts it when one frees up. */
        data class Queued(override val ticket: Ticket, val lane: String?) : NotStarted

        /** Open prerequisites hold it ([detail] names them): it cannot start until they are done. */
        data class Held(override val ticket: Ticket, val detail: String?) : NotStarted

        /** The daemon refused the start (no profile for the role, a refused provider or model, a refused branch...). */
        data class Refused(override val ticket: Ticket, val detail: String) : NotStarted
    }

    /**
     * Start [ticket] as [origin] (the calling agent's session id, null for a person) and wait for the board's answer.
     * NOT idempotent: a connection that drops after the move was sent is reported, never re-sent.
     */
    suspend fun start(stream: FrameStream, ticket: Ticket, origin: String?, timeoutMs: Long = stream.answerTimeoutMs): Outcome {
        if (ticket.status == TicketStatus.IN_PROGRESS) {
            // GAP-006b: an in-progress ticket whose worker cannot carry it (gone, or never briefed) is started again by
            // the same move; one whose worker can is left alone. The daemon applies the same rule and has the last word.
            val worker = ticket.sessionId?.let { id -> sessions(stream).firstOrNull { it.id == id } }
            if (TicketWorkerHealth.problem(worker, ticket.sessionId) == null) return Outcome.AlreadyInProgress(ticket, worker)
        }
        val move = TicketMove(id = ticket.id, status = TicketStatus.IN_PROGRESS)
        stream.send(Envelope(Envelope.TICKET_MOVE, json.encodeToString(TicketMove.serializer(), move), origin))
        // A reconnect BEFORE or DURING that send delivered it on the new link; only one after it can lose the answer.
        stream.takeReconnected()
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val frame = stream.next(POLL_MS)
            if (frame == null) {
                if (stream.takeReconnected()) return Outcome.Refused(ticket, LOST)
                continue
            }
            when {
                frame.type == Envelope.TICKET -> {
                    val answered = runCatching { json.decodeFromString(Ticket.serializer(), frame.data) }.getOrNull() ?: continue
                    val start = answered.start
                    if (answered.id == ticket.id && start != null) return outcomeOf(stream, answered, start, origin)
                }
                // A reply to THIS connection carries no session id; a run's own failure lines carry theirs.
                frame.type == Envelope.ERROR && frame.sessionId == null && !frame.data.startsWith("launch warning:") ->
                    return Outcome.Refused(ticket, frame.data)
            }
        }
        return Outcome.Refused(
            ticket,
            "the daemon did not answer the start within ${timeoutMs}ms; read the ticket again before retrying",
        )
    }

    private suspend fun outcomeOf(stream: FrameStream, answered: Ticket, start: TicketStart, origin: String?): Outcome = when {
        start.ok -> {
            val listed = sessions(stream)
            val (note, warning) = startedDetail(start.detail)
            Outcome.Started(
                ticket = answered,
                session = answered.sessionId?.let { id -> listed.firstOrNull { it.id == id } },
                // The daemon records the frame's session as the starter only when it knows that session.
                startedBy = origin?.takeIf { caller -> listed.any { it.id == caller } },
                warning = warning,
                note = note,
            )
        }
        start.reason == TicketStart.ALREADY_IN_PROGRESS ->
            Outcome.AlreadyInProgress(answered, answered.sessionId?.let { id -> sessions(stream).firstOrNull { it.id == id } })
        start.reason == REASON_QUEUED -> Outcome.Queued(answered, start.detail)
        start.reason == REASON_HELD -> Outcome.Held(answered, start.detail)
        else -> Outcome.Refused(answered, start.detail ?: start.reason)
    }

    /** Every session as the daemon lists it now; empty when the answer is unreadable (the start stands either way). */
    private suspend fun sessions(stream: FrameStream): List<SessionRecord> {
        val answer = stream.request(Envelope(Envelope.SESSIONS), answerType = Envelope.SESSIONS, what = "sessions", idempotent = true)
        return runCatching { json.decodeFromString(ListSerializer(SessionRecord.serializer()), answer.data) }.getOrDefault(emptyList())
    }

    /**
     * A started ticket's [TicketStart.detail] read apart (note to warning): the board writes what it cut the branch
     * from first, then any launch warning, which begins with [TicketStart.LAUNCH_WARNING] (a ref never holds a space,
     * so the marker cannot sit inside the note). A daemon from before BUG-REGRESS-1 sends the warning alone.
     */
    internal fun startedDetail(detail: String?): Pair<String?, String?> {
        val text = detail?.trim().orEmpty()
        val at = text.indexOf(TicketStart.LAUNCH_WARNING)
        if (at < 0) return text.ifEmpty { null } to null
        val note = text.substring(0, at).trim().removeSuffix(";").trim().ifEmpty { null }
        return note to text.substring(at)
    }

    /** The role a ticket asks for, when it names one (`role:<id>`); a ticket assigned to one profile names none. */
    fun roleOf(ticket: Ticket): String? = ticket.agentId?.takeIf { it.startsWith(ROLE_PREFIX) }?.removePrefix(ROLE_PREFIX)

    private const val ROLE_PREFIX = "role:"
    private const val REASON_QUEUED = "queued"
    private const val REASON_HELD = "held"
    private const val POLL_MS = 100L
    private const val LOST =
        "the connection to the daemon dropped before it answered the start, so it is unknown whether the ticket " +
            "started: read the ticket again before retrying"

    private val json = Json { ignoreUnknownKeys = true }
}
