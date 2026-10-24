package com.superiorsoftllc.agenthq.session

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * Contract 1.124.0: what the daemon has learned about how long a labelled stretch of a job takes (a gradle task, a test
 * task, a gate stage), per project. The last [MAX_DURATIONS] durations of each label are kept; the rolling median is the
 * default estimate when a worker reports a label with none of its own. Small by design: at most [MAX_LABELS] labels per
 * project, persisted to [file] (null = memory only) so a daemon restart keeps what it learned.
 */
class ProgressEstimates(private val file: File? = null) {
    @Serializable
    private data class Snapshot(val version: Int = 1, val projects: Map<String, Map<String, List<Long>>> = emptyMap())

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val data = LinkedHashMap<String, LinkedHashMap<String, ArrayDeque<Long>>>()

    init {
        runCatching {
            if (file?.isFile == true) {
                for ((project, labels) in json.decodeFromString(Snapshot.serializer(), file.readText()).projects) {
                    val into = data.getOrPut(project) { LinkedHashMap() }
                    for ((label, ms) in labels) into[label] = ArrayDeque(ms.takeLast(MAX_DURATIONS))
                }
            }
        }
    }

    /** The median duration (ms) of [label] in [project], or null when it has never finished (a first run). */
    @Synchronized
    fun estimate(project: String?, label: String): Long? = data[project.orEmpty()]?.get(label)?.takeIf { it.isNotEmpty() }?.let(::median)

    /** The durations kept for [label], oldest first (for tests and diagnostics). */
    @Synchronized
    fun durations(project: String?, label: String): List<Long> = data[project.orEmpty()]?.get(label)?.toList().orEmpty()

    @Synchronized
    fun record(project: String?, label: String, ms: Long) {
        if (ms <= 0 || label.isBlank()) return
        val labels = data.getOrPut(project.orEmpty()) { LinkedHashMap() }
        val list = labels.remove(label) ?: ArrayDeque() // re-inserted last: the oldest label is the one evicted
        list.addLast(ms)
        while (list.size > MAX_DURATIONS) list.removeFirst()
        labels[label] = list
        while (labels.size > MAX_LABELS) labels.remove(labels.keys.first())
        save()
    }

    private fun save() {
        val target = file ?: return
        runCatching {
            target.parentFile?.mkdirs()
            val tmp = File(target.parentFile, target.name + ".tmp")
            val snapshot = Snapshot(projects = data.mapValues { (_, l) -> l.mapValues { it.value.toList() } })
            tmp.writeText(json.encodeToString(Snapshot.serializer(), snapshot))
            Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }

    private fun median(values: List<Long>): Long {
        val s = values.sorted()
        return if (s.size % 2 == 1) s[s.size / 2] else (s[s.size / 2 - 1] + s[s.size / 2]) / 2
    }

    companion object {
        const val MAX_DURATIONS = 20
        const val MAX_LABELS = 200
    }
}
