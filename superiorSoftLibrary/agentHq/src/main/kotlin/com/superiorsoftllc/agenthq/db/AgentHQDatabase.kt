package com.superiorsoftllc.agenthq.db

import app.cash.sqldelight.adapter.primitive.IntColumnAdapter
import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.superiorsoftllc.agenthq.role.RoleIdMigration
import java.io.Closeable

/**
 * Persistence story 1/3 -- the ONE place that opens the `:agentHq` SQLite database.
 *
 * WHY A FACTORY AND NOT A SINGLETON: the daemon needs one long-lived handle, but a test needs to
 * create a database in a temp dir, close it, and REOPEN THE SAME FILE to prove a row survived. A
 * process-wide singleton would make that sequence impossible to express. So opening is a plain
 * function of a path, and callers own the lifetime via [AgentHQDatabaseHandle].
 *
 * WHY `sqlite-driver` AND NOT `jdbc-driver`: `libs.sqlite.driver` (2.2.1) is the line the version
 * catalog already carries, and the same artifact `superiorSoftLibrary` consumes on JVM, so this adds
 * no new dependency. `jdbc-driver` on its own is only the generic JDBC layer: it would need
 * `org.xerial:sqlite-jdbc` declared next to it, and that coordinate is not in `libs.versions.toml`.
 * `sqlite-driver` pulls both `jdbc-driver` and `org.xerial:sqlite-jdbc` in transitively (verified
 * with `:superiorSoftLibrary:agentHq:dependencies --configuration runtimeClasspath`), so it is the
 * one catalog line that works with no extra coordinate -- the same reasoning as build.gradle.kts.
 */
object AgentHQDatabaseFactory {

    /** How long SQLite waits for a competing writer before giving up with SQLITE_BUSY. */
    private const val BUSY_TIMEOUT_MS = 5_000

    /**
     * Opens (creating if needed) the database at [path] and returns the handle plus its driver.
     *
     * @param path absolute filesystem path to the SQLite file; a missing file is created by SQLite.
     */
    fun open(path: String, holderLabel: String? = null, busyTimeoutMs: Int = BUSY_TIMEOUT_MS): AgentHQDatabaseHandle {
        val raw = JdbcSqliteDriver(sqliteUrl(path, busyTimeoutMs))
        // Lock attribution: every statement goes through the tracker so a SQLITE_BUSY can name its holder.
        val tracker = WriteTracker(path, holderLabel ?: "agenthq@${ProcessHandle.current().pid()}", busyTimeoutMs)
        val driver = AttributingSqlDriver(raw, tracker)
        applyPragmas(driver, busyTimeoutMs)
        reconcileSchema(driver)
        // ORIGIN-SCRUB-1a: stored rows naming a retired role id read back under its replacement (idempotent).
        if (RoleIdMigration.needed(driver)) newDatabase(driver).transaction { RoleIdMigration.apply(driver) }
        // ORIGIN-SCRUB-1b: stored tickets with a retired type read back under its replacement (idempotent).
        if (TicketTypeMigration.needed(driver)) newDatabase(driver).transaction { TicketTypeMigration.apply(driver) }
        return AgentHQDatabaseHandle(newDatabase(driver), driver, tracker)
    }

    /**
     * The JDBC URL for [path], carrying the pragmas as connect-time parameters.
     *
     * WHY PARAMETERS *AND* NOT ONLY THE `PRAGMA` STATEMENTS BELOW: this is not belt-and-braces
     * pedantry, it is the difference between working and silently not working.
     * [busy_timeout] and [synchronous] are PER-CONNECTION settings -- unlike `journal_mode`, they
     * are not persisted in the file, so they have to be applied to whichever connection
     * `DriverManager.getConnection` hands the driver. xerial (the JDBC layer under `sqlite-driver`)
     * parses its configuration when the connection is OPENED, so URL parameters land on every
     * connection it creates, while a bare `PRAGMA` statement lands only on the one connection that
     * happened to be open when it ran. Measured while proving this module: with statements only,
     * `PRAGMA busy_timeout` read back xerial's default `3000` instead of the configured 5000 and
     * `PRAGMA synchronous` read back `2` (FULL) instead of `1`.
     *
     * Same form as the in-repo precedent `superiorSoftLibrary/src/jvmMain/com/superiorsoftllc/
     * superiorSoftLibrary/db/DatabaseDriverFactory.kt`. Assumes [path] contains no `?` or `&`;
     * the daemon's path is under `%LOCALAPPDATA%` and the tests use temp paths, so that holds.
     */
    private fun sqliteUrl(path: String, busyTimeoutMs: Int): String =
        "jdbc:sqlite:$path" +
            "?journal_mode=WAL" +
            "&busy_timeout=$busyTimeoutMs" +
            "&synchronous=NORMAL" +
            "&cache_size=-64000" +
            // Phase 4.5: events.run_id and runs.pod_id are foreign keys with ON DELETE CASCADE.
            // SQLite ignores every FK clause unless this per-connection pragma is ON, so like
            // busy_timeout it MUST ride the URL to land on every connection.
            "&foreign_keys=true"

    /**
     * The pragmas re-asserted as statements on every open, as a backstop to [sqliteUrl].
     *
     *  - [journal_mode = WAL] lets readers proceed while a writer commits, which is the whole point
     *    for a long-lived daemon that reads its own DB from many places. WAL is PERSISTENT in the
     *    file once set, but it is re-asserted anyway so a database opened by some other tool does
     *    not silently run in rollback-journal mode.
     *  - [busy_timeout] makes a blocked writer WAIT instead of failing instantly with SQLITE_BUSY.
     *    Without it, two near-simultaneous writers lose the race by throwing, and "who wrote first"
     *    would be decided by luck rather than by the database.
     *
     * Kept because they are self-documenting at the call site and cost nothing; the URL in
     * [sqliteUrl] is what makes them survive the driver's own connection handling.
     */
    private fun applyPragmas(driver: SqlDriver, busyTimeoutMs: Int) {
        driver.execute(null, "PRAGMA journal_mode = WAL;", 0)
        driver.execute(null, "PRAGMA busy_timeout = $busyTimeoutMs;", 0)
        driver.execute(null, "PRAGMA synchronous = NORMAL;", 0)
        driver.execute(null, "PRAGMA foreign_keys = ON;", 0)
    }

    /**
     * Brings an existing database file up to the current schema, creating it when it is new.
     *
     * The version lives in SQLite's own `PRAGMA user_version` header field, so the answer comes
     * from the FILE and survives a crash: a daemon that dies leaves a file that was never closed,
     * and "does the file exist" says nothing about which schema it holds.
     *
     *  - user_version 0, no `runs` table   -> a brand-new (or empty) file: [AgentHQDatabase.Schema]
     *    `.create()` from the .sq DDL, then stamp the current version.
     *  - user_version 0, `runs` present     -> a STORY-1 file. Story 1 never wrote user_version, and
     *    its schema is exactly v2 (runs only), so it is migrated FROM [STORY_1_VERSION].
     *  - 0 < user_version < Schema.version -> migrate from the stamped version (.sqm chain).
     *  - user_version > Schema.version     -> written by a NEWER build. Refused loudly: running
     *    older code against a newer schema risks silently corrupting columns it does not know.
     *
     * Migration and stamp happen in ONE transaction, so a crash mid-migration rolls the file back
     * to its previous version instead of leaving it half-migrated with a stale stamp (SQLite DDL,
     * including ALTER TABLE, is transactional).
     */
    private fun reconcileSchema(driver: SqlDriver) {
        val target = AgentHQDatabase.Schema.version
        val stored = userVersion(driver)
        check(stored <= target) {
            "AgentHQ database schema v$stored is newer than this build understands (v$target); " +
                "refusing to open it rather than risk corrupting it."
        }
        if (stored == target) return

        val database = newDatabase(driver)
        database.transaction {
            when {
                stored == 0L && !hasRunsTable(driver) -> AgentHQDatabase.Schema.create(driver)
                stored == 0L -> AgentHQDatabase.Schema.migrate(driver, STORY_1_VERSION, target)
                else -> AgentHQDatabase.Schema.migrate(driver, stored, target)
            }
            // PRAGMA takes no bind parameters; target is a compile-time Long, so this is not injectable.
            driver.execute(null, "PRAGMA user_version = $target;", 0)
        }
    }

    /** The generated database with its column adapters -- the only place they are wired. */
    internal fun newDatabase(driver: SqlDriver): AgentHQDatabase =
        AgentHQDatabase(
            driver = driver,
            podsAdapter = Pods.Adapter(max_concurrencyAdapter = IntColumnAdapter),
        )

    /** The schema a story-1 file holds: `runs` only, i.e. the version after 1.sqm. */
    internal const val STORY_1_VERSION = 2L

    /** The `PRAGMA user_version` stamped in the file header; 0 for a file nobody stamped. */
    internal fun userVersion(driver: SqlDriver): Long =
        driver.executeQuery(
            identifier = null,
            sql = "PRAGMA user_version;",
            mapper = { cursor ->
                val hasRow = cursor.next().value
                QueryResult.Value(if (hasRow) cursor.getLong(0) ?: 0L else 0L)
            },
            parameters = 0,
        ).value

    /**
     * Whether the `runs` table is present in an already-open database. Queries `sqlite_master`,
     * which always exists even in a brand-new empty file, so this is safe on a fresh database.
     */
    private fun hasRunsTable(driver: SqlDriver): Boolean {
        val count = driver.executeQuery(
            identifier = null,
            sql = "SELECT COUNT(*) FROM sqlite_master WHERE type = 'table' AND name = 'runs';",
            mapper = { cursor ->
                // next() is QueryResult<Boolean>: the driver calls this mapper synchronously for a
                // JVM driver, so unwrapping the value is exactly the work of reading one row.
                val hasRow = cursor.next().value
                QueryResult.Value(if (hasRow) cursor.getLong(0) ?: 0L else 0L)
            },
            parameters = 0,
        ).value
        return count > 0
    }
}

/**
 * An open `:agentHq` database together with the driver that owns it, so the lifetime of both is a
 * single [Closeable]. Closing the driver is what releases the SQLite file and its WAL.
 */
class AgentHQDatabaseHandle(
    val database: AgentHQDatabase,
    /**
     * The underlying driver, exposed so callers can run raw statements outside the generated query
     * set -- pragmas, or integrity checks -- while sharing this handle's one connection. Closing
     * still happens through [close], not through the driver directly.
     */
    val driver: SqlDriver,
    /** Who-holds-the-write-lock bookkeeping; null for a handle built without one. */
    val writes: WriteTracker? = null,
) : Closeable {
    override fun close() {
        // Closing the driver runs SQLite's final WAL checkpoint, which can hit SQLITE_BUSY. The tracker
        // must still be open then, so that BUSY can be attributed to its holder: driver first, tracker second.
        try {
            driver.close()
        } finally {
            writes?.close()
        }
    }
}
