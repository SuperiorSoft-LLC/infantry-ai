// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.session

import java.util.concurrent.ConcurrentHashMap

/**
 * Per-session "is it working or stuck?" facts, fed by the daemon's event broadcast and stamped onto every served
 * [SessionRecord] ([SessionRecordSerializer]): the last action as short text, when the last output line arrived, and
 * when the last event of any kind did. Memory only (a daemon start clears it); a session never seen has no entry, so
 * its record carries none of the three fields.
 */
object SessionActivityLog {
    private data class Entry(val action: String?, val outputAt: Long?, val eventAt: Long, val toolCalls: Int = 0, val toolAt: Long? = null, val turnAt: Long? = null)

    private val entries = ConcurrentHashMap<String, Entry>()

    /** The session produced an output line at [now]; [action] (when known) replaces the last action. */
    fun output(sessionId: String, action: String?, now: Long = System.currentTimeMillis(), tool: Boolean = false) {
        entries.merge(sessionId, Entry(action, now, now, if (tool) 1 else 0, if (tool) now else null)) { old, new ->
            new.copy(action = new.action ?: old.action, toolCalls = old.toolCalls + new.toolCalls, toolAt = new.toolAt ?: old.toolAt, turnAt = old.turnAt)
        }
    }

    /** Any other event (state change, turn start/end) at [now]; [action] (when given) replaces the last action. */
    fun event(sessionId: String, action: String?, now: Long = System.currentTimeMillis()) {
        entries.merge(sessionId, Entry(action, null, now)) { old, new ->
            old.copy(action = new.action ?: old.action, eventAt = new.eventAt)
        }
    }

    /**
     * LAST-TURN-1: a turn of the session ended at [now] (the daemon's `finished`, whatever the harness and outcome). This is
     * `lastTurnAt`, the time a watcher reads to tell "has not taken a turn in N minutes"; [output] alone moves `lastOutputAt`.
     */
    fun turnEnded(sessionId: String, now: Long = System.currentTimeMillis()) {
        entries.merge(sessionId, Entry(null, null, now, turnAt = now)) { old, _ -> old.copy(eventAt = now, turnAt = now) }
    }

    /** Tool calls the daemon counted for a session (0 when none), for the stats and the tests. */
    fun lastAction(sessionId: String): String? = entries[sessionId]?.action

    fun toolCalls(sessionId: String): Int = entries[sessionId]?.toolCalls ?: 0

    fun forget(sessionId: String) {
        entries.remove(sessionId)
        AutoProgress.commits.remove(sessionId)
    }

    fun clear() = entries.clear()

    /**
     * [record] with the activity fields filled from what the log holds; unchanged when it holds nothing. A time the log has not
     * seen since this daemon started keeps the record's own (the value `sessions.json` held from before the restart).
     */
    fun overlay(record: SessionRecord): SessionRecord {
        val e = entries[record.id]
        val now = System.currentTimeMillis()
        if (e == null) {
            val p = AutoProgress.overlay(record, 0, null, null, now)
            return if (p == record.progress) record else record.copy(progress = p)
        }
        return record.copy(
            lastAction = e.action ?: record.lastAction, lastOutputAt = e.outputAt ?: record.lastOutputAt, lastEventAt = e.eventAt,
            lastTurnAt = e.turnAt ?: record.lastTurnAt,
            progress = AutoProgress.overlay(record, e.toolCalls, e.toolAt, e.eventAt, now),
            waiting = WaitingRules.overlay(record.waiting, e.outputAt ?: record.lastOutputAt),
        )
    }
}
