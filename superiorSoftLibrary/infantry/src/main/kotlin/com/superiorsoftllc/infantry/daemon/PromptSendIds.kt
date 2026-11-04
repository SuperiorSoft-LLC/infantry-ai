// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.daemon

import com.superiorsoftllc.infantry.protocol.Envelope

/**
 * CHAT-SENDING-1 (contract 1.151.0): the client message ids of recent `prompt`s, per session, so a resend of a message the daemon
 * already took (the ack was lost on a dropped link) is acknowledged and never run twice. Only a `requestId` that starts with
 * [Envelope.CLIENT_SEND_PREFIX] is remembered: other request ids (a window's own counters) are not unique across windows.
 * Daemon-wide, not per connection: the resend usually arrives on a NEW connection after a reconnect. Keeps the [capacity]
 * most recent ids (setting `limits.chat.sentIdsKept`).
 */
class PromptSendIds(private val capacity: () -> Int) {
    private val ids = LinkedHashSet<String>()

    /** The key [requestId] is remembered under for [sessionId], or null when it is not a client message id. */
    fun keyOf(sessionId: String?, requestId: String?): String? =
        if (sessionId == null || requestId == null || !requestId.startsWith(Envelope.CLIENT_SEND_PREFIX)) null else "$sessionId\n$requestId"

    /** Reserve [key] before the prompt runs: false = already taken (a duplicate; do not run it). */
    @Synchronized
    fun claim(key: String): Boolean {
        if (!ids.add(key)) return false
        val max = capacity().coerceAtLeast(1)
        while (ids.size > max) ids.remove(ids.first())
        return true
    }

    /** The prompt behind [key] was refused (not run): forget it, so a retry runs. */
    @Synchronized
    fun release(key: String) {
        ids.remove(key)
    }
}

/** CHAT-SENDING-1: the `prompt_ack` answering [request] (`accepted` or `duplicate`); it echoes the request's session and client id. */
internal fun promptAck(request: Envelope, duplicate: Boolean): Envelope =
    Envelope(Envelope.PROMPT_ACK, if (duplicate) "duplicate" else "accepted", request.sessionId, requestId = request.requestId)
