// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.mailbox

/**
 * NUDGE-SCRIPT-1: every built-in nudge or notice first goes through this gate. The gate emits a named event with the payload
 * (so a script hook on it can post a chat note, wake the agent with its own short prompt, or drop it) and answers whether the
 * built-in defers to hooks (setting `nudges.deferToHooks` lists the event names). Deferred = the built-in mail/prompt is NOT sent;
 * not deferred (the default: an empty list, [NONE]) = it is sent exactly as before.
 */
class NudgeGate(
    private val emit: (name: String, payload: Map<String, String>) -> Unit,
    private val defers: (name: String) -> Boolean,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private class Mute(val name: String, val sessionId: String?, val until: Long)
    private val mutes = java.util.concurrent.CopyOnWriteArrayList<Mute>()

    /**
     * SCRIPT-SESSION-1: a hook marks notices of [name] (a nudge name, or `*`) as suppressed for [ms] millis, for one session or (null) any.
     * The built-in sends nothing while the mark lasts; the event still fires. Marks are capped in number and expire by themselves.
     */
    fun suppress(name: String, sessionId: String?, ms: Long) {
        val now = clock()
        mutes.removeIf { it.until <= now }
        if (mutes.size >= MAX_MUTES) mutes.removeAt(0)
        mutes.add(Mute(name, sessionId?.takeIf { it.isNotBlank() }, now + ms.coerceIn(1_000L, MAX_MUTE_MS)))
    }

    fun suppressed(name: String, sessionId: String?): Boolean {
        val now = clock()
        return mutes.any { it.until > now && (it.name == "*" || it.name == name) && (it.sessionId == null || it.sessionId == sessionId) }
    }

    /** True when the built-in must NOT send its own nudge: a hook owns it. Emit failures never block the default. */
    fun handled(name: String, payload: Map<String, String>): Boolean {
        runCatching { emit(name, payload) }
        if (suppressed(name, payload["sessionId"])) return true
        return runCatching { defers(name) }.getOrDefault(false)
    }

    companion object {
        const val MAX_MUTES = 100
        const val MAX_MUTE_MS = 3_600_000L
        val NONE = NudgeGate({ _, _ -> }, { false })
        const val SETTING = "nudges.deferToHooks"
        const val TICKET_FINISHED = "notice.ticket_finished"
        const val TICKET_FAILED = "notice.ticket_failed"
        const val WORKER_WAITING = "notice.worker_waiting"
        const val WORKER_IDLE = "nudge.worker_idle"
        const val WORKER_NO_REPORT = "notice.worker_no_report"
        const val WORKER_STALLED = "nudge.worker_stalled"
        const val BG_DONE = "notice.bg_done"
        val ALL = listOf(TICKET_FINISHED, TICKET_FAILED, WORKER_WAITING, WORKER_IDLE, WORKER_NO_REPORT, WORKER_STALLED, BG_DONE)
    }
}
