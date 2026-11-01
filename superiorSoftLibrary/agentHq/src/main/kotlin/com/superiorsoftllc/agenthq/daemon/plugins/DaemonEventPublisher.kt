package com.superiorsoftllc.agenthq.daemon.plugins

import com.superiorsoftllc.agenthq.daemon.api.board.TicketEvent
import com.superiorsoftllc.agenthq.mailbox.Message
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.util.concurrent.ConcurrentHashMap

/**
 * SCRIPT-3: publishes the daemon's existing happenings as named events (names: [NamedEventBus.DAEMON_EVENTS]).
 * `ticket.commented` is published by the board plugin that owns comments, through `api.events.publish`.
 */
class DaemonEventPublisher(private val publish: (name: String, payload: String) -> Unit) {
    private val lastState = ConcurrentHashMap<String, String>()

    private fun obj(vararg f: Pair<String, Any?>) = JsonObject(f.filter { it.second != null }.associate { (k, v) ->
        k to (if (v is Number) JsonPrimitive(v) else JsonPrimitive(v.toString()))
    }).toString()

    fun ticket(e: TicketEvent) {
        val name = when (e.kind) {
            TicketEvent.CREATED -> "ticket.created"
            TicketEvent.UPDATED -> "ticket.updated"
            TicketEvent.DELETED -> "ticket.deleted"
            else -> "ticket.moved" // moved, started, done: all lane changes
        }
        publish(name, obj("ticketId" to e.ticketId, "from" to e.from, "to" to e.to, "kind" to e.kind, "seq" to e.seq))
    }

    /** A session status push: [state] is the state name (`IDLE`, `BUSY`, ...). First sight of a session = started; entering IDLE = idle. */
    fun sessionStatus(sessionId: String, state: String, role: String?, projectId: String?) {
        val prev = lastState.put(sessionId, state)
        val p = obj("sessionId" to sessionId, "state" to state, "role" to role, "projectId" to projectId)
        if (prev == null) publish("session.started", p)
        if (state == "IDLE" && prev != "IDLE") publish("session.idle", p)
    }

    fun sessionEnded(sessionId: String) {
        lastState.remove(sessionId)
        publish("session.ended", obj("sessionId" to sessionId))
    }

    /** SCRIPT-VIS-1: a prompt woke a session (`reason`: owner, agent, external, mail, mail-hook-delivered), or a hook handled a mail with no wake (`mail-handled`). */
    fun woken(sessionId: String, reason: String, ref: String?, projectId: String? = null) {
        publish("session.woken", obj("sessionId" to sessionId, "reason" to reason, "ref" to ref, "projectId" to projectId))
    }

    fun mail(m: Message) {
        publish("mail.sent", obj("id" to m.id, "from" to m.from, "to" to m.to, "subject" to m.subject, "threadId" to m.threadId))
    }
}
