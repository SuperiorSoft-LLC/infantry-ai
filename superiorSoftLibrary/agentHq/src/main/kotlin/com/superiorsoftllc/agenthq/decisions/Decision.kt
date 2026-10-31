package com.superiorsoftllc.agenthq.decisions

/** Who wrote a decision, resolved from the declared session. */
data class DecisionAuthor(
    val sessionId: String? = null,
    val agentId: String? = null,
    val role: String? = null,
) {
    companion object {
        val NONE = DecisionAuthor()
    }
}

/**
 * A coded decision refusal; the wire text is `<frame> refused: <code>: <detail>`, the code first (as
 * [com.superiorsoftllc.agenthq.memory.MemoryRefusal]). Shape errors (blank or oversized summary, bad ref) stay plain
 * [IllegalArgumentException]s.
 */
class DecisionRefusal(val code: String, detail: String) : IllegalArgumentException("$code: $detail") {
    companion object {
        /** `supersedes` / `decision_get` named a number the project does not hold (another project's included). */
        const val DECISION_NOT_FOUND = "decision_not_found"

        /** `supersedes` named a decision that a later one already superseded: supersede the head of its chain. */
        const val ALREADY_SUPERSEDED = "already_superseded"

        /** `decision_add` declared a session the daemon does not know, so its author cannot be recorded. */
        const val SESSION_NOT_FOUND = "session_not_found"
    }
}
