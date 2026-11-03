package com.superiorsoftllc.infantry.ui

import com.superiorsoftllc.infantry.ui.model.AppState
import com.superiorsoftllc.infantry.ui.model.BuildCheck
import com.superiorsoftllc.infantry.ui.model.BuildStamp
import com.superiorsoftllc.infantry.ui.model.reduce
import com.superiorsoftllc.infantry.ui.protocol.Envelope
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** BUILD-STAMP-1: the build string's parts, the app/daemon comparison and the `ready` frame's `build=`. */
class BuildStampModelTest {
    @Test fun `a clean stamp parses into version, UTC time and commit`() {
        val s = BuildStamp.parse("1.0.1+20261023.1432.e85b223")!!
        assertEquals("1.0.1", s.version)
        assertEquals("e85b223", s.sha)
        assertFalse(s.dirty)
        assertEquals("2026-10-23 14:32 UTC", s.builtAt)
        assertEquals("version 1.0.1, built 2026-10-23 14:32 UTC, commit e85b223", s.detail)
    }

    @Test fun `the dirty flag is read and said`() {
        val s = BuildStamp.parse("1.0.1+20261023.1432.e85b223.dirty")!!
        assertTrue(s.dirty)
        assertEquals("e85b223", s.sha)
        assertTrue(s.detail!!.endsWith("uncommitted changes"))
    }

    @Test fun `a string that is not a stamp is kept raw, blank is nothing`() {
        assertEquals(BuildStamp("dev"), BuildStamp.parse(" dev "))
        assertNull(BuildStamp.parse("1.0.1")!!.detail)
        assertNull(BuildStamp.parse("  "))
        assertNull(BuildStamp.parse(null))
    }

    @Test fun `the same commit packaged twice is not a mismatch, another commit or a dirty tree is`() {
        assertNull(BuildCheck.mismatch("1.0.1+20261023.1432.e85b223", "1.0.1+20261023.1500.e85b223"))
        val other = BuildCheck.mismatch("1.0.1+20261023.1432.e85b223", "1.0.1+20261022.0900.abc1234")
        assertNotNull(other)
        assertTrue("1.0.1+20261023.1432.e85b223" in other && "1.0.1+20261022.0900.abc1234" in other, other)
        assertNotNull(BuildCheck.mismatch("1.0.1+20261023.1432.e85b223", "1.0.1+20261023.1432.e85b223.dirty"))
        assertNotNull(BuildCheck.mismatch("1.0.2+20261023.1432.e85b223", "1.0.1+20261023.1432.e85b223"))
    }

    @Test fun `an unknown side is no mismatch`() {
        assertNull(BuildCheck.mismatch(null, "1.0.1+20261023.1432.e85b223"))
        assertNull(BuildCheck.mismatch("1.0.1+20261023.1432.e85b223", null))
    }

    @Test fun `the footer names the app build, or both builds as a warning`() {
        assertEquals("build 1.0.1+20261023.1432.e85b223" to false, BuildCheck.footer("1.0.1+20261023.1432.e85b223", "1.0.1+20261023.1432.e85b223"))
        assertEquals("app 1.0.1+20261023.1432.e85b223 \u2260 daemon 1.0.0+20261001.0800.0123abc" to true, BuildCheck.footer("1.0.1+20261023.1432.e85b223", "1.0.0+20261001.0800.0123abc"))
        assertEquals("daemon build 1.0.1+20261023.1432.e85b223" to false, BuildCheck.footer(null, "1.0.1+20261023.1432.e85b223"))
        assertEquals("build unknown" to false, BuildCheck.footer(null, null))
    }

    @Test fun `ready carries the daemon build, an older daemon none`() {
        val withBuild = reduce(AppState(), Envelope(Envelope.READY, "provider=stub contract=1.115.0 instance=A since=0 build=1.0.1+20261023.1432.e85b223"), 0)
        assertEquals("1.0.1+20261023.1432.e85b223", withBuild.connection.daemonBuild)
        assertEquals("1.115.0", withBuild.connection.contract)
        val older = reduce(AppState(), Envelope(Envelope.READY, "provider=stub contract=1.110.0 instance=A since=0"), 0)
        assertNull(older.connection.daemonBuild)
    }
}
