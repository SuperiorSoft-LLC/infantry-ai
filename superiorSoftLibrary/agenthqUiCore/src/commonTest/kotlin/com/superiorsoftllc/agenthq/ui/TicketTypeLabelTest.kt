package com.superiorsoftllc.agenthq.ui

import com.superiorsoftllc.agenthq.ui.model.ticketTypeLabel
import kotlin.test.Test
import kotlin.test.assertEquals

/** ORIGIN-SCRUB-1b: a type a pre-1.91.0 daemon sends is shown under its current name; a current one as sent. */
class TicketTypeLabelTest {
    @Test
    fun aRetiredTypeIsShownUnderItsNewName() {
        assertEquals("defect", ticketTypeLabel("bug"))
        assertEquals("enhancement", ticketTypeLabel("feature"))
        assertEquals("hardening", ticketTypeLabel("security"))
        assertEquals("defect", ticketTypeLabel("defect"))
        assertEquals("custom-x", ticketTypeLabel("custom-x"))
    }
}
