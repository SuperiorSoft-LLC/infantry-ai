package com.superiorsoftllc.infantry.ui

import com.superiorsoftllc.infantry.ui.model.AppState
import com.superiorsoftllc.infantry.ui.model.ReportFlag
import com.superiorsoftllc.infantry.ui.model.reduce
import com.superiorsoftllc.infantry.ui.model.reportFlagFor
import com.superiorsoftllc.infantry.ui.protocol.Envelope
import com.superiorsoftllc.infantry.ui.protocol.SessionReport
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * TRAITS-T9: the Board and session `unreported` flag, read off `SessionRecord.report` (contract 1.52.0). Frames are
 * raw daemon JSON so the test exercises the real decode, including an older daemon that sends no `report` at all.
 */
class ReportFlagModelTest {

    private fun session(id: String, report: String?): Envelope {
        val reportField = report?.let { ""","report":$it""" }.orEmpty()
        return Envelope(
            Envelope.SESSION,
            """{"id":"$id","provider":"stub","workingDir":"/w","spawnedAt":1,"lastHeartbeat":1,"state":"IDLE","unattended":true$reportField}""",
        )
    }

    private fun report(status: String) = """{"status":"$status","traitId":"report-back","reprompted":false,"checkedRun":1}"""

    private fun ticket(id: String, sessionId: String) =
        Envelope(Envelope.TICKET, """{"id":"$id","title":"T","status":"in_progress","sessionId":"$sessionId"}""")

    private fun feed(vararg e: Envelope) = e.fold(AppState()) { s, f -> reduce(s, f, 100) }

    @Test
    fun `pending decodes and shows no flag`() {
        val s = feed(session("a", report("pending")), ticket("t", "a"))
        assertEquals(SessionReport.STATUS_PENDING, s.agents.getValue("a").session.report?.status)
        assertNull(s.reportFlagFor("a"))
        assertNull(s.reportFlagFor(s.tickets.single()))
    }

    @Test
    fun `reported shows the muted reported flag`() {
        val s = feed(session("a", report("reported")), ticket("t", "a"))
        assertEquals(ReportFlag.REPORTED, s.reportFlagFor("a"))
        assertEquals(ReportFlag.REPORTED, s.reportFlagFor(s.tickets.single()))
    }

    @Test
    fun `unreported shows the unreported flag on the session and its ticket card`() {
        val s = feed(session("a", report("unreported")), ticket("t", "a"))
        val decoded = assertNotNull(s.agents.getValue("a").session.report)
        assertEquals("report-back", decoded.traitId)
        assertEquals(1, decoded.checkedRun)
        assertEquals(ReportFlag.UNREPORTED, s.reportFlagFor("a"))
        assertEquals(ReportFlag.UNREPORTED, s.reportFlagFor(s.tickets.single()))
        assertEquals("unreported", ReportFlag.UNREPORTED.label)
    }

    @Test
    fun `a later report flips unreported to reported`() {
        val s = feed(session("a", report("unreported")), session("a", report("reported")))
        assertEquals(ReportFlag.REPORTED, s.reportFlagFor("a"))
    }

    @Test
    fun `an old daemon that sends no report decodes without a crash and shows no flag`() {
        val s = feed(session("a", report = null), ticket("t", "a"))
        val agent = assertNotNull(s.agents["a"], "the session must still decode and reach the sidebar")
        assertNull(agent.session.report)
        assertNull(s.reportFlagFor("a"))
        assertNull(s.reportFlagFor(s.tickets.single()))
    }

    @Test
    fun `an unknown status or an unknown session shows no flag`() {
        val s = feed(session("a", report("escalated")), ticket("t", "gone"))
        assertNull(s.reportFlagFor("a"))
        assertNull(s.reportFlagFor(s.tickets.single()))
        assertNull(s.reportFlagFor(null as String?))
    }

    @Test
    fun `the attention kind unreported is kept as its wire string like the others`() {
        val item = """{"id":"x","at":5,"kind":"unreported","sessionId":"a","outcome":"unreported","detail":"missing: commit"}"""
        val s = feed(session("a", report("unreported")), Envelope(Envelope.ATTENTION, item))
        assertEquals("unreported", s.attention.single().kind)
        assertEquals("a", s.attention.single().sessionId)
    }
}
