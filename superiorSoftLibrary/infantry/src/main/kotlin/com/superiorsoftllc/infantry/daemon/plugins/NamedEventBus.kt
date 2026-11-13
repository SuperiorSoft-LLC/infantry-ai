// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.daemon.plugins

import com.superiorsoftllc.infantry.daemon.api.NamedEvent
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executor

/**
 * SCRIPT-3: the generic named-event bus. Any plugin or agent publishes a [NamedEvent]; any plugin subscribes by exact name, by
 * prefix (`ticket.*`) or to all (`*`). Core only validates the name and the payload's JSON shape and carries source / causeId /
 * depth; it knows no event by meaning and enforces no policy on the chain. Delivery runs on [executor] (one thread, publish order).
 */
class NamedEventBus(
    private val executor: Executor = Executor { it.run() },
    private val clock: () -> Long = System::currentTimeMillis,
    private val idFactory: () -> String = { UUID.randomUUID().toString() },
    /** PROJECT-ISOLATION-1: (name, payload) -> the payload as delivered; the host adds the event's `projectId` ([ProjectIsolation.stamp]). */
    private val stamp: (String, String) -> String = { _, p -> p },
) {
    private class Sub(val pattern: String, val listener: (NamedEvent) -> Unit)

    private val subs = CopyOnWriteArrayList<Sub>()

    fun subscribe(pattern: String, listener: (NamedEvent) -> Unit): AutoCloseable {
        val p = pattern.trim()
        require(p == "*" || NAME.matches(p.removeSuffix(".*").removeSuffix("."))) { "event pattern '$pattern' is not a dotted name, a prefix 'name.*' or '*'" }
        val sub = Sub(p, listener)
        subs += sub
        return AutoCloseable { subs -= sub }
    }

    /** Publishes; returns the event as delivered. A bad name or a non-object payload is refused with [IllegalArgumentException]. */
    fun publish(name: String, payload: String = "{}", source: String, causeId: String? = null, depth: Int = 0): NamedEvent {
        require(NAME.matches(name)) { "event name '$name' must be dotted lower-case words (e.g. ticket.moved)" }
        require(depth >= 0) { "depth must not be negative" }
        val raw = payload.ifBlank { "{}" }
        require(runCatching { Json.parseToJsonElement(raw) is JsonObject }.getOrDefault(false)) { "payload must be a JSON object" }
        val text = runCatching { stamp(name, raw) }.getOrDefault(raw)
        val id = idFactory()
        val event = NamedEvent(name, text, source, id, causeId?.takeIf { it.isNotBlank() } ?: id, depth, clock())
        for (s in subs) if (matches(s.pattern, name)) executor.execute { runCatching { s.listener(event) } }
        return event
    }

    companion object {
        val NAME = Regex("[a-z][a-z0-9_-]*([.][a-z0-9][a-z0-9_-]*)*")

        fun matches(pattern: String, name: String): Boolean = when {
            pattern == "*" -> true
            pattern.endsWith(".*") -> name.startsWith(pattern.dropLast(1))
            pattern.endsWith(".") -> name.startsWith(pattern)
            else -> pattern == name
        }

        /** The names the daemon publishes itself (the one documented list). */
        val DAEMON_EVENTS = listOf(
            "ticket.created", "ticket.moved", "ticket.updated", "ticket.deleted", "ticket.commented",
            "session.started", "session.idle", "session.ended", "session.woken", "mail.sent",
            "session.turn_started", "session.turn_ended", "session.tool_call", "session.output", "session.error",
            "session.mcp", "daemon.started", "progress.failed", "modules.changed",
        )

        /**
         * BRIDGE-EVENTS-1: names kept for events the daemon will publish but cannot yet. `session.first-turn-timeout`: a harness's first turn
         * got no sign of life (FIRST-TURN-1 fails that turn, so today a script sees `session.error` + `session.idle`); it needs a harness API
         * seam to name the cause, which is an API contract bump of its own.
         */
        val RESERVED_EVENTS = listOf("session.first-turn-timeout")
    }
}
