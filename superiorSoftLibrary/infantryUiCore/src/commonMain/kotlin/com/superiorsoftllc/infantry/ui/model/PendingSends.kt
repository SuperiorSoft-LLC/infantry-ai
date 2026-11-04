// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.ui.model

import com.superiorsoftllc.infantry.ui.protocol.Envelope
import com.superiorsoftllc.infantry.ui.protocol.RunInFlight
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlin.random.Random

/**
 * CHAT-SENDING-1: where a message the human sent stands, from the press of Send until the daemon's echo of the turn shows it in
 * the transcript. [SENDING]: on its way, no answer yet. [ACCEPTED]: the daemon acknowledged it (`prompt_ack`, contract 1.151.0);
 * its `turn` echo is due. [FAILED]: no answer in time, refused, or the link dropped: the bubble offers "not sent - retry".
 */
enum class SendState { SENDING, ACCEPTED, FAILED }

/**
 * CHAT-SENDING-1: one message the human sent to [sessionId], shown at the bottom of its chat until the daemon's `turn` echo replaces
 * it. [clientId] is its client message id (the prompt's `requestId`, `send:<id>`): a retry resends the same id, so a daemon that
 * already took it answers `duplicate` and does not run it twice. [at] = when it was (re)sent; [reason] = why it failed.
 */
data class PendingSend(
    val clientId: String,
    val sessionId: String,
    val text: String,
    val at: Long,
    val state: SendState = SendState.SENDING,
    val reason: String? = null,
)

object PendingSends {
    /** The setting the "not sent - retry" wait is read from (a daemon limit, so every client of one daemon waits the same). */
    const val ACK_SECONDS_KEY = "limits.chat.sendAckSeconds"

    /** The wait when the daemon does not say (an older daemon, or before its `settings` frame). Mirrors the daemon's default. */
    const val DEFAULT_ACK_SECONDS = 45L

    /** A fresh client message id: unique per message (time + randomness), with the prefix the daemon dedupes on. */
    fun newClientId(now: Long, random: Random = Random): String =
        Envelope.CLIENT_SEND_PREFIX + now.toString(36) + "-" + random.nextLong().toULong().toString(36)

    /** `limits.chat.sendAckSeconds` in a `settings` frame, or null when the frame does not carry it (or carries a value below 1). */
    fun ackSecondsFromSettingsFrame(data: String): Long? {
        val obj = runCatching { Json.parseToJsonElement(data) as? JsonObject }.getOrNull() ?: return null
        return (obj[ACK_SECONDS_KEY] as? JsonPrimitive)?.longOrNull?.takeIf { it >= 1 }
    }

    /** The bubble's secondary line: "sending...", "sent" or "not sent - retry" (with the reason when there is one). */
    fun label(p: PendingSend): String = when (p.state) {
        SendState.SENDING -> "sending..."
        SendState.ACCEPTED -> "sent"
        SendState.FAILED -> "not sent" + (p.reason?.let { " ($it)" } ?: "") + " - retry"
    }
}

/** CHAT-SENDING-1: the messages sent to [sessionId] that its transcript does not show yet, oldest first. */
fun AppState.pendingSendsFor(sessionId: String): List<PendingSend> =
    pendingSends.values.filter { it.sessionId == sessionId }.sortedBy { it.at }

fun AppState.withPendingSend(p: PendingSend): AppState = copy(pendingSends = pendingSends + (p.clientId to p))

fun AppState.withoutPendingSend(clientId: String): AppState =
    if (clientId in pendingSends) copy(pendingSends = pendingSends - clientId) else this

/** CHAT-SENDING-1: [clientId] still has no answer after the wait: it becomes "not sent - retry" (an accepted or failed one is left alone). */
fun AppState.expirePendingSend(clientId: String, waitedSeconds: Long): AppState {
    val p = pendingSends[clientId] ?: return this
    if (p.state != SendState.SENDING) return this
    return withPendingSend(p.copy(state = SendState.FAILED, reason = "no answer in ${waitedSeconds}s"))
}

/** CHAT-SENDING-1: the pending send a `run_in_flight` refusal answers (the agent became busy first): the controller queues it instead. */
fun AppState.pendingRefusedBusy(env: Envelope): PendingSend? {
    if (env.type != Envelope.ERROR || !env.data.startsWith(RunInFlight.PREFIX + " ")) return null
    return env.requestId?.let { pendingSends[it] }
}

/**
 * CHAT-SENDING-1: settle the pending sends against frame [env], which turned [before] into [after]:
 * - `prompt_ack` with a pending client id: ACCEPTED (the `turn` echo removes it; `duplicate` = the daemon already had it).
 * - a NEW `turn` entry on a session (not a replayed one): removes that session's pending sends whose text the turn carries, and
 *   every ACCEPTED one (the daemon took it as this turn; a wrapped first turn need not match the text).
 * - an `error` with a pending client id: FAILED with the daemon's reason.
 * A dropped link is [failSendsOnDrop] (the link state is not a frame).
 */
fun settlePendingSends(before: AppState, after: AppState, env: Envelope): AppState {
    if (after.pendingSends.isEmpty()) return after
    var out = after
    val id = env.requestId
    when (env.type) {
        Envelope.PROMPT_ACK -> out.pendingSends[id]?.let { out = out.withPendingSend(it.copy(state = SendState.ACCEPTED, reason = null)) }
        Envelope.ERROR -> out.pendingSends[id]?.let { out = out.withPendingSend(it.copy(state = SendState.FAILED, reason = env.data.ifBlank { null })) }
        Envelope.TURN -> {
            val sessionId = env.sessionId
            val grew = sessionId != null &&
                (after.agents[sessionId]?.entries?.size ?: 0) > (before.agents[sessionId]?.entries?.size ?: 0)
            if (grew) {
                val settled = out.pendingSends.values.filter {
                    it.sessionId == sessionId && (it.state == SendState.ACCEPTED || turnCarries(env.data, it.text))
                }
                if (settled.isNotEmpty()) out = out.copy(pendingSends = out.pendingSends - settled.map { it.clientId }.toSet())
            }
        }
    }
    return out
}

/**
 * CHAT-SENDING-1: the link went from live ([before]) to not live ([after]): every SENDING message becomes "not sent - retry" at
 * once, rather than waiting out the timer. The retry resends the same id; a daemon that took it before the drop says `duplicate`.
 */
fun failSendsOnDrop(before: AppState, after: AppState): AppState {
    if (!before.connection.isLive || after.connection.isLive) return after
    if (after.pendingSends.values.none { it.state == SendState.SENDING }) return after
    return after.copy(pendingSends = after.pendingSends.mapValues { (_, p) ->
        if (p.state == SendState.SENDING) p.copy(state = SendState.FAILED, reason = "connection dropped") else p
    })
}
