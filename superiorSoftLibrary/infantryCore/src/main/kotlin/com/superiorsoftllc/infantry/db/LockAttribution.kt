package com.superiorsoftllc.infantry.db

import app.cash.sqldelight.Transacter
import app.cash.sqldelight.TransactionWithReturn
import app.cash.sqldelight.TransactionWithoutReturn
import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlCursor
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.db.SqlPreparedStatement
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.Closeable
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.sql.SQLException
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/*
 * LOCK ATTRIBUTION.
 *
 * A SQLite write lock is CORRECT behaviour: one writer at a time is how the file stays consistent. What was
 * wrong is that a blocked agent was told `[SQLITE_BUSY] The database file is locked` and nothing else. This
 * file makes the failure say WHO holds the lock, doing WHAT, for HOW LONG, and WHICH of the two different
 * failures it is:
 *
 *  - TIMEOUT  (SQLITE_BUSY / SQLITE_LOCKED): a writer waited the whole busy_timeout. Someone really held the
 *             lock; retrying may succeed.
 *  - CONNECT  (SQLITE_IOERR* while setting up a connection, with a registered writer in flight): the same
 *             contention seen across processes on Windows; see [LockFailureKind.CONNECT].
 *  - SNAPSHOT (SQLITE_BUSY_SNAPSHOT): fails in milliseconds. A read-then-write transaction tried to upgrade
 *             after another connection had committed, so the snapshot can never be upgraded. busy_timeout
 *             does NOT apply and retrying the same transaction shape will not help.
 *
 * SOURCE OF TRUTH (the cross-process decision): SQLite cannot say who holds its lock, and an in-memory map
 * cannot answer "which daemon" when daemons are separate processes. So every store instance PUBLISHES its
 * in-flight writes to a small file of its own, `<db>.holders/<instanceId>.json`, outside SQLite entirely.
 * The record therefore never needs the write lock: a blocked agent reads plain files, so it cannot be locked
 * out of finding out why it is locked. One file per instance (never a shared file) means no two processes
 * ever write the same file, so the registry needs no lock of its own.
 *
 * RELEASE / SELF-HEALING: an entry exists only while the write is in flight (removed in `finally`, so
 * commit AND rollback clear it). A process that dies cannot clean up, so a record is trusted only while
 * its heartbeat is younger than [WriteTracker.ttlMs] AND its pid is alive; stale files are ignored and
 * deleted. A starved-but-alive holder whose heartbeat lapses is dropped from the message (the error then
 * says no holder is registered) rather than pointed at forever.
 *
 * KNOWN LIMITS (stated, not hidden): (1) the identity is the daemon/store instance, not the individual
 * agent -- the store has no notion of an agent, so "which daemon" is exact and "which agent" is whatever
 * label the opener supplied. (2) Publication is periodic ([WriteTracker.publishIntervalMs]), so a write
 * shorter than that may never be visible to ANOTHER process; a lock that outlasted busy_timeout always is.
 * (3) A holder outside this store (another tool on the file) is unknown and reported as such.
 */

/** Which of the two different SQLITE_BUSY failures this is. */
enum class LockFailureKind {
    TIMEOUT,
    SNAPSHOT,

    /**
     * A NEW connection could not even be set up while another PROCESS held the write lock. Measured on
     * Windows: it surfaces as SQLITE_IOERR_TRUNCATE from the connect-time pragmas, not as SQLITE_BUSY, so a
     * cross-process blocked agent would otherwise be told "disk I/O error". Only ever reported when a
     * registered writer is in flight; with none, an I/O error stays the I/O error it is.
     */
    CONNECT,
}

/** One write in flight, as published and as shown in an error. */
@Serializable
data class WriteInFlight(
    /** e.g. `writing ticket_runs`. */
    val what: String,
    /** `HOLDING`: a write already succeeded in a still-open transaction (it owns the lock). `ACTIVE`: a write statement is running or waiting. */
    val state: String,
    /** Epoch millis since which the lock is held (HOLDING) or the statement started (ACTIVE). */
    val since: Long,
    val thread: String,
)

@Serializable
data class LastWrite(val what: String, val at: Long)

/** The published record of one store instance. */
@Serializable
data class HolderRecord(
    val instanceId: String,
    val label: String,
    val pid: Long,
    val heartbeatAt: Long,
    val inFlight: List<WriteInFlight> = emptyList(),
    val lastWrite: LastWrite? = null,
)

/** A candidate holder, resolved for display. */
data class LockHolder(
    val label: String,
    val instanceId: String,
    val pid: Long,
    val sameProcess: Boolean,
    val what: String,
    val state: String,
    val heldForMs: Long,
)

/**
 * The attributed form of a SQLITE_BUSY. A [SQLException] on purpose: anything that caught the driver's
 * exception still catches this one, and the original is kept as the cause.
 */
class StoreLockedException(
    message: String,
    val kind: LockFailureKind,
    val operation: String,
    val file: String,
    val holders: List<LockHolder>,
    val waitedMs: Long,
    cause: Throwable?,
) : SQLException(message, null, 0, cause)

class WriteTracker(
    private val dbPath: String,
    val label: String,
    private val busyTimeoutMs: Int,
    val publishIntervalMs: Long = 250,
    val ttlMs: Long = 4_000,
) : Closeable {

    val instanceId: String = UUID.randomUUID().toString().take(8)
    val pid: Long = ProcessHandle.current().pid()
    private val dir = File("$dbPath.holders")
    private val self = File(dir, "$instanceId.json")

    private class Op(val thread: Thread, var what: String, var state: String, var since: Long, var tx: Any? = null)
    private class Scope { val tables = LinkedHashSet<String>(); var op: Op? = null }

    private val ops = ConcurrentHashMap<Long, Op>()
    private val scope = ThreadLocal<Scope?>()
    @Volatile private var lastWrite: LastWrite? = null
    @Volatile private var closed = false
    @Volatile private var warned = false

    private val publisher: ScheduledExecutorService? = if (publishIntervalMs > 0) {
        Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "infantry-lock-attribution").apply { isDaemon = true } }
            .also { it.scheduleWithFixedDelay({ publishNow() }, 0, publishIntervalMs, TimeUnit.MILLISECONDS) }
    } else {
        null
    }

    /** Runs a transaction body; the lock is attributed from its first successful write to its end. */
    fun <T> scoped(body: () -> T): T {
        if (scope.get() != null) return body()
        val s = Scope()
        scope.set(s)
        try {
            return body()
        } catch (e: SQLException) {
            throw attribute(e, s.op?.what ?: "transaction", 0) ?: e
        } finally {
            val op = s.op
            if (op != null) {
                ops.remove(op.thread.id, op)
                if (op.state == HOLDING) { lastWrite = LastWrite(op.what, System.currentTimeMillis()); publishNow() } // released: retract at once
            }
            scope.remove()
        }
    }

    /** Wraps one statement; only writes are tracked, but a BUSY on any statement is attributed. */
    internal fun <R> statement(sql: String, tx: Transacter.Transaction? = null, run: () -> R): R {
        val claim = sql == WRITE_CLAIM
        val table = if (claim) CLAIM_LABEL else writeTarget(sql)
        val started = System.currentTimeMillis()
        if (table == null) {
            try { return run() } catch (e: SQLException) { throw attribute(e, "reading", started) ?: e }
        }
        val s = scope.get()
        val thread = Thread.currentThread()
        // A transaction opened outside [scoped] (e.g. the schema migration's `database.transaction`) still holds
        // the lock BETWEEN its statements; without this its holder vanished after every statement (BUG-534baac6).
        val rawTx = if (s == null) tx else null
        val existing = ops[thread.id]
        val op = s?.op ?: existing?.takeIf { rawTx != null && it.tx === rawTx }
            ?: Op(thread, if (claim) table else "writing $table", ACTIVE, started, rawTx).also { ops[thread.id] = it; s?.op = it }
        if (!claim) {
            if (rawTx != null && existing === op && !op.what.contains(table)) op.what += ", $table"
            s?.tables?.add(table)
            if (s != null) op.what = "writing " + s.tables.joinToString(", ")
        }
        try {
            val r = run()
            if (rawTx != null) {
                if (op.state == ACTIVE) {
                    op.state = HOLDING; op.since = System.currentTimeMillis()
                    val release = { ops.remove(thread.id, op); lastWrite = LastWrite(op.what, System.currentTimeMillis()); publishNow() }
                    rawTx.afterCommit(release); rawTx.afterRollback(release)
                    publishNow()
                }
            } else if (s != null && op.state == ACTIVE) {
                op.state = HOLDING; op.since = System.currentTimeMillis()
                publishNow() // a lock that is now held is visible to other processes at once, not a heartbeat later
            }
            return r
        } catch (e: SQLException) {
            throw attribute(e, op.what, started) ?: e
        } finally {
            if (s == null && rawTx == null) {
                lastWrite = LastWrite(op.what, System.currentTimeMillis())
                ops.remove(thread.id, op)
            }
        }
    }

    private fun attribute(e: SQLException, operation: String, startedAt: Long): StoreLockedException? {
        if (e is StoreLockedException) return e
        val code = codeOf(e) ?: return null
        val holders = holders(excluding = Thread.currentThread().id)
        val kind = when {
            code == "SQLITE_BUSY_SNAPSHOT" -> LockFailureKind.SNAPSHOT
            code.startsWith("SQLITE_BUSY") || code == "SQLITE_LOCKED" -> LockFailureKind.TIMEOUT
            code.startsWith("SQLITE_IOERR") && holders.isNotEmpty() && duringConnect(e) -> LockFailureKind.CONNECT
            else -> return null
        }
        val waited = if (startedAt > 0) System.currentTimeMillis() - startedAt else 0
        val file = File(dbPath).name
        return StoreLockedException(compose(kind, operation, file, holders, waited, recentWriter(), e), kind, operation, file, holders, waited, e)
    }

    /** The most recent committed write by anyone else, for the snapshot message. */
    private fun recentWriter(): String? {
        val now = System.currentTimeMillis()
        val others = readOthers().mapNotNull { r -> r.lastWrite?.let { r to it } }
        val own = lastWrite?.let { Triple(label, pid, it) }
        val best = (others.map { (r, w) -> Triple(r.label, r.pid, w) } + listOfNotNull(own)).maxByOrNull { it.third.at } ?: return null
        return "${best.first} (pid ${best.second}) last wrote ${best.third.what} ${fmt(now - best.third.at)} ago"
    }

    /** Candidate holders, the real owner first: HOLDING before ACTIVE, then oldest. */
    fun holders(excluding: Long? = null): List<LockHolder> {
        val now = System.currentTimeMillis()
        val mine = ops.values.filter { it.thread.id != excluding }
            .map { LockHolder(label, instanceId, pid, true, it.what, it.state, now - it.since) }
        val theirs = readOthers().flatMap { r ->
            r.inFlight.map { LockHolder(r.label, r.instanceId, r.pid, false, it.what, it.state, now - it.since) }
        }
        return (mine + theirs).sortedWith(compareBy<LockHolder> { if (it.state == HOLDING) 0 else 1 }.thenByDescending { it.heldForMs })
    }

    private fun readOthers(): List<HolderRecord> {
        val files = dir.listFiles { f -> f.name.endsWith(".json") } ?: return emptyList()
        val now = System.currentTimeMillis()
        return files.mapNotNull { f ->
            if (f == self) return@mapNotNull null
            val rec = runCatching { WIRE.decodeFromString<HolderRecord>(f.readText()) }.getOrNull()
            val alive = rec != null && ProcessHandle.of(rec.pid).map { it.isAlive }.orElse(false)
            val fresh = rec != null && now - rec.heartbeatAt <= ttlMs
            if (rec != null && alive && fresh) rec else {
                // Self-healing: a dead or lapsed record is a stale pointer; never report it, and clean it up.
                if (rec == null || !alive) runCatching { f.delete() }
                null
            }
        }
    }

    /** Writes this instance's record now. Never throws: attribution must not be able to fail a write. */
    fun publishNow() {
        if (closed) return
        try {
            val record = HolderRecord(
                instanceId, label, pid, System.currentTimeMillis(),
                ops.values.map { WriteInFlight(it.what, it.state, it.since, it.thread.name) },
                lastWrite,
            )
            dir.mkdirs()
            val tmp = File(dir, "$instanceId.tmp")
            tmp.writeText(WIRE.encodeToString(record))
            Files.move(tmp.toPath(), self.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (t: Throwable) {
            if (!warned) { warned = true; System.err.println("[infantry] lock attribution cannot publish to $dir: $t") }
        }
    }

    override fun close() {
        closed = true
        publisher?.shutdownNow()
        runCatching { self.delete() }
        runCatching { File(dir, "$instanceId.tmp").delete() }
        runCatching { dir.delete() } // only succeeds when empty
    }

    private fun compose(kind: LockFailureKind, op: String, file: String, holders: List<LockHolder>, waitedMs: Long, recent: String?, cause: Throwable): String {
        val action = op.removePrefix("writing ").let { if (op.startsWith("writing ")) "write $it" else op }
        val who = holders.firstOrNull()?.let { h ->
            val where = if (h.sameProcess) "this process" else "pid ${h.pid}"
            val verb = if (h.state == HOLDING) "held" else "in flight"
            "store is locked by ${h.label} ($where, instance ${h.instanceId}, ${h.what}, $verb ${fmt(h.heldForMs)})" +
                if (holders.size > 1) " and ${holders.size - 1} other writer(s) in flight" else ""
        }
        return when (kind) {
            LockFailureKind.TIMEOUT ->
                "could not $action on $file: " + (who ?: "$file is locked by a writer that is not registered (another tool, or a holder whose record lapsed)") +
                    " - this is a TIMEOUT (waited ${fmt(waitedMs)} of a ${busyTimeoutMs}ms busy_timeout); retrying may succeed once the holder finishes."
            LockFailureKind.CONNECT ->
                "could not $action on $file: " + (who ?: "a writer is active") +
                    " - a new connection could not be set up while it holds the write lock (${codeOf(cause) ?: "I/O error"} during " +
                    "connection setup: across processes the lock shows up as an I/O error instead of SQLITE_BUSY). " +
                    "This is contention, not a damaged disk; retrying may succeed once the holder finishes."
            LockFailureKind.SNAPSHOT ->
                "could not $action on $file: this transaction read, then tried to write after another connection had committed, " +
                    "so its snapshot can never be upgraded (SQLITE_BUSY_SNAPSHOT). " +
                    (recent?.let { "Most recent writer: $it. " } ?: "") +
                    "This is NOT a timeout: busy_timeout does not apply and retrying the same transaction shape will not help; " +
                    "make the write first, use a single write statement, or re-read inside a new transaction."
        }
    }

    companion object {
        const val HOLDING = "HOLDING"
        const val ACTIVE = "ACTIVE"

        /**
         * The first statement of [com.superiorsoftllc.infantry.persistence.InfantryStore.writeTransaction]: a write
         * that matches no row, so it takes the write lock before any read (the `BEGIN IMMEDIATE` shape) and changes
         * nothing. `runs` is the oldest table (schema v1), so it exists in every database this store opens. Tracked
         * as the lock it claims, not as a write to `runs`, which it never is.
         */
        const val WRITE_CLAIM = "UPDATE runs SET error = error WHERE 0"
        private const val CLAIM_LABEL = "holding the write lock (write-first transaction)"

        private val WRITE = Regex(
            """^\s*(?:INSERT(?:\s+OR\s+\w+)?\s+INTO|REPLACE\s+INTO|UPDATE(?:\s+OR\s+\w+)?|DELETE\s+FROM|CREATE\s+(?:UNIQUE\s+)?(?:TABLE|INDEX)(?:\s+IF\s+NOT\s+EXISTS)?|DROP\s+(?:TABLE|INDEX)(?:\s+IF\s+EXISTS)?|ALTER\s+TABLE)\s+["`\[]?(\w+)""",
            RegexOption.IGNORE_CASE,
        )
        private val WIRE = Json { encodeDefaults = true; ignoreUnknownKeys = true }
        private val CODE = Regex("""\[(SQLITE_[A-Z_]+)]""")

        /** The table a statement writes, or null for anything that is not a write. */
        internal fun writeTarget(sql: String): String? = WRITE.find(sql)?.groupValues?.get(1)

        /** Whether the failure came out of opening a connection rather than out of running a statement. */
        internal fun duringConnect(e: Throwable): Boolean =
            generateSequence(e) { it.cause }.any { t -> t.stackTrace.any { it.className == "org.sqlite.SQLiteConfig" || it.methodName == "getConnection" } }

        /** The SQLITE_* result code named in the exception chain, e.g. `SQLITE_BUSY_SNAPSHOT`. */
        internal fun codeOf(e: Throwable): String? {
            var t: Throwable? = e
            while (t != null) {
                CODE.find(t.message ?: "")?.let { return it.groupValues[1] }
                t = t.cause
            }
            return null
        }

        internal fun fmt(ms: Long): String = if (ms < 1000) "${ms}ms" else "%.1fs".format(ms / 1000.0)
    }
}

/** Driver decorator: routes every statement through the [WriteTracker]. */
internal class AttributingSqlDriver(private val inner: SqlDriver, private val tracker: WriteTracker) : SqlDriver by inner {
    override fun execute(identifier: Int?, sql: String, parameters: Int, binders: (SqlPreparedStatement.() -> Unit)?): QueryResult<Long> =
        tracker.statement(sql, inner.currentTransaction()) { inner.execute(identifier, sql, parameters, binders) }

    override fun <R> executeQuery(
        identifier: Int?, sql: String, mapper: (SqlCursor) -> QueryResult<R>, parameters: Int, binders: (SqlPreparedStatement.() -> Unit)?,
    ): QueryResult<R> = tracker.statement(sql, inner.currentTransaction()) { inner.executeQuery(identifier, sql, mapper, parameters, binders) }
}

/** The `transaction` / `transactionWithResult` of the database, run inside the tracker's scope. */
class TrackedTransacter(private val db: InfantryDatabase, private val tracker: WriteTracker?) {
    fun <R> transactionWithResult(noEnclosing: Boolean = false, bodyWithReturn: TransactionWithReturn<R>.() -> R): R =
        if (tracker == null) db.transactionWithResult(noEnclosing, bodyWithReturn)
        else tracker.scoped { db.transactionWithResult(noEnclosing, bodyWithReturn) }

    fun transaction(noEnclosing: Boolean = false, body: TransactionWithoutReturn.() -> Unit) {
        if (tracker == null) db.transaction(noEnclosing, body) else tracker.scoped { db.transaction(noEnclosing, body) }
    }
}
