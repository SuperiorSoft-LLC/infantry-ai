package com.superiorsoftllc.agenthq.plugins.official.notes.daemon

/** Who wrote an issue or an event, resolved from the declared session. */
data class IssueActor(
    val sessionId: String? = null,
    val agentId: String? = null,
) {
    companion object {
        val NONE = IssueActor()
    }
}

/**
 * A coded issue refusal; the wire text is `<frame> refused: <code>: <detail>`, the code first (as
 * [com.superiorsoftllc.agenthq.decisions.DecisionRefusal]). Shape errors (blank summary, bad ref, an unknown outcome)
 * stay plain [IllegalArgumentException]s.
 */
class IssueRefusal(val code: String, detail: String) : IllegalArgumentException("$code: $detail") {
    companion object {
        /** A ref names a number the project does not hold (another project's included). */
        const val ISSUE_NOT_FOUND = "issue_not_found"

        /** `issue_fix` named no issue and the declared session has no active one (or none was declared). */
        const val NO_ACTIVE_ISSUE = "no_active_issue"

        /** A fix, or an attempt, on an issue that already has its fix: a recurrence is a new issue. */
        const val ALREADY_FIXED = "already_fixed"

        /** A frame declared a session the daemon does not know, so its author cannot be recorded. */
        const val SESSION_NOT_FOUND = "session_not_found"
    }
}

fun issueRef(number: Int): String = "I$number"
