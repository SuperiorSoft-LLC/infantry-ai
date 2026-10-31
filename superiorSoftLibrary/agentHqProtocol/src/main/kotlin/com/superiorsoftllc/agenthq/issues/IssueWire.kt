package com.superiorsoftllc.agenthq.issues

import kotlinx.serialization.Serializable

/**
 * P10-K6 (contract 1.54.0, docs/AGENTHQ-PHASE10-KNOWLEDGE.md K-D8): one issue of a project's append-only issues log,
 * with its attempts and its fix ([events], in `seq` order).
 *
 * [number] is per project from 1 and shown as [ref] (`I<number>`). [status] is DERIVED: `fixed` once [events] holds a
 * `fix`, `open` otherwise. [implicit] marks an issue an attempt opened because it had no issue to go to. [location] is
 * the path the issue is about ("" for an implicit issue whose attempt named none). [createdAt] is Unix epoch millis.
 */
@Serializable
data class Issue(
    val projectId: String,
    val number: Int,
    val ref: String,
    val summary: String,
    val location: String = "",
    val ticketId: String? = null,
    val implicit: Boolean = false,
    val status: String,
    val openedBySession: String? = null,
    val openedByAgent: String? = null,
    val createdAt: Long,
    val events: List<IssueEvent> = emptyList(),
) {
    val fixed: Boolean get() = status == STATUS_FIXED

    companion object {
        const val STATUS_OPEN = "open"
        const val STATUS_FIXED = "fixed"
    }
}

/** One attempt (`kind` `attempt`, [outcome] worked / failed / partial) or the fix (`kind` `fix`, no outcome). */
@Serializable
data class IssueEvent(
    val seq: Int,
    val kind: String,
    val summary: String,
    val outcome: String? = null,
    val location: String? = null,
    val sessionId: String? = null,
    val agentId: String? = null,
    val createdAt: Long,
) {
    companion object {
        const val KIND_ATTEMPT = "attempt"
        const val KIND_FIX = "fix"
        const val OUTCOME_WORKED = "worked"
        const val OUTCOME_FAILED = "failed"
        const val OUTCOME_PARTIAL = "partial"
        val OUTCOMES = listOf(OUTCOME_WORKED, OUTCOME_FAILED, OUTCOME_PARTIAL)
    }
}

/** `issue_open` payload: [location] is required (projectmem `log_issue(summary, location)`). */
@Serializable
data class IssueOpen(
    val projectId: String,
    val summary: String,
    val location: String,
    val sessionId: String? = null,
    val ticketId: String? = null,
)

/**
 * `issue_attempt` payload. [outcome] defaults to `failed`. Without [issue] the attempt goes to the declared session's
 * active issue, or opens an implicit one when there is none.
 */
@Serializable
data class IssueAttempt(
    val projectId: String,
    val summary: String,
    val sessionId: String? = null,
    val outcome: String? = null,
    val location: String? = null,
    val issue: String? = null,
)

/** `issue_fix` payload: closes [issue], or the declared session's active issue (`no_active_issue` with neither). */
@Serializable
data class IssueFix(
    val projectId: String,
    val summary: String,
    val sessionId: String? = null,
    val issue: String? = null,
)

/** `issue_list` payload: open issues only unless [includeFixed]; [location] keeps those at or under that path. */
@Serializable
data class IssueList(
    val projectId: String,
    val includeFixed: Boolean = false,
    val location: String? = null,
)

/** `issue_get` payload: [ref] is `I7` or `7`. */
@Serializable
data class IssueGet(
    val projectId: String,
    val ref: String,
)

/** `issue_precheck` payload: the issues whose location is [path] or lies under it, open ones first. */
@Serializable
data class IssuePrecheck(
    val projectId: String,
    val path: String,
)

/** `I7`, `i7` or `7` -> 7; anything else (zero, negative, text) is refused. */
fun parseIssueRef(ref: String): Int {
    val text = ref.trim()
    val digits = if (text.startsWith("I") || text.startsWith("i")) text.substring(1) else text
    return digits.toIntOrNull()?.takeIf { it >= 1 && digits.all(Char::isDigit) }
        ?: throw IllegalArgumentException("invalid issue ref '$ref'; expected I<n> or <n>")
}

/**
 * A location as path segments: `\` becomes `/`, a trailing `:line` (or `:line:col`) is dropped, and empty and `.`
 * segments go. `src\a.kt:12` and `./src/a.kt` are both `[src, a.kt]`.
 */
fun locationSegments(location: String): List<String> =
    location.trim().replace('\\', '/').replace(LINE_SUFFIX, "")
        .split('/').filter { it.isNotEmpty() && it != "." }

/** True when [location] is [path] or lies under it, compared by segment: `src/a` matches `src/a/b`, never `src/ab`. */
fun locationUnder(location: String, path: String): Boolean {
    val prefix = locationSegments(path)
    if (prefix.isEmpty()) return false
    val segments = locationSegments(location)
    return segments.size >= prefix.size && segments.subList(0, prefix.size) == prefix
}

private val LINE_SUFFIX = Regex("(:\\d+)+$")
