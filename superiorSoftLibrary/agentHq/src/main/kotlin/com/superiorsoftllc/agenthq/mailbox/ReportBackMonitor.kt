package com.superiorsoftllc.agenthq.mailbox

import com.superiorsoftllc.agenthq.attention.AttentionKind
import com.superiorsoftllc.agenthq.attention.AttentionStore
import com.superiorsoftllc.agenthq.protocol.TraitCheck
import com.superiorsoftllc.agenthq.session.ReportBack
import com.superiorsoftllc.agenthq.session.ReportStatus
import com.superiorsoftllc.agenthq.session.SessionEvent
import com.superiorsoftllc.agenthq.session.SessionManager
import com.superiorsoftllc.agenthq.session.SessionRecord
import com.superiorsoftllc.agenthq.session.SessionReport
import com.superiorsoftllc.agenthq.session.TraitSnapshot
import com.superiorsoftllc.agenthq.session.isBusy

/**
 * TRAITS-T6 (docs/AGENTHQ-ROLE-TRAITS.md section 5, D7-D10): enforces the REPORT BACK trait when a run ends by itself.
 *
 * A session has reported once its OUTBOX holds a mail sent since spawn, addressed to the check's `reportTo` role (the
 * role name, an agent id of that role, or a session id of that role), whose body carries every required `label: value`
 * line ([ReportBack.missingFields]). No transcript is read (D7).
 *
 * Checked on [SessionEvent.Exit] only, never for a kill ([SessionEvent.Exit.killOutcome] set), only for a session with
 * a `pending`/`unreported` [SessionRecord.report] (so unattended, with a `report` trait: D9), at most once per run, and
 * skipped while the session has an open needs-input ask. On a miss: ONE `system` re-prompt mail when the check allows it
 * and the run exited 0; otherwise (re-prompt off, failed run, re-prompt already used or refused) ONE attention item
 * `unreported` per instance (D10). A later qualifying mail still flips the status to `reported`; the attention item stays
 * until an operator acks it.
 */
class ReportBackMonitor(
    private val sessions: SessionManager,
    private val mailbox: Mailbox,
    /** Sends AND pumps the re-prompt ([MailRouter.send]), so a session at rest gets it at once. */
    private val send: (MailSendRequest) -> MailResult,
    private val attention: AttentionStore,
    /** The role of an agent profile id, null when no such profile exists. */
    private val agentRole: (String) -> String?,
) {

    fun onSessionEvent(event: SessionEvent) {
        if (event !is SessionEvent.Exit) return
        if (event.killOutcome != null) return // an operator kill is never checked (D9)
        runCatching { check(event) }
            .onFailure { System.err.println("[agentHq] report-back check failed for ${event.sessionId}: ${it.message}") }
    }

    private fun check(event: SessionEvent.Exit) {
        val session = sessions.get(event.sessionId) ?: return
        val report = session.report ?: return
        if (!session.unattended || report.status == ReportStatus.REPORTED) return
        // Status(IDLE) is published before Exit, so queued mail may already have started the NEXT run: that run's number
        // is not the one that just ended.
        val endedRun = if (session.isBusy()) session.runCount - 1 else session.runCount
        if (endedRun <= report.checkedRun) return
        val instance = sessions.instance(session.id) ?: return
        val (trait, check) = instance.traits.firstOrNull { it.id == report.traitId }?.let { t -> t.check?.let { t to it } }
            ?: return
        // A declared block is communication, not silence: the session is waiting on someone.
        if (mailbox.waiting().any { it.session == session.id }) return

        val candidates = mailbox.outbox(session.id).filter { it.sentAt >= session.spawnedAt }
        val found = candidates.firstOrNull { addressedTo(it.to, check.reportTo) && ReportBack.missingFields(it.body, check.fields).isEmpty() }
        if (found != null) {
            sessions.setReport(session.id, report.copy(status = ReportStatus.REPORTED, checkedRun = endedRun, reportMessageId = found.id))
            return
        }

        val missed = report.copy(status = ReportStatus.UNREPORTED, checkedRun = endedRun)
        if (check.reprompt && !report.reprompted && event.code == 0) {
            val sent = send(repromptFor(session, trait, check, closestMissing(candidates, check)))
            if (sent is MailResult.Sent) {
                sessions.setReport(session.id, missed.copy(reprompted = true))
                return
            }
        }
        if (report.attentionId != null) {
            sessions.setReport(session.id, missed)
            return
        }
        val item = attention.raise(
            kind = AttentionKind.UNREPORTED,
            ticketId = session.ticketId,
            sessionId = session.id,
            outcome = "unreported",
            detail = detailFor(session, trait, check, endedRun, report, event.code, candidates),
        )
        sessions.setReport(session.id, missed.copy(attentionId = item.id))
    }

    /** The recipient check (D8): the role name itself, an agent id whose profile has that role, or a session of it. */
    private fun addressedTo(to: String, reportTo: String): Boolean {
        val address = to.trim()
        if (address.equals(reportTo, ignoreCase = true)) return true
        if (agentRole(address)?.equals(reportTo, ignoreCase = true) == true) return true
        return sessions.get(address)?.role?.equals(reportTo, ignoreCase = true) == true
    }

    /** The missing fields of the mail closest to a report (fewest missing), or every field when nothing was sent. */
    private fun closestMissing(candidates: List<Message>, check: TraitCheck): List<String> =
        candidates.map { ReportBack.missingFields(it.body, check.fields) }.minByOrNull { it.size } ?: check.fields

    private fun repromptFor(session: SessionRecord, trait: TraitSnapshot, check: TraitCheck, missing: List<String>) =
        MailSendRequest(
            from = SYSTEM,
            to = session.id,
            subject = REPROMPT_SUBJECT,
            body = buildString {
                append("Your run ended without the REPORT BACK mail your role requires (${trait.name}, ${trait.id} v${trait.version}).\n")
                append("Missing: ${missing.joinToString(", ")}.\n")
                append("Send it now, ONE mail to ${check.reportTo}, with every line filled in:\n")
                append("agenthq send ${check.reportTo} \"<one-line verdict>\n")
                check.fields.forEach { append("$it: <value>\n") }
                append("\"\n")
                append("NOT RUN, none and unknown are valid values; a <placeholder> is not. ")
                append("Do not reply to this message: a reply goes to $SYSTEM, not to ${check.reportTo}.")
            },
        )

    private fun detailFor(
        session: SessionRecord,
        trait: TraitSnapshot,
        check: TraitCheck,
        run: Int,
        report: SessionReport,
        code: Int?,
        candidates: List<Message>,
    ): String {
        val why = when {
            code != 0 -> "the run failed (exit $code)"
            !check.reprompt -> "re-prompt is off"
            report.reprompted -> "still missing after the re-prompt"
            else -> "the re-prompt could not be sent"
        }
        val closest = candidates.minByOrNull { ReportBack.missingFields(it.body, check.fields).size }
        val missing = closestMissing(candidates, check)
        val closestNote = closest?.let { " (closest mail ${it.id} to '${it.to}')" } ?: " (no mail sent)"
        return "Session ${session.id} (role ${session.role ?: "-"}, agent ${session.agentId ?: "-"}) ended run $run " +
            "without the REPORT BACK mail to '${check.reportTo}' (${trait.id} v${trait.version}): $why. " +
            "Missing: ${missing.joinToString(", ")}$closestNote."
    }

    companion object {
        /** The non-session handle the re-prompt is sent from, like the CLI's `user`. */
        const val SYSTEM = "system"
        const val REPROMPT_SUBJECT = "REPORT BACK missing"
    }
}
