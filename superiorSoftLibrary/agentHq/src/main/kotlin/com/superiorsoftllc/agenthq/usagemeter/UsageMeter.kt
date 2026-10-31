package com.superiorsoftllc.agenthq.usagemeter

import com.superiorsoftllc.agenthq.protocol.MessageOrigin
import com.superiorsoftllc.agenthq.session.InfantryEnv
import com.superiorsoftllc.agenthq.session.SessionEvent
import com.superiorsoftllc.agenthq.session.SessionRecord
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.sql.Connection
import java.sql.DriverManager
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit
import java.time.temporal.TemporalAdjusters
import java.util.concurrent.ConcurrentHashMap

/**
 * USAGE-1: the daemon's usage meter. THE SEAM: this package is self-contained. Its whole surface is
 * [onSessionEvent] (feed it the session events), [reportJson] (the `usage_meter` frame body) and [prune]
 * (scheduled by [start]); it owns its own SQLite file, so it touches no other daemon table. When the daemon
 * plugin system exists (audit 22-24) this whole package moves behind one registration:
 * `UsageMeter.open(file)`, subscribe `onSessionEvent`, serve `usage_meter_get`.
 *
 * Per ended run it records provider-reported tokens (input, output, cache read, cache write), reported cost,
 * turns and the context seen, tagged with role, model and SOURCE. Weekly retention: see [prune].
 */
class UsageMeter private constructor(
    private val conn: Connection,
    private val keepWeeks: () -> Int,
    private val clock: () -> Long,
) {
    private class Pending(val source: String, val rec: SessionRecord?, val acc: StreamUsageAccumulator = StreamUsageAccumulator())

    /** STATS-ACC-1: fired after every recorded turn / compaction. The daemon pushes a `usage_meter` frame from it. */
    @Volatile var onRecorded: (() -> Unit)? = null
    /** Price table (USD per million tokens) shipped in the report; the UI never hard-codes prices. */
    @Volatile var prices: () -> List<Price> = { emptyList() }

    private val pending = ConcurrentHashMap<String, Pending>()
    @Volatile private var timer: java.util.Timer? = null

    init {
        synchronized(conn) {
            conn.createStatement().use {
                it.execute(
                    """CREATE TABLE IF NOT EXISTS meter_run (
                        run_id TEXT PRIMARY KEY, session_id TEXT NOT NULL, ended_at INTEGER NOT NULL,
                        role TEXT NOT NULL, provider TEXT NOT NULL, model TEXT NOT NULL, source TEXT NOT NULL,
                        turns INTEGER NOT NULL, input INTEGER NOT NULL, output INTEGER NOT NULL,
                        cache_read INTEGER NOT NULL, cache_write INTEGER NOT NULL, context_sum INTEGER NOT NULL, cost REAL)""",
                )
                it.execute("CREATE INDEX IF NOT EXISTS meter_run_ended ON meter_run(ended_at)")
                // STATS-ACC-1 (additive): one row per model request. NULL = the harness did not report that field.
                it.execute(
                    """CREATE TABLE IF NOT EXISTS meter_request (
                        id INTEGER PRIMARY KEY AUTOINCREMENT, ts INTEGER NOT NULL, session_id TEXT NOT NULL, run_no INTEGER NOT NULL,
                        ticket_id TEXT, role TEXT NOT NULL, harness TEXT NOT NULL, model TEXT, source TEXT NOT NULL,
                        input INTEGER, output INTEGER, cache_read INTEGER, cache_write INTEGER, context INTEGER, cost REAL)""",
                )
                it.execute("CREATE INDEX IF NOT EXISTS meter_request_ts ON meter_request(ts)")
                it.execute(
                    """CREATE TABLE IF NOT EXISTS meter_event (
                        id INTEGER PRIMARY KEY AUTOINCREMENT, ts INTEGER NOT NULL, session_id TEXT NOT NULL, ticket_id TEXT,
                        harness TEXT NOT NULL, kind TEXT NOT NULL, trigger_kind TEXT, tokens_before INTEGER)""",
                )
                it.execute(
                    """CREATE TABLE IF NOT EXISTS meter_day (
                        day TEXT NOT NULL, role TEXT NOT NULL, provider TEXT NOT NULL, model TEXT NOT NULL, source TEXT NOT NULL,
                        runs INTEGER NOT NULL, turns INTEGER NOT NULL, input INTEGER NOT NULL, output INTEGER NOT NULL,
                        cache_read INTEGER NOT NULL, cache_write INTEGER NOT NULL, context_sum INTEGER NOT NULL, cost REAL,
                        PRIMARY KEY (day, role, provider, model, source))""",
                )
            }
        }
    }

    /** Who caused this run: `trigger` (a trigger checkpoint's mail), `mail` (another agent / outside mail woke it), else `pm` or `worker`. */
    internal fun sourceOf(record: SessionRecord?, origin: MessageOrigin): String = when {
        origin is MessageOrigin.External && origin.senderLabel.startsWith("trigger:") -> "trigger"
        origin is MessageOrigin.Agent || origin is MessageOrigin.External || (origin is MessageOrigin.System && origin.kind == "mail") -> "mail"
        origin is MessageOrigin.System && origin.kind == "scheduler" -> "trigger"
        record?.role.equals("pm", ignoreCase = true) -> "pm"
        else -> "worker"
    }

    fun onSessionEvent(event: SessionEvent, sessions: (String) -> SessionRecord?) {
        when (event) {
            is SessionEvent.Turn -> {
                val rec = sessions(event.sessionId)
                val p = Pending(sourceOf(rec, event.origin), rec)
                p.acc.onTurn = { t -> recordTurn(event.sessionId, p, t) }
                p.acc.onCompaction = { c -> recordCompaction(event.sessionId, p, c) }
                pending[event.sessionId] = p
            }
            is SessionEvent.Stdout -> {
                val line = event.line
                if (line.length < 4 || ("usage" !in line && "tokens" !in line && "compact" !in line)) return
                pending[event.sessionId]?.acc?.accept(line)
            }
            is SessionEvent.Exit -> {
                val p = pending.remove(event.sessionId) ?: return
                val t = p.acc.totals() ?: return
                val rec = sessions(event.sessionId) ?: return
                runCatching { insert(rec, p.source, t) }
                    .onFailure { System.err.println("[agentHq] usage meter did not store run of ${rec.id}: ${it.message}") }
            }
            else -> Unit
        }
    }

    private fun recordTurn(sessionId: String, p: Pending, t: TurnUsage) {
        val rec = p.rec
        runCatching {
            synchronized(conn) {
                conn.prepareStatement("INSERT INTO meter_request (ts, session_id, run_no, ticket_id, role, harness, model, source, input, output, cache_read, cache_write, context, cost) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?)").use { s ->
                    s.setLong(1, clock()); s.setString(2, sessionId); s.setInt(3, rec?.runCount ?: 0)
                    s.setString(4, rec?.ticketId); s.setString(5, rec?.role ?: "none"); s.setString(6, rec?.provider ?: "unknown")
                    s.setString(7, rec?.model ?: t.model); s.setString(8, p.source)
                    s.setNullableLong(9, t.input); s.setNullableLong(10, t.output); s.setNullableLong(11, t.cacheRead)
                    s.setNullableLong(12, t.cacheWrite); s.setNullableLong(13, t.context)
                    if (t.cost == null) s.setNull(14, java.sql.Types.REAL) else s.setDouble(14, t.cost)
                    s.executeUpdate()
                }
            }
        }.onFailure { System.err.println("[agentHq] usage meter did not store a request of $sessionId: ${it.message}") }
        onRecorded?.invoke()
    }

    private fun recordCompaction(sessionId: String, p: Pending, c: CompactionUsage) {
        val rec = p.rec
        runCatching {
            synchronized(conn) {
                conn.prepareStatement("INSERT INTO meter_event (ts, session_id, ticket_id, harness, kind, trigger_kind, tokens_before) VALUES (?,?,?,?,?,?,?)").use { s ->
                    s.setLong(1, clock()); s.setString(2, sessionId); s.setString(3, rec?.ticketId); s.setString(4, rec?.provider ?: "unknown")
                    s.setString(5, "compaction"); s.setString(6, c.trigger); s.setNullableLong(7, c.tokensBefore)
                    s.executeUpdate()
                }
            }
        }
        onRecorded?.invoke()
    }

    private fun java.sql.PreparedStatement.setNullableLong(i: Int, v: Long?) { if (v == null) setNull(i, java.sql.Types.INTEGER) else setLong(i, v) }

    internal fun insert(rec: SessionRecord, source: String, t: RunTotals, endedAt: Long = clock()) {
        val model = rec.model ?: t.model ?: "default"
        synchronized(conn) {
            conn.prepareStatement("INSERT OR REPLACE INTO meter_run VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?)").use { s ->
                s.setString(1, "${rec.id}:${rec.runCount}"); s.setString(2, rec.id); s.setLong(3, endedAt)
                s.setString(4, rec.role ?: "none"); s.setString(5, rec.provider); s.setString(6, model); s.setString(7, source)
                s.setLong(8, t.turns); s.setLong(9, t.input); s.setLong(10, t.output); s.setLong(11, t.cacheRead)
                s.setLong(12, t.cacheWrite); s.setLong(13, t.contextSum)
                if (t.cost == null) s.setNull(14, java.sql.Types.REAL) else s.setDouble(14, t.cost)
                s.executeUpdate()
            }
        }
    }

    /**
     * WEEKLY retention (weeks start Monday 00:00 UTC). Per-run rows are kept for the current week plus the previous
     * week. Every older run is rolled into one row per day per (role, provider, model, source) and deleted, in one
     * transaction. Daily rollups older than [keepWeeks] weeks before that cutoff are deleted. Idempotent.
     * Returns (runs rolled up, day rows deleted).
     */
    fun prune(): Pair<Int, Int> {
        val now = Instant.ofEpochMilli(clock()).atZone(ZoneOffset.UTC).toLocalDate()
        val cutoffDay = now.with(TemporalAdjusters.previousOrSame(java.time.DayOfWeek.MONDAY)).minusWeeks(1)
        val cutoff = cutoffDay.atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli()
        val dropBefore = cutoffDay.minusWeeks(keepWeeks().coerceAtLeast(0).toLong()).toString()
        synchronized(conn) {
            conn.autoCommit = false
            try {
                val rolled = conn.prepareStatement(
                    """INSERT INTO meter_day SELECT strftime('%Y-%m-%d', ended_at/1000, 'unixepoch') d, role, provider, model, source,
                        COUNT(*), SUM(turns), SUM(input), SUM(output), SUM(cache_read), SUM(cache_write), SUM(context_sum), SUM(cost)
                        FROM meter_run WHERE ended_at < ? GROUP BY d, role, provider, model, source
                        ON CONFLICT(day, role, provider, model, source) DO UPDATE SET
                        runs=runs+excluded.runs, turns=turns+excluded.turns, input=input+excluded.input, output=output+excluded.output,
                        cache_read=cache_read+excluded.cache_read, cache_write=cache_write+excluded.cache_write,
                        context_sum=context_sum+excluded.context_sum, cost=CASE WHEN cost IS NULL THEN excluded.cost WHEN excluded.cost IS NULL THEN cost ELSE cost+excluded.cost END""",
                ).use { it.setLong(1, cutoff); it.executeUpdate() }
                val deletedRuns = conn.prepareStatement("DELETE FROM meter_run WHERE ended_at < ?").use { it.setLong(1, cutoff); it.executeUpdate() }
                conn.prepareStatement("DELETE FROM meter_request WHERE ts < ?").use { it.setLong(1, cutoff); it.executeUpdate() }
                conn.prepareStatement("DELETE FROM meter_event WHERE ts < ?").use { it.setLong(1, cutoff); it.executeUpdate() }
                val deletedDays = conn.prepareStatement("DELETE FROM meter_day WHERE day < ?").use { it.setString(1, dropBefore); it.executeUpdate() }
                conn.commit()
                return deletedRuns to deletedDays
            } catch (e: Exception) {
                conn.rollback(); throw e
            } finally {
                conn.autoCommit = true
            }
        }
    }

    /** Runs [prune] now and then every [everyHours] (a daemon thread). */
    fun start(everyHours: Long = 6) {
        if (timer != null) return
        val t = java.util.Timer("usage-meter-prune", true)
        t.schedule(object : java.util.TimerTask() {
            override fun run() { runCatching { prune() }.onFailure { System.err.println("[agentHq] usage meter prune failed: ${it.message}") } }
        }, 5_000, everyHours * 3_600_000)
        timer = t
    }

    fun close() { timer?.cancel(); synchronized(conn) { runCatching { conn.close() } } }

    @Serializable
    data class Row(
        val day: String, val role: String, val provider: String, val model: String, val source: String,
        val runs: Long, val turns: Long, val input: Long, val output: Long,
        val cacheRead: Long, val cacheWrite: Long, val contextSum: Long, val cost: Double? = null,
    )

    /** One model request. Null = not reported by the harness (shown as n/a). */
    @Serializable
    data class RequestRow(
        val ts: Long, val sessionId: String, val ticketId: String? = null, val role: String, val harness: String, val model: String? = null,
        val source: String, val input: Long? = null, val output: Long? = null, val cacheRead: Long? = null, val cacheWrite: Long? = null,
        val context: Long? = null, val cost: Double? = null,
    )

    @Serializable
    data class EventRow(val ts: Long, val sessionId: String, val ticketId: String? = null, val harness: String, val kind: String, val trigger: String? = null, val tokensBefore: Long? = null)

    /** USD per million tokens for models whose id contains [match] (case-insensitive); the first match wins. A config table, not UI knowledge. */
    @Serializable
    data class Price(val match: String, val input: Double, val output: Double, val cacheRead: Double, val cacheWrite: Double)

    @Serializable
    data class Report(
        val rows: List<Row>, val keepWeeks: Int, val generatedAt: Long,
        val requests: List<RequestRow> = emptyList(), val events: List<EventRow> = emptyList(), val prices: List<Price> = emptyList(),
        /** STALL-2: the stall watcher's own counters (no model call behind any of them); null = not attached. */
        val watch: com.superiorsoftllc.agenthq.mailbox.WatchMeter.Counts? = null,
    )

    /** One row per day per (role, provider, model, source): live runs grouped by day, plus the daily rollups. */
    fun report(): Report {
        val rows = ArrayList<Row>()
        synchronized(conn) {
            conn.createStatement().use { st ->
                st.executeQuery(
                    """SELECT d, role, provider, model, source, SUM(r), SUM(turns), SUM(input), SUM(output), SUM(cr), SUM(cw), SUM(ctx), SUM(cost) FROM (
                        SELECT strftime('%Y-%m-%d', ended_at/1000, 'unixepoch') d, role, provider, model, source, 1 r, turns, input, output,
                        cache_read cr, cache_write cw, context_sum ctx, cost FROM meter_run
                        UNION ALL SELECT day, role, provider, model, source, runs, turns, input, output, cache_read, cache_write, context_sum, cost FROM meter_day)
                        GROUP BY d, role, provider, model, source ORDER BY d""",
                ).use { rs ->
                    while (rs.next()) {
                        val cost = rs.getDouble(13).takeUnless { rs.wasNull() }
                        rows += Row(rs.getString(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5),
                            rs.getLong(6), rs.getLong(7), rs.getLong(8), rs.getLong(9), rs.getLong(10), rs.getLong(11), rs.getLong(12), cost)
                    }
                }
            }
        }
        // Runs still in flight count now (their turns are already recorded), so the pane never lags until the process exits.
        val today = Instant.ofEpochMilli(clock()).atZone(ZoneOffset.UTC).toLocalDate().toString()
        for ((_, p) in pending) {
            val t = p.acc.totals() ?: continue
            val rec = p.rec ?: continue
            rows += Row(today, rec.role ?: "none", rec.provider, rec.model ?: t.model ?: "default", p.source, 0, t.turns, t.input, t.output,
                t.cacheRead, t.cacheWrite, t.contextSum, t.cost)
        }
        return Report(rows, keepWeeks(), clock(), requests(), events(), prices())
    }

    /** USAGE-FIT: every recorded request with `ts >= since`, oldest first, capped at [limit] (newest kept). Read-only: a SELECT, nothing is written. */
    fun requestsSince(since: Long, limit: Int = 500_000): List<com.superiorsoftllc.agenthq.daemon.api.usage.MeterRequest> = synchronized(conn) {
        val out = ArrayList<com.superiorsoftllc.agenthq.daemon.api.usage.MeterRequest>()
        conn.prepareStatement("SELECT ts, session_id, role, harness, model, input, output, cache_read, cache_write FROM meter_request WHERE ts >= ? ORDER BY ts DESC, id DESC LIMIT ?").use { st ->
            st.setLong(1, since); st.setInt(2, limit)
            st.executeQuery().use { rs ->
                fun l(i: Int) = rs.getLong(i).takeUnless { rs.wasNull() }
                while (rs.next()) out += com.superiorsoftllc.agenthq.daemon.api.usage.MeterRequest(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5), l(6), l(7), l(8), l(9))
            }
        }
        out.reverse(); out
    }

    private fun requests(limit: Int = 600): List<RequestRow> = synchronized(conn) {
        val out = ArrayList<RequestRow>()
        conn.createStatement().use { st ->
            st.executeQuery("SELECT ts, session_id, ticket_id, role, harness, model, source, input, output, cache_read, cache_write, context, cost FROM meter_request ORDER BY id DESC LIMIT $limit").use { rs ->
                fun l(i: Int) = rs.getLong(i).takeUnless { rs.wasNull() }
                while (rs.next()) out += RequestRow(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5), rs.getString(6),
                    rs.getString(7), l(8), l(9), l(10), l(11), l(12), rs.getDouble(13).takeUnless { rs.wasNull() })
            }
        }
        out.reverse(); out
    }

    private fun events(limit: Int = 100): List<EventRow> = synchronized(conn) {
        val out = ArrayList<EventRow>()
        conn.createStatement().use { st ->
            st.executeQuery("SELECT ts, session_id, ticket_id, harness, kind, trigger_kind, tokens_before FROM meter_event ORDER BY id DESC LIMIT $limit").use { rs ->
                while (rs.next()) out += EventRow(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4), rs.getString(5), rs.getString(6), rs.getLong(7).takeUnless { rs.wasNull() })
            }
        }
        out.reverse(); out
    }

    fun reportJson(): String = Json.encodeToString(Report.serializer(), report().copy(watch = com.superiorsoftllc.agenthq.mailbox.WatchMeter.counts()))

    companion object {
        const val DEFAULT_KEEP_WEEKS = 4

        /** `AGENTHQ_USAGE_METER_KEEP_WEEKS` (weeks of daily rollups kept), default [DEFAULT_KEEP_WEEKS]. */
        fun keepWeeksFromEnv(): Int = InfantryEnv.get("AGENTHQ_USAGE_METER_KEEP_WEEKS")?.trim()?.toIntOrNull()?.takeIf { it in 0..520 } ?: DEFAULT_KEEP_WEEKS

        fun open(file: File, keepWeeks: () -> Int = ::keepWeeksFromEnv, clock: () -> Long = System::currentTimeMillis): UsageMeter {
            file.absoluteFile.parentFile?.mkdirs()
            return UsageMeter(DriverManager.getConnection("jdbc:sqlite:${file.absolutePath}"), keepWeeks, clock)
        }
    }
}
