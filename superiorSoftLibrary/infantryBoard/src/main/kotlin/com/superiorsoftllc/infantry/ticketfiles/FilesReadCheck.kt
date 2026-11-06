// SPDX-License-Identifier: Apache-2.0
// Copyright (c) SuperiorSoft LLC
package com.superiorsoftllc.infantry.ticketfiles

/**
 * DAEMON-CONTEXT-1: a ticket worker's done report must carry a `Files read: <paths>` line. The check is pure; the mail
 * router applies it to mail a worker sends its starter, and the line is stored on the ticket.
 * FIRST-RUN-1: the label is also accepted mid-line after a space or punctuation ("... wrote hello.txt. Files read: none"):
 * on Windows the `infantry.cmd` launcher keeps only the first line of an argument, so a one-line report could never pass.
 */
object FilesReadCheck {
    private val LINE = Regex("""(?im)(?:^[\s>*_`#.\d)-]*|(?<=[\s.;,|(*_`]))files[ _-]read[\s*_`]*(?:\([^)\r\n]*\))?[\s*_`]*:[\s*_`]*(\S[^\r\n]*)$""")
    private val REPORTISH = Regex("""(?i)\b(sha|commit(ted)?)\b""")

    const val REFUSAL = "report refused: it has no 'Files read: <paths>' line. Add that line (the files you read, " +
        "or 'Files read: none') and send it again."

    /** The paths on the report's `Files read:` line, or null when it has none. */
    fun extract(body: String): String? = LINE.find(body)?.groupValues?.get(1)?.trim()?.takeIf { it.isNotEmpty() }

    /** A message is a done report when its ticket is done or it names a commit / sha (a question to the starter is not). */
    fun isReport(body: String, ticketDone: Boolean): Boolean = ticketDone || REPORTISH.containsMatchIn(body)

    /** The refusal for [body], or null when it is accepted (not a report, or carries the line). */
    fun refusal(body: String, ticketDone: Boolean): String? =
        if (isReport(body, ticketDone) && extract(body) == null) REFUSAL else null
}
