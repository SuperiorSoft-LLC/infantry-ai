package com.superiorsoftllc.infantry.session

import java.util.concurrent.ConcurrentHashMap

/**
 * PROGRESS-ALL: the progress every running ticket session shows without its agent reporting anything. Computed in the
 * daemon from signals it already has (elapsed time, the tool-call count and time of the last one, commits on the ticket
 * branch, the ticket's weight, and how long past tickets of that weight took). No model call, no prompt text: zero tokens.
 *
 * It is an ESTIMATE and says so: `estimated` = true, `origin` = `auto`, `estimateSource` = `history`. With no history for
 * the ticket's weight there is no number at all (never an invented one): the bar reads "no estimate yet" and only the
 * activity counters show. Precedence: any progress the agent (or its to-do list, or a sleep) set wins; this only fills
 * the gap when there is none. The [SessionProgress.stale] flag, though, is set on whatever progress a ticket session shows.
 */
object AutoProgress {
    const val ORIGIN_AUTO = "auto"
    const val SOURCE_HISTORY = "history"
    const val LABEL = "working"

    /** Fewest finished tickets of one weight before their median is trusted as an estimate. */
    const val MIN_SAMPLES = 3

    /** What the daemon knows about one ticket session at one instant. */
    data class Signals(
        val startedAt: Long, val now: Long, val toolCalls: Int, val lastToolAt: Long?, val commits: Int?,
        /** Durations (ms) of the past tickets of the same weight; empty = no history. */
        val history: List<Long>, val staleMs: Long, val lastEventAt: Long? = null,
    )

    /** Newest activity the daemon saw: the last tool call, else the last event, else the start. */
    fun lastActivity(s: Signals): Long = maxOf(s.lastToolAt ?: 0L, s.lastEventAt ?: 0L, s.startedAt)

    /** No tool/event activity for [Signals.staleMs] (0 = never flagged). */
    fun stale(s: Signals): Boolean = s.staleMs > 0 && s.now - lastActivity(s) >= s.staleMs

    /** The estimated total duration, or null while there are fewer than [MIN_SAMPLES] past tickets. */
    fun estimateMs(history: List<Long>): Long? =
        if (history.size >= MIN_SAMPLES) ProgressEstimates.median(history)?.takeIf { it > 0 } else null

    /** The derived bar for a session whose agent reported nothing. */
    fun derive(s: Signals): SessionProgress {
        val est = estimateMs(s.history)
        val parts = buildList {
            add("${s.toolCalls} tool calls")
            s.commits?.takeIf { it > 0 }?.let { add("$it commits") }
        }
        return SessionProgress(
            mode = "indeterminate", label = "$LABEL (${parts.joinToString(", ")})",
            estimated = true, estimateMs = est, estimateSource = est?.let { SOURCE_HISTORY },
            startedAt = s.startedAt, updatedAt = s.lastToolAt ?: s.startedAt, labelStartedAt = s.startedAt,
            lastOutputAt = lastActivity(s), origin = ORIGIN_AUTO, stale = stale(s).takeIf { it },
        )
    }

    /** [progress] (an agent's own) with the stale flag stamped on; unchanged when it is not stale. */
    fun withStale(progress: SessionProgress, s: Signals): SessionProgress =
        if (progress.state == SessionProgress.RUNNING && stale(s) && progress.stale != true) progress.copy(stale = true) else progress

    // --- the daemon's wiring (set at start; memory only) ------------------------------------------------------------

    /** Past durations of finished tickets of [weight] in [projectId]; the daemon sets the real one. */
    @Volatile var history: (projectId: String?, weight: Int?) -> List<Long> = { _, _ -> emptyList() }

    /** The ticket's weight, by ticket id. */
    @Volatile var weightOf: (ticketId: String) -> Int? = { null }

    /** The stale limit in ms (0 = off), read at every use so a settings change applies at once. */
    @Volatile var staleMs: () -> Long = { 0L }

    /** Commits on each session's ticket branch, refreshed by the manager's timer (a git call is not for the hot path). */
    val commits = ConcurrentHashMap<String, Int>()

    /** Commits on [dir]'s HEAD since [base]; null when git cannot say. A short blocking call, made on the manager's timer only. */
    fun commitCount(dir: String, base: String): Int? = runCatching {
        val p = ProcessBuilder("git", "-C", dir, "rev-list", "--count", "$base..HEAD").redirectErrorStream(true).start()
        if (!p.waitFor(10, java.util.concurrent.TimeUnit.SECONDS)) { p.destroyForcibly(); return null }
        if (p.exitValue() != 0) null else p.inputStream.bufferedReader().readText().trim().toIntOrNull()
    }.getOrNull()

    /** The stamp for a live ticket [record], or null when it is not one (no ticket, or not at work). */
    fun overlay(record: SessionRecord, toolCalls: Int, lastToolAt: Long?, lastEventAt: Long?, now: Long): SessionProgress? {
        return runCatching { stamp(record, toolCalls, lastToolAt, lastEventAt, now) }.getOrDefault(record.progress)
    }

    private fun stamp(record: SessionRecord, toolCalls: Int, lastToolAt: Long?, lastEventAt: Long?, now: Long): SessionProgress? {
        // A derived bar is never stored (a persisted one is dropped): it is recomputed on every read.
        val p = record.progress?.takeIf { it.origin != ORIGIN_AUTO }
        val ticket = record.ticketId ?: return p
        if (record.state != SessionState.BUSY && record.state != SessionState.STARTING) return p
        // A finished report of the agent's own, or one it is still making, is the agent's: only stale may be added.
        val signals = Signals(
            startedAt = record.spawnedAt, now = now, toolCalls = toolCalls, lastToolAt = lastToolAt, commits = commits[record.id],
            history = if (p == null) history(record.projectId, weightOf(ticket)) else emptyList(),
            staleMs = staleMs(), lastEventAt = lastEventAt,
        )
        return if (p == null) derive(signals) else withStale(p, signals)
    }
}
