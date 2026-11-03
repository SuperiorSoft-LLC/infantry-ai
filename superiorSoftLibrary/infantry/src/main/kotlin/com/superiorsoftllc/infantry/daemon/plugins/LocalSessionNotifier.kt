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
) : SessionNotifier {
    override fun notifySession(sessionId: String, subject: String, body: String): Boolean {
        if (live().none { it.id == sessionId && it.isInSession() }) return false
        return runCatching { send(sessionId, subject, body) }.isSuccess
    }

    override fun notifyRole(projectId: String, role: String, subject: String, body: String): Int {
        return live().filter { it.role == role && it.projectId == projectId && it.isInSession() }
            .count { runCatching { send(it.id, subject, body) }.isSuccess }
    }
}
