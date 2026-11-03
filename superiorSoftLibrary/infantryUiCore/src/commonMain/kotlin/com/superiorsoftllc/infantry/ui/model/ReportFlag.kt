// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.ui.model

import com.superiorsoftllc.infantry.ui.protocol.SessionReport
import com.superiorsoftllc.infantry.ui.protocol.Ticket

/**
 * TRAITS-T9 (docs/INFANTRY-ROLE-TRAITS.md section 6): the REPORT BACK flag a board card and a session row show.
 * [UNREPORTED] renders in the attention colour, [REPORTED] muted; `pending`, an unknown status and no report at all
 * (an older daemon, or a session with no obligation) show nothing.
 */
enum class ReportFlag(val label: String) { UNREPORTED("unreported"), REPORTED("reported") }

fun reportFlagOf(report: SessionReport?): ReportFlag? = when (report?.status) {
    SessionReport.STATUS_UNREPORTED -> ReportFlag.UNREPORTED
    SessionReport.STATUS_REPORTED -> ReportFlag.REPORTED
    else -> null
}

/** The flag of the session [sessionId], or null when the UI does not know that session. */
fun AppState.reportFlagFor(sessionId: String?): ReportFlag? = sessionId?.let { agents[it] }?.session?.report.let(::reportFlagOf)

/** The flag of the session a ticket card links to. */
fun AppState.reportFlagFor(ticket: Ticket): ReportFlag? = reportFlagFor(ticket.sessionId)
