package com.superiorsoftllc.infantry.cli

import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * SOAK-1: a soak run's JUnit XML must still be readable AFTER the run that follows it.
 *
 * Gradle wipes `build/test-results` at the start of every test task, so the XML of a failed soak is
 * gone by the time anybody reads the failure -- which is exactly when they need it. The soak
 * archives the previous run's XML aside, and every failing soak names where its own XML is.
 */
class SoakRunArchiveTest {

    private val root: File = Files.createTempDirectory("infantry-soak-archive").toFile()

    private fun resultsDir() = File(root, "test-results/test").apply { mkdirs() }
    private fun archiveRoot() = File(root, "soak-results").apply { mkdirs() }

    /** Writes what Gradle would have written for a finished run. */
    private fun writeResults(contents: String, name: String = "TEST-com.superiorsoftllc.infantry.cli.CliAgentsLiveSoakTest.xml") {
        resultsDir().resolve(name).writeText(contents)
    }

    @AfterTest
    fun cleanUp() {
        root.deleteRecursively()
        // The archive gate is JVM-wide; reset it so a test that exercises archivePreviousRunOnce
        // cannot leave it flipped and gate out CliAgentsLiveSoakTest's @BeforeTest later in the JVM.
        SoakRunArchive.resetArchiveGateForTest()
    }

    @Test
    fun thePreviousRunsXmlIsCopiedIntoItsOwnDirectory() {
        writeResults("<testsuite name=\"CliAgentsLiveSoakTest\" tests=\"1\" failures=\"1\"/>")

        val archived = SoakRunArchive(resultsDir(), archiveRoot(), runIndex = 2).archivePreviousRun()

        assertEquals(1, archived.previousRunIndex, "the archiver claimed to preserve the wrong run")
        val dir = assertNotNull(archived.directory, "run 1's XML was not preserved anywhere")
        assertEquals(archiveRoot().resolve("run-001"), dir, "the preserved XML is not under its own run directory")
        val preserved = dir.resolve("TEST-com.superiorsoftllc.infantry.cli.CliAgentsLiveSoakTest.xml")
        assertTrue(preserved.isFile, "no XML at $preserved")
        assertEquals(
            "<testsuite name=\"CliAgentsLiveSoakTest\" tests=\"1\" failures=\"1\"/>",
            preserved.readText(),
            "the preserved XML is not the XML the run produced",
        )
        assertEquals(1, archived.files.size, "the archiver reported ${archived.files.size} file(s) for one XML")
    }

    @Test
    fun preservingDoesNotDisturbTheResultsTheNextRunIsStillReading() {
        writeResults("<testsuite/>")

        SoakRunArchive(resultsDir(), archiveRoot(), runIndex = 2).archivePreviousRun()

        assertTrue(
            resultsDir().resolve("TEST-com.superiorsoftllc.infantry.cli.CliAgentsLiveSoakTest.xml").isFile,
            "archiving MOVED the XML instead of copying it: the run that owns it must still see it",
        )
        assertTrue(
            assertNotNull(SoakRunArchive(resultsDir(), archiveRoot(), runIndex = 2).archivePreviousRun().directory)
                .resolve("TEST-com.superiorsoftllc.infantry.cli.CliAgentsLiveSoakTest.xml").isFile,
            "nothing was copied, so the original being intact proves nothing",
        )
    }

    @Test
    fun everyClassResultOfThePreviousRunIsPreserved() {
        writeResults("<testsuite name=\"A\"/>", "TEST-A.xml")
        writeResults("<testsuite name=\"B\"/>", "TEST-B.xml")
        writeResults("not a result", "notes.txt")

        val archived = SoakRunArchive(resultsDir(), archiveRoot(), runIndex = 2).archivePreviousRun()
        val dir = assertNotNull(archived.directory)

        assertEquals(
            listOf("TEST-A.xml", "TEST-B.xml"),
            dir.list()?.sorted(),
            "not every test class result of the previous run was preserved",
        )
    }

    @Test
    fun anAlreadyPreservedRunIsNeverOverwritten() {
        writeResults("<testsuite name=\"first attempt\" failures=\"1\"/>")
        val archive = SoakRunArchive(resultsDir(), archiveRoot(), runIndex = 2)
        val first = assertNotNull(archive.archivePreviousRun().directory)
        writeResults("<testsuite name=\"second attempt\"/>")
        val second = assertNotNull(archive.archivePreviousRun().directory)

        assertTrue(
            second != first,
            "the second attempt wrote over $first: evidence from an earlier run was destroyed",
        )
        assertTrue(
            first.resolve("TEST-com.superiorsoftllc.infantry.cli.CliAgentsLiveSoakTest.xml").readText()
                .contains("failures=\"1\""),
            "the earlier run's XML was lost when the later one was preserved",
        )
        assertEquals(1, second.list()?.size, "the suffixed directory should hold only the new run's XML")
    }

    @Test
    fun aDirectoryHoldingOnlyThisRunsOwnNotesIsNotTreatedAsPreserved() {
        val archive = SoakRunArchive(resultsDir(), archiveRoot(), runIndex = 5)
        val runDir = archive.runDir
        assertTrue(runDir.resolve("iterations").mkdirs(), "the iteration notes could not be written")
        runDir.resolve("iterations/iteration-1.txt").writeText("PASS")

        writeResults("<testsuite/>")
        val archived = archive.archivePreviousRun()

        assertEquals(
            File(archiveRoot(), "run-004").canonicalFile,
            assertNotNull(archived.directory).canonicalFile,
            "the previous run's XML was pushed into a suffixed directory because this run's own " +
                "notes were already in its directory",
        )
    }

    @Test
    fun theFirstRunHasNoPreviousRunToPreserve() {
        val archived = SoakRunArchive(resultsDir(), archiveRoot(), runIndex = 1).archivePreviousRun()

        assertEquals(0, archived.previousRunIndex, "run 0 does not exist")
        assertEquals(emptyList(), archived.files, "run 1 preserved something that was never run")
    }

    @Test
    fun aRunWithNoXmlPreservesNothingWithoutFailing() {
        val archived = SoakRunArchive(resultsDir(), archiveRoot(), runIndex = 3).archivePreviousRun()

        assertEquals(emptyList(), archived.files, "an empty results directory produced archived XML")
    }

    @Test
    fun theFailureBannerNamesWhereThisRunsXmlIsAndWhereItWillBePreserved() {
        val archive = SoakRunArchive(resultsDir(), archiveRoot(), runIndex = 7)

        val banner = archive.evidenceBanner()

        val canonicalResults = resultsDir().canonicalPath
        val canonicalArchive = archiveRoot().canonicalPath
        assertTrue(
            canonicalResults in banner || resultsDir().absolutePath in banner,
            "the failure banner does not name this run's XML directory ($canonicalResults):\n$banner",
        )
        assertTrue(
            canonicalArchive in banner || archiveRoot().absolutePath in banner,
            "the failure banner does not name the preserved run directory ($canonicalArchive):\n$banner",
        )
        assertTrue("run-007" in banner, "the failure banner does not name this run: $banner")
    }

    @Test
    fun oneIterationNoteIsWrittenUnderThisRunsDirectory() {
        val archive = SoakRunArchive(resultsDir(), archiveRoot(), runIndex = 4)

        val note = assertNotNull(
            archive.writeIterationNote("SOAK-1a", 2, "PASS: 41 listings, 12 mid-save"),
            "the iteration note was not written",
        )

        assertTrue(note.isFile, "no note at $note")
        assertEquals("PASS: 41 listings, 12 mid-save", note.readText())
        assertEquals(
            File(archiveRoot(), "run-004"),
            note.parentFile.parentFile,
            "iteration notes belong under this run's own directory",
        )
    }

    @Test
    fun nineIterationNotesAcrossThreeSoaksAreNotOverwritten() {
        val archive = SoakRunArchive(resultsDir(), archiveRoot(), runIndex = 4)
        val soaks = listOf("SOAK-1a", "SOAK-1b", "SOAK-1c")
        for (soak in soaks) {
            for (i in 1..3) {
                assertNotNull(
                    archive.writeIterationNote(soak, i, "PASS: $soak iteration $i"),
                    "could not write $soak iteration $i",
                )
            }
        }

        val names = File(archive.runDir, "iterations").listFiles()?.map { it.name }?.sorted() ?: emptyList()

        assertEquals(
            9,
            names.size,
            "expected 9 notes (3 soaks x 3 iterations), got ${names.size}: $names",
        )
        for (soak in soaks) {
            for (i in 1..3) {
                val expected = "iteration-${soak}-%03d.txt".format(i)
                assertTrue(
                    expected in names,
                    "missing $expected: the per-soak name was dropped and $soak iteration $i was overwritten",
                )
            }
        }
    }

    @Test
    fun archivePreviousRunOnceIsGatedAcrossInstances() {
        SoakRunArchive.resetArchiveGateForTest()
        writeResults("<testsuite/>")

        // Two instances stand in for two @Test methods: JUnit 4 builds a fresh instance per method,
        // so only a companion (static) gate can stop the second method re-archiving the same run.
        val first = SoakRunArchive(resultsDir(), archiveRoot(), runIndex = 2)
        val second = SoakRunArchive(resultsDir(), archiveRoot(), runIndex = 2)

        val r1 = first.archivePreviousRunOnce()
        val r2 = second.archivePreviousRunOnce()

        assertNotNull(r1.directory, "the first call should archive the previous run")
        assertNull(
            r2.directory,
            "a second instance (a later @Test method) re-archived the previous run; " +
                "the once-per-JVM gate was not shared",
        )
    }
}