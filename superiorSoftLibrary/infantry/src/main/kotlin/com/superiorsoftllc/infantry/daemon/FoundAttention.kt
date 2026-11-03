// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.daemon

import com.superiorsoftllc.infantry.daemon.api.attention.Attention
import com.superiorsoftllc.infantry.daemon.api.attention.AttentionEntry

/**
 * ATTN-1: what a consumer holds. Looks the provider up per call (the plugin host is built after the consumers).
 * ATTN-2b: the provider is Infantry_Attention's, not core's. Items raised before the provider is registered (startup
 * recovery runs before the plugins load) are held, up to [PENDING_MAX], and delivered by the first call that finds it
 * (or [flush]); an item raised with no provider ever present is lost, with one log line the first time.
 */
class FoundAttention(private val find: () -> Attention?) : Attention {
    private class Pending(val kind: String, val triggerId: String?, val ticketId: String?, val podId: String?, val sessionId: String?, val outcome: String, val detail: String)

    @Volatile private var warned = false
    private val pending = ArrayDeque<Pending>()

    private fun provider(): Attention? {
        val found = find()
        if (found != null) drain(found)
        return found
    }

    private fun drain(provider: Attention) {
        val items = synchronized(pending) { pending.toList().also { pending.clear() } }
        for (p in items) runCatching { provider.raise(p.kind, p.triggerId, p.ticketId, p.podId, p.sessionId, p.outcome, p.detail) }
    }

    /** Deliver anything held, once the provider is there. */
    fun flush() { find()?.let(::drain) }

    override fun raise(kind: String, triggerId: String?, ticketId: String?, podId: String?, sessionId: String?, outcome: String, detail: String): String? {
        val found = provider()
        if (found != null) return found.raise(kind, triggerId, ticketId, podId, sessionId, outcome, detail)
        if (!warned) { warned = true; System.err.println("[infantry] no Attention provider registered yet; attention items are held until it is") }
        synchronized(pending) { if (pending.size < PENDING_MAX) pending.addLast(Pending(kind, triggerId, ticketId, podId, sessionId, outcome, detail)) }
        return null
    }

    override fun resolve(id: String): Boolean = provider()?.resolve(id) ?: false

    override fun list(project: String?): List<AttentionEntry> = provider()?.list(project).orEmpty()

    private companion object { const val PENDING_MAX = 200 }
}
