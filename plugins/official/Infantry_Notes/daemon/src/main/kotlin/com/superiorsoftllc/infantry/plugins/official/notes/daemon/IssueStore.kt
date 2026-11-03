package com.superiorsoftllc.infantry.plugins.official.notes.daemon

import com.superiorsoftllc.infantry.daemon.api.storage.PluginStorage
import com.superiorsoftllc.infantry.issues.Issue
import com.superiorsoftllc.infantry.issues.IssueEvent
import com.superiorsoftllc.infantry.issues.locationSegments
import com.superiorsoftllc.infantry.issues.locationUnder
import com.superiorsoftllc.infantry.issues.parseIssueRef

/**
 * P10-K6 / DP-7c (docs/INFANTRY-PHASE10-KNOWLEDGE.md K-D8): a project's append-only issues log, over the `issues` and
 * `issue_events` tables of the daemon's own SQLite file, adopted through the plugin storage (module.json `daemon.storage.adopt`; core creates them) (so an issue and its attempts survive a restart).
 *
 * The store owns the rules; the tables back them (triggers abort UPDATE/DELETE, one fix per issue, nothing after it):
 *  - [open] numbers per project from 1 and needs a `location`; the issue becomes the session's active issue.
 *  - [attempt] goes to the named issue, else to the session's ACTIVE issue, else opens an IMPLICIT issue from the
 *    attempt's own text (so an attempt is never lost). `outcome` is worked / failed / partial, default failed.
 *  - [fix] closes the named issue or the active one ([IssueRefusal.NO_ACTIVE_ISSUE] with neither); a second fix, or an
 *    attempt on a fixed issue, is [IssueRefusal.ALREADY_FIXED]: a recurrence is a new issue.
 *  - The ACTIVE issue of a session is the open issue this session most recently opened or named in an attempt. It is
 *    PER SESSION (section 2.11): another session's issues are never a candidate, and a write with no session has none.
 *  - [precheck] returns the issues whose location is a path or lies under it, by segment, open ones first.
 * A refusal writes nothing.
 *
 * Every write reads what it needs and inserts inside ONE storage transaction,
 * so two writers can never take the same number or seq, nor both fix one issue. `created_at` is kept strictly
 * increasing (one millisecond past the last row at worst), so "most recent" is never a tie.
 */
class IssueStore(
    private val db: PluginStorage,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private var lastAt: Long? = null

    @Synchronized
    fun open(
        projectId: String,
        summary: String,
        location: String,
        ticketId: String? = null,
        actor: IssueActor = IssueActor.NONE,
    ): Issue {
        val project = project(projectId)
        val line = issueSummary(summary)
        val where = location(location) ?: throw IllegalArgumentException("location is required: the path the issue is about")
        val ticket = ticketId?.trim()?.takeIf { it.isNotEmpty() }
        val number = db.transaction { insertIssue(project, line, where, ticket, implicit = false, actor) }
        return get(project, number)!!
    }

    @Synchronized
    fun attempt(
        projectId: String,
        summary: String,
        outcome: String? = null,
        location: String? = null,
        issue: String? = null,
        actor: IssueActor = IssueActor.NONE,
    ): Issue {
        val project = project(projectId)
        val text = eventSummary(summary)
        val result = outcome?.trim()?.takeIf { it.isNotEmpty() } ?: IssueEvent.OUTCOME_FAILED
        require(result in IssueEvent.OUTCOMES) { "invalid outcome '$outcome'; expected ${IssueEvent.OUTCOMES.joinToString(" | ")}" }
        val where = location(location)
        val named = issue?.takeIf { it.isNotBlank() }?.let(::parseIssueRef)

        val number = db.transaction {
            val target = when {
                named != null -> requireOpen(project, named)
                else -> activeIn(project, actor.sessionId)
                    ?: insertIssue(project, implicitSummary(text), where ?: "", ticket = null, implicit = true, actor)
            }
            insertEvent(project, target, IssueEvent.KIND_ATTEMPT, text, result, where, actor)
            target
        }
        return get(project, number)!!
    }

    @Synchronized
    fun fix(projectId: String, summary: String, issue: String? = null, actor: IssueActor = IssueActor.NONE): Issue {
        val project = project(projectId)
        val text = eventSummary(summary)
        val named = issue?.takeIf { it.isNotBlank() }?.let(::parseIssueRef)
        val number = db.transaction {
            val target = if (named != null) {
                requireOpen(project, named)
            } else {
                activeIn(project, actor.sessionId) ?: throw IssueRefusal(
                    IssueRefusal.NO_ACTIVE_ISSUE,
                    if (actor.sessionId == null) "no issue named and no session declared; name one: I<n>"
                    else "session '${actor.sessionId}' has no open issue in project '$project'; name one: I<n>",
                )
            }
            insertEvent(project, target, IssueEvent.KIND_FIX, text, outcome = null, location = null, actor)
            target
        }
        return get(project, number)!!
    }

    /** One issue with its events, by ref (`I7` / `7`), or null when the project holds no such number. */
    fun get(projectId: String, ref: String): Issue? = get(projectId.trim(), parseIssueRef(ref))

    /** The session's active issue (see the class doc), or null. A null or blank session has none. */
    fun active(projectId: String, sessionId: String?): Issue? =
        activeIn(projectId.trim(), sessionId)?.let { get(projectId.trim(), it) }

    /** The project's issues in number order: open ones only unless [includeFixed]; at or under [location] if given. */
    fun list(projectId: String, includeFixed: Boolean = false, location: String? = null): List<Issue> {
        val path = location?.takeIf { it.isNotBlank() }
        return all(projectId.trim())
            .filter { includeFixed || !it.fixed }
            .filter { path == null || locationUnder(it.location, path) }
    }

    /** The issues whose location is [path] or lies under it (by segment): open ones first, each group in number order. */
    fun precheck(projectId: String, path: String): List<Issue> {
        require(locationSegments(path).isNotEmpty()) { "path is required" }
        val (open, fixed) = all(projectId.trim()).filter { locationUnder(it.location, path) }.partition { !it.fixed }
        return open + fixed
    }

    // ------------------------------------------------------------------------

    private fun get(project: String, number: Int): Issue? {
        val row = db.query("SELECT * FROM issues WHERE project_id = ? AND number = ?", listOf(project, number.toLong())).firstOrNull() ?: return null
        return row.toIssue(db.query("SELECT * FROM issue_events WHERE project_id = ? AND issue_number = ? ORDER BY seq ASC", listOf(project, number.toLong())))
    }

    private fun all(project: String): List<Issue> {
        val events = db.query("SELECT * FROM issue_events WHERE project_id = ? ORDER BY issue_number ASC, seq ASC", listOf(project)).groupBy { it.long("issue_number") }
        return db.query("SELECT * FROM issues WHERE project_id = ? ORDER BY number ASC", listOf(project)).map { it.toIssue(events[it.long("number")].orEmpty()) }
    }

    /**
     * The open issue [sessionId] most recently opened or named in an attempt, in [project]; null for no session.
     * Only this session's own rows are read, so another session's issues are never a candidate.
     */
    private fun activeIn(project: String, sessionId: String?): Int? {
        val session = sessionId?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val touched = HashMap<Long, Long>()
        for (row in db.query("SELECT number, created_at FROM issues WHERE project_id = ? AND opened_by_session = ?", listOf(project, session))) {
            touched.merge(row.long("number"), row.long("created_at"), ::maxOf)
        }
        for (row in db.query("SELECT issue_number, created_at FROM issue_events WHERE project_id = ? AND session_id = ? AND kind = 'attempt'", listOf(project, session))) {
            touched.merge(row.long("issue_number"), row.long("created_at"), ::maxOf)
        }
        val fixed = fixedNumbers(project).toSet()
        return touched.entries
            .filter { it.key !in fixed }
            .maxWithOrNull(compareBy<Map.Entry<Long, Long>> { it.value }.thenBy { it.key })
            ?.key?.toInt()
    }

    private fun requireOpen(project: String, number: Int): Int {
        db.query("SELECT number FROM issues WHERE project_id = ? AND number = ?", listOf(project, number.toLong())).firstOrNull()
            ?: throw IssueRefusal(IssueRefusal.ISSUE_NOT_FOUND, "no issue ${issueRef(number)} in project '$project'")
        if (number.toLong() in fixedNumbers(project)) {
            throw IssueRefusal(
                IssueRefusal.ALREADY_FIXED,
                "${issueRef(number)} is already fixed; a recurrence is a new issue (infantry issue open)",
            )
        }
        return number
    }

    private fun insertIssue(
        project: String,
        summary: String,
        location: String,
        ticket: String?,
        implicit: Boolean,
        actor: IssueActor,
    ): Int {
        val number = db.query("SELECT COALESCE(MAX(number), 0) AS n FROM issues WHERE project_id = ?", listOf(project)).first().long("n") + 1
        db.execute(
            "INSERT INTO issues(project_id, number, summary, location, ticket_id, implicit, opened_by_session, opened_by_agent, created_at) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
            listOf(project, number, summary, location, ticket, if (implicit) 1L else 0L, actor.sessionId, actor.agentId, now()),
        )
        return number.toInt()
    }

    private fun insertEvent(
        project: String,
        issue: Int,
        kind: String,
        summary: String,
        outcome: String?,
        location: String?,
        actor: IssueActor,
    ) {
        val seq = db.query("SELECT COALESCE(MAX(seq), 0) AS n FROM issue_events WHERE project_id = ? AND issue_number = ?", listOf(project, issue.toLong())).first().long("n") + 1
        db.execute(
            "INSERT INTO issue_events(project_id, issue_number, seq, kind, summary, outcome, location, session_id, agent_id, created_at) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
            listOf(project, issue.toLong(), seq, kind, summary, outcome, location, actor.sessionId, actor.agentId, now()),
        )
    }

    /** The clock, but strictly past every row already written, so the active-issue order never ties. */
    private fun now(): Long {
        val floor = lastAt ?: maxOf(
            db.query("SELECT COALESCE(MAX(created_at), 0) AS n FROM issues").first().long("n"),
            db.query("SELECT COALESCE(MAX(created_at), 0) AS n FROM issue_events").first().long("n"),
        )
        return maxOf(clock(), floor + 1).also { lastAt = it }
    }

    private fun project(projectId: String): String =
        projectId.trim().also { require(it.isNotEmpty()) { "projectId is required" } }

    private fun issueSummary(summary: String): String {
        val line = summary.trim()
        require(line.isNotEmpty()) { "summary is required: one line naming the issue" }
        require('\n' !in line && '\r' !in line) { "summary must be one line" }
        require(line.length <= SUMMARY_MAX) { "summary is ${line.length} chars; at most $SUMMARY_MAX" }
        return line
    }

    private fun eventSummary(summary: String): String {
        val text = summary.trim()
        require(text.isNotEmpty()) { "summary is required: what was tried, or what fixed it" }
        require(text.length <= EVENT_SUMMARY_MAX) { "summary is ${text.length} chars; at most $EVENT_SUMMARY_MAX" }
        return text
    }

    private fun location(location: String?): String? {
        val where = location?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        require('\n' !in where && '\r' !in where) { "location must be one line" }
        require(where.length <= LOCATION_MAX) { "location is ${where.length} chars; at most $LOCATION_MAX" }
        return where
    }

    /** An implicit issue is named by the first line of the attempt that opened it, cut to [SUMMARY_MAX]. */
    private fun implicitSummary(text: String): String {
        val first = text.lineSequence().first().trim()
        return if (first.length <= SUMMARY_MAX) first else first.take(SUMMARY_MAX - 3).trimEnd() + "..."
    }

    private fun fixedNumbers(project: String): List<Long> =
        db.query("SELECT issue_number FROM issue_events WHERE project_id = ? AND kind = 'fix'", listOf(project)).map { it.long("issue_number") }

    private fun Map<String, Any?>.long(key: String): Long = (this[key] as Number).toLong()

    private fun Map<String, Any?>.toIssue(events: List<Map<String, Any?>>): Issue {
        val mapped = events.map {
            IssueEvent(
                seq = it.long("seq").toInt(),
                kind = it["kind"] as String,
                summary = it["summary"] as String,
                outcome = it["outcome"] as String?,
                location = it["location"] as String?,
                sessionId = it["session_id"] as String?,
                agentId = it["agent_id"] as String?,
                createdAt = it.long("created_at"),
            )
        }
        val n = long("number").toInt()
        return Issue(
            projectId = this["project_id"] as String,
            number = n,
            ref = issueRef(n),
            summary = this["summary"] as String,
            location = this["location"] as String,
            ticketId = this["ticket_id"] as String?,
            implicit = long("implicit") == 1L,
            status = if (mapped.any { it.kind == IssueEvent.KIND_FIX }) Issue.STATUS_FIXED else Issue.STATUS_OPEN,
            openedBySession = this["opened_by_session"] as String?,
            openedByAgent = this["opened_by_agent"] as String?,
            createdAt = long("created_at"),
            events = mapped,
        )
    }

    companion object {
        const val SUMMARY_MAX = 200
        const val EVENT_SUMMARY_MAX = 4_000
        const val LOCATION_MAX = 500
    }
}
