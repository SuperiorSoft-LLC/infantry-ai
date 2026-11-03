package com.superiorsoftllc.infantry.daemon.api.sessions

/*
 * DP-7e (DaemonApi 1.19.0, additive): the mail port. A plugin tells live sessions something by mail without knowing the mail
 * router: one session by id, or every live session of a role in a project. A community plugin needs the grant `sessions.notify`.
 * Best effort by contract: a recipient that is gone is simply not counted.
 */
interface SessionNotifier {
    /** Mail [subject] / [body] to the session [sessionId]; true when it was handed to the mail layer. */
    fun notifySession(sessionId: String, subject: String, body: String): Boolean

    /** Mail every live session of [role] in [projectId]; returns how many were mailed (0 = none live). */
    fun notifyRole(projectId: String, role: String, subject: String, body: String): Int
}

/** A daemon without a mail layer: nothing is delivered. */
object NoSessionNotifier : SessionNotifier {
    override fun notifySession(sessionId: String, subject: String, body: String) = false
    override fun notifyRole(projectId: String, role: String, subject: String, body: String) = 0
}
