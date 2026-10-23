package com.superiorsoftllc.agenthq.decisions

import com.superiorsoftllc.agenthq.db.Decisions
import com.superiorsoftllc.agenthq.persistence.SqlAgentHqStore

/**
 * P10-K4 (docs/AGENTHQ-PHASE10-KNOWLEDGE.md K-D7): a project's append-only decisions log, over the `decisions` table of
 * the daemon's own SQLite file (so a decision survives a restart).
 *
 * The store owns the rules; the table backs them (triggers abort UPDATE/DELETE, a self foreign key keeps a supersede
 * inside its project, a partial unique index lets a decision be superseded once):
 *  - numbering is per project, from 1, with no gap;
 *  - `supersedes` must name a decision of the SAME project ([DecisionRefusal.DECISION_NOT_FOUND] otherwise, another
 *    project's number included) that is still active ([DecisionRefusal.ALREADY_SUPERSEDED] otherwise);
 *  - `summary` is one non-blank line of at most [SUMMARY_MAX] chars; `body` at most [BODY_MAX] chars.
 * There is no edit and no delete. A refusal writes nothing.
 *
 * [add] reads the next number and the supersede target and inserts inside ONE write-first transaction
 * ([SqlAgentHqStore.writeTransaction]), so two adds can never take the same number or supersede the same decision.
 */
class DecisionStore(
    private val store: SqlAgentHqStore,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val q get() = store.database.decisionsQueries

    @Synchronized
    fun add(
        projectId: String,
        summary: String,
        body: String? = null,
        supersedes: String? = null,
        author: DecisionAuthor = DecisionAuthor.NONE,
    ): Decision {
        val project = projectId.trim()
        require(project.isNotEmpty()) { "projectId is required" }
        val line = summary.trim()
        require(line.isNotEmpty()) { "summary is required: one line stating the decision" }
        require('\n' !in line && '\r' !in line) { "summary must be one line; put the reasons in body" }
        require(line.length <= SUMMARY_MAX) { "summary is ${line.length} chars; at most $SUMMARY_MAX" }
        val text = body ?: ""
        require(text.length <= BODY_MAX) { "body is ${text.length} chars; at most $BODY_MAX" }
        val target = supersedes?.takeIf { it.isNotBlank() }?.let(::parseDecisionRef)

        val row = store.writeTransaction {
            if (target != null) {
                q.getDecision(project, target.toLong()).executeAsOneOrNull()
                    ?: throw DecisionRefusal(
                        DecisionRefusal.DECISION_NOT_FOUND,
                        "no decision ${decisionRef(target)} in project '$project'",
                    )
                q.getSuperseder(project, target.toLong()).executeAsOneOrNull()?.let { later ->
                    throw DecisionRefusal(
                        DecisionRefusal.ALREADY_SUPERSEDED,
                        "${decisionRef(target)} is already superseded by ${decisionRef(later.number.toInt())}; " +
                            "supersede the head of its chain instead",
                    )
                }
            }
            val number = q.lastDecisionNumber(project).executeAsOne() + 1
            q.insertDecision(
                project_id = project,
                number = number,
                summary = line,
                body = text,
                supersedes = target?.toLong(),
                author_session_id = author.sessionId,
                author_agent_id = author.agentId,
                author_role = author.role,
                created_at = clock(),
            )
            q.getDecision(project, number).executeAsOne()
        }
        return row.toDecision(supersededBy = null)
    }

    /** One decision by ref (`D7` / `7`), or null when the project holds no such number. */
    fun get(projectId: String, ref: String): Decision? {
        val project = projectId.trim()
        val number = parseDecisionRef(ref).toLong()
        val row = q.getDecision(project, number).executeAsOneOrNull() ?: return null
        return row.toDecision(q.getSuperseder(project, number).executeAsOneOrNull()?.number?.toInt())
    }

    /** The project's decisions in number order: active ones only unless [includeSuperseded]. */
    fun list(projectId: String, includeSuperseded: Boolean = false): List<Decision> {
        val rows = q.getProjectDecisions(projectId.trim()).executeAsList()
        val supersededBy = rows.mapNotNull { r -> r.supersedes?.let { it.toInt() to r.number.toInt() } }.toMap()
        return rows.map { it.toDecision(supersededBy[it.number.toInt()]) }
            .filter { includeSuperseded || it.active }
    }

    private fun Decisions.toDecision(supersededBy: Int?) = Decision(
        projectId = project_id,
        number = number.toInt(),
        ref = decisionRef(number.toInt()),
        summary = summary,
        body = body,
        supersedes = supersedes?.toInt(),
        supersededBy = supersededBy,
        authorSessionId = author_session_id,
        authorAgentId = author_agent_id,
        authorRole = author_role,
        createdAt = created_at,
    )

    companion object {
        const val SUMMARY_MAX = 200
        const val BODY_MAX = 16_000
    }
}
