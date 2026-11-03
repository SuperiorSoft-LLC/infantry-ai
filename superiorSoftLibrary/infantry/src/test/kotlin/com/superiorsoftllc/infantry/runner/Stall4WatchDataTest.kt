package com.superiorsoftllc.infantry.runner

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** STALL-4 (ticket 14ac9383): the watch's DATA: notice templates, redirect / failure-marker profiles, the capped log, pruning. */
class Stall4WatchDataTest {

    // ---- item 1: wording ----

    @Test
    fun noShippedTemplateNamesARoleOrAssumesARecipient() {
        val bad = WatchNotices.DEFAULTS.mapValues { WatchNotices.roleWords(it.value) }.filterValues { it.isNotEmpty() }
        assertTrue(bad.isEmpty(), "role words in watch notices: $bad")
    }

    @Test
    fun theGuardCatchesEveryRoleWord() {
        for (t in listOf("report to the PM", "tell the owner", "worker 1234 idle", "the PMs", "Report To someone")) {
            assertTrue(WatchNotices.roleWords(t).isNotEmpty(), t)
        }
        assertTrue(WatchNotices.roleWords("If you are waiting on it, end your turn; a report is in the log.").isEmpty())
    }

    @Test
    fun anOverrideRewordsANotice_aRoleWordOrAnUnknownKeyIsRefused() {
        assertFailsWith<IllegalArgumentException> { WatchNotices.validate(mapOf("job.quiet.head" to "quiet; report to the PM")) }
        assertFailsWith<IllegalArgumentException> { WatchNotices.validate(mapOf("no.such.key" to "x")) }
        WatchNotices.validate(mapOf("job.quiet.subject" to "quiet {id}"))
        val before = WatchNotices.overrides
        try {
            WatchNotices.overrides = { mapOf("job.quiet.subject" to "Q {id} {unknown}", "job.eof.subject" to "ask the owner") }
            assertEquals("Q j1 {unknown}", WatchNotices.text("job.quiet.subject", "id" to "j1"))
            // A stored override that names a role never reaches a session: the shipped text is used.
            assertEquals(WatchNotices.DEFAULTS["job.eof.subject"]!!.replace("{id}", "j1"), WatchNotices.text("job.eof.subject", "id" to "j1"))
        } finally {
            WatchNotices.overrides = before
        }
    }

    // ---- item 2: redirects are data ----

    @Test
    fun redirectsAreFoundFromProfileData_nullDevicesAndVariablesSkipped() {
        val cwd = File("/w").absoluteFile
        fun t(cmd: String) = ToolProfiles.redirectTargets(cmd, cwd, ToolProfiles.DEFAULTS).map { File(it).name }
        assertEquals(listOf("x.log"), t("./gradlew test > /tmp/x.log 2>&1"))
        assertEquals(listOf("b.log"), t("gradle build >> b.log"))
        assertEquals(listOf("e.log"), t("npm test 2> e.log"))
        assertEquals(listOf("t.log"), t("gradle test | tee -a t.log"))
        assertEquals(listOf("o.txt"), t("gradle test | Out-File -FilePath o.txt"))
        assertEquals(listOf("m.log"), t("mvn -l m.log verify"))
        assertEquals(listOf("m2.log"), t("mvn --log-file=m2.log verify"))
        assertEquals(emptyList(), t("gradle test > /dev/null 2>&1"))
        assertEquals(emptyList(), t("gradle test > \$TMP/x.log"))
        assertEquals(emptyList(), t("gradle test 2>&1 | grep e:"))
        assertEquals(emptyList(), t("if [ a -gt 1 ]; then echo hi; fi"))
    }

    @Test
    fun failureMarkersAreData() {
        val m = ToolProfiles.failureMarkers(ToolProfiles.DEFAULTS)
        assertTrue(m.any { it.containsMatchIn("BUILD FAILED in 4s") })
        assertTrue(m.any { it.containsMatchIn("FAILURE: Build failed with an exception.") })
        assertFalse(m.any { it.containsMatchIn("BUILD SUCCESSFUL in 4s") })
        // A project's own tool: one more entry, no code.
        val custom = ToolProfiles.merge(ToolProfiles.DEFAULTS, listOf(ToolProfile("cargo", commands = listOf("cargo"), failureMarkers = listOf("^error\\[E\\d+\\]"))))
        assertTrue(ToolProfiles.failureMarkers(custom).any { it.containsMatchIn("error[E0308]: mismatched types") })
        assertFailsWith<IllegalArgumentException> { ToolProfiles.validate(ToolProfile("bad", failureMarkers = listOf("("))) }
    }

    // ---- item 4: limited space ----

    private fun tmpDir(): File = Files.createTempDirectory("stall4").toFile().also { it.deleteOnExit() }

    @Test
    fun theLogIsCappedFromTheHead_theTailAndATruncatedMarkerStay() {
        val f = File(tmpDir(), "j.log")
        val log = CappedLog(f, maxBytes = 10_000, minFreeBytes = 0)
        repeat(2_000) { log.appendLine("line $it ".padEnd(40, 'x')) }
        log.close()
        assertTrue(f.length() <= 10_000 + 200, "capped: ${f.length()}")
        val lines = f.readLines()
        assertTrue(lines.first().startsWith("[infantry:truncated ") && lines.first().contains("bytes"), lines.first())
        assertTrue(lines.last().startsWith("line 1999 "), lines.last())
        assertTrue(log.truncated > 60_000, "${log.truncated}")
        assertTrue(lines.drop(1).all { it.startsWith("line ") }, "cut at a line boundary")
    }

    @Test
    fun belowTheFreeSpaceFloorTheLogPauses_onceAndWithoutFailing() {
        val f = File(tmpDir(), "j.log")
        var free = 1_000L * CappedLog.MB
        var t = 0L
        val log = CappedLog(f, maxBytes = 0, minFreeBytes = 200 * CappedLog.MB, freeSpace = { free }, clock = { t })
        assertTrue(log.appendLine("one"))
        free = 10 * CappedLog.MB
        t += 5_000L
        assertFalse(log.appendLine("two"))
        assertNotNull(log.pausedReason)
        assertTrue(log.pausedReason!!.contains("below the floor"), log.pausedReason)
        assertFalse(log.appendLine("three"))
        log.close()
        assertEquals(listOf("one"), f.readLines())
    }

    @Test
    fun aLogThatCannotBeOpenedIsNotAnError() {
        val dir = tmpDir()
        val log = CappedLog(dir, maxBytes = 100, minFreeBytes = 0) // a directory: no stream
        assertFalse(log.appendLine("x"))
        assertNull(log.pausedReason)
        log.close()
    }

    @Test
    fun pruneDropsOldFinishedJobsByCountAgeAndSize_neverARunningOneOrTheNewJob() {
        val dir = tmpDir()
        val now = System.currentTimeMillis()
        fun job(id: String, ageMs: Long, finished: Boolean, bytes: Int = 10) {
            File(dir, "$id.log").apply { writeBytes(ByteArray(bytes)); setLastModified(now - ageMs) }
            if (finished) File(dir, "$id.exit").apply { writeText("0"); setLastModified(now - ageMs) }
        }
        for (i in 1..5) job("f$i", i * 60_000L, finished = true)
        job("run1", 10 * 60_000L, finished = false)
        job("old", 9L * 86_400_000L, finished = false)
        job("new", 0, finished = false)
        LogPrune.prune(dir, keepJobs = 3, keepDays = 7, totalBytes = 0, keepIds = setOf("new"), now = now)
        val left = dir.listFiles()!!.map { it.name.substringBefore('.') }.toSet()
        assertEquals(setOf("f1", "f2", "f3", "run1", "new"), left)
        LogPrune.prune(dir, keepJobs = 0, keepDays = 0, totalBytes = 15, keepIds = setOf("new"), now = now)
        assertEquals(setOf("f1", "run1", "new"), dir.listFiles()!!.map { it.name.substringBefore('.') }.toSet())
    }
}
