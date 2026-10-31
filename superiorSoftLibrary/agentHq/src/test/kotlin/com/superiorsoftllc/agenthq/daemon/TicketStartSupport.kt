package com.superiorsoftllc.agenthq.daemon

import com.superiorsoftllc.agenthq.protocol.AgentProfile
import com.superiorsoftllc.agenthq.protocol.Envelope
import com.superiorsoftllc.agenthq.protocol.Ticket
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.sql.DriverManager

/**
 * Story 9.3 (contract 1.6.0) test support: request helpers that snapshot the frame buffer before each
 * send and read only frames that arrive AFTER it. A missing behaviour then fails on its own assertion in
 * milliseconds instead of waiting out a 20 s timeout against a frame that will never come.
 *
 * The same rule the harness already uses: everything runs against the REAL daemon on a real WebSocket.
 */
val JSON = Json { ignoreUnknownKeys = true; encodeDefaults = true }

/**
 * The ONE default for every "wait for the daemon" poll in the agentHq tests (frame awaits, state polls).
 * It was a literal 20 s repeated per helper; a full-suite run on a loaded machine starves the daemon's
 * threads for longer than that, and the symptom was "no frame after #N" with nothing wrong in the product.
 * Generous on purpose: a wait only costs its full length when the behaviour is genuinely missing, and a
 * missing behaviour usually fails earlier on its own assertion. Override with AGENTHQ_TEST_WAIT_MS (the
 * mutation check: =1 must make the waiting tests fail).
 */
object TestWait {
    const val FALLBACK_MS = 60_000L
    /**
     * The store's SQLite busy_timeout for fixture daemons (production keeps 5 s). Observed once in a clean
     * full run: a `ticket_save` waited out 5 s behind a writer that was NOT registered with the lock tracker
     * (a starved holder whose record lapsed) and was refused. A fixture daemon has one writer, so a longer
     * wait only ever turns that contention into a pass; tests of the lock error itself pass their own value.
     */
    val storeBusyMs: Int = System.getenv("AGENTHQ_TEST_STORE_BUSY_MS")?.toIntOrNull()?.takeIf { it > 0 } ?: 30_000
    val defaultMs: Long = System.getenv("AGENTHQ_TEST_WAIT_MS")?.toLongOrNull()?.takeIf { it > 0 } ?: FALLBACK_MS
}

/** Frame count now: snapshot before a send so [awaitAfter] ignores everything that came before. */
fun WsTestClient.mark(): Int = frames.size

/**
 * The first frame at index >= [from] satisfying [predicate]. Scans the whole tail, so a push that
 * arrives because of a PREVIOUS request cannot satisfy this one.
 */
fun WsTestClient.awaitAfter(
    from: Int,
    description: String,
    timeoutMs: Long = TestWait.defaultMs,
    predicate: (Envelope) -> Boolean,
): Envelope {
    val deadline = System.currentTimeMillis() + timeoutMs
    while (System.currentTimeMillis() < deadline) {
        for (i in from until frames.size) if (predicate(frames[i])) return frames[i]
        Thread.sleep(20)
    }
    error("no frame after #$from matching $description within ${timeoutMs}ms; got ${frames.drop(from).map { it.type to it.data.take(260) }}")
}

/** Send [type] and return the first LATER answer of any kind the backlog speaks. */
fun WsTestClient.answerTo(type: String, data: String = ""): Envelope {
    val from = mark()
    send(type, data)
    return awaitAfter(from, "answer to $type") {
        (it.type == Envelope.ERROR && it.sessionId == null) || it.type == Envelope.TICKET || it.type == Envelope.TICKETS ||
            it.type == Envelope.SETTINGS || it.type == Envelope.AGENT || it.type == Envelope.AGENTS
    }
}

/** Decode a `ticket` frame, or throw naming the daemon's refusal. */
fun Envelope.requireTicket(): Ticket {
    if (type == Envelope.ERROR) error("$type refused: $data")
    check(type == Envelope.TICKET) { "expected a ticket frame, got '$type': ${data.take(120)}" }
    return JSON.decodeFromString(Ticket.serializer(), data)
}

fun Envelope.ticketOrNull(): Ticket? =
    if (type == Envelope.TICKET) JSON.decodeFromString(Ticket.serializer(), data) else null

/** Save a profile and return it as stored (fails on the daemon's refusal). */
fun WsTestClient.saveProfile(jsonText: String): AgentProfile {
    val from = mark()
    send(Envelope.AGENT_SAVE, jsonText)
    val env = awaitAfter(from, "agent answer") { it.type == Envelope.AGENT || it.type == Envelope.ERROR }
    if (env.type == Envelope.ERROR) error("agent_save refused: ${env.data}")
    return JSON.decodeFromString(AgentProfile.serializer(), env.data)
}

/**
 * Create (or, with an `id`, edit) a ticket and return it as stored.
 *
 * BUG-FLAKE-4: only the frame for THIS save is the answer. An edit names its ticket. A create's answer is a ticket
 * this client has never been sent before: a late run-end push for an EARLIER ticket
 * (see [moveTicket]) is a ticket already seen, and taking it made the next move restart that earlier ticket.
 */
fun WsTestClient.saveTicket(jsonText: String): Ticket {
    val request = JSON.parseToJsonElement(jsonText) as JsonObject
    val editId = (request["id"] as? JsonPrimitive)?.contentOrNull
    val from = mark()
    val seen = frames.take(from).mapNotNull { it.ticketOrNull()?.id }.toSet()
    send(Envelope.TICKET_SAVE, jsonText)
    return awaitAfter(from, "the ticket_save answer for ${editId ?: "a new ticket"}") { env ->
        val ticket = env.ticketOrNull()
        env.type == Envelope.ERROR || ticket != null && when (editId) {
            null -> ticket.id !in seen
            else -> ticket.id == editId
        }
    }.requireTicket()
}

/**
 * Move a ticket to a column; returns the resulting `ticket` frame (a start carries `start`).
 *
 * BUG-FLAKE-4: only a `ticket` frame for [id] is the answer. A finished run's board update is applied on the
 * board's own event thread (`TicketBoard.onSessionEvent`), so it can reach this client AFTER the session reads
 * IDLE, i.e. after the next request's [mark]. Taking the first `ticket` frame of any id then returned the
 * PREVIOUS ticket, with no `start` ("the ticket must start: null" under full-suite load).
 */
fun WsTestClient.moveTicket(id: String, status: String): Ticket {
    val from = mark()
    send(Envelope.TICKET_MOVE, """{"id":"$id","status":"$status"}""")
    return awaitAfter(from, "the ticket_move answer for $id") {
        // A run's failure line is an ERROR frame WITH a sessionId; a request's refusal has none. With a runner that fails at once the
        // run error can reach this client before the move's answer (VC gate: 'error refused: gated failure #1'); it is not the answer.
        (it.type == Envelope.ERROR && it.sessionId == null) || (it.type == Envelope.TICKET && it.ticketOrNull()?.id == id)
    }.requireTicket()
}

/** Read the whole board as the daemon serves it now. */
fun WsTestClient.board(): List<Ticket> {
    // Wait for the `tickets` answer by type: a `ticket` push for a ticket changing under us (a session binding,
    // a run finishing) is also on the wire and must never be taken for the listing.
    val from = mark()
    send(Envelope.TICKET_LIST, "")
    val listed = awaitAfter(from, "the tickets answer to ticket_list") { it.type == Envelope.ERROR || it.type == Envelope.TICKETS }
    check(listed.type == Envelope.TICKETS) { "ticket_list refused: ${listed.data}" }
    return JSON.decodeFromString(ListSerializer(Ticket.serializer()), listed.data)
}

/** Read one setting (or all when [key] is blank) as a JSON object. */
fun WsTestClient.settings(key: String = ""): JsonObject {
    val env = answerTo(Envelope.SETTINGS_GET, key)
    if (env.type == Envelope.ERROR) error("settings_get refused: ${env.data}")
    check(env.type == Envelope.SETTINGS) { "expected a settings frame, got '${env.type}': ${env.data.take(120)}" }
    return JSON.parseToJsonElement(env.data) as JsonObject
}

/** Every `ticket_runs` row for [ticketId], read with plain JDBC off the daemon's own database file. */
fun ticketRuns(dbPath: String, ticketId: String): List<Map<String, String?>> =
    DriverManager.getConnection("jdbc:sqlite:$dbPath").use { conn ->
        conn.prepareStatement(
            "SELECT session_id, started_at, ended_at, outcome, detail FROM ticket_runs WHERE ticket_id = ? ORDER BY started_at",
        ).use { ps ->
            ps.setString(1, ticketId)
            ps.executeQuery().use { rs ->
                buildList {
                    while (rs.next()) {
                        add(
                            mapOf(
                                "sessionId" to rs.getString("session_id"),
                                "startedAt" to rs.getString("started_at"),
                                "endedAt" to rs.getString("ended_at"),
                                "outcome" to rs.getString("outcome"),
                                "detail" to rs.getString("detail"),
                            ),
                        )
                    }
                }
            }
        }
    }

/** The daemon's database file for [fixture]. */
fun DaemonFixture.dbPath(): String = java.io.File(stateDir, "agenthq.db").absolutePath

/** DP-5h-3d: [AgentHqDaemon.board] is null on a daemon without a board; an E2E test that needs the board reads it through here. */
fun AgentHqDaemon.requireBoard(): com.superiorsoftllc.agenthq.backlog.TicketBoard = board ?: error("this daemon has no board")
