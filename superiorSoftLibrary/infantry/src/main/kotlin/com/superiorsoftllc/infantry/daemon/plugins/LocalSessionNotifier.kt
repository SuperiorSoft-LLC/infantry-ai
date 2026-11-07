// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.daemon.plugins

import com.superiorsoftllc.infantry.daemon.api.sessions.SessionNotifier
import com.superiorsoftllc.infantry.session.SessionRecord
import com.superiorsoftllc.infantry.session.isInSession

/**
 * DP-7e: the core [SessionNotifier] over the existing mail router. [live] lists the sessions, [send] mails one (recipient id,
 * subject, body).
 */
class LocalSessionNotifier(
    private val live: () -> List<SessionRecord>,
    private val send: (to: String, subject: String, body: String) -> Unit,
    /** Sends on a named thread; null = [send] on the default thread. */
    private val sendOnThread: ((to: String, subject: String, body: String, threadId: String) -> Unit)? = null,
) : SessionNotifier {
    override fun notifySession(sessionId: String, subject: String, body: String): Boolean {
        if (live().none { it.id == sessionId && it.isInSession() }) return false
        return runCatching { send(sessionId, subject, body) }.isSuccess
    }

    override fun notifySession(sessionId: String, subject: String, body: String, threadId: String?): Boolean {
        val threaded = sendOnThread
        if (threadId == null || threaded == null) return notifySession(sessionId, subject, body)
        if (live().none { it.id == sessionId && it.isInSession() }) return false
        return runCatching { threaded(sessionId, subject, body, threadId) }.isSuccess
    }

    override fun notifyRole(projectId: String, role: String, subject: String, body: String): Int {
        return live().filter { it.role == role && it.projectId == projectId && it.isInSession() }
            .count { runCatching { send(it.id, subject, body) }.isSuccess }
    }
}
