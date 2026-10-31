package com.superiorsoftllc.agenthq.decisions

import kotlinx.serialization.Serializable

/**
 * P10-K4 (contract 1.53.0, docs/AGENTHQ-PHASE10-KNOWLEDGE.md K-D7): one decision of a project's append-only log.
 *
 * [number] is per project from 1 and shown as [ref] (`D<number>`). [supersedes] names an earlier decision of the same
 * project; [supersededBy] is DERIVED (the later decision that names this one), so a decision is active exactly when
 * [supersededBy] is null. The author fields come from the session declared on `decision_add`, null when none was.
 * [createdAt] is Unix epoch milliseconds.
 */
@Serializable
data class Decision(
    val projectId: String,
    val number: Int,
    val ref: String,
    val summary: String,
    val body: String = "",
    val supersedes: Int? = null,
    val supersededBy: Int? = null,
    val authorSessionId: String? = null,
    val authorAgentId: String? = null,
    val authorRole: String? = null,
    val createdAt: Long,
) {
    val active: Boolean get() = supersededBy == null
}

/**
 * `decision_add` payload. [supersedes] is a ref (`D1` or `1`); [sessionId] declares the author, whose agent and role
 * the daemon looks up (an unknown session is refused `session_not_found`).
 */
@Serializable
data class DecisionAdd(
    val projectId: String,
    val summary: String,
    val sessionId: String? = null,
    val body: String? = null,
    val supersedes: String? = null,
)

/** `decision_list` payload: active decisions only unless [includeSuperseded]. */
@Serializable
data class DecisionList(
    val projectId: String,
    val includeSuperseded: Boolean = false,
)

/** `decision_get` payload: [ref] is `D7` or `7`. */
@Serializable
data class DecisionGet(
    val projectId: String,
    val ref: String,
)

/** `D7`, `d7` or `7` -> 7; anything else (zero, negative, text) is refused. */
fun parseDecisionRef(ref: String): Int {
    val text = ref.trim()
    val digits = if (text.startsWith("D") || text.startsWith("d")) text.substring(1) else text
    return digits.toIntOrNull()?.takeIf { it >= 1 && digits.all(Char::isDigit) }
        ?: throw IllegalArgumentException("invalid decision ref '$ref'; expected D<n> or <n>")
}

fun decisionRef(number: Int): String = "D$number"
