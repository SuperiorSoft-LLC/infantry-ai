// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.plugins.official.notes.daemon

import com.superiorsoftllc.infantry.daemon.api.storage.PluginStorage
import java.sql.Connection
import java.sql.DriverManager

/**
 * DP-7c test support: a [PluginStorage] over a plain JDBC connection (the real one, LocalPluginStorage, is covered by
 * core's PluginStorageTest) and the decisions/issues schema as core creates it (core migrations own the tables; the
 * plugin adopts them), triggers and unique indexes included.
 */
class JdbcStorage(private val c: Connection) : PluginStorage {
    override fun migrate(version: Int, sql: List<String>) = sql.forEach { c.createStatement().use { s -> s.execute(it) } }
    override fun query(sql: String, args: List<Any?>): List<Map<String, Any?>> = c.prepareStatement(sql).use { ps ->
        args.forEachIndexed { i, a -> ps.setObject(i + 1, a) }
        ps.executeQuery().use { rs ->
            val cols = (1..rs.metaData.columnCount).map { rs.metaData.getColumnLabel(it) }
            generateSequence { if (rs.next()) cols.associateWith { k -> rs.getObject(k) } else null }.toList()
        }
    }
    override fun execute(sql: String, args: List<Any?>): Int = c.prepareStatement(sql).use { ps ->
        args.forEachIndexed { i, a -> ps.setObject(i + 1, a) }
        ps.executeUpdate()
    }
    override fun <T> transaction(block: () -> T): T = block()
}

object NotesTestDb {
    /** Opens (creating it if need be) the SQLite file at [path] with the Notes tables, over a fresh connection. */
    fun open(path: String): Pair<Connection, JdbcStorage> {
        val c = DriverManager.getConnection("jdbc:sqlite:$path?foreign_keys=true")
        return c to JdbcStorage(c).also { it.migrate(1, SCHEMA) }
    }

    /** DP-7f: an open handle the way the memory tests used the database handle: [database] to build a store over, [close] to stop. */
    class Handle(private val conn: Connection, val database: JdbcStorage) : AutoCloseable {
        override fun close() = conn.close()
    }

    fun openHandle(path: String): Handle = open(path).let { (c, s) -> Handle(c, s) }

    val SCHEMA = listOf(
        "CREATE TABLE IF NOT EXISTS memory_notes (id TEXT NOT NULL PRIMARY KEY, project_id TEXT NOT NULL, name TEXT NOT NULL, folder TEXT NOT NULL DEFAULT '', " +
            "description TEXT NOT NULL DEFAULT '', body TEXT NOT NULL DEFAULT '', updated_at TEXT NOT NULL, created_at INTEGER NOT NULL, " +
            "scope TEXT NOT NULL DEFAULT 'project', owner_id TEXT NOT NULL DEFAULT '')",
        "CREATE TABLE IF NOT EXISTS decisions (project_id TEXT NOT NULL, number INTEGER NOT NULL CHECK (number >= 1), summary TEXT NOT NULL, " +
            "body TEXT NOT NULL DEFAULT '', supersedes INTEGER CHECK (supersedes IS NULL OR (supersedes >= 1 AND supersedes < number)), " +
            "author_session_id TEXT, author_agent_id TEXT, author_role TEXT, created_at INTEGER NOT NULL, PRIMARY KEY (project_id, number))",
        "CREATE UNIQUE INDEX IF NOT EXISTS index_decisions_on_supersedes ON decisions(project_id, supersedes) WHERE supersedes IS NOT NULL",
        "CREATE TRIGGER IF NOT EXISTS decisions_supersedes_same_project BEFORE INSERT ON decisions BEGIN " +
            "SELECT RAISE(ABORT, 'decisions supersede a decision of the same project') WHERE new.supersedes IS NOT NULL " +
            "AND NOT EXISTS (SELECT 1 FROM decisions WHERE project_id = new.project_id AND number = new.supersedes); END",
        "CREATE TRIGGER IF NOT EXISTS decisions_append_only_update BEFORE UPDATE ON decisions BEGIN SELECT RAISE(ABORT, 'decisions are append-only'); END",
        "CREATE TRIGGER IF NOT EXISTS decisions_append_only_delete BEFORE DELETE ON decisions BEGIN SELECT RAISE(ABORT, 'decisions are append-only'); END",
        "CREATE TABLE IF NOT EXISTS issues (project_id TEXT NOT NULL, number INTEGER NOT NULL CHECK (number >= 1), summary TEXT NOT NULL, " +
            "location TEXT NOT NULL DEFAULT '', ticket_id TEXT, implicit INTEGER NOT NULL DEFAULT 0 CHECK (implicit IN (0, 1)), " +
            "opened_by_session TEXT, opened_by_agent TEXT, created_at INTEGER NOT NULL, PRIMARY KEY (project_id, number))",
        "CREATE TABLE IF NOT EXISTS issue_events (project_id TEXT NOT NULL, issue_number INTEGER NOT NULL, seq INTEGER NOT NULL CHECK (seq >= 1), " +
            "kind TEXT NOT NULL CHECK (kind IN ('attempt', 'fix')), summary TEXT NOT NULL, " +
            "outcome TEXT CHECK ((kind = 'attempt' AND outcome IN ('worked', 'failed', 'partial')) OR (kind = 'fix' AND outcome IS NULL)), " +
            "location TEXT, session_id TEXT, agent_id TEXT, created_at INTEGER NOT NULL, PRIMARY KEY (project_id, issue_number, seq), " +
            "FOREIGN KEY (project_id, issue_number) REFERENCES issues(project_id, number))",
        "CREATE UNIQUE INDEX IF NOT EXISTS index_issue_events_one_fix ON issue_events(project_id, issue_number) WHERE kind = 'fix'",
        "CREATE INDEX IF NOT EXISTS index_issue_events_on_session ON issue_events(project_id, session_id)",
        "CREATE INDEX IF NOT EXISTS index_issues_on_session ON issues(project_id, opened_by_session)",
        "CREATE TRIGGER IF NOT EXISTS issue_events_none_after_fix BEFORE INSERT ON issue_events BEGIN SELECT RAISE(ABORT, 'issue is already fixed') " +
            "WHERE EXISTS (SELECT 1 FROM issue_events WHERE project_id = new.project_id AND issue_number = new.issue_number AND kind = 'fix'); END",
        "CREATE TRIGGER IF NOT EXISTS issues_append_only_update BEFORE UPDATE ON issues BEGIN SELECT RAISE(ABORT, 'issues are append-only'); END",
        "CREATE TRIGGER IF NOT EXISTS issues_append_only_delete BEFORE DELETE ON issues BEGIN SELECT RAISE(ABORT, 'issues are append-only'); END",
        "CREATE TRIGGER IF NOT EXISTS issue_events_append_only_update BEFORE UPDATE ON issue_events BEGIN SELECT RAISE(ABORT, 'issue events are append-only'); END",
        "CREATE TRIGGER IF NOT EXISTS issue_events_append_only_delete BEFORE DELETE ON issue_events BEGIN SELECT RAISE(ABORT, 'issue events are append-only'); END",
    )
}
