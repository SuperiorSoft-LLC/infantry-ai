package com.superiorsoftllc.infantry.daemon

import com.superiorsoftllc.infantry.triggers.HookOutcome
import com.superiorsoftllc.infantry.triggers.WebhookIngress
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.contentLength
import io.ktor.server.request.receiveChannel
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import io.ktor.utils.io.readRemaining
import kotlinx.io.readByteArray

/**
 * P9.6: `POST /hooks/{triggerId}`, the one route besides `/health` that is reachable without the daemon bearer
 * (the Owner's 2026-10-18 decision). Admission is the per-hook HMAC signature, checked in [WebhookIngress]; the
 * Origin check and, without a token, the Host check still apply (see [AccessPolicy.check]).
 *
 * The body is read with a hard cap: a declared Content-Length over the cap is refused unread, and a chunked or
 * lying body is cut off one byte past it, so an unauthenticated caller can never make the daemon buffer more
 * than [WebhookIngress.MAX_BODY_BYTES] + 1 bytes.
 */
fun Route.hookRoutes(ingress: WebhookIngress) {
    post("/hooks/{id}") {
        val declared = call.request.contentLength()
        val max = WebhookIngress.MAX_BODY_BYTES
        val body = if (declared != null && declared > max) {
            null
        } else {
            call.receiveChannel().readRemaining(max + 1L).readByteArray()
        }
        if (body == null || body.size > max) {
            call.respondText("body is larger than $max bytes", status = HttpStatusCode.PayloadTooLarge)
            return@post
        }
        val outcome = ingress.deliver(
            triggerId = call.parameters["id"].orEmpty(),
            signature = call.request.headers[WebhookIngress.SIGNATURE_HEADER] ?: call.request.headers[WebhookIngress.LEGACY_SIGNATURE_HEADER],
            timestamp = call.request.headers[WebhookIngress.TIMESTAMP_HEADER] ?: call.request.headers[WebhookIngress.LEGACY_TIMESTAMP_HEADER],
            delivery = call.request.headers[WebhookIngress.DELIVERY_HEADER] ?: call.request.headers[WebhookIngress.LEGACY_DELIVERY_HEADER],
            body = body,
        )
        when (outcome) {
            is HookOutcome.Accepted ->
                call.respondText("""{"status":"ok","disposition":"${outcome.disposition}"}""", ContentType.Application.Json)
            is HookOutcome.Rejected ->
                call.respondText(outcome.reason, status = HttpStatusCode.fromValue(outcome.status))
        }
    }
}
