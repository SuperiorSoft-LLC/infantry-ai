package com.superiorsoftllc.agenthq.role

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import com.superiorsoftllc.agenthq.db.AgentHQDatabaseFactory
import com.superiorsoftllc.agenthq.session.JsonSessionStore
import com.superiorsoftllc.agenthq.session.SessionRecord
import com.superiorsoftllc.agenthq.session.SessionState
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * ORIGIN-SCRUB-1a: a database written before the role ids were renamed still works after it. Every row that names
 * a role by a retired id (an agent profile, a session's frozen role, a ticket assigned to `role:<id>`, a role's
 * binding and trait list) reads back under the new id once the file is opened, opening it again changes nothing,
 * and the append-only decision log is left exactly as written (it refuses an UPDATE). Temp files only: this never touches a real ~/.agenthq.
 */
class RoleIdMigrationTest {

    private val dir: Path = Files.createTempDirectory("role-id-migration")
    private val db: String = dir.resolve("agenthq.db").toString()

    @AfterTest
    fun cleanup() {
        dir.toFile().deleteRecursively()
    }

    /** A file as an older build left it: every role-bearing column holds a retired id. */
    private fun seedOldIds() {
        AgentHQDatabaseFactory.open(db).use { h ->
            val d = h.driver
            d.exec("INSERT INTO agents(id, name, role, created_at, updated_at) VALUES ('a1', 'Ideas', 'brainstormer', 1, 1)")
            d.exec("INSERT INTO agents(id, name, role, created_at, updated_at) VALUES ('a2', 'Steward', 'git', 1, 1)")
            d.exec("INSERT INTO agents(id, name, role, created_at, updated_at) VALUES ('a3', 'Plain', 'backend', 1, 1)")
            d.exec("INSERT INTO session_instances(session_id, agent_id, role, created_at) VALUES ('s1', 'a1', 'chief-of-staff', 1)")
            d.exec("INSERT INTO decisions(project_id, number, summary, author_role, created_at) VALUES ('p', 1, 'x', 'i18n', 1)")
            d.exec("INSERT INTO tickets(id, title, status, position, created_at, updated_at, agent_id) VALUES ('t1', 'T', 'todo', 0, 1, 1, 'role:bot')")
            d.exec("INSERT INTO tickets(id, title, status, position, created_at, updated_at, agent_id) VALUES ('t2', 'U', 'todo', 1, 1, 1, 'a2')")
            d.exec("INSERT INTO role_bindings(role_id, provider, updated_at) VALUES ('brainstormer', 'claude', 1)")
            // A binding under BOTH ids (an old row, and one written after the rename): the new id's row wins.
            d.exec("INSERT INTO role_bindings(role_id, provider, updated_at) VALUES ('git', 'old', 1)")
            d.exec("INSERT INTO role_bindings(role_id, provider, updated_at) VALUES ('version-control', 'new', 2)")
            d.exec("INSERT INTO role_traits(role_id, trait_ids, updated_at) VALUES ('i18n', '[\"t\"]', 1)")
        }
    }

    private fun state(): Map<String, List<String>> = AgentHQDatabaseFactory.open(db).use { h ->
        val d = h.driver
        mapOf(
            "agents" to d.strings("SELECT id || '=' || role FROM agents ORDER BY id"),
            "sessions" to d.strings("SELECT session_id || '=' || role FROM session_instances ORDER BY session_id"),
            "decisions" to d.strings("SELECT number || '=' || author_role FROM decisions ORDER BY number"),
            "tickets" to d.strings("SELECT id || '=' || agent_id FROM tickets ORDER BY id"),
            "bindings" to d.strings("SELECT role_id || '=' || provider FROM role_bindings ORDER BY role_id"),
            "traits" to d.strings("SELECT role_id || '=' || trait_ids FROM role_traits ORDER BY role_id"),
        )
    }

    private val expected = mapOf(
        "agents" to listOf("a1=ideation", "a2=version-control", "a3=backend"),
        "sessions" to listOf("s1=assistant"),
        // Append-only history: a decision keeps the role its author had when it was written.
        "decisions" to listOf("1=i18n"),
        "tickets" to listOf("t1=role:automation", "t2=a2"),
        "bindings" to listOf("ideation=claude", "version-control=new"),
        "traits" to listOf("localization=[\"t\"]"),
    )

    @Test
    fun anOldRoleIdDatabaseReadsBackUnderTheNewIds() {
        seedOldIds()
        val after = state()
        for ((table, rows) in expected) {
            assertEquals(rows, after[table], "$table still carries a retired role id")
        }
    }

    @Test
    fun theMigrationIsIdempotent() {
        seedOldIds()
        val first = state()
        val second = state()
        assertEquals(expected, first)
        assertEquals(first, second, "a second open must change nothing")
    }

    /** Sessions live in a JSON file, not the database: a saved retired role id reads back under its replacement. */
    @Test
    fun aSessionSavedUnderARetiredRoleIdLoadsUnderTheNewId() {
        val store = JsonSessionStore(dir.resolve("sessions.json").toFile())
        val old = SessionRecord("s1", "stub", dir.toString(), 1, 1, SessionState.entries.first(), role = "git")
        val plain = old.copy(id = "s2", role = "backend")
        store.saveAll(listOf(old, plain))
        assertEquals(listOf("version-control", "backend"), store.load().sortedBy { it.id }.map { it.role })
    }

    private fun SqlDriver.exec(sql: String) {
        execute(null, sql, 0)
    }

    private fun SqlDriver.strings(sql: String): List<String> = executeQuery(
        identifier = null,
        sql = sql,
        mapper = { c ->
            val out = mutableListOf<String>()
            while (c.next().value) out += c.getString(0).orEmpty()
            QueryResult.Value(out)
        },
        parameters = 0,
    ).value
}
