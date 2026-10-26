package com.superiorsoftllc.agenthq.plugin.host

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** PLUGINS-2: the version and range parser behind module.json `dependencies`. */
class SemVerTest {
    private fun inRange(range: String, version: String) = VersionRange.parse(range).contains(SemVer.parse(version))

    @Test
    fun `versions parse with missing parts as zero and order pre-releases first`() {
        assertEquals(SemVer(1, 2, 0), SemVer.parse("1.2"))
        assertEquals(SemVer(1, 2, 3, listOf("rc", "1")), SemVer.parse("v1.2.3-rc.1+build.5"))
        assertTrue(SemVer.parse("1.0.0-rc.1") < SemVer.parse("1.0.0"))
        assertTrue(SemVer.parse("1.0.0-rc.2") < SemVer.parse("1.0.0-rc.10"))
        assertTrue(SemVer.parse("1.0.0-alpha") < SemVer.parse("1.0.0-beta"))
        assertTrue(SemVer.parse("1.10.0") > SemVer.parse("1.9.9"))
        assertNull(SemVer.parseOrNull("one"))
        assertNull(SemVer.parseOrNull("1.2.3.4"))
    }

    @Test
    fun `comparators joined by spaces must all hold`() {
        assertTrue(inRange(">=1.2 <2", "1.2.0"))
        assertTrue(inRange(">=1.2 <2", "1.9.9"))
        assertFalse(inRange(">=1.2 <2", "1.1.9"))
        assertFalse(inRange(">=1.2 <2", "2.0.0"))
        assertTrue(inRange(">= 1.2   < 2", "1.5.0"), "a space after the operator is allowed")
        assertTrue(inRange(">1.2", "1.3.0"))
        assertFalse(inRange(">1.2", "1.2.9"), ">1.2 is above every 1.2.x")
        assertTrue(inRange("<=1.2", "1.2.9"))
        assertFalse(inRange("<=1.2", "1.3.0"))
    }

    @Test
    fun `bare, caret, tilde, any and alternatives`() {
        assertTrue(inRange("1.2.3", "1.2.3"))
        assertFalse(inRange("1.2.3", "1.2.4"))
        assertTrue(inRange("1.2", "1.2.7"))
        assertFalse(inRange("1", "2.0.0"))
        assertTrue(inRange("^1.2.3", "1.9.0"))
        assertFalse(inRange("^1.2.3", "2.0.0"))
        assertFalse(inRange("^0.2.3", "0.3.0"), "below 1.0 a caret keeps the minor")
        assertTrue(inRange("~1.2.3", "1.2.9"))
        assertFalse(inRange("~1.2.3", "1.3.0"))
        for (any in listOf("*", "x", "", " ")) assertTrue(inRange(any, "7.0.0"), "'$any'")
        assertTrue(inRange("^1.0 || ^3.0", "3.1.0"))
        assertFalse(inRange("^1.0 || ^3.0", "2.0.0"))
    }

    @Test
    fun `nonsense is not a range`() {
        for (bad in listOf(">=", "abc", ">=1.2 <two", "1.0 - 2.0")) assertNull(VersionRange.parseOrNull(bad), bad)
    }
}
