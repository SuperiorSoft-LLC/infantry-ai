package com.superiorsoftllc.infantry.session

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** PROGRESS-S5: the per-project label history behind the opt-in estimated mode. */
class ProgressEstimatesTest {
    @Test fun `median of the newest five, per project and label`() {
        val h = InMemoryProgressEstimates()
        assertNull(h.estimate("p", "gate"))
        h.record("p", "gate", 10)
        assertEquals(10, h.estimate("p", "gate"))
        h.record("p", "gate", 30)
        assertEquals(20, h.estimate("p", "gate"))
        listOf(1_000L, 1_000L, 1_000L, 1_000L).forEach { h.record("p", "gate", it) }
        assertEquals(1_000, h.estimate("p", "gate"), "only the newest ${ProgressEstimates.KEEP} count")
        assertNull(h.estimate("q", "gate"), "another project has its own history")
        assertNull(h.estimate(null, "gate"))
        h.record("p", "zero", 0)
        assertNull(h.estimate("p", "zero"), "a zero duration is not recorded")
    }

    @Test fun `the file outlives the process and a broken file is an empty history`() {
        val dir = Files.createTempDirectory("infantry-estimates").toFile()
        try {
            val file = dir.resolve(FileProgressEstimates.FILE_NAME)
            FileProgressEstimates(file).apply { record("p", "gate", 90_000); record(null, "build", 30_000) }
            val reread = FileProgressEstimates(file)
            assertEquals(90_000, reread.estimate("p", "gate"))
            assertEquals(30_000, reread.estimate(null, "build"))
            file.writeText("{not json")
            assertNull(FileProgressEstimates(file).estimate("p", "gate"))
        } finally {
            dir.deleteRecursively()
        }
    }
}
