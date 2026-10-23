package com.superiorsoftllc.agenthq.db

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import java.nio.file.Files
import java.nio.file.Paths
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Persistence story 1/3 -- the proof that the `runs` table round-trips through SQLite, and that a
 * row outlives the connection that wrote it.
 *
 * These are the two properties the whole ticket rests on, and they are deliberately BROKEN APART
 * into separate connections, because the interesting failure mode is not "can I write and read the
 * same object back" -- that trivially passes -- it is "does the row actually reach the FILE". A
 * wiring bug that never reaches disk (no commit, a transaction left open, an in-memory database)
 * passes the first half and fails the second, so the reopen is the real assertion.
 *
 * Every test works on a throwaway temp file created with [Files.createTempFile], so nothing here
 * touches the daemon's real `agenthq.db`, and tests stay order-independent.
 */
class RunsDatabaseTest {

    private fun newDatabasePath(): String {
        val file = Files.createTempFile("agenthq-runs-test-", ".db")
        file.toFile().delete() // let SQLite create it, so open() takes the "new file" path
        return file.toAbsolutePath().toString()
    }

    /** A fully-populated, closed-out run row -- the "after" of the round-trip in the first test. */
    private fun finishedRun() = Runs(
        id = "run-1",
        session_id = "session-1",
        provider = "opencode",
        working_dir = """C:\work\story-1""",
        prompt = "wire SQLDelight into :agentHq",
        state = "EXITED",
        started_at = 1_000L,
        ended_at = 2_000L,
        exit_code = 0L,
        stdout_lines = 12L,
        stderr_lines = 1L,
        provider_session_id = "ses_abc123",
        error = null,
        pod_id = null,
    )

    @Test
    fun `insert select update round-trip and reopen sees the row`() {
        val path = newDatabasePath()

        // ---- Open 1: insert a STARTING run, finish it, read it back twice. ----
        AgentHQDatabaseFactory.open(path).use { handle ->
            val queries = handle.database.runsQueries

            // A run begins the way production begins it: spawned, not yet ended.
            queries.insertRun(
                id = "run-1",
                session_id = "session-1",
                provider = "opencode",
                working_dir = """C:\work\story-1""",
                prompt = "wire SQLDelight into :agentHq",
                state = "STARTING",
                started_at = 1_000L,
                ended_at = null,
                exit_code = null,
                stdout_lines = 0L,
                stderr_lines = 0L,
                provider_session_id = null,
                error = null,
                pod_id = null,
            )

            val started = queries.getRunById("run-1").executeAsOne()
            assertEquals("STARTING", started.state)
            // Still in flight: no outcome yet. This is exactly the state a crashed daemon leaves.
            assertNull(started.ended_at)
            assertNull(started.exit_code)
            assertNull(started.provider_session_id)

            // ---- the UPDATE half of the round-trip ----
            queries.finishRun(
                state = "EXITED",
                ended_at = 2_000L,
                exit_code = 0L,
                stdout_lines = 12L,
                stderr_lines = 1L,
                provider_session_id = "ses_abc123",
                error = null,
                id = "run-1",
            )

            val finished = queries.getRunById("run-1").executeAsOne()
            assertEquals("EXITED", finished.state)
            assertEquals(2_000L, finished.ended_at)
            assertEquals(0L, finished.exit_code)
            assertEquals(12L, finished.stdout_lines)
            assertEquals(1L, finished.stderr_lines)
            assertEquals("ses_abc123", finished.provider_session_id)
        }

        // ---- Open 2: a FRESH connection over the SAME FILE. The row must survive. ----
        // This is the crash-recovery case -- the daemon died, the file stayed. Anything that only
        // ever lived in open-1's memory is gone by definition, so if this read works, it reached
        // disk, and the whole schema round-trip is real.
        AgentHQDatabaseFactory.open(path).use { handle ->
            assertEquals(finishedRun(), handle.database.runsQueries.getRunById("run-1").executeAsOne())
        }

        // ---- AND the file exists on disk, independent of the handle that wrote it. ----
        assertTrue(Files.exists(Paths.get(path)))
    }

    @Test
    fun `reopening does not duplicate or wipe the schema`() {
        val path = newDatabasePath()

        AgentHQDatabaseFactory.open(path).use { h ->
            h.database.runsQueries.insertRun(
                id = "run-1", session_id = "session-1", provider = "opencode",
                working_dir = """C:\w""", prompt = "p", state = "EXITED",
                started_at = 1L, ended_at = 2L, exit_code = 0L,
                stdout_lines = 1L, stderr_lines = 0L, provider_session_id = null, error = null,
                pod_id = null,
            )
            h.database.runsQueries.insertRun(
                id = "run-2", session_id = "session-1", provider = "claude",
                working_dir = """C:\w""", prompt = "p", state = "FAILED",
                started_at = 3L, ended_at = 4L, exit_code = 1L,
                stdout_lines = 0L, stderr_lines = 3L, provider_session_id = null, error = "could not start",
                pod_id = null,
            )
        }

        // Opening an existing file a second time must NOT run CREATE TABLE again -- which would
        // either throw, or (worse) leave the file in a half-rebuilt state.
        AgentHQDatabaseFactory.open(path).use { h ->
            val rows = h.database.runsQueries.getRunsBySessionId("session-1").executeAsList()
            assertEquals(2, rows.size)
            assertEquals("run-1", rows[0].id)
            assertEquals("run-2", rows[1].id)
        }

        // ...and a third time, after the WAL sidecar files have been created and checkpointed.
        AgentHQDatabaseFactory.open(path).use { h ->
            assertEquals(
                2,
                h.database.runsQueries.getRunsBySessionId("session-1").executeAsList().size,
            )
        }
    }

    @Test
    fun `unfinished runs are the reconciliation set and can be closed out`() {
        val path = newDatabasePath()

        AgentHQDatabaseFactory.open(path).use { h ->
            val q = h.database.runsQueries
            q.insertRun("run-1", "session-1", "opencode", """C:\w""", "p", "BUSY",
                1L, null, null, 0L, 0L, null, null, null)
            q.insertRun("run-2", "session-1", "opencode", """C:\w""", "p", "EXITED",
                2L, 3L, 0L, 0L, 0L, null, null, null)
            q.insertRun("run-3", "session-2", "claude", """C:\w""", "p", "STARTING",
                4L, null, null, 0L, 0L, null, null, null)

            // Only the never-ended rows are candidates for reconciliation. A run that already
            // exited must never be reopened by a restart.
            val unfinished = q.getUnfinishedRuns().executeAsList()
            assertEquals(2, unfinished.size)

            // Mark them ended without INVENTING an exit code: the process is gone, and claiming an
            // exit code for it would be a stored lie the daemon could not distinguish later.
            q.markUnfinishedRunsEnded(ended_at = 9_999L)

            assertEquals(0, q.getUnfinishedRuns().executeAsList().size)
            val r1 = q.getRunById("run-1").executeAsOne()
            assertEquals(9_999L, r1.ended_at)
            // State is deliberately NOT rewritten here -- the caller decides what a reconciled run
            // means; this query only closes the interval.
            assertEquals("BUSY", r1.state)
            assertNull(r1.exit_code)
            // run-2 already ended at 3, so reconciliation must leave its own ended_at alone.
            assertEquals(3L, q.getRunById("run-2").executeAsOne().ended_at)
        }
    }

    @Test
    fun `delete by id and by session`() {
        val path = newDatabasePath()

        AgentHQDatabaseFactory.open(path).use { h ->
            val q = h.database.runsQueries
            q.insertRun("run-1", "session-1", "opencode", """C:\w""", "p", "EXITED",
                1L, 2L, 0L, 0L, 0L, null, null, null)
            q.insertRun("run-2", "session-1", "opencode", """C:\w""", "p", "EXITED",
                3L, 4L, 0L, 0L, 0L, null, null, null)
            q.insertRun("run-3", "session-2", "claude", """C:\w""", "p", "EXITED",
                5L, 6L, 0L, 0L, 0L, null, null, null)

            q.deleteRun("run-1")
            assertNull(q.getRunById("run-1").executeAsOneOrNull())
            assertEquals(1, q.getRunsBySessionId("session-1").executeAsList().size)

            q.deleteRunsBySessionId("session-1")
            assertEquals(0, q.getRunsBySessionId("session-1").executeAsList().size)

            // session-2 untouched: a session-scoped delete must not leak into another session.
            assertEquals("run-3", q.getRunById("run-3").executeAsOne().id)
        }
    }

    @Test
    fun `failure columns and run id semantics survive the round-trip`() {
        val path = newDatabasePath()

        AgentHQDatabaseFactory.open(path).use { h ->
            val q = h.database.runsQueries
            q.insertRun(
                id = "run-1", session_id = "session-1", provider = "claude",
                working_dir = """C:\w""", prompt = "do a thing", state = "FAILED",
                started_at = 1_000L, ended_at = 1_500L, exit_code = 137L,
                stdout_lines = 0L, stderr_lines = 4L, provider_session_id = null,
                error = "process could not be started",
                pod_id = null,
            )

            val row = q.getRunById("run-1").executeAsOne()
            assertEquals("claude", row.provider)   // WHICH cli failed, without a second lookup
            assertEquals("FAILED", row.state)
            assertEquals(137L, row.exit_code)
            assertEquals("process could not be started", row.error)
            // A run that never got a provider handle stores NULL, not an empty string.
            assertNull(row.provider_session_id)

            // Primary key: re-inserting the same id OVERWRITES in place instead of accumulating a duplicate,
            // which is what makes a retried write after a crash idempotent.
            q.insertRun(
                id = "run-1", session_id = "session-1", provider = "claude",
                working_dir = """C:\w""", prompt = "second attempt", state = "EXITED",
                started_at = 1_000L, ended_at = 1_600L, exit_code = 0L,
                stdout_lines = 2L, stderr_lines = 0L, provider_session_id = null, error = null,
                pod_id = null,
            )
            val all = q.getRunsBySessionId("session-1").executeAsList()
            assertEquals(1, all.size)
            assertEquals("second attempt", all[0].prompt)
        }
    }

    /**
     * The index on `session_id` is a performance claim in the .sq file. This asserts SQLite actually
     * holds it, so the common "every run of a session" read is not a full table scan -- verified
     * through SQLite's own catalog rather than by trusting the schema file.
     */
    @Test
    fun `index on session id actually exists`() {
        val path = newDatabasePath()
        AgentHQDatabaseFactory.open(path).close()

        val indexCount = JdbcSqliteDriver("jdbc:sqlite:$path").use { driver ->
            driver.executeQuery(
                identifier = null,
                sql = "SELECT COUNT(*) FROM sqlite_master WHERE type = 'index' " +
                    "AND name = 'index_runs_on_session_id';",
                mapper = { cursor ->
                    val hasRow = cursor.next().value
                    QueryResult.Value(if (hasRow) cursor.getLong(0) ?: 0L else 0L)
                },
                parameters = 0,
            ).value
        }
        assertEquals(1L, indexCount)
    }

    /**
     * The durability pragmas, read back OUT of SQLite rather than trusting the factory.
     *
     * Both are easy to claim in code and easy to get wrong in ways that only show up later:
     * `journal_mode` asked before a connection exists, or asked but immediately overwritten, leaves
     * the database in the default rollback-journal mode; `busy_timeout` silently ignored is a
     * configuration document that does nothing. So the assertion is on the effective value.
     *
     * `busy_timeout` is asserted to the EXACT configured value rather than merely `> 0`, and that is
     * the one thing this test taught while it was being written: xerial applies a DEFAULT
     * `busy_timeout` of 3000 ms at connect time, so a `> 0` assertion passed even while the pragma
     * was not landing at all. An assertion that cannot fail proves nothing, so this one is pinned to
     * the value the factory configures.
     */
    @Test
    fun `pragmas are effective - wal mode on and busy timeout set`() {
        val path = newDatabasePath()
        AgentHQDatabaseFactory.open(path).use { handle ->
            val driver = handle.driver
            fun pragmaString(sql: String): String =
                driver.executeQuery(
                    null, sql,
                    { cursor ->
                        val hasRow = cursor.next().value
                        QueryResult.Value(if (hasRow) cursor.getString(0) else null)
                    },
                    0,
                ).value.orEmpty()

            // WAL is persistent in the file header, so reading it back proves the factory put the
            // database in WAL mode and that it survived to a fresh connection.
            assertEquals("wal", pragmaString("PRAGMA journal_mode;").lowercase())

            fun pragmaLong(sql: String): Long =
                driver.executeQuery(
                    null, sql,
                    { cursor ->
                        val hasRow = cursor.next().value
                        QueryResult.Value(if (hasRow) cursor.getLong(0) ?: 0L else 0L)
                    },
                    0,
                ).value

            assertEquals(5_000L, pragmaLong("PRAGMA busy_timeout;"))
            // Synchronous=NORMAL is the safe pairing for WAL (FULL would be wasted fsyncs); assert
            // it so a later change cannot quietly drop durability while WAL stays on.
            assertEquals(1L, pragmaLong("PRAGMA synchronous;"))
        }
    }
}
