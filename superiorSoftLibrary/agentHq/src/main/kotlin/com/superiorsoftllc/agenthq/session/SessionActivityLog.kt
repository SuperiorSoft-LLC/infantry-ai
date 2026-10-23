package com.superiorsoftllc.agenthq.session

import java.util.concurrent.ConcurrentHashMap

/**
 * Per-session "is it working or stuck?" facts, fed by the daemon's event broadcast and stamped onto every served
 * [SessionRecord] ([SessionRecordSerializer]): the last action as short text, when the last output line arrived, and
 * when the last event of any kind did. Memory only (a daemon start clears it); a session never seen has no entry, so
 * its record carries none of the three fields.
 */
object SessionActivityLog {
    private data class Entry(val action: String?, val outputAt: Long?, val eventAt: Long)

    private val entries = ConcurrentHashMap<String, Entry>()

    /** The session produced an output line at [now]; [action] (when known) replaces the last action. */
    fun output(sessionId: String, action: String?, now: Long = System.currentTimeMillis()) {
        entries.merge(sessionId, Entry(action, now, now)) { old, new -> new.copy(action = new.action ?: old.action) }
    }

    /** Any other event (state change, turn start/end) at [now]; [action] (when given) replaces the last action. */
    fun event(sessionId: String, action: String?, now: Long = System.currentTimeMillis()) {
        entries.merge(sessionId, Entry(action, null, now)) { old, new ->
            Entry(new.action ?: old.action, old.outputAt, new.eventAt)
        }
    }

    fun forget(sessionId: String) {
        entries.remove(sessionId)
    }

    fun clear() = entries.clear()

    /** [record] with the three activity fields filled from what the log holds; unchanged when it holds nothing. */
    fun overlay(record: SessionRecord): SessionRecord {
        val e = entries[record.id] ?: return record
        return record.copy(lastAction = e.action, lastOutputAt = e.outputAt, lastEventAt = e.eventAt)
    }
}
