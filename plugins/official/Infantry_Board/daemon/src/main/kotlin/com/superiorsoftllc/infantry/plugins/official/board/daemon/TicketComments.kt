package com.superiorsoftllc.infantry.plugins.official.board.daemon

import com.superiorsoftllc.infantry.daemon.api.sessions.SessionNotifier
import com.superiorsoftllc.infantry.daemon.api.storage.PluginStorage
import java.util.UUID

/**
 * TICKET-COMMENTS-2: comments on a ticket, the ticket's creator and its notify list, all in the plugin's own tables (core gets no
 * board code). A comment is an extra channel next to mail: every new comment wakes each notified agent's live session with ONE short
 * mail line, so a worker's "done" reaches the version-control agent, the PM and QA at once.
 *
 * Notify targets are USER-CREATED agents only (the agent registry: agents the owner or a role defines), never an invoked / spawned
 * ticket worker session. Which agents a project notifies by default is project DATA (setting `board.notify.<projectId>`, comma
 * separated agent ids), never code: empty means the creator only.
 *
 * Everything the service needs from the daemon comes in as a port, so it runs over a plain SQLite file in a test.
 */
class TicketComments(
    private val db: PluginStorage,
    /** Is the id an agent of the registry (a user-created / role agent)? */
    private val isRegistryAgent: (String) -> Boolean,
    /** Is the id a session id (an invoked worker)? Only used to word the refusal. */
    private val isSession: (String) -> Boolean,
    /** The ids of an agent's live sessions in a project. */
    private val liveSessions: (agentId: String, projectId: String?) -> List<String>,
    private val notifier: SessionNotifier,
    /** A daemon setting by key (project data), or null. */
    private val setting: (String) -> String?,
    private val clock: () -> Long = System::currentTimeMillis,
    /** Called once a comment is stored (the wiring publishes `ticket.commented` from it). */
    private val onAdded: (Comment) -> Unit = {},
) {
    /** Who wrote a comment: the owner, a session (with its agent), or the daemon. */
    data class Author(val kind: String, val sessionId: String? = null, val agentId: String? = null) {
        val label: String get() = when (kind) {
            OWNER -> "owner"
            DAEMON -> "daemon"
            else -> (agentId ?: "agent") + (sessionId?.let { " (${it.takeLast(8)})" } ?: "")
        }

        companion object {
            const val OWNER = "owner"
            const val DAEMON = "daemon"
            const val SESSION = "session"
            val owner = Author(OWNER)
            val daemon = Author(DAEMON)
        }
    }

    data class Comment(
        val id: String, val ticketId: String, val author: Author, val kind: String, val body: String, val state: String,
        val createdAt: Long, val resolvedAt: Long?, val resolvedBy: String?, val undelivered: List<String>,
    ) {
        val shortId: String get() = id.take(8)
    }

    data class Meta(val createdBy: String, val notify: List<String>)

    data class Summary(val open: Int, val latestOpen: Comment?, val undelivered: List<String>)

    fun migrate() = db.migrate(
        1,
        listOf(
            "CREATE TABLE IF NOT EXISTS infantry_board_comments(id TEXT PRIMARY KEY, ticket_id TEXT NOT NULL, author_kind TEXT NOT NULL, " +
                "author_session TEXT, author_agent TEXT, kind TEXT NOT NULL, body TEXT NOT NULL, state TEXT NOT NULL, created_at INTEGER NOT NULL, " +
                "resolved_at INTEGER, resolved_by TEXT, undelivered TEXT NOT NULL DEFAULT '')",
            "CREATE INDEX IF NOT EXISTS infantry_board_comments_ticket ON infantry_board_comments(ticket_id, created_at)",
            "CREATE TABLE IF NOT EXISTS infantry_board_ticket_meta(ticket_id TEXT PRIMARY KEY, created_by TEXT NOT NULL, notify TEXT NOT NULL DEFAULT '')",
        ),
    )

    // ---- notify list -------------------------------------------------------------------------------------------------------

    /** The ids as registry agent ids; a bare role name counts as `role:<name>`. Anything else is refused with a message naming why. */
    fun validateTargets(ids: List<String>): List<String> = ids.map { raw ->
        val id = raw.trim()
        require(id.isNotEmpty()) { "a notify target is a non-blank agent id" }
        when {
            isRegistryAgent(id) -> id
            isRegistryAgent("role:$id") -> "role:$id"
            isSession(id) -> throw IllegalArgumentException("'$id' is a session, not an agent: only user-created agents (infantry agent-def list) can be notified, not invoked ticket workers")
            else -> throw IllegalArgumentException("'$id' is not a user-created agent (infantry agent-def list shows them); notify takes agent ids")
        }
    }.distinct()

    /** The project's default notify agents (project data), the ones still in the registry. */
    fun projectDefaults(projectId: String?): List<String> {
        if (projectId.isNullOrBlank()) return emptyList()
        return setting("board.notify.$projectId").orEmpty().split(',', '\n').map { it.trim() }.filter { it.isNotEmpty() }
            .mapNotNull { runCatching { validateTargets(listOf(it)).single() }.getOrNull() }.distinct()
    }

    /** Records the creator (never edited afterwards) and the starting notify list: the explicit one if given, else creator + project defaults. */
    fun onCreated(ticketId: String, projectId: String?, creator: Author, explicit: List<String>?) {
        val creatorAgent = creator.agentId?.takeIf { isRegistryAgent(it) }
        val notify = explicit ?: (listOfNotNull(creatorAgent) + projectDefaults(projectId)).distinct()
        db.execute(
            "INSERT OR IGNORE INTO infantry_board_ticket_meta(ticket_id, created_by, notify) VALUES (?, ?, ?)",
            listOf(ticketId, creator.agentId ?: creator.label, notify.joinToString("\n")),
        )
    }

    fun meta(ticketId: String): Meta? = db.query("SELECT * FROM infantry_board_ticket_meta WHERE ticket_id = ?", listOf(ticketId)).firstOrNull()?.let {
        Meta(it["created_by"] as String, (it["notify"] as String).split('\n').filter(String::isNotBlank))
    }

    /** Set (replace), add and remove, validated. A ticket from before this feature gets a meta row (createdBy `unknown`) on its first edit. */
    @Synchronized
    fun editNotify(ticketId: String, set: List<String>?, add: List<String>, remove: List<String>): List<String> {
        val current = meta(ticketId)
        val base = set?.let(::validateTargets) ?: current?.notify.orEmpty()
        val gone = remove.map { it.trim() }.flatMap { listOf(it, "role:$it") }.toSet()
        val next = (base + validateTargets(add)).distinct().filterNot { it in gone }
        if (current == null) {
            db.execute("INSERT INTO infantry_board_ticket_meta(ticket_id, created_by, notify) VALUES (?, 'unknown', ?)", listOf(ticketId, next.joinToString("\n")))
        } else {
            db.execute("UPDATE infantry_board_ticket_meta SET notify = ? WHERE ticket_id = ?", listOf(next.joinToString("\n"), ticketId))
        }
        return next
    }

    // ---- comments ----------------------------------------------------------------------------------------------------------

    @Synchronized
    fun add(ticketId: String, projectId: String?, title: String, author: Author, kind: String, state: String, body: String): Comment {
        require(kind in KINDS) { "kind is one of ${KINDS.joinToString("|")}" }
        require(state in STATES) { "state is one of ${STATES.joinToString("|")}" }
        require(body.isNotBlank()) { "a comment needs a body" }
        val id = UUID.randomUUID().toString()
        val now = clock()
        db.execute(
            "INSERT INTO infantry_board_comments(id, ticket_id, author_kind, author_session, author_agent, kind, body, state, created_at, resolved_at, resolved_by) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
            listOf(id, ticketId, author.kind, author.sessionId, author.agentId, kind, body, state, now, if (state == RESOLVED) now else null, if (state == RESOLVED) author.label else null),
        )
        val undelivered = notifyAll(ticketId, projectId, title, id, author, kind, state, body)
        if (undelivered.isNotEmpty()) db.execute("UPDATE infantry_board_comments SET undelivered = ? WHERE id = ?", listOf(undelivered.joinToString("\n"), id))
        val saved = get(id)!!
        onAdded(saved)
        return saved
    }

    /** ONE short waking mail per live session of every notified agent, the author's own session skipped. An agent with no live session is undelivered. */
    private fun notifyAll(ticketId: String, projectId: String?, title: String, commentId: String, author: Author, kind: String, state: String, body: String): List<String> {
        val agents = meta(ticketId)?.notify.orEmpty()
        val short = ticketId.takeLast(8)
        val line = "[TICKET COMMENT] $short $title: $kind/$state comment ${commentId.take(8)} by ${author.label}: " +
            body.replace(Regex("\\s+"), " ").trim().take(200) + ". Read: infantry_ticket_comments $short"
        val missed = mutableListOf<String>()
        for (agent in agents) {
            if (author.agentId != null && agent == author.agentId) continue
            val sessions = liveSessions(agent, projectId).filter { it != author.sessionId }
            val delivered = sessions.count { notifier.notifySession(it, "ticket comment $short", line) }
            if (delivered == 0) missed += agent
        }
        return missed
    }

    fun get(idOrPrefix: String): Comment? {
        val key = idOrPrefix.trim().replace("%", "").replace("_", "")
        if (key.isEmpty()) return null
        return db.query("SELECT * FROM infantry_board_comments WHERE id LIKE ?", listOf("$key%")).singleOrNull()?.let(::comment)
    }

    /** Marks a comment resolved (idempotent); returns it, or null for an unknown / ambiguous id. No mail. */
    @Synchronized
    fun resolve(idOrPrefix: String, by: Author): Comment? {
        val c = get(idOrPrefix) ?: return null
        if (c.state != RESOLVED) {
            db.execute("UPDATE infantry_board_comments SET state = 'resolved', resolved_at = ?, resolved_by = ? WHERE id = ?", listOf(clock(), by.label, c.id))
        }
        return get(c.id)
    }

    /** Which = latestOpen (default) | latest | first | all, optionally one kind; the limit caps `all` (newest kept, oldest first). */
    fun read(ticketId: String, which: String, kind: String?, limit: Int?): List<Comment> {
        require(which in WHICH) { "which is one of ${WHICH.joinToString("|")}" }
        require(kind == null || kind in KINDS) { "kind is one of ${KINDS.joinToString("|")}" }
        val all = db.query("SELECT * FROM infantry_board_comments WHERE ticket_id = ? ORDER BY created_at, rowid", listOf(ticketId)).map(::comment)
            .filter { kind == null || it.kind == kind }
        return when (which) {
            "latestOpen" -> listOfNotNull(all.lastOrNull { it.state == OPEN })
            "latest" -> listOfNotNull(all.lastOrNull())
            "first" -> listOfNotNull(all.firstOrNull())
            else -> if (limit != null && limit > 0) all.takeLast(limit) else all
        }
    }

    /** TICKET-COMMENTS-4: did session [sessionId] comment on [ticketId] at or after [sinceMs]? */
    fun commentedSince(ticketId: String, sessionId: String, sinceMs: Long): Boolean =
        db.query("SELECT 1 AS x FROM infantry_board_comments WHERE ticket_id = ? AND author_session = ? AND created_at >= ? LIMIT 1", listOf(ticketId, sessionId, sinceMs)).isNotEmpty()

    fun summary(ticketId: String): Summary {
        val all = read(ticketId, "all", null, null)
        val open = all.filter { it.state == OPEN }
        return Summary(open.size, open.lastOrNull(), all.flatMap { it.undelivered }.distinct())
    }

    private fun comment(row: Map<String, Any?>) = Comment(
        id = row["id"] as String,
        ticketId = row["ticket_id"] as String,
        author = Author(row["author_kind"] as String, row["author_session"] as? String, row["author_agent"] as? String),
        kind = row["kind"] as String,
        body = row["body"] as String,
        state = row["state"] as String,
        createdAt = (row["created_at"] as Number).toLong(),
        resolvedAt = (row["resolved_at"] as? Number)?.toLong(),
        resolvedBy = row["resolved_by"] as? String,
        undelivered = (row["undelivered"] as? String).orEmpty().split('\n').filter(String::isNotBlank),
    )

    companion object {
        const val OPEN = "open"
        const val RESOLVED = "resolved"
        val KINDS = listOf("done", "issue", "note")
        val STATES = listOf(OPEN, RESOLVED)
        val WHICH = listOf("latestOpen", "latest", "first", "all")
    }
}
