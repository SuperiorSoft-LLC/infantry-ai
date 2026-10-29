package com.superiorsoftllc.agenthq.daemon

import com.superiorsoftllc.agenthq.mailbox.FanOutResult
import com.superiorsoftllc.agenthq.mailbox.MailFanOutRequest
import com.superiorsoftllc.agenthq.mailbox.MailReadRequest
import com.superiorsoftllc.agenthq.mailbox.MailRefused
import com.superiorsoftllc.agenthq.mailbox.MailResult
import com.superiorsoftllc.agenthq.mailbox.MailRouter
import com.superiorsoftllc.agenthq.mailbox.MailSendRequest
import com.superiorsoftllc.agenthq.mailbox.Mailbox
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receiveText
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import kotlinx.serialization.json.Json

private val mailJson = Json { ignoreUnknownKeys = true; encodeDefaults = true }

/**
 * Phase 6, agent-facing half: the HTTP mail API.
 *
 * A delivered mailbox message is rendered into the recipient's prompt, but the recipient is a
 * *spawned CLI agent* -- it cannot speak the daemon's WebSocket protocol. This is the surface it
 * CAN use: plain HTTP on the daemon it was started by, reachable with the one tool every agent has,
 * a shell command (`curl`). The delivered prompt carries the exact commands, so an unattended
 * handoff can be **delivered AND answered** with no human relaying anything.
 *
 * It reuses the SAME [MailRouter]/[Mailbox] the WebSocket frames use, so the limits, receipts and
 * delivery policy are identical whichever door the message came through.
 *
 * SECURITY: these routes sit behind [installAccessPolicy], like every route except `/health`. When the
 * daemon has a bearer token, a request without it is refused with 401 before any handler runs, so a
 * refused `/mail/send` never reaches the mailbox. Without a token the daemon is loopback-only and the
 * Host check applies. The curl in a delivered prompt sends `$AGENTHQ_TOKEN`, which the daemon puts in the
 * agent's environment; the literal token is never written into a prompt.
 */
fun Route.mailRoutes(
    mail: MailRouter,
    mailbox: Mailbox,
    /** DEMO-LOCK (ticket 2f154f5f): a non-null answer refuses the send (403) before the mailbox sees it. */
    sendGuard: (MailSendRequest) -> String? = { null },
) {

    /** Send (or reply to) a message. 200 + the stored `Message`, or 400 + a `MailRefused`. */
    post("/mail/send") {
        val request = runCatching {
            mailJson.decodeFromString(MailSendRequest.serializer(), call.receiveText())
        }.getOrNull()
        if (request == null) {
            call.respond(HttpStatusCode.BadRequest, MailRefused("", "", null, "malformed mail_send payload"))
            return@post
        }
        sendGuard(request)?.let { reason ->
            call.respond(HttpStatusCode.Forbidden, MailRefused(request.from, request.to, request.threadId, reason))
            return@post
        }
        when (val result = mail.send(request)) {
            // Re-read the stored message: `send` returns the QUEUED snapshot it persisted, and the
            // delivery pump runs after it. An HTTP caller has no event stream, so the response must
            // carry the CURRENT state (DELIVERED, or UNDELIVERABLE with the reason).
            is MailResult.Sent -> call.respond(HttpStatusCode.OK, mailbox.message(result.message.id) ?: result.message)
            is MailResult.Refused ->
                call.respond(
                    HttpStatusCode.BadRequest,
                    MailRefused(request.from, request.to, request.threadId, result.reason),
                )
        }
    }

    /**
     * Fan-out: one sender, N recipients, one collecting thread. 200 + the stored `Message` per
     * recipient (re-read so the state reflects delivery), or 400 + a `MailRefused`.
     */
    post("/mail/fanout") {
        val request = runCatching {
            mailJson.decodeFromString(MailFanOutRequest.serializer(), call.receiveText())
        }.getOrNull()
        if (request == null) {
            call.respond(HttpStatusCode.BadRequest, MailRefused("", "", null, "malformed mail_fanout payload"))
            return@post
        }
        when (val result = mail.fanOut(request)) {
            is FanOutResult.Sent -> {
                // Re-read each: `fanOut` returns the QUEUED snapshots, but the pump already ran.
                val current = result.messages.map { mailbox.message(it.id) ?: it }
                call.respond(HttpStatusCode.OK, current)
            }
            is FanOutResult.Refused ->
                call.respond(
                    HttpStatusCode.BadRequest,
                    MailRefused(request.from, request.recipients.joinToString(","), request.threadId, result.reason),
                )
        }
    }

    /** Acknowledge one message (delivered -> read). 200 + the updated `Message`, or 404. */
    post("/mail/read") {
        val request = runCatching {
            mailJson.decodeFromString(MailReadRequest.serializer(), call.receiveText())
        }.getOrNull()
        if (request == null || request.messageId.isBlank()) {
            call.respond(HttpStatusCode.BadRequest, MailRefused("", "", null, "malformed mail_read payload"))
            return@post
        }
        val updated = mailbox.markRead(request.messageId)
        if (updated == null) {
            call.respond(HttpStatusCode.NotFound, MailRefused("", "", null, "unknown or unreadable message ${request.messageId}"))
        } else {
            call.respond(HttpStatusCode.OK, updated)
        }
    }

    /** Messages addressed to a session, oldest first. `?session=<id>`. */
    get("/mail/inbox") {
        val who = call.request.queryParameters["session"].orEmpty()
        call.respond(mailbox.inbox(who))
    }

    /** Messages a session sent. `?session=<id>`. */
    get("/mail/outbox") {
        val who = call.request.queryParameters["session"].orEmpty()
        call.respond(mailbox.outbox(who))
    }

    /** One thread, in order. `/{threadId}` (URL-encoded; `pair:a|b`, `run:x`...). */
    get("/mail/thread/{threadId}") {
        call.respond(mailbox.thread(call.parameters["threadId"].orEmpty()))
    }

    /** Open needs-input signals, filtered to senders at rest. */
    get("/mail/waiting") {
        call.respond(mail.waiting())
    }
}
