package com.superiorsoftllc.infantry.plugins.official.notes.daemon

import com.superiorsoftllc.infantry.daemon.api.storage.PluginStorage
import com.superiorsoftllc.infantry.decisions.Decision
import com.superiorsoftllc.infantry.decisions.decisionRef
import com.superiorsoftllc.infantry.decisions.parseDecisionRef

/**
 * P10-K4 / DP-7c (docs/INFANTRY-PHASE10-KNOWLEDGE.md K-D7): a project's append-only decisions log, over the `decisions` table
 * of the daemon's own SQLite file, adopted through the plugin storage (module.json `daemon.storage.adopt`; core creates the
 * table and its triggers) so a decision survives a restart.
 *
 * The store owns the rules; the table backs them (triggers abort UPDATE/DELETE, the supersede trigger keeps a supersede
 * inside its project, a partial unique index lets a decision be superseded once):
 *  - numbering is per project, from 1, with no gap;
 *  - `supersedes` must name a decision of the SAME project ([DecisionRefusal.DECISION_NOT_FOUND] otherwise, another
 *    project's number included) that is still active ([DecisionRefusal.ALREADY_SUPERSEDED] otherwise);
 *  - `summary` is one non-blank line of at most [SUMMARY_MAX] chars; `body` at most [BODY_MAX] chars.
 * There is no edit and no delete. A refusal writes nothing.
 *
 * [add] reads the next number and the supersede target and inserts inside ONE storage transaction, so two adds can never
 * take the same number or supersede the same decision.
 */
class DecisionStore(
    private val db: PluginStorage,
    private val clock: () -> Long = System::currentTimeMillis,
) {
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

        val number = db.transaction {
            if (target != null) {
                db.query("SELECT number FROM decisions WHERE project_id = ? AND number = ?", listOf(project, target.toLong())).firstOrNull()
                    ?: throw DecisionRefusal(DecisionRefusal.DECISION_NOT_FOUND, "no decision ${decisionRef(target)} in project '$project'")
                db.query("SELECT number FROM decisions WHERE project_id = ? AND supersedes = ?", listOf(project, target.toLong())).firstOrNull()?.let { later ->
                    throw DecisionRefusal(
                        DecisionRefusal.ALREADY_SUPERSEDED,
                        "${decisionRef(target)} is already superseded by ${decisionRef((later["number"] as Number).toInt())}; " +
                            "supersede the head of its chain instead",
                    )
                }
            }
            val next = (db.query("SELECT COALESCE(MAX(number), 0) AS n FROM decisions WHERE project_id = ?", listOf(project)).first()["n"] as Number).toLong() + 1
            db.execute(
                "INSERT INTO decisions(project_id, number, summary, body, supersedes, author_session_id, author_agent_id, author_role, created_at) " +
                    "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
                listOf(project, next, line, text, target?.toLong(), author.sessionId, author.agentId, author.role, clock()),
            )
            next.toInt()
        }
        return get(project, number.toString())!!
    }

    /** One decision by ref (`D7` / `7`), or null when the project holds no such number. */
    fun get(projectId: String, ref: String): Decision? {
        val project = projectId.trim()
        val number = parseDecisionRef(ref).toLong()
        val row = db.query("SELECT * FROM decisions WHERE project_id = ? AND number = ?", listOf(project, number)).firstOrNull() ?: return null
        val by = db.query("SELECT number FROM decisions WHERE project_id = ? AND supersedes = ?", listOf(project, number)).firstOrNull()
        return row.toDecision((by?.get("number") as? Number)?.toInt())
    }

    /** The project's decisions in number order: active ones only unless [includeSuperseded]. */
    fun list(projectId: String, includeSuperseded: Boolean = false): List<Decision> {
        val rows = db.query("SELECT * FROM decisions WHERE project_id = ? ORDER BY number ASC", listOf(projectId.trim()))
        val supersededBy = rows.mapNotNull { r -> (r["supersedes"] as? Number)?.let { it.toInt() to (r["number"] as Number).toInt() } }.toMap()
        return rows.map { it.toDecision(supersededBy[(it["number"] as Number).toInt()]) }
            .filter { includeSuperseded || it.active }
    }

    private fun Map<String, Any?>.toDecision(supersededBy: Int?): Decision {
        val n = (this["number"] as Number).toInt()
        return Decision(
            projectId = this["project_id"] as String,
            number = n,
            ref = decisionRef(n),
            summary = this["summary"] as String,
            body = this["body"] as String,
            supersedes = (this["supersedes"] as? Number)?.toInt(),
            supersededBy = supersededBy,
            authorSessionId = this["author_session_id"] as String?,
            authorAgentId = this["author_agent_id"] as String?,
            authorRole = this["author_role"] as String?,
            createdAt = (this["created_at"] as Number).toLong(),
        )
    }

    companion object {
        const val SUMMARY_MAX = 200
        const val BODY_MAX = 16_000
    }
}
