package com.superiorsoftllc.infantry.daemon

import com.superiorsoftllc.infantry.mailbox.MailSendRequest
import com.superiorsoftllc.infantry.protocol.Envelope
import com.superiorsoftllc.infantry.protocol.TicketStatus
import com.superiorsoftllc.infantry.protocol.TraitCheck
import com.superiorsoftllc.infantry.protocol.TraitView
import com.superiorsoftllc.infantry.runner.ProcessListener
import com.superiorsoftllc.infantry.runner.ProcessRunner
import com.superiorsoftllc.infantry.runner.ProcessSpec
import com.superiorsoftllc.infantry.runner.RunningProcess
import com.superiorsoftllc.infantry.runner.ScriptedProcess
import com.superiorsoftllc.infantry.runner.StubScript
import com.superiorsoftllc.infantry.session.ReportStatus
import com.superiorsoftllc.infantry.session.SessionReport
import com.superiorsoftllc.infantry.session.SessionState
import kotlinx.serialization.json.Json
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * TRAITS-T6 (docs/INFANTRY-ROLE-TRAITS.md section 5, D7-D10) over the real daemon, a real WebSocket and the real mailbox.
 *
 * Every run is HELD by [HeldRunner] until the test releases it, so the "agent" can mail (or not) through the real
 * `mail_send` frame, speaking as its own session, BEFORE its run ends -- exactly what `infantry send` does from inside a
 * child. The obligation comes from the `backend` role binding a REPORT BACK trait with a `report` check, frozen into the
 * ticket hand-out's instance (TRAITS-T4).
 */
class ReportBackDaemonTest {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    @Test
    fun aReportMailToThePmMarksTheSessionReported() = withDaemon { fixture, runner ->
        val sessionId = startTicket(fixture)
        finishTicket(fixture, sessionId)
        assertEquals(SessionReport(traitId = "report-back"), report(fixture, sessionId), "pending from spawn")

        val mailId = mailAs(fixture, sessionId, to = "pm", body = FULL_REPORT)
        runner.release(0)

        awaitReport(fixture, sessionId) { it.status == ReportStatus.REPORTED }
        assertEquals(SessionReport(ReportStatus.REPORTED, "report-back", reprompted = false, checkedRun = 1, reportMessageId = mailId), report(fixture, sessionId))
        assertEquals(1, runner.processes.size, "no re-prompt run")
        assertTrue(unreported(fixture).isEmpty())
    }

    @Test
    fun aMissingReportIsRepromptedOnceThenTheReportCounts() = withDaemon { fixture, runner ->
        val sessionId = startTicket(fixture)
        finishTicket(fixture, sessionId)
        runner.release(0) // silence

        val reprompt = runner.awaitProcess(fixture, 1)
        awaitReport(fixture, sessionId) { it.status == ReportStatus.UNREPORTED && it.reprompted }
        val delivered = reprompt.deliveredInput
        assertTrue(delivered.contains("REPORT BACK missing"), "the system re-prompt reached the session: ${delivered.take(800)}")
        assertTrue(delivered.contains("Missing: commit, tests, wall time, blockers."), delivered.take(800))
        assertTrue(delivered.contains("infantry send pm"), delivered.take(800))
        assertTrue(unreported(fixture).isEmpty(), "no attention while the re-prompt is outstanding (D10)")

        val mailId = mailAs(fixture, sessionId, to = "pm", body = FULL_REPORT)
        runner.release(1)

        awaitReport(fixture, sessionId) { it.status == ReportStatus.REPORTED }
        assertEquals(SessionReport(ReportStatus.REPORTED, "report-back", reprompted = true, checkedRun = 2, reportMessageId = mailId), report(fixture, sessionId))
        assertTrue(unreported(fixture).isEmpty(), "a self-healed miss never wakes a human")
        assertEquals(2, runner.processes.size)
    }

    @Test
    fun stillMissingAfterTheRepromptRaisesExactlyOneAttentionItem() = withDaemon { fixture, runner ->
        val sessionId = startTicket(fixture)
        finishTicket(fixture, sessionId)
        runner.release(0)
        runner.awaitProcess(fixture, 1)
        // The closest mail is a status note to the pm with only some labels.
        mailAs(fixture, sessionId, to = "pm", body = "progress\ncommit: abc123\ntests: <passed/failed/total>")
        runner.release(1)

        fixture.await(what = "the unreported attention item") { unreported(fixture).isNotEmpty() }
        val item = unreported(fixture).single()
        assertEquals(sessionId, item.sessionId)
        assertNotNull(item.ticketId)
        assertEquals("unreported", item.outcome)
        assertTrue(item.detail.contains("still missing after the re-prompt"), item.detail)
        assertTrue(item.detail.contains("Missing: tests, wall time, blockers"), item.detail)
        assertTrue(item.detail.contains("role backend") && item.detail.contains("agent backend") && item.detail.contains("run 2"), item.detail)
        awaitReport(fixture, sessionId) { it.attentionId == item.id }
        assertEquals(SessionReport(ReportStatus.UNREPORTED, "report-back", reprompted = true, checkedRun = 2, attentionId = item.id), report(fixture, sessionId))

        // One obligation per instance: a later silent run raises nothing more.
        Thread.sleep(200)
        assertEquals(2, runner.processes.size, "no third run: one re-prompt only")
        assertEquals(1, unreported(fixture).size)
    }

    /** Ticket ef3356ae: a clean turn with the ticket still open is a worker waiting for its next instruction, not a miss. */
    @Test
    fun aCleanTurnWithTheTicketOpenIsWaitingNotUnreported() = withDaemon { fixture, runner ->
        val sessionId = startTicket(fixture)
        mailAs(fixture, sessionId, to = "pm", body = "plan ready, waiting for your go")
        runner.release(0)

        fixture.await(what = "the run to end") { fixture.daemon.manager.get(sessionId)?.let { it.runCount == 1 && it.state == SessionState.IDLE } == true }
        Thread.sleep(200)
        assertEquals(SessionReport(traitId = "report-back"), report(fixture, sessionId), "still pending: the report is due when the ticket is done")
        assertEquals(1, runner.processes.size, "no re-prompt run")
        assertTrue(unreported(fixture).isEmpty(), "a waiting worker never needs attention")
    }

    @Test
    fun aKilledRunIsNeverChecked() = withDaemon { fixture, runner ->
        val sessionId = startTicket(fixture)
        fixture.daemon.manager.kill(sessionId, force = true)
        fixture.await(what = "the kill to land") { fixture.daemon.manager.get(sessionId)?.state == SessionState.EXITED }
        Thread.sleep(200)

        assertEquals(SessionReport(traitId = "report-back"), report(fixture, sessionId), "a kill leaves the obligation pending")
        assertEquals(1, runner.processes.size, "no re-prompt after a kill")
        assertTrue(unreported(fixture).isEmpty(), "no attention after a kill")
    }

    @Test
    fun aReportToAnotherRoleIsNotAReport() = withDaemon { fixture, runner ->
        val sessionId = startTicket(fixture)
        finishTicket(fixture, sessionId)
        mailAs(fixture, sessionId, to = "qa", body = FULL_REPORT)
        runner.release(0)

        runner.awaitProcess(fixture, 1)
        awaitReport(fixture, sessionId) { it.status == ReportStatus.UNREPORTED && it.reprompted }
        assertNull(report(fixture, sessionId)!!.reportMessageId, "the requester (pm) did not hear (D8)")
    }

    @Test
    fun anUnfilledPlaceholderIsNotAValue() = withDaemon { fixture, runner ->
        val sessionId = startTicket(fixture)
        finishTicket(fixture, sessionId)
        mailAs(fixture, sessionId, to = "pm", body = FULL_REPORT.replace("commit: abc123", "commit: <sha or none>"))
        runner.release(0)

        val reprompt = runner.awaitProcess(fixture, 1)
        awaitReport(fixture, sessionId) { it.status == ReportStatus.UNREPORTED && it.reprompted }
        assertTrue(reprompt.deliveredInput.contains("Missing: commit."), reprompt.deliveredInput.take(800))
    }

    @Test
    fun aFailedRunRaisesTheAttentionItemAtOnce() = withDaemon(exitCodes = listOf(1)) { fixture, runner ->
        val sessionId = startTicket(fixture)
        runner.release(0)

        fixture.await(what = "the unreported attention item") { unreported(fixture).isNotEmpty() }
        assertTrue(unreported(fixture).single().detail.contains("the run failed (exit 1)"))
        awaitReport(fixture, sessionId) { it.attentionId != null }
        assertFalse(report(fixture, sessionId)!!.reprompted, "no re-prompt for a failed run")
        Thread.sleep(200)
        assertEquals(1, runner.processes.size)
    }

    @Test
    fun anOpenNeedsInputAskSkipsTheCheck() = withDaemon { fixture, runner ->
        val sessionId = startTicket(fixture)
        // A live PM console, so the ask is DELIVERED and stays open (an undeliverable ask is not "waiting" on anyone).
        fixture.client.saveProfile("""{"id":"pm-agent","name":"PM","role":"pm","instructions":"You are the PM."}""")
        fixture.send(Envelope(Envelope.NEW, """{"agentId":"pm-agent"}"""))
        fixture.await(what = "the PM console") { fixture.daemon.manager.list().any { it.agentId == "pm-agent" } }
        mailAs(fixture, sessionId, to = "pm-agent", body = "which table do I migrate?", needsInput = true)
        runner.awaitProcess(fixture, 1) // the PM's run, holding the question
        runner.release(0)

        fixture.await(what = "the run to end") { fixture.daemon.manager.get(sessionId)?.let { it.runCount == 1 && it.state == SessionState.IDLE } == true }
        Thread.sleep(200)
        assertEquals(SessionReport(traitId = "report-back"), report(fixture, sessionId), "a declared block is not silence")
        assertEquals(2, runner.processes.size, "no re-prompt run")
        assertTrue(runner.processes.none { it.deliveredInput.contains("REPORT BACK missing") })
        assertTrue(unreported(fixture).isEmpty())
    }

    @Test
    fun anInteractiveConsoleOwesNoReport() = withDaemon { fixture, runner ->
        seed(fixture)
        fixture.send(Envelope(Envelope.NEW, """{"agentId":"backend"}"""))
        fixture.await(what = "the bound console") { fixture.daemon.manager.list().isNotEmpty() }
        val session = fixture.daemon.manager.list().single()
        assertFalse(session.unattended)
        assertNull(session.report, "an interactive session is never enforced (D9)")
        assertTrue(runner.processes.isEmpty())
    }

    // --- helpers ---------------------------------------------------------------------------------------------------

    /** The canonical REPORT BACK check (section 8), trimmed to four labels; bound to the `backend` role. */
    private fun seed(fixture: DaemonFixture) {
        fixture.client.await(Envelope.READY)
        val store = fixture.daemon.store
        val check = TraitCheck(TraitCheck.KIND_REPORT, "pm", listOf("commit", "tests", "wall time", "blockers"), reprompt = true)
        store.traits.saveWith("report-back") { TraitView("report-back", "REPORT BACK", 1, "mail the pm", check, 1L, 1L) }
        store.roleTraits.set("backend", listOf("report-back"), 1L)
        fixture.client.saveProfile("""{"id":"backend","name":"Backend","role":"backend","instructions":"You are the backend agent."}""")
    }

    /** Hands a ticket to the backend agent (an UNATTENDED spawn) and returns its session, its first run held. */
    private fun startTicket(fixture: DaemonFixture): String {
        seed(fixture)
        val ticket = fixture.client.saveTicket("""{"title":"Ship it","description":"the body","agentId":"backend"}""")
        val started = fixture.client.moveTicket(ticket.id, TicketStatus.IN_PROGRESS)
        assertEquals("started", started.start?.reason, "the ticket must start: ${started.start?.detail}")
        val sessionId = started.sessionId!!
        assertTrue(fixture.daemon.manager.get(sessionId)!!.unattended)
        return sessionId
    }

    /** The worker's ticket moved to done, as a worker does before its last turn ends: its report is due now. */
    private fun finishTicket(fixture: DaemonFixture, sessionId: String) {
        val ticketId = fixture.daemon.manager.get(sessionId)!!.ticketId!!
        fixture.client.moveTicket(ticketId, TicketStatus.DONE)
    }

    /** Sends a mail AS [sessionId] through the real `mail_send` frame and returns the stored message id. */
    private fun mailAs(fixture: DaemonFixture, sessionId: String, to: String, body: String, needsInput: Boolean = false): String {
        val from = fixture.client.mark()
        fixture.send(Envelope(Envelope.MAIL_SEND, json.encodeToString(MailSendRequest.serializer(), MailSendRequest(from = sessionId, to = to, body = body, needsInput = needsInput))))
        val answer = fixture.client.awaitAfter(from, "the mail answer") { it.type == Envelope.MAIL || it.type == Envelope.MAIL_REFUSED }
        assertEquals(Envelope.MAIL, answer.type, "mail refused: ${answer.data}")
        return json.parseToJsonElement(answer.data).let { (it as kotlinx.serialization.json.JsonObject)["id"]!!.toString().trim('"') }
    }

    private fun report(fixture: DaemonFixture, sessionId: String): SessionReport? = fixture.daemon.manager.get(sessionId)?.report

    private fun awaitReport(fixture: DaemonFixture, sessionId: String, what: (SessionReport) -> Boolean) =
        fixture.await(what = "session $sessionId's report state (now ${report(fixture, sessionId)})") { report(fixture, sessionId)?.let(what) == true }

    private fun unreported(fixture: DaemonFixture): List<com.superiorsoftllc.infantry.daemon.api.attention.AttentionEntry> =
        fixture.daemon.attention.list().filter { it.kind == "unreported" }

    /** Every run waits for [release]; run N exits with [exitCodes]`[N]` (0 when absent). */
    private class HeldRunner(private val exitCodes: List<Int>) : ProcessRunner {
        override val id: String = "held"
        val processes = CopyOnWriteArrayList<ScriptedProcess>()

        override fun start(spec: ProcessSpec, listener: ProcessListener): RunningProcess {
            val script = StubScript.default().copy(exitCode = exitCodes.getOrElse(processes.size) { 0 })
            val inner = ScriptedProcess(listener, script, 0)
            processes += inner
            return object : RunningProcess by inner {
                override fun closeInput() = Unit // held until release()
            }
        }

        fun release(index: Int) = processes[index].closeInput()

        fun awaitProcess(fixture: DaemonFixture, index: Int): ScriptedProcess {
            fixture.await(what = "run ${index + 1} to start") { processes.size > index }
            return processes[index]
        }
    }

    private fun withDaemon(exitCodes: List<Int> = emptyList(), block: (DaemonFixture, HeldRunner) -> Unit) {
        val runner = HeldRunner(exitCodes)
        val fixture = DaemonFixture.launch(StubScript.default(), runner = runner)
        try {
            block(fixture, runner)
        } finally {
            fixture.stop()
            fixture.deleteState()
        }
    }

    private companion object {
        const val FULL_REPORT = "Done: endpoint shipped\ncommit: abc123\n- Tests: 42/0/42\n* wall time: 12\nblockers: none"
    }
}
