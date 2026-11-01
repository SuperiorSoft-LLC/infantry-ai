package com.superiorsoftllc.agenthq.plugins.official.board.daemon

import kotlin.test.Test
import kotlin.test.assertEquals

class TicketReportLineTest {
    @Test
    fun a_created_ticket_carries_the_line_once_and_the_setting_changes_or_removes_it() {
        val d = TicketReportLine.DEFAULT
        assertEquals(d, TicketReportLine.apply(null, null))
        assertEquals("brief\n\n$d", TicketReportLine.apply("brief\n", null))
        assertEquals("brief\n\n$d", TicketReportLine.apply("brief\n\n$d", null))
        assertEquals("brief\n\nSay when done.", TicketReportLine.apply("brief", "Say when done."))
        assertEquals("brief", TicketReportLine.apply("brief", "  "))
        assertEquals(null, TicketReportLine.apply(null, ""))
    }
}
