package com.superiorsoftllc.agenthq.db

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.superiorsoftllc.agenthq.persistence.SqlAgentHqStore
import com.superiorsoftllc.agenthq.persistence.PodRecord
import com.superiorsoftllc.agenthq.persistence.RunEvent
import com.superiorsoftllc.agenthq.persistence.TicketRow
import com.superiorsoftllc.agenthq.protocol.AgentProfile
import com.superiorsoftllc.agenthq.session.SessionState
import java.nio.file.Files
import java.sql.DriverManager
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Phase 4.5 -- the migration chain.
 *
 * VerifyMigrationTask (the SQLDelight task that statically proves the .sqm chain reproduces the .sq
 * schema) is disabled repo-wide by design and stays disabled. This test is the replacement guard:
 * it builds one file through `Schema.create()` and one through `1.sqm` to `5.sqm`, then compares what
 * SQLite itself reports about both -- columns, indexes, foreign keys and CHECK behaviour.
 */
class SchemaMigrationTest {

    private fun newDatabasePath(): String {
        val file = Files.createTempFile("agenthq-migration-test-", ".db")
        file.toFile().delete()
        return file.toAbsolutePath().toString()
    }

    /** Raw JDBC rows, every column as text: schema introspection without the generated layer. */
    private fun rows(path: String, sql: String): List<List<String?>> =
        DriverManager.getConnection("jdbc:sqlite:$path").use { conn ->
            conn.createStatement().use { st ->
                st.executeQuery(sql).use { rs ->
                    val n = rs.metaData.columnCount
                    buildList { while (rs.next()) add((1..n).map { rs.getString(it) }) }
                }
            }
        }

    private fun exec(path: String, sql: String) =
        DriverManager.getConnection("jdbc:sqlite:$path?foreign_keys=true").use { conn ->
            conn.createStatement().use { it.executeUpdate(sql) }
        }

    /** Everything SQLite reports about a table's shape, in a comparable form. */
    private fun shape(path: String, table: String): Map<String, Any> {
        val indexes = rows(path, "PRAGMA index_list($table);")
            .map { it[1]!! }
            .filterNot { it.startsWith("sqlite_autoindex") }
            .sorted()
            .associateWith { idx -> rows(path, "PRAGMA index_info($idx);").map { it[2] } }
        return mapOf(
            "columns" to rows(path, "PRAGMA table_info($table);"),
            "indexes" to indexes,
            // id, seq, table, from, to, on_update, on_delete, match
            "foreignKeys" to rows(path, "PRAGMA foreign_key_list($table);").map { it.drop(2) },
        )
    }

    private val tables = listOf(
        "runs", "pods", "events", "pod_nodes", "node_dependencies", "node_attempts", "agents",
        "tickets", "ticket_links", "settings", "ticket_runs", "projects", "attention", "epics", "role_bindings", "triggers", "prompts", "skills", "traits", "role_traits", "session_instances",
        "decisions", "issues", "issue_events", "story_migration", "node_config",
    )

    /**
     * P10-K4: what the PRAGMAs above cannot see -- triggers, and a partial index's WHERE clause -- as SQLite stored them,
     * whitespace-normalised, so a fresh file and a migrated one must declare the same triggers and the same index SQL.
     */
    private fun declared(path: String, type: String, table: String): List<List<String?>> =
        rows(path, "SELECT name, sql FROM sqlite_master WHERE type = '$type' AND tbl_name = '$table' AND sql IS NOT NULL ORDER BY name;")
            .map { (name, sql) -> listOf(name, sql?.replace(Regex("\\s+"), " ")?.trim()) }

    @Test
    fun `schema version is 10 - attention records survive daemon restart`() {
        // Version = highest NN.sqm + 1; derived so each new migration does not break this test.
        val dir = java.io.File("src/main/sqldelight/com/superiorsoftllc/agenthq/db")
        val highest = dir.listFiles { f -> f.name.matches(Regex("[0-9]+[.]sqm")) }!!.maxOf { it.name.removeSuffix(".sqm").toLong() }
        assertEquals(highest + 1, AgentHQDatabase.Schema.version, "schema version is the highest migration file + 1")
    }

    @Test
    fun `fresh create and the 1-to-10 migration chain produce identical schemas`() {
        val created = newDatabasePath()
        AgentHQDatabaseFactory.open(created).close()

        val migrated = newDatabasePath()
        JdbcSqliteDriver("jdbc:sqlite:$migrated").use { driver ->
            AgentHQDatabase.Schema.migrate(driver, 1, AgentHQDatabase.Schema.version)
        }

        for (table in tables) {
            assertEquals(shape(created, table), shape(migrated, table), "table '$table' drifted")
        }
        for (type in listOf("trigger", "index")) {
            assertEquals(declared(created, type, "decisions"), declared(migrated, type, "decisions"), "decisions ${type}s drifted")
        }
        assertEquals(
            listOf("decisions_append_only_delete", "decisions_append_only_update", "decisions_supersedes_same_project"),
            declared(created, "trigger", "decisions").map { it[0] },
        )
        for (table in listOf("issues", "issue_events")) {
            for (type in listOf("trigger", "index")) {
                assertEquals(declared(created, type, table), declared(migrated, type, table), "$table ${type}s drifted")
            }
        }
        assertEquals(
            listOf("issue_events_append_only_delete", "issue_events_append_only_update", "issue_events_none_after_fix"),
            declared(created, "trigger", "issue_events").map { it[0] },
        )
        assertEquals(listOf("issues_append_only_delete", "issues_append_only_update"), declared(created, "trigger", "issues").map { it[0] })
        // Sanity: the comparison is not vacuous.
        assertTrue((shape(created, "events")["foreignKeys"] as List<*>).isNotEmpty())
        assertTrue((shape(created, "runs")["indexes"] as Map<*, *>).keys.containsAll(
            listOf("index_runs_on_session_id", "index_runs_on_pod_id"),
        ))
        assertTrue((shape(created, "node_attempts")["foreignKeys"] as List<*>).size == 2)
        assertTrue((shape(created, "pod_nodes")["indexes"] as Map<*, *>).keys.contains("index_pod_nodes_on_pod_id"))
        assertTrue((shape(created, "agents")["indexes"] as Map<*, *>).keys.contains("index_agents_on_role"))

        // CHECK constraints are invisible to the PRAGMAs above, so prove them by behaviour on both.
        for (path in listOf(created, migrated)) {
            exec(path, "INSERT INTO runs(id, session_id, provider, working_dir, prompt, state, started_at) " +
                "VALUES ('r', 's', 'p', 'w', 'x', 'BUSY', 1);")
            assertFailsWith<java.sql.SQLException>("raw provider frame type must be refused ($path)") {
                exec(path, "INSERT INTO events(run_id, type, payload, vocabulary_version, created_at) " +
                    "VALUES ('r', 'step_start', '{}', '1.0.0', 1);")
            }
            assertFailsWith<java.sql.SQLException>("max_concurrency 0 must be refused ($path)") {
                exec(path, "INSERT INTO pods(id, goal, state, max_concurrency, created_at) VALUES ('p', 'g', 's', 0, 1);")
            }
            exec(path, "INSERT INTO pods(id, goal, state, max_concurrency, created_at) VALUES ('pp', 'g', 'RUNNING', 1, 1);")
            assertFailsWith<java.sql.SQLException>("max_attempts 0 must be refused ($path)") {
                exec(path, "INSERT INTO pod_nodes(id, pod_id, name, spec_index, prompt, state, max_attempts, created_at) " +
                    "VALUES ('n0', 'pp', 'zero', 0, 'x', 'PENDING', 0, 1);")
            }
            exec(path, "INSERT INTO pod_nodes(id, pod_id, name, spec_index, prompt, state, created_at) " +
                "VALUES ('n1', 'pp', 'one', 0, 'x', 'PENDING', 1);")
            assertFailsWith<java.sql.SQLException>("a self edge must be refused ($path)") {
                exec(path, "INSERT INTO node_dependencies(node_id, depends_on) VALUES ('n1', 'n1');")
            }
            assertFailsWith<java.sql.SQLException>("attempt 0 must be refused ($path)") {
                exec(path, "INSERT INTO node_attempts(run_id, node_id, attempt) VALUES ('r', 'n1', 0);")
            }
        }
    }

    @Test
    fun `a fresh file is stamped with the current version and reopening is a no-op`() {
        val path = newDatabasePath()
        AgentHQDatabaseFactory.open(path).close()
        assertEquals(AgentHQDatabase.Schema.version.toString(), rows(path, "PRAGMA user_version;").single().single())
        AgentHQDatabaseFactory.open(path).close()
        assertEquals(AgentHQDatabase.Schema.version.toString(), rows(path, "PRAGMA user_version;").single().single())
    }

    /** Story 9.2 -- the backlog tables (tickets, ticket_links) enforce their rules on a fresh file. */
    @Test
    fun `the backlog tables enforce positions, flags, link types and cascades`() {
        val path = newDatabasePath()
        AgentHQDatabaseFactory.open(path).close()

        // Both backlog indexes exist.
        val indexes = rows(path, "SELECT name FROM sqlite_master WHERE type = 'index'").map { it.single() }
        assertTrue(indexes.contains("index_tickets_on_status"), "missing index_tickets_on_status in $indexes")
        assertTrue(
            indexes.contains("index_ticket_links_on_related_id"),
            "missing index_ticket_links_on_related_id in $indexes",
        )

        // Both link columns cascade to tickets on delete.
        val foreignKeys = rows(path, "PRAGMA foreign_key_list(ticket_links)")
        assertEquals(2, foreignKeys.size)
        for (fk in foreignKeys) {
            assertEquals("tickets", fk[2], "ticket_links FK must target tickets")
            assertEquals("CASCADE", fk[6], "ticket_links FK must cascade on delete")
        }

        // Valid rows are accepted.
        exec(path, "INSERT INTO tickets(id, title, description, ticket_type, status, position, wont_fix, created_at, updated_at) " +
            "VALUES ('t1', 'T', '', NULL, 'todo', 0, 0, 1, 1);")
        exec(path, "INSERT INTO tickets(id, title, description, ticket_type, status, position, wont_fix, created_at, updated_at) " +
            "VALUES ('t2', 'T', '', NULL, 'todo', 0, 0, 1, 1);")
        exec(path, "INSERT INTO ticket_links(ticket_id, related_id, type, created_at) VALUES ('t1', 't2', 'blocked_by', 1);")

        // Each rule is enforced.
        assertFails {
            exec(path, "INSERT INTO tickets(id, title, description, ticket_type, status, position, wont_fix, created_at, updated_at) " +
                "VALUES ('t3', 'T', '', NULL, 'todo', -1, 0, 1, 1);")
        }
        assertFails {
            exec(path, "INSERT INTO tickets(id, title, description, ticket_type, status, position, wont_fix, created_at, updated_at) " +
                "VALUES ('t4', 'T', '', NULL, 'todo', 0, 2, 1, 1);")
        }
        assertFails {
            exec(path, "INSERT INTO ticket_links(ticket_id, related_id, type, created_at) VALUES ('t2', 't1', 'blocks', 1);")
        }
        assertFails {
            exec(path, "INSERT INTO ticket_links(ticket_id, related_id, type, created_at) VALUES ('t1', 't1', 'relates_to', 1);")
        }
        assertFails {
            exec(path, "INSERT INTO ticket_links(ticket_id, related_id, type, created_at) VALUES ('t1', 'nope', 'relates_to', 1);")
        }

        // Deleting a ticket cascades to its links.
        exec(path, "DELETE FROM tickets WHERE id = 't2';")
        assertEquals("0", rows(path, "SELECT COUNT(*) FROM ticket_links").single().single())
    }

    /**
     * The real upgrade path: a file written by story 1's build (runs table, user_version never
     * written) is migrated in place by the factory, keeping its rows.
     */
    @Test
    fun `a story-1 file is migrated in place and keeps its rows`() {
        val path = newDatabasePath()
        JdbcSqliteDriver("jdbc:sqlite:$path").use { driver ->
            AgentHQDatabase.Schema.migrate(driver, 1, AgentHQDatabaseFactory.STORY_1_VERSION) // 1.sqm only
        }
        exec(path, "INSERT INTO runs(id, session_id, provider, working_dir, prompt, state, started_at, ended_at, exit_code) " +
            "VALUES ('old-run', 'session-1', 'opencode', 'C:/w', 'from story 1', 'EXITED', 10, 20, 0);")
        assertEquals("0", rows(path, "PRAGMA user_version;").single().single())
        assertTrue(rows(path, "SELECT name FROM sqlite_master WHERE name = 'events';").isEmpty())

        SqlAgentHqStore.open(path).use { store ->
            val old = store.runs.get("old-run")!!
            assertEquals("from story 1", old.prompt)
            assertEquals(SessionState.EXITED, old.state)
            assertNull(old.podId)

            // The new tables work on the migrated file, FKs included.
            store.pods.create(PodRecord(id = "pod-1", goal = "g", state = "running", createdAt = 30))
            store.runs.upsert(old.copy(podId = "pod-1"))
            store.events.append("old-run", RunEvent.TextDelta("hello"), 40)
            assertEquals(1L, store.events.count("old-run"))
            assertEquals("pod-1", store.runs.get("old-run")!!.podId)
        }
        assertEquals(AgentHQDatabase.Schema.version.toString(), rows(path, "PRAGMA user_version;").single().single())
    }

    @Test
    fun `a database written by a newer build is refused, not silently opened`() {
        val path = newDatabasePath()
        AgentHQDatabaseFactory.open(path).close()
        exec(path, "PRAGMA user_version = 99;")

        val error = assertFailsWith<IllegalStateException> { AgentHQDatabaseFactory.open(path) }
        assertTrue(error.message!!.contains("v99"))
    }

    /** The Phase 4.5 -> Phase 5 upgrade: a stamped v3 file gains the node tables and keeps its rows. */
    @Test
    fun `a v3 file is migrated in place to v4 and keeps its pods, runs and events`() {
        val path = newDatabasePath()
        JdbcSqliteDriver("jdbc:sqlite:$path").use { driver ->
            AgentHQDatabase.Schema.migrate(driver, 1, 3) // 1.sqm + 2.sqm: exactly the Phase 4.5 schema
            driver.execute(null, "PRAGMA user_version = 3;", 0)
        }
        exec(path, "INSERT INTO pods(id, goal, state, max_concurrency, created_at) VALUES ('pod-v3', 'g', 'RUNNING', 2, 1);")
        exec(path, "INSERT INTO runs(id, session_id, provider, working_dir, prompt, state, started_at, pod_id) " +
            "VALUES ('run-v3', 's', 'p', 'w', 'x', 'IDLE', 1, 'pod-v3');")
        exec(path, "INSERT INTO events(run_id, type, payload, vocabulary_version, created_at) " +
            "VALUES ('run-v3', 'text_delta', 'hi', '1.0.0', 1);")
        assertTrue(rows(path, "SELECT name FROM sqlite_master WHERE name = 'pod_nodes';").isEmpty())

        SqlAgentHqStore.open(path).use { store ->
            assertEquals(2, store.pods.get("pod-v3")!!.maxConcurrency)
            assertEquals("pod-v3", store.runs.get("run-v3")!!.podId)
            assertEquals(1L, store.events.count("run-v3"))
            assertTrue(store.nodes.byPod("pod-v3").isEmpty())
        }
        assertEquals(AgentHQDatabase.Schema.version.toString(), rows(path, "PRAGMA user_version;").single().single())
    }

    /** The Phase 5 -> story 9.1 upgrade: a stamped v4 file gains `agents` and keeps its pods and nodes. */
    @Test
    fun `a v4 file is migrated in place to v5 and keeps its pods and nodes`() {
        val path = newDatabasePath()
        JdbcSqliteDriver("jdbc:sqlite:$path").use { driver ->
            AgentHQDatabase.Schema.migrate(driver, 1, 4) // 1.sqm to 3.sqm: exactly the Phase 5 schema
            driver.execute(null, "PRAGMA user_version = 4;", 0)
        }
        exec(path, "INSERT INTO pods(id, goal, state, max_concurrency, created_at) VALUES ('pod-v4', 'g', 'RUNNING', 1, 1);")
        exec(path, "INSERT INTO pod_nodes(id, pod_id, name, spec_index, prompt, state, created_at) " +
            "VALUES ('node-v4', 'pod-v4', 'one', 0, 'x', 'PENDING', 1);")
        assertTrue(rows(path, "SELECT name FROM sqlite_master WHERE name = 'agents';").isEmpty())

        SqlAgentHqStore.open(path).use { store ->
            assertEquals("one", store.nodes.get("node-v4")!!.name)
            assertTrue(store.agents.all().isEmpty())
            store.agents.save(AgentProfile(id = "backend", name = "Backend", role = "backend"), at = 5)
            assertEquals("backend", store.agents.get("backend")!!.role)
        }
        assertEquals(AgentHQDatabase.Schema.version.toString(), rows(path, "PRAGMA user_version;").single().single())
    }

    /** The story 9.1 -> story 9.2 upgrade: a stamped v5 file gains the backlog and keeps its agent profiles. */
    @Test
    fun `a v5 file is migrated in place to the backlog schema and keeps its agent profiles`() {
        val path = newDatabasePath()
        JdbcSqliteDriver("jdbc:sqlite:$path").use { driver ->
            AgentHQDatabase.Schema.migrate(driver, 1, 5) // 1.sqm to 4.sqm: exactly the story 9.1 schema
            driver.execute(null, "PRAGMA user_version = 5;", 0)
        }
        exec(path, "INSERT INTO agents(id, name, role, provider, model, instructions, created_at, updated_at) " +
            "VALUES ('backend', 'Backend', 'backend', 'opencode', 'deepseek', 'be careful', 1, 1);")
        assertTrue(rows(path, "SELECT name FROM sqlite_master WHERE name = 'tickets';").isEmpty())

        SqlAgentHqStore.open(path).use { store ->
            val profile = store.agents.get("backend")!!
            assertEquals("Backend", profile.name)
            assertEquals("backend", profile.role)
            assertTrue(store.tickets.all().isEmpty())
            store.tickets.create(TicketRow(id = "t1", title = "T", status = "todo", position = 0, createdAt = 1, updatedAt = 1))
            assertEquals("T", store.tickets.get("t1")?.title)
        }
        assertEquals(AgentHQDatabase.Schema.version.toString(), rows(path, "PRAGMA user_version;").single().single())
    }

    /**
     * The story 9.2 -> story 9.3 upgrade (contract 1.6.0, schema v7): a stamped v6 file with a ticket
     * migrates in place, keeps its tickets, and gains the executor columns, the settings table and
     * ticket_runs -- with ticket_runs cascading from a deleted ticket.
     */
    @Test
    fun `a v6 file with tickets is migrated to v7 and keeps its rows`() {
        val path = newDatabasePath()
        JdbcSqliteDriver("jdbc:sqlite:$path").use { driver ->
            AgentHQDatabase.Schema.migrate(driver, 1, 6) // 1.sqm..5.sqm: exactly the story 9.2 schema
            driver.execute(null, "PRAGMA user_version = 6;", 0)
        }
        exec(path, "INSERT INTO tickets(id, title, description, ticket_type, status, position, wont_fix, created_at, updated_at) " +
            "VALUES ('t1', 'T', 'kept', NULL, 'todo', 0, 0, 1, 1);")

        SqlAgentHqStore.open(path).use { store ->
            assertEquals("kept", store.tickets.get("t1")?.description, "the ticket survives the migration")
        }
        assertEquals(AgentHQDatabase.Schema.version.toString(), rows(path, "PRAGMA user_version;").single().single())

        val names = rows(path, "SELECT name FROM sqlite_master WHERE type = 'table'").map { it.single() }
        assertTrue(names.contains("settings"), "missing settings in $names")
        assertTrue(names.contains("ticket_runs"), "missing ticket_runs in $names")

        // The new executor columns on tickets.
        val columns = rows(path, "PRAGMA table_info(tickets)").map { it[1] }
        listOf(
            "agent_id", "provider", "model", "use_worktree", "worktree_branch",
            "include_linked_tasks_context", "queued_for", "queued_at", "session_id",
        ).forEach { column -> assertTrue(columns.contains(column), "tickets missing column '$column' in $columns") }

        // ticket_runs cascades from tickets, so deleting a ticket never orphans its runs.
        val foreignKeys = rows(path, "PRAGMA foreign_key_list(ticket_runs)")
        assertEquals(1, foreignKeys.size, "ticket_runs has one FK: $foreignKeys")
        assertEquals("tickets", foreignKeys.single()[2])
        assertEquals("CASCADE", foreignKeys.single()[6])
    }

    /**
     * The story 9.3 -> I0.2 upgrade: a stamped v7 file (the full backlog plus the executor columns,
     * settings and ticket_runs) gains the slice-A `projects` table and keeps every row it held.
     *
     * WHY A MIGRATION IS NEEDED AT ALL: `projects.sq` landed as a new .sq file, which only
     * `Schema.create()` reads. A database file written by the previous build reaches its version
     * WITHOUT a projects table, so only an explicit migration step adds it -- see 7.sqm.
     */
    @Test
    fun `a v7 file gains the projects table and keeps its backlog rows`() {
        val path = newDatabasePath()
        JdbcSqliteDriver("jdbc:sqlite:$path").use { driver ->
            AgentHQDatabase.Schema.migrate(driver, 1, 7) // 1.sqm..6.sqm: exactly the story 9.3 schema
            driver.execute(null, "PRAGMA user_version = 7;", 0)
        }
        exec(path, "INSERT INTO tickets(id, title, description, ticket_type, status, position, wont_fix, created_at, updated_at) " +
            "VALUES ('t1', 'T', 'kept', NULL, 'todo', 0, 0, 1, 1);")
        exec(path, "INSERT INTO settings(key, value, updated_at) VALUES ('k', 'v', 1);")
        assertTrue(rows(path, "SELECT name FROM sqlite_master WHERE name = 'projects';").isEmpty())

        SqlAgentHqStore.open(path).use { store ->
            assertEquals("kept", store.tickets.get("t1")?.description, "the ticket survives the migration")
        }
        assertEquals(AgentHQDatabase.Schema.version.toString(), rows(path, "PRAGMA user_version;").single().single())

        // The table the migration adds: exactly the frozen Project model -- id, name, goal and the
        // ordered story ids -- with no extra column and no missing one.
        val columns = rows(path, "PRAGMA table_info(projects)").map { it[1] }
        listOf("id", "name", "goal", "story_ids", "created_at").forEach { column ->
            assertTrue(columns.contains(column), "projects missing column '$column' in $columns")
        }

        // A row written by the new build survives a reopen through the generated queries.
        exec(path, "INSERT INTO projects(id, name, goal, story_ids, created_at) VALUES ('p1', 'P', 'g', '[\"s1\"]', 1);")
        SqlAgentHqStore.open(path).use { reopened ->
            val row = reopened.handle.database.projectsQueries.getAllProjects().executeAsList().single()
            assertEquals("[\"s1\"]", row.story_ids)
        }
    }

    /**
     * P9.5d: 21.sqm (v21 -> v22) adds `triggers.action_prompt`. A real v21 file holding a `start_next` trigger is
     * opened through the factory: the row survives with a NULL prompt, and a prompt trigger then round-trips
     * through the store.
     */
    @Test
    fun `a v21 file gains triggers action_prompt and keeps its trigger rows`() {
        val path = newDatabasePath()
        JdbcSqliteDriver("jdbc:sqlite:$path").use { driver ->
            AgentHQDatabase.Schema.migrate(driver, 1, 21)
            driver.execute(null, "PRAGMA user_version = 21;", 0)
        }
        exec(path, "INSERT INTO triggers(id, name, kind, action_kind, action_agent_id, enabled, state, coalesce_ms, ceiling_runs, ceiling_window_ms, created_at) " +
            "VALUES ('trg-v21', 'Old', 'manual', 'start_next', 'backend', 1, 'armed', 0, 6, 3600000, 1);")

        AgentHQDatabaseFactory.open(path).close()

        assertEquals(AgentHQDatabase.Schema.version.toString(), rows(path, "PRAGMA user_version;").single().single())
        assertEquals(listOf(listOf("trg-v21", "start_next", "backend", null)), rows(path, "SELECT id, action_kind, action_agent_id, action_prompt FROM triggers;"))
        SqlAgentHqStore.open(path).use { store ->
            val old = store.triggers.get("trg-v21")!!
            assertEquals(null, old.action.prompt, "a pre-1.41.0 trigger has no prompt")
            store.triggers.upsert(old.copy(action = old.action.copy(kind = "prompt", prompt = "hello")), null)
            assertEquals("hello", store.triggers.get("trg-v21")!!.action.prompt)
        }
    }

    /**
     * P9.6: 22.sqm (v22 -> v23) adds `trigger_webhooks`. A real v22 file holding a trigger is opened through
     * the factory: it must come out at v23 with the new table (trigger_id, secret) and the trigger row intact.
     */
    @Test
    fun `a v22 file gains trigger_webhooks and keeps its trigger rows`() {
        val path = newDatabasePath()
        JdbcSqliteDriver("jdbc:sqlite:$path").use { driver ->
            AgentHQDatabase.Schema.migrate(driver, 1, 22)
            driver.execute(null, "PRAGMA user_version = 22;", 0)
        }
        assertTrue(rows(path, "SELECT name FROM sqlite_master WHERE type='table' AND name='trigger_webhooks';").isEmpty(), "a v22 file has no trigger_webhooks yet")
        exec(path, "INSERT INTO triggers(id, name, kind, action_kind, action_agent_id, enabled, state, coalesce_ms, ceiling_runs, ceiling_window_ms, created_at) " +
            "VALUES ('trg-v22', 'Old', 'manual', 'start_next', 'backend', 1, 'armed', 0, 6, 3600000, 1);")

        AgentHQDatabaseFactory.open(path).close()

        assertEquals(AgentHQDatabase.Schema.version.toString(), rows(path, "PRAGMA user_version;").single().single())
        assertEquals(listOf("trigger_id", "secret"), rows(path, "PRAGMA table_info(trigger_webhooks);").map { it[1] })
        assertEquals(listOf(listOf("trg-v22", "start_next", "backend")), rows(path, "SELECT id, action_kind, action_agent_id FROM triggers;"))
        assertTrue(rows(path, "SELECT * FROM trigger_webhooks;").isEmpty())
    }

    /**
     * TRAITS-T1: 26.sqm (v26 -> v27) adds `traits`. A real v26 file holding a profile with an effort is opened through the
     * factory: it must come out at v27 with the new table, the profile intact, the version CHECK enforced, and the table usable.
     */
    @Test
    fun `a v26 file gains traits and keeps its profiles`() {
        val path = newDatabasePath()
        JdbcSqliteDriver("jdbc:sqlite:$path").use { driver ->
            AgentHQDatabase.Schema.migrate(driver, 1, 26)
            driver.execute(null, "PRAGMA user_version = 26;", 0)
        }
        assertTrue(rows(path, "SELECT name FROM sqlite_master WHERE type='table' AND name='traits';").isEmpty(), "a v26 file has no traits yet")
        exec(path, "INSERT INTO agents(id, name, role, provider, model, instructions, created_at, updated_at, project_id, effort) " +
            "VALUES ('a-v26', 'Old', 'backend', 'claude', NULL, 'be nice', 1, 2, 'p1', 'high');")

        AgentHQDatabaseFactory.open(path).close()

        assertEquals(AgentHQDatabase.Schema.version.toString(), rows(path, "PRAGMA user_version;").single().single())
        assertEquals(listOf("id", "name", "version", "body", "check_spec", "created_at", "updated_at"), rows(path, "PRAGMA table_info(traits);").map { it[1] })
        assertEquals(listOf(listOf("a-v26", "Old", "high")), rows(path, "SELECT id, name, effort FROM agents;"))
        assertTrue(rows(path, "SELECT * FROM traits;").isEmpty())
        assertFailsWith<java.sql.SQLException>("version 0 must be refused") {
            exec(path, "INSERT INTO traits(id, name, version, body, check_spec, created_at, updated_at) VALUES ('t', 'T', 0, 'b', NULL, 1, 1);")
        }
        SqlAgentHqStore.open(path).use { store ->
            val saved = store.traits.saveWith("t") { com.superiorsoftllc.agenthq.protocol.TraitView("t", "T", 1, "b", null, 5L, 5L) }
            assertEquals(saved, store.traits.get("t"))
            assertEquals(1, store.traits.all().size)
        }
    }

    /**
     * P10-K13: 35.sqm (v35 -> v36) adds `session_instances.skill_ids`. A real v35 file holding an instance is opened
     * through the factory: the old row gets `[]` (it was spawned before skills existed), its other columns are kept, and
     * the store round-trips a frozen skill list, insert-only like the rest of the row.
     */
    @Test
    fun `a v35 file gains the frozen session skill ids and keeps its instances`() {
        val path = newDatabasePath()
        JdbcSqliteDriver("jdbc:sqlite:$path").use { driver ->
            AgentHQDatabase.Schema.migrate(driver, 1, 35)
            driver.execute(null, "PRAGMA user_version = 35;", 0)
        }
        assertTrue(rows(path, "PRAGMA table_info(session_instances);").none { it[1] == "skill_ids" }, "a v35 file has no skill_ids yet")
        exec(path, "INSERT INTO session_instances(session_id, agent_id, role, instructions, traits, created_at) VALUES ('s-v35', 'a1', 'backend', 'p', '[]', 1);")

        AgentHQDatabaseFactory.open(path).close()

        assertEquals(AgentHQDatabase.Schema.version.toString(), rows(path, "PRAGMA user_version;").single().single())
        assertEquals(listOf(listOf("s-v35", "a1", "p", "[]")), rows(path, "SELECT session_id, agent_id, instructions, skill_ids FROM session_instances;"))
        SqlAgentHqStore.open(path).use { store ->
            assertEquals(emptyList(), store.sessionInstances.get("s-v35")!!.skillIds)
            val frozen = com.superiorsoftllc.agenthq.session.RoleInstance("a2", "qa", null, skillIds = listOf("s2", "s1"))
            store.sessionInstances.insert("s-new", frozen, 2L)
            assertEquals(frozen, store.sessionInstances.get("s-new"))
            store.sessionInstances.insert("s-new", frozen.copy(skillIds = listOf("late")), 3L)
            assertEquals(listOf("s2", "s1"), store.sessionInstances.get("s-new")!!.skillIds, "the first row wins: frozen at spawn")
        }
    }

    /**
     * P10-K12: 34.sqm (v34 -> v35) adds `agents.skill_ids` and `skills.attached_only`. A real v34 file holding an agent and
     * a skill is opened through the factory: it must come out at the current version with both columns at their defaults
     * (`[]`, 0) on the old rows, the flag's CHECK enforced, and the store round-tripping both.
     */
    @Test
    fun `a v34 file gains the agent skill list and the attached-only flag`() {
        val path = newDatabasePath()
        JdbcSqliteDriver("jdbc:sqlite:$path").use { driver ->
            AgentHQDatabase.Schema.migrate(driver, 1, 34)
            driver.execute(null, "PRAGMA user_version = 34;", 0)
        }
        assertTrue(rows(path, "PRAGMA table_info(agents);").none { it[1] == "skill_ids" }, "a v34 file has no agents.skill_ids yet")
        assertTrue(rows(path, "PRAGMA table_info(skills);").none { it[1] == "attached_only" }, "a v34 file has no skills.attached_only yet")
        exec(path, "INSERT INTO agents(id, name, role, created_at, updated_at, trait_ids) VALUES ('a-v34', 'Old', 'backend', 1, 2, '[\"t1\"]');")
        exec(path, "INSERT INTO skills(id, name, description, body, tags, created_at, updated_at) VALUES ('s-v34', 'Old skill', 'd', 'b', '[]', 1, 2);")

        AgentHQDatabaseFactory.open(path).close()

        assertEquals(AgentHQDatabase.Schema.version.toString(), rows(path, "PRAGMA user_version;").single().single())
        assertEquals(listOf(listOf("a-v34", "[\"t1\"]", "[]")), rows(path, "SELECT id, trait_ids, skill_ids FROM agents;"))
        assertEquals(listOf(listOf("s-v34", "0")), rows(path, "SELECT id, attached_only FROM skills;"))
        assertFailsWith<java.sql.SQLException>("attached_only is 0 or 1") {
            exec(path, "UPDATE skills SET attached_only = 2;")
        }
        SqlAgentHqStore.open(path).use { store ->
            val old = store.agents.get("a-v34")!!
            assertEquals(emptyList(), old.skillIds)
            store.agents.save(old.copy(skillIds = listOf("s-v34")), 3L)
            assertEquals(listOf("s-v34"), store.agents.get("a-v34")!!.skillIds)
            store.agents.save(old.copy(skillIds = null), 4L)
            assertEquals(listOf("s-v34"), store.agents.get("a-v34")!!.skillIds, "a null list keeps the stored one")
            assertEquals(listOf("t1"), store.agents.get("a-v34")!!.traitIds)

            assertEquals(false, store.skills.get("s-v34")!!.attachedOnly)
            assertEquals(true, store.skills.upsertByName("Old skill", "d", "b", emptyList(), 5L, attachedOnly = true) { "unused" }.attachedOnly)
            assertEquals(true, store.skills.upsertByName("Old skill", "d", "b2", emptyList(), 6L) { "unused" }.attachedOnly, "null keeps")
            assertEquals("s-v34", store.skills.getByName("Old skill")!!.id)
        }
    }

    /**
     * P10-K11: 33.sqm (v33 -> v34) adds `tickets.skill_ids`, `skills_mode` and `include_skills_context`. A real v33 file
     * holding a ticket is opened through the factory: it must come out at the current version with the three columns at
     * their defaults (`[]`, `merge`, NULL) on the old row, the mode CHECK enforced, and the store round-tripping them.
     */
    /**
     * IMG-3: 40.sqm (v40 -> v41) adds `tickets.images`. A real v40 file holding a ticket (no images column at all) is
     * opened through the factory: the old row reads back with `[]`, never NULL, and the store round-trips a list.
     */
    @Test
    fun `a v40 file gains ticket images and keeps its tickets`() {
        val path = newDatabasePath()
        JdbcSqliteDriver("jdbc:sqlite:$path").use { driver ->
            AgentHQDatabase.Schema.migrate(driver, 1, 40)
            driver.execute(null, "PRAGMA user_version = 40;", 0)
        }
        assertTrue(rows(path, "PRAGMA table_info(tickets);").none { it[1] == "images" }, "a v40 file has no images yet")
        exec(path, "INSERT INTO tickets(id, title, status, position, created_at, updated_at) VALUES ('t-v40', 'Old', 'todo', 0, 1, 2);")

        AgentHQDatabaseFactory.open(path).close()

        assertEquals(AgentHQDatabase.Schema.version.toString(), rows(path, "PRAGMA user_version;").single().single())
        assertEquals(listOf(listOf("t-v40", "Old", "[]")), rows(path, "SELECT id, title, images FROM tickets;"))
    }

    /**
     * TICKET-WEIGHT: 44.sqm (v44 -> v45) adds `tickets.weight`. A real v44 file holding a ticket is opened through the
     * factory: the old row reads back NULL (unweighted), the CHECK refuses a weight outside 1..5, and 1..5 are accepted.
     */
    @Test
    fun `a v44 file gains ticket weight and keeps its tickets unweighted`() {
        val path = newDatabasePath()
        JdbcSqliteDriver("jdbc:sqlite:$path").use { driver ->
            AgentHQDatabase.Schema.migrate(driver, 1, 44)
            driver.execute(null, "PRAGMA user_version = 44;", 0)
        }
        assertTrue(rows(path, "PRAGMA table_info(tickets);").none { it[1] == "weight" }, "a v44 file has no weight yet")
        exec(path, "INSERT INTO tickets(id, title, status, position, created_at, updated_at) VALUES ('t-v44', 'Old', 'todo', 0, 1, 2);")

        AgentHQDatabaseFactory.open(path).close()

        assertEquals(AgentHQDatabase.Schema.version.toString(), rows(path, "PRAGMA user_version;").single().single())
        assertEquals(listOf(listOf("t-v44", "Old", null)), rows(path, "SELECT id, title, weight FROM tickets;"))
        exec(path, "UPDATE tickets SET weight = 5 WHERE id = 't-v44';")
        assertEquals(listOf(listOf("5")), rows(path, "SELECT weight FROM tickets;"))
        assertFailsWith<Exception> { exec(path, "UPDATE tickets SET weight = 6 WHERE id = 't-v44';") }
    }

    /**
     * TRIGGER-NUDGE: 45.sqm (v45 -> v46) adds the trigger's project, last/next fire and pending mail. A real v45 file
     * holding a schedule trigger is opened through the factory: the old row reads back with all four NULL.
     */
    @Test
    fun `a v45 file gains the trigger nudge columns and keeps its triggers`() {
        val path = newDatabasePath()
        JdbcSqliteDriver("jdbc:sqlite:$path").use { driver ->
            AgentHQDatabase.Schema.migrate(driver, 1, 45)
            driver.execute(null, "PRAGMA user_version = 45;", 0)
        }
        assertTrue(rows(path, "PRAGMA table_info(triggers);").none { it[1] == "next_fire_at" }, "a v45 file has no next_fire_at yet")
        exec(path, "INSERT INTO triggers(id, name, kind, action_kind, enabled, state, coalesce_ms, ceiling_runs, ceiling_window_ms, created_at, interval_ms) " +
            "VALUES ('trg-v45', 'Old', 'schedule', 'start_next', 1, 'armed', 0, 6, 3600000, 1, 1800000);")

        AgentHQDatabaseFactory.open(path).close()

        assertEquals(AgentHQDatabase.Schema.version.toString(), rows(path, "PRAGMA user_version;").single().single())
        assertEquals(
            listOf(listOf("trg-v45", "1800000", null, null, null, null)),
            rows(path, "SELECT id, interval_ms, project_id, last_fired_at, next_fire_at, pending_mail_id FROM triggers;"),
        )
    }

    @Test
    fun `a v33 file gains the ticket skill columns and keeps its tickets`() {
        val path = newDatabasePath()
        JdbcSqliteDriver("jdbc:sqlite:$path").use { driver ->
            AgentHQDatabase.Schema.migrate(driver, 1, 33)
            driver.execute(null, "PRAGMA user_version = 33;", 0)
        }
        assertTrue(rows(path, "PRAGMA table_info(tickets);").none { it[1] == "skill_ids" }, "a v33 file has no skill_ids yet")
        exec(path, "INSERT INTO tickets(id, title, status, position, created_at, updated_at, include_linked_tasks_context) " +
            "VALUES ('t-v33', 'Old', 'todo', 0, 1, 2, 0);")

        AgentHQDatabaseFactory.open(path).close()

        assertEquals(AgentHQDatabase.Schema.version.toString(), rows(path, "PRAGMA user_version;").single().single())
        assertEquals(
            listOf(listOf("t-v33", "Old", "0", "[]", "merge", null)),
            rows(path, "SELECT id, title, include_linked_tasks_context, skill_ids, skills_mode, include_skills_context FROM tickets;"),
        )
        assertFailsWith<java.sql.SQLException>("an unknown skills_mode is refused") {
            exec(path, "UPDATE tickets SET skills_mode = 'other';")
        }
        assertFailsWith<java.sql.SQLException>("include_skills_context is 0, 1 or NULL") {
            exec(path, "UPDATE tickets SET include_skills_context = 2;")
        }
        SqlAgentHqStore.open(path).use { store ->
            val old = store.tickets.get("t-v33")!!
            assertEquals(Triple(emptyList<String>(), "merge", null as Boolean?), Triple(old.skillIds, old.skillsMode, old.includeSkillsContext))
            store.tickets.update(old.copy(skillIds = listOf("s-b", "s-a"), skillsMode = "replace", includeSkillsContext = false))
            val back = store.tickets.get("t-v33")!!
            assertEquals(Triple(listOf("s-b", "s-a"), "replace", false as Boolean?), Triple(back.skillIds, back.skillsMode, back.includeSkillsContext))
        }
    }

    /**
     * P10-K6: 32.sqm (v32 -> v33) adds `issues` + `issue_events`. A real v32 file holding a decision is opened through
     * the factory: it must come out at the current version with both tables (empty), their indexes and five triggers,
     * the decision untouched; the triggers must then abort a raw UPDATE / DELETE on either table and any event after a
     * fix, and the store must number from I1.
     */
    @Test
    fun `a v32 file gains the append-only issues tables and keeps its rows`() {
        val path = newDatabasePath()
        JdbcSqliteDriver("jdbc:sqlite:$path").use { driver ->
            AgentHQDatabase.Schema.migrate(driver, 1, 32)
            driver.execute(null, "PRAGMA user_version = 32;", 0)
        }
        exec(path, "INSERT INTO decisions(project_id, number, summary, created_at) VALUES ('P', 1, 'one', 1);")
        assertTrue(rows(path, "SELECT name FROM sqlite_master WHERE type = 'table' AND name = 'issues';").isEmpty())
        val before = rows(path, "SELECT * FROM decisions ORDER BY number;")

        AgentHQDatabaseFactory.open(path).close()

        assertEquals(AgentHQDatabase.Schema.version.toString(), rows(path, "PRAGMA user_version;").single().single())
        assertEquals(before, rows(path, "SELECT * FROM decisions ORDER BY number;"))
        assertEquals("0", rows(path, "SELECT COUNT(*) FROM issues;").single().single())
        assertEquals("0", rows(path, "SELECT COUNT(*) FROM issue_events;").single().single())
        assertEquals(2, declared(path, "trigger", "issues").size)
        assertEquals(3, declared(path, "trigger", "issue_events").size)
        assertTrue((shape(path, "issue_events")["indexes"] as Map<*, *>).keys.containsAll(
            listOf("index_issue_events_one_fix", "index_issue_events_on_session"),
        ))

        exec(path, "INSERT INTO issues(project_id, number, summary, location, created_at) VALUES ('P', 1, 'one', 'src/a', 1);")
        exec(path, "INSERT INTO issue_events(project_id, issue_number, seq, kind, summary, outcome, created_at) VALUES ('P', 1, 1, 'fix', 'f', NULL, 2);")
        assertFailsWith<java.sql.SQLException>("nothing after a fix") {
            exec(path, "INSERT INTO issue_events(project_id, issue_number, seq, kind, summary, outcome, created_at) VALUES ('P', 1, 2, 'attempt', 'a', 'failed', 3);")
        }
        assertFailsWith<java.sql.SQLException>("issues UPDATE aborts") { exec(path, "UPDATE issues SET summary = 'x';") }
        assertFailsWith<java.sql.SQLException>("issues DELETE aborts") { exec(path, "DELETE FROM issues;") }
        assertFailsWith<java.sql.SQLException>("events UPDATE aborts") { exec(path, "UPDATE issue_events SET summary = 'x';") }
        assertFailsWith<java.sql.SQLException>("events DELETE aborts") { exec(path, "DELETE FROM issue_events;") }
        assertEquals("1", rows(path, "SELECT COUNT(*) FROM issue_events;").single().single())
        // DP-7c: the store (numbering per project) moved to the Infantry_Notes plugin and is tested there.
    }

    /**
     * P10-K4: 31.sqm (v31 -> v32) adds `decisions`. A real v31 file holding an attention row is opened through the
     * factory: it must come out at the current version with the new table (empty), its partial unique index and its three
     * triggers, every older row untouched; the triggers must then abort a raw UPDATE / DELETE, the partial index must
     * refuse a second supersede of the same decision, and the store must number from D1.
     */
    @Test
    fun `a v31 file gains the append-only decisions table and keeps its rows`() {
        val path = newDatabasePath()
        JdbcSqliteDriver("jdbc:sqlite:$path").use { driver ->
            AgentHQDatabase.Schema.migrate(driver, 1, 31)
            driver.execute(null, "PRAGMA user_version = 31;", 0)
        }
        exec(path, "INSERT INTO attention(id, created_at, kind, outcome, detail) VALUES ('a1', 10, 'unreported', 'unreported', 'd');")
        assertTrue(rows(path, "SELECT name FROM sqlite_master WHERE type = 'table' AND name = 'decisions';").isEmpty())
        val before = rows(path, "SELECT * FROM attention ORDER BY id;")

        AgentHQDatabaseFactory.open(path).close()

        assertEquals(AgentHQDatabase.Schema.version.toString(), rows(path, "PRAGMA user_version;").single().single())
        assertEquals(before, rows(path, "SELECT * FROM attention ORDER BY id;"))
        assertEquals("0", rows(path, "SELECT COUNT(*) FROM decisions;").single().single())
        assertEquals(3, declared(path, "trigger", "decisions").size)
        assertTrue((shape(path, "decisions")["indexes"] as Map<*, *>).keys.contains("index_decisions_on_supersedes"))

        exec(path, "INSERT INTO decisions(project_id, number, summary, created_at) VALUES ('P', 1, 'one', 1);")
        exec(path, "INSERT INTO decisions(project_id, number, summary, supersedes, created_at) VALUES ('P', 2, 'two', 1, 2);")
        assertFailsWith<java.sql.SQLException>("a decision is superseded at most once") {
            exec(path, "INSERT INTO decisions(project_id, number, summary, supersedes, created_at) VALUES ('P', 3, 'three', 1, 3);")
        }
        assertFailsWith<java.sql.SQLException>("UPDATE aborts") { exec(path, "UPDATE decisions SET summary = 'x';") }
        assertFailsWith<java.sql.SQLException>("DELETE aborts") { exec(path, "DELETE FROM decisions;") }
        assertEquals("2", rows(path, "SELECT COUNT(*) FROM decisions;").single().single())
        // DP-7c: the store (numbering per project) moved to the Infantry_Notes plugin and is tested there.
    }

    /**
     * TRAITS-T6: 30.sqm (v30 -> v31) REBUILDS `attention` so its `kind` CHECK admits `unreported` (SQLite cannot alter a
     * CHECK). A real v30 file holding an open and an acked item is opened through the factory: both rows must survive
     * byte for byte, the index must be back, the old CHECK must have refused `unreported` before the migration, the new
     * one must accept it and still refuse an unknown kind, and the store must read the new kind back.
     */
    @Test
    fun `a v30 file rebuilds attention for the unreported kind and keeps its rows`() {
        val path = newDatabasePath()
        JdbcSqliteDriver("jdbc:sqlite:$path").use { driver ->
            AgentHQDatabase.Schema.migrate(driver, 1, 30)
            driver.execute(null, "PRAGMA user_version = 30;", 0)
        }
        exec(path, "INSERT INTO attention(id, created_at, kind, trigger_id, ticket_id, pod_id, session_id, outcome, detail, acked_at) " +
            "VALUES ('a1', 10, 'stalled', NULL, 't1', 'p1', 's1', 'stalled', 'no output', NULL);")
        exec(path, "INSERT INTO attention(id, created_at, kind, trigger_id, ticket_id, pod_id, session_id, outcome, detail, acked_at) " +
            "VALUES ('a2', 20, 'queue_cleared', 'tr', NULL, NULL, NULL, 'cleared', 'boot', 25);")
        assertFailsWith<java.sql.SQLException>("a v30 file refuses the new kind") {
            exec(path, "INSERT INTO attention(id, created_at, kind, outcome, detail) VALUES ('x', 1, 'unreported', 'o', 'd');")
        }
        val before = rows(path, "SELECT * FROM attention ORDER BY id;")

        AgentHQDatabaseFactory.open(path).close()

        assertEquals(AgentHQDatabase.Schema.version.toString(), rows(path, "PRAGMA user_version;").single().single())
        assertEquals(before, rows(path, "SELECT * FROM attention ORDER BY id;"), "every row survives the rebuild")
        assertEquals(2, before.size)
        assertTrue(rows(path, "SELECT name FROM sqlite_master WHERE type = 'table' AND name = 'attention_new';").isEmpty())
        assertTrue((shape(path, "attention")["indexes"] as Map<*, *>).keys.contains("index_attention_on_acknowledged_created_at"))
        exec(path, "INSERT INTO attention(id, created_at, kind, outcome, detail) VALUES ('a3', 30, 'unreported', 'unreported', 'no report');")
        assertFailsWith<java.sql.SQLException>("an unknown kind is still refused") {
            exec(path, "INSERT INTO attention(id, created_at, kind, outcome, detail) VALUES ('y', 1, 'bogus', 'o', 'd');")
        }
        SqlAgentHqStore.open(path).use { store ->
            val kinds = store.attention.all().map { it.id to it.kind.wireName }
            assertEquals(listOf("a1" to "stalled", "a2" to "queue_cleared", "a3" to "unreported"), kinds)
            assertEquals(25L, store.attention.get("a2")!!.ackedAt)
        }
    }

    /**
     * TRAITS-T4: 29.sqm (v29 -> v30) adds `session_instances`. A real v29 file holding a profile with trait additions and a
     * role trait list is opened through the factory: it must come out at the current version with the new table (empty,
     * `traits` defaulting to `[]`), the existing rows intact, and the store writing, reading (snapshots round-trip, the
     * first insert wins) and deleting a row.
     */
    @Test
    fun `a v29 file gains session_instances and keeps its profiles and role traits`() {
        val path = newDatabasePath()
        JdbcSqliteDriver("jdbc:sqlite:$path").use { driver ->
            AgentHQDatabase.Schema.migrate(driver, 1, 29)
            driver.execute(null, "PRAGMA user_version = 29;", 0)
        }
        assertTrue(rows(path, "SELECT name FROM sqlite_master WHERE type = 'table' AND name = 'session_instances';").isEmpty(), "a v29 file has no session_instances yet")
        exec(path, "INSERT INTO agents(id, name, role, provider, model, instructions, created_at, updated_at, project_id, effort, trait_ids) " +
            "VALUES ('a-v29', 'Old', 'backend', 'claude', NULL, 'be nice', 1, 2, 'p1', 'high', '[\"tdd\"]');")
        exec(path, "INSERT INTO role_traits(role_id, trait_ids, updated_at) VALUES ('backend', '[\"report-back\"]', 3);")

        AgentHQDatabaseFactory.open(path).close()

        assertEquals(AgentHQDatabase.Schema.version.toString(), rows(path, "PRAGMA user_version;").single().single())
        assertEquals(listOf(listOf("a-v29", "[\"tdd\"]")), rows(path, "SELECT id, trait_ids FROM agents;"))
        assertEquals(listOf(listOf("backend", "[\"report-back\"]")), rows(path, "SELECT role_id, trait_ids FROM role_traits;"))
        assertEquals(emptyList(), rows(path, "SELECT * FROM session_instances;"))
        exec(path, "INSERT INTO session_instances(session_id, created_at) VALUES ('legacy', 1);")
        assertEquals(listOf(listOf("[]")), rows(path, "SELECT traits FROM session_instances WHERE session_id = 'legacy';"))
        SqlAgentHqStore.open(path).use { store ->
            val snap = com.superiorsoftllc.agenthq.session.TraitSnapshot(
                "report-back", 3, "REPORT BACK", "mail the pm",
                com.superiorsoftllc.agenthq.protocol.TraitCheck("report", "pm", listOf("commit", "tests")),
            )
            val instance = com.superiorsoftllc.agenthq.session.RoleInstance("a-v29", "backend", "be nice", listOf(snap))
            store.sessionInstances.insert("s1", instance, 10L)
            assertEquals(instance, store.sessionInstances.get("s1"))
            store.sessionInstances.insert("s1", instance.copy(instructions = "changed", traits = emptyList()), 11L)
            assertEquals(instance, store.sessionInstances.get("s1"), "insert-only: the first row wins")
            assertEquals(com.superiorsoftllc.agenthq.session.RoleInstance(null, null, null, emptyList()), store.sessionInstances.get("legacy"))
            store.sessionInstances.delete("s1")
            assertNull(store.sessionInstances.get("s1"))
        }
    }

    /**
     * TRAITS-T3: 28.sqm (v28 -> v29) adds `agents.trait_ids`. A real v28 file holding a profile and a role trait list is
     * opened through the factory: it must come out at the current version with the column last, the profile intact with
     * `trait_ids = '[]'`, the role list intact, and the store reading and writing the column (null keeps, [] clears).
     */
    @Test
    fun `a v28 file gains agents trait_ids and keeps its profiles`() {
        val path = newDatabasePath()
        JdbcSqliteDriver("jdbc:sqlite:$path").use { driver ->
            AgentHQDatabase.Schema.migrate(driver, 1, 28)
            driver.execute(null, "PRAGMA user_version = 28;", 0)
        }
        assertTrue(rows(path, "PRAGMA table_info(agents);").none { it[1] == "trait_ids" }, "a v28 file has no agents.trait_ids yet")
        exec(path, "INSERT INTO agents(id, name, role, provider, model, instructions, created_at, updated_at, project_id, effort) " +
            "VALUES ('a-v28', 'Old', 'backend', 'claude', NULL, 'be nice', 1, 2, 'p1', 'high');")
        exec(path, "INSERT INTO role_traits(role_id, trait_ids, updated_at) VALUES ('backend', '[\"report-back\"]', 3);")

        AgentHQDatabaseFactory.open(path).close()

        assertEquals(AgentHQDatabase.Schema.version.toString(), rows(path, "PRAGMA user_version;").single().single())
        // trait_ids is 28.sqm's ADD COLUMN; P10-K12's 34.sqm appends skill_ids after it.
        assertEquals(listOf("trait_ids", "skill_ids"), rows(path, "PRAGMA table_info(agents);").map { it[1] }.takeLast(2))
        assertEquals(listOf(listOf("a-v28", "Old", "high", "[]")), rows(path, "SELECT id, name, effort, trait_ids FROM agents;"))
        assertEquals(listOf(listOf("backend", "[\"report-back\"]")), rows(path, "SELECT role_id, trait_ids FROM role_traits;"))
        SqlAgentHqStore.open(path).use { store ->
            val old = store.agents.get("a-v28")!!
            assertEquals(emptyList(), old.traitIds)
            store.agents.save(old.copy(traitIds = listOf("tdd", "report-back")), 10L)
            assertEquals(listOf("tdd", "report-back"), store.agents.get("a-v28")!!.traitIds)
            store.agents.save(old.copy(name = "Renamed", traitIds = null), 11L)
            assertEquals(listOf("tdd", "report-back"), store.agents.get("a-v28")!!.traitIds, "null keeps the stored list")
            store.agents.save(old.copy(traitIds = emptyList()), 12L)
            assertEquals(emptyList(), store.agents.get("a-v28")!!.traitIds)
        }
    }

    /**
     * TRAITS-T2: 27.sqm (v27 -> v28) adds `role_traits`. A real v27 file holding a trait and a role binding is opened through
     * the factory: it must come out at the current version with the new table (empty, `trait_ids` defaulting to `[]`), the
     * existing rows intact, and the table usable through the store.
     */
    @Test
    fun `a v27 file gains role_traits and keeps its traits`() {
        val path = newDatabasePath()
        JdbcSqliteDriver("jdbc:sqlite:$path").use { driver ->
            AgentHQDatabase.Schema.migrate(driver, 1, 27)
            driver.execute(null, "PRAGMA user_version = 27;", 0)
        }
        assertTrue(rows(path, "SELECT name FROM sqlite_master WHERE type='table' AND name='role_traits';").isEmpty(), "a v27 file has no role_traits yet")
        exec(path, "INSERT INTO traits(id, name, version, body, check_spec, created_at, updated_at) VALUES ('report-back', 'RB', 2, 'mail', NULL, 1, 2);")
        exec(path, "INSERT INTO role_bindings(role_id, provider, model, effort, updated_at) VALUES ('backend', 'claude', NULL, 'high', 3);")

        AgentHQDatabaseFactory.open(path).close()

        assertEquals(AgentHQDatabase.Schema.version.toString(), rows(path, "PRAGMA user_version;").single().single())
        assertEquals(listOf("role_id", "trait_ids", "updated_at"), rows(path, "PRAGMA table_info(role_traits);").map { it[1] })
        assertEquals(listOf(listOf("report-back", "2")), rows(path, "SELECT id, version FROM traits;"))
        assertEquals(listOf(listOf("backend", "high")), rows(path, "SELECT role_id, effort FROM role_bindings;"))
        exec(path, "INSERT INTO role_traits(role_id, updated_at) VALUES ('qa', 4);")
        assertEquals(listOf(listOf("qa", "[]")), rows(path, "SELECT role_id, trait_ids FROM role_traits;"), "trait_ids defaults to []")
        SqlAgentHqStore.open(path).use { store ->
            val set = store.roleTraits.set("backend", listOf("report-back"), 9L)
            assertEquals(com.superiorsoftllc.agenthq.protocol.RoleTraits("backend", listOf("report-back"), 9L), set)
            assertEquals(listOf("backend", "qa"), store.roleTraits.all().map { it.roleId })
            assertEquals(emptyList(), store.roleTraits.get("qa")!!.traitIds)
        }
    }

    /**
     * Role-first S2: 25.sqm (v25 -> v26) adds `agents.effort`. A real v25 file holding a profile is opened through the factory:
     * it must come out at v26 with the column, the profile intact with effort null, and the column writable.
     */
    @Test
    fun `a v25 file gains agents effort and keeps its profiles with effort null`() {
        val path = newDatabasePath()
        JdbcSqliteDriver("jdbc:sqlite:$path").use { driver ->
            AgentHQDatabase.Schema.migrate(driver, 1, 25)
            driver.execute(null, "PRAGMA user_version = 25;", 0)
        }
        assertTrue(rows(path, "PRAGMA table_info(agents);").none { it[1] == "effort" }, "a v25 file has no agents.effort yet")
        exec(path, "INSERT INTO agents(id, name, role, provider, model, instructions, created_at, updated_at, project_id) " +
            "VALUES ('a-v25', 'Old', 'backend', 'claude', NULL, 'be nice', 1, 2, 'p1');")

        AgentHQDatabaseFactory.open(path).close()

        assertEquals(AgentHQDatabase.Schema.version.toString(), rows(path, "PRAGMA user_version;").single().single())
        assertTrue(rows(path, "PRAGMA table_info(agents);").any { it[1] == "effort" }, "agents.effort exists")
        assertEquals(listOf(listOf("a-v25", "Old", "backend", "be nice", "p1", null)),
            rows(path, "SELECT id, name, role, instructions, project_id, effort FROM agents;"))
        SqlAgentHqStore.open(path).use { store ->
            val old = store.agents.get("a-v25")!!
            assertEquals(null, old.effort)
            store.agents.save(old.copy(effort = "high"), 9L)
            assertEquals("high", store.agents.get("a-v25")!!.effort)
        }
    }

    /**
     * P10-B: 24.sqm (v24 -> v25) adds `skills`. A real v24 file holding a prompt is opened through the factory:
     * it must come out at v25 with the new table, the prompt row intact, and the table usable.
     */
    @Test
    fun `a v24 file gains skills and keeps its prompt rows`() {
        val path = newDatabasePath()
        JdbcSqliteDriver("jdbc:sqlite:$path").use { driver ->
            AgentHQDatabase.Schema.migrate(driver, 1, 24)
            driver.execute(null, "PRAGMA user_version = 24;", 0)
        }
        assertTrue(rows(path, "SELECT name FROM sqlite_master WHERE type='table' AND name='skills';").isEmpty(), "a v24 file has no skills yet")
        exec(path, "INSERT INTO prompts(id, name, body, tags, created_at, updated_at) VALUES ('p-v24', 'Old', 'old body', '[\"x\"]', 1, 2);")

        AgentHQDatabaseFactory.open(path).close()

        assertEquals(AgentHQDatabase.Schema.version.toString(), rows(path, "PRAGMA user_version;").single().single())
        assertEquals(listOf("id", "name", "description", "body", "tags", "created_at", "updated_at", "attached_only"), rows(path, "PRAGMA table_info(skills);").map { it[1] })
        assertEquals(listOf(listOf("p-v24", "Old", "old body")), rows(path, "SELECT id, name, body FROM prompts;"))
        assertTrue(rows(path, "SELECT * FROM skills;").isEmpty())
        SqlAgentHqStore.open(path).use { store ->
            val saved = store.skills.upsertByName("s", "d", "b", listOf("x"), 5L) { "id-1" }
            assertEquals("id-1", saved.id)
            assertEquals(listOf("x"), store.skills.get("id-1")!!.tags)
            assertEquals("d", store.skills.get("id-1")!!.description)
        }
    }

    /**
     * P10-A: 23.sqm (v23 -> v24) adds `prompts`. A real v23 file holding a trigger is opened through the factory:
     * it must come out at v24 with the new table, the trigger row intact, and the table usable.
     */
    @Test
    fun `a v23 file gains prompts and keeps its trigger rows`() {
        val path = newDatabasePath()
        JdbcSqliteDriver("jdbc:sqlite:$path").use { driver ->
            AgentHQDatabase.Schema.migrate(driver, 1, 23)
            driver.execute(null, "PRAGMA user_version = 23;", 0)
        }
        assertTrue(rows(path, "SELECT name FROM sqlite_master WHERE type='table' AND name='prompts';").isEmpty(), "a v23 file has no prompts yet")
        exec(path, "INSERT INTO triggers(id, name, kind, action_kind, action_agent_id, enabled, state, coalesce_ms, ceiling_runs, ceiling_window_ms, created_at) " +
            "VALUES ('trg-v23', 'Old', 'manual', 'start_next', 'backend', 1, 'armed', 0, 6, 3600000, 1);")

        AgentHQDatabaseFactory.open(path).close()

        assertEquals(AgentHQDatabase.Schema.version.toString(), rows(path, "PRAGMA user_version;").single().single())
        assertEquals(listOf("id", "name", "body", "tags", "created_at", "updated_at"), rows(path, "PRAGMA table_info(prompts);").map { it[1] })
        assertEquals(listOf(listOf("trg-v23", "start_next", "backend")), rows(path, "SELECT id, action_kind, action_agent_id FROM triggers;"))
        assertTrue(rows(path, "SELECT * FROM prompts;").isEmpty())
        SqlAgentHqStore.open(path).use { store ->
            val saved = store.prompts.upsertByName("p", "b", listOf("x"), 5L) { "id-1" }
            assertEquals("id-1", saved.id)
            assertEquals(listOf("x"), store.prompts.get("id-1")!!.tags)
        }
    }

    /**
     * ROLE-1a: 20.sqm (v20 -> v21) adds `role_bindings`. A real v20 file holding an agent is opened through
     * the factory: it must come out at v21 with an empty `role_bindings`, the agent row untouched, and the
     * new table usable through the store.
     */
    @Test
    fun `a v20 file gains role_bindings and keeps its rows`() {
        val path = newDatabasePath()
        JdbcSqliteDriver("jdbc:sqlite:$path").use { driver ->
            AgentHQDatabase.Schema.migrate(driver, 1, 20)
            driver.execute(null, "PRAGMA user_version = 20;", 0)
        }
        exec(path, "INSERT INTO agents(id, name, role, created_at, updated_at) VALUES ('ag-v20', 'Old', 'backend', 1, 1);")

        AgentHQDatabaseFactory.open(path).close()

        assertEquals(AgentHQDatabase.Schema.version.toString(), rows(path, "PRAGMA user_version;").single().single(), "a v20 file migrates to the current schema")
        assertEquals(listOf(listOf("0")), rows(path, "SELECT COUNT(*) FROM role_bindings;"), "role_bindings starts empty")
        assertEquals(listOf(listOf("ag-v20", "Old", "backend")), rows(path, "SELECT id, name, role FROM agents;"))
        SqlAgentHqStore.open(path).use { store ->
            store.roleBindings.set(com.superiorsoftllc.agenthq.role.RoleBinding("backend", "claude", "opus"), 5)
            assertEquals("opus", store.roleBindings.get("backend")!!.model)
        }
    }

    /**
     * CP-3: 19.sqm backfills `agents.project_id`. A v19 file holding two projects (the oldest is the
     * default) and three agents: one linked by a ticket to the NEWER project, one with no ticket, and one
     * whose latest ticket wins over an older one. No row may be lost.
     */
    @Test
    fun `a v19 file backfills agents project_id from tickets then the default project and loses no row`() {
        val path = newDatabasePath()
        JdbcSqliteDriver("jdbc:sqlite:$path").use { driver ->
            AgentHQDatabase.Schema.migrate(driver, 1, 19)
            driver.execute(null, "PRAGMA user_version = 19;", 0)
        }
        exec(path, "INSERT INTO projects(id, name, goal, story_ids, created_at) VALUES ('pA', 'Oldest', '', '[]', 1);")
        exec(path, "INSERT INTO projects(id, name, goal, story_ids, created_at) VALUES ('pB', 'Newer', '', '[]', 5);")
        for (a in listOf("ag-ticket", "ag-none", "ag-latest")) {
            exec(path, "INSERT INTO agents(id, name, role, created_at, updated_at) VALUES ('$a', '$a', 'backend', 1, 1);")
        }
        fun ticket(id: String, agent: String, project: String, at: Int) = exec(
            path,
            "INSERT INTO tickets(id, title, description, ticket_type, status, position, wont_fix, created_at, updated_at, agent_id, project_id) " +
                "VALUES ('$id', 'T', '', NULL, 'todo', 0, 0, $at, $at, '$agent', '$project');",
        )
        ticket("t1", "ag-ticket", "pB", 10)
        ticket("t2", "ag-latest", "pB", 5)
        ticket("t3", "ag-latest", "pA", 9)   // the latest ticket of ag-latest is in pA

        AgentHQDatabaseFactory.open(path).close()

        assertEquals(AgentHQDatabase.Schema.version.toString(), rows(path, "PRAGMA user_version;").single().single())
        val byAgent = rows(path, "SELECT id, project_id FROM agents ORDER BY id;").associate { it[0] to it[1] }
        assertEquals(3, byAgent.size, "no agent row is lost: $byAgent")
        assertEquals("pB", byAgent["ag-ticket"], "ticket-linked agent gets that ticket's project")
        assertEquals("pA", byAgent["ag-none"], "ticketless agent gets the default (oldest) project")
        assertEquals("pA", byAgent["ag-latest"], "the agent's LATEST ticket decides")
        assertEquals(listOf(listOf("3")), rows(path, "SELECT COUNT(*) FROM tickets;"), "tickets untouched")
    }

    /**
     * The story PW-3 upgrade: 13.sqm (v13 -> v14) adds the `workspaces` table. A real v13 file
     * (1.sqm to 12.sqm, stamped 13, holding one project and one trigger) is opened through the
     * factory: it must come out at v14 with an empty `workspaces` table, and the rows it already
     * held must be unchanged.
     */
    @Test
    fun `a v13 file is migrated to v14 and keeps its rows`() {
        val path = newDatabasePath()
        JdbcSqliteDriver("jdbc:sqlite:$path").use { driver ->
            AgentHQDatabase.Schema.migrate(driver, 1, 13) // 1.sqm..12.sqm: exactly the v13 schema
            driver.execute(null, "PRAGMA user_version = 13;", 0)
        }
        exec(path, "INSERT INTO projects(id, name, goal, story_ids, created_at) " +
            "VALUES ('proj-v13', 'Social Machine', 'ship it', '[\"s1\"]', 7);")
        exec(path, "INSERT INTO triggers(id, name, kind, action_kind, enabled, state, coalesce_ms, " +
            "ceiling_runs, ceiling_window_ms, created_at, interval_ms) " +
            "VALUES ('trig-v13', 'nightly', 'schedule', 'run', 1, 'armed', 0, 0, 0, 1, 60000);")

        AgentHQDatabaseFactory.open(path).close()

        assertEquals(AgentHQDatabase.Schema.version.toString(), rows(path, "PRAGMA user_version;").single().single())
        val names = rows(path, "SELECT name FROM sqlite_master WHERE type = 'table'").map { it.single() }
        assertTrue(names.contains("workspaces"), "missing workspaces in $names")
        assertEquals(listOf(listOf("0")), rows(path, "SELECT COUNT(*) FROM workspaces;"), "workspaces starts empty")
        assertEquals(
            listOf(listOf("proj-v13", "Social Machine", "ship it", "[\"s1\"]", "7")),
            rows(path, "SELECT id, name, goal, story_ids, created_at FROM projects;"),
            "the project row survives the migration unchanged",
        )
        assertEquals(
            listOf(listOf("trig-v13", "nightly", "60000")),
            rows(path, "SELECT id, name, interval_ms FROM triggers;"),
            "the trigger row survives the migration unchanged",
        )
    }

    /**
     * CP-2: 18.sqm (v18 -> v19) adds `projects.directory`. A real v18 file (1.sqm to 17.sqm, stamped 18,
     * holding one project) is opened through the factory: it must come out at v19, the project row must be
     * unchanged, and its directory must read back as the empty string (the column's default) -- not NULL,
     * which the generated query would reject.
     */
    @Test
    fun `a v18 file gains projects directory and keeps its project row`() {
        val path = newDatabasePath()
        JdbcSqliteDriver("jdbc:sqlite:$path").use { driver ->
            AgentHQDatabase.Schema.migrate(driver, 1, 18) // 1.sqm..17.sqm: exactly the v18 schema
            driver.execute(null, "PRAGMA user_version = 18;", 0)
        }
        exec(path, "INSERT INTO projects(id, name, goal, story_ids, created_at) " +
            "VALUES ('proj-v18', 'Social Machine', 'ship it', '[]', 9);")
        assertTrue(
            rows(path, "PRAGMA table_info(projects)").none { it[1] == "directory" },
            "a v18 file has no directory column yet",
        )

        AgentHQDatabaseFactory.open(path).close()

        assertEquals(AgentHQDatabase.Schema.version.toString(), rows(path, "PRAGMA user_version;").single().single())
        // Raw SQL on purpose: the assertion must read the column from the migrated FILE, not from a
        // generated query that would pass on a fresh create and say nothing about the upgrade.
        assertEquals(
            listOf(listOf("proj-v18", "Social Machine", "ship it", "")),
            rows(path, "SELECT id, name, goal, directory FROM projects;"),
            "the project row survives and its directory is the empty default, never NULL",
        )
    }

    /**
     * CONFIG-3: the first manifest a daemon receives (stored in the reserved node_config row `@manifest`, no new table)
     * deletes each seed row equal to the declared default and turns the other seed rows (somebody's value) into user
     * rows. User rows are not touched, and the manifest row never shows in the node_config list.
     */
    @Test
    fun `the first manifest migrates the seed rows of an existing node_config`() {
        val path = newDatabasePath()
        AgentHQDatabaseFactory.open(path).close()
        exec(path, "INSERT INTO node_config VALUES ('infantry.shell.theme', 'accent', '\"RED\"', 'seed', 1);")
        exec(path, "INSERT INTO node_config VALUES ('infantry.shell.theme', 'dark', 'false', 'seed', 1);")
        exec(path, "INSERT INTO node_config VALUES ('infantry.shell.board', 'title', '\"Mine\"', 'user', 1);")

        AgentHQDatabaseFactory.open(path).use { h ->
            val manifest = """[{"declaration":{"fields":[{"default":"RED","key":"accent"},{"default":true,"key":"dark"}],"node":"infantry.shell.theme"},"module":"Infantry_Appearance"}]"""
            val nodeConfig = com.superiorsoftllc.agenthq.daemon.NodeConfigStore(h.database)
            com.superiorsoftllc.agenthq.daemon.ConfigManifestStore(h.database, nodeConfig)
                .put(com.superiorsoftllc.agenthq.plugin.api.ConfigManifest("desktop", "h1", manifest))
            assertEquals(2, nodeConfig.all().size, "the @manifest row is not listed")
        }
        assertEquals(
            listOf(listOf("@manifest", "desktop", "manifest"), listOf("infantry.shell.board", "title", "user"), listOf("infantry.shell.theme", "dark", "user")),
            rows(path, "SELECT node_id, key, set_by FROM node_config ORDER BY node_id, key;"),
            "the seed row equal to the default is gone, the other one is the user's now, the user row is untouched",
        )
    }
}
