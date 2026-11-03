package com.superiorsoftllc.infantry.cli

import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/**
 * SOAK-1 evidence keeper: every soak run's JUnit XML has to survive the NEXT run.
 *
 * Gradle deletes `build/test-results` when a test task starts, so without this the XML of a run that
 * failed -- and the only record of WHY it failed -- is gone by the time anyone goes looking. This
 * copies the previous run's XML into its own per-run directory under `build/soak-results` before the
 * current run begins, and hands out the paths a failure message has to name.
 *
 * A "run" here is ONE Gradle `test` invocation: that is the granularity at which XML exists, since
 * Gradle writes a single result file per test CLASS, not per test method or per in-JVM iteration.
 * The opt-in repeat loop in [CliAgentsLiveSoakTest] therefore cannot produce per-iteration XML and
 * writes its own text notes; the XML belongs to the invocation and is preserved here.
 *
 * Nothing is ever overwritten. A destination that already holds a preserved run gets a suffixed
 * directory instead, because the whole point is that evidence from an earlier attempt is not lost.
 */
class SoakRunArchive(
    /** Where the test task writes THIS run's XML (read at the next run's start). */
    val resultsDir: File,
    /** Root the per-run copies live under. */
    val archiveRoot: File,
    /** 1-based index of the run that is starting now. */
    val runIndex: Int,
) {

    /** Where this run's own XML will be preserved once a LATER run archives it. */
    val runDir: File get() = File(archiveRoot, runName(runIndex))

    /**
     * Copies the previous run's `TEST-*.xml` aside, before this run's test task overwrites them.
     *
     * Returns what was archived and where. An empty result means there was nothing to keep -- no
     * previous run, or a previous run that produced no result files -- which is not an error.
     */
    fun archivePreviousRun(): ArchiveResult {
        val previous = runIndex - 1
        if (previous < 1) return ArchiveResult(previous, null, emptyList())

        val sources = resultsDir.listFiles { f: File -> f.isFile && f.name.startsWith("TEST-") && f.name.endsWith(".xml") }
            ?.sortedBy { it.name }
            ?: emptyList()
        if (sources.isEmpty()) return ArchiveResult(previous, null, emptyList())

        val destination = freeDirectoryFor(previous)
        destination.mkdirs()
        val copied = sources.map { source ->
            val target = File(destination, source.name)
            source.copyTo(target, overwrite = true)
            target
        }
        return ArchiveResult(previous, destination, copied)
    }

    /**
     * Archives the previous run AT MOST ONCE per JVM.
     *
     * The guard is the companion [archiveGate], because JUnit 4 builds a fresh test-class instance
     * for every `@Test` method and an instance flag would let each method archive again -- observed
     * in the step-3 rerun as `run-006`, `run-006-2` and `run-006-3`, three full copies of the same
     * previous run, while the suite reported green. The first call flips the gate and archives; every
     * later call returns an empty result, which is the behaviour [archivePreviousRun] already gives
     * for "nothing to keep".
     */
    fun archivePreviousRunOnce(): ArchiveResult {
        if (!archiveGate.compareAndSet(false, true)) {
            return ArchiveResult(runIndex - 1, null, emptyList())
        }
        return archivePreviousRun()
    }

    /**
     * `run-NNN`, or the first suffixed variant of it that holds no preserved run yet.
     *
     * "Occupied" means it holds a `TEST-*.xml`: a directory holding only this run's own iteration
     * notes is the CURRENT run's directory, and treating it as taken would push the previous run's
     * XML into a suffixed directory for no reason.
     */
    private fun freeDirectoryFor(previous: Int): File {
        val base = File(archiveRoot, runName(previous))
        if (!holdsPreservedRun(base)) return base
        var suffix = 2
        while (holdsPreservedRun(File(archiveRoot, "${runName(previous)}-$suffix"))) suffix++
        return File(archiveRoot, "${runName(previous)}-$suffix")
    }

    private fun holdsPreservedRun(dir: File): Boolean =
        dir.isDirectory && dir.listFiles { f: File -> f.name.startsWith("TEST-") && f.name.endsWith(".xml") }
            ?.isNotEmpty() == true

    /**
     * The block of text a failing soak appends to its message, naming where its XML is.
     *
     * Both directories are named because they hold the evidence at different times: the live results
     * directory is written when the test task ENDS, so it is what a single forced failure leaves
     * behind, while the per-run directory under the archive root is what is still there after the
     * next run wipes it.
     */
    fun evidenceBanner(): String {
        val thisRun = runName(runIndex)
        return buildString {
            appendLine("SOAK-1 evidence:")
            appendLine("  this run's JUnit XML (written when the test task ends): ${resultsDir.absolutePath}")
            appendLine("  preserved per-run copies: ${archiveRoot.absolutePath}")
            append("  this run is $thisRun: ${runDir.absolutePath}")
        }
    }

    /**
     * Records one iteration of the opt-in repeat loop as text under this run's directory.
     *
     * [iteration] is the iteration's position within the loop that ran the test (1..REPEAT), and
     * [soak] is the test's name (e.g. "SOAK-1a"). Both go into the file name, because a JVM that
     * runs SOAK-1a, then SOAK-1b, then SOAK-1c through runSoak would otherwise overwrite the
     * earlier tests' iteration-001.txt with the later ones' -- the run that takes 54 seconds
     * instead of 18 with REPEAT=3 fires nine iterations, and the proof of a per-test breakdown is
     * gone the moment a later test writes its first iteration.
     */
    fun writeIterationNote(soak: String, iteration: Int, text: String): File? {
        val dir = File(runDir, "iterations")
        if (!dir.mkdirs() && !dir.isDirectory) return null
        val safe = soak.replace(Regex("[^A-Za-z0-9._-]"), "_")
        val note = File(dir, "iteration-${safe}-%03d.txt".format(iteration))
        return try {
            note.writeText(text)
            note
        } catch (e: Exception) {
            // A soak that cannot record its notes still has to report the failure it found.
            System.err.println("SOAK-1: could not write $note: ${e.message}")
            null
        }
    }

    companion object {
        const val PAIRS_PROPERTY = "infantry.soak.pairs"
        const val RUN_PROPERTY = "infantry.soak.run"
        const val REPEAT_PROPERTY = "infantry.soak.repeat"
        const val ARCHIVE_PROPERTY = "infantry.soak.archive"
        const val RESULTS_PROPERTY = "infantry.soak.results"

        fun runName(index: Int): String = "run-%03d".format(index)

        /**
         * Once-per-JVM archive gate. Static because JUnit 4's per-method instantiation would reset an
         * instance flag before every method, re-running the archive. Shared across every
         * [SoakRunArchive] instance in the JVM, so the first `archivePreviousRunOnce` archives and
         * the rest find it done.
         */
        private val archiveGate: AtomicBoolean = AtomicBoolean(false)

        /** Test hook: reset the once-per-JVM gate so a test can exercise the first-archive path again. */
        fun resetArchiveGateForTest() {
            archiveGate.set(false)
        }

        /** The archiver for the run that is starting now, from the forwarded `-D` knobs. */
        fun forCurrentRun(): SoakRunArchive = SoakRunArchive(
            resultsDir = File(System.getProperty(RESULTS_PROPERTY) ?: "build/test-results/test"),
            archiveRoot = File(System.getProperty(ARCHIVE_PROPERTY) ?: "build/soak-results"),
            runIndex = System.getProperty(RUN_PROPERTY)?.toIntOrNull()?.coerceAtLeast(1) ?: 1,
        )
    }
}

/** What [SoakRunArchive.archivePreviousRun] found, for the run log and the failure banner. */
data class ArchiveResult(
    val previousRunIndex: Int,
    val directory: File?,
    val files: List<File>,
)