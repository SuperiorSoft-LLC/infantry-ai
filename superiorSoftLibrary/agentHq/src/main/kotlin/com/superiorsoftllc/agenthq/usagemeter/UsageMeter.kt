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
    private class Pending(val source: String, val acc: StreamUsageAccumulator = StreamUsageAccumulator())

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
            is SessionEvent.Turn -> pending[event.sessionId] = Pending(sourceOf(sessions(event.sessionId), event.origin))
            is SessionEvent.Stdout -> {
                val line = event.line
                if (line.length < 4 || ("usage" !in line && "tokens" !in line)) return
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

    @Serializable
    data class Report(val rows: List<Row>, val keepWeeks: Int, val generatedAt: Long)

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
        return Report(rows, keepWeeks(), clock())
    }

    fun reportJson(): String = Json.encodeToString(Report.serializer(), report())

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
