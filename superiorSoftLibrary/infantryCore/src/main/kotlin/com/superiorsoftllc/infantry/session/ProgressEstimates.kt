// SPDX-License-Identifier: MPL-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.session

import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import java.io.File

/**
 * PROGRESS-S5 (ticket-680e0b39): how long a labelled job took the last few times in a project, for the OPT-IN estimated
 * mode (`infantry run --estimate`). Only a job that asked for an estimate and finished `done` is recorded; a failure is
 * not a duration. Keyed by (project, label); a session with no project shares the "" project.
 */
interface ProgressEstimates {
    /** The expected duration in ms (median of the remembered runs), or null when the label has no finished run. */
    fun estimate(projectId: String?, label: String): Long?

    /** Remembers one finished run, keeping the newest [KEEP]. */
    fun record(projectId: String?, label: String, durationMs: Long)

    companion object {
        const val KEEP = 20

        fun key(projectId: String?, label: String) = "${projectId.orEmpty()}\u0000$label"

        fun median(runs: List<Long>): Long? = runs.sorted().takeIf { it.isNotEmpty() }?.let { s ->
            if (s.size % 2 == 1) s[s.size / 2] else (s[s.size / 2 - 1] + s[s.size / 2]) / 2
        }
    }
}

/** Memory only (tests, and a daemon that could not open its file). */
open class InMemoryProgressEstimates : ProgressEstimates {
    protected val runs = LinkedHashMap<String, List<Long>>()

    @Synchronized
    override fun estimate(projectId: String?, label: String): Long? = ProgressEstimates.median(runs[ProgressEstimates.key(projectId, label)].orEmpty())

    @Synchronized
    override fun record(projectId: String?, label: String, durationMs: Long) {
        if (durationMs <= 0) return
        val k = ProgressEstimates.key(projectId, label)
        val kept = (runs.remove(k).orEmpty() + durationMs).takeLast(ProgressEstimates.KEEP)
        runs[k] = kept // re-inserted, so it is now the newest-used label
        // A bounded file: the oldest-used labels go first.
        while (runs.size > MAX_LABELS) runs.remove(runs.keys.first())
        saved()
    }

    protected open fun saved() = Unit

    companion object {
        const val MAX_LABELS = 500
    }
}

/** Persisted in `<stateDir>/progress-estimates.json`, so the history outlives a daemon restart. Never throws. */
class FileProgressEstimates(private val file: File) : InMemoryProgressEstimates() {
    private val json = Json { ignoreUnknownKeys = true }
    private val shape = MapSerializer(String.serializer(), ListSerializer(Long.serializer()))

    init {
        runCatching { if (file.isFile) runs.putAll(json.decodeFromString(shape, file.readText())) }
    }

    override fun saved() {
        runCatching {
            file.parentFile?.mkdirs()
            val tmp = File(file.path + ".tmp")
            tmp.writeText(json.encodeToString(shape, runs))
            if (!tmp.renameTo(file)) {
                file.delete()
                tmp.renameTo(file)
            }
        }
    }

    companion object {
        const val FILE_NAME = "progress-estimates.json"
    }
}
