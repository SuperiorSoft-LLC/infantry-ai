package com.superiorsoftllc.infantry.tokens

import kotlinx.serialization.json.Json
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption

/**
 * A [UsageLedger] that survives the process that wrote it.
 *
 * WHY JSONL and not the whole-file snapshot used by
 * [com.superiorsoftllc.infantry.session.JsonSessionStore]: the ledger is APPEND-ONLY. A measured
 * run is a fact that must never be rewritten, so the write is one appended line rather than a
 * re-serialisation of every past run. That also means a crash mid-write can only ever cost the
 * newest line, never the history that earned it.
 *
 * WHY NO DAEMON WIRING YET: this is pure core. The path is passed in, so nothing here knows about
 * [com.superiorsoftllc.infantry.session.stateDir], the database, or which arm the daemon is running.
 *
 * WHY CORRUPTION IS FATAL: a silently skipped line would quietly flatter whichever arm it landed
 * on. The whole epic is judged on the DELTA between two arms, so a missing sample is worse than a
 * ledger that refuses to open.
 */
class JsonFileUsageLedger(private val file: Path) : UsageLedger {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    // Reused rather than re-derived, so the aggregation rules (per-arm totals, absent arms stay
    // absent instead of being zero-filled) have exactly ONE definition.
    private val delegate = InMemoryUsageLedger()

    init {
        if (Files.isRegularFile(file)) {
            Files.readAllLines(file).forEachIndexed { index, line ->
                if (line.isNotBlank()) {
                    val parsed = try {
                        json.decodeFromString(UsageSample.serializer(), line)
                    } catch (e: Exception) {
                        throw IllegalStateException(
                            "usage ledger ${file.toAbsolutePath()} is corrupt at line ${index + 1}: ${e.message}",
                            e,
                        )
                    }
                    delegate.record(parsed)
                }
            }
        }
    }

    override fun record(sample: UsageSample) {
        delegate.record(sample)
        Files.newOutputStream(
            file,
            StandardOpenOption.CREATE,
            StandardOpenOption.APPEND,
        ).use { it.write((json.encodeToString(UsageSample.serializer(), sample) + "\n").toByteArray()) }
    }

    override fun samples(taskId: String): List<UsageSample> = delegate.samples(taskId)

    override fun compare(taskId: String): ArmComparison = delegate.compare(taskId)
}
