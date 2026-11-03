package com.superiorsoftllc.infantry.daemon.plugins

import com.superiorsoftllc.infantry.daemon.api.sessions.SessionNotifier

/** DP-7e: the mail port behind one plugin's grants. Official / core notify freely; a community plugin needs `sessions.notify` (default deny: the call throws). */
class GrantedSessionNotifier(private val source: () -> SessionNotifier, private val may: (String) -> Boolean) : SessionNotifier {
    override fun notifySession(sessionId: String, subject: String, body: String): Boolean = allowed().notifySession(sessionId, subject, body)
    override fun notifyRole(projectId: String, role: String, subject: String, body: String): Int = allowed().notifyRole(projectId, role, subject, body)
    private fun allowed(): SessionNotifier {
        check(may(GRANT)) { "missing grant '$GRANT' in modules.json" }
        return source()
    }

    companion object { const val GRANT = "sessions.notify" }
}
