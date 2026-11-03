package com.superiorsoftllc.infantry.update

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** BUILD-STAMP-1: where a daemon/CLI JVM finds its build string, and the string's shape. */
class BuildInfoTest {
    @Test
    fun theLauncherPropertyWinsThenTheBuildFileThenTheVersionThenDev() {
        val home = Files.createTempDirectory("infantry-buildinfo").toFile()
        try {
            java.io.File(home, BuildInfo.FILE).writeText("1.0.1+20261023.1432.e85b223\n")
            assertEquals("2.0.0+20261101.0800.abcdef1", BuildInfo.resolve("2.0.0+20261101.0800.abcdef1", home, "1.0.1"))
            assertEquals("1.0.1+20261023.1432.e85b223", BuildInfo.resolve(null, home, "1.0.1"), "BUILD file, trimmed")
            assertEquals("1.0.1+20261023.1432.e85b223", BuildInfo.resolve("  ", home, null), "a blank property is not a build")
            assertEquals("1.0.1", BuildInfo.resolve(null, null, "1.0.1"))
            assertEquals("dev", BuildInfo.resolve(null, null, null))
        } finally {
            home.deleteRecursively()
        }
    }

    @Test
    fun theBuildStringIsOneReadyToken() {
        // It travels as `build=<string>` in the space-separated `ready` data, so it never carries whitespace.
        assertEquals("1.0.1+x", BuildInfo.resolve("1.0.1 +x\n", null, null))
    }

    @Test
    fun thePatternAcceptsCleanAndDirtyStampsOnly() {
        assertTrue(BuildInfo.PATTERN.matches("1.0.1+20261023.1432.e85b223"))
        assertTrue(BuildInfo.PATTERN.matches("1.0.1+20261023.1432.e85b223.dirty"))
        assertFalse(BuildInfo.PATTERN.matches("1.0.1"))
        assertFalse(BuildInfo.PATTERN.matches("1.0.1+20261023.e85b223"), "the time is required")
        assertFalse(BuildInfo.PATTERN.matches("1.0.1+20261023.1432.e85b223.DIRTY"))
        assertEquals("e85b223", BuildInfo.PATTERN.matchEntire("1.0.1+20261023.1432.e85b223.dirty")!!.groupValues[4])
    }
}
