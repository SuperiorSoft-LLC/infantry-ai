package com.superiorsoftllc.agenthq.session

import com.superiorsoftllc.agenthq.persistence.AgentHqStore
import java.util.concurrent.ConcurrentHashMap

/**
 * PROGRESS-ALL: how long finished tickets took, read from the ticket run log (`ticket_runs`, the durable record of every
 * run's start and end). One finished ticket = the sum of its runs' durations, counted only when its last run SUCCEEDED and
 * the ticket is done. Grouped by (project, weight); the newest [ProgressEstimates.KEEP] per group. Read-only, cached for [FRESH_FOR].
 */
class AutoProgressHistory(private val store: AgentHqStore, private val clock: () -> Long = System::currentTimeMillis) {
    private data class Cached(val at: Long, val durations: List<Long>)

    private val cache = ConcurrentHashMap<Pair<String?, Int?>, Cached>()

    /** Durations (ms) of the newest finished tickets of [weight] in [projectId], oldest first; empty = none. */
    fun durations(projectId: String?, weight: Int?): List<Long> {
        val key = projectId to weight
        val now = clock()
        cache[key]?.takeIf { now - it.at < FRESH_FOR }?.let { return it.durations }
        val d = runCatching { load(projectId, weight) }.getOrDefault(emptyList())
        cache[key] = Cached(now, d)
        return d
    }

    private fun load(projectId: String?, weight: Int?): List<Long> =
        finished(store.tickets.all().filter { it.projectId == projectId && it.weight == weight && it.status == "done" }.map { it.id })
            .sortedBy { it.first }.takeLast(ProgressEstimates.KEEP).map { it.second }

    /** (endedAt, duration) of every ticket in [ids] whose runs ended and whose last one SUCCEEDED. */
    private fun finished(ids: List<String>): List<Pair<Long, Long>> = ids.mapNotNull { id ->
        val runs = store.ticketRuns.byTicket(id)
        val last = runs.lastOrNull { it.endedAt != null } ?: return@mapNotNull null
        if (last.outcome != "SUCCEEDED") return@mapNotNull null
        val ms = runs.filter { it.endedAt != null }.sumOf { it.endedAt!! - it.startedAt }
        if (ms > 0) last.endedAt!! to ms else null
    }

    fun install(settings: com.superiorsoftllc.agenthq.settings.SettingsService) {
        AutoProgress.history = ::durations
        AutoProgress.weightOf = { id -> runCatching { store.tickets.get(id)?.weight }.getOrNull() }
        AutoProgress.staleMs = {
            com.superiorsoftllc.agenthq.settings.LimitRegistry.minutesMs(
                settings.limit(com.superiorsoftllc.agenthq.settings.LimitRegistry.PROGRESS_STALE_MINUTES),
                com.superiorsoftllc.agenthq.settings.LimitRegistry.PROGRESS_STALE_MINUTES,
            ).takeIf { it != Long.MAX_VALUE } ?: 0L
        }
    }

    companion object {
        const val FRESH_FOR = 60_000L
    }
}
