package com.superiorsoftllc.infantry.cli

import com.superiorsoftllc.infantry.protocol.TicketStart
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * BUG-REGRESS-1: a started ticket's `start.detail` says what its branch was cut from, then any launch warning; the CLI
 * and MCP read them apart, so a cut note is never shown as a warning, and a daemon that sends only a launch warning
 * (every daemon before BUG-REGRESS-1) still reads as one.
 */
class TicketStartedDetailTest {

    private val warning = TicketStart.LAUNCH_WARNING + "the claude CLI was not found on PATH"

    @Test
    fun noDetail_isNeitherANoteNorAWarning() {
        assertEquals(null to null, TicketStarter.startedDetail(null))
        assertEquals(null to null, TicketStarter.startedDetail("   "))
    }

    @Test
    fun aCutNoteAlone_isTheNote() {
        assertEquals("cut from HEAD 1a2b3c4d" to null, TicketStarter.startedDetail("cut from HEAD 1a2b3c4d"))
    }

    @Test
    fun aLaunchWarningAlone_isTheWarning_asAnOlderDaemonSendsIt() {
        assertEquals(null to warning, TicketStarter.startedDetail(warning))
    }

    @Test
    fun aCutNoteThenALaunchWarning_areReadApart() {
        val detail = "cut from release 1a2b3c4d; $warning"
        assertEquals("cut from release 1a2b3c4d" to warning, TicketStarter.startedDetail(detail))
    }
}
